# MasterCRM v2.0 — Production-Ready Ecosystem for Beauty Studios & Nail Masters

MasterCRM v2.0 — це автономна повнофункціональна екосистема для б'юті-майстрів та студій манікюру, розроблена за найвищими стандартами індустрії.

---

## 🏛️ 1. Архітектура та стек технологій

```
MasterCRM v2.0
├── backend/
│   ├── Python 3.11+ / FastAPI
│   ├── SQLAlchemy 2.0 (Async) + PostgreSQL (asyncpg) / SQLite (aiosqlite)
│   ├── aiogram 3.x (Telegram Bot з FSM та інлайн-календарем)
│   ├── APScheduler (фонові регламентні задачі кожні 10 хв)
│   └── Docker & Docker Compose
│
└── android-app/
    ├── Kotlin & Jetpack Compose (Material 3)
    ├── Room Database (Offline-First з чергою OutboxQueue)
    ├── Retrofit2 & OkHttp3 (єдиний JSON Envelope API)
    ├── EncryptedSharedPreferences (AES256_GCM захист токенів)
    └── 1080x1920 Canvas PNG генератор сторіс + FileProvider
```

---

## 🚀 2. Швидкий запуск Backend

### Варіант А: Запуск через Docker Compose (Рекомендовано для продакшену)
```bash
cd backend
docker-compose up --build -d
```
API буде доступне за адресою: `http://localhost:8000`  
Документація Swagger: `http://localhost:8000/docs`

### Варіант Б: Локальний запуск у віртуальному середовищі
```bash
cd backend
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
source .venv/bin/activate

pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 📱 3. Telegram Bot (aiogram 3.x)

1. Отримайте токен бота у [@BotFather](https://t.me/BotFather).
2. Додайте токен у файл `.env`:
   ```env
   BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
   ```
3. При запуску бекенду бот запускається автоматично.

### Команди та потоки:
- **Для клієнта:**
  - `/start` — Головне меню з 5 кнопками (💅 Онлайн-запис, 📅 Мої записи, 📋 Прайс, 👩🎨 Про студію, 📝 Лист очікування).
  - Інтерактивний інлайн-календар з блокуванням минулих дат.
  - Покроковий майстер бронювання (FSM).
  - Сплата завдатку на карту або Monobank банку з надсиланням фото чека.
  - Самовидалення / скасування запису (`c_can:<id>`) зі сповіщенням майстра та черги очікування.
  - Оцінка візиту від 1 до 5 зірок з текстовим коментарем.
- **Для майстра / власниці:**
  - `/login` — генерація 6-значного OTP-коду (діє 15 хв) для входу в Android-додаток.
  - `/today` — розклад на сьогодні за часовим поясом `Europe/Kyiv` з попередженням `⚠️ Пора повного зняття покриття!`.
  - `/requests` — підтвердження або відхилення нових записів.
  - `/admin` або `/settings` — зміна режимів SOLO/STUDIO, налаштування завдатку, реквізитів та адреси.

---

## ⏰ 4. Фонові регламентні процеси (APScheduler)

Кожні 10 хвилин плагін автоматично виконує:
1. **60-хвилинний таймаут завдатку**: Скасовує непокриті завдатком записи `PENDING`, сповіщає клієнта та автоматично розсилає вільний слот клієнтам у `WaitingList`.
2. **Нагадування за 24г та 2г**: Надвилає сповіщення клієнтам (ідемпотентно через `NotificationLog`). Якщо у клієнта 3+ візити без зняття, додає рекомендацію про зняття матеріалу.
3. **Оцінка після візиту (10 хв)**: Надсилає запит на оцінку 1–5 зірочок через 10 хв після завершення процедури.
4. **14-денний чекап**: Відправляє опитування «Як носиться покриття?» суворо у дозволені години (10:00–20:00 за Києвом).

---

## 📲 5. Android-клієнт (Jetpack Compose + Room)

1. Відкрийте папку `android-app` в **Android Studio**.
2. Додаток підтримує динамічну зміну URL сервера через іконку шестерні на екрані входу (не потрібно перезбирати APK для зміни IP).
3. **Offline-First архітектура**:
   - Будь-яка дія (створення запису, зміна статусу, бриф) миттєво відображається у локальній базі Room і записується в `OutboxQueue`.
   - При появі інтернету черга відправляється на сервер. При критичних конфліктах (409 RESOURCE_BUSY) черга очищується без зациклення і показує Snackbar.
   - Метод `performSync()` виконує Delta-Sync з обробкою Tombstones (`deleted: true`).
4. **Маркетинг у 1 клік**:
   - Генерація історій для Instagram 1080x1920 у форматі PNG на нативному Canvas Android.
   - Безпечний шеринг у будь-які додатки через `FileProvider` з прапорцем `FLAG_GRANT_READ_URI_PERMISSION`.

---

## 📡 6. Приклади запитів до Unified API (`/exec?action=...`)

### Health Check (ping)
```bash
curl -X GET "http://localhost:8000/exec?action=ping"
```

### Обмін OTP на Device Token
```bash
curl -X POST "http://localhost:8000/exec?action=auth_exchange_code" \
     -H "Content-Type: application/json" \
     -d '{"otp_code": "123456", "device_name": "Pixel 8"}'
```

### Отримання вільних слотів (slots_get)
```bash
curl -X POST "http://localhost:8000/exec?action=slots_get" \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer devtok_..." \
     -d '{"service_id": "srv-01", "date": "2026-10-20"}'
```

### Дельта-синхронізація (sync)
```bash
curl -X POST "http://localhost:8000/exec?action=sync" \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer devtok_..." \
     -d '{"since": "2026-10-01T00:00:00Z"}'
```
