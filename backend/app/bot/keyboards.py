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


def get_client_main_keyboard(lang: str = "uk") -> ReplyKeyboardMarkup:
    if lang == "en":
        return ReplyKeyboardMarkup(
            keyboard=[
                [KeyboardButton(text="💅 Book Online")],
                [KeyboardButton(text="📅 My Bookings"), KeyboardButton(text="📋 Price List")],
                [KeyboardButton(text="🏢 About Studio"), KeyboardButton(text="📝 Waiting List")],
            ],
            resize_keyboard=True,
            one_time_keyboard=False,
        )
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text="💅 Записатися онлайн")],
            [KeyboardButton(text="📅 Мої записи"), KeyboardButton(text="📋 Прайс")],
            [KeyboardButton(text="👩🎨 Про майстра / Адреса"), KeyboardButton(text="📝 Лист очікування")],
        ],
        resize_keyboard=True,
        one_time_keyboard=False,
    )


def get_contact_request_keyboard(lang: str = "uk") -> ReplyKeyboardMarkup:
    if lang == "en":
        return ReplyKeyboardMarkup(
            keyboard=[
                [KeyboardButton(text="📱 Share Phone Number", request_contact=True)],
                [KeyboardButton(text="❌ Cancel Booking")],
            ],
            resize_keyboard=True,
            one_time_keyboard=True,
        )
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text="📱 Поділитися номером телефону", request_contact=True)],
            [KeyboardButton(text="❌ Скасувати запис")],
        ],
        resize_keyboard=True,
        one_time_keyboard=True,
    )


def create_inline_calendar(year: int, month: int, today_str: str, lang: str = "uk") -> InlineKeyboardMarkup:
    month_names_ua = [
        "", "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
        "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
    ]
    month_names_en = [
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    ]
    month_names = month_names_en if lang == "en" else month_names_ua
    header = f"{month_names[month]} {year}"

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
    dow_list = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"] if lang == "en" else ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
    dow_row = [
        InlineKeyboardButton(text=day, callback_data="cal:ignore")
        for day in dow_list
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

    cancel_label = "❌ Cancel" if lang == "en" else "❌ Скасувати"
    buttons.append([InlineKeyboardButton(text=cancel_label, callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_categories_keyboard(categories: list[tuple[str, str, str]], lang: str = "uk") -> InlineKeyboardMarkup:
    # categories: [(id, name, icon_emoji)]
    buttons = []
    for c_id, name, icon in categories:
        buttons.append([InlineKeyboardButton(text=f"{icon} {name}", callback_data=f"cat:{c_id}")])
    cancel_label = "❌ Cancel" if lang == "en" else "❌ Скасувати"
    buttons.append([InlineKeyboardButton(text=cancel_label, callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_services_keyboard(services: list[tuple[str, str, float, int]], currency: str = "$", lang: str = "uk") -> InlineKeyboardMarkup:
    # services: [(id, title, price, duration)]
    buttons = []
    min_unit = "min" if lang == "en" else "хв"
    for s_id, title, price, dur in services:
        if currency == "$":
            curr_label = f"${int(price)}"
        elif currency == "€":
            curr_label = f"€{int(price)}"
        else:
            curr_label = f"{int(price)} грн"
        label = f"{title} — {curr_label} ({dur} {min_unit})"
        buttons.append([InlineKeyboardButton(text=label, callback_data=f"srv:{s_id}")])
    back_label = "◀️ Back to Categories" if lang == "en" else "◀️ Назад до категорій"
    cancel_label = "❌ Cancel" if lang == "en" else "❌ Скасувати"
    buttons.append([InlineKeyboardButton(text=back_label, callback_data="booking:back_cat")])
    buttons.append([InlineKeyboardButton(text=cancel_label, callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_time_slots_keyboard(slots: list, lang: str = "uk") -> InlineKeyboardMarkup:
    buttons = []
    # 3 slots per row
    row = []
    for item in slots:
        if isinstance(item, tuple):
            time_str, is_passed = item
        else:
            time_str, is_passed = item, False

        if is_passed:
            row.append(InlineKeyboardButton(text=f"▫️{time_str}", callback_data="time:passed"))
        else:
            row.append(InlineKeyboardButton(text=time_str, callback_data=f"time:{time_str}"))
        if len(row) == 3:
            buttons.append(row)
            row = []
    if row:
        buttons.append(row)

    custom_label = "💬 Request Custom Time" if lang == "en" else "💬 Обговорити інший час"
    waitlist_label = "📝 Join Waiting List" if lang == "en" else "📝 Стати в лист очікування"
    back_label = "◀️ Back to Dates" if lang == "en" else "◀️ Назад до вибору дати"
    cancel_label = "❌ Cancel" if lang == "en" else "❌ Скасувати"

    buttons.append([InlineKeyboardButton(text=custom_label, callback_data="time:custom")])
    buttons.append([InlineKeyboardButton(text=waitlist_label, callback_data="time:waitlist")])
    buttons.append([InlineKeyboardButton(text=back_label, callback_data="booking:back_date")])
    buttons.append([InlineKeyboardButton(text=cancel_label, callback_data="booking:cancel")])
    return InlineKeyboardMarkup(inline_keyboard=buttons)


def get_cancel_appointment_keyboard(appointment_id: str, lang: str = "uk") -> InlineKeyboardMarkup:
    cancel_text = "❌ Cancel this booking" if lang == "en" else "❌ Скасувати цей запис"
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text=cancel_text, callback_data=f"c_can:{appointment_id}")]
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


def get_wear_check_keyboard(appointment_id: str, has_future_booking: bool, lang: str = "uk") -> InlineKeyboardMarkup:
    stars_row = [
        InlineKeyboardButton(text=f"{score} ⭐", callback_data=f"review_{appointment_id}_{score}")
        for score in range(1, 6)
    ]
    rows = [stars_row]
    if not has_future_booking:
        book_ahead_text = "📅 Book next appointment" if lang == "en" else "📅 Записатися завчасно"
        rows.append([InlineKeyboardButton(text=book_ahead_text, callback_data="booking:start")])
    fb_text = "📸 Send feedback / photo of nails" if lang == "en" else "📸 Надіслати відгук / фото нігтів"
    rows.append([InlineKeyboardButton(text=fb_text, callback_data=f"wear_fb:{appointment_id}")])
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
            [InlineKeyboardButton(text="📱 Завантажити застосунок (APK)", callback_data="adm:get_app")],
        ]
    )


def get_client_main_inline_keyboard(lang: str = "uk") -> InlineKeyboardMarkup:
    if lang == "en":
        return InlineKeyboardMarkup(
            inline_keyboard=[
                [InlineKeyboardButton(text="💅 Book Online", callback_data="m:book")],
                [
                    InlineKeyboardButton(text="📅 My Bookings", callback_data="m:my_apts"),
                    InlineKeyboardButton(text="📋 Price List", callback_data="m:price"),
                ],
                [
                    InlineKeyboardButton(text="⭐ Client Reviews", callback_data="m:reviews:0"),
                    InlineKeyboardButton(text="📝 Waiting List", callback_data="m:waitlist"),
                ],
                [
                    InlineKeyboardButton(text="🏢 About Studio", callback_data="m:about"),
                ],
            ]
        )
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


def get_nav_back_keyboard(lang: str = "uk") -> InlineKeyboardMarkup:
    book_text = "💅 Book Online" if lang == "en" else "💅 Записатися онлайн"
    main_text = "« Main Menu" if lang == "en" else "« Головне меню"
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text=book_text, callback_data="m:book"),
                InlineKeyboardButton(text=main_text, callback_data="m:main"),
            ]
        ]
    )


def get_booking_summary_keyboard(lang: str = "uk") -> InlineKeyboardMarkup:
    confirm_text = "✅ Confirm Booking" if lang == "en" else "✅ Підтвердити запис"
    edit_text = "✏️ Edit Info (name / phone)" if lang == "en" else "✏️ Змінити мої дані (ім'я / телефон)"
    cancel_text = "❌ Cancel" if lang == "en" else "❌ Скасувати"
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text=confirm_text, callback_data="bk:confirm")],
            [InlineKeyboardButton(text=edit_text, callback_data="bk:edit_profile")],
            [InlineKeyboardButton(text=cancel_text, callback_data="booking:cancel")],
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


def create_waitlist_calendar(year: int, month: int, today_str: str, lang: str = "uk") -> InlineKeyboardMarkup:
    month_names_ua = [
        "", "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
        "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
    ]
    month_names_en = [
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    ]
    month_names = month_names_en if lang == "en" else month_names_ua
    header = f"{month_names[month]} {year}"

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

    dow_list = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"] if lang == "en" else ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"]
    dow_row = [
        InlineKeyboardButton(text=day, callback_data="wl_cal:ignore")
        for day in dow_list
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

    book_text = "💅 Book Online" if lang == "en" else "💅 Записатися онлайн"
    main_text = "« Main Menu" if lang == "en" else "« Головне меню"
    buttons.append([
        InlineKeyboardButton(text=book_text, callback_data="m:book"),
        InlineKeyboardButton(text=main_text, callback_data="m:main"),
    ])
    return InlineKeyboardMarkup(inline_keyboard=buttons)
