import hashlib
import json
import logging
import secrets
from datetime import datetime, time, timedelta, timezone
from decimal import Decimal
from typing import Any, Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from sqlalchemy import func, select, or_, and_
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db, is_sqlite
from app.models import (
    Appointment,
    Client,
    Device,
    Expense,
    Income,
    Master,
    NotificationLog,
    Product,
    Review,
    Service,
    ServiceCategory,
    ServiceConsumable,
    StockMovement,
    StudioSetting,
    VisitBrief,
    WaitingList,
    utc_now,
)
from app.schemas import (
    ApiResponse,
    AppointmentDto,
    ClientDto,
    ExpenseDto,
    IncomeDto,
    MasterDto,
    ProductDto,
    ReviewDto,
    ServiceDto,
    SyncResponseData,
)

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Unified API"])


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


@router.api_route("/exec", methods=["GET", "POST"])
async def unified_exec(
    request: Request,
    action: str = Query(..., description="Action name to execute"),
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

    # 2. Dispatch actions
    # Public actions: ping, auth_exchange_code
    if action == "ping":
        return ApiResponse.ok({"pong": True, "time": utc_now().isoformat(), "server": "MasterCRM v2.0"})

    if action == "auth_exchange_code":
        otp = str(body.get("otp_code", "")).strip()
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

    # Protected actions: require valid current_master
    if not current_master:
        return ApiResponse.fail("UNAUTHORIZED", "Потрібна авторизація пристрою (Authorization: Bearer <device_token>)")

    # 3. Action: sync
    if action == "sync":
        since_str = body.get("since")
        since_dt: Optional[datetime] = None
        if since_str:
            try:
                clean_str = since_str.replace("Z", "+00:00")
                since_dt = datetime.fromisoformat(clean_str)
                if since_dt.tzinfo is None:
                    since_dt = since_dt.replace(tzinfo=timezone.utc)
            except Exception:
                since_dt = None

        async def fetch_table(model: Any) -> list[Any]:
            query = select(model)
            if since_dt:
                query = query.where(model.updated_at >= since_dt)
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

        # Settings dictionary
        settings_res = await db.execute(select(StudioSetting).where(StudioSetting.deleted.is_(False)))
        all_settings = settings_res.scalars().all()
        settings_map = {s.key: s.value for s in all_settings}

        data = SyncResponseData(
            appointments=[AppointmentDto.model_validate(a) for a in appointments],
            clients=[ClientDto.model_validate(c) for c in clients],
            services=[ServiceDto.model_validate(s) for s in services],
            masters=[MasterDto.model_validate(m) for m in masters],
            incomes=[IncomeDto.model_validate(i) for i in incomes],
            expenses=[ExpenseDto.model_validate(e) for e in expenses],
            products=[ProductDto.model_validate(p) for p in products],
            reviews=[ReviewDto.model_validate(r) for r in reviews],
            settings=settings_map,
            server_sync_time=utc_now().isoformat(),
        )
        return ApiResponse.ok(data.model_dump())

    # 4. Action: slots_get
    if action == "slots_get":
        master_id = body.get("master_id") or current_master.id
        service_id = body.get("service_id")
        date_str = body.get("date")  # YYYY-MM-DD

        if not service_id or not date_str:
            return ApiResponse.fail("BAD_REQUEST", "Параметри service_id та date є обов'язковими")

        srv = await db.get(Service, service_id)
        if not srv or srv.deleted:
            return ApiResponse.fail("SERVICE_NOT_FOUND", "Послуга не знайдена")

        duration_with_buffer = srv.duration_minutes + srv.buffer_minutes

        # Query existing appointments for this master and date
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

        # Generate slots 09:00 to 19:00 with step 30 min
        available_slots = []
        day_start = 9 * 60   # 09:00
        day_end = 19 * 60    # 19:00

        current_min = day_start
        while current_min + srv.duration_minutes <= day_end:
            slot_start = current_min
            slot_end = current_min + duration_with_buffer
            
            # Check overlap: (slot_start < apt_end) and (slot_end > apt_start)
            conflict = False
            for apt_start, apt_end in occupied_intervals:
                if slot_start < apt_end and slot_end > apt_start:
                    conflict = True
                    break

            if not conflict:
                h = current_min // 60
                m = current_min % 60
                available_slots.append(f"{h:02d}:{m:02d}")

            current_min += 30

        return ApiResponse.ok({
            "master_id": master_id,
            "service_id": service_id,
            "date": date_str,
            "available_slots": available_slots,
        })

    # 5. Action: appointment_create
    if action == "appointment_create":
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
            cancellation_reason=""
        )
        db.add(new_apt)
        await db.commit()
        await db.refresh(new_apt)
        return ApiResponse.ok(AppointmentDto.model_validate(new_apt).model_dump())

    # 6. Action: appointment_update_status
    if action == "appointment_update_status":
        apt_id = body.get("appointment_id")
        new_status = body.get("status")
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

        old_status = apt.status
        apt.status = new_status
        if reason:
            apt.cancellation_reason = reason
        apt.mark_updated()

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

        if not vb:
            vb = VisitBrief(
                appointment_id=apt_id,
                client_id=body.get("client_id", ""),
                master_id=body.get("master_id", current_master.id),
                material_notes=body.get("material_notes", ""),
                nail_condition=body.get("nail_condition", ""),
                coffee_prefs=body.get("coffee_prefs", ""),
                photo_url=body.get("photo_url", "")
            )
            db.add(vb)
        else:
            vb.material_notes = body.get("material_notes", vb.material_notes)
            vb.nail_condition = body.get("nail_condition", vb.nail_condition)
            vb.coffee_prefs = body.get("coffee_prefs", vb.coffee_prefs)
            vb.photo_url = body.get("photo_url", vb.photo_url)
            vb.mark_updated()

        await db.commit()
        return ApiResponse.ok({"message": "Бриф візиту успішно збережено", "visit_brief_id": vb.id})

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

    # 9. Action: income_add, expense_add, finance_report
    if action == "income_add":
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
        return ApiResponse.ok(IncomeDto.model_validate(inc).model_dump())

    if action == "expense_add":
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
        return ApiResponse.ok(ExpenseDto.model_validate(exp).model_dump())

    if action == "finance_report":
        from_date = body.get("from_date", "2026-01-01")
        to_date = body.get("to_date", "2026-12-31")

        # Incomes
        inc_stmt = select(Income).where(
            Income.date >= from_date,
            Income.date <= to_date,
            Income.deleted.is_(False)
        )
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
        exp_res = await db.execute(exp_stmt)
        all_expenses = exp_res.scalars().all()

        direct_expenses = Decimal("0.00")
        materials_cogs = Decimal("0.00")
        for e in all_expenses:
            if e.category == "MATERIALS":
                materials_cogs += e.amount
            else:
                direct_expenses += e.amount

        total_expenses = direct_expenses + materials_cogs
        net_profit = (gross_revenue + tips) - total_expenses

        # Count completed appointments in period
        apt_count_stmt = select(func.count(Appointment.id)).where(
            Appointment.date >= from_date,
            Appointment.date <= to_date,
            Appointment.status == "COMPLETED",
            Appointment.deleted.is_(False)
        )
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
        prod = Product(
            name=body.get("name", "Новий товар"),
            unit=body.get("unit", "шт"),
            current_stock=Decimal(str(body.get("current_stock", "0.00"))),
            min_stock_alert=Decimal(str(body.get("min_stock_alert", "5.00"))),
            purchase_price=Decimal(str(body.get("purchase_price", "0.00"))),
        )
        db.add(prod)
        await db.commit()
        await db.refresh(prod)
        return ApiResponse.ok(ProductDto.model_validate(prod).model_dump())

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

    # 11. Action: services CRUD
    if action == "service_create":
        srv = Service(
            category_id=body.get("category_id", "cat-01"),
            title=body.get("title", "Нова послуга"),
            price=Decimal(str(body.get("price", "0.00"))),
            duration_minutes=int(body.get("duration_minutes", 60)),
            buffer_minutes=int(body.get("buffer_minutes", 15)),
            is_active=bool(body.get("is_active", True)),
        )
        db.add(srv)
        await db.commit()
        await db.refresh(srv)
        return ApiResponse.ok(ServiceDto.model_validate(srv).model_dump())

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
        m = Master(
            name=body.get("name", "Майстер"),
            phone=body.get("phone", "+380000000000"),
            role=body.get("role", "master"),
            specialization=body.get("specialization", "Nail Master"),
            is_active=True
        )
        db.add(m)
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
        if "role" in body:
            m.role = body["role"]
        if "specialization" in body:
            m.specialization = body["specialization"]
        if "is_active" in body:
            m.is_active = bool(body["is_active"])
        m.mark_updated()
        await db.commit()
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

    if action == "settings_update":
        if current_master.role != "owner":
            return ApiResponse.fail("FORBIDDEN", "Тільки власник має доступ до налаштувань студії")
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
        return ApiResponse.ok({"message": "Налаштування успішно оновлено"})

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

    return ApiResponse.fail("UNKNOWN_ACTION", f"Невідомий екшен: {action}")
