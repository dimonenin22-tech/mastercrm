import calendar
from datetime import datetime, timezone
import pytz
from aiogram.types import (
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    ReplyKeyboardMarkup,
    ReplyKeyboardRemove,
)


def get_client_main_keyboard() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text="💅 Записатися онлайн")],
            [KeyboardButton(text="📅 Мої записи"), KeyboardButton(text="📋 Прайс")],
            [KeyboardButton(text="👩🎨 Про майстра / Адреса"), KeyboardButton(text="📝 Лист очікування")],
        ],
        resize_keyboard=True,
        one_time_keyboard=False,
    )


def get_contact_request_keyboard() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text="📱 Поділитися номером телефону", request_contact=True)],
            [KeyboardButton(text="❌ Скасувати запис")],
        ],
        resize_keyboard=True,
        one_time_keyboard=True,
    )


def create_inline_calendar(year: int, month: int, today_str: str) -> InlineKeyboardMarkup:
    month_names_ua = [
        "", "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
        "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
    ]
    header = f"{month_names_ua[month]} {year}"

    # Navigation buttons
    prev_month = month - 1
    prev_year = year
    if prev_month < 1:
        prev_month = 12
        prev_year -= 1

    next_month = month + 1
    next_year = year
    if next_month > 12:
        next_month = 1
        next_year += 1

    buttons = [
        [
            InlineKeyboardButton(text="◀️", callback_data=f"cal:nav:{prev_year}:{prev_month}"),
            InlineKeyboardButton(text=header, callback_data="cal:ignore"),
            InlineKeyboardButton(text="▶️", callback_data=f"cal:nav:{next_year}:{next_month}"),
        ]
    ]

    # Days of week
    dow_row = [
        InlineKeyboardButton(text=day, callback_data="cal:ignore")
        for day in ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
    ]
    buttons.append(dow_row)

    # Weeks
    month_matrix = calendar.monthcalendar(year, month)
    today_dt = datetime.strptime(today_str, "%Y-%m-%d").date()

    for week in month_matrix:
        row = []
        for day in week:
            if day == 0:
                row.append(InlineKeyboardButton(text=" ", callback_data="cal:ignore"))
            else:
                day_date = datetime(year, month, day).date()
                date_iso = f"{year:04d}-{month:02d}-{day:02d}"
                if day_date < today_dt:
                    # Past date: blocked
                    row.append(InlineKeyboardButton(text=f"▫️{day}", callback_data="cal:blocked"))
                else:
                    row.append(InlineKeyboardButton(text=f"{day}", callback_data=f"cal:day:{date_iso}"))
        buttons.append(row)

    buttons.append([InlineKeyboardButton(text="❌ Скасувати", callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_categories_keyboard(categories: list[tuple[str, str, str]]) -> InlineKeyboardMarkup:
    # categories: [(id, name, icon_emoji)]
    buttons = []
    for c_id, name, icon in categories:
        buttons.append([InlineKeyboardButton(text=f"{icon} {name}", callback_data=f"cat:{c_id}")])
    buttons.append([InlineKeyboardButton(text="❌ Скасувати", callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_services_keyboard(services: list[tuple[str, str, float, int]]) -> InlineKeyboardMarkup:
    # services: [(id, title, price, duration)]
    buttons = []
    for s_id, title, price, dur in services:
        label = f"{title} — {int(price)} грн ({dur} хв)"
        buttons.append([InlineKeyboardButton(text=label, callback_data=f"srv:{s_id}")])
    buttons.append([InlineKeyboardButton(text="◀️ Назад до категорій", callback_data="booking:back_cat")])
    buttons.append([InlineKeyboardButton(text="❌ Скасувати", callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_time_slots_keyboard(slots: list[str]) -> InlineKeyboardMarkup:
    buttons = []
    # 3 slots per row
    row = []
    for s in slots:
        row.append(InlineKeyboardButton(text=s, callback_data=f"time:{s}"))
        if len(row) == 3:
            buttons.append(row)
            row = []
    if row:
        buttons.append(row)

    buttons.append([InlineKeyboardButton(text="💬 Обговорити інший час", callback_data="time:custom")])
    buttons.append([InlineKeyboardButton(text="📝 Стати в лист очікування", callback_data="time:waitlist")])
    buttons.append([InlineKeyboardButton(text="◀️ Назад до вибору дати", callback_data="booking:back_date")])
    buttons.append([InlineKeyboardButton(text="❌ Скасувати", callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_cancel_appointment_keyboard(appointment_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="❌ Скасувати цей запис", callback_data=f"c_can:{appointment_id}")]
        ]
    )


def get_review_rating_keyboard(appointment_id: str) -> InlineKeyboardMarkup:
    row = [
        InlineKeyboardButton(text=f"{score} ⭐", callback_data=f"review_{appointment_id}_{score}")
        for score in range(1, 6)
    ]
    return InlineKeyboardMarkup(inline_keyboard=[row])


def get_post_review_stars_keyboard() -> InlineKeyboardMarkup:
    row = [
        InlineKeyboardButton(text=f"{score} ⭐", callback_data=f"rev_star:{score}")
        for score in [5, 4, 3, 2, 1]
    ]
    return InlineKeyboardMarkup(inline_keyboard=[row])


def get_wear_check_keyboard(appointment_id: str, has_future_booking: bool) -> InlineKeyboardMarkup:
    stars_row = [
        InlineKeyboardButton(text=f"{score} ⭐", callback_data=f"review_{appointment_id}_{score}")
        for score in range(1, 6)
    ]
    rows = [stars_row]
    if not has_future_booking:
        rows.append([InlineKeyboardButton(text="📅 Записатися завчасно", callback_data="booking:start")])
    rows.append([InlineKeyboardButton(text="📸 Надіслати відгук / фото нігтів", callback_data=f"wear_fb:{appointment_id}")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_master_requests_keyboard(appointment_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="✅ Підтвердити", callback_data=f"m_req:ok:{appointment_id}"),
                InlineKeyboardButton(text="❌ Відхилити", callback_data=f"m_req:no:{appointment_id}"),
            ]
        ]
    )


def get_master_receipt_approval_keyboard(appointment_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="✅ Підтвердити оплату", callback_data=f"m_pay:ok:{appointment_id}"),
                InlineKeyboardButton(text="❌ Відхилити чек", callback_data=f"m_pay:no:{appointment_id}"),
            ]
        ]
    )


def get_admin_main_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="🔄 Режим: SOLO / STUDIO", callback_data="adm:toggle_mode")],
            [InlineKeyboardButton(text="💳 Вимога завдатку (Увімк/Вимк)", callback_data="adm:toggle_deposit")],
            [InlineKeyboardButton(text="💰 Сума завдатку", callback_data="adm:set_deposit_amount")],
            [InlineKeyboardButton(text="🏦 Банківська картка", callback_data="adm:set_card")],
            [InlineKeyboardButton(text="🏺 Посилання на Monobank банку", callback_data="adm:set_jar")],
            [InlineKeyboardButton(text="📍 Адреса студії", callback_data="adm:set_address")],
            [InlineKeyboardButton(text="📸 Instagram", callback_data="adm:set_insta")],
        ]
    )


def get_client_main_inline_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="💅 Записатися онлайн", callback_data="m:book")],
            [
                InlineKeyboardButton(text="📅 Мої записи", callback_data="m:my_apts"),
                InlineKeyboardButton(text="📋 Прайс", callback_data="m:price"),
            ],
            [
                InlineKeyboardButton(text="⭐ Відгуки клієнтів", callback_data="m:reviews:0"),
                InlineKeyboardButton(text="📝 Лист очікування", callback_data="m:waitlist"),
            ],
            [
                InlineKeyboardButton(text="👩🎨 Про студію", callback_data="m:about"),
            ],
        ]
    )


def get_nav_back_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="💅 Записатися онлайн", callback_data="m:book"),
                InlineKeyboardButton(text="« Головне меню", callback_data="m:main"),
            ]
        ]
    )


def get_booking_summary_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="✅ Підтвердити запис", callback_data="bk:confirm")],
            [InlineKeyboardButton(text="✏️ Змінити мої дані (ім'я / телефон)", callback_data="bk:edit_profile")],
            [InlineKeyboardButton(text="❌ Скасувати", callback_data="booking:cancel")],
        ]
    )


def get_master_notification_keyboard(appointment_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="✅ Підтвердити", callback_data=f"m_apt:confirm:{appointment_id}"),
                InlineKeyboardButton(text="❌ Відхилити", callback_data=f"m_apt:reject:{appointment_id}"),
            ]
        ]
    )


def create_waitlist_calendar(year: int, month: int, today_str: str) -> InlineKeyboardMarkup:
    month_names_ua = [
        "", "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
        "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
    ]
    header = f"{month_names_ua[month]} {year}"

    prev_month = month - 1
    prev_year = year
    if prev_month < 1:
        prev_month = 12
        prev_year -= 1

    next_month = month + 1
    next_year = year
    if next_month > 12:
        next_month = 1
        next_year += 1

    buttons = [
        [
            InlineKeyboardButton(text="◀️", callback_data=f"wl_cal:nav:{prev_year}:{prev_month}"),
            InlineKeyboardButton(text=header, callback_data="wl_cal:ignore"),
            InlineKeyboardButton(text="▶️", callback_data=f"wl_cal:nav:{next_year}:{next_month}"),
        ]
    ]

    dow_row = [
        InlineKeyboardButton(text=day, callback_data="wl_cal:ignore")
        for day in ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
    ]
    buttons.append(dow_row)

    month_matrix = calendar.monthcalendar(year, month)
    today_dt = datetime.strptime(today_str, "%Y-%m-%d").date()

    for week in month_matrix:
        row = []
        for day in week:
            if day == 0:
                row.append(InlineKeyboardButton(text=" ", callback_data="wl_cal:ignore"))
            else:
                day_date = datetime(year, month, day).date()
                date_iso = f"{year:04d}-{month:02d}-{day:02d}"
                if day_date < today_dt:
                    row.append(InlineKeyboardButton(text=f"▫️{day}", callback_data="wl_cal:blocked"))
                else:
                    row.append(InlineKeyboardButton(text=f"{day}", callback_data=f"wl_cal:day:{date_iso}"))
        buttons.append(row)

    buttons.append([
        InlineKeyboardButton(text="💅 Записатися онлайн", callback_data="m:book"),
        InlineKeyboardButton(text="« Головне меню", callback_data="m:main"),
    ])
    return InlineKeyboardMarkup(inline_keyboard=buttons)

