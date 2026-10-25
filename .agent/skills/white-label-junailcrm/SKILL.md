---
name: white-label-junailcrm
description: "Generates, rebrands, builds, and deploys a production-ready white-label instance of JuNailCRM v2.4.7 for any beauty or nail salon client using client parameters, with zero regression to business logic. Triggered automatically by the /nailcrm slash command."
---

# White-Label JuNailCRM v2.4.7 Deployment Skill

Цей скіл автоматизує повний цикл ребрендингу, конфігурації та розгортання екосистеми **JuNailCRM v2.4.7** під бренд будь-якої нової студії манікюру або краси.

> **Тригер команди:** активується автоматично при виклику команди `/nailcrm` або при запитах на білу копію (white-label) CRM.

---

## 1. Необхідні вхідні змінні клієнта (Client Variables)

Коли цей скіл активується, запитайте у користувача (або зчитайте з запиту) наступні параметри:

### Обов'язкові змінні:
1. `{{BOT_TOKEN}}` — Telegram Bot Token, виданий `@BotFather`.
2. `{{BOT_USERNAME}}` — Юзернейм бота в Telegram без символу `@` (наприклад, `alina_nails_bot`).
3. `{{MASTER_TELEGRAM_ID}}` — Числовий Telegram Chat ID майстра/власниці (наприклад, `683419385`).
4. `{{STUDIO_NAME}}` — Офіційна назва бренду студії (наприклад, `Alina Nails Studio`).
5. `{{APP_ICON_PATH}}` — Шлях до квадратного PNG логотипу 1024x1024 (або `none` для збереження стандартної іконки).
6. `{{HOSTING_METHOD}}` — Один із 3 методів розгортання:
   - `RENDER` — 1-click cloud deploy через `render.yaml` Blueprint + Render PostgreSQL.
   - `VPS_DOCKER` — автономний Linux VPS через `backend/docker-compose.yml` + `backend/.env`.
   - `DOCKER_CLOUD` — Railway / Fly.io / Koyeb через кореневий `Dockerfile`.
7. `{{BACKEND_PUBLIC_URL}}` — Публічний HTTPS URL бекенду з обов'язковим завершальним слешем `/` (наприклад, `https://alina-crm.onrender.com/`).

### Опціональні змінні (з розумними дефолтами):
- `{{OWNER_NAME}}` — Ім'я власниці (за замовчуванням: `Анастасія (Власниця)`).
- `{{OWNER_PHONE}}` — Контактний номер студії (за замовчуванням: `+380991234567`).
- `{{STUDIO_ADDRESS}}` — Адреса студії (за замовчуванням: `м. Київ, вул. Хрещатик, 1, оф. 10`).
- `{{STUDIO_INSTAGRAM}}` — Instagram акаунт (за замовчуванням: `@master_crm_beauty`).
- `{{MONOBANK_JAR}}` — Посилання на банку Monobank для завдатків (за замовчуванням: `https://send.monobank.ua/jar/123456`).
- `{{CARD_DETAILS}}` — Реквізити картки (за замовчуванням: `5375 4141 1234 5678 (Анастасія В.)`).
- `{{GITHUB_REPO}}` — GitHub репозиторій для збірки APK через Actions (за замовчуванням: `dimonenin22-tech/mastercrm`).
- `{{APK_FILENAME}}` — Назва файлу APK (за замовчуванням: `JuNailCRM.apk`).

---

## 2. Швидкий запуск через скрипт автоматизації

У проекті наявний спеціалізований скрипт [`scripts/rebrand_and_deploy.py`](file:///d:/Vibeapp/scripts/rebrand_and_deploy.py).

### Інтерактивний режим:
```powershell
python scripts/rebrand_and_deploy.py --interactive
```

### Автономний запуск з прапорцями:
```powershell
python scripts/rebrand_and_deploy.py `
  --studio-name "Alina Nails Studio" `
  --bot-token "1234567890:ABC-DEF..." `
  --bot-username "alina_nails_bot" `
  --master-chat-id "683419385" `
  --backend-url "https://alina-crm.onrender.com/" `
  --icon "assets/logo_1024.png" `
  --hosting-method "RENDER"
```

---

## 3. Точна карта ін'єкцій змінних (File Mapping)

Скрипт або агент вносять зміни виключно в ці місця, зберігаючи 100% працездатність бізнес-логіки v2.4.7:

### A. Бекенд та Telegram-бот (`backend/app/...`)
1. **[`backend/app/config.py`](file:///d:/Vibeapp/backend/app/config.py)**:
   - `PROJECT_NAME: str = "{{STUDIO_NAME}}"`
   - `BOT_TOKEN: str = "{{BOT_TOKEN}}"`
   - `MASTER_CHAT_ID: str = "{{MASTER_TELEGRAM_ID}}"`
   - `DEFAULT_OWNER_NAME: str = "{{OWNER_NAME}}"`
   - `DEFAULT_OWNER_PHONE: str = "{{OWNER_PHONE}}"`
2. **[`backend/app/main.py`](file:///d:/Vibeapp/backend/app/main.py)** (`init_and_seed_db`):
   - Оновлює мапу `default_settings`:
     - `"studio_name": ("{{STUDIO_NAME}}", "Назва студії")`
     - `"bot_username": ("{{BOT_USERNAME}}", "Юзернейм Telegram-бота")`
     - `"master_chat_id": ("{{MASTER_TELEGRAM_ID}}", "Telegram ID власника")`
     - `"address": ("{{STUDIO_ADDRESS}}", "Адреса студії")`
     - `"phone": ("{{OWNER_PHONE}}", "Контактний телефон студії")`
     - `"instagram": ("{{STUDIO_INSTAGRAM}}", "Instagram акаунт студії")`
     - `"jar_link": ("{{MONOBANK_JAR}}", "Посилання на банку Monobank")`
     - `"card_details": ("{{CARD_DETAILS}}", "Реквізити банківської картки")`
   - Сід первинного майстра: `Master(id="m-01", name=settings.DEFAULT_OWNER_NAME, phone=settings.DEFAULT_OWNER_PHONE, role="owner", is_active=True)`.
3. **[`backend/app/api/legacy_router.py`](file:///d:/Vibeapp/backend/app/api/legacy_router.py)**:
   - У функції `render_public_landing_page()` оновлює дефолтні фолбеки `studio_name` та `bot_username`.
4. **[`backend/app/bot/handlers_client.py`](file:///d:/Vibeapp/backend/app/bot/handlers_client.py)**:
   - Замінює згадки бренду у вітальних текстах, головному меню, повідомленнях про успішний запис та запитах на відгук.
5. **[`backend/app/bot/handlers_master.py`](file:///d:/Vibeapp/backend/app/bot/handlers_master.py)**:
   - Оновлює константи `APK_DOWNLOAD_URL` та `APK_GITHUB_RELEASE_URL` на відповідний GitHub репозиторій клієнта.

### B. Android-додаток (`android-app/...`)
1. **[`AuthRepository.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/data/repository/AuthRepository.kt)**:
   - Оновлює `const val DEFAULT_URL = "{{BACKEND_PUBLIC_URL}}"` (з обов'язковим слешем `/` на кінці).
2. **[`LoginScreen.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/ui/screens/LoginScreen.kt)**:
   - Заголовок картки авторизації майстра: `Text(text = "{{STUDIO_NAME}}", ...)`.
3. **[`MarketingScreen.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/ui/screens/MarketingScreen.kt)**:
   - Задає значення за замовчуванням `studioName: String = "{{STUDIO_NAME}}"` у функціях `MarketingStudioScreen` та `MarketingScreen()`, щоб усі 5 шаблонів Canva Stories малювали брендовий бейдж клієнта.
4. **[`ClientsScreen.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/ui/screens/ClientsScreen.kt)**:
   - Оновлює шаблони персональних SMS/Viber/WhatsApp нагадувань клієнтам напередодні візиту.
5. **[`SettingsScreen.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/ui/screens/SettingsScreen.kt)**:
   - У вкладці управління командою встановлює `val botUsername = "{{BOT_USERNAME}}"` для формування deep-link посилань майстрам (`https://t.me/$botUsername?start=join_${master.id}`).
6. **[`strings.xml`](file:///d:/Vibeapp/android-app/app/src/main/res/values/strings.xml)**:
   - Оновлює ім'я застосунку в системі: `<string name="app_name">{{STUDIO_NAME}}</string>`.
7. **Редагування контактів студії в додатку ([`SettingsScreen.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/ui/screens/SettingsScreen.kt))**:
   - Власниця студії може в будь-який момент змінити назву, адресу, телефон та Instagram студії у вкладці «Налаштування» ➔ «Студія» в картці «🏢 Профіль та контакти студії». Зміни синхронізуються з Telegram-ботом, публічною сторінкою та нагадуваннями клієнтам без перескладання додатку!
8. **Адаптивні іконки (`{{APP_ICON_PATH}}`)**:
   - Автоматично масштабує і накладає круглу маску для всіх папок:
     - `mipmap-mdpi/ic_launcher.png` (48x48) та `ic_launcher_round.png`
     - `mipmap-hdpi/ic_launcher.png` (72x72) та `ic_launcher_round.png`
     - `mipmap-xhdpi/ic_launcher.png` (96x96) та `ic_launcher_round.png`
     - `mipmap-xxhdpi/ic_launcher.png` (144x144) та `ic_launcher_round.png`
     - `mipmap-xxxhdpi/ic_launcher.png` (192x192) та `ic_launcher_round.png`
     - `drawable/ic_launcher_foreground.png` (432x432)

---

## 4. Сценарії хостингу та розгортання (Hosting Methods)

### Спосіб 1: RENDER (1-Click Blueprint)
1. Скрипт генерує актуальний [`render.yaml`](file:///d:/Vibeapp/render.yaml) з назвами сервісів та бази клієнта.
2. Зробіть push до GitHub:
   ```powershell
   git add .
   git commit -m "feat(whitelabel): configure {{STUDIO_NAME}} instance"
   git push origin master
   ```
3. У панелі Render виберіть **New + -> Blueprint** та вкажіть репозиторій.
4. Render автоматично підніме PostgreSQL та запустить контейнер FastAPI бекенду з ботом.
5. Скопіюйте публічний HTTPS URL (наприклад `https://alina-crm.onrender.com/`) та переконайтеся, що він прописаний у `AuthRepository.kt`.

### Спосіб 2: VPS_DOCKER (Linux VPS через Docker Compose)
1. Скрипт генерує файл `backend/.env`.
2. Скопіюйте папку `backend` на сервер або склонуйте репозиторій.
3. Запустіть контейнери:
   ```bash
   cd backend
   docker compose up -d --build
   ```
4. Налаштуйте Nginx reverse proxy на порт 8000 з безкоштовним SSL-сертифікатом Let's Encrypt Certbot.

### Спосіб 3: DOCKER_CLOUD (Railway / Fly.io / Koyeb)
1. Використовуйте кореневий [`Dockerfile`](file:///d:/Vibeapp/Dockerfile).
2. Задайте змінні оточення в панелі провайдера:
   - `ENVIRONMENT=production`
   - `TIMEZONE=Europe/Kyiv`
   - `BOT_TOKEN={{BOT_TOKEN}}`
   - `MASTER_CHAT_ID={{MASTER_TELEGRAM_ID}}`
   - `DATABASE_URL=postgresql+asyncpg://user:pass@host:5432/dbname`

---

## 5. Збірка Android APK

1. **Автоматично через GitHub Actions**:
   - Файл [`.github/workflows/android-build.yml`](file:///d:/Vibeapp/.github/workflows/android-build.yml) автоматично збирає `JuNailCRM.apk` та публікує реліз при push у `master`.
2. **Локально**:
   ```powershell
   cd android-app
   .\gradlew.bat assembleRelease
   ```
   Зібраний APK знаходитиметься в `android-app/app/build/outputs/apk/release/app-release-unsigned.apk`.

---

## 6. Золоті інваріанти JuNailCRM v2.4.7 (Golden Invariants — Захист від регресій)

Скрипт та агент у жодному разі не повинні порушувати ці робочі механізми:
- **`@JvmSuppressWildcards` в [`ApiService.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/data/remote/ApiService.kt)** — запобігає збоям Retrofit з wildcard-типами (`? extends Object`).
- **Room Database `version = 7` в [`AppDatabase.kt`](file:///d:/Vibeapp/android-app/app/src/main/java/com/mastercrm/data/local/AppDatabase.kt)** — підтримка `consumedMaterialsJson` у `VisitBriefEntity`.
- **Іконки статус-бару в сповіщеннях**:
  - `BookingNotificationHelper.kt`: `.setSmallIcon(android.R.drawable.stat_notify_chat)`.
  - `BookingSyncService.kt`: `.setSmallIcon(android.R.drawable.stat_notify_sync)`.
  > [!WARNING]
  > Не замінюйте їх на звичайні PNG/XML ресурси, оскільки Android 12–15 вимагає системні векторні drawables для статус-бару.
- **Ідемпотентне списання матеріалів зі складу в [`legacy_router.py`](file:///d:/Vibeapp/backend/app/api/legacy_router.py)**: повернення попередньо списаних матеріалів перед фіксацією нових.
- **Відсутність локальних тіньових імпортів**: жодних `import json`, `import csv` всередині тіла `unified_exec` (усі імпорти strictly на рівні модуля).
- **Неперервний фоновий сервіс**: `BookingSyncService` із `START_STICKY`, `PARTIAL_WAKE_LOCK` та відновленням через AlarmManager.
- **Двоетапний збір відгуків у боті**: спершу вибір 1..5 зірочок через inline-кнопки, потім надсилання розгорнутого тексту.
