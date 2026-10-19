import os
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    PROJECT_NAME: str = "MasterCRM v2.0"
    ENVIRONMENT: str = "production"
    DEBUG: bool = False
    SERVER_HOST: str = "0.0.0.0"
    SERVER_PORT: int = 8000
    DATABASE_URL: str = "sqlite+aiosqlite:///./mastercrm.db"
    BOT_TOKEN: str = ""
    TIMEZONE: str = "Europe/Kyiv"
    SECRET_KEY: str = "super-secret-mastercrm-key-9876543210"
    DEFAULT_OWNER_PHONE: str = "+380991234567"
    DEFAULT_OWNER_NAME: str = "Анастасія (Власниця)"
    MASTER_CHAT_ID: str = ""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )


settings = Settings()
