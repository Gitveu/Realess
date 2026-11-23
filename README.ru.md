# realness

[English](README.md) | **Русский**

Автономное Android-приложение с открытым исходным кодом (`uwu.realness.gitveu`) для прямого управления наушниками **15 брендов (13 семейств протоколов)** по Bluetooth — без фирменных комбайнов, аккаунтов и облака.

- **Актуальный релиз:** `v1.8 (build 9)` — `realness 1.8.apk` и `realness-tiny 1.8.apk`
- **Список моделей:** [SUPPORTED_DEVICES.ru.md](SUPPORTED_DEVICES.ru.md) ([English](SUPPORTED_DEVICES.md))
- **Техническая и протокольная документация:** [AGENTS.ru.md](AGENTS.ru.md) ([English](AGENTS.md))

---

## Две редакции

| Редакция | Пакет | Размер | UI и стек | Примечание |
|---|---|---|---|---|
| **realness** | `uwu.realness.gitveu` | ~1.6 МБ | Kotlin, Jetpack Compose, нативный **Material 3 Expressive** (Material You динамические цвета, интерактивная кривая EQ, демо Spatial Audio) | Основная рекомендуемая сборка |
| **realness-tiny** | `uwu.realness.gitveu.tiny` | ~65 КБ | Чистый Java 8 + Android SDK (без Gradle и AndroidX), классический компактный тёмный UI | Ставится рядом с `realness`, все 13 протоколов |

---

## Поддерживаемые бренды (13 семейств протоколов)

Полная матрица моделей и функций: **[SUPPORTED_DEVICES.ru.md](SUPPORTED_DEVICES.ru.md)**.

- **realme Buds и DIZO** — T300, T200, T310, T500, Air 5 / 6 / 7 / 8 / Air 8 Pro, Buds Clip, Wireless (`TL + OPPOv1`)
- **OnePlus Buds** — Buds / 3 / 3V / 4, Ace, Clip, Flow, N, Pro..Pro 4, Z, Nord Buds..4 Pro (`TL + OPPOv1`)
- **OPPO Enco** — Air..Air5 Pro, Buds..Buds3 Pro+, Clip, Free..Free4, X..X4, R, W, M (`TL + OPPOv1`)
- **HUAWEI** — FreeBuds 3 / 4i / 5i / 6i / Pro..Pro 5 / SE..SE 4 / Studio / FreeClip / FreeLace (`Huawei SPP`)
- **HONOR** — Earbuds 2 / S2, HONOR CHOICE Earbuds X8 / X5 / X5 Pro / X3 / X2 / X (`Huawei SPP` и `HONOR CHOICE SPP`)
- **Nothing и CMF** — все 23 модели: Ear (1)/(2)/(a)/(3)/(3a)/(stick)/(open), Headphone, CMF Buds / Neo / Pro / 2 / Clip Pro (`Nothing SPP`)
- **Samsung Galaxy Buds** — Buds, Buds+, Live, Pro, Buds2, FE, Buds3, Core, Buds4 (`Galaxy Buds SPP`)
- **Sony** — WH-1000XM3..XM6, WF-1000XM3..XM5, LinkBuds, WH-CH, ULT WEAR (`Sony MDR V1/V2 SPP`)
- **Bose** — QuietComfort 35 / 45 / QC / QC Ultra, NC 700, SoundSport (`Bose BMAP SPP`)
- **Anker Soundcore** — Liberty, Space, Life, Sport X (`Soundcore SPP`)
- **Xiaomi / Redmi / POCO** — Xiaomi Buds 3..5 Pro, Redmi Buds 4 Active / 4..6 Pro / 8 Active, POCO Buds (`Xiaomi SPP + SAFER+`)
- **Sennheiser** — MOMENTUM, ACCENTUM, CX True Wireless (`GAIA v3 SPP`)
- **Jabra** — Elite 4..10 / 75t / 85t, Evolve2 (`Jabra SPP`)
- **Google Pixel Buds** — Pro, Pro 2, A-Series, 2a (`Maestro HDLC + Pigweed RPC`)
- **JBL** — Tour, Live, Tune, Wave, Reflect, Club (`JBL BLE GATT`)

В боковой панели **«Модель»** можно оставить **Автоматически** или вручную выбрать любую модель/протокол. При отключении приложение запоминает и показывает последнее подключённое устройство.

---

## Возможности

- **Автоподключение Zero-Touch:** Приложение слушает системные события Bluetooth (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, A2DP) и автоматически открывает канал SPP/GATT, как только наушники подключены к Android. Есть автоматический переподбор с backoff при кратковременных обрывах RFCOMM.
- **Уровень заряда (Левый / Кейс / Правый):** Точные проценты с индикацией зарядки и нормализацией закрытого кейса (неактивный кейс показывается как `—`, а не `0%`).
- **Шумоподавление (ANC):** Шумоподавление, Выкл., Прозрачность + уровни интенсивности (Комфорт / Обычный / Ультра / Динамический или Низкий / Средний / Высокий / Адаптивный) на Huawei, Nothing/CMF и Xiaomi.
- **Звуковые эффекты и настраиваемый эквалайзер:**
  - Фирменные пресеты под каждый бренд.
  - Интерактивный редактор кривой EQ с учётом числа полос и диапазона каждого протокола:
    - **realme / OnePlus / OPPO**: 6 полос (`62..16k`, `-6..+6 дБ`), несколько профилей (создание / правка / выбор / удаление).
    - **HUAWEI**: 10 полос (`-6..+6 дБ`), несколько профилей.
    - **Xiaomi / Redmi**: 10 полос (`-10..+10 дБ`, сохранение и сброс через RCSP `0x0037`).
    - **Sony MDR**: 6 полос (`-10..+10 дБ`).
    - **Anker Soundcore**: 8 полос (`-6..+6 дБ`).
    - **Sennheiser GAIA** и **Pixel Buds**: 5 полос (`-6..+6 дБ`).
    - **Nothing / CMF** (`-6..+6 дБ`) и **Bose** (`-10..+10 дБ`): 3 полосы (Бас / Средние / Высокие).
- **Универсальный поиск наушников:** Работает на **любых** Bluetooth-наушниках — синтезируемый нарастающий сигнал (`15% -> 100%` за 20 с) с выбором канала Левый / Оба / Правый и автоматическим возвратом громкости после остановки, плюс встроенный аппаратный сигнал прошивки там, где он поддерживается (realme / OnePlus / OPPO, Nothing / CMF, Galaxy Buds).
- **Переименование наушников:** Отправка имени в прошивку (Bose BMAP), обновление системного имени Android `BluetoothDevice.setAlias()` при наличии прав ОС и сохранение пользовательского имени по MAC внутри `realness`.
- **Низкая задержка / Игровой режим:** Поддерживается на realme / OnePlus / OPPO, Xiaomi / Redmi (включая Redmi Buds 4 Active через `0x002F`), HUAWEI, Nothing / CMF, Samsung Galaxy Buds и Soundcore.
- **Настройка сенсорных кнопок:** Двойное касание, тройное касание и удержание отдельно для левого и правого наушника (realme / OnePlus / OPPO, HUAWEI).
- **Пространственное аудио, HD-кодеки и датчик ношения:** Spatial Audio (с локальным демо realme), LDAC / LHDC 5.0 / приоритет качества звука, Динамическое аудио / Ultra Bass, подключение к двум устройствам + история, автопауза, автоответ и статистика времени использования L/R.
- **Протокольный лог:** Сворачиваемая консоль обмена кадрами в реальном времени с копированием и очисткой.

> **Намеренно не включено:** облачные функции фирменных приложений (MindFlow, проверка слуха, перевод с ИИ, проверка прилегания, поиск телефона) и рискованные операции (OTA-прошивка, заводской сброс).

---

## Структура репозитория

```text
Realess/
├── README.md / README.ru.md                  # Описание проекта
├── SUPPORTED_DEVICES.md / .ru.md             # Полная матрица поддерживаемых моделей
├── AGENTS.md / AGENTS.ru.md                  # Правила разработки и документация протоколов
├── .github/workflows/build-release.yml       # CI-сборка и публикация в GitHub Releases
├── tiny-realness/                            # Ультралёгкая сборка на чистом Java 8 + build.sh
│   └── src/uwu/realness/gitveu/tiny/         # Protocol, BudsLink, FindTone, MainActivity, ExtraProtos
└── realness/                                 # Основное приложение (Jetpack Compose, Material 3 Expressive)
    └── app/src/main/java/uwu/realness/gitveu/
        ├── protocol/                         # 13 семейств протоколов и флаги возможностей BudsModel
        ├── ble/                              # T300BleManager.kt и T300GattConfig.kt
        ├── audio/                            # FindBudsPlayer.kt (универсальный сигнал поиска)
        ├── viewmodel/                        # BudsViewModel.kt (StateFlow, автоподключение, переименование)
        └── ui/                               # ExpressiveTheme.kt, ExpressiveSheets.kt, MainActivity.kt
```

---

## Установка и сборка

1. Скачайте `realness <версия>.apk` или `realness-tiny <версия>.apk` из [GitHub Releases](https://github.com/Gitveu/Realess/releases/latest) (Android 8.0+ / API 26+).
2. Предоставьте разрешение **«Устройства поблизости»** (`BLUETOOTH_CONNECT`).
3. Подключите наушники по Bluetooth — `realness` определит их и подключится автоматически.

### Версионирование

- **Функциональные релизы:** `1.x` (например, `1.8`, `1.9`).
- **Небольшие исправления / хотфиксы:** `1.x.y` (например, `1.8.1`, `1.8.2`).
- `versionCode` увеличивается на `+1` с каждым релизом.

### Сборка из исходников

Ключ подписи хранится в **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) и никогда не попадает в Git. Для локальной подписанной сборки задайте переменные окружения `REALNESS_KEYSTORE_*` или создайте локальный `keystore.properties` (в `.gitignore`):

```bash
git clone https://github.com/Gitveu/Realess.git
cd Realess

# Основная сборка Material 3 Expressive (Gradle + R8)
cd realness && ./gradlew assembleRelease

# Компактная сборка на чистом Java 8
cd .. && ./tiny-realness/build.sh
```

---

## Источники протоколов

Реверс-инжиниринг и open-source референсы: `realme Link` (`com.realme.iot.headset.tl.*`), [QuickBuds](https://github.com/spizganed/QuickBuds), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones) и [MiBudsClient](https://github.com/CesurPolat/MiBudsClient).
