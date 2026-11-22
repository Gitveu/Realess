# Поддерживаемые наушники — realness 🎧

[English](SUPPORTED_DEVICES.md) | **Русский**

База поддерживаемых устройств активно расширяется — **новые модели добавляются постепенно** по мере анализа протоколов и обратной связи. Если ваша модель не определилась автоматически, вы всегда можете выбрать совместимую модель вручную в боковой панели **«Модель»** (`☰`).

---

## 1. realme Buds (`TL + OPPOv1`)
RFCOMM SPP UUID: `0000079A-D102-11E1-9B23-00025B00A5A5`, `00001107-D102-11E1-9B23-00025B00A5A5`, `00001101-0000-1000-8000-00805F9B34FB` (+ резервный BLE GATT).  
*Источники: `realme Link.apkm`, [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Модель | Функции | Статус |
|---|---|---|
| **realme Buds T300** | Заряд L/Кейс/R, ANC, EQ (+ кастомные), жесты, Пространственное аудио + демо, Игровой режим, статистика времени L/R | Подтверждено на устройстве |
| **realme Buds T200** | Все базовые функции + LDAC, Динамическое аудио, Подключение к двум устройствам + история | Подтверждено на устройстве |
| **realme Buds Air 7 Pro** | Все базовые функции + LHDC 5.0, Динамическое аудио, Подключение к двум устройствам + история, Автовоспроизведение/пауза, Автоответ | Подтверждено на устройстве |
| **realme Buds Air 5 / 5 Pro / 6 / 6 Pro / 7 / T310 / T100** и прочие **realme Buds** | Заряд, ANC (где поддерживается), EQ, жесты, Пространственное аудио, Игровой режим, модельные переключатели | Добавлено `[QuickBuds / TL]` |

---

## 2. OnePlus Buds (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Источник: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Модели | Функции | Статус |
|---|---|---|
| **OnePlus Buds 3 / 3V / 4 / Pro / Pro 2 / Pro 3** | Заряд L/Кейс/R, ANC, EQ, жесты, Пространственное аудио, Игровой режим, LHDC, Dual-Device Connection, Автопауза | Добавлено `[QuickBuds]` |
| **OnePlus Buds / Ace / Ace 2 / Ace 3 / Clip / N / Z / Z2 / Nord Buds / 2 / 3 / 3 Pro** | Заряд L/Кейс/R, ANC (где поддерживается), EQ, жесты, Игровой режим, Dual-Device (Nord 3 Pro) | Добавлено `[QuickBuds]` |

---

## 3. OPPO Enco (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Источник: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Модели | Функции | Статус |
|---|---|---|
| **OPPO Enco X / X2 / X3** | Заряд L/Кейс/R, ANC, EQ, жесты, Игровой режим, LHDC / LDAC, Dual-Device Connection, Автопауза | Добавлено `[QuickBuds]` |
| **OPPO Enco Air / Air2 / Air3 / Air3 Pro / Air4 / Air4 Pro / Buds / Buds2 / Free2 / Free3** | Заряд, ANC (где поддерживается), EQ, жесты, Игровой режим, Dual-Device (Air3 / Air4) | Добавлено `[QuickBuds]` |

---

## 4. HUAWEI FreeBuds / FreeClip / FreeLace (`Huawei SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` и `96cc203e-5068-46ad-b32d-e316f5e069ba` (кадр `0x5A` + TLV + CRC16-XMODEM).  
*Источник: [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds).*

| Модели | Функции | Статус |
|---|---|---|
| **HUAWEI FreeBuds 5i / 6i / Pro / Pro 2 / Pro 3 / Pro 4 / Pro 5** | Заряд L/Кейс/R, ANC + уровни интенсивности (Комфорт / Обычный / Ультра / Динамический), EQ (+ кастомные), жесты, Автопауза, Приоритет качества звука, Low Latency, Dual-Device + список устройств | Добавлено `[OpenFreebuds]` |
| **HUAWEI FreeBuds 4i / SE / SE 2 / SE 4 ANC / 3** | Заряд, ANC (где поддерживается, + уровни на SE 4), EQ (SE 2 / SE 4), жесты, Автопауза (4i), Low Latency (SE 2 / SE 4) | Добавлено `[OpenFreebuds]` |
| **HUAWEI FreeClip / FreeClip 2 / Studio / FreeLace Pro / FreeLace Pro 2** | Заряд, ANC + уровни (Studio / FreeLace), EQ, жесты, Автопауза, Приоритет качества, Low Latency, Dual-Device | Добавлено `[OpenFreebuds]` |

---

## 5. HONOR Earbuds и HONOR CHOICE (`Huawei SPP` и `HONOR CHOICE SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` и `96cc203e-5068-46ad-b32d-e316f5e069ba`.  
*Источники: [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds).*

| Модели | Протокол и функции | Статус |
|---|---|---|
| **HONOR Earbuds 2 / 2 SE / 2 Lite / S2** | Huawei SPP: Заряд, ANC, жесты, Автопауза | Добавлено `[OpenFreebuds]` |
| **HONOR CHOICE Earbuds X8** | HONOR CHOICE SPP (`0x5A`, 1-байтовый `0x2B04` ANC, `0x2701` EQ): Заряд L/Кейс/R, ANC (Шумоподавление / Выкл. / Прозрачность), EQ (Баланс / Басы / Голоса) | Добавлено `[noctalia/honor-earbuds]` |
| **HONOR CHOICE Earbuds X5 / X5 Pro / X3 / X3 Lite / X2 / X / Moecen** | HONOR CHOICE SPP: Заряд L/Кейс/R, ANC (где поддерживается), EQ (Баланс / Басы / Голоса) | Добавлено `[noctalia/honor-earbuds]` |

---

## 6. Nothing и CMF by Nothing (`Nothing RFCOMM SPP`)
RFCOMM SPP UUID: `AEAC4A03-DFF5-498F-843A-34487CF133EB` (кадр `0x55` + CRC16/MODBUS).  
*Источники: [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Nothing Ear (1) / (2) / Ear / (a) / (3) / (3a) / Headphone (1) / (a)** | Заряд L/Кейс/R, ANC + уровни (Низкий / Средний / Высокий / Адаптивный), Classic EQ (`0xC01F`/`0xF010`), Low Latency, датчик ношения, Ultra Bass | Добавлено `[swift-nothing-ear / kde-headphones]` |
| **Nothing Ear (stick) / Ear (open)** | Заряд L/Кейс/R, Classic EQ, Low Latency, датчик ношения | Добавлено `[swift-nothing-ear]` |
| **CMF Buds / Buds 2 / 2a / 2 Plus / Buds Pro / Buds Pro 2 / Neckband Pro / Headphone Pro** | Заряд L/Кейс/R, ANC + уровни, ListeningMode / Classic EQ, Low Latency, датчик ношения, Ultra Bass | Добавлено `[swift-nothing-ear / kde-headphones]` |

---

## 7. Samsung Galaxy Buds (`Galaxy Buds SPP`)
RFCOMM SPP UUID: `2E73A4AD-332D-41FC-90E2-16BEF06523F2` (современные), `00001102-0000-1000-8000-00805F9B34FD` (Buds 2019), `00001101-0000-1000-8000-00805F9B34FB` (Buds+) — кадр `0xFD..0xDD` / `0xFE..0xEE` + CRC16-CCITT.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Galaxy Buds Pro / Buds2 / Buds2 Pro / Buds FE / Buds3 / Buds3 Pro / Buds3 FE / Buds Core / Buds4 / Buds4 Pro** | Заряд L/Кейс/R, ANC / Выкл. / Прозрачность (`0x78`), EQ (`0x86`: Обычный / Басы / Динамичный / Чёткий), Бесшовное переключение (`0xAF`) | Добавлено `[kde-headphones]` |
| **Galaxy Buds Live** | Заряд L/Кейс/R, ANC (`0x98`), EQ (`0x86`) | Добавлено `[kde-headphones]` |
| **Galaxy Buds (2019) / Galaxy Buds+** | Заряд L/Кейс/R, Прозрачность (`0x80`), EQ (`0x86`) | Добавлено `[kde-headphones]` |

---

> **Хотите помочь добавить или проверить модель?** Откройте Issue / PR с точным Bluetooth-именем наушников и скопированным протокольным логом из приложения!
