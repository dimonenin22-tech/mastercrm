import asyncio
from contextlib import asynccontextmanager
from decimal import Decimal
import logging
from typing import AsyncGenerator

from fastapi import FastAPI
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

    async with AsyncSessionLocal() as db:
        # 1. Seed StudioSettings
        default_settings = {
            "app_mode": ("SOLO", "Режим роботи: SOLO або STUDIO"),
            "deposit_required": ("false", "Чи потрібен обов'язковий завдаток: true/false"),
            "deposit_amount": ("200", "Сума завдатку в грн"),
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
                unit="шт",
                current_stock=Decimal("12.00"),
                min_stock_alert=Decimal("3.00"),
                purchase_price=Decimal("280.00"),
            )
            p2 = Product(
                id="prod-02",
                name="Топ без липкого шару No Wipe Top 15ml",
                unit="шт",
                current_stock=Decimal("8.00"),
                min_stock_alert=Decimal("2.00"),
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

app.include_router(api_router)


@app.get("/")
async def root():
    return {
        "app": settings.PROJECT_NAME,
        "version": "2.0.0",
        "status": "online",
        "api_docs": "/docs",
        "endpoint": "/exec?action=<action>"
    }
