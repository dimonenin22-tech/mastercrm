import asyncio
import datetime
import os
import sys
import threading
import time
from decimal import Decimal
from typing import Any, Optional

import httpx
from sqlalchemy import select

# ANSI Colors
GREEN = "\033[92m"
RED = "\033[91m"
BOLD = "\033[1m"
RESET = "\033[0m"

BASE_URL = os.getenv("TEST_SERVER_URL", "http://127.0.0.1:8000")


def print_pass(step_num: int, title: str, details: str = ""):
    print(f"{GREEN}{BOLD}[PASS] Step {step_num}: {title}{RESET}")
    if details:
        print(f"       -> {details}")


def print_fail(step_num: int, title: str, error: str):
    print(f"{RED}{BOLD}[FAIL] Step {step_num}: {title}{RESET}")
    print(f"       -> {error}")
    sys.exit(1)


async def ensure_server_running():
    """Verify if local server is responding; if not, spin up uvicorn server in a background thread."""
    async with httpx.AsyncClient(timeout=2.0) as client:
        try:
            r = await client.get(f"{BASE_URL}/exec?action=ping")
            if r.status_code == 200:
                print(f"[INFO] Connected to existing server at {BASE_URL}")
                return None
        except Exception:
            pass

    print(f"[INFO] No server found on {BASE_URL}. Launching background Uvicorn server...")
    import uvicorn
    from app.main import app, init_and_seed_db

    # Ensure DB initialized
    await init_and_seed_db()

    config = uvicorn.Config(app=app, host="127.0.0.1", port=8000, log_level="warning")
    server = uvicorn.Server(config)

    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()

    # Wait for server ready
    for _ in range(30):
        await asyncio.sleep(0.3)
        try:
            async with httpx.AsyncClient(timeout=1.0) as client:
                res = await client.get(f"{BASE_URL}/exec?action=ping")
                if res.status_code == 200:
                    print(f"[INFO] Background server ready at {BASE_URL}")
                    return server
        except Exception:
            continue
    raise RuntimeError("Failed to start local test server on 127.0.0.1:8000")


async def run_smoke_e2e():
    print(f"\n{BOLD}======================================================{RESET}")
    print(f"{BOLD}   MasterCRM v2.0 - Automated E2E Smoke-Test Suite   {RESET}")
    print(f"{BOLD}======================================================\n{RESET}")

    server = await ensure_server_running()

    # Pre-record start timestamp for delta-sync check
    test_start_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
    # Subtract 5 seconds to ensure inclusive delta-window
    since_dt = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)).isoformat()

    from app.database import AsyncSessionLocal
    from app.models import Appointment, Client, Device, Income, Master, Product, utc_now

    async with httpx.AsyncClient(base_url=BASE_URL, timeout=10.0) as client:
        # ====================================================================
        # STEP 1: GET /exec?action=ping
        # ====================================================================
        try:
            res1 = await client.get("/exec?action=ping")
            assert res1.status_code == 200, f"Expected 200, got {res1.status_code}"
            data1 = res1.json()
            assert data1.get("success") is True, f"Expected success=True, got {data1}"
            assert data1.get("status") == "success", f"Expected status='success', got {data1}"
            print_pass(1, "GET /exec?action=ping", f"Server response: {data1['data']}")
        except Exception as e:
            print_fail(1, "GET /exec?action=ping", str(e))

        # ====================================================================
        # STEP 2: Generate test OTP in Device for m-01 & call auth_exchange_code
        # ====================================================================
        test_otp = "812345"
        device_token = ""
        try:
            async with AsyncSessionLocal() as db:
                # Ensure m-01 exists
                m01 = await db.get(Master, "m-01")
                if not m01:
                    m01 = Master(id="m-01", name="Owner Master", phone="+380990001122", role="owner", is_active=True)
                    db.add(m01)
                    await db.flush()

                # Add device with test_otp
                test_dev = Device(
                    master_id="m-01",
                    device_token=f"pending_smoke_{test_otp}",
                    device_name="E2E Smoke Tester",
                    otp_code=test_otp,
                    otp_expires_at=utc_now() + datetime.timedelta(minutes=15),
                    is_active=False
                )
                db.add(test_dev)
                await db.commit()

            res2 = await client.post("/exec?action=auth_exchange_code", json={
                "otp_code": test_otp,
                "device_name": "E2E Test Runner"
            })
            assert res2.status_code == 200, f"Expected 200, got {res2.status_code}"
            data2 = res2.json()
            assert data2.get("success") is True, f"Expected success=True: {data2}"
            device_token = data2["data"]["device_token"]
            assert device_token.startswith("devtok_"), f"Token does not start with devtok_: {device_token}"
            assert data2["data"]["master_id"] == "m-01"
            assert data2["data"]["role"] == "owner"
            assert "master" in data2["data"]
            print_pass(2, "POST /exec?action=auth_exchange_code", f"Device token obtained: {device_token[:18]}... (Role: {data2['data']['role']})")
        except Exception as e:
            print_fail(2, "POST /exec?action=auth_exchange_code", str(e))

        headers = {"Authorization": f"Bearer {device_token}"}

        # ====================================================================
        # STEP 3: POST /exec?action=service_create with token
        # ====================================================================
        created_service_id = ""
        try:
            # We link consumable prod-03 (qty 1.00) so later completed status deduction works
            res3 = await client.post("/exec?action=service_create", headers=headers, json={
                "category_id": "cat-01",
                "title": "VIP Е2Е Манікюр Смок-тест",
                "price": "850.00",
                "duration_minutes": 60,
                "buffer_minutes": 15,
                "is_active": True,
                "consumable_product_id": "prod-03",
                "quantity_per_service": "1.00"
            })
            assert res3.status_code == 200, f"Expected 200, got {res3.status_code}"
            data3 = res3.json()
            assert data3.get("success") is True, f"Expected success=True: {data3}"
            created_service_id = data3["data"]["id"]
            assert created_service_id, "Service ID is empty!"
            print_pass(3, "POST /exec?action=service_create", f"Service created: id={created_service_id}, title='{data3['data']['title']}', price={data3['data']['price']} грн")
        except Exception as e:
            print_fail(3, "POST /exec?action=service_create", str(e))

        # ====================================================================
        # STEP 4: GET /exec?action=slots_get&master_id=m-01&service_id=<id>&date=2026-12-10
        # ====================================================================
        target_date = "2026-12-10"
        async with AsyncSessionLocal() as db:
            del_stmt = select(Appointment).where(Appointment.master_id == "m-01", Appointment.date == target_date, Appointment.deleted.is_(False))
            for old_apt in (await db.execute(del_stmt)).scalars().all():
                old_apt.soft_delete()
            await db.commit()

        try:
            url4 = f"/exec?action=slots_get&master_id=m-01&service_id={created_service_id}&date={target_date}"
            res4 = await client.get(url4, headers=headers)
            assert res4.status_code == 200, f"Expected 200, got {res4.status_code}"
            data4 = res4.json()
            assert data4.get("success") is True, f"Expected success=True: {data4}"
            slots = data4["data"]["available_slots"]
            assert isinstance(slots, list), f"Expected list of slots, got {type(slots)}"
            assert "14:00" in slots, f"Slot 14:00 must be available on empty date, got {slots}"
            print_pass(4, f"GET /exec?action=slots_get", f"Found {len(slots)} available slots for {target_date} (incl. 14:00)")
        except Exception as e:
            print_fail(4, f"GET /exec?action=slots_get", str(e))

        # ====================================================================
        # STEP 5: POST /exec?action=appointment_create (3 parallel threads on slot 14:00)
        # ====================================================================
        created_apt_id = ""
        created_client_id = ""
        try:
            async def book_attempt(client_suffix: int):
                return await client.post("/exec?action=appointment_create", headers=headers, json={
                    "master_id": "m-01",
                    "service_id": created_service_id,
                    "date": target_date,
                    "start_time": "14:00",
                    "client_name": f"Паралельний Клієнт #{client_suffix}",
                    "client_phone": f"+38050999000{client_suffix}",
                    "total_price": "850.00"
                })

            # Fire 3 requests in parallel
            responses = await asyncio.gather(
                book_attempt(1),
                book_attempt(2),
                book_attempt(3),
                return_exceptions=False
            )

            status_codes = [r.status_code for r in responses]
            successes = [r for r in responses if r.status_code == 200]
            conflicts = [r for r in responses if r.status_code == 409]

            assert len(successes) == 1, f"Expected exactly 1 success (200), got {len(successes)}. Statuses: {status_codes}"
            assert len(conflicts) == 2, f"Expected exactly 2 conflicts (409), got {len(conflicts)}. Statuses: {status_codes}"

            # Verify the 409 responses have the proper error envelope
            for cr in conflicts:
                c_json = cr.json()
                assert c_json.get("success") is False, f"409 must have success=False: {c_json}"
                assert c_json.get("error", {}).get("code") == "RESOURCE_BUSY", f"409 must have code RESOURCE_BUSY: {c_json}"

            succ_data = successes[0].json()["data"]
            created_apt_id = succ_data["id"]
            created_client_id = succ_data["client_id"]

            print_pass(
                5,
                "POST appointment_create (3 parallel requests on 14:00)",
                f"Concurrency safe! Exactly 1 succeeded (HTTP 200, apt_id={created_apt_id}) & 2 rejected with HTTP 409 RESOURCE_BUSY"
            )
        except Exception as e:
            print_fail(5, "POST appointment_create concurrency collision", str(e))

        # ====================================================================
        # STEP 6: POST /exec?action=appointment_update_status (new_status="COMPLETED")
        # ====================================================================
        try:
            # Query product stock before
            async with AsyncSessionLocal() as db:
                p_before = await db.get(Product, "prod-03")
                stock_before = p_before.current_stock

            res6 = await client.post("/exec?action=appointment_update_status", headers=headers, json={
                "appointment_id": created_apt_id,
                "new_status": "COMPLETED"
            })
            assert res6.status_code == 200, f"Expected 200, got {res6.status_code}"
            data6 = res6.json()
            assert data6.get("success") is True, f"Expected success=True: {data6}"
            assert data6["data"]["status"] == "COMPLETED"

            # Assert database side-effects
            async with AsyncSessionLocal() as db:
                # 1. Product stock deducted
                p_after = await db.get(Product, "prod-03")
                assert p_after.current_stock == stock_before - Decimal("1.00"), (
                    f"Product stock not deducted! Before: {stock_before}, After: {p_after.current_stock}"
                )

                # 2. Income auto-created
                inc_stmt = select(Income).where(Income.appointment_id == created_apt_id)
                inc = (await db.execute(inc_stmt)).scalar_one_or_none()
                assert inc is not None, "Income record was not created on COMPLETED!"
                assert inc.amount == Decimal("850.00"), f"Income amount mismatch: {inc.amount}"

                # 3. Client visits_since_last_removal incremented
                cl = await db.get(Client, created_client_id)
                assert cl.visits_since_last_removal >= 1, (
                    f"Client visits_since_last_removal not incremented! Value: {cl.visits_since_last_removal}"
                )

            print_pass(
                6,
                "POST appointment_update_status (COMPLETED)",
                f"Income created ({inc.amount} грн), Product prod-03 stock deducted ({stock_before} -> {p_after.current_stock}), Client removal counter incremented to {cl.visits_since_last_removal}"
            )
        except Exception as e:
            print_fail(6, "POST appointment_update_status (COMPLETED)", str(e))

        # ====================================================================
        # STEP 7: POST /exec?action=sync with since timestamp
        # ====================================================================
        try:
            res7 = await client.post("/exec?action=sync", headers=headers, json={
                "since": since_dt
            })
            assert res7.status_code == 200, f"Expected 200, got {res7.status_code}"
            data7 = res7.json()
            assert data7.get("success") is True, f"Expected success=True: {data7}"
            sync_payload = data7["data"]

            # Verify delta contains newly created/modified records
            synced_apt_ids = [a["id"] for a in sync_payload["appointments"]]
            synced_service_ids = [s["id"] for s in sync_payload["services"]]
            synced_client_ids = [c["id"] for c in sync_payload["clients"]]
            synced_income_apt_ids = [i.get("appointment_id") for i in sync_payload["incomes"]]

            assert created_apt_id in synced_apt_ids, f"Appointment {created_apt_id} missing in delta sync!"
            assert created_service_id in synced_service_ids, f"Service {created_service_id} missing in delta sync!"
            assert created_client_id in synced_client_ids, f"Client {created_client_id} missing in delta sync!"
            assert created_apt_id in synced_income_apt_ids, f"Income for apt {created_apt_id} missing in delta sync!"

            # Verify tombstones boolean property
            for a in sync_payload["appointments"]:
                assert "deleted" in a and isinstance(a["deleted"], bool), "deleted field missing in AppointmentDto"

            print_pass(
                7,
                "POST /exec?action=sync (Delta-Sync)",
                f"Delta payload verified! Synced {len(sync_payload['appointments'])} appointments, {len(sync_payload['services'])} services, {len(sync_payload['incomes'])} incomes with valid tombstones"
            )
        except Exception as e:
            print_fail(7, "POST /exec?action=sync", str(e))

    print(f"\n{GREEN}{BOLD}======================================================{RESET}")
    print(f"{GREEN}{BOLD} [SUCCESS] ALL 7 SMOKE-TEST STEPS PASSED 100% GREEN!  {RESET}")
    print(f"{GREEN}{BOLD}======================================================\n{RESET}")


if __name__ == "__main__":
    asyncio.run(run_smoke_e2e())
