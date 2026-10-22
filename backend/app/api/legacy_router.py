import asyncio
import hashlib
import html
import json
import logging
import secrets
from datetime import datetime, time, timedelta, timezone
from decimal import Decimal
from typing import Any, Optional
import pytz

import base64
import os
import uuid
from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import JSONResponse
from sqlalchemy import func, select, or_, and_
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db, is_sqlite
from app.models import (
    Appointment,
    AppointmentHistory,
    Client,
    Device,
    Expense,
    Income,
    Master,
    NotificationLog,
    Photo,
    Product,
    ReminderTemplate,
    Review,
    Schedule,
    ScheduleException,
    Service,
    ServiceCategory,
    ServiceConsumable,
    ServiceResource,
    SharedResource,
    StockMovement,
    StudioSetting,
    Supplier,
    VisitBrief,
    WaitingList,
    generate_uuid,
    utc_now,
)
from app.schemas import (
    ApiResponse,
    AppointmentDto,
    ClientDto,
    ExpenseDto,
    IncomeDto,
    MasterDto,
    PhotoDto,
    ProductDto,
    ReminderTemplateDto,
    ReviewDto,
    ScheduleDto,
    ScheduleExceptionDto,
    ServiceDto,
    SyncResponseData,
    VisitBriefDto,
)

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Unified API"])
_appointment_create_lock = asyncio.Lock()


async def get_current_master(
    authorization: Optional[str] = Header(None),
    db: AsyncSession = Depends(get_db),
) -> Optional[Master]:
    if not authorization or not authorization.startswith("Bearer "):
        return None
    token = authorization.replace("Bearer ", "").strip()
    stmt = (
        select(Device)
        .options(selectinload(Device.master))
        .where(Device.device_token == token, Device.is_active.is_(True), Device.deleted.is_(False))
    )
    result = await db.execute(stmt)
    device = result.scalar_one_or_none()
    if not device or not device.master or device.master.deleted or not device.master.is_active:
        return None
    return device.master


def calculate_end_time(start_str: str, duration_minutes: int) -> str:
    try:
        parts = start_str.split(":")
        start_dt = datetime(2026, 1, 1, int(parts[0]), int(parts[1]))
        end_dt = start_dt + timedelta(minutes=duration_minutes)
        return end_dt.strftime("%H:%M")
    except Exception:
        return "10:00"


def time_to_minutes(t_str: str) -> int:
    try:
        h, m = map(int, t_str.split(":"))
        return h * 60 + m
    except Exception:
        return 0


async def calculate_service_cost(service_id: str, db: AsyncSession) -> Decimal:
    try:
        c_stmt = (
            select(ServiceConsumable)
            .options(selectinload(ServiceConsumable.product))
            .where(ServiceConsumable.service_id == service_id)
        )
        c_res = await db.execute(c_stmt)
        consumables = c_res.scalars().all()
        total_cost = Decimal("0.00")
        for item in consumables:
            if item.product and not item.product.deleted:
                total_cost += item.quantity_per_service * item.product.purchase_price
        return total_cost
    except Exception:
        return Decimal("0.00")


async def notify_waiting_list_clients(db: AsyncSession, target_date: str, service_title: str) -> None:
    try:
        from app.bot.bot_instance import bot
        if not bot:
            return
        stmt = (
            select(WaitingList)
            .options(selectinload(WaitingList.client))
            .where(WaitingList.preferred_date == target_date, WaitingList.status == "ACTIVE", WaitingList.deleted.is_(False))
        )
        res = await db.execute(stmt)
        entries = res.scalars().all()
        for entry in entries:
            if entry.client and entry.client.telegram_id:
                msg = (
                    f"🎉 <b>З'явилося вільне віконце!</b>\n\n"
                    f"На дату <b>{target_date}</b> звільнився час для послуги «{service_title}».\n"
                    f"Встигніть записатися через меню /start !"
                )
                try:
                    await bot.send_message(chat_id=entry.client.telegram_id, text=msg, parse_mode="HTML")
                    entry.status = "NOTIFIED"
                    entry.mark_updated()
                except Exception as ex:
                    logger.warning("Failed to notify waiting list client: %s", ex)
        await db.commit()
    except Exception as e:
        logger.error("Error notifying waiting list: %s", e)


async def render_public_landing_page() -> str:
    from app.database import AsyncSessionLocal
    async with AsyncSessionLocal() as db:
        # Settings
        s_res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        settings_map = {s.key: s.value for s in s_res.scalars().all()}

        studio_name = settings_map.get("studio_name", "MasterCRM Beauty Studio")
        address = settings_map.get("address", "вул. Хрещатик, 1, оф. 10, Київ")
        phone = settings_map.get("phone", "+380 (99) 123-45-67")
        instagram = settings_map.get("instagram", "@master_crm_beauty")
        bot_username = settings_map.get("bot_username", "zachanails_bot").replace("@", "")

        # Owner master
        m_res = await db.execute(select(Master).where(Master.role == "owner", Master.deleted.is_(False)))
        owner = m_res.scalars().first()
        master_name = owner.name if owner else "Топ-Майстер"
        specialization = owner.specialization if owner else "Топ-Майстер / Власниця"

        # Active services
        srv_res = await db.execute(
            select(Service)
            .options(selectinload(Service.category))
            .where(Service.deleted.is_(False), Service.is_active.is_(True))
        )
        services = srv_res.scalars().all()

        # Approved reviews
        rev_res = await db.execute(
            select(Review)
            .options(selectinload(Review.client))
            .where(Review.is_published.is_(True), Review.moderation_status == "APPROVED", Review.deleted.is_(False))
            .order_by(Review.created_at.desc())
            .limit(10)
        )
        reviews = rev_res.scalars().all()

    # Build services HTML
    services_html = ""
    for s in services:
        cat_icon = s.category.icon_emoji if s.category else "💅"
        book_link = f"https://t.me/{bot_username}?start=srv_{s.id}"
        services_html += f"""
        <div class="service-card">
            <div class="service-info">
                <h3>{cat_icon} {html.escape(s.title)}</h3>
                <span class="duration">⏱️ {s.duration_minutes} хв</span>
            </div>
            <div class="service-action">
                <span class="price">{int(s.price)} грн</span>
                <a href="{book_link}" class="btn-book" target="_blank">Записатись ⚡</a>
            </div>
        </div>
        """

    # Build reviews HTML
    reviews_html = ""
    if reviews:
        for r in reviews:
            stars = "⭐" * r.rating
            c_name = r.client.first_name if r.client else "Клієнт"
            reviews_html += f"""
            <div class="review-card">
                <div class="review-header">
                    <span class="client-name">{html.escape(c_name)}</span>
                    <span class="stars">{stars}</span>
                </div>
                <p class="comment">{html.escape(r.comment or 'Чудовий сервіс та бездоганний манікюр!')}</p>
            </div>
            """
    else:
        reviews_html = '<div class="empty-state" style="color: #94a3b8; font-size: 13px; text-align: center; padding: 16px;">Поки немає опублікованих відгуків. Будьте першими!</div>'

    bot_link = f"https://t.me/{bot_username}"
    qr_url = f"https://api.qrserver.com/v1/create-qr-code/?size=180x180&data={bot_link}"

    return f"""<!DOCTYPE html>
<html lang="uk">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{html.escape(studio_name)} — Онлайн-запис</title>
    <style>
        :root {{
            --bg-main: #0f172a;
            --bg-card: #1e293b;
            --border: #334155;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --accent: #ec4899;
            --accent-hover: #db2777;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }}
        body {{ background-color: var(--bg-main); color: var(--text-main); min-height: 100vh; padding: 24px 16px; line-height: 1.5; }}
        .container {{ max-width: 680px; margin: 0 auto; }}
        .header {{ text-align: center; padding: 32px 0 24px; }}
        .avatar {{ width: 88px; height: 88px; border-radius: 50%; background: linear-gradient(135deg, #ec4899, #8b5cf6); margin: 0 auto 16px; display: flex; align-items: center; justify-content: center; font-size: 38px; box-shadow: 0 10px 25px -5px rgba(236, 72, 153, 0.4); }}
        h1 {{ font-size: 26px; font-weight: 800; margin-bottom: 6px; }}
        .subtitle {{ color: var(--accent); font-size: 15px; font-weight: 600; margin-bottom: 12px; }}
        .contacts {{ display: flex; flex-wrap: wrap; justify-content: center; gap: 12px; color: var(--text-muted); font-size: 13px; margin-top: 8px; }}
        .badge {{ background: var(--border); padding: 4px 10px; border-radius: 16px; display: inline-flex; align-items: center; gap: 4px; text-decoration: none; color: inherit; }}
        .section-title {{ font-size: 18px; font-weight: 700; margin: 32px 0 16px; display: flex; align-items: center; gap: 8px; color: var(--text-main); border-bottom: 1px solid var(--border); padding-bottom: 8px; }}
        .services-list {{ display: flex; flex-direction: column; gap: 12px; }}
        .service-card {{ background: var(--bg-card); border: 1px solid var(--border); border-radius: 14px; padding: 16px; display: flex; justify-content: space-between; align-items: center; transition: transform 0.15s, border-color 0.15s; }}
        .service-card:hover {{ transform: translateY(-2px); border-color: var(--accent); }}
        .service-info h3 {{ font-size: 15px; font-weight: 600; margin-bottom: 4px; }}
        .duration {{ font-size: 12px; color: var(--text-muted); }}
        .service-action {{ display: flex; align-items: center; gap: 12px; }}
        .price {{ font-size: 16px; font-weight: 700; color: var(--accent); }}
        .btn-book {{ background: var(--accent); color: white; text-decoration: none; padding: 8px 14px; border-radius: 10px; font-size: 13px; font-weight: 600; transition: background 0.15s; }}
        .btn-book:hover {{ background: var(--accent-hover); }}
        .reviews-list {{ display: grid; gap: 12px; }}
        .review-card {{ background: var(--bg-card); border: 1px solid var(--border); border-radius: 12px; padding: 14px; }}
        .review-header {{ display: flex; justify-content: space-between; margin-bottom: 6px; }}
        .client-name {{ font-weight: 600; font-size: 14px; }}
        .stars {{ font-size: 12px; }}
        .comment {{ font-size: 13px; color: var(--text-muted); }}
        .qr-section {{ text-align: center; margin: 40px 0 20px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 16px; padding: 24px; }}
        .qr-img {{ border-radius: 12px; background: white; padding: 8px; margin: 12px 0; width: 140px; height: 140px; }}
        .footer {{ text-align: center; color: var(--text-muted); font-size: 12px; margin-top: 32px; padding-top: 16px; border-top: 1px solid var(--border); }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="avatar">💅</div>
            <h1>{html.escape(studio_name)}</h1>
            <div class="subtitle">{html.escape(master_name)} • {html.escape(specialization)}</div>
            <div class="contacts">
                <span class="badge">📍 {html.escape(address)}</span>
                <span class="badge">📞 {html.escape(phone)}</span>
                <a href="https://instagram.com/{instagram.replace('@', '')}" target="_blank" class="badge">📸 {html.escape(instagram)}</a>
            </div>
        </div>

        <div class="section-title">📋 Прайс та швидкий запис онлайн</div>
        <div class="services-list">
            {services_html}
        </div>

        <div class="section-title">💬 Відгуки наших клієнтів</div>
        <div class="reviews-list">
            {reviews_html}
        </div>

        <div class="qr-section">
            <h3 style="font-size: 16px; margin-bottom: 4px;">Швидкий перехід у Telegram Бот</h3>
            <p style="font-size: 13px; color: var(--text-muted);">Відскануйте QR-код камерою смартфона для миттєвого запису</p>
            <img src="{qr_url}" alt="Telegram Bot QR" class="qr-img">
            <div>
                <a href="{bot_link}" target="_blank" class="btn-book" style="display: inline-block; margin-top: 8px;">Відкрити в Telegram ✈️</a>
            </div>
        </div>

        <div class="footer">
            Powered by <b>MasterCRM v2.0</b> • Автоматизована екосистема краси
        </div>
    </div>
</body>
</html>"""


@router.api_route("/exec", methods=["GET", "POST"])
async def unified_exec(
    request: Request,
    action: Optional[str] = Query(None, description="Action name to execute"),
    db: AsyncSession = Depends(get_db),
    current_master: Optional[Master] = Depends(get_current_master),
) -> Any:
    # 1. Parse request body if available
    body: dict[str, Any] = {}
    if request.method == "POST":
        try:
            raw_body = await request.body()
            if raw_body:
                body = json.loads(raw_body.decode("utf-8"))
        except Exception:
            body = {}
    else:
        body = dict(request.query_params)

    if not action and isinstance(body, dict):
        action = body.get("action")

    # Handle direct browser visits to /exec without action parameter
    if not action:
        accept = request.headers.get("accept", "")
        if "text/html" in accept:
            from fastapi.responses import HTMLResponse
            return HTMLResponse(content=await render_public_landing_page())
        return ApiResponse.ok({"server": "MasterCRM v2.0", "status": "online", "docs": "/docs"})

    # 2. Dispatch actions
    # Public actions: ping, auth_exchange_code
    if action == "ping":
        return ApiResponse.ok({"pong": True, "time": utc_now().isoformat(), "server": "MasterCRM v2.0"})

    if action == "auth_exchange_code":
        otp = str(body.get("otp_code") or body.get("code") or "").strip()
        device_name = str(body.get("device_name", "Android Device")).strip()
        if not otp:
            return ApiResponse.fail("INVALID_CODE", "OTP-код обов'язковий")

        now = utc_now()
        stmt = (
            select(Device)
            .options(selectinload(Device.master))
            .where(
                Device.otp_code == otp,
                Device.is_active.is_(False),
                Device.deleted.is_(False),
                or_(Device.otp_expires_at.is_(None), Device.otp_expires_at >= now)
            )
        )
        res = await db.execute(stmt)
        device = res.scalar_one_or_none()
        if not device:
            return ApiResponse.fail("INVALID_CODE", "Невірний або прострочений 6-значний код")

        master = device.master
        if not master or master.deleted or not master.is_active:
            return ApiResponse.fail("MASTER_INACTIVE", "Майстер не знайдений або деактивований")

        random_bytes = secrets.token_bytes(32)
        dev_token = f"devtok_{hashlib.sha256(random_bytes).hexdigest()}"

        device.device_token = dev_token
        device.device_name = device_name
        device.is_active = True
        device.otp_code = None
        device.otp_expires_at = None
        device.last_synced_at = now
        device.mark_updated()
        await db.commit()

        return ApiResponse.ok({
            "device_token": dev_token,
            "master_id": master.id,
            "master_name": master.name,
            "role": master.role,
            "master": {
                "id": master.id,
                "name": master.name,
                "phone": master.phone,
                "role": master.role,
            }
        })

    # Public / accessible action: slots_get (Smart Slot Engine)
    if action == "slots_get":
        master_id = body.get("master_id") or (current_master.id if current_master else "m-01")
        service_id = body.get("service_id")
        date_str = body.get("date")  # YYYY-MM-DD

        if not service_id or not date_str:
            return ApiResponse.fail("BAD_REQUEST", "Параметри service_id та date є обов'язковими")

        srv = await db.get(Service, service_id)
        if not srv or srv.deleted:
            return ApiResponse.fail("SERVICE_NOT_FOUND", "Послуга не знайдена")

        duration_with_buffer = srv.duration_minutes + srv.buffer_minutes

        # 1. Working Hours from Schedule & ScheduleException
        day_start = 9 * 60   # 09:00 default
        day_end = 19 * 60    # 19:00 default
        slot_step = 30

        exc_stmt = select(ScheduleException).where(
            ScheduleException.master_id == master_id,
            ScheduleException.date == date_str,
            ScheduleException.deleted.is_(False)
        )
        exc_res = await db.execute(exc_stmt)
        sched_exc = exc_res.scalar_one_or_none()

        if sched_exc and sched_exc.is_day_off:
            return ApiResponse.ok({
                "master_id": master_id,
                "service_id": service_id,
                "date": date_str,
                "available_slots": [],
            })

        if sched_exc and sched_exc.start_time and sched_exc.end_time:
            day_start = time_to_minutes(sched_exc.start_time)
            day_end = time_to_minutes(sched_exc.end_time)
        else:
            try:
                date_obj = datetime.strptime(date_str, "%Y-%m-%d")
                weekday = date_obj.weekday()
                sched_stmt = select(Schedule).where(
                    Schedule.master_id == master_id,
                    Schedule.day_of_week == weekday,
                    Schedule.deleted.is_(False)
                )
                sched_res = await db.execute(sched_stmt)
                sched = sched_res.scalar_one_or_none()
                if sched:
                    if not sched.is_working_day:
                        return ApiResponse.ok({
                            "master_id": master_id,
                            "service_id": service_id,
                            "date": date_str,
                            "available_slots": [],
                        })
                    day_start = time_to_minutes(sched.start_time)
                    day_end = time_to_minutes(sched.end_time)
                    slot_step = sched.slot_step_minutes or 30
            except Exception:
                pass

        # 2. Query existing appointments for this master and date
        stmt = (
            select(Appointment)
            .where(
                Appointment.master_id == master_id,
                Appointment.date == date_str,
                Appointment.deleted.is_(False),
                Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"])
            )
        )
        res = await db.execute(stmt)
        existing = res.scalars().all()

        occupied_intervals = []
        for apt in existing:
            if apt.start_time == "За домовленістю":
                continue
            s_min = time_to_minutes(apt.start_time)
            e_min = time_to_minutes(apt.end_time)
            occupied_intervals.append((s_min, e_min))

        # 3. SharedResource checks in STUDIO mode
        app_mode_res = await db.execute(select(StudioSetting).where(StudioSetting.key == "app_mode"))
        app_mode_setting = app_mode_res.scalar_one_or_none()
        is_studio_mode = app_mode_setting and app_mode_setting.value == "STUDIO"

        shared_resource_max = 999
        shared_service_ids = []
        if is_studio_mode:
            sr_stmt = (
                select(ServiceResource)
                .options(selectinload(ServiceResource.resource))
                .where(ServiceResource.service_id == service_id, ServiceResource.deleted.is_(False))
            )
            sr = (await db.execute(sr_stmt)).scalar_one_or_none()
            if sr and sr.resource and not sr.resource.deleted:
                shared_resource_max = sr.resource.quantity
                all_sr_stmt = select(ServiceResource.service_id).where(
                    ServiceResource.resource_id == sr.resource_id,
                    ServiceResource.deleted.is_(False)
                )
                shared_service_ids = list((await db.execute(all_sr_stmt)).scalars().all())

        resource_occupied_intervals = []
        if shared_service_ids and shared_resource_max < 999:
            all_m_stmt = select(Appointment).where(
                Appointment.service_id.in_(shared_service_ids),
                Appointment.date == date_str,
                Appointment.deleted.is_(False),
                Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"]),
                Appointment.start_time != "За домовленістю"
            )
            for a in (await db.execute(all_m_stmt)).scalars().all():
                resource_occupied_intervals.append((time_to_minutes(a.start_time), time_to_minutes(a.end_time)))

        # 4. Generate candidate slots
        available_slots = []
        current_min = day_start
        kyiv_tz = pytz.timezone(settings.TIMEZONE)
        now_kyiv = datetime.now(kyiv_tz)
        is_today = (date_str == now_kyiv.strftime("%Y-%m-%d"))
        now_minutes = now_kyiv.hour * 60 + now_kyiv.minute

        while current_min + srv.duration_minutes <= day_end:
            if is_today and current_min <= now_minutes + 5:
                current_min += slot_step
                continue

            slot_start = current_min
            slot_end = current_min + duration_with_buffer
            
            # Check master overlap: (slot_start < apt_end) and (slot_end > apt_start)
            conflict = False
            for apt_start, apt_end in occupied_intervals:
                if slot_start < apt_end and slot_end > apt_start:
                    conflict = True
                    break

            # Check shared resource capacity
            if not conflict and shared_service_ids and shared_resource_max < 999:
                concurrent_count = 0
                for r_start, r_end in resource_occupied_intervals:
                    if slot_start < r_end and slot_end > r_start:
                        concurrent_count += 1
                if concurrent_count >= shared_resource_max:
                    conflict = True

            if not conflict:
                h = current_min // 60
                m = current_min % 60
                available_slots.append(f"{h:02d}:{m:02d}")

            current_min += slot_step

        return ApiResponse.ok({
            "master_id": master_id,
            "service_id": service_id,
            "date": date_str,
            "available_slots": available_slots,
        })

    # Public action: reviews_public
    if action == "reviews_public":
        rev_stmt = (
            select(Review)
            .options(selectinload(Review.client))
            .where(Review.is_published.is_(True), Review.moderation_status == "APPROVED", Review.deleted.is_(False))
            .order_by(Review.created_at.desc())
            .limit(20)
        )
        revs = (await db.execute(rev_stmt)).scalars().all()
        return ApiResponse.ok([
            {
                "id": r.id,
                "rating": r.rating,
                "comment": r.comment,
                "client_name": r.client_name or (r.client.first_name if r.client else "Клієнт"),
                "service_title": r.service_title or "",
                "photo_url": r.photo_url or "",
                "review_type": r.review_type or "POST_VISIT",
                "created_at": r.created_at.isoformat() if r.created_at else ""
            }
            for r in revs
        ])

    # Protected actions: require valid current_master
    if not current_master:
        return ApiResponse.fail("UNAUTHORIZED", "Потрібна авторизація пристрою (Authorization: Bearer <device_token>)")

    # 3. Action: sync
    if action == "sync":
        since_str = body.get("since")
        force_full = (
            str(body.get("force_full", "")).lower() in ("true", "1")
            or not since_str
            or str(since_str).strip() in ("", "0")
            or str(since_str).startswith("1970")
        )
        safe_since_dt: Optional[datetime] = None
        if not force_full and since_str:
            try:
                clean_str = since_str.replace("Z", "+00:00")
                since_dt = datetime.fromisoformat(clean_str)
                if since_dt.tzinfo is None:
                    since_dt = since_dt.replace(tzinfo=timezone.utc)
                # 10-minute safety overlap buffer against clock skew and commit lag
                safe_since_dt = since_dt - timedelta(minutes=10)
            except Exception:
                safe_since_dt = None

        async def fetch_table(model: Any) -> list[Any]:
            query = select(model)
            if safe_since_dt:
                query = query.where(
                    or_(
                        model.updated_at >= safe_since_dt,
                        model.created_at >= safe_since_dt,
                        model.updated_at.is_(None)
                    )
                )
            res = await db.execute(query)
            return list(res.scalars().all())

        appointments = await fetch_table(Appointment)
        clients = await fetch_table(Client)
        services = await fetch_table(Service)
        masters = await fetch_table(Master)
        incomes = await fetch_table(Income)
        expenses = await fetch_table(Expense)
        products = await fetch_table(Product)
        reviews = await fetch_table(Review)
        schedules = await fetch_table(Schedule)
        visit_briefs = await fetch_table(VisitBrief)

        # Settings dictionary
        settings_res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        all_settings = settings_res.scalars().all()
        settings_map = {s.key: s.value for s in all_settings}

        c_map = {c.id: c for c in clients}
        s_map = {s.id: s for s in services}
        now_iso = utc_now().isoformat()

        def format_time_slot(t: str) -> str:
            if not t:
                return "09:00"
            t = t.strip()
            if ":" in t:
                parts = t.split(":")
                return f"{int(parts[0]):02d}:{int(parts[1]):02d}"
            try:
                return f"{int(t):02d}:00"
            except Exception:
                return t

        serialized_appointments = []
        for a in appointments:
            c = c_map.get(a.client_id)
            srv = s_map.get(a.service_id)
            c_name = c.first_name if c and c.first_name else "Клієнт"
            c_phone = c.phone if c and c.phone else ""
            s_title = srv.title if srv and srv.title else "Послуга"

            serialized_appointments.append({
                "id": str(a.id),
                "master_id": str(a.master_id or "m-01"),
                "client_id": str(a.client_id or ""),
                "client_name": str(c_name),
                "client_phone": str(c_phone),
                "service_id": str(a.service_id or ""),
                "service_title": str(s_title),
                "date": str(a.date or "").strip(),
                "start_time": format_time_slot(str(a.start_time or "09:00")),
                "end_time": format_time_slot(str(a.end_time or "10:00")),
                "status": str(a.status or "PENDING"),
                "total_price": float(a.total_price or 0.0),
                "deposit_amount": float(getattr(a, "deposit_amount", 0.0) or 0.0),
                "is_deposit_paid": bool(getattr(a, "is_deposit_paid", False)),
                "receipt_photo_url": str(getattr(a, "receipt_photo_url", "") or ""),
                "cancellation_reason": str(a.cancellation_reason or ""),
                "created_at": a.created_at.isoformat() if a.created_at else now_iso,
                "updated_at": a.updated_at.isoformat() if a.updated_at else now_iso,
                "deleted": bool(a.deleted),
                "version": int(a.version or 1),
            })

        def safe_map(dto_cls: Any, items: list[Any]) -> list[Any]:
            res = []
            for item in items:
                try:
                    res.append(dto_cls.model_validate(item).model_dump(mode="json"))
                except Exception as ex:
                    logger.warning("DTO error for %s id=%s: %s", dto_cls.__name__, getattr(item, "id", None), ex)
            return res

        data = {
            "appointments": serialized_appointments,
            "clients": safe_map(ClientDto, clients),
            "services": safe_map(ServiceDto, services),
            "masters": safe_map(MasterDto, masters),
            "incomes": safe_map(IncomeDto, incomes),
            "expenses": safe_map(ExpenseDto, expenses),
            "products": safe_map(ProductDto, products),
            "reviews": safe_map(ReviewDto, reviews),
            "schedules": safe_map(ScheduleDto, schedules),
            "visit_briefs": safe_map(VisitBriefDto, visit_briefs),
            "settings": settings_map,
            "server_sync_time": now_iso,
        }
        return ApiResponse.ok(data)

    # 5. Action: appointment_create
    if action == "appointment_create":
        async with _appointment_create_lock:
            m_id = body.get("master_id") or current_master.id
            s_id = body.get("service_id")
            c_id = body.get("client_id")
            date_str = body.get("date")
            start_time = body.get("start_time", "10:00")
            total_price = Decimal(str(body.get("total_price", "0.00")))
            deposit_amount = Decimal(str(body.get("deposit_amount", "0.00")))
            is_deposit_paid = bool(body.get("is_deposit_paid", False))

            if not s_id or not date_str:
                return ApiResponse.fail("BAD_REQUEST", "service_id та date є обов'язковими")

            # Row-level locking on Master to prevent race conditions
            master_stmt = select(Master).where(Master.id == m_id)
            if not is_sqlite:
                master_stmt = master_stmt.with_for_update()
            m_res = await db.execute(master_stmt)
            locked_master = m_res.scalar_one_or_none()
            if not locked_master:
                return ApiResponse.fail("MASTER_NOT_FOUND", "Майстра не знайдено")

            srv = await db.get(Service, s_id)
            if not srv or srv.deleted:
                return ApiResponse.fail("SERVICE_NOT_FOUND", "Послуга не знайдена")

            if total_price == Decimal("0.00"):
                total_price = srv.price

            end_time = body.get("end_time") or calculate_end_time(start_time, srv.duration_minutes)

            # Check collision if start_time != "За домовленістю"
            if start_time != "За домовленістю":
                slot_s = time_to_minutes(start_time)
                slot_e = time_to_minutes(end_time) + srv.buffer_minutes

                stmt_conflict = select(Appointment).where(
                    Appointment.master_id == m_id,
                    Appointment.date == date_str,
                    Appointment.deleted.is_(False),
                    Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"]),
                    Appointment.start_time != "За домовленістю"
                )
                c_res = await db.execute(stmt_conflict)
                existing_apts = c_res.scalars().all()
                for ex in existing_apts:
                    ex_s = time_to_minutes(ex.start_time)
                    ex_e = time_to_minutes(ex.end_time)
                    if slot_s < ex_e and slot_e > ex_s:
                        return ApiResponse.fail("RESOURCE_BUSY", "Вибраний час уже зайнятий іншим клієнтом")

                # Check shared resource capacity in STUDIO mode
                app_mode_res = await db.execute(select(StudioSetting).where(StudioSetting.key == "app_mode"))
                app_mode_setting = app_mode_res.scalar_one_or_none()
                if app_mode_setting and app_mode_setting.value == "STUDIO":
                    sr_stmt = (
                        select(ServiceResource)
                        .options(selectinload(ServiceResource.resource))
                        .where(ServiceResource.service_id == s_id, ServiceResource.deleted.is_(False))
                    )
                    sr = (await db.execute(sr_stmt)).scalar_one_or_none()
                    if sr and sr.resource and not sr.resource.deleted:
                        shared_resource_max = sr.resource.quantity
                        all_sr_stmt = select(ServiceResource.service_id).where(
                            ServiceResource.resource_id == sr.resource_id,
                            ServiceResource.deleted.is_(False)
                        )
                        shared_service_ids = list((await db.execute(all_sr_stmt)).scalars().all())
                        if shared_service_ids:
                            all_m_stmt = select(Appointment).where(
                                Appointment.service_id.in_(shared_service_ids),
                                Appointment.date == date_str,
                                Appointment.deleted.is_(False),
                                Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"]),
                                Appointment.start_time != "За домовленістю"
                            )
                            concurrent_count = 0
                            for a in (await db.execute(all_m_stmt)).scalars().all():
                                a_s = time_to_minutes(a.start_time)
                                a_e = time_to_minutes(a.end_time)
                                if slot_s < a_e and slot_e > a_s:
                                    concurrent_count += 1
                            if concurrent_count >= shared_resource_max:
                                return JSONResponse(
                                    status_code=status.HTTP_409_CONFLICT,
                                    content=ApiResponse.fail("RESOURCE_BUSY", f"Спільне обладнання «{sr.resource.name}» повністю зайняте на цей час").model_dump()
                                )

            # Client resolution
            if not c_id:
                c_phone = body.get("client_phone", "+380000000000")
                c_name = body.get("client_name", "Клієнт")
                client_stmt = select(Client).where(Client.phone == c_phone, Client.deleted.is_(False))
                client_res = await db.execute(client_stmt)
                client = client_res.scalar_one_or_none()
                if not client:
                    client = Client(
                        first_name=c_name,
                        phone=c_phone,
                        notes=body.get("notes", "")
                    )
                    db.add(client)
                    await db.flush()
                c_id = client.id

            apt_custom_id = body.get("id")
            if apt_custom_id:
                existing_apt = await db.get(Appointment, apt_custom_id)
                if existing_apt:
                    existing_apt.master_id = m_id
                    existing_apt.client_id = c_id
                    existing_apt.service_id = s_id
                    existing_apt.date = date_str
                    existing_apt.start_time = start_time
                    existing_apt.end_time = end_time
                    existing_apt.status = body.get("status", existing_apt.status)
                    existing_apt.total_price = total_price
                    existing_apt.deposit_amount = deposit_amount
                    existing_apt.is_deposit_paid = is_deposit_paid
                    existing_apt.updated_at = utc_now()
                    new_apt = existing_apt
                else:
                    new_apt = Appointment(
                        id=apt_custom_id,
                        master_id=m_id,
                        client_id=c_id,
                        service_id=s_id,
                        date=date_str,
                        start_time=start_time,
                        end_time=end_time,
                        status=body.get("status", "CONFIRMED"),
                        total_price=total_price,
                        deposit_amount=deposit_amount,
                        is_deposit_paid=is_deposit_paid,
                        cancellation_reason="",
                        deleted=False,
                        version=1,
                        created_at=utc_now(),
                        updated_at=utc_now()
                    )
                    db.add(new_apt)
            else:
                new_apt = Appointment(
                    master_id=m_id,
                    client_id=c_id,
                    service_id=s_id,
                    date=date_str,
                    start_time=start_time,
                    end_time=end_time,
                    status=body.get("status", "CONFIRMED"),
                    total_price=total_price,
                    deposit_amount=deposit_amount,
                    is_deposit_paid=is_deposit_paid,
                    cancellation_reason="",
                    created_at=utc_now(),
                    updated_at=utc_now()
                )
                db.add(new_apt)
            await db.flush()

            # Audit history record
            history = AppointmentHistory(
                appointment_id=new_apt.id,
                previous_status="NONE",
                new_status=new_apt.status,
                changed_by="master" if current_master else "system",
                comment="Створення запису"
            )
            db.add(history)

            await db.commit()
            await db.refresh(new_apt)
            return ApiResponse.ok(AppointmentDto.model_validate(new_apt).model_dump(mode='json'))

    # 6. Action: appointment_update_status
    if action == "appointment_update_status":
        apt_id = body.get("appointment_id")
        new_status = body.get("status") or body.get("new_status")
        reason = body.get("cancellation_reason", "")

        if not apt_id or not new_status:
            return ApiResponse.fail("BAD_REQUEST", "appointment_id та status є обов'язковими")

        stmt = (
            select(Appointment)
            .options(selectinload(Appointment.service), selectinload(Appointment.client))
            .where(Appointment.id == apt_id, Appointment.deleted.is_(False))
        )
        res = await db.execute(stmt)
        apt = res.scalar_one_or_none()
        if not apt:
            return ApiResponse.fail("NOT_FOUND", "Запис не знайдено")

        if apt.status == "COMPLETED":
            return ApiResponse.fail("LOCKED", "Запис вже виконано та закрито для змін")

        old_status = apt.status
        apt.status = new_status
        if reason:
            apt.cancellation_reason = reason
        apt.mark_updated()

        # Audit history record
        history = AppointmentHistory(
            appointment_id=apt.id,
            previous_status=old_status,
            new_status=new_status,
            changed_by="master" if current_master else "system",
            comment=reason or ""
        )
        db.add(history)

        # NO_SHOW blacklist automation
        if new_status == "NO_SHOW" and old_status != "NO_SHOW":
            if apt.client and not apt.client.deleted:
                apt.client.no_show_count += 1
                if apt.client.no_show_count >= 2:
                    apt.client.is_blacklisted = True
                apt.client.mark_updated()

        # COMPLETED logic
        if new_status == "COMPLETED" and old_status != "COMPLETED":
            # 1. Deduct consumables
            if apt.service_id:
                c_stmt = (
                    select(ServiceConsumable)
                    .options(selectinload(ServiceConsumable.product))
                    .where(ServiceConsumable.service_id == apt.service_id)
                )
                c_res = await db.execute(c_stmt)
                consumables = c_res.scalars().all()
                for item in consumables:
                    if item.product and not item.product.deleted:
                        item.product.current_stock -= item.quantity_per_service
                        item.product.mark_updated()
                        movement = StockMovement(
                            product_id=item.product.id,
                            type="DEDUCTION",
                            quantity=item.quantity_per_service,
                            appointment_id=apt.id,
                            comment=f"Списання за запис {apt.id}"
                        )
                        db.add(movement)

                        # Low-stock alert check
                        if item.product.current_stock <= item.product.min_stock_alert:
                            try:
                                from app.bot.bot_instance import bot
                                if bot:
                                    owner_stmt = select(Master).where(
                                        Master.role == "owner",
                                        Master.deleted.is_(False),
                                        Master.telegram_chat_id.isnot(None)
                                    )
                                    owners = (await db.execute(owner_stmt)).scalars().all()
                                    alert_msg = (
                                        f"⚠️ <b>Закінчується матеріал на складі:</b> {item.product.name} "
                                        f"(Залишок: {item.product.current_stock} {item.product.unit})!"
                                    )
                                    for owner in owners:
                                        if owner.telegram_chat_id:
                                            await bot.send_message(chat_id=owner.telegram_chat_id, text=alert_msg, parse_mode="HTML")
                            except Exception as alert_err:
                                logger.warning("Failed to send low stock alert: %s", alert_err)

            # 2. Create Income strictly once
            inc_stmt = select(Income).where(Income.appointment_id == apt.id, Income.deleted.is_(False))
            inc_res = await db.execute(inc_stmt)
            existing_income = inc_res.scalar_one_or_none()
            if not existing_income:
                inc = Income(
                    appointment_id=apt.id,
                    master_id=apt.master_id,
                    client_id=apt.client_id,
                    amount=apt.total_price,
                    payment_method="CASH",
                    date=apt.date,
                    category="services"
                )
                db.add(inc)

            # 3. Increment Client counters strictly once
            if apt.client and not apt.client.deleted:
                apt.client.total_visits += 1
                apt.client.total_spent += apt.total_price
                apt.client.visits_since_last_removal += 1
                apt.client.last_visit_at = utc_now()
                apt.client.mark_updated()

        # CANCELLED logic -> Notify Waiting List
        if new_status in ["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"] and old_status not in ["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER"]:
            srv_title = apt.service.title if apt.service else "Процедура"
            await notify_waiting_list_clients(db, apt.date, srv_title)

        # Telegram notification to client on status update from master app
        if old_status != new_status and new_status in ["CONFIRMED", "CANCELLED_BY_MASTER"]:
            if apt.client and apt.client.telegram_id:
                try:
                    from app.bot.bot_instance import bot
                    if bot:
                        s_title = apt.service.title if apt.service else "Процедура"
                        if new_status == "CONFIRMED":
                            c_msg = (
                                f"🎉 <b>Ваш запис підтверджено майстром!</b>\n\n"
                                f"💅 <b>Послуга:</b> {html.escape(s_title)}\n"
                                f"📅 <b>Дата та час:</b> {apt.date} о {apt.start_time}\n\n"
                                f"Чекаємо на вас! 💖"
                            )
                        else:
                            c_msg = (
                                f"😔 На жаль, майстер не зможе прийняти вас {apt.date} о {apt.start_time}. "
                                f"Будь ласка, оберіть інший зручний час у меню /start."
                            )
                        await bot.send_message(chat_id=apt.client.telegram_id, text=c_msg, parse_mode="HTML")
                except Exception as tg_err:
                    logger.warning("Failed to send status notification to client: %s", tg_err)

        await db.commit()
        await db.refresh(apt)
        return ApiResponse.ok(AppointmentDto.model_validate(apt).model_dump())

    # 7. Action: visit_brief_save
    if action == "visit_brief_save":
        apt_id = body.get("appointment_id")
        if not apt_id:
            return ApiResponse.fail("BAD_REQUEST", "appointment_id обов'язковий")

        vb_stmt = select(VisitBrief).where(VisitBrief.appointment_id == apt_id)
        vb_res = await db.execute(vb_stmt)
        vb = vb_res.scalar_one_or_none()

        master_id = body.get("master_id") or (current_master.id if current_master else "m-01")
        client_id = body.get("client_id", "")
        if not client_id:
            apt_obj = await db.get(Appointment, apt_id)
            if apt_obj:
                client_id = apt_obj.client_id
        if not client_id:
            client_id = "unknown"

        if not vb:
            custom_vb_id = body.get("id")
            vb = VisitBrief(
                id=custom_vb_id or generate_uuid(),
                appointment_id=apt_id,
                client_id=client_id,
                master_id=master_id,
                material_notes=body.get("material_notes", ""),
                nail_condition=body.get("nail_condition", ""),
                coffee_prefs=body.get("coffee_prefs", ""),
                photo_url=body.get("photo_url", ""),
                coating_condition=body.get("coating_condition", ""),
                nail_plate_type=body.get("nail_plate_type", ""),
                cuticle_type=body.get("cuticle_type", ""),
                base_gel_used=body.get("base_gel_used", ""),
                design_and_length=body.get("design_and_length", ""),
                health_notes=body.get("health_notes", ""),
                photo_before_url=body.get("photo_before_url", ""),
                photo_after_url=body.get("photo_after_url", ""),
            )
            db.add(vb)
        else:
            if client_id and not vb.client_id:
                vb.client_id = client_id
            if "material_notes" in body: vb.material_notes = body["material_notes"]
            if "nail_condition" in body: vb.nail_condition = body["nail_condition"]
            if "coffee_prefs" in body: vb.coffee_prefs = body["coffee_prefs"]
            if "photo_url" in body: vb.photo_url = body["photo_url"]
            if "coating_condition" in body: vb.coating_condition = body["coating_condition"]
            if "nail_plate_type" in body: vb.nail_plate_type = body["nail_plate_type"]
            if "cuticle_type" in body: vb.cuticle_type = body["cuticle_type"]
            if "base_gel_used" in body: vb.base_gel_used = body["base_gel_used"]
            if "design_and_length" in body: vb.design_and_length = body["design_and_length"]
            if "health_notes" in body: vb.health_notes = body["health_notes"]
            if "photo_before_url" in body: vb.photo_before_url = body["photo_before_url"]
            if "photo_after_url" in body: vb.photo_after_url = body["photo_after_url"]
            vb.mark_updated()

        # Update client's notes if new diagnostic notes were provided
        if client_id:
            c = await db.get(Client, client_id)
            if c:
                additions = []
                if body.get("health_notes"): additions.append(f"Здоров'я: {body['health_notes']}")
                if body.get("base_gel_used"): additions.append(f"Матеріали: {body['base_gel_used']}")
                if additions:
                    summary = " • ".join(additions)
                    if summary not in (c.notes or ""):
                        c.notes = f"{c.notes or ''}\n[{utc_now().strftime('%Y-%m-%d')}]: {summary}".strip()
                        c.mark_updated()

        await db.commit()
        await db.refresh(vb)
        return ApiResponse.ok({
            "message": "Бриф візиту успішно збережено",
            "visit_brief_id": vb.id,
            "brief": VisitBriefDto.model_validate(vb).model_dump()
        })

    # 8. Action: client_reset_removal_counter
    if action == "client_reset_removal_counter":
        c_id = body.get("client_id")
        if not c_id:
            return ApiResponse.fail("BAD_REQUEST", "client_id обов'язковий")
        client = await db.get(Client, c_id)
        if not client or client.deleted:
            return ApiResponse.fail("NOT_FOUND", "Клієнта не знайдено")

        client.visits_since_last_removal = 0
        client.last_removal_date = utc_now()
        client.mark_updated()
        await db.commit()
        return ApiResponse.ok({"message": "Лічильник зняття скинуто на 0", "client_id": c_id})

    if action == "client_update":
        c_id = body.get("client_id") or body.get("id")
        if not c_id:
            return ApiResponse.fail("BAD_REQUEST", "client_id обов'язковий")
        client = await db.get(Client, c_id)
        if not client or client.deleted:
            return ApiResponse.fail("CLIENT_NOT_FOUND", "Клієнта не знайдено")
        
        if "first_name" in body: client.first_name = str(body["first_name"])
        if "last_name" in body: client.last_name = str(body["last_name"])
        if "phone" in body: client.phone = str(body["phone"])
        if "notes" in body: client.notes = str(body["notes"])
        if "is_blacklisted" in body: client.is_blacklisted = bool(body["is_blacklisted"])
        if "discount_percent" in body: client.discount_percent = int(body["discount_percent"])
        client.mark_updated()
        await db.commit()
        await db.refresh(client)
        return ApiResponse.ok(ClientDto.model_validate(client).model_dump(mode="json"))

    # 9. Action: income_add, expense_add, finance_report
    if action == "income_add":
        inc_id = body.get("id")
        if inc_id:
            inc = await db.get(Income, inc_id)
            if inc:
                inc.master_id = body.get("master_id") or current_master.id
                inc.appointment_id = body.get("appointment_id", inc.appointment_id)
                inc.client_id = body.get("client_id", inc.client_id)
                inc.amount = Decimal(str(body.get("amount", inc.amount)))
                inc.payment_method = body.get("payment_method", inc.payment_method)
                inc.date = body.get("date", inc.date)
                inc.category = body.get("category", inc.category)
                inc.updated_at = utc_now()
            else:
                inc = Income(
                    id=inc_id,
                    master_id=body.get("master_id") or current_master.id,
                    appointment_id=body.get("appointment_id"),
                    client_id=body.get("client_id"),
                    amount=Decimal(str(body.get("amount", "0.00"))),
                    payment_method=body.get("payment_method", "CASH"),
                    date=body.get("date", utc_now().strftime("%Y-%m-%d")),
                    category=body.get("category", "services"),
                )
                db.add(inc)
        else:
            inc = Income(
                master_id=body.get("master_id") or current_master.id,
                appointment_id=body.get("appointment_id"),
                client_id=body.get("client_id"),
                amount=Decimal(str(body.get("amount", "0.00"))),
                payment_method=body.get("payment_method", "CASH"),
                date=body.get("date", utc_now().strftime("%Y-%m-%d")),
                category=body.get("category", "services"),
            )
            db.add(inc)
        await db.commit()
        await db.refresh(inc)
        return ApiResponse.ok(IncomeDto.model_validate(inc).model_dump(mode="json"))

    if action == "expense_add":
        exp_id = body.get("id")
        if exp_id:
            exp = await db.get(Expense, exp_id)
            if exp:
                exp.master_id = body.get("master_id") or current_master.id
                exp.category = body.get("category", exp.category)
                exp.amount = Decimal(str(body.get("amount", exp.amount)))
                exp.payment_method = body.get("payment_method", exp.payment_method)
                exp.date = body.get("date", exp.date)
                exp.description = body.get("description", exp.description)
                exp.updated_at = utc_now()
            else:
                exp = Expense(
                    id=exp_id,
                    master_id=body.get("master_id") or current_master.id,
                    category=body.get("category", "MATERIALS"),
                    amount=Decimal(str(body.get("amount", "0.00"))),
                    payment_method=body.get("payment_method", "CASH"),
                    date=body.get("date", utc_now().strftime("%Y-%m-%d")),
                    description=body.get("description", ""),
                )
                db.add(exp)
        else:
            exp = Expense(
                master_id=body.get("master_id") or current_master.id,
                category=body.get("category", "MATERIALS"),
                amount=Decimal(str(body.get("amount", "0.00"))),
                payment_method=body.get("payment_method", "CASH"),
                date=body.get("date", utc_now().strftime("%Y-%m-%d")),
                description=body.get("description", ""),
            )
            db.add(exp)
        await db.commit()
        await db.refresh(exp)
        return ApiResponse.ok(ExpenseDto.model_validate(exp).model_dump(mode="json"))

    if action == "finance_report":
        from_date = body.get("from_date", "2026-01-01")
        to_date = body.get("to_date", "2026-12-31")
        m_filter = body.get("master_id") or request.query_params.get("master_id")
        target_master = m_filter if (m_filter and m_filter not in ["ALL", "all", ""]) else None

        # Incomes
        inc_stmt = select(Income).where(
            Income.date >= from_date,
            Income.date <= to_date,
            Income.deleted.is_(False)
        )
        if target_master:
            inc_stmt = inc_stmt.where(Income.master_id == target_master)
        inc_res = await db.execute(inc_stmt)
        all_incomes = inc_res.scalars().all()

        gross_revenue = Decimal("0.00")
        tips = Decimal("0.00")
        for i in all_incomes:
            if i.category == "tips":
                tips += i.amount
            else:
                gross_revenue += i.amount

        # Expenses
        exp_stmt = select(Expense).where(
            Expense.date >= from_date,
            Expense.date <= to_date,
            Expense.deleted.is_(False)
        )
        if target_master:
            exp_stmt = exp_stmt.where(Expense.master_id == target_master)
        exp_res = await db.execute(exp_stmt)
        all_expenses = exp_res.scalars().all()

        direct_expenses = Decimal("0.00")
        materials_cogs = Decimal("0.00")
        for e in all_expenses:
            if e.category == "MATERIALS":
                materials_cogs += e.amount
            else:
                direct_expenses += e.amount

        # Calculate materials COGS from consumables for completed appointments if no direct materials expense
        if materials_cogs == Decimal("0.00"):
            apts_stmt = select(Appointment).where(
                Appointment.date >= from_date,
                Appointment.date <= to_date,
                Appointment.status == "COMPLETED",
                Appointment.deleted.is_(False)
            )
            if target_master:
                apts_stmt = apts_stmt.where(Appointment.master_id == target_master)
            completed_apts = (await db.execute(apts_stmt)).scalars().all()
            for c_apt in completed_apts:
                if c_apt.service_id:
                    materials_cogs += await calculate_service_cost(c_apt.service_id, db)

        total_expenses = direct_expenses + materials_cogs
        net_profit = (gross_revenue + tips) - total_expenses

        # Count completed appointments in period
        apt_count_stmt = select(func.count(Appointment.id)).where(
            Appointment.date >= from_date,
            Appointment.date <= to_date,
            Appointment.status == "COMPLETED",
            Appointment.deleted.is_(False)
        )
        if target_master:
            apt_count_stmt = apt_count_stmt.where(Appointment.master_id == target_master)
        apt_count = (await db.execute(apt_count_stmt)).scalar() or 0

        avg_check = (gross_revenue / Decimal(apt_count)) if apt_count > 0 else Decimal("0.00")
        margin_percent = ((net_profit / gross_revenue) * Decimal(100)) if gross_revenue > Decimal("0.00") else Decimal("0.00")

        return ApiResponse.ok({
            "from_date": from_date,
            "to_date": to_date,
            "gross_revenue": float(gross_revenue),
            "tips": float(tips),
            "direct_expenses": float(direct_expenses),
            "materials_cogs": float(materials_cogs),
            "total_expenses": float(total_expenses),
            "net_profit": float(net_profit),
            "appointments_completed": apt_count,
            "average_check": round(float(avg_check), 2),
            "margin_percent": round(float(margin_percent), 2),
        })

    # 10. Action: products & inventory
    if action == "product_create":
        prod_id = body.get("id")
        if prod_id:
            prod = await db.get(Product, prod_id)
            if prod:
                prod.name = body.get("name", prod.name)
                prod.unit = body.get("unit", prod.unit)
                prod.current_stock = Decimal(str(body.get("current_stock", prod.current_stock)).replace(",", "."))
                prod.min_stock_alert = Decimal(str(body.get("min_stock_alert", prod.min_stock_alert)).replace(",", "."))
                prod.purchase_price = Decimal(str(body.get("purchase_price", prod.purchase_price)).replace(",", "."))
                prod.deleted = False
                prod.updated_at = utc_now()
            else:
                prod = Product(
                    id=prod_id,
                    name=body.get("name", "Новий товар"),
                    unit=body.get("unit", "шт"),
                    current_stock=Decimal(str(body.get("current_stock", "0.00")).replace(",", ".")),
                    min_stock_alert=Decimal(str(body.get("min_stock_alert", "5.00")).replace(",", ".")),
                    purchase_price=Decimal(str(body.get("purchase_price", "0.00")).replace(",", ".")),
                )
                db.add(prod)
        else:
            prod = Product(
                name=body.get("name", "Новий товар"),
                unit=body.get("unit", "шт"),
                current_stock=Decimal(str(body.get("current_stock", "0.00")).replace(",", ".")),
                min_stock_alert=Decimal(str(body.get("min_stock_alert", "5.00")).replace(",", ".")),
                purchase_price=Decimal(str(body.get("purchase_price", "0.00")).replace(",", ".")),
            )
            db.add(prod)
        await db.commit()
        await db.refresh(prod)
        return ApiResponse.ok(ProductDto.model_validate(prod).model_dump(mode="json"))

    if action == "product_restock":
        p_id = body.get("product_id")
        qty = Decimal(str(body.get("quantity", "0.00")))
        prod = await db.get(Product, p_id)
        if not prod or prod.deleted:
            return ApiResponse.fail("NOT_FOUND", "Матеріал не знайдено")
        prod.current_stock += qty
        prod.mark_updated()
        mov = StockMovement(
            product_id=p_id,
            type="RESTOCK",
            quantity=qty,
            comment=body.get("comment", "Поповнення складу")
        )
        db.add(mov)
        await db.commit()
        return ApiResponse.ok({"message": "Склад успішно поповнено", "current_stock": float(prod.current_stock)})

    if action == "inventory_audit":
        p_id = body.get("product_id")
        real_qty = Decimal(str(body.get("real_quantity", "0.00")))
        prod = await db.get(Product, p_id)
        if not prod or prod.deleted:
            return ApiResponse.fail("NOT_FOUND", "Матеріал не знайдено")
        diff = real_qty - prod.current_stock
        prod.current_stock = real_qty
        prod.mark_updated()
        mov = StockMovement(
            product_id=p_id,
            type="AUDIT",
            quantity=diff,
            comment=f"Інвентаризація: скориговано на {diff}"
        )
        db.add(mov)
        await db.commit()
        return ApiResponse.ok({"message": "Інвентаризацію проведено", "new_stock": float(prod.current_stock)})

    if action == "product_delete":
        p_id = body.get("product_id")
        prod = await db.get(Product, p_id)
        if not prod or prod.deleted:
            return ApiResponse.fail("NOT_FOUND", "Матеріал не знайдено")
        prod.soft_delete()
        await db.commit()
        return ApiResponse.ok({"message": "Матеріал успішно видалено"})

    if action == "product_writeoff":
        p_id = body.get("product_id")
        qty = Decimal(str(body.get("quantity", "0.00")))
        reason = body.get("reason", "Ручне списання матеріалу")
        if not p_id or qty <= Decimal("0.00"):
            return ApiResponse.fail("BAD_REQUEST", "product_id та додатна кількість обов'язкові")

        prod_stmt = select(Product).where(Product.id == p_id, Product.deleted.is_(False))
        if not is_sqlite:
            prod_stmt = prod_stmt.with_for_update()
        prod_res = await db.execute(prod_stmt)
        prod = prod_res.scalar_one_or_none()
        if not prod:
            return ApiResponse.fail("NOT_FOUND", "Матеріал не знайдено")

        prod.current_stock -= qty
        prod.mark_updated()
        mov = StockMovement(
            product_id=p_id,
            type="MANUAL_DEDUCTION",
            quantity=-qty,
            comment=reason
        )
        db.add(mov)

        # Check low stock alert
        if prod.current_stock <= prod.min_stock_alert:
            try:
                from app.bot.bot_instance import bot
                if bot:
                    owner_stmt = select(Master).where(
                        Master.role == "owner",
                        Master.deleted.is_(False),
                        Master.telegram_chat_id.isnot(None)
                    )
                    owners = (await db.execute(owner_stmt)).scalars().all()
                    alert_msg = (
                        f"⚠️ <b>Закінчується матеріал на складі:</b> {prod.name} "
                        f"(Залишок: {prod.current_stock} {prod.unit})!\n"
                        f"Причина списання: {html.escape(reason)}"
                    )
                    for owner in owners:
                        if owner.telegram_chat_id:
                            await bot.send_message(chat_id=owner.telegram_chat_id, text=alert_msg, parse_mode="HTML")
            except Exception as alert_err:
                logger.warning("Failed to send low stock alert: %s", alert_err)

        await db.commit()
        return ApiResponse.ok({
            "message": "Матеріал успішно списано",
            "product_id": p_id,
            "current_stock": float(prod.current_stock),
            "deducted": float(qty)
        })

    if action == "service_consumables_get":
        s_id = body.get("service_id") or request.query_params.get("service_id")
        if not s_id:
            return ApiResponse.fail("BAD_REQUEST", "service_id є обов'язковим")
        sc_stmt = (
            select(ServiceConsumable)
            .options(selectinload(ServiceConsumable.product))
            .where(ServiceConsumable.service_id == s_id, ServiceConsumable.deleted.is_(False))
        )
        sc_res = await db.execute(sc_stmt)
        consumables = sc_res.scalars().all()
        return ApiResponse.ok([
            {
                "id": c.id,
                "product_id": c.product_id,
                "product_name": c.product.name if c.product else "",
                "quantity_per_service": float(c.quantity_per_service),
                "unit": c.product.unit if c.product else "шт",
            }
            for c in consumables
        ])

    if action == "service_consumables_set":
        s_id = body.get("service_id")
        items = body.get("consumables", [])
        if not s_id:
            return ApiResponse.fail("BAD_REQUEST", "service_id є обов'язковим")
        srv = await db.get(Service, s_id)
        if not srv or srv.deleted:
            return ApiResponse.fail("NOT_FOUND", "Послугу не знайдено")

        old_stmt = select(ServiceConsumable).where(ServiceConsumable.service_id == s_id)
        old_items = (await db.execute(old_stmt)).scalars().all()
        for old in old_items:
            await db.delete(old)
        await db.flush()

        for item in items:
            p_id = item.get("product_id")
            q = Decimal(str(item.get("quantity_per_service", "1.00")).replace(",", "."))
            if p_id and q > Decimal("0.00"):
                db.add(ServiceConsumable(
                    service_id=s_id,
                    product_id=p_id,
                    quantity_per_service=q
                ))
        await db.commit()

        sc_stmt = (
            select(ServiceConsumable)
            .options(selectinload(ServiceConsumable.product))
            .where(ServiceConsumable.service_id == s_id, ServiceConsumable.deleted.is_(False))
        )
        sc_res = await db.execute(sc_stmt)
        updated_consumables = sc_res.scalars().all()

        return ApiResponse.ok({
            "message": "Норми витрат послуги успішно оновлено",
            "service_id": s_id,
            "consumables": [
                {
                    "id": c.id,
                    "product_id": c.product_id,
                    "product_name": c.product.name if c.product else "",
                    "quantity_per_service": float(c.quantity_per_service),
                    "unit": c.product.unit if c.product else "шт",
                }
                for c in updated_consumables
            ]
        })

    # 11. Action: services CRUD
    if action == "service_create":
        srv_id = body.get("id")
        if srv_id:
            srv = await db.get(Service, srv_id)
            if srv:
                srv.category_id = body.get("category_id", srv.category_id)
                srv.title = body.get("title", srv.title)
                srv.price = Decimal(str(body.get("price", srv.price)))
                srv.duration_minutes = int(body.get("duration_minutes", srv.duration_minutes))
                srv.buffer_minutes = int(body.get("buffer_minutes", srv.buffer_minutes))
                srv.is_active = bool(body.get("is_active", srv.is_active))
                srv.deleted = False
                srv.updated_at = utc_now()
            else:
                srv = Service(
                    id=srv_id,
                    category_id=body.get("category_id", "cat-01"),
                    title=body.get("title", "Нова послуга"),
                    price=Decimal(str(body.get("price", "0.00"))),
                    duration_minutes=int(body.get("duration_minutes", 60)),
                    buffer_minutes=int(body.get("buffer_minutes", 15)),
                    is_active=bool(body.get("is_active", True)),
                )
                db.add(srv)
        else:
            srv = Service(
                category_id=body.get("category_id", "cat-01"),
                title=body.get("title", "Нова послуга"),
                price=Decimal(str(body.get("price", "0.00"))),
                duration_minutes=int(body.get("duration_minutes", 60)),
                buffer_minutes=int(body.get("buffer_minutes", 15)),
                is_active=bool(body.get("is_active", True)),
            )
            db.add(srv)
        consumable_id = body.get("consumable_product_id") or body.get("product_id")
        if consumable_id:
            await db.flush()
            db.add(ServiceConsumable(
                service_id=srv.id,
                product_id=consumable_id,
                quantity_per_service=Decimal(str(body.get("quantity_per_service", "1.00")))
            ))
        await db.commit()
        await db.refresh(srv)
        return ApiResponse.ok(ServiceDto.model_validate(srv).model_dump(mode="json"))

    if action == "service_update":
        s_id = body.get("service_id")
        srv = await db.get(Service, s_id)
        if not srv or srv.deleted:
            return ApiResponse.fail("NOT_FOUND", "Послугу не знайдено")
        if "title" in body:
            srv.title = body["title"]
        if "price" in body:
            srv.price = Decimal(str(body["price"]))
        if "duration_minutes" in body:
            srv.duration_minutes = int(body["duration_minutes"])
        if "buffer_minutes" in body:
            srv.buffer_minutes = int(body["buffer_minutes"])
        if "is_active" in body:
            srv.is_active = bool(body["is_active"])
        srv.mark_updated()
        await db.commit()
        return ApiResponse.ok(ServiceDto.model_validate(srv).model_dump())

    if action == "service_delete":
        s_id = body.get("service_id")
        srv = await db.get(Service, s_id)
        if not srv or srv.deleted:
            return ApiResponse.fail("NOT_FOUND", "Послугу не знайдено")
        srv.soft_delete()
        await db.commit()
        return ApiResponse.ok({"message": "Послугу успішно видалено"})

    # 12. Action: masters CRUD
    if action == "master_create":
        if current_master.role != "owner":
            return ApiResponse.fail("FORBIDDEN", "Лише власник може створювати майстрів")

        raw_tg = body.get("telegram_chat_id")
        clean_tg = None
        if raw_tg:
            try:
                clean_tg = int(str(raw_tg).replace("@", "").strip())
            except (ValueError, TypeError):
                clean_tg = None

        m_id = body.get("id") or f"mst-{uuid.uuid4().hex[:6]}"
        m = Master(
            id=m_id,
            name=body.get("name", "Майстер"),
            phone=body.get("phone", "+380000000000"),
            telegram_chat_id=clean_tg,
            role=body.get("role", "master"),
            specialization=body.get("specialization", "Nail Master"),
            is_active=True
        )
        db.add(m)

        invite_setting = StudioSetting(
            key=f"invite_{m.id}",
            value=m.id,
            description=f"Invite token for master {m.name}"
        )
        db.add(invite_setting)

        await db.commit()
        await db.refresh(m)
        return ApiResponse.ok(MasterDto.model_validate(m).model_dump())

    if action == "master_update":
        if current_master.role != "owner":
            return ApiResponse.fail("FORBIDDEN", "Лише власник може редагувати майстрів")
        m_id = body.get("master_id")
        m = await db.get(Master, m_id)
        if not m or m.deleted:
            return ApiResponse.fail("NOT_FOUND", "Майстра не знайдено")
        if "name" in body:
            m.name = body["name"]
        if "phone" in body:
            m.phone = body["phone"]
        if "telegram_chat_id" in body:
            raw_tg = body["telegram_chat_id"]
            if raw_tg is None or raw_tg == "":
                m.telegram_chat_id = None
            else:
                try:
                    m.telegram_chat_id = int(str(raw_tg).replace("@", "").strip())
                except (ValueError, TypeError):
                    pass
        if "role" in body:
            m.role = body["role"]
        if "specialization" in body:
            m.specialization = body["specialization"]
        if "is_active" in body:
            m.is_active = bool(body["is_active"])
        m.mark_updated()
        await db.commit()
        await db.refresh(m)
        return ApiResponse.ok(MasterDto.model_validate(m).model_dump())

    if action == "master_delete":
        if current_master.role != "owner":
            return ApiResponse.fail("FORBIDDEN", "Лише власник може видаляти майстрів")
        m_id = body.get("master_id")
        m = await db.get(Master, m_id)
        if not m or m.deleted:
            return ApiResponse.fail("NOT_FOUND", "Майстра не знайдено")
        m.soft_delete()
        m.is_active = False

        # Revoke all device tokens immediately!
        dev_stmt = select(Device).where(Device.master_id == m_id)
        dev_res = await db.execute(dev_stmt)
        for dev in dev_res.scalars().all():
            dev.is_active = False
            dev.soft_delete()

        await db.commit()
        return ApiResponse.ok({"message": "Майстра видалено та всі токени авторизації відкликано"})

    if action == "master_deletion_impact":
        m_id = body.get("master_id")
        today = utc_now().strftime("%Y-%m-%d")
        stmt = select(func.count(Appointment.id)).where(
            Appointment.master_id == m_id,
            Appointment.date >= today,
            Appointment.status.notin_(["CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER", "COMPLETED"]),
            Appointment.deleted.is_(False)
        )
        cnt = (await db.execute(stmt)).scalar() or 0
        return ApiResponse.ok({"master_id": m_id, "active_future_appointments": cnt})

    # 13. Action: settings
    if action == "settings_get":
        s_res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        items = s_res.scalars().all()
        return ApiResponse.ok({item.key: item.value for item in items})

    if action in ["settings_update", "studio_setting_set"]:
        if "key" in body:
            updates = {body["key"]: body.get("value", "")}
        else:
            updates = body.get("settings", {})
        for k, v in updates.items():
            st_res = await db.execute(select(StudioSetting).where(StudioSetting.key == k))
            item = st_res.scalar_one_or_none()
            if not item:
                item = StudioSetting(key=k, value=str(v))
                db.add(item)
            else:
                item.value = str(v)
                item.mark_updated()
        await db.commit()
        return ApiResponse.ok({"message": "Налаштування успішно збережено"})

    # 14. Action: review_moderate
    if action == "review_moderate":
        rev_id = body.get("review_id")
        mod_status = body.get("status", "APPROVED")  # APPROVED / REJECTED
        is_pub = bool(body.get("is_published", True if mod_status == "APPROVED" else False))
        rev = await db.get(Review, rev_id)
        if not rev or rev.deleted:
            return ApiResponse.fail("NOT_FOUND", "Відгук не знайдено")
        rev.moderation_status = mod_status
        rev.is_published = is_pub
        rev.mark_updated()
        await db.commit()
        return ApiResponse.ok({"message": "Статус відгуку оновлено", "review_id": rev_id})

    # 15. Action: finance_export_csv (owner only)
    if action == "finance_export_csv":
        if current_master.role != "owner":
            return ApiResponse.fail("FORBIDDEN", "Лише власник має доступ до експорту фінансів")
        from_date = body.get("from_date", "2026-01-01")
        to_date = body.get("to_date", "2026-12-31")
        import csv
        import io
        output = io.StringIO()
        writer = csv.writer(output)
        writer.writerow(["Type", "Date", "Category", "Amount", "Payment Method", "Description/Client"])

        inc_stmt = (
            select(Income)
            .where(Income.date >= from_date, Income.date <= to_date, Income.deleted.is_(False))
            .order_by(Income.date.asc())
        )
        for inc in (await db.execute(inc_stmt)).scalars().all():
            client_name = ""
            if inc.client_id:
                cl = await db.get(Client, inc.client_id)
                if cl:
                    client_name = f"{cl.first_name} {cl.last_name or ''}".strip()
            writer.writerow(["INCOME", inc.date, inc.category, str(inc.amount), inc.payment_method, client_name])

        exp_stmt = select(Expense).where(
            Expense.date >= from_date, Expense.date <= to_date, Expense.deleted.is_(False)
        ).order_by(Expense.date.asc())
        for exp in (await db.execute(exp_stmt)).scalars().all():
            writer.writerow(["EXPENSE", exp.date, exp.category, str(exp.amount), exp.payment_method, exp.description])

        csv_content = output.getvalue()
        if request.query_params.get("format") == "raw" or "text/csv" in request.headers.get("accept", ""):
            return Response(
                content=csv_content,
                media_type="text/csv",
                headers={"Content-Disposition": f"attachment; filename=finance_report_{from_date}_{to_date}.csv"}
            )
        return ApiResponse.ok({
            "csv": csv_content,
            "from_date": from_date,
            "to_date": to_date
        })

    # 16. Action: photo_upload
    if action == "photo_upload":
        c_id = body.get("client_id")
        apt_id = body.get("appointment_id")
        photo_type = body.get("photo_type", "AFTER")
        b64_data = body.get("image_base64")
        filename = body.get("filename") or f"photo_{uuid.uuid4().hex[:8]}.jpg"

        c_id = body.get("client_id") or "unknown"

        upload_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "uploads")
        os.makedirs(upload_dir, exist_ok=True)
        file_path = os.path.join(upload_dir, filename)

        raw_b64 = b64_data
        if b64_data:
            if "," in b64_data:
                b64_data = b64_data.split(",", 1)[1]
            with open(file_path, "wb") as f:
                f.write(base64.b64decode(b64_data))

        file_url = raw_b64 if raw_b64 and raw_b64.startswith("data:") else (f"data:image/jpeg;base64,{b64_data}" if b64_data else f"/uploads/{filename}")
        photo = Photo(
            client_id=c_id,
            appointment_id=apt_id,
            photo_type=photo_type,
            file_url=file_url,
            thumbnail_url=file_url
        )
        db.add(photo)
        await db.commit()
        await db.refresh(photo)
        return ApiResponse.ok(PhotoDto.model_validate(photo).model_dump())

    # 17. Action: schedule_get
    if action == "schedule_get":
        m_id = body.get("master_id") or current_master.id
        s_stmt = select(Schedule).where(Schedule.master_id == m_id, Schedule.deleted.is_(False)).order_by(Schedule.day_of_week.asc())
        schedules = (await db.execute(s_stmt)).scalars().all()

        if not schedules:
            schedules = []
            for dow in range(7):
                is_work = dow < 5  # Mon-Fri
                sch = Schedule(
                    master_id=m_id,
                    day_of_week=dow,
                    start_time="09:00",
                    end_time="19:00",
                    slot_step_minutes=30,
                    is_working_day=is_work
                )
                db.add(sch)
                schedules.append(sch)
            await db.commit()
            for sch in schedules:
                await db.refresh(sch)

        exc_stmt = select(ScheduleException).where(
            ScheduleException.master_id == m_id,
            ScheduleException.deleted.is_(False)
        ).order_by(ScheduleException.date.asc())
        exceptions = (await db.execute(exc_stmt)).scalars().all()

        return ApiResponse.ok({
            "master_id": m_id,
            "schedules": [ScheduleDto.model_validate(s).model_dump() for s in schedules],
            "exceptions": [ScheduleExceptionDto.model_validate(e).model_dump() for e in exceptions]
        })

    # 18. Action: schedule_update
    if action == "schedule_update":
        m_id = body.get("master_id") or current_master.id
        days_data = body.get("days", [])
        for item in days_data:
            dow = item.get("day_of_week")
            if dow is None:
                continue
            sch_stmt = select(Schedule).where(Schedule.master_id == m_id, Schedule.day_of_week == dow, Schedule.deleted.is_(False))
            sch = (await db.execute(sch_stmt)).scalar_one_or_none()
            if not sch:
                sch = Schedule(
                    master_id=m_id,
                    day_of_week=dow,
                    start_time=item.get("start_time", "09:00"),
                    end_time=item.get("end_time", "19:00"),
                    slot_step_minutes=item.get("slot_step_minutes", 30),
                    is_working_day=bool(item.get("is_working_day", True))
                )
                db.add(sch)
            else:
                sch.start_time = item.get("start_time", sch.start_time)
                sch.end_time = item.get("end_time", sch.end_time)
                sch.slot_step_minutes = item.get("slot_step_minutes", sch.slot_step_minutes)
                sch.is_working_day = bool(item.get("is_working_day", sch.is_working_day))
                sch.mark_updated()
        await db.commit()
        return ApiResponse.ok({"message": "Графік роботи успішно оновлено"})

    # 19. Action: schedule_exception_add
    if action == "schedule_exception_add":
        m_id = body.get("master_id") or current_master.id
        date_str = body.get("date")
        if not date_str:
            return ApiResponse.fail("BAD_REQUEST", "date є обов'язковим")
        exc_stmt = select(ScheduleException).where(ScheduleException.master_id == m_id, ScheduleException.date == date_str, ScheduleException.deleted.is_(False))
        exc = (await db.execute(exc_stmt)).scalar_one_or_none()
        if not exc:
            exc = ScheduleException(
                master_id=m_id,
                date=date_str,
                start_time=body.get("start_time"),
                end_time=body.get("end_time"),
                is_day_off=bool(body.get("is_day_off", True)),
                reason=body.get("reason", "Вихідний")
            )
            db.add(exc)
        else:
            exc.start_time = body.get("start_time")
            exc.end_time = body.get("end_time")
            exc.is_day_off = bool(body.get("is_day_off", True))
            exc.reason = body.get("reason", exc.reason)
            exc.mark_updated()
        await db.commit()
        await db.refresh(exc)
        return ApiResponse.ok(ScheduleExceptionDto.model_validate(exc).model_dump())

    # 20. Action: reminder_template_update
    if action == "reminder_template_update":
        t_type = body.get("type")
        text = body.get("template_text")
        if not t_type or not text:
            return ApiResponse.fail("BAD_REQUEST", "type та template_text обов'язкові")
        tmpl_stmt = select(ReminderTemplate).where(ReminderTemplate.type == t_type, ReminderTemplate.deleted.is_(False))
        tmpl = (await db.execute(tmpl_stmt)).scalar_one_or_none()
        if not tmpl:
            tmpl = ReminderTemplate(
                type=t_type,
                template_text=text,
                is_active=bool(body.get("is_active", True))
            )
            db.add(tmpl)
        else:
            tmpl.template_text = text
            if "is_active" in body:
                tmpl.is_active = bool(body["is_active"])
            tmpl.mark_updated()
        await db.commit()
        await db.refresh(tmpl)
        return ApiResponse.ok(ReminderTemplateDto.model_validate(tmpl).model_dump())

    # Action: reminder_templates_get
    if action == "reminder_templates_get":
        tmpls = (await db.execute(select(ReminderTemplate).where(ReminderTemplate.deleted.is_(False)))).scalars().all()
        return ApiResponse.ok([ReminderTemplateDto.model_validate(t).model_dump(mode="json") for t in tmpls])

    # 21. Action: clean_demo_data
    if action == "clean_demo_data":
        if current_master.role != "owner":
            return ApiResponse.fail("FORBIDDEN", "Лише власник може очищати демо-дані")
        apts = (await db.execute(select(Appointment).where(Appointment.deleted.is_(False)))).scalars().all()
        for a in apts:
            a.soft_delete()
        incs = (await db.execute(select(Income).where(Income.deleted.is_(False)))).scalars().all()
        for i in incs:
            i.soft_delete()
        exps = (await db.execute(select(Expense).where(Expense.deleted.is_(False)))).scalars().all()
        for e in exps:
            e.soft_delete()
        photos = (await db.execute(select(Photo).where(Photo.deleted.is_(False)))).scalars().all()
        for p in photos:
            p.soft_delete()
        vbs = (await db.execute(select(VisitBrief).where(VisitBrief.deleted.is_(False)))).scalars().all()
        for v in vbs:
            v.soft_delete()
        await db.commit()
        return ApiResponse.ok({"message": "Демо-дані успішно очищено. Налаштування студії та майстри збережені."})

    return ApiResponse.fail("UNKNOWN_ACTION", f"Невідомий екшен: {action}")
