import asyncio
import os
from contextlib import asynccontextmanager
from decimal import Decimal
import logging
from typing import AsyncGenerator

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import select

from app.api.legacy_router import router as api_router
from app.bot.bot_instance import bot, dp
from app.bot.handlers_client import client_router
from app.bot.handlers_master import master_router
from app.config import settings
from app.database import AsyncSessionLocal, engine
from app.models import (
    Base,
    Master,
    Product,
    ReminderTemplate,
    Service,
    ServiceCategory,
    ServiceConsumable,
    StudioSetting,
)
from app.scheduler.tasks import scheduler, setup_scheduler

logging.basicConfig(
    level=logging.INFO if not settings.DEBUG else logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("mastercrm")


async def init_and_seed_db() -> None:
    logger.info("Initializing database schema...")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        from sqlalchemy import text
        for col_sql in [
            "ALTER TABLE clients ADD COLUMN no_show_count INTEGER DEFAULT 0",
            "ALTER TABLE clients ADD COLUMN is_blacklisted BOOLEAN DEFAULT 0",
            "ALTER TABLE clients ADD COLUMN discount_percent INTEGER DEFAULT 0",
            "ALTER TABLE visit_briefs ADD COLUMN coating_condition TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN nail_plate_type TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN cuticle_type TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN base_gel_used TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN design_and_length TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN health_notes TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN photo_before_url TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ADD COLUMN photo_after_url TEXT DEFAULT ''",
            "ALTER TABLE reviews ADD COLUMN client_name TEXT DEFAULT ''",
            "ALTER TABLE reviews ADD COLUMN service_title TEXT DEFAULT ''",
            "ALTER TABLE reviews ADD COLUMN photo_url TEXT DEFAULT ''",
            "ALTER TABLE reviews ADD COLUMN tg_file_id TEXT DEFAULT ''",
            "ALTER TABLE reviews ADD COLUMN review_type TEXT DEFAULT 'POST_VISIT'",
            "ALTER TABLE appointments ADD COLUMN receipt_photo_url TEXT DEFAULT ''",
            "ALTER TABLE visit_briefs ALTER COLUMN photo_url TYPE TEXT",
            "ALTER TABLE visit_briefs ALTER COLUMN photo_before_url TYPE TEXT",
            "ALTER TABLE visit_briefs ALTER COLUMN photo_after_url TYPE TEXT",
            "ALTER TABLE photos ALTER COLUMN file_url TYPE TEXT",
            "ALTER TABLE photos ALTER COLUMN thumbnail_url TYPE TEXT",
            "ALTER TABLE visit_briefs DROP CONSTRAINT IF EXISTS visit_briefs_appointment_id_fkey",
            "ALTER TABLE visit_briefs DROP CONSTRAINT IF EXISTS visit_briefs_client_id_fkey",
            "ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_appointment_id_fkey",
            "ALTER TABLE photos DROP CONSTRAINT IF EXISTS photos_client_id_fkey",
            "ALTER TABLE visit_briefs ADD COLUMN consumed_materials_json TEXT DEFAULT '[]'",
            "ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS stock_movements_appointment_id_fkey",
        ]:
            try:
                await conn.execute(text(col_sql))
            except Exception:
                pass

    async with AsyncSessionLocal() as db:
        # 1. Seed StudioSettings
        default_settings = {
            "app_mode": ("SOLO", "Режим роботи: SOLO або STUDIO"),
            "deposit_required": ("false", "Чи потрібен обов'язковий завдаток: true/false"),
            "deposit_mode": ("NONE", "Режим передоплати: NONE, FIRST_TIME, ALL"),
            "deposit_amount": ("200", "Сума завдатку в грн"),
            "currency_symbol": ("$", "Символ валюти для відображення: $, грн, €"),
            "card_details": ("5375 4141 1234 5678 (Анастасія В.)", "Реквізити банківської картки"),
            "jar_link": ("https://send.monobank.ua/jar/123456", "Посилання на банку Monobank"),
            "address": ("вул. Хрещатик, 1, оф. 10, Київ", "Адреса студії"),
            "phone": (settings.DEFAULT_OWNER_PHONE, "Контактний телефон студії"),
            "instagram": ("@master_crm_beauty", "Instagram акаунт студії"),
        }
        for k, (v, desc) in default_settings.items():
            res = await db.execute(select(StudioSetting).where(StudioSetting.key == k))
            if not res.scalar_one_or_none():
                db.add(StudioSetting(key=k, value=v, description=desc))

        # One-time SQL fix: tops/bases unit to мл
        try:
            await db.execute(text("UPDATE products SET unit = 'мл' WHERE (name LIKE '%ml%' OR name LIKE '%мл%' OR name LIKE '%Топ%' OR name LIKE '%База%') AND unit = 'шт'"))
            await db.commit()
        except Exception:
            pass

        # 2. Seed Default Owner Master
        res_m = await db.execute(select(Master).where(Master.id == "m-01"))
        if not res_m.scalar_one_or_none():
            db.add(
                Master(
                    id="m-01",
                    name=settings.DEFAULT_OWNER_NAME,
                    phone=settings.DEFAULT_OWNER_PHONE,
                    role="owner",
                    specialization="Топ-Майстер / Власниця",
                    is_active=True
                )
            )

        # 3. Seed Default Categories and Services
        res_cat = await db.execute(select(ServiceCategory).where(ServiceCategory.id == "cat-01"))
        if not res_cat.scalar_one_or_none():
            cat_manicure = ServiceCategory(
                id="cat-01",
                name="Манікюр",
                order_index=1,
                icon_emoji="💅",
                is_active=True
            )
            cat_pedicure = ServiceCategory(
                id="cat-02",
                name="Педикюр",
                order_index=2,
                icon_emoji="🦶",
                is_active=True
            )
            cat_care = ServiceCategory(
                id="cat-03",
                name="Догляд та SPA",
                order_index=3,
                icon_emoji="✨",
                is_active=True
            )
            db.add_all([cat_manicure, cat_pedicure, cat_care])
            await db.flush()

            # Services
            s1 = Service(
                id="srv-01",
                category_id="cat-01",
                title="Комплекс: Зняття + Манікюр + Покриття гель-лаком",
                price=Decimal("650.00"),
                duration_minutes=90,
                buffer_minutes=15,
                is_active=True
            )
            s2 = Service(
                id="srv-02",
                category_id="cat-01",
                title="Гігієнічний апаратний манікюр",
                price=Decimal("350.00"),
                duration_minutes=45,
                buffer_minutes=15,
                is_active=True
            )
            s3 = Service(
                id="srv-03",
                category_id="cat-02",
                title="Педикюр естетичний з покриттям",
                price=Decimal("800.00"),
                duration_minutes=90,
                buffer_minutes=15,
                is_active=True
            )
            db.add_all([s1, s2, s3])

        # 4. Seed Products & Inventory
        res_prod = await db.execute(select(Product).where(Product.id == "prod-01"))
        if not res_prod.scalar_one_or_none():
            p1 = Product(
                id="prod-01",
                name="База каучукова Rubber Base 15ml",
                unit="мл",
                current_stock=Decimal("30.00"),
                min_stock_alert=Decimal("5.00"),
                purchase_price=Decimal("280.00"),
            )
            p2 = Product(
                id="prod-02",
                name="Топ без липкого шару No Wipe Top 15ml",
                unit="мл",
                current_stock=Decimal("30.00"),
                min_stock_alert=Decimal("5.00"),
                purchase_price=Decimal("290.00"),
            )
            p3 = Product(
                id="prod-03",
                name="Одноразовий набір (пилка, баф, апельсинова паличка)",
                unit="шт",
                current_stock=Decimal("95.00"),
                min_stock_alert=Decimal("20.00"),
                purchase_price=Decimal("15.00"),
            )
            db.add_all([p1, p2, p3])
            await db.flush()

            # Link consumable to srv-01
            db.add(
                ServiceConsumable(
                    service_id="srv-01",
                    product_id="prod-03",
                    quantity_per_service=Decimal("1.00")
                )
            )

        # 4. Seed Default Reminder Templates (8 independent keys)
        default_templates = {
            "REMINDER_24H": "🔔 Привіт, {name}! Нагадуємо про ваш запис завтра ({date}) о {time} на послугу «{service}». Чекаємо на вас!",
            "REMINDER_2H": "⏰ Привіт, {name}! Нагадуємо, що ваш візит на «{service}» відбудеться вже сьогодні через 2 години — о {time}!",
            "REVIEW_10MIN_TEXT_ASK": "💖 Дякуємо за візит, {name}! Як вам результат процедури «{service}»? Будь ласка, оцініть якість роботи майстра від 1 до 5 зірочок нижче:",
            "REVIEW_10MIN_AUTO_REPLY": "Дякуємо за довіру! Чекаємо на наступну корекцію ❤️",
            "REVIEW_10MIN_STARS_ASK": "❤️ Дякуємо за вашу оцінку {rating} ⭐! Напишіть, будь ласка, у відповідь кілька слів про ваші враження — що сподобалося найбільше або на що варто звернути увагу 👇",
            "WEAR_2WEEKS_TEXT_ASK": "💅 Привіт, {name}! Минуло 2 тижні з вашого візиту («{service}»). Напишіть, будь ласка, як носиться покриття? Чи все тримається ідеально?",
            "WEAR_2WEEKS_PHOTO_ASK": "📸 Дякуємо за відповідь! А тепер надішліть, будь ласка, у цей чат фото стану ваших нігтів зараз (або натисніть кнопку нижче, щоб продовжити без фото):",
            "WEAR_2WEEKS_FINAL_BOOK_OFFER": "💖 Дякуємо за ваш звіт та фото! Настав час планувати наступну корекцію — оберіть зручне віконце завчасно за кнопкою нижче:",
        }
        for k, v in default_templates.items():
            t_res = await db.execute(select(ReminderTemplate).where(ReminderTemplate.type == k))
            tmpl_existing = t_res.scalar_one_or_none()
            if not tmpl_existing:
                db.add(ReminderTemplate(type=k, template_text=v, is_active=True))
            elif k == "REVIEW_10MIN_TEXT_ASK" and "зірочок" not in (tmpl_existing.template_text or ""):
                tmpl_existing.template_text = v
                tmpl_existing.mark_updated()
            elif k == "REVIEW_10MIN_STARS_ASK" and "оцінку" not in (tmpl_existing.template_text or ""):
                tmpl_existing.template_text = v
                tmpl_existing.mark_updated()

        await db.commit()
    logger.info("Database seeding completed.")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    # Startup
    logger.info("Starting MasterCRM v2.0...")
    await init_and_seed_db()
    setup_scheduler()

    bot_task: asyncio.Task | None = None
    if bot:
        dp.include_router(client_router)
        dp.include_router(master_router)
        try:
            await bot.delete_webhook(drop_pending_updates=True)
        except Exception as e:
            logger.warning("Could not delete webhook: %s", e)
        logger.info("Starting aiogram Telegram bot polling...")
        bot_task = asyncio.create_task(dp.start_polling(bot))

    yield

    # Shutdown
    logger.info("Shutting down MasterCRM...")
    if scheduler.running:
        scheduler.shutdown(wait=False)
    if bot_task:
        bot_task.cancel()
        try:
            await bot_task
        except asyncio.CancelledError:
            pass
    if bot:
        await bot.session.close()


app = FastAPI(
    title=settings.PROJECT_NAME,
    description="Zero-bug MasterCRM v2.0 Ecosystem API & Bot for Beauty Studios",
    version="2.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

uploads_dir = os.path.join(os.path.dirname(__file__), "uploads")
os.makedirs(uploads_dir, exist_ok=True)
app.mount("/uploads", StaticFiles(directory=uploads_dir), name="uploads")

app.include_router(api_router)


@app.api_route("/", methods=["GET", "HEAD"])
async def root(request: Request):
    accept = request.headers.get("accept", "")
    if "text/html" in accept and request.method == "GET":
        from app.api.legacy_router import render_public_landing_page
        return HTMLResponse(content=await render_public_landing_page())

    return {
        "app": settings.PROJECT_NAME,
        "version": "2.0.0",
        "status": "online",
        "api_docs": "/docs",
        "endpoint": "/exec?action=<action>"
    }
