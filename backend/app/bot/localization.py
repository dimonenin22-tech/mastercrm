import html
from typing import Optional


BOT_TEXTS = {
    "uk": {
        "welcome": (
            "Привіт, <b>{name}</b>! ✨\n\n"
            "Раді вітати вас у боті онлайн-запису студії <b>{studio}</b> 💅\n"
            "Тут ви можете обрати зручний час для манікюру, переглянути актуальний прайс або перевірити свої записи.\n\n"
            "Оберіть потрібну дію в меню нижче 👇"
        ),
        "main_menu_title": "Головне меню <b>{studio}</b> ✨\n\nРаді вітати, <b>{name}</b>! Оберіть потрібний пункт нижче 👇",
        "menu_quick": "💅 Швидке меню:",
        "btn_book": "💅 Записатися онлайн",
        "btn_price": "📋 Прайс",
        "btn_my_bookings": "📅 Мої записи",
        "btn_about": "🏢 Про студію",
        "btn_contacts": "📞 Контакти",
        "btn_waitlist": "📝 Лист очікування",
        "btn_cancel": "❌ Скасувати",
        "btn_back_cat": "◀️ Назад до категорій",
        "btn_back_date": "◀️ Назад до вибору дати",
        "btn_discuss_time": "💬 Обговорити інший час",
        "btn_waitlist_action": "📝 Стати в лист очікування",
        "btn_confirm": "✅ Підтвердити запис",
        "step_cat": "💅 <b>Крок 1/5: Оберіть категорію послуг:</b>",
        "step_srv": "💅 <b>Крок 2/5: Оберіть послугу:</b>",
        "step_date": "📅 <b>Крок 3/5: Оберіть день у календарі:</b>",
        "step_time": "⏰ <b>Крок 4/5: Вільні віконця на {date}:</b>\nОберіть зручний час:",
        "step_time_full": (
            "😔 На дату <b>{date}</b> вільних автоматичних віконець немає.\n\n"
            "Ви можете стати у <b>Лист очікування</b> або обрати опцію <b>Обговорити інший час</b>."
        ),
        "booking_summary_title": "💅 <b>Крок 5/5: Перевірте деталі вашого запису:</b>",
        "booking_summary_srv": "▫️ <b>Послуга:</b> {title} ({duration} хв)",
        "booking_summary_date": "▫️ <b>Дата та час:</b> {date} о {time}",
        "booking_summary_price": "▫️ <b>Вартість:</b> {price}",
        "booking_summary_client": "▫️ <b>Клієнт:</b> {name} ({phone})",
        "booking_confirmed": "✅ Запис прийнято! Майстер підтвердить його найближчим часом. Чекаємо на вас у студії «{studio}».",
        "price_title": "📋 <b>Наш прайс-лист:</b>\n",
        "price_updating": "📋 Прайс-лист наразі оновлюється.",
        "my_bookings_empty": "📅 У вас наразі немає активних записів.",
        "my_bookings_title": "📅 <b>Ваші записи:</b>\n",
        "min_unit": "хв",
    },
    "en": {
        "welcome": (
            "Hello, <b>{name}</b>! ✨\n\n"
            "Welcome to <b>{studio}</b> online booking bot 💅\n"
            "Here you can choose a convenient time for your nails, check our price list, or manage your bookings.\n\n"
            "Please select an option below 👇"
        ),
        "main_menu_title": "Main Menu <b>{studio}</b> ✨\n\nWelcome, <b>{name}</b>! Choose an option below 👇",
        "menu_quick": "💅 Quick Menu:",
        "btn_book": "💅 Book Appointment",
        "btn_price": "📋 Price List",
        "btn_my_bookings": "📅 My Bookings",
        "btn_about": "🏢 About Studio",
        "btn_contacts": "📞 Contact Us",
        "btn_waitlist": "📝 Waiting List",
        "btn_cancel": "❌ Cancel",
        "btn_back_cat": "◀️ Back to Categories",
        "btn_back_date": "◀️ Back to Dates",
        "btn_discuss_time": "💬 Request Custom Time",
        "btn_waitlist_action": "📝 Join Waiting List",
        "btn_confirm": "✅ Confirm Booking",
        "step_cat": "💅 <b>Step 1/5: Select Service Category:</b>",
        "step_srv": "💅 <b>Step 2/5: Select Service:</b>",
        "step_date": "📅 <b>Step 3/5: Choose Date on Calendar:</b>",
        "step_time": "⏰ <b>Step 4/5: Available Time Slots for {date}:</b>\nSelect your preferred time:",
        "step_time_full": (
            "😔 There are no automatic slots available for <b>{date}</b>.\n\n"
            "You can join our <b>Waiting List</b> or request a custom time."
        ),
        "booking_summary_title": "💅 <b>Step 5/5: Please verify your booking details:</b>",
        "booking_summary_srv": "▫️ <b>Service:</b> {title} ({duration} min)",
        "booking_summary_date": "▫️ <b>Date & Time:</b> {date} at {time}",
        "booking_summary_price": "▫️ <b>Price:</b> {price}",
        "booking_summary_client": "▫️ <b>Client:</b> {name} ({phone})",
        "booking_confirmed": "✅ Booking received! The master will confirm it shortly. Looking forward to seeing you at «{studio}».",
        "price_title": "📋 <b>Our Price List:</b>\n",
        "price_updating": "📋 Price list is currently being updated.",
        "my_bookings_empty": "📅 You have no upcoming bookings.",
        "my_bookings_title": "📅 <b>Your Bookings:</b>\n",
        "min_unit": "min",
    }
}


def t_bot(lang: str, key: str, **kwargs) -> str:
    lang_dict = BOT_TEXTS.get(lang, BOT_TEXTS["uk"])
    text_template = lang_dict.get(key, BOT_TEXTS["uk"].get(key, ""))
    if kwargs:
        return text_template.format(**kwargs)
    return text_template


def format_bot_price(amount: float, currency: str = "$") -> str:
    amt = int(amount)
    if currency == "$":
        return f"${amt}"
    elif currency == "€":
        return f"€{amt}"
    return f"{amt} грн"
