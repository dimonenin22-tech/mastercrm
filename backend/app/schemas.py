from datetime import datetime
from decimal import Decimal
from typing import Any, Generic, Optional, TypeVar
from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ApiError(BaseModel):
    code: str
    message: str


class ApiResponse(BaseModel, Generic[T]):
    success: bool
    status: str  # "success" or "error"
    data: Optional[T] = None
    error: Optional[ApiError] = None

    @classmethod
    def ok(cls, data: Any = None) -> "ApiResponse[Any]":
        return cls(success=True, status="success", data=data, error=None)

    @classmethod
    def fail(cls, code: str, message: str) -> "ApiResponse[Any]":
        return cls(success=False, status="error", data=None, error=ApiError(code=code, message=message))


class MasterDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    phone: str
    telegram_chat_id: Optional[int] = None
    role: str
    specialization: Optional[str] = None
    is_active: bool
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class ClientDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    telegram_id: Optional[int] = None
    first_name: str
    last_name: Optional[str] = ""
    phone: str
    notes: Optional[str] = ""
    total_visits: int
    total_spent: Decimal
    last_visit_at: Optional[datetime] = None
    visits_since_last_removal: int
    last_removal_date: Optional[datetime] = None
    no_show_count: int = 0
    is_blacklisted: bool = False
    discount_percent: int = 0
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class ServiceDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    category_id: str
    title: str
    price: Decimal
    duration_minutes: int
    buffer_minutes: int
    is_active: bool
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class AppointmentDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    master_id: str
    client_id: str
    service_id: str
    date: str
    start_time: str
    end_time: str
    status: str
    total_price: Decimal
    deposit_amount: Decimal
    is_deposit_paid: bool
    cancellation_reason: Optional[str] = ""
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class IncomeDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    appointment_id: Optional[str] = None
    master_id: str
    client_id: Optional[str] = None
    amount: Decimal
    payment_method: str
    date: str
    category: str
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class ExpenseDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    master_id: str
    category: str
    amount: Decimal
    payment_method: str
    date: str
    description: Optional[str] = ""
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class ProductDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    unit: str
    current_stock: Decimal
    min_stock_alert: Decimal
    purchase_price: Decimal
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class ReviewDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    appointment_id: str
    client_id: str
    rating: int
    comment: Optional[str] = ""
    moderation_status: str
    is_published: bool
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class ScheduleDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    master_id: str
    day_of_week: int
    start_time: str
    end_time: str
    slot_step_minutes: int
    is_working_day: bool


class ScheduleExceptionDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    master_id: str
    date: str
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    is_day_off: bool
    reason: Optional[str] = ""


class PhotoDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    appointment_id: Optional[str] = None
    client_id: Optional[str] = None
    photo_type: str
    file_url: str
    thumbnail_url: Optional[str] = ""


class ReminderTemplateDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    type: str
    template_text: str
    is_active: bool


class VisitBriefDto(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    appointment_id: str
    client_id: str
    master_id: str
    material_notes: Optional[str] = ""
    nail_condition: Optional[str] = ""
    coffee_prefs: Optional[str] = ""
    photo_url: Optional[str] = ""
    coating_condition: Optional[str] = ""
    nail_plate_type: Optional[str] = ""
    cuticle_type: Optional[str] = ""
    base_gel_used: Optional[str] = ""
    design_and_length: Optional[str] = ""
    health_notes: Optional[str] = ""
    photo_before_url: Optional[str] = ""
    photo_after_url: Optional[str] = ""
    created_at: datetime
    updated_at: datetime
    deleted: bool
    version: int


class SyncResponseData(BaseModel):
    appointments: list[AppointmentDto] = Field(default_factory=list)
    clients: list[ClientDto] = Field(default_factory=list)
    services: list[ServiceDto] = Field(default_factory=list)
    masters: list[MasterDto] = Field(default_factory=list)
    incomes: list[IncomeDto] = Field(default_factory=list)
    expenses: list[ExpenseDto] = Field(default_factory=list)
    products: list[ProductDto] = Field(default_factory=list)
    reviews: list[ReviewDto] = Field(default_factory=list)
    schedules: list[ScheduleDto] = Field(default_factory=list)
    visit_briefs: list[VisitBriefDto] = Field(default_factory=list)
    settings: dict[str, str] = Field(default_factory=dict)
    server_sync_time: str


