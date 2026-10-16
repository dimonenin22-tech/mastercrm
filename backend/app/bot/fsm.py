from aiogram.fsm.state import State, StatesGroup


class BookingStates(StatesGroup):
    waiting_for_category = State()
    waiting_for_service = State()
    waiting_for_date = State()
    waiting_for_time = State()
    waiting_for_phone = State()
    waiting_for_custom_note = State()


class ReceiptUploadStates(StatesGroup):
    waiting_for_photo = State()


class ReviewStates(StatesGroup):
    waiting_for_comment = State()


class WaitingListStates(StatesGroup):
    waiting_for_category = State()
    waiting_for_service = State()
    waiting_for_date = State()
    waiting_for_phone = State()


class AdminSettingStates(StatesGroup):
    waiting_for_value = State()
