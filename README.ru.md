# realness

[English](README.md) | **Русский**

Автономное Android-приложение с открытым исходным кодом (`uwu.realness.gitveu`) для прямого управления наушниками и гарнитурами **16 брендов (14 семейств протоколов)** по Bluetooth и USB-OTG — без фирменных комбайнов, аккаунтов и облака.

- **Актуальный релиз:** `v1.9.4 (build 14)` — `realness 1.9.4.apk` и `realness-tiny 1.9.4.apk`
- **Список моделей:** [SUPPORTED_DEVICES.ru.md](SUPPORTED_DEVICES.ru.md) ([English](SUPPORTED_DEVICES.md))
- **Техническая и протокольная документация:** [AGENTS.ru.md](AGENTS.ru.md) ([English](AGENTS.md))

---

## Две редакции

| Редакция | Пакет | Размер | UI и стек | Примечание |
|---|---|---|---|---|
| **realness** | `uwu.realness.gitveu` | ~1.7 МБ | Kotlin, Jetpack Compose, нативный **Material 3 Expressive** (Material You динамические цвета + виджеты 4×2 / 4×1 / 2×2 / 1×2 / 1×1 с живым Material You превью, интерактивная кривая EQ, Logitech USB-OTG HID, демо Spatial Audio) | Основная рекомендуемая сборка |
| **realness-tiny** | `uwu.realness.gitveu.tiny` | ~69 КБ | Чистый Java 8 + Android SDK (без Gradle и AndroidX), классический компактный тёмный UI | Ставится рядом с `realness`, все 14 протоколов |

---

## Поддерживаемые бренды (14 семейств протоколов)

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
- **Logitech G** — G733 LIGHTSPEED (USB-OTG HID++ 2.0: напряжение/процент заряда, остаток часов, Sidetone, RGB-подсветка, автоотключение) и G435 LIGHTSPEED / Bluetooth (USB-OTG `046D:0ACB` FFC0-мост с 22-точечной Li-Po кривой + определение зарядки + прогноз часов, а также Bluetooth HFP / BLE Battery Service)

В боковой панели **«Модель»** можно оставить **Автоматически** или вручную выбрать любую модель/протокол. При отключении приложение запоминает и показывает последнее подключённое устройство.

---

## Возможности

- **Автоподключение Zero-Touch:** Приложение слушает системные события Bluetooth (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, A2DP) и автоматически открывает канал SPP/GATT, как только наушники подключены к Android. Есть автоматический переподбор с backoff при кратковременных обрывах RFCOMM.
- **Виджеты на рабочий стол (Material You / QuickBuds):** 5 размеров с живым Material You превью (`previewLayout`): полный 4×2 (название, шкалы заряда Левый / Кейс / Правый, последний заряд кейса и «когда» при закрытом кейсе, быстрые кнопки Шумодав / Выкл. / Прозрачность), компактный 4×1 (сжимается до 2×1), квадратный 2×2, вертикальный 1×2 и сверхкомпактный мини 1×1.
- **Уровень заряда (Левый / Кейс / Правый) и последний заряд кейса:** Точные проценты с индикацией зарядки, нормализацией закрытого кейса и автоматическим запоминанием последнего заряда кейса и времени (например, `60% · 5 ч назад`) в приложении и на виджетах.
- **Шумоподавление (ANC) и уровни интенсивности:** Шумоподавление, Выкл., Прозрачность + выбор силы шумодава на realme / OnePlus / OPPO, HUAWEI, Nothing / CMF, Xiaomi / Redmi, Bose CNC, Soundcore и Samsung Galaxy Buds 3 Pro / 4 (Адаптивный).
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
- **Переименование наушников (Android + прошивка):** Отправка имени в прошивку (Bose BMAP), реальное переименование в настройках Bluetooth Android через `BluetoothDevice.setAlias()` с автоматической привязкой `CompanionDeviceManager` на Android 12+, а также сохранение имени в `realness` и на виджетах.
- **Низкая задержка / Игровой режим:** Поддерживается на realme / OnePlus / OPPO, Xiaomi / Redmi (включая Redmi Buds 4 Active через `0x002F`), HUAWEI, Nothing / CMF, Samsung Galaxy Buds и Soundcore.
- **Настройка сенсорных кнопок:** Двойное касание, тройное касание и удержание отдельно для левого и правого наушника (realme / OnePlus / OPPO, HUAWEI, Nothing / CMF).
- **Пространственное аудио, оптимизация баса и датчик ношения:** Многорежимное Spatial Audio (realme + демо, Nothing / CMF Фикс. / Слежение / Кино / Концерт, Soundcore Surround, Sennheiser, JBL), Оптимизация баса с выбором уровня 1–5 на Nothing / CMF, LDAC / LHDC 5.0 / приоритет качества звука, подключение к двум устройствам + история, автопауза, автоответ и статистика времени использования L/R.
- **Поддержка Logitech G733 / G435:** USB-OTG HID++ 2.0 для G733 (напряжение + калиброванный %, ползунок Sidetone `0–100%`, переключатель RGB-подсветки, таймер автоотключения `0–90 мин`) и G435 (USB-OTG `046D:0ACB` FFC0-мост с 22-точечной кривой напряжения, сглаживанием, определением зарядки и прогнозом оставшихся часов + заряд по Bluetooth HFP / BLE BAS).
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

Реверс-инжиниринг и open-source референсы: `realme Link` (`com.realme.iot.headset.tl.*`), [QuickBuds](https://github.com/spizganed/QuickBuds), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones), [MiBudsClient](https://github.com/CesurPolat/MiBudsClient), [HeadsetControl](https://github.com/Sapd/HeadsetControl) / [HeadsetControl-G733-Tray](https://github.com/Spider19996/HeadsetControl-G733-Tray) и [g435-hub](https://github.com/minchiki/g435-hub).
