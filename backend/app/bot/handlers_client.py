import html
import logging
import os
import random
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any, Optional
import base64
import io
import pytz

from aiogram import F, Router
from aiogram.filters import Command, CommandStart, CommandObject, StateFilter
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message, ReplyKeyboardRemove, InlineKeyboardMarkup, InlineKeyboardButton
from sqlalchemy import select, or_
from sqlalchemy.orm import selectinload

from app.bot.fsm import BookingStates, ReceiptUploadStates, ReviewStates, WaitingListStates
from app.bot.keyboards import (
    create_inline_calendar,
    create_waitlist_calendar,
    get_cancel_appointment_keyboard,
    get_categories_keyboard,
    get_client_main_keyboard,
    get_client_main_inline_keyboard,
    get_contact_request_keyboard,
    get_nav_back_keyboard,
    get_booking_summary_keyboard,
    get_master_notification_keyboard,
    get_master_receipt_approval_keyboard,
    get_review_rating_keyboard,
    get_post_review_stars_keyboard,
    get_services_keyboard,
    get_time_slots_keyboard,
)
from app.config import settings
from app.database import AsyncSessionLocal
from app.models import (
    Appointment,
    Client,
    Device,
    Master,
    Product,
    Review,
    Schedule,
    ScheduleException,
    Service,
    ServiceCategory,
    StudioSetting,
    VisitBrief,
    WaitingList,
    utc_now,
)

logger = logging.getLogger(__name__)
client_router = Router(name="client_router")


def get_kyiv_today() -> str:
    kyiv_tz = pytz.timezone(settings.TIMEZONE)
    return datetime.now(kyiv_tz).strftime("%Y-%m-%d")


def time_to_minutes(t_str: str) -> int:
    try:
        h, m = map(int, t_str.split(":"))
        return h * 60 + m
    except Exception:
        return 0


def calculate_end_time(start_str: str, duration_minutes: int) -> str:
    try:
        parts = start_str.split(":")
        start_min = int(parts[0]) * 60 + int(parts[1])
        end_min = start_min + duration_minutes
        h = end_min // 60
        m = end_min % 60
        return f"{h:02d}:{m:02d}"
    except Exception:
        return "11:00"


# 1. /start and Main Menu
@client_router.message(CommandStart())
async def cmd_start(message: Message, state: FSMContext, command: Optional[CommandObject] = None) -> None:
    await state.clear()

    # Deep-link support: /start join_<master_id>
    if command and command.args and command.args.startswith("join_"):
        master_id = command.args.replace("join_", "").strip()
        chat_id = message.chat.id
        async with AsyncSessionLocal() as db:
            master = await db.get(Master, master_id)
            if not master or master.deleted:
                inv_res = await db.execute(select(StudioSetting).where(StudioSetting.key == f"invite_{master_id}"))
                inv_set = inv_res.scalar_one_or_none()
                if inv_set:
                    master = await db.get(Master, inv_set.value)

            if master and not master.deleted:
                master.telegram_chat_id = chat_id
                master.is_active = True
                master.mark_updated()

                otp_code = f"{random.randint(100000, 999999)}"
                device = Device(
                    master_id=master.id,
                    device_token=f"pending_{uuid.uuid4().hex}",
                    device_name=f"Android ({master.name})",
                    otp_code=otp_code,
                    otp_expires_at=utc_now() + timedelta(days=7),
                    is_active=False
                )
                db.add(device)
                await db.commit()

                text = (
                    f"🎉 Вітаємо, <b>{html.escape(master.name)}</b>! Ваш Telegram-акаунт (ID: <code>{chat_id}</code>) успішно прив'язано до студії!\n\n"
                    f"🔔 Тепер ви будете отримувати сюди сповіщення про всі нові записи.\n\n"
                    f"🔐 Ваш код для входу в додаток <b>JuNailCRM</b>: <code>{otp_code}</code>\n"
                    f"Введіть цей 6-значний код на екрані авторизації в додатку."
                )
                await message.answer(text, parse_mode="HTML")
                return
            else:
                await message.answer("⚠️ Запрошення недійсне або майстра не знайдено.")
                return

    # Deep-link support: /start srv_<service_id>
    if command and command.args and command.args.startswith("srv_"):
        srv_id = command.args.replace("srv_", "").strip()
        async with AsyncSessionLocal() as db:
            srv = await db.get(Service, srv_id)
            if srv and not srv.deleted and srv.is_active:
                await state.update_data(
                    service_id=srv.id,
                    service_title=srv.title,
                    service_price=float(srv.price),
                    service_duration=srv.duration_minutes
                )
                await state.set_state(BookingStates.choosing_date)
                today_str = get_kyiv_today()
                parts = today_str.split("-")
                cal_kb = create_inline_calendar(int(parts[0]), int(parts[1]), today_str)
                await message.answer(
                    f"💅 <b>Обрано послугу: {html.escape(srv.title)}</b> ({int(srv.price)} грн)\n\n"
                    f"Оберіть бажану дату запису в календарі нижче:",
                    reply_markup=cal_kb,
                    parse_mode="HTML"
                )
                return

    first_name = html.escape(message.from_user.first_name if message.from_user else "Клієнт")
    text = (
        f"Привіт, <b>{first_name}</b>! ✨\n\n"
        f"Ласкаво просимо до <b>{html.escape(settings.PROJECT_NAME)}</b> — системи онлайн-запису та краси.\n"
        f"Оберіть потрібний пункт у меню нижче:"
    )
    await message.answer(text, reply_markup=get_client_main_keyboard(), parse_mode="HTML")
    await message.answer("💅 Швидке меню:", reply_markup=get_client_main_inline_keyboard(), parse_mode="HTML")


@client_router.message(Command("myid", "id"))
async def cmd_myid(message: Message) -> None:
    chat_id = message.chat.id
    text = (
        f"🆔 Ваш Telegram ID: <code>{chat_id}</code>\n\n"
        "Натисніть на цифри, щоб скопіювати та вставити в налаштування додатку JuNailCRM."
    )
    await message.answer(text, parse_mode="HTML")


# 2. Main Menu Actions & Callbacks
@client_router.callback_query(F.data == "m:main")
async def cb_main_menu(callback: CallbackQuery, state: FSMContext) -> None:
    await state.clear()
    await callback.answer()
    first_name = html.escape(callback.from_user.first_name if callback.from_user else "Клієнт")
    text = (
        f"Привіт, <b>{first_name}</b>! ✨\n\n"
        f"Ласкаво просимо до <b>{html.escape(settings.PROJECT_NAME)}</b> — системи онлайн-запису та краси.\n"
        f"Оберіть потрібний пункт у меню нижче:"
    )
    try:
        await callback.message.edit_text(text, reply_markup=get_client_main_inline_keyboard(), parse_mode="HTML")
    except Exception:
        await callback.message.answer(text, reply_markup=get_client_main_inline_keyboard(), parse_mode="HTML")


@client_router.callback_query(F.data == "m:book")
async def cb_book(callback: CallbackQuery, state: FSMContext) -> None:
    await state.clear()
    await callback.answer()
    await start_booking_flow(callback.message, state, is_callback=True)


async def _send_price_list(target_message: Message, is_edit: bool = False) -> None:
    async with AsyncSessionLocal() as db:
        res = await db.execute(
            select(ServiceCategory)
            .options(selectinload(ServiceCategory.services))
            .where(ServiceCategory.deleted.is_(False), ServiceCategory.is_active.is_(True))
        )
        categories = res.scalars().all()

        if not categories:
            text = "📋 Прайс-лист наразі оновлюється."
        else:
            lines = ["📋 <b>Наш прайс-лист:</b>\n"]
            for cat in categories:
                active_services = [s for s in cat.services if not s.deleted and s.is_active]
                if active_services:
                    lines.append(f"<b>{html.escape(cat.icon_emoji)} {html.escape(cat.name)}:</b>")
                    for s in active_services:
                        lines.append(f"  • {html.escape(s.title)} — <b>{int(s.price)} ₴</b> ({s.duration_minutes} хв)")
                    lines.append("")
            text = "\n".join(lines).strip()

        if is_edit:
            try:
                await target_message.edit_text(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")
                return
            except Exception:
                pass
        await target_message.answer(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")


@client_router.callback_query(F.data == "m:price")
async def cb_price(callback: CallbackQuery, state: FSMContext) -> None:
    await state.clear()
    await callback.answer()
    await _send_price_list(callback.message, is_edit=True)


@client_router.message(F.text == "📋 Прайс")
async def show_price_list(message: Message, state: FSMContext) -> None:
    await state.clear()
    await _send_price_list(message, is_edit=False)


async def _send_about_info(target_message: Message, is_edit: bool = False) -> None:
    async with AsyncSessionLocal() as db:
        res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        items = {s.key: s.value for s in res.scalars().all()}

        addr = items.get("address", "вул. Хрещатик, 1, м. Київ")
        phone = items.get("phone", "+380 (99) 123-45-67")
        insta = items.get("instagram", "@master_crm_beauty")

        text = (
            f"👩🎨 <b>Про нашу студію:</b>\n\n"
            f"📍 <b>Адреса:</b> {html.escape(addr)}\n"
            f"📞 <b>Телефон:</b> {html.escape(phone)}\n"
            f"📸 <b>Instagram:</b> {html.escape(insta)}\n\n"
            f"Чекаємо на вас затишно та з турботою про ваші нігтики! 💅☕️"
        )
        if is_edit:
            try:
                await target_message.edit_text(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")
                return
            except Exception:
                pass
        await target_message.answer(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")


@client_router.callback_query(F.data == "m:about")
async def cb_about(callback: CallbackQuery, state: FSMContext) -> None:
    await state.clear()
    await callback.answer()
    await _send_about_info(callback.message, is_edit=True)


@client_router.message(F.text == "👩🎨 Про майстра / Адреса")
async def show_about_info(message: Message, state: FSMContext) -> None:
    await state.clear()
    await _send_about_info(message, is_edit=False)


async def _send_client_appointments(target_message: Message, tg_id: int, is_edit: bool = False) -> None:
    async with AsyncSessionLocal() as db:
        c_res = await db.execute(
            select(Client).where(
                or_(Client.telegram_id == tg_id, Client.telegram_id == int(tg_id)),
                Client.deleted.is_(False)
            )
        )
        client = c_res.scalars().first()
        if not client:
            text = "У вас ще немає збережених записів. Записатися можна за кнопкою нижче! 💅"
            if is_edit:
                try:
                    await target_message.edit_text(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")
                    return
                except Exception:
                    pass
            await target_message.answer(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")
            return

        today = get_kyiv_today()
        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.service), selectinload(Appointment.master))
            .where(
                Appointment.client_id == client.id,
                Appointment.date >= today,
                Appointment.deleted.is_(False),
                Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"])
            )
            .order_by(Appointment.date.asc(), Appointment.start_time.asc())
        )
        apts = (await db.execute(stmt)).scalars().all()

        if not apts:
            text = "У вас немає активних майбутніх записів. Записатися можна за кнопкою 💅 <b>Записатися онлайн</b>!"
            if is_edit:
                try:
                    await target_message.edit_text(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")
                    return
                except Exception:
                    pass
            await target_message.answer(text, reply_markup=get_nav_back_keyboard(), parse_mode="HTML")
            return

        status_labels = {
            "PENDING": "⏳ Очікує підтвердження",
            "CONFIRMED": "✅ Підтверджено",
            "COMPLETED": "🎉 Завершено",
        }

        for a in apts:
            s_title = a.service.title if a.service else "Послуга"
            m_name = a.master.name if a.master else "Майстер"
            st_text = status_labels.get(a.status, a.status)
            msg = (
                f"📅 <b>Запис на {html.escape(a.date)}</b>\n"
                f"⏰ Час: <b>{html.escape(a.start_time)} - {html.escape(a.end_time)}</b>\n"
                f"💅 Послуга: <b>{html.escape(s_title)}</b>\n"
                f"👩🎨 Майстер: <b>{html.escape(m_name)}</b>\n"
                f"💰 Вартість: <b>{int(a.total_price)} ₴</b>\n"
                f"📌 Статус: <b>{st_text}</b>"
            )
            kb = InlineKeyboardMarkup(
                inline_keyboard=[
                    [InlineKeyboardButton(text="❌ Скасувати цей запис", callback_data=f"c_can:{a.id}")],
                    [
                        InlineKeyboardButton(text="💅 Записатися ще", callback_data="m:book"),
                        InlineKeyboardButton(text="« Головне меню", callback_data="m:main"),
                    ]
                ]
            )
            await target_message.answer(msg, reply_markup=kb, parse_mode="HTML")


@client_router.callback_query(F.data == "m:my_apts")
async def cb_my_apts(callback: CallbackQuery, state: FSMContext) -> None:
    await state.clear()
    await callback.answer()
    await _send_client_appointments(callback.message, callback.from_user.id if callback.from_user else 0, is_edit=False)


@client_router.message(F.text == "📅 Мої записи")
async def show_client_appointments(message: Message, state: FSMContext) -> None:
    await state.clear()
    await _send_client_appointments(message, message.from_user.id if message.from_user else 0, is_edit=False)


# Interactive Waiting List Flow
async def _send_waitlist_flow(target_message: Message, tg_id: int, is_edit: bool = False) -> None:
    today_str = get_kyiv_today()
    parts = today_str.split("-")
    year, month = int(parts[0]), int(parts[1])

    async with AsyncSessionLocal() as db:
        c_res = await db.execute(
            select(Client).where(
                or_(Client.telegram_id == tg_id, Client.telegram_id == int(tg_id)),
                Client.deleted.is_(False)
            )
        )
        client = c_res.scalars().first()

        active_entries = []
        if client:
            wl_stmt = (
                select(WaitingList)
                .where(
                    WaitingList.client_id == client.id,
                    WaitingList.status == "ACTIVE",
                    WaitingList.deleted.is_(False)
                )
                .order_by(WaitingList.preferred_date.asc())
            )
            active_entries = (await db.execute(wl_stmt)).scalars().all()

        lines = [
            "📝 <b>Лист очікування</b>\n",
            "Оберіть бажану дату в календарі нижче.",
            "Щойно звільниться віконце на цей день — наш бот негайно надішле вам сповіщення!\n"
        ]
        if active_entries:
            lines.append("📌 <b>Ваші активні заявки в листі очікування:</b>")
            for e in active_entries:
                lines.append(f"  • {e.preferred_date}")
            lines.append("")

        text = "\n".join(lines)
        cal_kb = create_waitlist_calendar(year, month, today_str)

        if is_edit:
            try:
                await target_message.edit_text(text, reply_markup=cal_kb, parse_mode="HTML")
                return
            except Exception:
                pass
        await target_message.answer(text, reply_markup=cal_kb, parse_mode="HTML")


@client_router.callback_query(F.data == "m:waitlist")
async def cb_waitlist(callback: CallbackQuery, state: FSMContext) -> None:
    await state.clear()
    await callback.answer()
    await _send_waitlist_flow(callback.message, callback.from_user.id if callback.from_user else 0, is_edit=True)


@client_router.message(F.text == "📝 Лист очікування")
async def msg_waitlist(message: Message, state: FSMContext) -> None:
    await state.clear()
    await _send_waitlist_flow(message, message.from_user.id if message.from_user else 0, is_edit=False)


@client_router.callback_query(F.data.startswith("wl_cal:day:"))
async def cb_waitlist_day(callback: CallbackQuery) -> None:
    chosen_date = callback.data.split(":")[2]
    tg_id = callback.from_user.id if callback.from_user else 0
    first_name = callback.from_user.first_name if callback.from_user else "Клієнт"

    async with AsyncSessionLocal() as db:
        c_res = await db.execute(
            select(Client).where(
                or_(Client.telegram_id == tg_id, Client.telegram_id == int(tg_id)),
                Client.deleted.is_(False)
            )
        )
        client = c_res.scalars().first()
        if not client:
            client = Client(
                telegram_id=tg_id,
                first_name=first_name,
                phone="",
            )
            db.add(client)
            await db.flush()

        wl = WaitingList(
            client_id=client.id,
            master_id="m-01",
            service_id="",
            preferred_date=chosen_date,
            status="ACTIVE"
        )
        db.add(wl)
        await db.commit()

    await callback.answer("✅ Додано в лист очікування!", show_alert=True)
    await callback.message.edit_text(
        f"✅ <b>Ви успішно додані до листа очікування на {chosen_date}!</b>\n\n"
        f"Щойно з'явиться вільне віконце, бот негайно надішле вам персональне повідомлення. 💅",
        reply_markup=get_nav_back_keyboard(),
        parse_mode="HTML"
    )


@client_router.callback_query(F.data.startswith("wl_cal:nav:"))
async def cb_waitlist_nav(callback: CallbackQuery) -> None:
    parts = callback.data.split(":")
    year, month = int(parts[2]), int(parts[3])
    today_str = get_kyiv_today()
    await callback.message.edit_reply_markup(reply_markup=create_waitlist_calendar(year, month, today_str))
    await callback.answer()


@client_router.callback_query(F.data == "wl_cal:blocked")
async def cb_waitlist_blocked(callback: CallbackQuery) -> None:
    await callback.answer("Ця дата вже минула. Будь ласка, оберіть сьогодні або майбутній день.", show_alert=True)


@client_router.callback_query(F.data == "wl_cal:ignore")
async def cb_waitlist_ignore(callback: CallbackQuery) -> None:
    await callback.answer()


@client_router.callback_query(F.data.startswith("wl_del:"))
async def cb_waitlist_del(callback: CallbackQuery) -> None:
    wl_id = callback.data.split(":")[1]
    async with AsyncSessionLocal() as db:
        entry = await db.get(WaitingList, wl_id)
        if entry:
            entry.status = "CANCELLED"
            entry.deleted = True
            entry.mark_updated()
            await db.commit()
    await callback.answer("Запис у листі очікування скасовано.")
    await _send_waitlist_flow(callback.message, callback.from_user.id if callback.from_user else 0, is_edit=True)


# 3. Client Self-Service Cancellation
@client_router.callback_query(F.data.startswith("c_can:"))
async def cancel_by_client_cb(callback: CallbackQuery) -> None:
    apt_id = callback.data.split(":")[1]
    async with AsyncSessionLocal() as db:
        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.master), selectinload(Appointment.service), selectinload(Appointment.client))
            .where(Appointment.id == apt_id, Appointment.deleted.is_(False))
        )
        res = await db.execute(stmt)
        apt = res.scalar_one_or_none()
        if not apt or apt.status in ["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"]:
            await callback.answer("Запис уже скасовано або не знайдено.", show_alert=True)
            return

        apt.status = "CANCELLED_BY_CLIENT"
        apt.cancellation_reason = "Скасовано клієнтом самостійно через Telegram"
        apt.mark_updated()

        target_date = apt.date
        s_title = apt.service.title if apt.service else "Послуга"
        master_chat = apt.master.telegram_chat_id if apt.master else None

        await db.commit()

        # Notify master
        from app.bot.bot_instance import bot
        if bot and master_chat:
            c_name = apt.client.first_name if apt.client else "Клієнт"
            c_phone = apt.client.phone if apt.client else ""
            alert = (
                f"⚠️ <b>Клієнт скасував запис!</b>\n\n"
                f"📅 Дата: <b>{target_date}</b> о {apt.start_time}\n"
                f"💅 Послуга: <b>{html.escape(s_title)}</b>\n"
                f"👤 Клієнт: {html.escape(c_name)} ({html.escape(c_phone)})"
            )
            try:
                await bot.send_message(chat_id=master_chat, text=alert, parse_mode="HTML")
            except Exception as e:
                logger.warning("Could not alert master about cancellation: %s", e)

        # Notify Waiting List
        from app.api.legacy_router import notify_waiting_list_clients
        await notify_waiting_list_clients(db, target_date, s_title)

    await callback.message.edit_text("❌ <b>Ваш запис успішно скасовано.</b> Будемо раді бачити вас іншого разу!", parse_mode="HTML")
    await callback.answer()


# 4. Booking Wizard Flow (FSM)
@client_router.message(F.text == "💅 Записатися онлайн")
async def start_booking_flow(message: Message, state: FSMContext) -> None:
    await state.clear()
    async with AsyncSessionLocal() as db:
        res = await db.execute(select(ServiceCategory).where(ServiceCategory.deleted.is_(False), ServiceCategory.is_active.is_(True)))
        cats = res.scalars().all()
        if not cats:
            await message.answer("Наразі онлайн-запис недоступний (немає активних категорій послуг).", parse_mode="HTML")
            return

        data_cats = [(c.id, c.name, c.icon_emoji) for c in cats]
        await state.set_state(BookingStates.waiting_for_category)
        await message.answer(
            "💅 <b>Крок 1/5: Оберіть категорію послуг:</b>",
            reply_markup=get_categories_keyboard(data_cats),
            parse_mode="HTML"
        )


@client_router.callback_query(BookingStates.waiting_for_category, F.data.startswith("cat:"))
async def process_category_selected(callback: CallbackQuery, state: FSMContext) -> None:
    cat_id = callback.data.split(":")[1]
    await state.update_data(category_id=cat_id)

    async with AsyncSessionLocal() as db:
        res = await db.execute(select(Service).where(Service.category_id == cat_id, Service.deleted.is_(False), Service.is_active.is_(True)))
        services = res.scalars().all()
        if not services:
            await callback.answer("У цій категорії наразі немає доступних послуг.", show_alert=True)
            return

        data_srv = [(s.id, s.title, float(s.price), s.duration_minutes) for s in services]
        await state.set_state(BookingStates.waiting_for_service)
        await callback.message.edit_text(
            "✨ <b>Крок 2/5: Оберіть бажану процедуру:</b>",
            reply_markup=get_services_keyboard(data_srv),
            parse_mode="HTML"
        )
    await callback.answer()


@client_router.callback_query(F.data == "booking:back_cat")
async def back_to_categories(callback: CallbackQuery, state: FSMContext) -> None:
    await start_booking_flow(callback.message, state)
    await callback.answer()


@client_router.callback_query(BookingStates.waiting_for_service, F.data.startswith("srv:"))
async def process_service_selected(callback: CallbackQuery, state: FSMContext) -> None:
    srv_id = callback.data.split(":")[1]
    await state.update_data(service_id=srv_id)

    today_str = get_kyiv_today()
    dt = datetime.strptime(today_str, "%Y-%m-%d")

    await state.set_state(BookingStates.waiting_for_date)
    await callback.message.edit_text(
        "📅 <b>Крок 3/5: Оберіть бажаний день у календарі:</b>",
        reply_markup=create_inline_calendar(dt.year, dt.month, today_str),
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.callback_query(F.data.startswith("cal:nav:"))
async def process_calendar_nav(callback: CallbackQuery) -> None:
    parts = callback.data.split(":")
    year, month = int(parts[2]), int(parts[3])
    today_str = get_kyiv_today()
    await callback.message.edit_reply_markup(reply_markup=create_inline_calendar(year, month, today_str))
    await callback.answer()


@client_router.callback_query(F.data == "cal:blocked")
async def process_calendar_blocked(callback: CallbackQuery) -> None:
    await callback.answer("Ця дата вже минула. Будь ласка, оберіть сьогодні або майбутній день.", show_alert=True)


@client_router.callback_query(F.data == "cal:ignore")
async def process_calendar_ignore(callback: CallbackQuery) -> None:
    await callback.answer()


@client_router.callback_query(BookingStates.waiting_for_date, F.data.startswith("cal:day:"))
async def process_date_selected(callback: CallbackQuery, state: FSMContext) -> None:
    chosen_date = callback.data.split(":")[2]
    await state.update_data(date=chosen_date)

    data = await state.get_data()
    service_id = data.get("service_id")

    async with AsyncSessionLocal() as db:
        srv = await db.get(Service, service_id)
        if not srv:
            await callback.answer("Послугу не знайдено.", show_alert=True)
            return

        # Get active master (first active owner/master)
        m_res = await db.execute(select(Master).where(Master.is_active.is_(True), Master.deleted.is_(False)))
        master = m_res.scalars().first()
        if not master:
            await callback.answer("Наразі немає активних майстрів.", show_alert=True)
            return

        await state.update_data(master_id=master.id)

        # 1. Schedule & ScheduleException check
        exc_stmt = select(ScheduleException).where(
            ScheduleException.master_id == master.id,
            ScheduleException.date == chosen_date,
            ScheduleException.deleted.is_(False)
        )
        exc_res = await db.execute(exc_stmt)
        sched_exc = exc_res.scalar_one_or_none()

        day_start = 9 * 60
        day_end = 19 * 60
        slot_step = 30
        is_day_off = False

        if sched_exc and sched_exc.is_day_off:
            is_day_off = True
        elif sched_exc and sched_exc.start_time and sched_exc.end_time:
            day_start = time_to_minutes(sched_exc.start_time)
            day_end = time_to_minutes(sched_exc.end_time)
        else:
            try:
                date_obj = datetime.strptime(chosen_date, "%Y-%m-%d")
                weekday = date_obj.weekday()
                sched_stmt = select(Schedule).where(
                    Schedule.master_id == master.id,
                    Schedule.day_of_week == weekday,
                    Schedule.deleted.is_(False)
                )
                sched = (await db.execute(sched_stmt)).scalar_one_or_none()
                if sched:
                    if not sched.is_working_day:
                        is_day_off = True
                    else:
                        day_start = time_to_minutes(sched.start_time)
                        day_end = time_to_minutes(sched.end_time)
                        slot_step = sched.slot_step_minutes or 30
            except Exception:
                pass

        free_slots = []
        if not is_day_off:
            stmt = select(Appointment).where(
                Appointment.master_id == master.id,
                Appointment.date == chosen_date,
                Appointment.deleted.is_(False),
                Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"])
            )
            existing = (await db.execute(stmt)).scalars().all()
            occupied = []
            for a in existing:
                if a.start_time != "За домовленістю":
                    occupied.append((time_to_minutes(a.start_time), time_to_minutes(a.end_time)))

            dur = srv.duration_minutes + srv.buffer_minutes
            cur_min = day_start
            while cur_min + srv.duration_minutes <= day_end:
                s_slot = cur_min
                e_slot = cur_min + dur
                conflict = False
                for os, oe in occupied:
                    if s_slot < oe and e_slot > os:
                        conflict = True
                        break
                if not conflict:
                    h = cur_min // 60
                    m = cur_min % 60
                    free_slots.append(f"{h:02d}:{m:02d}")
                cur_min += slot_step

    await state.set_state(BookingStates.waiting_for_time)
    if free_slots:
        text = f"⏰ <b>Крок 4/5: Вільні віконця на {chosen_date}:</b>\nОберіть зручний час:"
        await callback.message.edit_text(text, reply_markup=get_time_slots_keyboard(free_slots), parse_mode="HTML")
    else:
        text = (
            f"😔 На дату <b>{chosen_date}</b> вільних автоматичних віконець немає.\n\n"
            f"Ви можете стати у <b>Лист очікування</b> (ми напишемо, щойно хтось скасує свій візит) "
            f"або обрати опцію <b>Обговорити інший час</b>."
        )
        await callback.message.edit_text(text, reply_markup=get_time_slots_keyboard([]), parse_mode="HTML")
    await callback.answer()


@client_router.callback_query(F.data == "booking:back_date")
async def back_to_date(callback: CallbackQuery, state: FSMContext) -> None:
    today_str = get_kyiv_today()
    dt = datetime.strptime(today_str, "%Y-%m-%d")
    await state.set_state(BookingStates.waiting_for_date)
    await callback.message.edit_text(
        "📅 <b>Крок 3/5: Оберіть день у календарі:</b>",
        reply_markup=create_inline_calendar(dt.year, dt.month, today_str),
        parse_mode="HTML"
    )
    await callback.answer()


async def render_booking_summary(target_message: Message, state: FSMContext, is_callback: bool = False) -> None:
    data = await state.get_data()
    s_title = data.get("service_title", "Послуга")
    s_price = data.get("service_price", 0)
    date_str = data.get("date", "")
    start_time = data.get("start_time", "")
    client_name = data.get("client_name", "Клієнт")
    client_phone = data.get("client_phone", "")

    summary_text = (
        f"📋 <b>Підтвердження запису:</b>\n\n"
        f"👤 <b>Ім'я:</b> {html.escape(client_name)}\n"
        f"📱 <b>Телефон:</b> {html.escape(client_phone)}\n"
        f"💅 <b>Послуга:</b> {html.escape(s_title)}\n"
        f"📅 <b>Дата:</b> {date_str}\n"
        f"⏰ <b>Час:</b> {start_time}\n"
        f"💰 <b>До сплати:</b> {int(s_price)} ₴\n\n"
        f"Будь ласка, перевірте дані та підтвердіть запис:"
    )
    if is_callback:
        try:
            await target_message.edit_text(summary_text, reply_markup=get_booking_summary_keyboard(), parse_mode="HTML")
            return
        except Exception:
            pass
    await target_message.answer(summary_text, reply_markup=get_booking_summary_keyboard(), parse_mode="HTML")


@client_router.callback_query(BookingStates.waiting_for_time, F.data.startswith("time:"))
async def process_time_selected(callback: CallbackQuery, state: FSMContext) -> None:
    slot_choice = callback.data.split(":")[1]

    if slot_choice == "waitlist":
        data = await state.get_data()
        async with AsyncSessionLocal() as db:
            tg_id = callback.from_user.id if callback.from_user else 0
            c_res = await db.execute(
                select(Client).where(
                    or_(Client.telegram_id == tg_id, Client.telegram_id == int(tg_id)),
                    Client.deleted.is_(False)
                )
            )
            client = c_res.scalars().first()
            if not client:
                client = Client(
                    telegram_id=tg_id,
                    first_name=callback.from_user.first_name if callback.from_user else "Клієнт",
                    phone=""
                )
                db.add(client)
                await db.flush()

            wl = WaitingList(
                client_id=client.id,
                master_id=data.get("master_id", "m-01"),
                service_id=data.get("service_id", ""),
                preferred_date=data.get("date", get_kyiv_today()),
                status="ACTIVE"
            )
            db.add(wl)
            await db.commit()

        await state.clear()
        await callback.message.edit_text(
            "✅ <b>Ви в листі очікування!</b> Ми обов'язково надішлемо вам повідомлення, щойно з'явиться віконце на цю дату.",
            reply_markup=get_nav_back_keyboard(),
            parse_mode="HTML"
        )
        await callback.answer()
        return

    if slot_choice == "custom":
        await state.update_data(start_time="За домовленістю")
    else:
        await state.update_data(start_time=slot_choice)

    tg_id = callback.from_user.id if callback.from_user else 0
    async with AsyncSessionLocal() as db:
        c_stmt = select(Client).where(
            or_(Client.telegram_id == tg_id, Client.telegram_id == int(tg_id)),
            Client.deleted.is_(False)
        )
        client = (await db.execute(c_stmt)).scalars().first()

    # SMART RECOGNITION: Returning client with known name and phone
    if client and client.first_name and client.phone:
        await state.update_data(
            client_id=client.id,
            client_name=client.first_name,
            client_phone=client.phone
        )
        await state.set_state(BookingStates.confirming_booking)
        await render_booking_summary(callback.message, state, is_callback=True)
        await callback.answer()
        return

    # First-time client: ask real name
    await state.set_state(BookingStates.waiting_for_name)
    prompt = (
        "👤 <b>Оскільки ви записуєтесь вперше</b>, напишіть, будь ласка, ваше справжнє <b>Ім'я</b> "
        "(як до вас звертатися майстру):"
    )
    try:
        await callback.message.edit_text(prompt, parse_mode="HTML")
    except Exception:
        await callback.message.answer(prompt, parse_mode="HTML")
    await callback.answer()


@client_router.message(BookingStates.waiting_for_name, F.text)
async def process_client_name(message: Message, state: FSMContext) -> None:
    text = message.text.strip()
    if text in ["❌ Скасувати", "❌ Скасувати запис"]:
        await state.clear()
        await message.answer("Запис скасовано.", reply_markup=get_client_main_keyboard(), parse_mode="HTML")
        return

    client_name = text
    await state.update_data(client_name=client_name)
    await state.set_state(BookingStates.waiting_for_contact)
    await message.answer(
        f"📱 <b>Дякуємо, {html.escape(client_name)}!</b>\n"
        f"Надішліть, будь ласка, ваш контактний номер телефону за допомогою кнопки нижче або введіть номер вручну:",
        reply_markup=get_contact_request_keyboard(),
        parse_mode="HTML"
    )


@client_router.message(StateFilter(BookingStates.waiting_for_contact, BookingStates.waiting_for_phone), F.contact | F.text)
async def process_phone_and_confirm(message: Message, state: FSMContext) -> None:
    if message.text == "❌ Скасувати запис":
        await state.clear()
        await message.answer("Запис скасовано.", reply_markup=get_client_main_keyboard(), parse_mode="HTML")
        return

    phone = ""
    if message.contact:
        phone = message.contact.phone_number
    elif message.text:
        phone = message.text.strip()

    if not phone or len(phone) < 7:
        await message.answer("Будь ласка, введіть дійсний номер телефону:")
        return

    data = await state.get_data()
    client_name = data.get("client_name", message.from_user.first_name if message.from_user else "Клієнт")
    tg_id = message.from_user.id if message.from_user else 0

    async with AsyncSessionLocal() as db:
        c_stmt = select(Client).where(
            or_(Client.telegram_id == tg_id, Client.phone == phone),
            Client.deleted.is_(False)
        )
        client = (await db.execute(c_stmt)).scalars().first()
        if not client:
            client = Client(
                telegram_id=tg_id,
                first_name=client_name,
                phone=phone
            )
            db.add(client)
        else:
            if tg_id:
                client.telegram_id = tg_id
            client.first_name = client_name
            client.phone = phone
            client.mark_updated()
        await db.commit()
        await db.refresh(client)

    await state.update_data(
        client_id=client.id,
        client_name=client.first_name,
        client_phone=client.phone
    )
    await state.set_state(BookingStates.confirming_booking)
    await render_booking_summary(message, state, is_callback=False)


@client_router.callback_query(F.data == "bk:edit_profile")
async def process_edit_profile(callback: CallbackQuery, state: FSMContext) -> None:
    await state.set_state(BookingStates.waiting_for_name)
    await callback.message.answer(
        "👤 Напишіть, будь ласка, ваше справжнє <b>Ім'я</b> (як до вас звертатися майстру):",
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.callback_query(BookingStates.confirming_booking, F.data == "bk:confirm")
async def finalize_booking(callback: CallbackQuery, state: FSMContext) -> None:
    await callback.answer()
    data = await state.get_data()

    m_id = data.get("master_id", "m-01")
    s_id = data.get("service_id")
    date_str = data.get("date")
    start_time = data.get("start_time", "10:00")
    client_name = data.get("client_name", "Клієнт")
    client_phone = data.get("client_phone", "")
    client_id = data.get("client_id")
    tg_id = callback.from_user.id if callback.from_user else 0

    async with AsyncSessionLocal() as db:
        # Load Client
        client = None
        if client_id:
            client = await db.get(Client, client_id)
        if not client:
            c_stmt = select(Client).where(
                or_(Client.telegram_id == tg_id, Client.phone == client_phone),
                Client.deleted.is_(False)
            )
            client = (await db.execute(c_stmt)).scalars().first()

        if not client:
            client = Client(
                telegram_id=tg_id,
                first_name=client_name,
                phone=client_phone
            )
            db.add(client)
            await db.flush()

        srv = await db.get(Service, s_id)
        srv_title = srv.title if srv else "Процедура"
        srv_price = srv.price if srv else Decimal("500.00")
        dur = srv.duration_minutes if srv else 60

        end_time = calculate_end_time(start_time, dur) if start_time != "За домовленістю" else "За домовленістю"

        # Check settings for deposit
        set_res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        st_map = {s.key: s.value for s in set_res.scalars().all()}

        # Apply loyalty discount if set
        discount_p = client.discount_percent or 0
        if discount_p > 0:
            srv_price = Decimal(str(round(float(srv_price) * (100 - discount_p) / 100.0, 2)))

        deposit_required = st_map.get("deposit_required", "false").lower() == "true"
        deposit_val = Decimal(st_map.get("deposit_amount", "200.00")) if deposit_required else Decimal("0.00")

        if client.is_blacklisted:
            deposit_required = True
            if deposit_val <= Decimal("0.00"):
                deposit_val = Decimal(st_map.get("deposit_amount", "200.00"))

        card_details = st_map.get("card_details", "5375 4141 1234 5678 (Анастасія)")
        jar_link = st_map.get("jar_link", "https://send.monobank.ua/jar/123456")

        apt = Appointment(
            master_id=m_id,
            client_id=client.id,
            service_id=s_id,
            date=date_str,
            start_time=start_time,
            end_time=end_time,
            status="PENDING",
            total_price=srv_price,
            deposit_amount=deposit_val,
            is_deposit_paid=False,
            cancellation_reason=""
        )
        db.add(apt)
        await db.commit()
        await db.refresh(apt)

        # Collect all recipient chat IDs for masters
        recipients = set()
        master = await db.get(Master, m_id)
        if master and master.telegram_chat_id:
            recipients.add(master.telegram_chat_id)

        all_masters = (await db.execute(select(Master).where(Master.is_active.is_(True), Master.deleted.is_(False)))).scalars().all()
        for m in all_masters:
            if m.telegram_chat_id:
                recipients.add(m.telegram_chat_id)

        env_ids = os.getenv("MASTER_CHAT_ID", "").split(",")
        for eid in env_ids:
            eid = eid.strip()
            if eid.lstrip("-").isdigit():
                recipients.add(int(eid))

        db_chat_id = st_map.get("master_chat_id", "").strip()
        if db_chat_id.lstrip("-").isdigit():
            recipients.add(int(db_chat_id))

        # Send Telegram notification to masters with 1-tap confirm/reject
        from app.bot.bot_instance import bot
        if bot:
            m_notice = (
                f"🔔 <b>Новий онлайн-запис!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(client.first_name)}</b> ({html.escape(client.phone)})\n"
                f"📅 Дата: <b>{date_str}</b>\n"
                f"⏰ Час: <b>{start_time} - {end_time}</b>\n"
                f"💅 Послуга: <b>{html.escape(srv_title)}</b>\n"
                f"💰 Сума: <b>{int(srv_price)} ₴</b>"
            )
            if deposit_val > Decimal("0.00"):
                m_notice += f"\n💳 Завдаток: <b>{int(deposit_val)} ₴</b>"
            if client.is_blacklisted:
                m_notice += "\n⚠️ <i>Клієнт у чорному списку (No-Show).</i>"

            for chat_id in recipients:
                try:
                    await bot.send_message(
                        chat_id=chat_id,
                        text=m_notice,
                        reply_markup=get_master_notification_keyboard(apt.id),
                        parse_mode="HTML"
                    )
                except Exception as ex:
                    logger.warning("Could not notify master chat %s: %s", chat_id, ex)

    await state.clear()

    confirmation_msg = (
        f"🎉 <b>Ваш запис успішно створено!</b>\n\n"
        f"📅 Дата: <b>{date_str}</b>\n"
        f"⏰ Час: <b>{start_time}</b>\n"
        f"💅 Процедура: <b>{html.escape(srv_title)}</b>\n"
        f"💰 Загальна вартість: <b>{int(srv_price)} ₴</b>\n"
    )
    if discount_p > 0:
        confirmation_msg += f"🎁 <i>Враховано вашу персональну знижку {discount_p}%!</i>\n"
    if client.is_blacklisted:
        confirmation_msg += f"⚠️ <i>Для вашого акаунту діє обов'язковий завдаток (No-Show).</i>\n"
    confirmation_msg += "\n"

    if deposit_required and deposit_val > Decimal("0.00"):
        confirmation_msg += (
            f"⚠️ <b>Увага: для підтвердження потрібен завдаток {int(deposit_val)} грн.</b>\n"
            f"Будь ласка, здійсніть оплату протягом 60 хвилин:\n"
            f"💳 <b>Карта:</b> <code>{html.escape(card_details)}</code>\n"
            f"🏺 <b>Monobank Банка:</b> {html.escape(jar_link)}\n\n"
            f"📸 <i>Після оплати просто надішліть фото або скріншот чека сюди в чат!</i>"
        )
        await state.set_state(ReceiptUploadStates.waiting_for_photo)
    else:
        confirmation_msg += "✅ Запис прийнято! Майстер підтвердить його найближчим часом. Чекаємо на вас у студії."

    try:
        await callback.message.edit_text(confirmation_msg, reply_markup=get_client_main_keyboard(), parse_mode="HTML")
    except Exception:
        await callback.message.answer(confirmation_msg, reply_markup=get_client_main_keyboard(), parse_mode="HTML")


# 5. Receipt Photo Upload Flow
@client_router.message(F.photo)
async def process_receipt_photo(message: Message, state: FSMContext) -> None:
    tg_id = message.from_user.id if message.from_user else 0
    async with AsyncSessionLocal() as db:
        c_res = await db.execute(select(Client).where(Client.telegram_id == tg_id))
        client = c_res.scalar_one_or_none()
        if not client:
            return

        # Find latest pending appointment with unpaid deposit
        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.master), selectinload(Appointment.service))
            .where(
                Appointment.client_id == client.id,
                Appointment.status == "PENDING",
                Appointment.deposit_amount > 0,
                Appointment.is_deposit_paid.is_(False),
                Appointment.deleted.is_(False)
            )
            .order_by(Appointment.created_at.desc())
        )
        apt = (await db.execute(stmt)).scalars().first()
        if not apt or not apt.master or not apt.master.telegram_chat_id:
            await message.answer("Дякуємо за фото! Наразі у вас немає записів, що очікують на оплату завдатку.")
            return

        from app.bot.bot_instance import bot
        if bot:
            photo_file_id = message.photo[-1].file_id
            caption = (
                f"🧾 <b>Чек про оплату завдатку!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(client.first_name)}</b> ({html.escape(client.phone)})\n"
                f"📅 Дата: <b>{apt.date}</b> ({apt.start_time})\n"
                f"💰 Сума завдатку: <b>{int(apt.deposit_amount)} грн</b>"
            )
            try:
                await bot.send_photo(
                    chat_id=apt.master.telegram_chat_id,
                    photo=photo_file_id,
                    caption=caption,
                    reply_markup=get_master_receipt_approval_keyboard(apt.id),
                    parse_mode="HTML"
                )
                await message.answer("✅ <b>Ваш чек отримано!</b> Майстер перевірить його найближчим часом та підтвердить запис.", parse_mode="HTML")
            except Exception as e:
                logger.error("Failed to forward receipt photo to master: %s", e)
                await message.answer("Сталася помилка при надсиланні чека. Будь ласка, повідомте майстра напряму.")
    await state.clear()


# 6. Reviews Flow
@client_router.callback_query(F.data == "booking:start")
async def process_booking_start_callback(callback: CallbackQuery, state: FSMContext) -> None:
    await callback.answer()
    await start_booking_flow(callback.message, state)


# =====================================================================
# FLOW A: POST-VISIT REVIEW (10 MIN) — TEXT FIRST -> 1..5 STARS
# =====================================================================
@client_router.callback_query(F.data.startswith("rev_start:"))
async def process_post_review_start(callback: CallbackQuery, state: FSMContext) -> None:
    apt_id = callback.data.split(":")[1]
    await state.update_data(review_apt_id=apt_id)
    await state.set_state(ReviewStates.waiting_for_post_review_text)
    await callback.message.answer(
        "✍️ <b>Напишіть, будь ласка, ваш текстовий відгук:</b>\n"
        "Як вам якість послуги, форма нігтів та атмосфера?",
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.message(ReviewStates.waiting_for_post_review_text, F.text)
async def process_post_review_text(message: Message, state: FSMContext) -> None:
    comment = message.text.strip()
    await state.update_data(review_comment=comment)
    await state.set_state(ReviewStates.waiting_for_post_review_stars)
    await message.answer(
        "⭐ <b>Чудово! А тепер оберіть вашу оцінку від 1 до 5 зірочок:</b>",
        reply_markup=get_post_review_stars_keyboard(),
        parse_mode="HTML"
    )


@client_router.callback_query(F.data.startswith("rev_star:"))
async def process_post_review_stars(callback: CallbackQuery, state: FSMContext) -> None:
    rating = int(callback.data.split(":")[1])
    data = await state.get_data()
    apt_id = data.get("review_apt_id")
    comment = data.get("review_comment", "")

    async with AsyncSessionLocal() as db:
        tg_id = callback.from_user.id if callback.from_user else 0
        c_res = await db.execute(select(Client).where(Client.telegram_id == tg_id))
        client = c_res.scalar_one_or_none()

        apt = await db.get(Appointment, apt_id) if apt_id else None
        if not client and apt:
            client = await db.get(Client, apt.client_id)

        client_name = client.first_name if client else "Клієнт"
        service_title = "Манікюр"
        if apt and apt.service_id:
            srv = await db.get(Service, apt.service_id)
            if srv:
                service_title = srv.title

        rev_stmt = select(Review).where(
            Review.appointment_id == apt_id,
            Review.review_type == "POST_VISIT",
            Review.deleted.is_(False)
        )
        existing_rev = (await db.execute(rev_stmt)).scalar_one_or_none() if apt_id else None

        if existing_rev:
            existing_rev.rating = rating
            existing_rev.comment = comment
            existing_rev.client_name = client_name
            existing_rev.service_title = service_title
            existing_rev.moderation_status = "APPROVED"
            existing_rev.is_published = True
            existing_rev.mark_updated()
        else:
            new_rev = Review(
                appointment_id=apt_id,
                client_id=client.id if client else None,
                client_name=client_name,
                service_title=service_title,
                rating=rating,
                comment=comment,
                photo_url="",
                tg_file_id="",
                review_type="POST_VISIT",
                moderation_status="APPROVED",
                is_published=True
            )
            db.add(new_rev)

        await db.commit()

        # Notify masters/owners
        from app.bot.bot_instance import bot
        if bot:
            owner_stmt = select(Master).where(
                Master.role.in_(["owner", "master"]),
                Master.deleted.is_(False),
                Master.telegram_chat_id.isnot(None)
            )
            masters = (await db.execute(owner_stmt)).scalars().all()
            alert_msg = (
                f"⭐ <b>Новий відгук від клієнта!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(client_name)}</b>\n"
                f"💅 Послуга: <b>{html.escape(service_title)}</b>\n"
                f"⭐ Оцінка: <b>{rating}/5</b> {'⭐' * rating}\n"
                f"💬 Відгук: «{html.escape(comment or 'Без коментаря')}»"
            )
            for m in masters:
                if m.telegram_chat_id:
                    try:
                        await bot.send_message(chat_id=m.telegram_chat_id, text=alert_msg, parse_mode="HTML")
                    except Exception as e:
                        logger.warning("Failed to notify master of review: %s", e)

    await state.clear()
    await callback.message.edit_text(
        f"❤️ <b>Дякуємо за ваш відгук та високу оцінку {rating} ⭐!</b>\n"
        f"Ми безмежно раді робити ваші нігтики бездоганними! До нових зустрічей у JuNails!",
        parse_mode="HTML"
    )
    await callback.answer()


# =====================================================================
# FLOW B: 2-WEEK WEAR REMINDER — TEXT -> NAIL PHOTO -> CONDITIONAL BOOKING
# =====================================================================
@client_router.callback_query(F.data.startswith("wear_start:") | F.data.startswith("wear_fb:"))
async def process_wear_check_start(callback: CallbackQuery, state: FSMContext) -> None:
    apt_id = callback.data.split(":")[1]
    await state.update_data(wear_apt_id=apt_id)
    await state.set_state(ReviewStates.waiting_for_wear_text)
    await callback.message.answer(
        "💬 <b>Крок 1 із 2:</b> Напишіть текстом, як носиться покриття (чи є сколи, відшарування або дискомфорт)?",
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.message(ReviewStates.waiting_for_wear_text, F.text)
async def process_wear_check_text(message: Message, state: FSMContext) -> None:
    wear_comment = message.text.strip()
    await state.update_data(wear_comment=wear_comment)
    await state.set_state(ReviewStates.waiting_for_wear_photo)
    kb = InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="⏩ Зберегти без фото", callback_data="wear_nophoto")]
    ])
    await message.answer(
        "📸 <b>Крок 2 із 2:</b> Надішліть, будь ласка, <b>фото стану ваших нігтиків зараз</b> (як виглядає покриття):\n\n"
        "<i>Якщо не маєте змоги сфотографувати зараз — просто натисніть кнопку нижче:</i>",
        reply_markup=kb,
        parse_mode="HTML"
    )


async def finalize_wear_report(
    user_id: int,
    apt_id: Optional[str],
    wear_comment: str,
    photo_file_id: Optional[str],
    photo_bytes: Optional[bytes],
    bot_ref: Any
) -> tuple[str, Optional[InlineKeyboardMarkup]]:
    today_str = get_kyiv_today()
    photo_url = ""

    if photo_bytes:
        try:
            from PIL import Image
            img = Image.open(io.BytesIO(photo_bytes))
            if img.mode != "RGB":
                img = img.convert("RGB")
            img.thumbnail((800, 800))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=75, optimize=True)
            comp = buf.getvalue()
        except Exception:
            comp = photo_bytes
        photo_url = f"data:image/jpeg;base64,{base64.b64encode(comp).decode('ascii')}"

    async with AsyncSessionLocal() as db:
        c_res = await db.execute(select(Client).where(Client.telegram_id == user_id))
        client = c_res.scalar_one_or_none()

        apt = await db.get(Appointment, apt_id) if apt_id else None
        if not client and apt:
            client = await db.get(Client, apt.client_id)

        client_name = client.first_name if client else "Клієнт"
        client_phone = client.phone if client else ""
        service_title = "Манікюр"
        if apt and apt.service_id:
            srv = await db.get(Service, apt.service_id)
            if srv:
                service_title = srv.title

        rev = Review(
            appointment_id=apt_id,
            client_id=client.id if client else None,
            client_name=client_name,
            service_title=service_title,
            rating=5,
            comment=wear_comment,
            photo_url=photo_url,
            tg_file_id=photo_file_id or "",
            review_type="WEAR_2WEEKS",
            moderation_status="APPROVED",
            is_published=True
        )
        db.add(rev)

        # Update VisitBrief photo if apt_id exists
        if apt_id:
            vb_res = await db.execute(select(VisitBrief).where(VisitBrief.appointment_id == apt_id))
            vb = vb_res.scalar_one_or_none()
            if vb:
                vb.coating_condition = wear_comment
                if photo_url:
                    vb.photo_after_url = photo_url
                vb.mark_updated()

        await db.commit()

        # Forward wear check to active masters/owner
        if bot_ref and client:
            owner_stmt = select(Master).where(
                Master.role.in_(["owner", "master"]),
                Master.deleted.is_(False),
                Master.telegram_chat_id.isnot(None)
            )
            masters = (await db.execute(owner_stmt)).scalars().all()
            alert_caption = (
                f"📸 <b>Звіт про носіння покриття (2 тижні)!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(client_name)}</b> ({html.escape(client_phone)})\n"
                f"💅 Послуга: <b>{html.escape(service_title)}</b>\n"
                f"💬 Відгук: {html.escape(wear_comment or 'Все добре')}"
            )
            for m in masters:
                if m.telegram_chat_id:
                    try:
                        if photo_file_id:
                            await bot_ref.send_photo(
                                chat_id=m.telegram_chat_id,
                                photo=photo_file_id,
                                caption=alert_caption,
                                parse_mode="HTML"
                            )
                        else:
                            await bot_ref.send_message(
                                chat_id=m.telegram_chat_id,
                                text=alert_caption,
                                parse_mode="HTML"
                            )
                    except Exception as e:
                        logger.warning("Failed to forward wear report: %s", e)

        # Conditional Booking Check
        next_apt = None
        if client:
            fut_stmt = (
                select(Appointment)
                .options(selectinload(Appointment.service))
                .where(
                    Appointment.client_id == client.id,
                    Appointment.date >= today_str,
                    Appointment.status.in_(["PENDING", "CONFIRMED"]),
                    Appointment.deleted.is_(False)
                )
                .order_by(Appointment.date.asc(), Appointment.start_time.asc())
            )
            next_apt = (await db.execute(fut_stmt)).scalars().first()

        if next_apt:
            srv_name = next_apt.service.title if next_apt.service else "манікюр"
            reply_msg = (
                f"❤️ <b>Дякуємо! Ваш звіт про носіння збережено.</b>\n\n"
                f"📅 Нагадуємо, що ваш наступний візит уже заплановано на <b>{next_apt.date} о {next_apt.start_time}</b> "
                f"(«{html.escape(srv_name)}»)! Чекаємо на вас! 🥰"
            )
            return reply_msg, None
        else:
            reply_msg = (
                f"❤️ <b>Дякуємо! Ваш звіт про носіння збережено.</b>\n\n"
                f"💅 <b>Зверніть увагу:</b> у вас ще немає запису на корекцію/оновлення. "
                f"Щоб обрати найзручніший для вас час, поки є вільні віконця — запишіться завчасно:"
            )
            kb = InlineKeyboardMarkup(inline_keyboard=[
                [InlineKeyboardButton(text="💅 Записатися завчасно", callback_data="m:book")]
            ])
            return reply_msg, kb


@client_router.message(ReviewStates.waiting_for_wear_photo, F.photo)
async def process_wear_photo_uploaded(message: Message, state: FSMContext) -> None:
    data = await state.get_data()
    apt_id = data.get("wear_apt_id")
    wear_comment = data.get("wear_comment", "")
    photo = message.photo[-1]
    photo_file_id = photo.file_id

    buf = io.BytesIO()
    await message.bot.download(photo, destination=buf)
    raw_bytes = buf.getvalue()

    reply_msg, kb = await finalize_wear_report(
        user_id=message.from_user.id if message.from_user else 0,
        apt_id=apt_id,
        wear_comment=wear_comment,
        photo_file_id=photo_file_id,
        photo_bytes=raw_bytes,
        bot_ref=message.bot
    )
    await state.clear()
    await message.answer(reply_msg, reply_markup=kb, parse_mode="HTML")


@client_router.callback_query(F.data == "wear_nophoto")
async def process_wear_no_photo_click(callback: CallbackQuery, state: FSMContext) -> None:
    data = await state.get_data()
    apt_id = data.get("wear_apt_id")
    wear_comment = data.get("wear_comment", "")

    reply_msg, kb = await finalize_wear_report(
        user_id=callback.from_user.id if callback.from_user else 0,
        apt_id=apt_id,
        wear_comment=wear_comment,
        photo_file_id=None,
        photo_bytes=None,
        bot_ref=callback.bot
    )
    await state.clear()
    await callback.message.edit_text(reply_msg, reply_markup=kb, parse_mode="HTML")
    await callback.answer()


# =====================================================================
# DEDICATED "ALL REVIEWS" WINDOW (CLIENT INLINE MENU & PAGINATION)
# =====================================================================
@client_router.callback_query(F.data.startswith("m:reviews"))
async def process_client_all_reviews(callback: CallbackQuery) -> None:
    parts = callback.data.split(":")
    page = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else 0
    page_size = 3

    async with AsyncSessionLocal() as db:
        rev_stmt = (
            select(Review)
            .where(
                Review.is_published.is_(True),
                Review.moderation_status == "APPROVED",
                Review.deleted.is_(False)
            )
            .order_by(Review.created_at.desc())
        )
        all_revs = (await db.execute(rev_stmt)).scalars().all()

    total_count = len(all_revs)
    total_pages = max(1, (total_count + page_size - 1) // page_size)
    page = min(page, total_pages - 1)

    start_idx = page * page_size
    current_page_revs = all_revs[start_idx : start_idx + page_size]

    text = f"⭐ <b>Відгуки наших клієнтів (сторінка {page + 1}/{total_pages}):</b>\n\n"
    if not current_page_revs:
        text += "<i>Поки що немає відгуків. Будьте першими, хто залишить враження! 💖</i>\n"

    keyboard_rows: list[list[InlineKeyboardButton]] = []

    for r in current_page_revs:
        type_badge = "💅 [Після візиту]" if r.review_type == "POST_VISIT" else "✨ [Через 2 тижні]"
        stars_str = "⭐" * r.rating
        c_name = r.client_name or "Клієнт"
        s_title = r.service_title or "Послуга студії"
        comment_text = r.comment or "Чудовий результат та ідеальний манікюр!"

        text += (
            f"👤 <b>{html.escape(c_name)}</b> {type_badge}\n"
            f"💅 <i>{html.escape(s_title)}</i> | {stars_str}\n"
            f"💬 «{html.escape(comment_text)}»\n\n"
        )
        if r.tg_file_id:
            keyboard_rows.append([
                InlineKeyboardButton(
                    text=f"📸 Фото нігтів від {c_name}",
                    callback_data=f"rev_pic:{r.id}"
                )
            ])

    # Pagination navigation
    nav_row: list[InlineKeyboardButton] = []
    if page > 0:
        nav_row.append(InlineKeyboardButton(text="◀️ Попередня", callback_data=f"m:reviews:{page - 1}"))
    if page < total_pages - 1:
        nav_row.append(InlineKeyboardButton(text="Наступна ▶️", callback_data=f"m:reviews:{page + 1}"))
    if nav_row:
        keyboard_rows.append(nav_row)

    keyboard_rows.append([
        InlineKeyboardButton(text="💅 Записатися онлайн", callback_data="m:book"),
        InlineKeyboardButton(text="« Головне меню", callback_data="m:main"),
    ])

    await callback.message.edit_text(
        text=text,
        reply_markup=InlineKeyboardMarkup(inline_keyboard=keyboard_rows),
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.callback_query(F.data.startswith("rev_pic:"))
async def process_show_review_photo(callback: CallbackQuery) -> None:
    rev_id = callback.data.split(":")[1]
    async with AsyncSessionLocal() as db:
        rev = await db.get(Review, rev_id)
        if not rev or not rev.tg_file_id:
            await callback.answer("Фото відсутнє або не збереглося.", show_alert=True)
            return

        caption = (
            f"📸 <b>Фото покриття від {html.escape(rev.client_name or 'клієнта')}</b>\n"
            f"⭐ {rev.rating}/5 — {html.escape(rev.service_title or 'Манікюр')}\n"
            f"«{html.escape(rev.comment or '')}»"
        )
        try:
            await callback.message.answer_photo(
                photo=rev.tg_file_id,
                caption=caption,
                parse_mode="HTML"
            )
        except Exception as e:
            logger.warning("Failed to show review photo: %s", e)
            await callback.answer("Не вдалося відкрити фото.", show_alert=True)

    await callback.answer()


# =====================================================================
# BACKWARD COMPATIBILITY HANDLERS (LEGACY CALLBACKS & COMMENTS)
# =====================================================================
@client_router.callback_query(F.data.startswith("review_"))
async def process_review_score_legacy(callback: CallbackQuery, state: FSMContext) -> None:
    parts = callback.data.split("_")
    apt_id = parts[1]
    score = int(parts[2])

    async with AsyncSessionLocal() as db:
        apt = await db.get(Appointment, apt_id)
        if not apt:
            await callback.answer("Запис не знайдено.", show_alert=True)
            return

        c = await db.get(Client, apt.client_id) if apt.client_id else None
        s = await db.get(Service, apt.service_id) if apt.service_id else None

        rev = Review(
            appointment_id=apt_id,
            client_id=apt.client_id,
            client_name=c.first_name if c else "Клієнт",
            service_title=s.title if s else "Манікюр",
            rating=score,
            comment="",
            review_type="POST_VISIT",
            moderation_status="APPROVED",
            is_published=True,
        )
        db.add(rev)
        await db.commit()
        await db.refresh(rev)

        await state.update_data(review_id=rev.id, wear_apt_id=apt_id)
        await state.set_state(ReviewStates.waiting_for_comment)

    await callback.message.edit_text(
        f"Дякуємо за оцінку <b>{score} ⭐</b>!\n"
        f"Напишіть, будь ласка, кілька слів про ваші враження від візиту або надішліть 📸 фото нігтів:",
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.message(ReviewStates.waiting_for_comment, F.photo)
async def process_review_photo_legacy(message: Message, state: FSMContext) -> None:
    data = await state.get_data()
    rev_id = data.get("review_id")
    caption = message.caption or "Фото стану покриття"
    photo_file_id = message.photo[-1].file_id

    async with AsyncSessionLocal() as db:
        if rev_id:
            rev = await db.get(Review, rev_id)
            if rev:
                rev.comment = caption
                rev.tg_file_id = photo_file_id
                rev.mark_updated()
                await db.commit()

    await state.clear()
    await message.answer("❤️ <b>Дякуємо за фото та відгук!</b> Майстер уже бачить ваш результат.", parse_mode="HTML")


@client_router.message(ReviewStates.waiting_for_comment, F.text)
async def process_review_comment_legacy(message: Message, state: FSMContext) -> None:
    comment = message.text.strip()
    data = await state.get_data()
    rev_id = data.get("review_id")

    if rev_id:
        async with AsyncSessionLocal() as db:
            rev = await db.get(Review, rev_id)
            if rev:
                rev.comment = comment
                rev.mark_updated()
                await db.commit()

    await state.clear()
    await message.answer("❤️ <b>Дякуємо за ваш відгук!</b> Ми постійно вдосконалюємо наш сервіс для вас.", parse_mode="HTML")
