# realness — Руководство разработчика и справочник протоколов

[English](AGENTS.md) | **Русский**

Дата обновления: 2026-11-24  
Актуальный релиз: `v1.9.1 (build 11)` — **14 семейств протоколов и 16 брендов** (`realness` в нативном Material 3 Expressive + виджеты Material You + `realness-tiny` в классическом компактном дизайне). Полный список моделей: [SUPPORTED_DEVICES.ru.md](SUPPORTED_DEVICES.ru.md).

---

## 1. Правила релизов, версионирования и разработки

### 1.1. Политика версионирования
- **Функциональные / UI / протокольные релизы:** формат `1.x` (например, `1.8`, `1.9`).
- **Небольшие исправления / хотфиксы** (например, фикс выбора пресета эквалайзера, опечатка в байте протокола, мелкий фикс UI): формат `1.x.y` (например, `1.8.1`, `1.8.2`, `1.9.1`).
- **`versionCode`:** ВСЕГДА монотонно увеличивается на `+1` при каждом релизе (`9 -> 10 -> 11`), в том числе для патчей `1.x.y`.
- **Синхронизация обеих сборок:** `versionName` и `versionCode` всегда одинаковы в:
  - `realness/app/build.gradle.kts`
  - `tiny-realness/AndroidManifest.xml`
- **Имена APK и GitHub Releases:** артефакты всегда называются `realness <versionName>.apk` и `realness-tiny <versionName>.apk`. Workflow `.github/workflows/build-release.yml` читает `versionName` из `realness/app/build.gradle.kts` и публикует релиз с тегом `v<versionName>` (одинаково работает для `1.x` и `1.x.y`).
- **Коммиты только в документацию** (только `*.md`) НЕ требуют поднятия `versionName` / `versionCode` и не запускают `build-release.yml`.

### 1.2. Сборка, подпись и совместимость
- Всегда собирать **release**-сборки:
  - `cd realness && ./gradlew assembleRelease` (R8 minify + shrinkResources + `lintVitalRelease`)
  - `./tiny-realness/build.sh` (чистый Java 8: `aapt2 -> javac -> R8 -> zipalign -> apksigner`)
- Сохранять совместимость `compileSdk = 34` / `targetSdk = 34` с GitHub Actions (`platforms;android-34`, `build-tools;34.0.0`, `material3:1.3.1`).
- **Никогда не коммитить** `.apk`, `realness-release.keystore` и `keystore.properties` (все в `.gitignore`). В CI ключ декодируется из GitHub Secrets (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`).
- Проверять локально собранные APK через `apksigner verify --print-certs`.

### 1.3. Правила UI и локализации
- **`realness` (`uwu.realness.gitveu`):** нативный **Material 3 Expressive** (`ExpressiveTheme.kt`, `ExpressiveSheets.kt`, `MainActivity.kt`):
  - `enableEdgeToEdge()`, динамические темы Material You на Android 12+ (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) с тёплой тональной fallback-палитрой для Android 8–11.
  - Использовать тональные токены `MaterialTheme.colorScheme.*` (`surfaceContainerLowest..Highest`, `primaryContainer`, `secondaryContainer`), без хардкодных тёмных hex-цветов.
  - Expressive-скругления (`24.dp`/`28.dp` карточки, `CircleShape` кнопки), `SingleChoiceSegmentedButtonRow`, иконки `Icons.Rounded.*`, без эмодзи в UI, табличные цифры (`fontFeatureSettings = "tnum"`) для заряда, дБ, громкости и времени использования, сетка отступов кратна `4.dp`.
- **`realness-tiny` (`uwu.realness.gitveu.tiny`):** сохранять классический компактный тёмный UI (~65 КБ, без AndroidX/Kotlin).
- **Паритет локализации:** `realness/app/src/main/res/values/strings.xml` (EN по умолчанию) и `values-ru/strings.xml` (RU) должны иметь полностью совпадающий набор ключей (`diff -u`). Технические протокольные логи выводятся на английском.

### 1.4. Дисциплина Git и авторства
- Индексировать файлы **явно** по путям (`git add <files>`) — никогда не использовать `git add -A` или `git add .`.
- Проверять `git diff --cached --name-only` и отсутствие секретов, локальных путей или бинарных артефактов перед коммитом.
- Сохранять автора репозитория `Gitveu <Gitveu@users.noreply.github.com>`. Не добавлять `Co-Authored-By`, ИИ-метки и эмодзи в сообщения коммитов.
- После коммита проверять `git show -s --format=fuller HEAD` и `git log -1 --format=%B`.
- Удалённый репозиторий: `https://github.com/Gitveu/Realess.git` (ветка `main`).

---

## 2. Архитектура и карта исходников

### 2.1. `realness/` (Kotlin + Jetpack Compose)
- `protocol/`:
  - `T300Protocol.kt`: кадры TL+OPPOv1 для realme / OnePlus / OPPO / DIZO, перечисление `BudsModel` (15 брендов, флаги возможностей, `customEqSpec()`, `supportsGameMode`, `supportsHardwareFindBuds`, `supportsFirmwareRename`), `T300State`, `CustomEqProfile`, `CustomEqSpec`.
  - `HuaweiProtocol.kt`, `NothingProtocol.kt`, `GalaxyProtocol.kt`, `SonyProtocol.kt`, `BoseSoundcoreProtocol.kt`, `XiaomiProtocol.kt`, `ExtraProtocols.kt` (Sennheiser, Jabra, Pixel Buds, JBL).
- `ble/`:
  - `T300GattConfig.kt`: все вендорные и стандартные RFCOMM SPP UUID и BLE GATT UUID.
  - `T300BleManager.kt`: менеджер RFCOMM SPP и BLE GATT с защитой от гонок через `sessionGen` (`isConnectedOrConnectingTo`), потоковые парсеры всех 13 протоколов, опрос, асинхронные события, Custom EQ, аппаратный поиск наушников, переименование Bose и `unexpectedDisconnectFlow` для автовосстановления.
- `audio/`:
  - `FindBudsPlayer.kt`: универсальный нарастающий стерео-сигнал поиска через `AudioTrack` (`FindBudsChannel.LEFT / BOTH / RIGHT`, тройной тональный импульс `2950 Hz + 3450 Hz`, плавное нарастание громкости `15% -> 100%` за 20 с, сохранение и восстановление громкости `STREAM_MUSIC`).
- `viewmodel/BudsViewModel.kt`: реактивный `StateFlow`, автоподключение по A2DP/ACL, backoff-переподключение при обрывах SPP, хранение последнего устройства / ручной модели / пользовательского имени по MAC в `SharedPreferences`, `MediaPlayer` для демо Spatial Audio, контроллер `FindBudsPlayer` и трёхуровневое переименование (`прошивка Bose -> BluetoothDevice.setAlias() -> локальный алиас по MAC`).
- `ui/`:
  - `ExpressiveTheme.kt`: `RealnessExpressiveTheme` (динамическая тема Material You + тональный fallback).
  - `ExpressiveSheets.kt`: `CustomEqEditorSheet` (интерактивная кривая Безье в `ModalBottomSheet`), `FindBudsSheet` (`ModalBottomSheet`), `RenameDeviceDialog` (`AlertDialog`).
  - `MainActivity.kt`: боковая панель выбора модели (`ModalNavigationDrawer`), карточка устройства с быстрыми действиями Поиск / Переименовать, карточки батареи, ANC, EQ и пользовательские профили, жесты, Spatial Audio, модельные переключатели, время работы, консоль логов.

### 2.2. `tiny-realness/` (чистый Java 8)
- `Protocol.java`, `HuaweiProto.java`, `NothingProto.java`, `GalaxyProto.java`, `ExtraProtos.java`: компактные реализации всех 13 семейств протоколов.
- `BudsLink.java`: менеджер RFCOMM SPP и BLE GATT с защитой `sessionGen`.
- `FindTone.java`: компактный нарастающий стерео-сигнал поиска на `AudioTrack`.
- `MainActivity.java`: классический программный тёмный UI без зависимостей с диалогами выбора модели, поиска наушников, переименования и Custom EQ на `SeekBar`.

---

## 3. Универсальные функции

### 3.1. Поиск наушников
- **Звуковой сигнал (ВСЕ наушники):** синтезируемый стерео-сигнал через `AudioTrack` (`USAGE_MEDIA`), плавно повышающий громкость `STREAM_MUSIC` с 15% до 100% за 20 с с панорамированием Левый / Оба / Правый и возвратом исходной громкости при остановке.
- **Опциональный встроенный аппаратный сигнал прошивки:**
  - realme / OnePlus / OPPO: `0x0400`, payload `[01]` (вкл) / `[00]` (выкл)
  - Nothing / CMF: `0xF002`, payload `[sideByte (0x01=L, 0x03=R, 0x06=Both), 01/00]`
  - Samsung Galaxy Buds: `0xA0` (старт) / `0xA1` (стоп), пустой payload

### 3.2. Переименование наушников
1. **Прошивка** (если поддерживается и подключено): Bose BMAP `Block=1, Func=2, Op=SET_GET`, UTF-8 до 31 байта.
2. **Системный алиас Android:** прямой вызов `BluetoothDevice.setAlias(String)` на API 31+ (reflection на API 26–30). Когда Android 12+ требует CDM-ассоциацию, запускается `CompanionDeviceManager.associate()`, после подтверждения сразу выполняется `device.setAlias()` — имя реально обновляется в настройках Bluetooth Android. Пустая строка вызывает `setAlias(null)`.
3. **Локальный алиас и виджеты:** сохранение по MAC в `SharedPreferences` (`custom_alias_<MAC>` / `alias_<MAC>`).

### 3.3. Виджеты Material You и последний заряд кейса
- `RealnessApp` держит общие `T300BleManager` и `LogitechUsbManager`; `WidgetStateStore` сохраняет снимок заряда/ANC и per-MAC **последний заряд кейса + время** при каждом валидном `caseBattery.level in 0..100`.
- `RealnessWidgetProvider` (4×2 Material You), `RealnessCompactWidgetProvider` (4×1, сжимается до 2×1), `RealnessSquareWidgetProvider` (2×2 квадратный) и `RealnessVerticalWidgetProvider` (1×2 вертикальный) показывают L / Кейс (с временем «когда» при закрытом кейсе) / R и переключают ANC напрямую или через быстрое фоновое подключение (`goAsync()`).

---

## 4. Семейство 1: realme / OnePlus / OPPO / DIZO (`TL + OPPOv1`)

По анализу `realme Link.apkm` (`com.realme.iot.headset.tl.*`) и [QuickBuds](https://github.com/spizganed/QuickBuds).

### 4.1. Транспорт и кадрирование
- **RFCOMM SPP UUID:**
  - realme основной: `0000079A-D102-11E1-9B23-00025B00A5A5`
  - OnePlus / OPPO основной: `00001107-D102-11E1-9B23-00025B00A5A5`
  - Стандартный SPP (fallback): `00001101-0000-1000-8000-00805F9B34FB`
  - Резервные вендорные: `db764ac8-4b08-7f25-aafe-59d03c27bae4`, `db764ac8-4b08-7f25-aafe-59d03c27bae3`
- **BLE GATT Fallback:** автодетект характеристик записи и уведомлений (`CCCD 00002902-0000-1000-8000-00805f9b34fb`).
- **Внешний кадр OPPOv1:** `[0xAA, lenVarint (TL_LEN + 2), flags (0x00), reserved (0x00), innerTL...]`
- **Внутренний кадр TL:** `[cmdLo, cmdHi, devAddr (0x00), payloadLenLo, payloadLenHi, payload...]`

### 4.2. Таблица команд

| Функция | TX Cmd | TX Payload | RX Cmd | RX Payload |
|---|:---:|---|:---:|---|
| Запрос батареи | `0x0106` | *пусто* | `0x8106` | `[00, count, (slot, level\|chgBit)...]` (`03 00` = закрытый кейс -> `-1`) |
| Запрос ANC | `0x010C` | `01 01` | `0x810C` | `[00, 01, 01, mode]` (`08`=ANC, `01`=Выкл., `02`=Прозрачность) |
| Установка ANC | `0x0404` | `01 01 <mode>` | `0x8404` | `[00]` |
| Запрос SwitchFeature | `0x010D` | `01 <featId>` | `0x810D` | `[00, count, (featId, 01/00)...]` |
| Установка SwitchFeature | `0x0403` | `<featId> <01/00>` | `0x8403` | `[00]` |
| Запрос пресета EQ | `0x010F` | *пусто* | `0x810F` | `[00, eqId]` (`02`=Серенада, `00`=Оригинал, `03`=Чистые басы, `01`=Глубокие басы) |
| Установка пресета EQ | `0x0406` | `<eqId>` | `0x8406` | `[00]` |
| Запрос профилей Custom EQ | `0x0122` | *пусто* | `0x8122` | `[00, count, (selected, tag 2B, id, nameLen, name, bands, [freq u16 LE, gain s8]...)...]` |
| Создание/Сохранение/Удаление Custom EQ | `0x0418` | `<action: 1/2/3> <tag 2B> <id> <nameLen> <name> <bands> [freq u16 LE, gain s8]...` | `0x8418` | `[00]` |
| Поиск наушников (аппаратный) | `0x0400` | `<01/00>` | — | — |
| Запрос кнопок | `0x0108` | `0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00` | `0x8108` | `[00, count, (dev 1=L/2=R, 01, act 2/3/4, func)...]` |
| Настройка кнопки | `0x0401` | `01 <dev> 01 <act> <func>` | `0x8401` | `[00]` |
| Список устройств MultiConnect | `0x0112` | *пусто* | `0x8112` | `[00, count, (6B MAC rev, entryLen, state, flag, nameLen, UTF-8)...]` |
| Время использования L/R | `0x0128` | *пусто* | `0x8128` | `[00, count, (role 1B, totalMinutes u32 LE)...]` |
| Подписка на события | `0x0201` | `<eventType>` (`01,02,03,05,06`) | `0x8201` | `[00]` |
| Асинхронные события | — | — | `0x0204` | `[03, ...]` ANC, `[05, val]` Game Mode, `[06, ...]` MultiConnect |

- **ID `SwitchFeature` (`0x010D` / `0x0403`):** `0x04` Автопауза, `0x06` Игровой режим, `0x08` Автоответ, `0x11` Двойное подключение, `0x18` LDAC / LHDC 5.0, `0x1B` Пространственное аудио, `0x1D` Динамическое аудио.
- **`functionId` жестов:** `0x01` Пауза, `0x06` След. трек, `0x05` Пред. трек, `0x03` Ассистент, `0x08` ANC, `0x0D` Смена устройства, `0x11` Игровой режим, `0x0B` Громче, `0x0C` Тише, `0x00` Нет.
- **Демо Spatial Audio:** локальное воспроизведение `MediaPlayer` встроенных сэмплов `headset_spatial_audio_fix.mp3` (3D) и `headset_spatial_audio_off.m4a` (стерео).

---

## 5. Семейства 2–3: HUAWEI и HONOR (`Huawei SPP` и `HONOR CHOICE SPP`)

По базе [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) и [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds).

- **UUID:** Стандартный SPP `00001101-0000-1000-8000-00805F9B34FB` (канал 1) и Huawei Private `96cc203e-5068-46ad-b32d-e316f5e069ba` (канал 16: FreeBuds 4i / 5i / SE / FreeLace Pro / Honor Earbuds 2).
- **Кадр:** `[0x5A, lenHi, lenLo, 0x00, cmdHi, cmdLo, TLV(type, len, val)..., crc16XmodemHi, crc16XmodemLo]`.
- **Команды:**
  - Батарея: чтение `0x0108`, уведомление `0x0127` (param 2 `[L, R, Case]`, param 3 флаги зарядки, param 1 общий).
  - ANC: `0x2B2A` чтение, `0x2B04` запись (`0=Выкл, 1=ANC, 2=Прозрачность`; уровни `1=Комфорт, 0=Обычный, 2=Ультра, 3=Динам.`), событие `0x2B03`.
  - Низкая задержка: `0x2B6C`; Автопауза: `0x2B11`/`0x2B10`; Приоритет качества звука: `0x2BA3`/`0x2BA2`.
  - EQ и Custom EQ (10 полос, `-6..+6 дБ`): чтение `0x2B4A` (param 2 активный ID, param 8 36-байтные блоки `[id, count, gains(0.1 дБ s8), имя UTF-8]`), запись `0x2B49` (`1=id, 2=bandCount, 3=gains, 4=name, 5=action(1=сохранить, 2=удалить)`).
  - Двойное подключение: `0x2B2F`/`0x2B2E`, перечисление `0x2B31`, событие `0x2B36`.
  - Жесты: двойное `0x0120`/`0x011F`, тройное `0x0126`/`0x0125`, удержание `0x2B17`/`0x2B16`.
- **Вариант HONOR CHOICE** (X8 / X5 / X5 Pro / X3 / X2 / X): тот же кадр `0x5A`, 1-байтная запись ANC `0x2B04` (`[0/1/2]`), запись EQ `0x2701` (`[0=Баланс, 1=Бас, 2=Голос]`).

---

## 6. Семейства 4–5: Nothing / CMF и Samsung Galaxy Buds

### 6.1. Nothing / CMF (`Nothing SPP`)
По базе [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear) и [kde-headphones](https://github.com/RodiAda/kde-headphones).
- **UUID:** `AEAC4A03-DFF5-498F-843A-34487CF133EB` (+ Standard SPP fallback).
- **Кадр:** `[0x55, 0x60, 0x01, cmdLo, cmdHi, lenLo, lenHi, opId, payload..., crc16ModbusLo, crc16ModbusHi]`.
- **Команды:** Handshake `0xC006`/`0x4006`; Батарея `0xC007`/`0x4007`/`0xE001` (`2=L, 3=R, 4=Case, 6=Main`); ANC `0xC01E`/`0x401E`/`0xE003`/`0xF00F` (`1=Выс, 2=Сред, 3=Низк, 4=Адапт, 5=Выкл, 7=Прозрачность`); Classic EQ `0xC01F`/`0xF010`, ListeningMode EQ `0xC050`/`0xF01D`; 3-полосный float Custom EQ `0xC044`/`0x4044`/`0xF041` (`[3, totalGain f32 LE, 3 × (type, gain, freq, Q)]`); In-Ear `0xC00E`/`0xF004`; Низкая задержка `0xC041`/`0xF040`; Ultra Bass `0xC04E`/`0xF051`; Поиск `0xF002`.

### 6.2. Samsung Galaxy Buds (`Galaxy Buds SPP`)
По базе [kde-headphones](https://github.com/RodiAda/kde-headphones).
- **UUID:** Modern `2E73A4AD-332D-41FC-90E2-16BEF06523F2`, Legacy Buds (2019) `00001102-0000-1000-8000-00805F9B34FD`, Buds+ `00001101-0000-1000-8000-00805F9B34FB`.
- **Кадры:** Modern `[0xFD, sizeLo, sizeHi&0x03, msgId, payload..., crc16CcittLo, crc16CcittHi, 0xDD]` и Legacy `[0xFE, 0x00, size, msgId, payload..., crcLo, crcHi, 0xEE]`.
- **Сообщения:** Push `0x61` (`ExtendedStatusUpdated`) и `0x60` (`StatusUpdated`); Handshake `0x88` (`ManagerInfo [01 02 22]`); ANC `0x78` / `0x98` / `0x80`; EQ `0x86`; Игровой режим `0x87`; Бесшовное переключение `0xAF`; Поиск наушников `0xA0` (старт) / `0xA1` (стоп).

---

## 7. Семейство 6: Xiaomi / Redmi / POCO (`Xiaomi SPP + SAFER+`)

По базе [kde-headphones](https://github.com/RodiAda/kde-headphones) и [MiBudsClient](https://github.com/CesurPolat/MiBudsClient).

- **UUID:** `0000FD2D-0000-1000-8000-00805F9B34FB` (+ Standard SPP fallback).
- **Кадр:** `[0xFE, 0xDC, 0xBA, type, opcode, bodyLenHi, bodyLenLo, (status если ответ), seq, payload..., 0xEF]`.
- **Аутентификация SAFER+:** Хост начинает `0x50 [00, 16B rand]`, отвечает на вызов гарнитуры `0x50` через шифр SAFER+ (`0x50` response) и подтверждает `0x51`.
- **Информация устройства и состояния:** `0x03` (`FF FF FF FF`) триплет батареи (`idx 0x07`), `0x07` (`FF FF FF FF`) ANC (`idx 0x09`) и датчик уха (`idx 0x0A`), `0x02` асинхронные статусы.
- **RCSP-конфигурация (`0xF3` Get / `0xF2` Set / `0xF4` Notify):**
  - Payload запроса `0xF3` — ровно **2-байтный big-endian ключ** `[keyHi, keyLo]` (без байта длины).
  - Payload записи `0xF2` — `[2 + valLen, keyHi, keyLo, val...]`.
  - В ответах `0xF3`/`0xF4` поддержаны обычные `[len, keyHi, keyLo, val...]` и расширенные `0xFF` `[0xFF, keyHi, keyLo, lenHi, lenLo, val...]` записи.
  - Ключи: `0x0003` Автоответ, `0x0004` Multipoint, `0x0007` пресет EQ, `0x000B` сила ANC, **`0x002F` Низкая задержка** (Redmi Buds 4 Active и линейка Xiaomi/Redmi), **`0x0037` 10-полосный Custom EQ** (`-10..+10 дБ`, знако-модульное кодирование `g >= 0 ? g : 128 - g`, сброс `[04, 01, 01, 01]`).

---

## 8. Семейства 7–14 (`kde-headphones`, `HeadsetControl`, `g435-hub`)

- **Sony MDR (V1/V2):** UUID `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (V2) / `96CC203E-5068-46AD-B32D-E316F5E069BA` (V1); кадрирование `0x3E..0x3C` с экранированием `0x3D`, sum8, ACK `0x01`; Батарея `0x22`/`0x23`/`0x25`, NCASM `0x66..0x69`, пресеты и 6-полосный Custom EQ `0x56..0x59` (`0xA0`, `gain + 10`).
- **Bose BMAP:** Стандартный SPP `00001101`; `[block, func, op, len, payload]`; Батарея `0x02/0x02`, ANC `0x01/0x05` (CNC) и `0x01/0x06` (ANR), 3-полосный EQ `0x01/0x07`, Multipoint `0x01/0x0A`, Автопауза `0x01/0x18`, Автоответ `0x01/0x1B`, **Переименование в прошивке** `0x01/0x02` (`OP_SET_GET`, UTF-8 <= 31B).
- **Anker Soundcore:** Стандартный SPP `00001101`; `08 EE 00 00 00` TX / `09 FF 00 00 01` RX + sum8; Статус `0x0101`, Батарея `0x0103`/`0x0104`, ANC `0x0601`/`0x0681`, Игровой режим `0x0187` (`0x1085` Liberty), 8-полосный Custom EQ `0x0281` (`[FE FE, 8 × (db*10 + 120)]`), Dual-Conn `0x0B84`, Автопауза `0x0181`.
- **Sennheiser GAIA v3:** UUID `A2129FF3-081B-4C45-8AFE-469D9C4842EC`; `0xFF 0x03`, vendor `0x0495`; Батарея `0x0603`/`0x0602`, ANC `0x1A04`/`0x1A05`, Прозрачность `0x1804`/`0x1805`, 5-полосный Custom EQ `0x1003`/`0x1001`, Spatial `0x3604`/`0x3605`, Автопауза `0x080C`/`0x080D`, Автоответ `0x080A`/`0x080B`.
- **Jabra:** Стандартный SPP `00001101`; `04 09 seq (type|len) feat16 payload`; Батарея `0x1202`, Ambience `0x13BE`, Noise Mode `0x1387`.
- **Google Pixel Buds:** UUID `25E97FF7-24CE-4C4C-8951-F764A708F7B5`; HDLC (`0x7E`, `0x7D`, CRC32) + Pigweed RPC (`maestro_pw.Maestro`); поток батареи + настройки `13` (ANC), `2` (OHD), `11` (Multipoint), `16` (5-полосный float User EQ).
- **JBL BLE GATT:** Сервис `65786365-6c70-6f69-6e74-2e636f6d0000`; `0xAA cmd len payload`; Батарея `0x25`, Шум `0x91`, Статус `0x21`/`0x22`, EQ `0x40`, Автопауза `0x35`, Spatial `0x9D`.
- **Logitech G733 / G435 (`LogitechProtocol` + `LogitechUsbManager`):**
  - **G733 (`046D:0AB5 / 0AFE / 0B1F`, USB-OTG HID++ 2.0):** 20-байтные кадры `[0x11, 0xFF, ...]`; батарея `0x08 0x0A` (BE мВ `resp[4..5]`, зарядка `resp[6]==0x03`, 8-точечная кривая G633/G733), Sidetone `0x07 0x1A <0..100>`, RGB-подсветка `0x04 0x3C` (полосы `0x01` + логотип `0x00`), автоотключение `0x07 0x21 <0..90>`.
  - **G435 (`046D:0ACB`, USB-OTG Vendor FFC0 Report 6 + Bluetooth):** Feature-отчёты `REPORT_ID=6` (`ENTER_BOOTLOADER 01 03` -> `GET_CONNECT 0xE3` -> `GET_MODE 0xE0` / `SET_MODE 0xE1 01` -> 3× `OTA GET_BATTERY 00 31` медиана -> `finally EXIT_BOOTLOADER 08 03`), 22-точечная кривая Li-Po + `G435BatteryEstimator` (сглаживание, определение зарядки, прогноз оставшихся часов). По Bluetooth: HFP `BluetoothDevice.getBatteryLevel()` + BLE Battery Service (`0x180F` / `0x2A19`).
- **Apple AirPods / Beats:** в `kde-headphones` используется сырой BR/EDR L2CAP `PSM 0x1001`, который недоступен в Android SDK без root.
