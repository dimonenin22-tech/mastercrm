import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Optional

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
)
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    mapped_column,
    relationship,
)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def generate_uuid() -> str:
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


class BaseMixin:
    id: Mapped[str] = mapped_column(
        String(64),
        primary_key=True,
        default=generate_uuid
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=utc_now,
        nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=utc_now,
        onupdate=utc_now,
        nullable=False
    )
    deleted: Mapped[bool] = mapped_column(
        Boolean,
        default=False,
        nullable=False,
        index=True
    )
    version: Mapped[int] = mapped_column(
        Integer,
        default=1,
        nullable=False
    )

    def mark_updated(self) -> None:
        self.updated_at = utc_now()
        self.version += 1

    def soft_delete(self) -> None:
        self.deleted = True
        self.mark_updated()


class Master(Base, BaseMixin):
    __tablename__ = "masters"

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    phone: Mapped[str] = mapped_column(String(64), nullable=False)
    telegram_chat_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    role: Mapped[str] = mapped_column(String(32), default="master", nullable=False)  # "owner" or "master"
    specialization: Mapped[Optional[str]] = mapped_column(String(255), default="Nail Master")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    devices: Mapped[list["Device"]] = relationship("Device", back_populates="master", cascade="all, delete-orphan")
    appointments: Mapped[list["Appointment"]] = relationship("Appointment", back_populates="master")
    incomes: Mapped[list["Income"]] = relationship("Income", back_populates="master")
    expenses: Mapped[list["Expense"]] = relationship("Expense", back_populates="master")


class Client(Base, BaseMixin):
    __tablename__ = "clients"

    telegram_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    first_name: Mapped[str] = mapped_column(String(128), nullable=False)
    last_name: Mapped[Optional[str]] = mapped_column(String(128), nullable=True, default="")
    phone: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True, default="")
    total_visits: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    total_spent: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    last_visit_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    visits_since_last_removal: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    last_removal_date: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    no_show_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_blacklisted: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    discount_percent: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    appointments: Mapped[list["Appointment"]] = relationship("Appointment", back_populates="client")
    waiting_lists: Mapped[list["WaitingList"]] = relationship("WaitingList", back_populates="client")
    reviews: Mapped[list["Review"]] = relationship("Review", back_populates="client")


class ServiceCategory(Base, BaseMixin):
    __tablename__ = "service_categories"

    name: Mapped[str] = mapped_column(String(128), nullable=False)
    order_index: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    icon_emoji: Mapped[str] = mapped_column(String(16), default="💅", nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    services: Mapped[list["Service"]] = relationship("Service", back_populates="category")


class Service(Base, BaseMixin):
    __tablename__ = "services"

    category_id: Mapped[str] = mapped_column(String(64), ForeignKey("service_categories.id"), nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    price: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    duration_minutes: Mapped[int] = mapped_column(Integer, default=60, nullable=False)
    buffer_minutes: Mapped[int] = mapped_column(Integer, default=15, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    category: Mapped["ServiceCategory"] = relationship("ServiceCategory", back_populates="services")
    appointments: Mapped[list["Appointment"]] = relationship("Appointment", back_populates="service")
    consumables: Mapped[list["ServiceConsumable"]] = relationship("ServiceConsumable", back_populates="service", cascade="all, delete-orphan")


class Appointment(Base, BaseMixin):
    __tablename__ = "appointments"

    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False, index=True)
    client_id: Mapped[str] = mapped_column(String(64), ForeignKey("clients.id"), nullable=False, index=True)
    service_id: Mapped[str] = mapped_column(String(64), ForeignKey("services.id"), nullable=False, index=True)
    date: Mapped[str] = mapped_column(String(10), nullable=False, index=True)  # YYYY-MM-DD
    start_time: Mapped[str] = mapped_column(String(32), nullable=False)  # HH:MM or "За домовленістю"
    end_time: Mapped[str] = mapped_column(String(32), nullable=False)    # HH:MM
    status: Mapped[str] = mapped_column(
        String(32),
        default="PENDING",
        nullable=False,
        index=True
    )  # PENDING, CONFIRMED, COMPLETED, CANCELLED_BY_CLIENT, CANCELLED_BY_MASTER, NO_SHOW
    total_price: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    deposit_amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    is_deposit_paid: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    cancellation_reason: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")

    master: Mapped["Master"] = relationship("Master", back_populates="appointments")
    client: Mapped["Client"] = relationship("Client", back_populates="appointments")
    service: Mapped["Service"] = relationship("Service", back_populates="appointments")
    visit_brief: Mapped[Optional["VisitBrief"]] = relationship("VisitBrief", back_populates="appointment", uselist=False, cascade="all, delete-orphan")
    incomes: Mapped[list["Income"]] = relationship("Income", back_populates="appointment")
    reviews: Mapped[list["Review"]] = relationship("Review", back_populates="appointment")
    notifications: Mapped[list["NotificationLog"]] = relationship("NotificationLog", back_populates="appointment")


class VisitBrief(Base, BaseMixin):
    __tablename__ = "visit_briefs"

    appointment_id: Mapped[str] = mapped_column(String(64), ForeignKey("appointments.id"), unique=True, nullable=False)
    client_id: Mapped[str] = mapped_column(String(64), ForeignKey("clients.id"), nullable=False)
    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False)
    material_notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True, default="")
    nail_condition: Mapped[Optional[str]] = mapped_column(Text, nullable=True, default="")
    coffee_prefs: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")
    photo_url: Mapped[Optional[str]] = mapped_column(String(512), nullable=True, default="")

    appointment: Mapped["Appointment"] = relationship("Appointment", back_populates="visit_brief")


class WaitingList(Base, BaseMixin):
    __tablename__ = "waiting_lists"

    client_id: Mapped[str] = mapped_column(String(64), ForeignKey("clients.id"), nullable=False)
    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False)
    service_id: Mapped[str] = mapped_column(String(64), ForeignKey("services.id"), nullable=False)
    preferred_date: Mapped[str] = mapped_column(String(10), nullable=False)  # YYYY-MM-DD
    status: Mapped[str] = mapped_column(String(32), default="ACTIVE", nullable=False)  # ACTIVE, NOTIFIED, BOOKED, CANCELLED

    client: Mapped["Client"] = relationship("Client", back_populates="waiting_lists")


class Income(Base, BaseMixin):
    __tablename__ = "incomes"

    appointment_id: Mapped[Optional[str]] = mapped_column(String(64), ForeignKey("appointments.id"), nullable=True)
    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False)
    client_id: Mapped[Optional[str]] = mapped_column(String(64), ForeignKey("clients.id"), nullable=True)
    amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    payment_method: Mapped[str] = mapped_column(String(32), default="CASH", nullable=False)  # CASH, CARD, ONLINE, DEPOSIT
    date: Mapped[str] = mapped_column(String(10), nullable=False, index=True)  # YYYY-MM-DD
    category: Mapped[str] = mapped_column(String(32), default="services", nullable=False)  # services or tips

    appointment: Mapped[Optional["Appointment"]] = relationship("Appointment", back_populates="incomes")
    master: Mapped["Master"] = relationship("Master", back_populates="incomes")


class Expense(Base, BaseMixin):
    __tablename__ = "expenses"

    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False)
    category: Mapped[str] = mapped_column(String(32), default="MATERIALS", nullable=False)  # MATERIALS, RENT, ADS, OTHER
    amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    payment_method: Mapped[str] = mapped_column(String(32), default="CASH", nullable=False)
    date: Mapped[str] = mapped_column(String(10), nullable=False, index=True)  # YYYY-MM-DD
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")

    master: Mapped["Master"] = relationship("Master", back_populates="expenses")


class Product(Base, BaseMixin):
    __tablename__ = "products"

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    unit: Mapped[str] = mapped_column(String(16), default="шт", nullable=False)  # шт, мл, г
    current_stock: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)
    min_stock_alert: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("5.00"), nullable=False)
    purchase_price: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("0.00"), nullable=False)

    consumables: Mapped[list["ServiceConsumable"]] = relationship("ServiceConsumable", back_populates="product", cascade="all, delete-orphan")
    movements: Mapped[list["StockMovement"]] = relationship("StockMovement", back_populates="product", cascade="all, delete-orphan")


class ServiceConsumable(Base, BaseMixin):
    __tablename__ = "service_consumables"

    service_id: Mapped[str] = mapped_column(String(64), ForeignKey("services.id"), nullable=False)
    product_id: Mapped[str] = mapped_column(String(64), ForeignKey("products.id"), nullable=False)
    quantity_per_service: Mapped[Decimal] = mapped_column(Numeric(10, 2), default=Decimal("1.00"), nullable=False)

    service: Mapped["Service"] = relationship("Service", back_populates="consumables")
    product: Mapped["Product"] = relationship("Product", back_populates="consumables")


class StockMovement(Base, BaseMixin):
    __tablename__ = "stock_movements"

    product_id: Mapped[str] = mapped_column(String(64), ForeignKey("products.id"), nullable=False)
    type: Mapped[str] = mapped_column(String(32), nullable=False)  # RESTOCK, DEDUCTION, AUDIT
    quantity: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
    appointment_id: Mapped[Optional[str]] = mapped_column(String(64), ForeignKey("appointments.id"), nullable=True)
    comment: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")

    product: Mapped["Product"] = relationship("Product", back_populates="movements")


class Review(Base, BaseMixin):
    __tablename__ = "reviews"

    appointment_id: Mapped[str] = mapped_column(String(64), ForeignKey("appointments.id"), nullable=False)
    client_id: Mapped[str] = mapped_column(String(64), ForeignKey("clients.id"), nullable=False)
    rating: Mapped[int] = mapped_column(Integer, nullable=False)  # 1..5
    comment: Mapped[Optional[str]] = mapped_column(Text, nullable=True, default="")
    moderation_status: Mapped[str] = mapped_column(String(32), default="PENDING", nullable=False)  # PENDING, APPROVED, REJECTED
    is_published: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    appointment: Mapped["Appointment"] = relationship("Appointment", back_populates="reviews")
    client: Mapped["Client"] = relationship("Client", back_populates="reviews")


class NotificationLog(Base, BaseMixin):
    __tablename__ = "notification_logs"

    appointment_id: Mapped[str] = mapped_column(String(64), ForeignKey("appointments.id"), nullable=False)
    recipient_tg_id: Mapped[int] = mapped_column(Integer, nullable=False)
    reminder_type: Mapped[str] = mapped_column(String(64), nullable=False)  # 24h, 2h, 10min_review, 2weeks_review, deposit_timeout
    sent_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    status: Mapped[str] = mapped_column(String(32), default="SUCCESS", nullable=False)  # SUCCESS, FAILED

    appointment: Mapped["Appointment"] = relationship("Appointment", back_populates="notifications")


class StudioSetting(Base, BaseMixin):
    __tablename__ = "studio_settings"

    key: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    value: Mapped[str] = mapped_column(Text, nullable=False, default="")
    description: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")


class Device(Base, BaseMixin):
    __tablename__ = "devices"

    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False)
    device_token: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    device_name: Mapped[str] = mapped_column(String(128), default="Android Device", nullable=False)
    otp_code: Mapped[Optional[str]] = mapped_column(String(16), nullable=True, index=True)
    otp_expires_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    last_synced_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)

    master: Mapped["Master"] = relationship("Master", back_populates="devices")


class Schedule(Base, BaseMixin):
    __tablename__ = "schedules"

    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False, index=True)
    day_of_week: Mapped[int] = mapped_column(Integer, nullable=False)  # 0=Mon .. 6=Sun
    start_time: Mapped[str] = mapped_column(String(8), default="09:00", nullable=False)
    end_time: Mapped[str] = mapped_column(String(8), default="19:00", nullable=False)
    slot_step_minutes: Mapped[int] = mapped_column(Integer, default=30, nullable=False)
    is_working_day: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    master: Mapped["Master"] = relationship("Master")


class ScheduleException(Base, BaseMixin):
    __tablename__ = "schedule_exceptions"

    master_id: Mapped[str] = mapped_column(String(64), ForeignKey("masters.id"), nullable=False, index=True)
    date: Mapped[str] = mapped_column(String(10), nullable=False, index=True)  # YYYY-MM-DD
    start_time: Mapped[Optional[str]] = mapped_column(String(8), nullable=True)
    end_time: Mapped[Optional[str]] = mapped_column(String(8), nullable=True)
    is_day_off: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    reason: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")

    master: Mapped["Master"] = relationship("Master")


class SharedResource(Base, BaseMixin):
    __tablename__ = "shared_resources"

    name: Mapped[str] = mapped_column(String(128), nullable=False)
    quantity: Mapped[int] = mapped_column(Integer, default=1, nullable=False)

    service_resources: Mapped[list["ServiceResource"]] = relationship("ServiceResource", back_populates="resource", cascade="all, delete-orphan")


class ServiceResource(Base, BaseMixin):
    __tablename__ = "service_resources"

    service_id: Mapped[str] = mapped_column(String(64), ForeignKey("services.id"), nullable=False, index=True)
    resource_id: Mapped[str] = mapped_column(String(64), ForeignKey("shared_resources.id"), nullable=False, index=True)

    service: Mapped["Service"] = relationship("Service")
    resource: Mapped["SharedResource"] = relationship("SharedResource", back_populates="service_resources")


class AppointmentHistory(Base, BaseMixin):
    __tablename__ = "appointment_histories"

    appointment_id: Mapped[str] = mapped_column(String(64), ForeignKey("appointments.id"), nullable=False, index=True)
    previous_status: Mapped[Optional[str]] = mapped_column(String(32), nullable=True)
    new_status: Mapped[str] = mapped_column(String(32), nullable=False)
    changed_by: Mapped[str] = mapped_column(String(32), default="system", nullable=False)  # client, master, system
    comment: Mapped[Optional[str]] = mapped_column(Text, nullable=True, default="")

    appointment: Mapped["Appointment"] = relationship("Appointment")


class Photo(Base, BaseMixin):
    __tablename__ = "photos"

    appointment_id: Mapped[Optional[str]] = mapped_column(String(64), ForeignKey("appointments.id"), nullable=True, index=True)
    client_id: Mapped[Optional[str]] = mapped_column(String(64), ForeignKey("clients.id"), nullable=True, index=True)
    photo_type: Mapped[str] = mapped_column(String(32), default="REFERENCE", nullable=False)  # BEFORE, AFTER, RECEIPT, REFERENCE
    file_url: Mapped[str] = mapped_column(String(512), nullable=False)
    thumbnail_url: Mapped[Optional[str]] = mapped_column(String(512), nullable=True, default="")

    appointment: Mapped[Optional["Appointment"]] = relationship("Appointment")
    client: Mapped[Optional["Client"]] = relationship("Client")


class Supplier(Base, BaseMixin):
    __tablename__ = "suppliers"

    name: Mapped[str] = mapped_column(String(128), nullable=False)
    contact_person: Mapped[Optional[str]] = mapped_column(String(128), nullable=True, default="")
    phone: Mapped[Optional[str]] = mapped_column(String(64), nullable=True, default="")
    website: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default="")
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True, default="")


class ReminderTemplate(Base, BaseMixin):
    __tablename__ = "reminder_templates"

    type: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)  # WELCOME, REMINDER_24H, REMINDER_2H, REVIEW_10MIN, REVIEW_2WEEKS
    template_text: Mapped[str] = mapped_column(Text, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

