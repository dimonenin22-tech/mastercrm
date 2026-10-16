import html
import logging
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import pytz

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from app.bot.bot_instance import bot
from app.bot.keyboards import get_review_rating_keyboard
from app.config import settings
from app.database import AsyncSessionLocal
from app.models import (
    Appointment,
    Client,
    NotificationLog,
    Service,
    WaitingList,
    utc_now,
)

logger = logging.getLogger(__name__)
scheduler = AsyncIOScheduler(timezone=pytz.timezone(settings.TIMEZONE))


def get_kyiv_now() -> datetime:
    tz = pytz.timezone(settings.TIMEZONE)
    return datetime.now(tz)


async def check_deposit_timeouts() -> None:
    """Cancels PENDING appointments where deposit_amount > 0, is_deposit_paid == False, and created_at > 60 min ago."""
    logger.info("Running check_deposit_timeouts job...")
    cutoff = utc_now() - timedelta(minutes=60)

    async with AsyncSessionLocal() as db:
        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.client), selectinload(Appointment.service))
            .where(
                Appointment.status == "PENDING",
                Appointment.deposit_amount > Decimal("0.00"),
                Appointment.is_deposit_paid.is_(False),
                Appointment.created_at <= cutoff,
                Appointment.deleted.is_(False)
            )
        )
        res = await db.execute(stmt)
        expired_apts = res.scalars().all()

        for apt in expired_apts:
            apt.status = "CANCELLED_BY_MASTER"
            apt.cancellation_reason = "Автоматичне скасування: час оплати завдатку (60 хв) вичерпано"
            apt.mark_updated()

            # Log notification
            if apt.client and apt.client.telegram_id:
                log = NotificationLog(
                    appointment_id=apt.id,
                    recipient_tg_id=apt.client.telegram_id,
                    reminder_type="deposit_timeout",
                    status="SUCCESS"
                )
                db.add(log)

                if bot:
                    msg = (
                        f"⏳ <b>Час очікування завдатку вичерпано.</b>\n\n"
                        f"Ваш запис на <b>{apt.date} о {apt.start_time}</b> було скасовано, "
                        f"оскільки чек про оплату не надійшов протягом 60 хвилин.\n"
                        f"Ви можете обрати новий зручний час через /start."
                    )
                    try:
                        await bot.send_message(chat_id=apt.client.telegram_id, text=msg, parse_mode="HTML")
                    except Exception as e:
                        logger.warning("Failed to notify client about deposit timeout: %s", e)
                        log.status = "FAILED"

            # Broadcast freed slot to WaitingList
            s_title = apt.service.title if apt.service else "Процедура"
            from app.api.legacy_router import notify_waiting_list_clients
            await notify_waiting_list_clients(db, apt.date, s_title)

        await db.commit()


async def send_24h_and_2h_reminders() -> None:
    """Sends 24-hour and 2-hour appointment reminders idempotently."""
    logger.info("Running send_24h_and_2h_reminders job...")
    kyiv_now = get_kyiv_now()

    async with AsyncSessionLocal() as db:
        # Fetch confirmed appointments for today and tomorrow
        date_today = kyiv_now.strftime("%Y-%m-%d")
        date_tomorrow = (kyiv_now + timedelta(days=1)).strftime("%Y-%m-%d")

        stmt = (
            select(Appointment)
            .options(
                selectinload(Appointment.client),
                selectinload(Appointment.service),
                selectinload(Appointment.master),
                selectinload(Appointment.notifications)
            )
            .where(
                Appointment.date.in_([date_today, date_tomorrow]),
                Appointment.status == "CONFIRMED",
                Appointment.deleted.is_(False),
                Appointment.start_time != "За домовленістю"
            )
        )
        apts = (await db.execute(stmt)).scalars().all()

        for apt in apts:
            if not apt.client or not apt.client.telegram_id:
                continue

            try:
                parts = apt.start_time.split(":")
                apt_hour, apt_minute = int(parts[0]), int(parts[1])
                apt_date_dt = datetime.strptime(apt.date, "%Y-%m-%d").date()
                apt_kyiv_dt = pytz.timezone(settings.TIMEZONE).localize(
                    datetime(apt_date_dt.year, apt_date_dt.month, apt_date_dt.day, apt_hour, apt_minute)
                )
            except Exception:
                continue

            time_diff = apt_kyiv_dt - kyiv_now
            s_title = apt.service.title if apt.service else "процедуру"

            # Check sent logs
            already_sent = {n.reminder_type for n in apt.notifications if n.status == "SUCCESS"}

            # 24h reminder (between 23h and 25h)
            if timedelta(hours=23) <= time_diff <= timedelta(hours=25) and "24h" not in already_sent:
                advice = ""
                if apt.client.visits_since_last_removal >= 3:
                    advice = (
                        "\n\n💡 <b>Порада майстра:</b> У вас це вже 3-й або більше візит поспіль без повного зняття матеріалу. "
                        "Рекомендуємо сьогодні зробити повне зняття, щоб дати нігтьовій пластині оновитися і зберегти здоров'я нігтів! 💅"
                    )

                text = (
                    f"🔔 <b>Нагадування про візит завтра!</b>\n\n"
                    f"Чекаємо на вас: <b>{apt.date} о {apt.start_time}</b>\n"
                    f"💅 Послуга: <b>{html.escape(s_title)}</b>{advice}\n\n"
                    f"Якщо ваші плани змінилися, будь ласка, попередьте заздалегідь через меню /start."
                )

                log = NotificationLog(
                    appointment_id=apt.id,
                    recipient_tg_id=apt.client.telegram_id,
                    reminder_type="24h",
                    status="SUCCESS"
                )
                db.add(log)
                if bot:
                    try:
                        await bot.send_message(chat_id=apt.client.telegram_id, text=text, parse_mode="HTML")
                    except Exception as e:
                        logger.warning("Failed to send 24h reminder: %s", e)
                        log.status = "FAILED"

            # 2h reminder (between 1h 45m and 2h 15m)
            if timedelta(minutes=105) <= time_diff <= timedelta(minutes=135) and "2h" not in already_sent:
                text = (
                    f"⏰ <b>Нагадуємо: до вашого візиту залишилося 2 години!</b>\n\n"
                    f"Сьогодні о <b>{apt.start_time}</b> чекаємо на вас на «{html.escape(s_title)}».\n"
                    f"Гарного дня та до зустрічі! ☕️✨"
                )

                log = NotificationLog(
                    appointment_id=apt.id,
                    recipient_tg_id=apt.client.telegram_id,
                    reminder_type="2h",
                    status="SUCCESS"
                )
                db.add(log)
                if bot:
                    try:
                        await bot.send_message(chat_id=apt.client.telegram_id, text=text, parse_mode="HTML")
                    except Exception as e:
                        logger.warning("Failed to send 2h reminder: %s", e)
                        log.status = "FAILED"

        await db.commit()


async def send_post_visit_and_14day_checks() -> None:
    """Sends review prompt 10 min after COMPLETED visit and 14-day wear check strictly 10:00-20:00."""
    logger.info("Running send_post_visit_and_14day_checks job...")
    kyiv_now = get_kyiv_now()

    async with AsyncSessionLocal() as db:
        # 1. 10-Minute Post-Visit Review Prompt
        cutoff_recent = utc_now() - timedelta(minutes=10)
        cutoff_window = utc_now() - timedelta(hours=4)

        stmt_comp = (
            select(Appointment)
            .options(selectinload(Appointment.client), selectinload(Appointment.notifications))
            .where(
                Appointment.status == "COMPLETED",
                Appointment.updated_at <= cutoff_recent,
                Appointment.updated_at >= cutoff_window,
                Appointment.deleted.is_(False)
            )
        )
        completed_apts = (await db.execute(stmt_comp)).scalars().all()

        for apt in completed_apts:
            if not apt.client or not apt.client.telegram_id:
                continue

            already_sent = {n.reminder_type for n in apt.notifications if n.status == "SUCCESS"}
            if "10min_review" not in already_sent:
                log = NotificationLog(
                    appointment_id=apt.id,
                    recipient_tg_id=apt.client.telegram_id,
                    reminder_type="10min_review",
                    status="SUCCESS"
                )
                db.add(log)
                if bot:
                    msg = (
                        f"💅 <b>Дякуємо за візит до нашої студії!</b>\n\n"
                        f"Будь ласка, оцініть якість послуги та атмосферу від 1 до 5 зірочок:"
                    )
                    try:
                        await bot.send_message(
                            chat_id=apt.client.telegram_id,
                            text=msg,
                            reply_markup=get_review_rating_keyboard(apt.id),
                            parse_mode="HTML"
                        )
                    except Exception as e:
                        logger.warning("Failed to send 10min review prompt: %s", e)
                        log.status = "FAILED"

        # 2. 14-Day Wear Check (Strictly quiet hours 10:00 - 20:00 Europe/Kyiv)
        current_hour = kyiv_now.hour
        if 10 <= current_hour < 20:
            target_date = (kyiv_now - timedelta(days=14)).strftime("%Y-%m-%d")

            stmt_14d = (
                select(Appointment)
                .options(selectinload(Appointment.client), selectinload(Appointment.service), selectinload(Appointment.notifications))
                .where(
                    Appointment.date == target_date,
                    Appointment.status == "COMPLETED",
                    Appointment.deleted.is_(False)
                )
            )
            apts_14d = (await db.execute(stmt_14d)).scalars().all()

            for apt in apts_14d:
                if not apt.client or not apt.client.telegram_id:
                    continue

                already_sent = {n.reminder_type for n in apt.notifications if n.status == "SUCCESS"}
                if "2weeks_review" not in already_sent:
                    # Check if client has future active appointment
                    future_stmt = select(Appointment).where(
                        Appointment.client_id == apt.client_id,
                        Appointment.date > apt.date,
                        Appointment.status.in_(["PENDING", "CONFIRMED"]),
                        Appointment.deleted.is_(False)
                    ).order_by(Appointment.date.asc(), Appointment.start_time.asc())
                    future_res = await db.execute(future_stmt)
                    next_apt = future_res.scalars().first()
                    has_future = next_apt is not None

                    service_title = apt.service.title if apt.service else "манікюр"
                    client_name = apt.client.first_name

                    msg = (
                        f"💅 <b>Привіт, {html.escape(client_name)}!</b>\n\n"
                        f"Минуло 2 тижні з вашого візиту («{html.escape(service_title)}»). "
                        f"Розкажіть, будь ласка, як носиться покриття? "
                        f"Оцініть стан від 1 до 5 ⭐ або надішліть у відповідь текстовий відгук та 📸 фото стану нігтів зараз!"
                    )

                    if not has_future:
                        msg += "\n\n✨ <b>До речі, у вас ще немає нового запису</b> — оберіть зручне віконце завчасно, поки є вільні місця!"
                    else:
                        msg += f"\n\n📅 <b>Нагадуємо:</b> ваш наступний візит вже заплановано на <b>{next_apt.date} о {next_apt.start_time}</b>!"

                    log = NotificationLog(
                        appointment_id=apt.id,
                        recipient_tg_id=apt.client.telegram_id,
                        reminder_type="2weeks_review",
                        status="SUCCESS"
                    )
                    db.add(log)
                    if bot:
                        from app.bot.keyboards import get_wear_check_keyboard
                        try:
                            await bot.send_message(
                                chat_id=apt.client.telegram_id,
                                text=msg,
                                reply_markup=get_wear_check_keyboard(apt.id, has_future),
                                parse_mode="HTML"
                            )
                        except Exception as e:
                            logger.warning("Failed to send 14-day check: %s", e)
                            log.status = "FAILED"

        await db.commit()


def setup_scheduler() -> None:
    scheduler.add_job(check_deposit_timeouts, "interval", minutes=10, id="deposit_timeouts")
    scheduler.add_job(send_24h_and_2h_reminders, "interval", minutes=10, id="reminders_24h_2h")
    scheduler.add_job(send_post_visit_and_14day_checks, "interval", minutes=10, id="post_visit_and_14d")
    scheduler.start()
    logger.info("APScheduler started with 10-minute interval background jobs.")
