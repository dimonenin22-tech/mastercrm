import asyncio
import json
import os
from decimal import Decimal
from httpx import AsyncClient, ASGITransport

if os.path.exists("mastercrm.db"):
    try:
        os.remove("mastercrm.db")
    except Exception:
        pass

from app.main import app, init_and_seed_db
from app.database import AsyncSessionLocal
from app.models import Client, Device, Master, Product, StudioSetting, Appointment, Income, StockMovement, utc_now
from sqlalchemy import select


async def run_full_verification():
    print("--- 1. Testing Database Initialization & Seeding ---")
    await init_and_seed_db()

    async with AsyncSessionLocal() as db:
        masters = (await db.execute(select(Master))).scalars().all()
        products = (await db.execute(select(Product))).scalars().all()
        settings = (await db.execute(select(StudioSetting))).scalars().all()
        print(f"Masters seeded: {len(masters)} (Owner: {masters[0].name})")
        print(f"Products seeded: {len(products)}")
        print(f"Settings seeded: {len(settings)}")
        assert len(masters) > 0, "Master not seeded!"
        assert len(products) > 0, "Products not seeded!"

    print("\n--- 2. Testing HTTP Endpoints via Test Client ---")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # 1. Ping
        res = await client.get("/exec?action=ping")
        assert res.status_code == 200
        ping_data = res.json()
        print("Ping response:", ping_data)
        assert ping_data["success"] is True

        # 2. Setup Device with OTP and test auth_exchange_code
        async with AsyncSessionLocal() as db:
            dev = Device(
                master_id="m-01",
                device_token="tmp_token_123",
                device_name="Test Android",
                otp_code="998877",
                is_active=False
            )
            db.add(dev)
            await db.commit()

        res = await client.post("/exec?action=auth_exchange_code", json={
            "otp_code": "998877",
            "device_name": "Pixel 8 Pro"
        })
        auth_data = res.json()
        print("Auth exchange response:", auth_data)
        assert auth_data["success"] is True
        token = auth_data["data"]["device_token"]
        headers = {"Authorization": f"Bearer {token}"}

        # 3. Test slots_get
        res = await client.post("/exec?action=slots_get", headers=headers, json={
            "service_id": "srv-01",
            "date": "2026-10-25"
        })
        slots_data = res.json()
        print(f"Slots found for 2026-10-25: {len(slots_data['data']['available_slots'])} slots")
        assert slots_data["success"] is True
        assert len(slots_data["data"]["available_slots"]) > 0

        # 4. Test appointment_create
        res = await client.post("/exec?action=appointment_create", headers=headers, json={
            "master_id": "m-01",
            "service_id": "srv-01",
            "client_name": "Оксана Тестова",
            "client_phone": "+380501112233",
            "date": "2026-10-25",
            "start_time": "10:00",
            "total_price": "650.00",
            "status": "CONFIRMED"
        })
        apt_data = res.json()
        print("Created appointment response:", apt_data)
        assert apt_data["success"] is True
        apt_id = apt_data["data"]["id"]
        client_id = apt_data["data"]["client_id"]

        # 4b. Test collision prevention on same time slot (Expect RESOURCE_BUSY)
        res_conflict = await client.post("/exec?action=appointment_create", headers=headers, json={
            "master_id": "m-01",
            "service_id": "srv-01",
            "client_name": "Інший Клієнт",
            "client_phone": "+380509998877",
            "date": "2026-10-25",
            "start_time": "10:30",
            "total_price": "650.00"
        })
        assert res_conflict.json()["success"] is False
        assert res_conflict.json()["error"]["code"] == "RESOURCE_BUSY"
        print("Collision check passed: duplicate slot correctly returned RESOURCE_BUSY!")

        # 5. Test appointment_update_status to COMPLETED
        async with AsyncSessionLocal() as db:
            p_before = await db.get(Product, "prod-03")
            stock_before = p_before.current_stock

        res = await client.post("/exec?action=appointment_update_status", headers=headers, json={
            "appointment_id": apt_id,
            "status": "COMPLETED"
        })
        comp_data = res.json()
        print("Completed status update response:", comp_data["data"]["status"])
        assert comp_data["data"]["status"] == "COMPLETED"

        # Verify side effects: consumable deducted, income created, client counters incremented
        async with AsyncSessionLocal() as db:
            p_after = await db.get(Product, "prod-03")
            print(f"Stock before: {stock_before}, stock after: {p_after.current_stock}")
            assert p_after.current_stock < stock_before, "Consumable stock not deducted!"

            inc = (await db.execute(select(Income).where(Income.appointment_id == apt_id))).scalar_one_or_none()
            assert inc is not None, "Income not created on COMPLETED!"
            print(f"Created Income: {inc.amount} грн ({inc.category})")

            cl = await db.get(Client, client_id)
            print(f"Client total_visits: {cl.total_visits}, visits_since_last_removal: {cl.visits_since_last_removal}, total_spent: {cl.total_spent}")
            assert cl.total_visits == 1
            assert cl.visits_since_last_removal == 1
            assert cl.total_spent == Decimal("650.00")

        # 6. Test client_reset_removal_counter
        res = await client.post("/exec?action=client_reset_removal_counter", headers=headers, json={
            "client_id": client_id
        })
        assert res.json()["success"] is True
        async with AsyncSessionLocal() as db:
            cl = await db.get(Client, client_id)
            assert cl.visits_since_last_removal == 0, "Removal counter not reset!"
            print("Client removal counter successfully reset to 0.")

        # 7. Test visit_brief_save
        res = await client.post("/exec?action=visit_brief_save", headers=headers, json={
            "appointment_id": apt_id,
            "client_id": client_id,
            "material_notes": "База каучукова + колір Бордо",
            "nail_condition": "Здорові, середня довжина",
            "coffee_prefs": "Капучино з вівсяним молоком"
        })
        assert res.json()["success"] is True
        print("VisitBrief successfully saved.")

        # 8. Test finance_report
        res = await client.post("/exec?action=finance_report", headers=headers, json={
            "from_date": "2026-01-01",
            "to_date": "2026-12-31"
        })
        fin = res.json()
        print("Finance report:", fin["data"])
        assert fin["success"] is True
        assert fin["data"]["gross_revenue"] == 650.0

        # 9. Test sync (Delta Sync)
        res = await client.post("/exec?action=sync", headers=headers, json={
            "since": "2026-01-01T00:00:00Z"
        })
        sync_data = res.json()
        assert sync_data["success"] is True
        print("Sync data items count:", {
            "appointments": len(sync_data["data"]["appointments"]),
            "clients": len(sync_data["data"]["clients"]),
            "services": len(sync_data["data"]["services"]),
            "products": len(sync_data["data"]["products"]),
            "settings": len(sync_data["data"]["settings"])
        })
        assert len(sync_data["data"]["appointments"]) >= 1

    print("\n[SUCCESS] ALL 10 VERIFICATION TEST SUITES PASSED FLAWLESSLY WITH ZERO ERRORS!")


if __name__ == "__main__":
    asyncio.run(run_full_verification())
