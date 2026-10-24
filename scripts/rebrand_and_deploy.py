#!/usr/bin/env python3
"""
JuNailCRM v2.4.7 — Automated White-Label Rebranding & Deployment Tool
====================================================================
Generates, rebrands, and validates a clean white-label copy of JuNailCRM
for beauty studios with ZERO regression to v2.4.7 business logic.
Triggered also via /nailcrm command.
"""

import argparse
import json
import os
import py_compile
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

# Fix Windows console UTF-8 output encoding for emojis and Ukrainian text
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


ROOT_DIR = Path(__file__).resolve().parent.parent


def print_banner():
    print(r"""
============================================================
   JuNailCRM v2.4.7 — White-Label Studio Rebranding Tool    
              Triggered via: /nailcrm                       
============================================================
""")


def create_backup() -> Path:
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = ROOT_DIR / "backups" / f"rebrand_backup_{ts}"
    backup_dir.mkdir(parents=True, exist_ok=True)

    files_to_backup = [
        "backend/app/config.py",
        "backend/app/main.py",
        "backend/app/api/legacy_router.py",
        "backend/app/bot/handlers_client.py",
        "backend/app/bot/handlers_master.py",
        "android-app/app/src/main/java/com/mastercrm/data/repository/AuthRepository.kt",
        "android-app/app/src/main/java/com/mastercrm/ui/screens/LoginScreen.kt",
        "android-app/app/src/main/java/com/mastercrm/ui/screens/MarketingScreen.kt",
        "android-app/app/src/main/java/com/mastercrm/ui/screens/ClientsScreen.kt",
        "android-app/app/src/main/java/com/mastercrm/ui/screens/SettingsScreen.kt",
        "android-app/app/src/main/res/values/strings.xml",
        "render.yaml",
    ]
    for rel in files_to_backup:
        src = ROOT_DIR / rel
        if src.exists():
            dst = backup_dir / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
    print(f"📦 Створено резервну копію конфігурацій: {backup_dir}")
    return backup_dir


def slugify(text: str) -> str:
    slug = re.sub(r"[^\w\s-]", "", text.lower()).strip()
    return re.sub(r"[-\s]+", "-", slug) or "studio"


def rebrand_backend(params: dict):
    print("\n[1/4] Ребрендинг Backend & Telegram Bot...")

    # 1. backend/app/config.py
    cfg_path = ROOT_DIR / "backend/app/config.py"
    cfg = cfg_path.read_text(encoding="utf-8")
    cfg = re.sub(r'PROJECT_NAME:\s*str\s*=\s*".*?"', f'PROJECT_NAME: str = "{params["studio_name"]}"', cfg)
    if params.get("bot_token"):
        cfg = re.sub(r'BOT_TOKEN:\s*str\s*=\s*".*?"', f'BOT_TOKEN: str = "{params["bot_token"]}"', cfg)
    if params.get("master_telegram_id"):
        cfg = re.sub(r'MASTER_CHAT_ID:\s*str\s*=\s*".*?"', f'MASTER_CHAT_ID: str = "{params["master_telegram_id"]}"', cfg)
    if params.get("owner_name"):
        cfg = re.sub(r'DEFAULT_OWNER_NAME:\s*str\s*=\s*".*?"', f'DEFAULT_OWNER_NAME: str = "{params["owner_name"]}"', cfg)
    if params.get("owner_phone"):
        cfg = re.sub(r'DEFAULT_OWNER_PHONE:\s*str\s*=\s*".*?"', f'DEFAULT_OWNER_PHONE: str = "{params["owner_phone"]}"', cfg)
    cfg_path.write_text(cfg, encoding="utf-8")
    print("  ✓ backend/app/config.py оновлено")

    # 2. backend/app/main.py
    main_path = ROOT_DIR / "backend/app/main.py"
    main_code = main_path.read_text(encoding="utf-8")
    main_code = re.sub(r'"card_details":\s*\(".*?",', f'"card_details": ("{params["card_details"]}",', main_code)
    main_code = re.sub(r'"jar_link":\s*\(".*?",', f'"jar_link": ("{params["monobank_jar"]}",', main_code)
    main_code = re.sub(r'"address":\s*\(".*?",', f'"address": ("{params["studio_address"]}",', main_code)
    main_code = re.sub(r'"instagram":\s*\(".*?",', f'"instagram": ("{params["studio_instagram"]}",', main_code)
    main_path.write_text(main_code, encoding="utf-8")
    print("  ✓ backend/app/main.py (дефолтні налаштування студії) оновлено")

    # 3. backend/app/api/legacy_router.py
    lr_path = ROOT_DIR / "backend/app/api/legacy_router.py"
    lr_code = lr_path.read_text(encoding="utf-8")
    lr_code = re.sub(r'settings_map\.get\("studio_name",\s*".*?"\)', f'settings_map.get("studio_name", "{params["studio_name"]}")', lr_code)
    lr_code = re.sub(r'settings_map\.get\("bot_username",\s*".*?"\)', f'settings_map.get("bot_username", "{params["bot_username"]}")', lr_code)
    lr_path.write_text(lr_code, encoding="utf-8")
    print("  ✓ backend/app/api/legacy_router.py (landing page фолбеки) оновлено")

    # 4. backend/app/bot/handlers_client.py
    hc_path = ROOT_DIR / "backend/app/bot/handlers_client.py"
    hc_code = hc_path.read_text(encoding="utf-8")
    hc_code = re.sub(r'студії <b>JuNail</b>', f'студії <b>{params["studio_name"]}</b>', hc_code)
    hc_code = re.sub(r'Головне меню <b>JuNail</b>', f'Головне меню <b>{params["studio_name"]}</b>', hc_code)
    hc_code = re.sub(r'студії JuNail', f'студії {params["studio_name"]}', hc_code)
    hc_code = re.sub(r'додатку JuNailCRM', f'додатку {params["studio_name"]}', hc_code)
    hc_path.write_text(hc_code, encoding="utf-8")
    print("  ✓ backend/app/bot/handlers_client.py (тексти для клієнтів) оновлено")

    # 5. backend/app/bot/handlers_master.py
    hm_path = ROOT_DIR / "backend/app/bot/handlers_master.py"
    hm_code = hm_path.read_text(encoding="utf-8")
    repo = params.get("github_repo") or "dimonenin22-tech/mastercrm"
    apk_name = params.get("apk_filename") or f"{slugify(params['studio_name'])}.apk"
    if not apk_name.endswith(".apk"):
        apk_name += ".apk"
    hm_code = re.sub(r'APK_DOWNLOAD_URL = ".*?"', f'APK_DOWNLOAD_URL = "https://github.com/{repo}/releases/download/v2.4.7/{apk_name}"', hm_code)
    hm_code = re.sub(r'APK_GITHUB_RELEASE_URL = ".*?"', f'APK_GITHUB_RELEASE_URL = "https://github.com/{repo}/releases/tag/v2.4.7"', hm_code)
    hm_code = re.sub(r'doc = URLInputFile\(APK_DOWNLOAD_URL, filename=".*?"\)', f'doc = URLInputFile(APK_DOWNLOAD_URL, filename="{apk_name}")', hm_code)
    hm_path.write_text(hm_code, encoding="utf-8")
    print("  ✓ backend/app/bot/handlers_master.py (посилання на скачування APK) оновлено")


def rebrand_android(params: dict):
    print("\n[2/4] Ребрендинг Android-додатку...")

    # 1. AuthRepository.kt
    auth_repo_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/data/repository/AuthRepository.kt"
    auth_code = auth_repo_path.read_text(encoding="utf-8")
    backend_url = params["backend_public_url"].strip()
    if not backend_url.endswith("/"):
        backend_url += "/"
    auth_code = re.sub(r'const val DEFAULT_URL = ".*?"', f'const val DEFAULT_URL = "{backend_url}"', auth_code)
    auth_repo_path.write_text(auth_code, encoding="utf-8")
    print(f"  ✓ AuthRepository.kt -> DEFAULT_URL = {backend_url}")

    # 2. LoginScreen.kt
    login_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/ui/screens/LoginScreen.kt"
    login_code = login_path.read_text(encoding="utf-8")
    login_code = re.sub(r'text\s*=\s*"JuNails?CRM",', f'text = "{params["studio_name"]}",', login_code)
    login_path.write_text(login_code, encoding="utf-8")
    print("  ✓ LoginScreen.kt -> заголовок картки оновлено")

    # 3. MarketingScreen.kt
    mkt_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/ui/screens/MarketingScreen.kt"
    mkt_code = mkt_path.read_text(encoding="utf-8")
    mkt_code = re.sub(r'studioName:\s*String\s*=\s*".*?",', f'studioName: String = "{params["studio_name"]}",', mkt_code)
    mkt_code = re.sub(r'studioName\s*=\s*"MasterCRM Studio",', f'studioName = "{params["studio_name"]}",', mkt_code)
    mkt_path.write_text(mkt_code, encoding="utf-8")
    print("  ✓ MarketingScreen.kt -> брендинг Canva Story шаблонів оновлено")

    # 4. ClientsScreen.kt
    cls_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/ui/screens/ClientsScreen.kt"
    cls_code = cls_path.read_text(encoding="utf-8")
    cls_code = cls_code.replace("MasterCRM", params["studio_name"])
    cls_code = cls_code.replace("JuNail", params["studio_name"])
    cls_path.write_text(cls_code, encoding="utf-8")
    print("  ✓ ClientsScreen.kt -> шаблони нагадувань оновлено")

    # 5. SettingsScreen.kt
    sets_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/ui/screens/SettingsScreen.kt"
    sets_code = sets_path.read_text(encoding="utf-8")
    sets_code = re.sub(r'val botUsername = ".*?"', f'val botUsername = "{params["bot_username"]}"', sets_code)
    sets_path.write_text(sets_code, encoding="utf-8")
    print("  ✓ SettingsScreen.kt -> юзернейм бота для інвайтів оновлено")

    # 6. strings.xml
    str_path = ROOT_DIR / "android-app/app/src/main/res/values/strings.xml"
    str_code = str_path.read_text(encoding="utf-8")
    str_code = re.sub(r'<string name="app_name">.*?</string>', f'<string name="app_name">{params["studio_name"]}</string>', str_code)
    str_path.write_text(str_code, encoding="utf-8")
    print(f"  ✓ strings.xml -> app_name = {params['studio_name']}")


def process_launcher_icons(icon_path: str):
    print("\n[3/4] Обробка та оновлення іконок додатку...")
    if not icon_path or not os.path.exists(icon_path):
        print(f"  ⚠️ Файл іконки не вказано або не знайдено за шляхом '{icon_path}', пропуск генерації іконок.")
        return

    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("  ⚠️ Бібліотека Pillow (PIL) не встановлена. Встановіть командою: pip install pillow")
        return

    src_img = Image.open(icon_path).convert("RGBA")

    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    res_dir = ROOT_DIR / "android-app/app/src/main/res"

    for folder, size in densities.items():
        target_dir = res_dir / folder
        target_dir.mkdir(parents=True, exist_ok=True)

        # 1. Square icon
        sq_icon = src_img.resize((size, size), Image.Resampling.LANCZOS)
        sq_icon.save(target_dir / "ic_launcher.png", format="PNG")

        # 2. Round icon (masked circle)
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)

        round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_icon.paste(sq_icon, (0, 0), mask=mask)
        round_icon.save(target_dir / "ic_launcher_round.png", format="PNG")
        print(f"  ✓ Згенеровано {folder} ({size}x{size})")

    # 3. Adaptive foreground (432x432)
    fg_size = 432
    fg_icon = src_img.resize((fg_size, fg_size), Image.Resampling.LANCZOS)
    fg_path = res_dir / "drawable/ic_launcher_foreground.png"
    fg_path.parent.mkdir(parents=True, exist_ok=True)
    fg_icon.save(fg_path, format="PNG")
    print(f"  ✓ Згенеровано drawable/ic_launcher_foreground.png ({fg_size}x{fg_size})")


def setup_hosting(params: dict):
    method = params.get("hosting_method", "RENDER").upper()
    slug = slugify(params["studio_name"])
    print(f"\n[4/4] Конфігурація середовища розгортання ({method})...")

    if method == "RENDER":
        render_yaml = ROOT_DIR / "render.yaml"
        content = f"""services:
  - type: web
    name: {slug}-backend
    runtime: docker
    dockerfilePath: ./Dockerfile
    dockerContext: .
    plan: free
    region: frankfurt
    envVars:
      - key: ENVIRONMENT
        value: production
      - key: TIMEZONE
        value: Europe/Kyiv
      - key: BOT_TOKEN
        value: "{params.get('bot_token', '')}"
      - key: MASTER_CHAT_ID
        value: "{params.get('master_telegram_id', '')}"
      - key: SECRET_KEY
        generateValue: true
      - key: DATABASE_URL
        fromDatabase:
          name: {slug}-postgres
          property: connectionString

databases:
  - name: {slug}-postgres
    plan: free
    region: frankfurt
    databaseName: {slug.replace('-', '_')}_db
    user: {slug.replace('-', '_')}
"""
        render_yaml.write_text(content, encoding="utf-8")
        print(f"  ✓ render.yaml налаштовано під сервіс '{slug}-backend'")

    elif method == "VPS_DOCKER":
        env_file = ROOT_DIR / "backend/.env"
        db_user = f"{slug.replace('-', '_')}_user"
        db_pass = f"pass_{slug.replace('-', '_')}_secure"
        db_name = f"{slug.replace('-', '_')}_db"
        content = f"""PROJECT_NAME="{params['studio_name']}"
ENVIRONMENT=production
BOT_TOKEN="{params.get('bot_token', '')}"
MASTER_CHAT_ID="{params.get('master_telegram_id', '')}"
DEFAULT_OWNER_NAME="{params.get('owner_name', '')}"
DEFAULT_OWNER_PHONE="{params.get('owner_phone', '')}"
TIMEZONE=Europe/Kyiv
SECRET_KEY="sec-{slug}-998877665544332211"
POSTGRES_USER={db_user}
POSTGRES_PASSWORD={db_pass}
POSTGRES_DB={db_name}
DATABASE_URL=postgresql+asyncpg://{db_user}:{db_pass}@db:5432/{db_name}
"""
        env_file.write_text(content, encoding="utf-8")
        print(f"  ✓ backend/.env згенеровано для docker-compose")


def audit_golden_invariants():
    print("\n🛡️ ПЕРЕВІРКА ЗОЛОТИХ ІНВАРІАНТІВ (GOLDEN INVARIANTS)...")

    # 1. ApiService.kt @JvmSuppressWildcards
    api_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/data/remote/ApiService.kt"
    api_code = api_path.read_text(encoding="utf-8")
    assert "@JvmSuppressWildcards" in api_code, "Помилка: відсутній @JvmSuppressWildcards в ApiService.kt!"
    print("  [PASS] @JvmSuppressWildcards присутній в ApiService.kt")

    # 2. Room database version == 7
    db_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/data/local/AppDatabase.kt"
    db_code = db_path.read_text(encoding="utf-8")
    assert "version = 7" in db_code, "Помилка: Room database версія повинна бути 7!"
    print("  [PASS] AppDatabase.kt має version = 7")

    # 3. Notification icons in services
    helper_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/ui/notifications/BookingNotificationHelper.kt"
    assert "stat_notify_chat" in helper_path.read_text(encoding="utf-8"), "Помилка: іконку сповіщень змінено в BookingNotificationHelper.kt!"

    svc_path = ROOT_DIR / "android-app/app/src/main/java/com/mastercrm/data/service/BookingSyncService.kt"
    assert "stat_notify_sync" in svc_path.read_text(encoding="utf-8"), "Помилка: іконку синхронізації змінено в BookingSyncService.kt!"
    print("  [PASS] Системні векторні іконки сповіщень збережено (Android 12-15 сумісність)")

    # 4. legacy_router.py scoping check (no local import json)
    lr_path = ROOT_DIR / "backend/app/api/legacy_router.py"
    lr_code = lr_path.read_text(encoding="utf-8")
    assert "            import json" not in lr_code, "Помилка: виявлено тіньовий локальний 'import json' у legacy_router.py!"
    print("  [PASS] legacy_router.py чистий від тіньових локальних імпортів json")

    # 5. Idempotent stock reconciliation
    assert "old_mov_stmt = select(StockMovement)" in lr_code, "Помилка: ідемпотентне списання матеріалів порушено!"
    print("  [PASS] Ідемпотентне списання матеріалів зі складу (BOM) збережено")

    # 6. Python bytecode compilation check
    for py_file in ["backend/app/config.py", "backend/app/main.py", "backend/app/api/legacy_router.py"]:
        py_compile.compile(str(ROOT_DIR / py_file), doraise=True)
    print("  [PASS] Синтаксис Python валідний (py_compile без помилок)")

    print("🎉 ВСІ ЗОЛОТІ ІНВАРІАНТИ ПІДТВЕРДЖЕНО! РЕГРЕСІЙ НЕМАЄ.\n")


def interactive_mode() -> dict:
    print("Будь ласка, введіть параметри нової студії:")
    studio_name = input("1. Назва студії (наприклад, 'Alina Nails Studio'): ").strip() or "Alina Nails Studio"
    bot_token = input("2. Telegram Bot Token: ").strip()
    bot_username = input("3. Telegram Bot Username (без @): ").strip().replace("@", "") or f"{slugify(studio_name)}_bot"
    master_chat_id = input("4. Telegram Chat ID власниці (число): ").strip()
    backend_url = input("5. Публічний HTTPS URL бекенду (наприклад, 'https://alina-crm.onrender.com/'): ").strip() or f"https://{slugify(studio_name)}.onrender.com/"
    icon_path = input("6. Шлях до PNG логотипу 1024x1024 (або Enter для пропуску): ").strip()
    hosting_method = input("7. Метод хостингу (RENDER / VPS_DOCKER / DOCKER_CLOUD) [RENDER]: ").strip().upper() or "RENDER"

    owner_name = input("8. Ім'я власниці [Анастасія (Власниця)]: ").strip() or "Анастасія (Власниця)"
    owner_phone = input("9. Контактний телефон [+380991234567]: ").strip() or "+380991234567"
    studio_address = input("10. Адреса студії [м. Київ, вул. Хрещатик, 1]: ").strip() or "м. Київ, вул. Хрещатик, 1, оф. 10"
    studio_instagram = input("11. Instagram [@studio_beauty]: ").strip() or f"@{slugify(studio_name)}"
    monobank_jar = input("12. Посилання на банку Monobank [https://send.monobank.ua/jar/123456]: ").strip() or "https://send.monobank.ua/jar/123456"
    card_details = input("13. Реквізити картки [5375 4141 1234 5678]: ").strip() or "5375 4141 1234 5678"
    github_repo = input("14. GitHub репозиторій для релізу APK [dimonenin22-tech/mastercrm]: ").strip() or "dimonenin22-tech/mastercrm"
    apk_filename = f"{slugify(studio_name)}.apk"

    return {
        "studio_name": studio_name,
        "bot_token": bot_token,
        "bot_username": bot_username,
        "master_telegram_id": master_chat_id,
        "backend_public_url": backend_url,
        "app_icon_path": icon_path,
        "hosting_method": hosting_method,
        "owner_name": owner_name,
        "owner_phone": owner_phone,
        "studio_address": studio_address,
        "studio_instagram": studio_instagram,
        "monobank_jar": monobank_jar,
        "card_details": card_details,
        "github_repo": github_repo,
        "apk_filename": apk_filename,
    }


def main():
    print_banner()
    parser = argparse.ArgumentParser(description="White-Label Rebranding Tool for JuNailCRM v2.4.7")
    parser.add_argument("--interactive", action="store_true", help="Запустити в інтерактивному покроковому режимі")
    parser.add_argument("--config", help="Шлях до JSON-файлу з параметрами студії")
    parser.add_argument("--studio-name", help="Назва студії")
    parser.add_argument("--bot-token", help="Telegram Bot Token")
    parser.add_argument("--bot-username", help="Telegram Bot Username")
    parser.add_argument("--master-chat-id", help="Telegram Chat ID майстра")
    parser.add_argument("--backend-url", help="Публічний URL бекенду")
    parser.add_argument("--icon", help="Шлях до PNG логотипу 1024x1024")
    parser.add_argument("--hosting-method", default="RENDER", choices=["RENDER", "VPS_DOCKER", "DOCKER_CLOUD"])
    parser.add_argument("--owner-name", default="Анастасія (Власниця)")
    parser.add_argument("--owner-phone", default="+380991234567")
    parser.add_argument("--studio-address", default="м. Київ, вул. Хрещатик, 1, оф. 10")
    parser.add_argument("--studio-instagram", default="@master_crm_beauty")
    parser.add_argument("--monobank-jar", default="https://send.monobank.ua/jar/123456")
    parser.add_argument("--card-details", default="5375 4141 1234 5678 (Анастасія В.)")
    parser.add_argument("--github-repo", default="dimonenin22-tech/mastercrm")
    parser.add_argument("--apk-filename", default="JuNailCRM.apk")

    args = parser.parse_args()

    if args.interactive:
        params = interactive_mode()
    elif args.config:
        params = json.loads(Path(args.config).read_text(encoding="utf-8"))
    elif args.studio_name and args.backend_url:
        params = {
            "studio_name": args.studio_name,
            "bot_token": args.bot_token or "",
            "bot_username": (args.bot_username or slugify(args.studio_name)).replace("@", ""),
            "master_telegram_id": args.master_chat_id or "",
            "backend_public_url": args.backend_url,
            "app_icon_path": args.icon or "",
            "hosting_method": args.hosting_method,
            "owner_name": args.owner_name,
            "owner_phone": args.owner_phone,
            "studio_address": args.studio_address,
            "studio_instagram": args.studio_instagram,
            "monobank_jar": args.monobank_jar,
            "card_details": args.card_details,
            "github_repo": args.github_repo,
            "apk_filename": args.apk_filename,
        }
    else:
        print("Використовуйте --interactive або передайте обов'язкові прапорці --studio-name та --backend-url.")
        sys.exit(1)

    create_backup()
    rebrand_backend(params)
    rebrand_android(params)
    if params.get("app_icon_path"):
        process_launcher_icons(params["app_icon_path"])
    setup_hosting(params)
    audit_golden_invariants()

    print("\n✅ УСПІШНО! Проект повністю ребрендовано під клієнта без порушення бізнес-логіки.")


if __name__ == "__main__":
    main()
