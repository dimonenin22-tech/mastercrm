import logging
from typing import Optional
from aiogram import Bot, Dispatcher
from aiogram.enums import ParseMode
from aiogram.client.default import DefaultBotProperties
from app.config import settings

logger = logging.getLogger(__name__)

bot: Optional[Bot] = None
dp: Dispatcher = Dispatcher()

if settings.BOT_TOKEN:
    try:
        bot = Bot(
            token=settings.BOT_TOKEN,
            default=DefaultBotProperties(parse_mode=ParseMode.HTML)
        )
    except Exception as e:
        logger.error("Failed to initialize Telegram Bot: %s", e)
        bot = None
else:
    logger.warning("BOT_TOKEN is not set. Telegram bot will run in stub mode.")
