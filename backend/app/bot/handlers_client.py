import html
import logging
from datetime import datetime, timezone
from decimal import Decimal
from typing import Optional
import pytz

from aiogram import F, Router
from aiogram.filters import Command, CommandStart, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, Message, ReplyKeyboardRemove
from sqlalchemy import select, or_
from sqlalchemy.orm import selectinload

from app.bot.fsm import BookingStates, ReceiptUploadStates, ReviewStates, WaitingListStates
from app.bot.keyboards import (
    create_inline_calendar,
    get_cancel_appointment_keyboard,
    get_categories_keyboard,
    get_client_main_keyboard,
    get_contact_request_keyboard,
    get_master_receipt_approval_keyboard,
    get_review_rating_keyboard,
    get_services_keyboard,
    get_time_slots_keyboard,
)
from app.config import settings
from app.database import AsyncSessionLocal
from app.models import (
    Appointment,
    Client,
    Master,
    Product,
    Review,
    Service,
    ServiceCategory,
    StudioSetting,
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


# 2. Main Menu Actions
@client_router.message(F.text == "📋 Прайс")
async def show_price_list(message: Message) -> None:
    async with AsyncSessionLocal() as db:
        res = await db.execute(
            select(ServiceCategory)
            .options(selectinload(ServiceCategory.services))
            .where(ServiceCategory.deleted.is_(False), ServiceCategory.is_active.is_(True))
            .order_index() if hasattr(ServiceCategory, "order_index") else select(ServiceCategory).options(selectinload(ServiceCategory.services)).where(ServiceCategory.deleted.is_(False), ServiceCategory.is_active.is_(True))
        )
        categories = res.scalars().all()

        if not categories:
            await message.answer("📋 Прайс-лист наразі оновлюється.", parse_mode="HTML")
            return

        lines = ["<b>📋 Наш прайс-лист:</b>\n"]
        for cat in categories:
            lines.append(f"<b>{html.escape(cat.icon_emoji)} {html.escape(cat.name)}:</b>")
            active_services = [s for s in cat.services if not s.deleted and s.is_active]
            if not active_services:
                lines.append("  <i>Послуг поки немає</i>")
            for s in active_services:
                lines.append(f"  • {html.escape(s.title)} — <b>{int(s.price)} грн</b> ({s.duration_minutes} хв)")
            lines.append("")

        await message.answer("\n".join(lines), parse_mode="HTML")


@client_router.message(F.text == "👩🎨 Про майстра / Адреса")
async def show_about_info(message: Message) -> None:
    async with AsyncSessionLocal() as db:
        res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        items = {s.key: s.value for s in res.scalars().all()}

        addr = items.get("address", "вул. Хрещатик, 1, м. Київ")
        phone = items.get("phone", "+380 (99) 123-45-67")
        insta = items.get("instagram", "@master_crm_beauty")

        text = (
            f"<b>👩🎨 Про нашу студію:</b>\n\n"
            f"📍 <b>Адреса:</b> {html.escape(addr)}\n"
            f"📞 <b>Телефон:</b> {html.escape(phone)}\n"
            f"📸 <b>Instagram:</b> {html.escape(insta)}\n\n"
            f"Чекаємо на вас затишно та з турботою про ваші нігтики! 💅☕️"
        )
        await message.answer(text, parse_mode="HTML")


@client_router.message(F.text == "📅 Мої записи")
async def show_client_appointments(message: Message) -> None:
    tg_id = message.from_user.id if message.from_user else 0
    async with AsyncSessionLocal() as db:
        c_res = await db.execute(select(Client).where(Client.telegram_id == tg_id, Client.deleted.is_(False)))
        client = c_res.scalar_one_or_none()
        if not client:
            await message.answer("У вас ще немає збережених записів.", parse_mode="HTML")
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
            await message.answer("У вас немає активних майбутніх записів. Записатися можна за кнопкою 💅 <b>Записатися онлайн</b>!", parse_mode="HTML")
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
                f"💰 Вартість: <b>{int(a.total_price)} грн</b>\n"
                f"📌 Статус: <b>{st_text}</b>"
            )
            await message.answer(msg, reply_markup=get_cancel_appointment_keyboard(a.id), parse_mode="HTML")


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

        # Calculate free slots
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
        free_slots = []
        cur_min = 9 * 60
        while cur_min + srv.duration_minutes <= 19 * 60:
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
            cur_min += 30

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


@client_router.callback_query(BookingStates.waiting_for_time, F.data.startswith("time:"))
async def process_time_selected(callback: CallbackQuery, state: FSMContext) -> None:
    slot_choice = callback.data.split(":")[1]

    if slot_choice == "waitlist":
        # Add to waiting list directly
        data = await state.get_data()
        async with AsyncSessionLocal() as db:
            tg_id = callback.from_user.id
            c_res = await db.execute(select(Client).where(Client.telegram_id == tg_id))
            client = c_res.scalar_one_or_none()
            if not client:
                await state.set_state(WaitingListStates.waiting_for_phone)
                await callback.message.answer("Будь ласка, надішліть номер телефону для листа очікування:", reply_markup=get_contact_request_keyboard())
                await callback.answer()
                return

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
        await callback.message.edit_text("✅ <b>Ви в листі очікування!</b> Ми обов'язково надішлемо вам повідомлення, щойно з'явиться віконце на цю дату.", parse_mode="HTML")
        await callback.answer()
        return

    if slot_choice == "custom":
        await state.update_data(start_time="За домовленістю")
    else:
        await state.update_data(start_time=slot_choice)

    await state.set_state(BookingStates.waiting_for_phone)
    await callback.message.delete()
    await callback.message.answer(
        "📱 <b>Крок 5/5: Залиште ваш номер телефону:</b>\n"
        "Натисніть кнопку внизу або надішліть номер текстом (наприклад, 0991234567):",
        reply_markup=get_contact_request_keyboard(),
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.message(BookingStates.waiting_for_phone, F.contact | F.text)
async def process_phone_and_finish_booking(message: Message, state: FSMContext) -> None:
    if message.text == "❌ Скасувати запис":
        await state.clear()
        await message.answer("Запис скасовано.", reply_markup=get_client_main_keyboard(), parse_mode="HTML")
        return

    phone = ""
    if message.contact:
        phone = message.contact.phone_number
    elif message.text:
        phone = message.text.strip()

    if not phone:
        await message.answer("Будь ласка, введіть дійсний номер телефону:")
        return

    data = await state.get_data()
    m_id = data.get("master_id")
    s_id = data.get("service_id")
    date_str = data.get("date")
    start_time = data.get("start_time", "10:00")
    tg_id = message.from_user.id if message.from_user else None
    first_name = message.from_user.first_name if message.from_user else "Клієнт"
    last_name = message.from_user.last_name or ""

    async with AsyncSessionLocal() as db:
        # Upsert or link Client
        c_stmt = select(Client).where(or_(Client.telegram_id == tg_id, Client.phone == phone), Client.deleted.is_(False))
        c_res = await db.execute(c_stmt)
        client = c_res.scalars().first()
        if not client:
            client = Client(
                telegram_id=tg_id,
                first_name=first_name,
                last_name=last_name,
                phone=phone,
            )
            db.add(client)
            await db.flush()
        else:
            if not client.telegram_id and tg_id:
                client.telegram_id = tg_id
            client.first_name = first_name
            client.phone = phone
            client.mark_updated()

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

        # Blacklisted clients force mandatory deposit
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

        # Notify Master
        master = await db.get(Master, m_id)
        from app.bot.bot_instance import bot
        if bot and master and master.telegram_chat_id:
            m_notice = (
                f"🔔 <b>Новий запис у системі!</b>\n\n"
                f"👤 Клієнт: <b>{html.escape(client.first_name)}</b> ({html.escape(client.phone)})\n"
                f"📅 Дата: <b>{date_str}</b> | Час: <b>{start_time}</b>\n"
                f"💅 Послуга: <b>{html.escape(srv_title)}</b> ({int(srv_price)} грн)\n"
                f"💰 Завдаток: <b>{int(deposit_val)} грн</b>"
            )
            if client.is_blacklisted:
                m_notice += "\n⚠️ <i>Клієнт у чорному списку (No-Show).</i>"
            try:
                await bot.send_message(chat_id=master.telegram_chat_id, text=m_notice, parse_mode="HTML")
            except Exception as ex:
                logger.warning("Could not notify master about new appointment: %s", ex)

    await state.clear()

    confirmation_msg = (
        f"🎉 <b>Ваш запис успішно створено!</b>\n\n"
        f"📅 Дата: <b>{date_str}</b>\n"
        f"⏰ Час: <b>{start_time}</b>\n"
        f"💅 Процедура: <b>{html.escape(srv_title)}</b>\n"
        f"💰 Загальна вартість: <b>{int(srv_price)} грн</b>\n"
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
        confirmation_msg += "✅ Запис прийнято! Чекаємо на вас у студії."

    await message.answer(confirmation_msg, reply_markup=get_client_main_keyboard(), parse_mode="HTML")


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
@client_router.callback_query(F.data.startswith("review_"))
async def process_review_score(callback: CallbackQuery, state: FSMContext) -> None:
    parts = callback.data.split("_")
    apt_id = parts[1]
    score = int(parts[2])

    async with AsyncSessionLocal() as db:
        apt = await db.get(Appointment, apt_id)
        if not apt:
            await callback.answer("Запис не знайдено.", show_alert=True)
            return

        rev = Review(
            appointment_id=apt_id,
            client_id=apt.client_id,
            rating=score,
            comment="",
            moderation_status="PENDING",
            is_published=False,
        )
        db.add(rev)
        await db.commit()
        await db.refresh(rev)

        await state.update_data(review_id=rev.id)
        await state.set_state(ReviewStates.waiting_for_comment)

    await callback.message.edit_text(
        f"Дякуємо за оцінку <b>{score} ⭐</b>!\n"
        f"Напишіть, будь ласка, кілька слів про ваші враження від візиту (або надішліть будь-який смайлик):",
        parse_mode="HTML"
    )
    await callback.answer()


@client_router.message(ReviewStates.waiting_for_comment, F.text)
async def process_review_comment(message: Message, state: FSMContext) -> None:
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
