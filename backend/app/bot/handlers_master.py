import html
import logging
import random
from datetime import datetime, timedelta, timezone
from typing import Optional
import pytz

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message, InlineKeyboardMarkup, InlineKeyboardButton, URLInputFile
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from app.bot.fsm import AdminSettingStates
from app.bot.keyboards import get_admin_main_keyboard, get_master_requests_keyboard
from app.config import settings
from app.database import AsyncSessionLocal
from app.models import (
    Appointment,
    Client,
    Device,
    Master,
    Review,
    StudioSetting,
    WaitingList,
    utc_now,
)

logger = logging.getLogger(__name__)
master_router = Router(name="master_router")


def get_kyiv_today() -> str:
    kyiv_tz = pytz.timezone(settings.TIMEZONE)
    return datetime.now(kyiv_tz).strftime("%Y-%m-%d")


# 1. /login - Master Link & 6-digit OTP for Android
@master_router.message(Command("login"))
async def cmd_login(message: Message) -> None:
    chat_id = message.from_user.id if message.from_user else 0
    username = message.from_user.full_name if message.from_user else "Майстер"

    async with AsyncSessionLocal() as db:
        # Check if master exists
        stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        res = await db.execute(stmt)
        master = res.scalar_one_or_none()

        if not master:
            # Check if any master exists in DB
            all_masters = (await db.execute(select(Master))).scalars().all()
            if not all_masters:
                # Seed default owner
                master = Master(
                    id="m-01",
                    name=settings.DEFAULT_OWNER_NAME,
                    phone=settings.DEFAULT_OWNER_PHONE,
                    telegram_chat_id=chat_id,
                    role="owner",
                    specialization="Топ-Майстер / Власниця",
                    is_active=True
                )
                db.add(master)
                await db.flush()
            else:
                # Link unassigned master by phone or link to first owner without chat_id
                first_owner = next((m for m in all_masters if m.role == "owner" and not m.telegram_chat_id), None)
                if first_owner:
                    master = first_owner
                    master.telegram_chat_id = chat_id
                    master.mark_updated()
                else:
                    # Create new master profile
                    master = Master(
                        name=username,
                        phone=settings.DEFAULT_OWNER_PHONE,
                        telegram_chat_id=chat_id,
                        role="master",
                        is_active=True
                    )
                    db.add(master)
                    await db.flush()

        # Generate 6-digit OTP code (TTL 15 min)
        otp = f"{random.randint(100000, 999999)}"
        expires_at = utc_now() + timedelta(minutes=15)

        device = Device(
            master_id=master.id,
            device_token=f"pending_otp_{otp}",
            device_name="Android Client",
            otp_code=otp,
            otp_expires_at=expires_at,
            is_active=False
        )
        db.add(device)
        await db.commit()

    text = (
        f"🔐 <b>Авторизація майстра: {html.escape(master.name)}</b>\n\n"
        f"Ваш одноразовий 6-значний код для входу в Android-додаток:\n"
        f"👉 <code>{otp}</code> 👈\n\n"
        f"⏳ Код дійсний <b>15 хвилин</b>.\n"
        f"Відкрийте JuNailCRM на вашому Android-пристрої та введіть цей код для синхронізації."
    )
    await message.answer(text, parse_mode="HTML")


# 2. /today - Today's Schedule with Kyiv Timezone & Badge
@master_router.message(Command("today"))
async def cmd_today(message: Message) -> None:
    chat_id = message.from_user.id if message.from_user else 0
    today_str = get_kyiv_today()

    async with AsyncSessionLocal() as db:
        m_stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        master = (await db.execute(m_stmt)).scalar_one_or_none()

        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.client), selectinload(Appointment.service))
            .where(
                Appointment.date == today_str,
                Appointment.deleted.is_(False),
                Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"])
            )
        )
        if master and master.role != "owner":
            stmt = stmt.where(Appointment.master_id == master.id)
        stmt = stmt.order_by(Appointment.start_time.asc())

        apts = (await db.execute(stmt)).scalars().all()

        if not apts:
            await message.answer(f"☕️ <b>Розклад на сьогодні ({today_str}):</b>\n\nЗаписів немає! Можна відпочити або зайнятися складом.", parse_mode="HTML")
            return

        lines = [f"📅 <b>Розклад на сьогодні ({today_str}):</b>\n"]
        for idx, a in enumerate(apts, 1):
            c_name = a.client.first_name if a.client else "Клієнт"
            c_phone = a.client.phone if a.client else "немає тел."
            s_title = a.service.title if a.service else "Послуга"
            visits_since_rem = a.client.visits_since_last_removal if a.client else 0

            badge = ""
            if visits_since_rem >= 3:
                badge = "\n   ⚠️ <b>Пора повного зняття покриття!</b> (вже 3+ візити без зняття)"

            lines.append(
                f"<b>{idx}. {html.escape(a.start_time)} - {html.escape(a.end_time)}</b> — {html.escape(s_title)}\n"
                f"   👤 {html.escape(c_name)} ({html.escape(c_phone)})\n"
                f"   💰 {int(a.total_price)} грн | Статус: <i>{a.status}</i>{badge}\n"
            )

        await message.answer("\n".join(lines), parse_mode="HTML")


# 3. /requests - Pending appointments list
@master_router.message(Command("requests"))
async def cmd_requests(message: Message) -> None:
    chat_id = message.from_user.id if message.from_user else 0
    today_str = get_kyiv_today()

    async with AsyncSessionLocal() as db:
        m_stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        master = (await db.execute(m_stmt)).scalar_one_or_none()

        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.client), selectinload(Appointment.service))
            .where(
                Appointment.status == "PENDING",
                Appointment.date >= today_str,
                Appointment.deleted.is_(False)
            )
        )
        if master and master.role != "owner":
            stmt = stmt.where(Appointment.master_id == master.id)
        stmt = stmt.order_by(Appointment.date.asc(), Appointment.start_time.asc())

        pending_apts = (await db.execute(stmt)).scalars().all()

        if not pending_apts:
            await message.answer("🎉 Немає нових запитів на підтвердження.", parse_mode="HTML")
            return

        for a in pending_apts:
            c_name = a.client.first_name if a.client else "Клієнт"
            c_phone = a.client.phone if a.client else ""
            s_title = a.service.title if a.service else "Послуга"
            card = (
                f"⏳ <b>Запит на підтвердження:</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(c_name)}</b> ({html.escape(c_phone)})\n"
                f"📅 Дата: <b>{a.date}</b> ({a.start_time})\n"
                f"💅 Послуга: <b>{html.escape(s_title)}</b>\n"
                f"💰 Сума: <b>{int(a.total_price)} грн</b> (Завдаток: {int(a.deposit_amount)} грн)"
            )
            await message.answer(card, reply_markup=get_master_requests_keyboard(a.id), parse_mode="HTML")


async def ensure_master_linked(db, chat_id: int, username: str = "Майстер") -> Optional[Master]:
    """If chat_id matches MASTER_CHAT_ID or owner/first master without chat_id, auto-link."""
    if not chat_id:
        return None
    stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
    master = (await db.execute(stmt)).scalar_one_or_none()
    if master:
        return master

    env_chat_ids = [cid.strip() for cid in settings.MASTER_CHAT_ID.split(",") if cid.strip()]
    if str(chat_id) in env_chat_ids or not env_chat_ids:
        all_masters = (await db.execute(select(Master).where(Master.deleted.is_(False)))).scalars().all()
        target = next((m for m in all_masters if m.role == "owner" and not m.telegram_chat_id), None)
        if not target and all_masters:
            target = next((m for m in all_masters if not m.telegram_chat_id), all_masters[0])
        if target:
            target.telegram_chat_id = chat_id
            target.mark_updated()
            await db.commit()
            return target
    return None


# 4. Master Request Callback (Accept/Reject: m_req / m_apt)
@master_router.callback_query(F.data.startswith("m_req:") | F.data.startswith("m_apt:"))
async def process_master_request_action(callback: CallbackQuery) -> None:
    parts = callback.data.split(":")
    decision = parts[1]  # "ok", "confirm" or "no", "reject"
    apt_id = parts[2]
    is_confirm = decision in ["ok", "confirm"]

    async with AsyncSessionLocal() as db:
        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.client), selectinload(Appointment.service))
            .where(Appointment.id == apt_id, Appointment.deleted.is_(False))
        )
        apt = (await db.execute(stmt)).scalar_one_or_none()
        if not apt:
            await callback.answer("Запис не знайдено.", show_alert=True)
            return

        c_name = apt.client.first_name if apt.client else "Клієнт"
        c_phone = apt.client.phone if apt.client else ""
        s_title = apt.service.title if apt.service else "Послуга"
        from app.bot.bot_instance import bot

        if is_confirm:
            apt.status = "CONFIRMED"
            apt.version += 1
            apt.mark_updated()
            await db.commit()

            if bot and apt.client and apt.client.telegram_id:
                try:
                    await bot.send_message(
                        chat_id=apt.client.telegram_id,
                        text=f"✅ <b>Ваш запис на {apt.date} о {apt.start_time} підтверджено майстром!</b>\nЧекаємо на вас у студії. 💅",
                        parse_mode="HTML"
                    )
                except Exception as e:
                    logger.warning("Could not notify client: %s", e)

            await callback.message.edit_text(
                f"✅ <b>Запис підтверджено!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(c_name)}</b> ({html.escape(c_phone)})\n"
                f"📅 Дата: <b>{apt.date}</b> ({apt.start_time})\n"
                f"💅 Послуга: <b>{html.escape(s_title)}</b>\n"
                f"💰 Сума: <b>{int(apt.total_price)} грн</b>",
                parse_mode="HTML"
            )
            await callback.answer("✅ Запис підтверджено!")

        else:
            apt.status = "CANCELLED_BY_MASTER"
            apt.cancellation_reason = "Відхилено майстром через Telegram"
            apt.version += 1
            apt.mark_updated()
            target_date = apt.date
            await db.commit()

            if bot and apt.client and apt.client.telegram_id:
                try:
                    await bot.send_message(
                        chat_id=apt.client.telegram_id,
                        text=f"❌ <b>На жаль, майстер не зміг підтвердити запис на {apt.date} о {apt.start_time}.</b>\nБудь ласка, оберіть інший час або послугу через /start.",
                        parse_mode="HTML"
                    )
                except Exception as e:
                    logger.warning("Could not notify client: %s", e)

            # Notify Waiting list
            from app.api.legacy_router import notify_waiting_list_clients
            await notify_waiting_list_clients(db, target_date, s_title)

            await callback.message.edit_text(
                f"❌ <b>Запис відхилено!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(c_name)}</b>\n"
                f"📅 Дата: <b>{apt.date}</b> ({apt.start_time})\n"
                f"💅 Послуга: <b>{html.escape(s_title)}</b>",
                parse_mode="HTML"
            )
            await callback.answer("❌ Запис відхилено.")



# 5. Master Receipt Approval (m_pay:ok / m_pay:no)
@master_router.callback_query(F.data.startswith("m_pay:"))
async def process_master_pay_action(callback: CallbackQuery) -> None:
    parts = callback.data.split(":")
    decision = parts[1]
    apt_id = parts[2]

    async with AsyncSessionLocal() as db:
        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.client))
            .where(Appointment.id == apt_id, Appointment.deleted.is_(False))
        )
        apt = (await db.execute(stmt)).scalar_one_or_none()
        if not apt:
            await callback.answer("Запис не знайдено.", show_alert=True)
            return

        from app.bot.bot_instance import bot

        if decision == "ok":
            apt.is_deposit_paid = True
            apt.status = "CONFIRMED"
            apt.mark_updated()
            await db.commit()

            if bot and apt.client and apt.client.telegram_id:
                try:
                    await bot.send_message(
                        chat_id=apt.client.telegram_id,
                        text=f"✅ <b>Оплату завдатку підтверджено!</b>\nВаш візит на {apt.date} о {apt.start_time} остаточно заброньовано. 💅",
                        parse_mode="HTML"
                    )
                except Exception as e:
                    logger.warning("Could not notify client about deposit confirmation: %s", e)

            await callback.message.edit_caption(caption=f"✅ <b>Завдаток зараховано!</b> Запис {apt.id} підтверджено.", parse_mode="HTML")

        elif decision == "no":
            if bot and apt.client and apt.client.telegram_id:
                try:
                    await bot.send_message(
                        chat_id=apt.client.telegram_id,
                        text="❌ <b>На жаль, надісланий чек не підтверджено.</b> Будь ласка, перевірте платіж або зв'яжіться з майстром.",
                        parse_mode="HTML"
                    )
                except Exception as e:
                    logger.warning("Could not notify client about rejected deposit: %s", e)

            await callback.message.edit_caption(caption=f"❌ <b>Чек відхилено.</b>", parse_mode="HTML")

    await callback.answer()


# 6. /admin and /settings - Studio Control Panel
@master_router.message(Command("admin"))
@master_router.message(Command("settings"))
async def cmd_admin_panel(message: Message) -> None:
    chat_id = message.from_user.id if message.from_user else 0

    async with AsyncSessionLocal() as db:
        m_stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        master = (await db.execute(m_stmt)).scalar_one_or_none()
        if not master or master.role != "owner":
            await message.answer("🔒 Ця команда доступна лише власниці студії.", parse_mode="HTML")
            return

        s_res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        st = {item.key: item.value for item in s_res.scalars().all()}

        mode = st.get("app_mode", "SOLO")
        dep_req = st.get("deposit_required", "false")
        dep_amt = st.get("deposit_amount", "200")
        card = st.get("card_details", "Не вказано")
        jar = st.get("jar_link", "Не вказано")
        addr = st.get("address", "Не вказано")

        info = (
            f"⚙️ <b>Панель керування MasterCRM v2.0:</b>\n\n"
            f"▫️ <b>Режим:</b> {mode}\n"
            f"▫️ <b>Завдаток обов'язковий:</b> {dep_req}\n"
            f"▫️ <b>Сума завдатку:</b> {dep_amt} грн\n"
            f"▫️ <b>Картка:</b> <code>{html.escape(card)}</code>\n"
            f"▫️ <b>Банка:</b> {html.escape(jar)}\n"
            f"▫️ <b>Адреса:</b> {html.escape(addr)}\n\n"
            f"<i>Оберіть параметр для зміни:</i>"
        )
        await message.answer(info, reply_markup=get_admin_main_keyboard(), parse_mode="HTML")


@master_router.callback_query(F.data == "adm:toggle_mode")
async def toggle_app_mode(callback: CallbackQuery) -> None:
    async with AsyncSessionLocal() as db:
        res = await db.execute(select(StudioSetting).where(StudioSetting.key == "app_mode"))
        setting = res.scalar_one_or_none()
        if not setting:
            setting = StudioSetting(key="app_mode", value="STUDIO")
            db.add(setting)
        else:
            setting.value = "STUDIO" if setting.value == "SOLO" else "SOLO"
            setting.mark_updated()
        await db.commit()
    await callback.answer(f"Режим змінено на {setting.value}!", show_alert=True)
    await cmd_admin_panel(callback.message)


@master_router.callback_query(F.data == "adm:toggle_deposit")
async def toggle_deposit(callback: CallbackQuery) -> None:
    async with AsyncSessionLocal() as db:
        res = await db.execute(select(StudioSetting).where(StudioSetting.key == "deposit_required"))
        setting = res.scalar_one_or_none()
        if not setting:
            setting = StudioSetting(key="deposit_required", value="true")
            db.add(setting)
        else:
            setting.value = "false" if setting.value.lower() == "true" else "true"
            setting.mark_updated()
        await db.commit()
    await callback.answer(f"Вимогу завдатку встановлено в {setting.value}!", show_alert=True)
    await cmd_admin_panel(callback.message)


@master_router.callback_query(F.data.startswith("adm:set_"))
async def prompt_setting_value(callback: CallbackQuery, state: FSMContext) -> None:
    setting_key = callback.data.replace("adm:set_", "")
    mapping = {
        "deposit_amount": ("Сума завдатку (грн)", "Введіть число, наприклад: 200"),
        "card": ("Реквізити банківської картки", "Введіть номер картки та ПІБ отримувача"),
        "jar": ("Посилання на банку Monobank", "Введіть посилання, наприклад: https://send.monobank.ua/jar/..."),
        "address": ("Адреса студії", "Введіть адресу студії"),
        "insta": ("Instagram профіль", "Введіть нікнейм або лінк на Instagram"),
    }
    field_title, prompt = mapping.get(setting_key, ("Параметр", "Введіть значення"))
    db_keys = {
        "deposit_amount": "deposit_amount",
        "card": "card_details",
        "jar": "jar_link",
        "address": "address",
        "insta": "instagram",
    }
    await state.update_data(target_key=db_keys.get(setting_key, setting_key))
    await state.set_state(AdminSettingStates.waiting_for_value)
    await callback.message.answer(f"✍️ <b>{field_title}:</b>\n{prompt}:", parse_mode="HTML")
    await callback.answer()


@master_router.message(AdminSettingStates.waiting_for_value, F.text)
async def save_setting_value(message: Message, state: FSMContext) -> None:
    val = message.text.strip()
    data = await state.get_data()
    key = data.get("target_key")

    if key:
        async with AsyncSessionLocal() as db:
            res = await db.execute(select(StudioSetting).where(StudioSetting.key == key))
            setting = res.scalar_one_or_none()
            if not setting:
                setting = StudioSetting(key=key, value=val)
                db.add(setting)
            else:
                setting.value = val
                setting.mark_updated()
            await db.commit()

    await state.clear()
    await message.answer("✅ <b>Налаштування збережено!</b>", parse_mode="HTML")
    await cmd_admin_panel(message)


# 9. /reviews - Master & Owner Review Management
@master_router.message(Command("reviews"))
async def cmd_master_reviews(message: Message) -> None:
    chat_id = message.from_user.id if message.from_user else 0
    async with AsyncSessionLocal() as db:
        m_res = await db.execute(
            select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        )
        master = m_res.scalar_one_or_none()
        if not master:
            await message.answer("❌ Ця команда доступна лише для зареєстрованих майстрів студії.")
            return

        revs_stmt = (
            select(Review)
            .where(Review.deleted.is_(False))
            .order_by(Review.created_at.desc())
            .limit(10)
        )
        revs = (await db.execute(revs_stmt)).scalars().all()

        if not revs:
            await message.answer("⭐ <b>Поки немає відгуків від клієнтів.</b>", parse_mode="HTML")
            return

        await message.answer(f"⭐ <b>Останні {len(revs)} відгуків клієнтів:</b>", parse_mode="HTML")
        for r in revs:
            c_name = r.client_name or "Клієнт"
            s_title = r.service_title or "Послуга"
            t_badge = "💅 [10 хв після візиту]" if r.review_type == "POST_VISIT" else "✨ [2 тижні (носка)]"
            stars = "⭐" * r.rating
            pub_status = "🟢 Опубліковано" if r.is_published else "⚪ Приховано"

            text = (
                f"👤 <b>{html.escape(c_name)}</b> {t_badge}\n"
                f"💅 <i>{html.escape(s_title)}</i> | {stars} ({r.rating}/5)\n"
                f"💬 «{html.escape(r.comment or 'Без коментаря')}»\n"
                f"Статус: <b>{pub_status}</b>"
            )

            row: list[InlineKeyboardButton] = []
            if r.tg_file_id:
                row.append(InlineKeyboardButton(text="📸 Переглянути фото", callback_data=f"rev_pic:{r.id}"))
            toggle_label = "Приховати ❌" if r.is_published else "Опублікувати ✅"
            row.append(InlineKeyboardButton(text=toggle_label, callback_data=f"rev_mod:{r.id}"))

            kb = InlineKeyboardMarkup(inline_keyboard=[row])
            await message.answer(text, reply_markup=kb, parse_mode="HTML")


@master_router.callback_query(F.data.startswith("rev_mod:"))
async def process_master_review_mod(callback: CallbackQuery) -> None:
    rev_id = callback.data.split(":")[1]
    async with AsyncSessionLocal() as db:
        rev = await db.get(Review, rev_id)
        if not rev:
            await callback.answer("Відгук не знайдено.", show_alert=True)
            return

        rev.is_published = not rev.is_published
        rev.moderation_status = "APPROVED" if rev.is_published else "REJECTED"
        rev.mark_updated()
        await db.commit()

        status_text = "опубліковано" if rev.is_published else "приховано"
        await callback.answer(f"Відгук {status_text}!", show_alert=True)

        c_name = rev.client_name or "Клієнт"
        s_title = rev.service_title or "Послуга"
        t_badge = "💅 [10 хв після візиту]" if rev.review_type == "POST_VISIT" else "✨ [2 тижні (носка)]"
        stars = "⭐" * rev.rating
        pub_status = "🟢 Опубліковано" if rev.is_published else "⚪ Приховано"

        text = (
            f"👤 <b>{html.escape(c_name)}</b> {t_badge}\n"
            f"💅 <i>{html.escape(s_title)}</i> | {stars} ({rev.rating}/5)\n"
            f"💬 «{html.escape(rev.comment or 'Без коментаря')}»\n"
            f"Статус: <b>{pub_status}</b>"
        )
        row: list[InlineKeyboardButton] = []
        if rev.tg_file_id:
            row.append(InlineKeyboardButton(text="📸 Переглянути фото", callback_data=f"rev_pic:{rev.id}"))
        toggle_label = "Приховати ❌" if rev.is_published else "Опублікувати ✅"
        row.append(InlineKeyboardButton(text=toggle_label, callback_data=f"rev_mod:{rev.id}"))

        try:
            await callback.message.edit_text(text, reply_markup=InlineKeyboardMarkup(inline_keyboard=[row]), parse_mode="HTML")
        except Exception:
            pass


APK_DOWNLOAD_URL = "https://github.com/dimonenin22-tech/mastercrm/releases/download/v2.4.4/JuNailCRM.apk"
APK_GITHUB_RELEASE_URL = "https://github.com/dimonenin22-tech/mastercrm/releases/tag/v2.4.4"


async def send_app_apk(bot, chat_id: int) -> None:
    await bot.send_chat_action(chat_id=chat_id, action="upload_document")
    doc = URLInputFile(APK_DOWNLOAD_URL, filename="JuNailCRM_v2.4.4.apk")
    caption = (
        "📱 <b>JuNailCRM v2.4.4 — Мобільний застосунок майстра</b>\n\n"
        "▫️ <b>Версія:</b> 2.4.4 (Збірка 244)\n"
        "▫️ <b>Оновлено:</b> 21 жовтня 2026\n"
        "▫️ <b>Особливості версії:</b>\n"
        "   • 📜 Вільне прокручування розкладу під кнопку «+» (без перекриття карток)\n"
        "   • 🔔 Надійні push-сповіщення зі звуком і вібрацією у фоні (WakeLock)\n"
        "   • 🕒 Повне приховання минулих слотів на сьогодні\n"
        "   • 🔋 Незнищенний 24/7 фоновий сервіс синхронізації (Foreground Service)\n"
        "   • ⚡️ 1-клік дозвіл роботи без обмежень батареї Android\n"
        "   • 📸 Фотофіксація чеків та робіт до/після\n\n"
        "👇 <i>Натисніть на файл нижче для миттєвого завантаження та встановлення:</i>"
    )
    kb = InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(
                    text="🌐 Реліз на GitHub",
                    url=APK_GITHUB_RELEASE_URL,
                )
            ]
        ]
    )
    await bot.send_document(chat_id=chat_id, document=doc, caption=caption, reply_markup=kb, parse_mode="HTML")


@master_router.message(Command("app"))
@master_router.message(Command("apk"))
@master_router.message(Command("download"))
async def cmd_download_app(message: Message) -> None:
    chat_id = message.from_user.id if message.from_user else 0

    async with AsyncSessionLocal() as db:
        m_stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        master = (await db.execute(m_stmt)).scalar_one_or_none()
        is_owner_id = bool(settings.MASTER_CHAT_ID and str(chat_id) == str(settings.MASTER_CHAT_ID))

        if not master and not is_owner_id:
            await message.answer(
                "🔒 Ця команда доступна лише майстрам студії.\n"
                "Якщо ви майстер, виконайте спочатку <code>/login</code> для авторизації.",
                parse_mode="HTML"
            )
            return

    await send_app_apk(message.bot, chat_id)


@master_router.callback_query(F.data == "adm:get_app")
async def process_admin_get_app(callback: CallbackQuery) -> None:
    chat_id = callback.from_user.id if callback.from_user else 0

    async with AsyncSessionLocal() as db:
        m_stmt = select(Master).where(Master.telegram_chat_id == chat_id, Master.deleted.is_(False))
        master = (await db.execute(m_stmt)).scalar_one_or_none()
        is_owner_id = bool(settings.MASTER_CHAT_ID and str(chat_id) == str(settings.MASTER_CHAT_ID))

        if not master and not is_owner_id:
            await callback.answer("🔒 Доступно лише майстрам та власниці студії.", show_alert=True)
            return

    await callback.answer("⏳ Відправляємо файл застосунку...")
    await send_app_apk(callback.bot, chat_id)

