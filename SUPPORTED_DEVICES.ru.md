# Поддерживаемые наушники — realness 🎧

[English](SUPPORTED_DEVICES.md) | **Русский**

Приложение поддерживает **14 семейств протоколов и 16 брендов** «из коробки» — все совместимые с Android протоколы из `realme Link`, [QuickBuds](https://github.com/spizganed/QuickBuds) (143 модели), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear) (все 23 модели), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones), [HeadsetControl](https://github.com/Sapd/HeadsetControl) / [HeadsetControl-G733-Tray](https://github.com/Spider19996/HeadsetControl-G733-Tray) и [g435-hub](https://github.com/minchiki/g435-hub). Если ваша модель не определилась автоматически, вы всегда можете выбрать совместимую модель/протокол вручную в боковой панели **«Модель»** (`☰`).

---

## 1. realme Buds и DIZO (`TL + OPPOv1`)
RFCOMM SPP UUID: `0000079A-D102-11E1-9B23-00025B00A5A5`, `00001107-D102-11E1-9B23-00025B00A5A5`, `00001101-0000-1000-8000-00805F9B34FB` (+ резервный BLE GATT).  
*Источники: `realme Link.apkm`, [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Модель | Функции | Статус |
|---|---|---|
| **realme Buds T300** | Заряд L/Кейс/R, ANC, EQ (+ кастомные), жесты, Пространственное аудио + демо, Игровой режим, статистика времени L/R | Подтверждено на устройстве |
| **realme Buds T200** | Все базовые функции + LDAC, Динамическое аудио, Подключение к двум устройствам + история | Подтверждено на устройстве |
| **realme Buds Air 7 Pro** | Все базовые функции + LHDC 5.0, Динамическое аудио, Подключение к двум устройствам + история, Автовоспроизведение/пауза, Автоответ | Подтверждено на устройстве |
| **realme Buds Air 8 Pro / Air 8** | Все базовые функции + LHDC 5.0, Динамическое аудио, Подключение к двум устройствам + история, Автовоспроизведение/пауза, Автоответ | Добавлено `[QuickBuds]` |
| **realme Buds Air 5 / 5 Pro / 6 / 6 Pro / 7 / T310 / T500 / T500 Pro / N1 Pro** | Заряд, ANC, EQ, жесты, Пространственное аудио, Игровой режим, HD-аудио, Динамическое аудио, Dual-Device, Автопауза | Добавлено `[QuickBuds / TL]` |
| **realme Buds T100 / T110 / T200x / T200 Lite / N1 / Buds Clip / Q / Q2 / Q3 / Wireless 2 / 3 / 5 / 6** и **DIZO GoPods / Wireless** | Заряд, ANC (где поддерживается), EQ, жесты, Пространственное аудио, Игровой режим | Добавлено `[QuickBuds / TL]` |

---

## 2. OnePlus Buds (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Источник: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Модели | Функции | Статус |
|---|---|---|
| **OnePlus Buds 3 / 3V / 4 / Pro / Pro 2 / Pro 3 / Pro 4 / Nord Buds 4 Pro** | Заряд L/Кейс/R, ANC, EQ, жесты, Пространственное аудио, Игровой режим, LHDC, Dual-Device Connection, Автопауза | Добавлено `[QuickBuds]` |
| **OnePlus Buds / Ace / Ace 2 / Ace 3 / Clip / Flow / Open / N / V / Z / Z2 / Nord Buds / 2 / 3 / 3 Pro / 4 / Bullets Wireless** | Заряд L/Кейс/R, ANC (где поддерживается), EQ, жесты, Игровой режим, Dual-Device (Nord 3 Pro) | Добавлено `[QuickBuds]` |

---

## 3. OPPO Enco (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Источник: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Модели | Функции | Статус |
|---|---|---|
| **OPPO Enco X / X2 / X3 / X3s / X3i / X4 / Free4** | Заряд L/Кейс/R, ANC, EQ, жесты, Игровой режим, LHDC / LDAC, Dual-Device Connection, Автопауза | Добавлено `[QuickBuds]` |
| **OPPO Enco Air / Air2 / Air3 / Air3 Pro / Air4 / Air4 Pro / Air5 / Air5 Pro / Buds / Buds2 / Buds3 / Buds3 Pro / Pro+ / Clip / Clip2 / Free2 / Free3 / R2..R5 / W11..W51 / M31..M33** | Заряд, ANC (где поддерживается), EQ, жесты, Игровой режим, Dual-Device (Air3..Air5 / Free) | Добавлено `[QuickBuds]` |

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

## 6. Nothing и CMF by Nothing (`Nothing RFCOMM SPP` — все 23 модели)
RFCOMM SPP UUID: `AEAC4A03-DFF5-498F-843A-34487CF133EB` (кадр `0x55` + CRC16/MODBUS).  
*Источники: [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Nothing Ear (1) / (2) / Ear / (a) / (3) / (3a) / Headphone (1) / Headphone (1) Pro / Headphone (a)** | Заряд L/Кейс/R, ANC + уровни (Низкий / Средний / Высокий / Адаптивный), Classic EQ (`0xC01F`/`0xF010`), Low Latency, датчик ношения, Ultra Bass | Добавлено `[swift-nothing-ear / kde-headphones]` |
| **Nothing Ear (stick) / Ear (open) / CMF Clip Pro / CMF Speaker S / M** | Заряд L/Кейс/R, Classic EQ, Low Latency, датчик ношения | Добавлено `[swift-nothing-ear]` |
| **CMF Buds / CMF Buds Neo / Buds 2 / 2a / 2 Plus / Buds Pro / Buds Pro 2 / Neckband Pro / Headphone Pro** | Заряд L/Кейс/R, ANC + уровни, ListeningMode / Classic EQ, Low Latency, датчик ношения, Ultra Bass | Добавлено `[swift-nothing-ear / kde-headphones]` |

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

## 8. Sony (`Sony MDR V1/V2 SPP`)
RFCOMM SPP UUID: `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (V2), `96CC203E-5068-46AD-B32D-E316F5E069BA` (V1) — кадр `0x3E..0x3C`, экранирование `0x3D`, контрольная сумма sum8 и рукопожатие ACK `0x01`.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones) / SonyHeadphonesClient.*

| Модели | Функции | Статус |
|---|---|---|
| **Sony WH-1000XM3..XM6 / WF-1000XM3..XM5 / WH-CH720N / WH-XB910N / WF-C700N / LinkBuds / LinkBuds S / ULT WEAR / INZONE** | Заряд (Single / Dual L+R / Кейс, `0x22`/`0x23`/`0x25`), ANC / Прозрачность (`0x66`..`0x69`), пресеты EQ (`0x56`..`0x59`: Выкл. / Яркий / Голоса / Усиление басов) | Добавлено `[kde-headphones]` |

---

## 9. Bose (`Bose BMAP SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` — кадр `[block, func, op, len, payload]`.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Bose QuietComfort 35 / 45 / QC / QC Ultra / NC 700 / SoundSport** | Заряд (`0x02/0x02`), ANC / Выкл. / Прозрачность (`0x01/0x06`), Мультипоинт (`0x01/0x07`) | Добавлено `[kde-headphones]` |

---

## 10. Anker Soundcore (`Soundcore SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` — кадр `08 EE 00 00 00` (TX) / `09 FF 00 00 01` (RX) + sum8.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Soundcore Liberty 3 Pro / 4 / 4 NC / Space Q45 / A40 / One / Life Q30 / Q35 / P3 / A3i / Sport X** | Заряд (`0x01/0x03`), ANC / Прозрачность / Обычный (`0x06/0x01`, `0x06/0x81`), Игровой режим (`0x01/0x11`) | Добавлено `[kde-headphones]` |

---

## 11. Xiaomi / Redmi / POCO (`Xiaomi SPP + SAFER+ Auth`)
RFCOMM SPP UUID: `0000FD2D-0000-1000-8000-00805F9B34FB` — кадр `FE DC BA .. EF` с полной аутентификацией **SAFER+** challenge-response (`0x50` / `0x51`).  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Xiaomi Buds 3 / 3T Pro / 4 / 4 Pro / 5 / 5 Pro / Redmi Buds 4 / 4 Pro / 5 / 5 Pro / 6 / 6 Pro / POCO Buds** | Заряд L/Кейс/R (`0x09`), ANC + уровни (`0x08`, `0xF2/0x0B`: Низкий / Средний / Высокий / Адаптивный), EQ (`0xF2/0x07`), датчик ношения (`0x0E`), Мультипоинт (`0xF2/0x04`), Автоответ (`0xF2/0x03`) | Добавлено `[kde-headphones]` |

---

## 12. Sennheiser (`Sennheiser GAIA v3 SPP`)
RFCOMM SPP UUID: `A2129FF3-081B-4C45-8AFE-469D9C4842EC` — Qualcomm GAIA v3 (`0xFF 0x03`, vendor `0x0495`).  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Sennheiser MOMENTUM 4 / MOMENTUM True Wireless 3 / 4 / ACCENTUM / ACCENTUM Plus / CX Plus / CX True Wireless** | Заряд (`0x06/0x02`), ANC / Выкл. / Прозрачность (`0x05/0x01..0x04`), Пространственное аудио (`0x06/0x06`) | Добавлено `[kde-headphones]` |

---

## 13. Jabra (`Jabra SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` — кадр `04 09 seq (type|len) feat16 payload`.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Jabra Elite 4 / 5 / 7 Pro / 8 Active / 10 / 75t / 85t / Evolve2** | Заряд (`0x0101`), ANC / Выкл. / HearThrough (`0x0104`), Мультипоинт (`0x0107`) | Добавлено `[kde-headphones]` |

---

## 14. Google Pixel Buds (`Maestro HDLC + Pigweed RPC SPP`)
RFCOMM SPP UUID: `25E97FF7-24CE-4C4C-8951-F764A708F7B5` — HDLC (`0x7E`, экранирование `0x7D`, CRC32) + Pigweed RPC (`maestro_pw.Maestro`) поверх protobuf.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **Google Pixel Buds Pro / Pro 2 / A-Series / 2a** | Заряд L/Кейс/R (`SubscribeBattery`), ANC / Выкл. / Прозрачность (настройка `11`), датчик ношения (настройка `5`), Мультипоинт (настройка `21`) | Добавлено `[kde-headphones]` |

---

## 15. JBL (`JBL BLE GATT`)
BLE GATT Service UUID: `65786365-6c70-6f69-6e74-2e636f6d0000` — кадр `0xAA cmd len payload`.  
*Источник: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Модели | Функции | Статус |
|---|---|---|
| **JBL Tour Pro 2 / 3 / Tour One M2 / Live Pro 2 / Live Beam 3 / Live Flex / Tune Buds / Tune Beam / Wave / Reflect / Club** | Заряд L/Кейс/R (`0x25`), ANC / Выкл. / Ambient Aware (`0x91`), пресеты EQ (`0x40`: Студия / Голоса / Клуб / Басы), Автовоспроизведение/пауза (`0x35`), Пространственное аудио (`0x9D`) | Добавлено `[kde-headphones]` |

---

## 16. Игровые гарнитуры Logitech G (`USB-OTG HID` и Bluetooth)
USB VID `0x046D` (`G733`: PID `0x0AB5`, `0x0AFE`, `0x0B1F`; `G435`: PID `0x0ACB` vendor FFC0 Report 6 + Bluetooth HFP/BLE BAS `0x180F`).  
*Источники: [HeadsetControl](https://github.com/Sapd/HeadsetControl), [HeadsetControl-G733-Tray](https://github.com/Spider19996/HeadsetControl-G733-Tray), [g435-hub](https://github.com/minchiki/g435-hub).*

| Модель | Функции | Статус |
|---|---|---|
| **Logitech G733 LIGHTSPEED** | USB-OTG HID++ 2.0: напряжение + калиброванный % заряда + прогноз часов, Sidetone (`0–100%`), RGB-подсветка (полосы + логотип), автоотключение (`0–90 мин`) | Добавлено `[HeadsetControl]` |
| **Logitech G435 LIGHTSPEED / Bluetooth** | USB-OTG 2.4 ГГц донгл (`046D:0ACB` FFC0-мост, 22-точечная кривая Li-Po, сглаживание, определение зарядки, прогноз часов) + Bluetooth HFP и BLE Battery Service (`0x180F/0x2A19`) | Добавлено `[g435-hub]` |

---

### Техническое примечание: Apple AirPods / Beats
В `kde-headphones` протокол Apple AAP работает через сырой сокет **Bluetooth Classic BR/EDR L2CAP** (`AF_BLUETOOTH`, `BTPROTO_L2CAP`, `PSM 0x1001`). Публичный Android SDK разрешает `createL2capChannel()` только для **BLE CoC** и блокирует открытие классических BR/EDR L2CAP PSM без root/системных привилегий, поэтому AirPods/Beats на нерутованном Android не поддерживаются.

---

> **Хотите помочь добавить или проверить модель?** Откройте Issue / PR с точным Bluetooth-именем наушников и скопированным протокольным логом из приложения!
