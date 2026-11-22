# Supported Earbuds — realness 🎧

**English** | [Русский](SUPPORTED_DEVICES.ru.md)

The list of supported devices is actively growing — **new earbud models are added continuously** based on protocol analysis and user feedback. If your model isn't recognized automatically, you can also select a compatible model manually in the **Model** side drawer (`☰`).

---

## 1. realme Buds (`TL + OPPOv1`)
RFCOMM SPP UUIDs: `0000079A-D102-11E1-9B23-00025B00A5A5`, `00001107-D102-11E1-9B23-00025B00A5A5`, `00001101-0000-1000-8000-00805F9B34FB` (+ BLE GATT fallback).  
*Sources: `realme Link.apkm`, [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Model | Features | Status |
|---|---|---|
| **realme Buds T300** | Battery L/Case/R, ANC, EQ (+ custom), Touch gestures, Spatial Audio + demo, Game Mode, L/R usage stats | Verified on device |
| **realme Buds T200** | All core features + LDAC, Dynamic Audio, Dual-Device Connection + history | Verified on device |
| **realme Buds Air 7 Pro** | All core features + LHDC 5.0, Dynamic Audio, Dual-Device Connection + history, Auto Play/Pause, Auto Answer | Verified on device |
| **realme Buds Air 5 / 5 Pro / 6 / 6 Pro / 7 / T310 / T100** & generic **realme Buds** | Battery, ANC (where supported), EQ, Touch gestures, Spatial Audio, Game Mode, model-specific toggles | Added `[QuickBuds / TL]` |

---

## 2. OnePlus Buds (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Source: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Models | Features | Status |
|---|---|---|
| **OnePlus Buds 3 / 3V / 4 / Pro / Pro 2 / Pro 3** | Battery L/Case/R, ANC, EQ, Touch gestures, Spatial Audio, Game Mode, LHDC, Dual-Device Connection, Auto Play/Pause | Added `[QuickBuds]` |
| **OnePlus Buds / Ace / Ace 2 / Ace 3 / Clip / N / Z / Z2 / Nord Buds / 2 / 3 / 3 Pro** | Battery L/Case/R, ANC (where supported), EQ, Touch gestures, Game Mode, Dual-Device (Nord 3 Pro) | Added `[QuickBuds]` |

---

## 3. OPPO Enco (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Source: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Models | Features | Status |
|---|---|---|
| **OPPO Enco X / X2 / X3** | Battery L/Case/R, ANC, EQ, Touch gestures, Game Mode, LHDC / LDAC, Dual-Device Connection, Auto Play/Pause | Added `[QuickBuds]` |
| **OPPO Enco Air / Air2 / Air3 / Air3 Pro / Air4 / Air4 Pro / Buds / Buds2 / Free2 / Free3** | Battery, ANC (where supported), EQ, Touch gestures, Game Mode, Dual-Device (Air3 / Air4) | Added `[QuickBuds]` |

---

## 4. HUAWEI FreeBuds / FreeClip / FreeLace (`Huawei SPP`)
RFCOMM SPP UUIDs: `00001101-0000-1000-8000-00805F9B34FB` & `96cc203e-5068-46ad-b32d-e316f5e069ba` (frame `0x5A` + TLV + CRC16-XMODEM).  
*Source: [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds).*

| Models | Features | Status |
|---|---|---|
| **HUAWEI FreeBuds 5i / 6i / Pro / Pro 2 / Pro 3 / Pro 4 / Pro 5** | Battery L/Case/R, ANC + intensity levels (Comfort / General / Ultra / Dynamic), EQ (+ custom detection), Touch gestures, Auto Play/Pause, Sound quality priority, Low latency, Dual-Device Connection + device list | Added `[OpenFreebuds]` |
| **HUAWEI FreeBuds 4i / SE / SE 2 / SE 4 ANC / 3** | Battery, ANC (where supported, + levels on SE 4), EQ (SE 2 / SE 4), Touch gestures, Auto Play/Pause (4i), Low latency (SE 2 / SE 4) | Added `[OpenFreebuds]` |
| **HUAWEI FreeClip / FreeClip 2 / Studio / FreeLace Pro / FreeLace Pro 2** | Battery, ANC + levels (Studio / FreeLace), EQ, Touch gestures, Auto Play/Pause, Sound quality priority, Low latency, Dual-Device Connection | Added `[OpenFreebuds]` |

---

## 5. HONOR Earbuds & HONOR CHOICE (`Huawei SPP` & `HONOR CHOICE SPP`)
RFCOMM SPP UUIDs: `00001101-0000-1000-8000-00805F9B34FB` & `96cc203e-5068-46ad-b32d-e316f5e069ba`.  
*Sources: [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds).*

| Models | Protocol & Features | Status |
|---|---|---|
| **HONOR Earbuds 2 / 2 SE / 2 Lite / S2** | Huawei SPP: Battery, ANC, Touch gestures, Auto Play/Pause | Added `[OpenFreebuds]` |
| **HONOR CHOICE Earbuds X8** | HONOR CHOICE SPP (`0x5A`, 1-byte `0x2B04` ANC, `0x2701` EQ): Battery L/Case/R, ANC (Noise Cancellation / Off / Transparency), EQ (Balanced / Bass / Voices) | Added `[noctalia/honor-earbuds]` |
| **HONOR CHOICE Earbuds X5 / X5 Pro / X3 / X3 Lite / X2 / X / Moecen** | HONOR CHOICE SPP: Battery L/Case/R, ANC (where supported), EQ (Balanced / Bass / Voices) | Added `[noctalia/honor-earbuds]` |

---

## 6. Nothing & CMF by Nothing (`Nothing RFCOMM SPP`)
RFCOMM SPP UUID: `AEAC4A03-DFF5-498F-843A-34487CF133EB` (frame `0x55` + CRC16/MODBUS).  
*Sources: [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Nothing Ear (1) / (2) / Ear / (a) / (3) / (3a) / Headphone (1) / (a)** | Battery L/Case/R, ANC + levels (Low / Mid / High / Adaptive), Classic EQ (`0xC01F`/`0xF010`), Low Latency, In-Ear Detection, Ultra Bass | Added `[swift-nothing-ear / kde-headphones]` |
| **Nothing Ear (stick) / Ear (open)** | Battery L/Case/R, Classic EQ, Low Latency, In-Ear Detection | Added `[swift-nothing-ear]` |
| **CMF Buds / Buds 2 / 2a / 2 Plus / Buds Pro / Buds Pro 2 / Neckband Pro / Headphone Pro** | Battery L/Case/R, ANC + levels, ListeningMode / Classic EQ, Low Latency, In-Ear Detection, Ultra Bass | Added `[swift-nothing-ear / kde-headphones]` |

---

## 7. Samsung Galaxy Buds (`Galaxy Buds SPP`)
RFCOMM SPP UUIDs: `2E73A4AD-332D-41FC-90E2-16BEF06523F2` (modern), `00001102-0000-1000-8000-00805F9B34FD` (Buds 2019), `00001101-0000-1000-8000-00805F9B34FB` (Buds+) — frame `0xFD..0xDD` / `0xFE..0xEE` + CRC16-CCITT.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Galaxy Buds Pro / Buds2 / Buds2 Pro / Buds FE / Buds3 / Buds3 Pro / Buds3 FE / Buds Core / Buds4 / Buds4 Pro** | Battery L/Case/R, ANC / Off / Ambient (`0x78`), EQ (`0x86`: Normal / Bass Boost / Dynamic / Clear), Seamless Earbud Connection (`0xAF`) | Added `[kde-headphones]` |
| **Galaxy Buds Live** | Battery L/Case/R, ANC (`0x98`), EQ (`0x86`) | Added `[kde-headphones]` |
| **Galaxy Buds (2019) / Galaxy Buds+** | Battery L/Case/R, Ambient Sound (`0x80`), EQ (`0x86`) | Added `[kde-headphones]` |

---

> **Want to add or verify a model?** Open an Issue / PR with the exact Bluetooth name of your earbuds and the copied protocol log from the app!
