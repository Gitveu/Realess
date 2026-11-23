# realness 🎧

[English](README.md) | **Русский**

Автономное Android-приложение (**realness**, пакет `uwu.realness.gitveu`, Kotlin + Jetpack Compose) и документация протоколов для прямого управления наушниками **15 брендов (13 семейств протоколов)** — **realme**, **OnePlus**, **OPPO**, **HUAWEI**, **HONOR / HONOR CHOICE**, **Nothing / CMF**, **Samsung Galaxy Buds**, **Sony**, **Bose**, **Soundcore**, **Xiaomi / Redmi**, **Sennheiser**, **Jabra**, **Google Pixel Buds** и **JBL** — без фирменных комбайнов, без аккаунтов и облака, напрямую по Bluetooth.

**Актуальная версия:** `v1.8 (build 9)` — release-сборки (`realness 1.8.apk` с нативным Material 3 Expressive, `realness-tiny 1.8.apk` в классическом компактном дизайне)  
**Источники протоколов:** `realme Link.apkm` (`com.realme.iot.headset.tl.*`), [QuickBuds](https://github.com/spizganed/QuickBuds), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones) и [MiBudsClient](https://github.com/CesurPolat/MiBudsClient).

---

## 📱 Поддерживаемые наушники

Полный список поддерживаемых брендов, моделей, протоколов и функций вынесен в отдельный файл:

👉 **[SUPPORTED_DEVICES.ru.md](SUPPORTED_DEVICES.ru.md)** ([English](SUPPORTED_DEVICES.md))

Краткий обзор брендов (13 семейств протоколов):
- **realme Buds и DIZO** (T300, T200, T310, T500, Air 5 / 6 / 7 / 8 / **Air 8 Pro**, Buds Clip, Wireless) — `TL + OPPOv1`
- **OnePlus Buds** (Buds / 3 / 3V / 4 / Ace / Clip / Flow / N / Pro..Pro 4 / Z / Nord Buds..4 Pro) — `TL + OPPOv1`
- **OPPO Enco** (Air..Air5 Pro / Buds..Buds3 Pro+ / Clip / Free..Free4 / X..X4 / R / W / M) — `TL + OPPOv1`
- **HUAWEI** (FreeBuds 3 / 4i / 5i / 6i / Pro..Pro 5 / SE..SE 4 / Studio / FreeClip / FreeLace) — `Huawei SPP`
- **HONOR** (Earbuds 2 / S2, **HONOR CHOICE Earbuds X8** / X5 / X5 Pro / X3 / X2 / X) — `Huawei SPP` и `HONOR CHOICE SPP`
- **Nothing и CMF** (все 23 модели: Ear (1)/(2)/(a)/(3)/(3a)/(stick)/(open), Headphone, **CMF Buds Neo** / Buds / Pro / 2 / Clip Pro) — `Nothing SPP`
- **Samsung Galaxy Buds** (Buds / Buds+ / Live / Pro / Buds2 / FE / Buds3 / Core / Buds4) — `Galaxy Buds SPP`
- **Sony** (WH-1000XM3..XM6, WF-1000XM3..XM5, LinkBuds, WH-CH, ULT WEAR) — `Sony MDR V1/V2 SPP`
- **Bose** (QuietComfort 35 / 45 / QC / QC Ultra, NC 700, SoundSport) — `Bose BMAP SPP`
- **Anker Soundcore** (Liberty, Space, Life, Sport X) — `Soundcore SPP`
- **Xiaomi / Redmi / POCO** (Xiaomi Buds 3..5 Pro, Redmi Buds 4..6 Pro, POCO Buds) — `Xiaomi SPP (+ SAFER+ Auth)`
- **Sennheiser** (MOMENTUM, ACCENTUM, CX True Wireless) — `Sennheiser GAIA v3 SPP`
- **Jabra** (Elite 4..10 / 75t / 85t, Evolve2) — `Jabra SPP`
- **Google Pixel Buds** (Pro, Pro 2, A-Series, 2a) — `Maestro HDLC + Pigweed RPC SPP`
- **JBL** (Tour, Live, Tune, Wave, Reflect, Club) — `JBL BLE GATT`

В боковой панели **«Модель»** (`☰`) можно оставить **Автоматическое** определение или вручную выбрать любую модель/протокол. Когда наушники не подключены, приложение запоминает и показывает **последнее подключённое устройство**, а не подставляет жёстко T300.

---

## 🚀 Возможности приложения

### Автоподключение Zero-Touch
Никаких диалогов поиска и ручного выбора устройств. Приложение слушает системные события Bluetooth (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, `ACTION_CONNECTION_STATE_CHANGED`, `ACTION_STATE_CHANGED`). Как только наушники подключаются к Android по A2DP, `realness` автоматически открывает канал управления. Когда оба наушника убраны в кейс, статус переходит в `В кейсе / отключено`.

### Заряд батареи (L / Кейс / R)
- Точный процент заряда (`0–100%`) для левого, правого наушника и кейса, индикатор зарядки `⚡`.
- Корректная обработка закрытого кейса: когда кейс закрыт/оффлайн, прошивка присылает `03 00` — парсер нормализует это в `-1`, UI показывает `—`, а неактивный слот не засоряет лог батареи.
- Мгновенная синхронизация по событиям + периодический опрос каждые 2 секунды.

### Шумоподавление (ANC)
Быстрое переключение и чтение трёх режимов: **Шумоподавление**, **Выкл.**, **Прозрачность**, с автоматическим масштабированием шрифта, чтобы названия не переносились.

### Звуковые эффекты и настраиваемый эквалайзер
- Пресеты адаптированы под каждый бренд (Серенада, Оригинальный звук, Чистые басы, Глубокие басы и др.).
- Интерактивный редактор кривой эквалайзера с учётом полос и диапазона каждого протокола: 6 полос (`-6..+6 дБ`, профили создание/сохранение/удаление) на **realme / OnePlus / OPPO**, 10 полос (`-6..+6 дБ`, профили) на **HUAWEI**, 10 полос (`-10..+10 дБ`) на **Xiaomi / Redmi** (`0x0037`), 6 полос (`-10..+10 дБ`) на **Sony**, 8 полос (`-6..+6 дБ`) на **Soundcore**, 5 полос (`-6..+6 дБ`) на **Sennheiser** и **Pixel Buds**, 3 полосы на **Nothing / CMF** и **Bose**.

### Поиск наушников (для всех) и переименование
- **Поиск наушников:** работает на **любых** подключённых наушниках — плавно нарастающий звуковой сигнал (`15% -> 100%` за 20 с) с выбором канала Левый / Оба / Правый и автоматическим возвратом громкости после остановки, плюс встроенный аппаратный сигнал там, где он есть (**realme / OnePlus / OPPO** `0x0400`, **Nothing / CMF** `0xF002`, **Galaxy Buds** `0xA0`/`0xA1`).
- **Переименование наушников:** отправка имени в прошивку (**Bose** BMAP `Block=1, Func=2`), обновление системного имени Android `BluetoothDevice.setAlias()` при наличии прав и сохранение пользовательского имени по MAC в `realness`.

### Низкая задержка / Игровой режим
- Поддерживается на **realme / OnePlus / OPPO** (`0x06`), **Xiaomi / Redmi** (включая Redmi Buds 4 Active через RCSP `0x002F`), **HUAWEI** (`0x2B6C`), **Nothing / CMF** (`0xF040`), **Samsung Galaxy Buds** (`0x87`) и **Anker Soundcore** (`0x0187` / `0x1085`).

### Настройка кнопок (Сенсорные жесты)
Чтение и изменение действий для **двойного касания**, **тройного касания** и **касания с удержанием** отдельно для левого и правого наушника, с фильтрацией допустимых действий для каждого жеста.

### Пространственное аудио (Spatial Audio / 3D Sound)
Управление и чтение состояния режима. Встроено локальное демо-воспроизведение оригинальных аудиосэмплов из realme Link (`headset_spatial_audio_fix.mp3` и `headset_spatial_audio_off.m4a`) через `MediaPlayer` с переключением на лету.

### Звук высокой чёткости (LDAC / LHDC 5.0) и Динамическое аудио
- Переключатель кодека высокого разрешения (`0x18`): **LDAC** на Buds T200, **LHDC 5.0** на Buds Air 7 Pro.
- Переключатель **Динамического аудио** (`0x1D`) для Buds T200 и Buds Air 7 Pro.

### Подключение к двум устройствам (Dual-Device Connection)
- Переключатель режима одновременного подключения к 2 устройствам (`0x11`) для Buds T200 и Buds Air 7 Pro.
- Отображение **истории подключений** и активных устройств (имя, MAC, статус) через `0x0112` / `0x8112` и асинхронные события `0x06`.

### Умные функции датчика ношения (Buds Air 7 Pro)
- **Автовоспроизведение/пауза** (`0x04`) — пауза при снятии наушника и возобновление при надевании.
- **Автоответ** (`0x08`) — автоматический приём вызова при извлечении и надевании наушников.

### Игровой режим (Game Mode)
Переключение и чтение режима минимальной задержки (`0x06`), синхронизация при переключении жестом на наушнике.

### Статистика времени использования (L / R)
Чтение накопленного времени работы левого и правого наушника прямо из памяти гарнитуры (`0x0128` / `0x8128`) — переносится вместе с наушниками, как в realme Link.

### Live-консоль протокола
Сворачиваемая панель логов с входящими/исходящими кадрами в реальном времени, копированием в буфер и очисткой.

> **Что намеренно не включено:** функции, работающие на стороне приложения/облака realme Link (**MindFlow**, **Golden Hearing / Проверка слуха**, **Перевод с ИИ**, **Проверка прилегания**, **Поиск телефона**), а также потенциально опасные операции (OTA-прошивка, сброс до заводских настроек).

---

## 🔬 Транспорт

### Bluetooth Classic RFCOMM SPP (основной)
Наушники сопрягаются как стандартная гарнитура (A2DP / HFP), канал управления — RFCOMM SPP:
- Основной вендорный UUID realme / OPPO / OnePlus: `0000079A-D102-11E1-9B23-00025B00A5A5`
- Стандартный SPP UUID (fallback): `00001101-0000-1000-8000-00805F9B34FB`
- Резервные вендорные UUID: `db764ac8-4b08-7f25-aafe-59d03c27bae4`, `db764ac8-4b08-7f25-aafe-59d03c27bae3`

```kotlin
device.createRfcommSocketToServiceRecord(REALME_VENDOR_SPP_UUID)
```

### BLE GATT (резервный)
При недоступности SPP-сокета выполняется автоматический переход на BLE GATT с автодетектом характеристик записи (`PROPERTY_WRITE` / `PROPERTY_WRITE_NO_RESPONSE`) и уведомлений (`PROPERTY_NOTIFY`, CCCD `00002902-0000-1000-8000-00805f9b34fb`).

---

## 🧩 Формат кадров (Wire Frames)

Двухуровневая инкапсуляция: внутренний TL-кадр оборачивается в транспортный фрейм OPPOv1.

### Транспортное обрамление OPPOv1 (SOF `0xAA`)
| Смещение | Поле |
|---|---|
| `0` | `0xAA` — Start of Frame |
| `1..K` | Длина пакета (varint 7-bit, равна `TL_FRAME_LENGTH + 2`; для пакетов < 128 байт — 1 байт) |
| `K+1` | Флаги (`0x00` — одиночный пакет) |
| `K+2` | Резерв (`0x00`) |
| `K+3..` | Внутренний TL-кадр |

### Внутренний TL-кадр
| Смещение | Поле |
|---|---|
| `0..1` | Command ID (uint16 little-endian) |
| `2` | Device Address (`0x00`) |
| `3..4` | Payload Length (uint16 little-endian) |
| `5..N` | Payload |

---

## 📡 Справочник протокола

Все кадры передаются через RFCOMM SPP в обёртке OPPOv1 (`SOF 0xAA`).

| Функция | TX Cmd | TX Payload | RX Cmd | RX Payload |
|---|:---:|---|:---:|---|
| Запрос батареи | `0x0106` | — | `0x8106` | `[00, count, (slot, level\|flag)...]` |
| Установка ANC | `0x0404` | `01 01 <mode>` | `0x8404` | `[00]` |
| Запрос режима ANC | `0x010C` | `01 01` | `0x810C` | `[00, 01, 01, <mode>]` |
| Запрос SwitchFeature | `0x010D` | `01 <featureId>` | `0x810D` | `[00, count, (featureId, val)...]` |
| Установка SwitchFeature | `0x0403` | `<featureId> <01/00>` | `0x8403` | `[00]` |
| Список устройств MultiConnect | `0x0112` | — | `0x8112` | `[00, count, (MAC 6B, entryLen, state, flag, nameLen, UTF-8)...]` |
| Статистика времени L/R | `0x0128` | — | `0x8128` | `[00, count, (role 1B, minutes 4B LE)...]` |
| Запрос пресета EQ | `0x010F` | — | `0x810F` | `[00, <eqId>]` |
| Установка пресета EQ | `0x0406` | `<eqId>` | `0x8406` | `[00]` |
| Запрос кастомных EQ | `0x0122` | — | `0x8122` | `[00, count, (selected, …, name)...]` |
| Запрос настроек кнопок | `0x0108` | `0E 01 03 04 … 00` | `0x8108` | `[00, count, (dev, 1, act, func)...]` |
| Установка настройки кнопки | `0x0401` | `01 dev 01 act func` | `0x8401` | `[00]` |
| Подписка на события | `0x0201` | `<eventType>` | `0x8201` | `[00]` |
| Асинхронные события | — | — | `0x0204` | `[03, …]`, `[05, val]`, `[06, …]` |

**Идентификаторы `SwitchFeature` (`0x010D` / `0x0403`):**
- `0x04` — Автовоспроизведение/пауза (датчик ношения, Air 7 Pro)
- `0x06` — Игровой режим (Game Mode)
- `0x08` — Автоответ на вызов (Air 7 Pro)
- `0x11` — Подключение к двум устройствам (T200 / Air 7 Pro)
- `0x18` — Звук высокой чёткости: LDAC (T200) / LHDC 5.0 (Air 7 Pro)
- `0x1B` — Пространственное аудио (Spatial Audio / 3D Sound)
- `0x1D` — Динамическое аудио (T200 / Air 7 Pro)

**Ключевые значения:**
- `noiseStatus`: `0x08` ANC / `0x01` Выкл. / `0x02` Прозрачность
- `componentId`: `0x01` левый / `0x02` правый / `0x03` кейс; `value & 0x7F` — уровень, `value & 0x80` — признак зарядки
- `eqId`: `0x02` Серенада / `0x00` Оригинальный / `0x03` Чистые басы / `0x01` Глубокие басы
- `actionId`: `0x02` двойное касание / `0x03` тройное / `0x04` долгое нажатие
- `eventType`: `0x03` ANC / `0x05` Game Mode / `0x01` батарея / `0x02` статус наушников / `0x06` MultiConnect

**Примеры полных OPPOv1-кадров:**

```text
Запрос батареи:      AA 07 00 00 06 01 00 00 00
Статистика L/R:      AA 07 00 00 28 01 00 00 00
ANC ON:              AA 0A 00 00 04 04 00 03 00 01 01 08
ANC OFF:             AA 0A 00 00 04 04 00 03 00 01 01 01
Прозрачность:        AA 0A 00 00 04 04 00 03 00 01 01 02
Game Mode ON:        AA 09 00 00 03 04 00 02 00 06 01
Spatial Audio ON:    AA 09 00 00 03 04 00 02 00 1B 01
LDAC / LHDC 5.0 ON:  AA 09 00 00 03 04 00 02 00 18 01
Auto Play/Pause ON:  AA 09 00 00 03 04 00 02 00 04 01
Auto Answer ON:      AA 09 00 00 03 04 00 02 00 08 01
```

Подробная документация по командам и архитектуре — в [AGENTS.ru.md](AGENTS.ru.md).

---

## 📁 Структура репозитория

```text
Realess/
├── README.md                      # Обзор проекта (English)
├── README.ru.md                   # Обзор проекта (Русский)
├── SUPPORTED_DEVICES.md           # Полный список поддерживаемых моделей (English)
├── SUPPORTED_DEVICES.ru.md        # Полный список поддерживаемых моделей (Русский)
├── AGENTS.md                      # Подробная документация протокола и приложения (English)
├── AGENTS.ru.md                   # Подробная документация протокола и приложения (Русский)
├── .github/workflows/             # GitHub Actions для сборки и публикации подписанных APK в Releases
├── tiny-realness/                 # Исходники и build.sh на чистой Java 8 без Gradle и AndroidX
└── realness/
    ├── app/src/main/java/uwu/realness/gitveu/
    │   ├── protocol/              # 13 протоколов (realme/OPPO, Huawei, Nothing, Galaxy, Sony, Bose, Soundcore, Xiaomi, Sennheiser, Jabra, Pixel Buds, JBL)
    │   ├── ble/                   # T300BleManager.kt — мультипротокольный SPP-сокет, GATT fallback
    │   ├── viewmodel/             # BudsViewModel.kt — StateFlow, автоподключение, память последнего устройства
    │   └── ui/                    # MainActivity.kt — Material3 UI + боковая панель выбора модели
    ├── app/src/main/res/          # values/, values-ru/, xml/locales_config.xml, raw/, иконки
    ├── build.gradle.kts
    └── settings.gradle.kts
```

### Стек и локализация
- **Основная сборка (`realness`):** Kotlin, Jetpack Compose (Material3), Coroutines / Flow, R8 minify + resource shrinking, MinSdk 26 (Android 8.0+), TargetSdk 34.
- **Ультралёгкая сборка (`realness-tiny`):** чистая Java 8 + Android SDK без Gradle и AndroidX (`aapt2 -> javac -> R8 -> zipalign -> apksigner`), пакет `uwu.realness.gitveu.tiny` (ставится рядом с основной версией, весь функционал протокола без 1.5 МБ демо-аудио).
- **Локализация:** английский по умолчанию (`values/strings.xml`), полная русская локализация (`values-ru/strings.xml`), поддержка выбора языка приложения в Android 13+ (`res/xml/locales_config.xml`). Технические логи протокола — на английском.
- **Иконка:** адаптивная (повернутый на 90° символ realme Link с диагональной линией `/` на градиентном фоне `#B662FE` → `#4F7FFF`, monochrome-слой для Android 13+ и legacy mipmap).

---

## 📲 Установка

1. Скачайте `realness <версия>.apk` (основная версия) или `realness-tiny <версия>.apk` (~33 КБ) из [GitHub Releases](https://github.com/Gitveu/realess-t300/releases/latest) и установите на Android 8.0+.
2. Выдайте разрешение «Устройства поблизости» (`BLUETOOTH_CONNECT`).
3. Подключите поддерживаемые наушники realme Buds — приложение подхватит их автоматически.

### Сборка из исходников
Ключ подписи хранится в **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) и **не коммитится в репозиторий**:
- **На GitHub (с любого устройства):** запушьте изменения или запустите workflow `Build Release APKs` во вкладке **Actions** — подписанные `realness 1.x.apk` и `realness-tiny 1.x.apk` автоматически публикуются в [GitHub Releases](https://github.com/Gitveu/realess-t300/releases) и загружаются в артефакты сборки.
- **Локально:** передайте переменные окружения `REALNESS_KEYSTORE_PATH`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD` (или положите локальный `keystore.properties` в корень, он в `.gitignore`):

```bash
git clone https://github.com/Gitveu/realess-t300.git
cd realess-t300

# Основная release-сборка (Gradle + R8):
cd realness && ./gradlew assembleRelease

# Ультралёгкая release-сборка (pure Java, без Gradle):
cd .. && ./tiny-realness/build.sh
```

Репозиторий: [github.com/Gitveu/realess-t300](https://github.com/Gitveu/realess-t300) (ветка `main`).
