from aiogram.fsm.state import State, StatesGroup


class BookingStates(StatesGroup):
    waiting_for_category = State()
    waiting_for_service = State()
    waiting_for_date = State()
    waiting_for_time = State()
    waiting_for_name = State()
    waiting_for_contact = State()
    waiting_for_phone = State()
    waiting_for_custom_note = State()
    confirming_booking = State()


class ReceiptUploadStates(StatesGroup):
    waiting_for_photo = State()


class ReviewStates(StatesGroup):
    waiting_for_comment = State()
    waiting_for_post_review_text = State()
    waiting_for_post_review_stars = State()
    waiting_for_wear_text = State()
    waiting_for_wear_photo = State()


class WaitingListStates(StatesGroup):
    waiting_for_category = State()
    waiting_for_service = State()
    waiting_for_date = State()
    waiting_for_phone = State()


class AdminSettingStates(StatesGroup):
    waiting_for_value = State()
