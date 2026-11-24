# Supported Earbuds — realness 🎧

**English** | [Русский](SUPPORTED_DEVICES.ru.md)

The list of supported devices is actively growing — **14 protocol families across 16 brands** are supported out of the box, covering all Android-compatible protocols from `realme Link`, [QuickBuds](https://github.com/spizganed/QuickBuds) (143 models), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear) (all 23 models), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones), [HeadsetControl](https://github.com/Sapd/HeadsetControl) / [HeadsetControl-G733-Tray](https://github.com/Spider19996/HeadsetControl-G733-Tray), and [g435-hub](https://github.com/minchiki/g435-hub). If your model isn't recognized automatically, you can select any compatible model/protocol manually in the **Model** side drawer (`☰`).

---

## 1. realme Buds & DIZO (`TL + OPPOv1`)
RFCOMM SPP UUIDs: `0000079A-D102-11E1-9B23-00025B00A5A5`, `00001107-D102-11E1-9B23-00025B00A5A5`, `00001101-0000-1000-8000-00805F9B34FB` (+ BLE GATT fallback).  
*Sources: `realme Link.apkm`, [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Model | Features | Status |
|---|---|---|
| **realme Buds T300** | Battery L/Case/R, ANC, EQ (+ custom), Touch gestures, Spatial Audio + demo, Game Mode, L/R usage stats | Verified on device |
| **realme Buds T200** | All core features + LDAC, Dynamic Audio, Dual-Device Connection + history | Verified on device |
| **realme Buds Air 7 Pro** | All core features + LHDC 5.0, Dynamic Audio, Dual-Device Connection + history, Auto Play/Pause, Auto Answer | Verified on device |
| **realme Buds Air 8 Pro / Air 8** | All core features + LHDC 5.0, Dynamic Audio, Dual-Device Connection + history, Auto Play/Pause, Auto Answer | Added `[QuickBuds]` |
| **realme Buds Air 5 / 5 Pro / 6 / 6 Pro / 7 / T310 / T500 / T500 Pro / N1 Pro** | Battery, ANC, EQ, Touch gestures, Spatial Audio, Game Mode, HD Audio, Dynamic Audio, Dual-Device, Auto Play/Pause | Added `[QuickBuds / TL]` |
| **realme Buds T100 / T110 / T200x / T200 Lite / N1 / Buds Clip / Q / Q2 / Q3 / Wireless 2 / 3 / 5 / 6** & **DIZO GoPods / Wireless** | Battery, ANC (where supported), EQ, Touch gestures, Spatial Audio, Game Mode | Added `[QuickBuds / TL]` |

---

## 2. OnePlus Buds (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Source: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Models | Features | Status |
|---|---|---|
| **OnePlus Buds 3 / 3V / 4 / Pro / Pro 2 / Pro 3 / Pro 4 / Nord Buds 4 Pro** | Battery L/Case/R, ANC, EQ, Touch gestures, Spatial Audio, Game Mode, LHDC, Dual-Device Connection, Auto Play/Pause | Added `[QuickBuds]` |
| **OnePlus Buds / Ace / Ace 2 / Ace 3 / Clip / Flow / Open / N / V / Z / Z2 / Nord Buds / 2 / 3 / 3 Pro / 4 / Bullets Wireless** | Battery L/Case/R, ANC (where supported), EQ, Touch gestures, Game Mode, Dual-Device (Nord 3 Pro) | Added `[QuickBuds]` |

---

## 3. OPPO Enco (`TL + OPPOv1`)
RFCOMM SPP UUID: `00001107-D102-11E1-9B23-00025B00A5A5` (fallback `0000079A-...` / `00001101-...`).  
*Source: [QuickBuds](https://github.com/spizganed/QuickBuds).*

| Models | Features | Status |
|---|---|---|
| **OPPO Enco X / X2 / X3 / X3s / X3i / X4 / Free4** | Battery L/Case/R, ANC, EQ, Touch gestures, Game Mode, LHDC / LDAC, Dual-Device Connection, Auto Play/Pause | Added `[QuickBuds]` |
| **OPPO Enco Air / Air2 / Air3 / Air3 Pro / Air4 / Air4 Pro / Air5 / Air5 Pro / Buds / Buds2 / Buds3 / Buds3 Pro / Pro+ / Clip / Clip2 / Free2 / Free3 / R2..R5 / W11..W51 / M31..M33** | Battery, ANC (where supported), EQ, Touch gestures, Game Mode, Dual-Device (Air3..Air5 / Free) | Added `[QuickBuds]` |

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

## 6. Nothing & CMF by Nothing (`Nothing RFCOMM SPP` — all 23 models)
RFCOMM SPP UUID: `AEAC4A03-DFF5-498F-843A-34487CF133EB` (frame `0x55` + CRC16/MODBUS).  
*Sources: [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Nothing Ear (1) / (2) / Ear / (a) / (3) / (3a) / Headphone (1) / Headphone (1) Pro / Headphone (a)** | Battery L/Case/R, ANC + levels (Low / Mid / High / Adaptive), Classic EQ (`0xC01F`/`0xF010`), Low Latency, In-Ear Detection, Ultra Bass | Added `[swift-nothing-ear / kde-headphones]` |
| **Nothing Ear (stick) / Ear (open) / CMF Clip Pro / CMF Speaker S / M** | Battery L/Case/R, Classic EQ, Low Latency, In-Ear Detection | Added `[swift-nothing-ear]` |
| **CMF Buds / CMF Buds Neo / Buds 2 / 2a / 2 Plus / Buds Pro / Buds Pro 2 / Neckband Pro / Headphone Pro** | Battery L/Case/R, ANC + levels, ListeningMode / Classic EQ, Low Latency, In-Ear Detection, Ultra Bass | Added `[swift-nothing-ear / kde-headphones]` |

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

## 8. Sony (`Sony MDR V1/V2 SPP`)
RFCOMM SPP UUIDs: `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (V2), `96CC203E-5068-46AD-B32D-E316F5E069BA` (V1) — frame `0x3E..0x3C` with `0x3D` byte escaping, sum8 checksum, and `0x01` ACK handshake.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones) / SonyHeadphonesClient.*

| Models | Features | Status |
|---|---|---|
| **Sony WH-1000XM3..XM6 / WF-1000XM3..XM5 / WH-CH720N / WH-XB910N / WF-C700N / LinkBuds / LinkBuds S / ULT WEAR / INZONE** | Battery (Single / Dual L+R / Case, `0x22`/`0x23`/`0x25`), NC/ASM (`0x66`..`0x69`), EQ presets (`0x56`..`0x59`: Off / Bright / Vocal / Bass Boost) | Added `[kde-headphones]` |

---

## 9. Bose (`Bose BMAP SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` — frame `[block, func, op, len, payload]`.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Bose QuietComfort 35 / 45 / QC / QC Ultra / NC 700 / SoundSport** | Battery (`0x02/0x02`), ANC / Off / Aware (`0x01/0x06`), Multipoint (`0x01/0x07`) | Added `[kde-headphones]` |

---

## 10. Anker Soundcore (`Soundcore SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` — frame `08 EE 00 00 00` (TX) / `09 FF 00 00 01` (RX) + sum8 checksum.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Soundcore Liberty 3 Pro / 4 / 4 NC / Space Q45 / A40 / One / Life Q30 / Q35 / P3 / A3i / Sport X** | Battery (`0x01/0x03`), ANC / Transparency / Normal (`0x06/0x01`, `0x06/0x81`), Gaming / Low Latency (`0x01/0x11`) | Added `[kde-headphones]` |

---

## 11. Xiaomi / Redmi / POCO (`Xiaomi SPP + SAFER+ Auth`)
RFCOMM SPP UUID: `0000FD2D-0000-1000-8000-00805F9B34FB` — frame `FE DC BA .. EF` with full **SAFER+** challenge-response authentication (`0x50` / `0x51`).  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Xiaomi Buds 3 / 3T Pro / 4 / 4 Pro / 5 / 5 Pro / Redmi Buds 4 / 4 Pro / 5 / 5 Pro / 6 / 6 Pro / POCO Buds** | Battery L/Case/R (`0x09`), ANC + strength levels (`0x08`, `0xF2/0x0B`: Low / Mid / High / Adaptive), EQ (`0xF2/0x07`), In-Ear Detection (`0x0E`), Multipoint (`0xF2/0x04`), Auto Answer (`0xF2/0x03`) | Added `[kde-headphones]` |

---

## 12. Sennheiser (`Sennheiser GAIA v3 SPP`)
RFCOMM SPP UUID: `A2129FF3-081B-4C45-8AFE-469D9C4842EC` — Qualcomm GAIA v3 (`0xFF 0x03`, vendor `0x0495`).  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Sennheiser MOMENTUM 4 / MOMENTUM True Wireless 3 / 4 / ACCENTUM / ACCENTUM Plus / CX Plus / CX True Wireless** | Battery (`0x06/0x02`), ANC / Off / Transparency (`0x05/0x01..0x04`), Spatial / Sound Personalization (`0x06/0x06`) | Added `[kde-headphones]` |

---

## 13. Jabra (`Jabra SPP`)
RFCOMM SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` — frame `04 09 seq (type|len) feat16 payload`.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Jabra Elite 4 / 5 / 7 Pro / 8 Active / 10 / 75t / 85t / Evolve2** | Battery (`0x0101`), ANC / Off / HearThrough (`0x0104`), Multipoint (`0x0107`) | Added `[kde-headphones]` |

---

## 14. Google Pixel Buds (`Maestro HDLC + Pigweed RPC SPP`)
RFCOMM SPP UUID: `25E97FF7-24CE-4C4C-8951-F764A708F7B5` — HDLC (`0x7E`, `0x7D` escape, CRC32) + Pigweed RPC (`maestro_pw.Maestro`) protobuf framing.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **Google Pixel Buds Pro / Pro 2 / A-Series / 2a** | Battery L/Case/R (`SubscribeBattery`), ANC / Off / Transparency (setting `11`), In-Ear Detection (setting `5`), Multipoint (setting `21`) | Added `[kde-headphones]` |

---

## 15. JBL (`JBL BLE GATT`)
BLE GATT Service UUID: `65786365-6c70-6f69-6e74-2e636f6d0000` — frame `0xAA cmd len payload`.  
*Source: [kde-headphones](https://github.com/RodiAda/kde-headphones).*

| Models | Features | Status |
|---|---|---|
| **JBL Tour Pro 2 / 3 / Tour One M2 / Live Pro 2 / Live Beam 3 / Live Flex / Tune Buds / Tune Beam / Wave / Reflect / Club** | Battery L/Case/R (`0x25`), ANC / Off / Ambient Aware (`0x91`), EQ presets (`0x40`: Studio / Vocal / Club / Bass), Auto Play/Pause (`0x35`), Spatial Audio (`0x9D`) | Added `[kde-headphones]` |

---

## 16. Logitech G Gaming Headsets (`USB-OTG HID` & Bluetooth)
USB VID `0x046D` (`G733`: PIDs `0x0AB5`, `0x0AFE`, `0x0B1F`; `G435`: PID `0x0ACB` vendor FFC0 Report 6 + Bluetooth HFP/BLE BAS `0x180F`).  
*Sources: [HeadsetControl](https://github.com/Sapd/HeadsetControl), [HeadsetControl-G733-Tray](https://github.com/Spider19996/HeadsetControl-G733-Tray), [g435-hub](https://github.com/minchiki/g435-hub).*

| Model | Features | Status |
|---|---|---|
| **Logitech G733 LIGHTSPEED** | USB-OTG HID++ 2.0: Battery voltage + calibrated % + hours left, Sidetone (`0–100%`), RGB Lights (strips + logo), Auto power-off (`0–90m`) | Added `[HeadsetControl]` |
| **Logitech G435 LIGHTSPEED / Bluetooth** | USB-OTG 2.4 GHz dongle (`046D:0ACB` FFC0 bridge, 22-point Li-Po curve, smoothing, charging detection, hours left) + Bluetooth HFP & BLE Battery Service (`0x180F/0x2A19`) | Added `[g435-hub]` |

---

### Technical Note: Apple AirPods / Beats
In `kde-headphones`, Apple AirPods / Beats communicate over a raw **Bluetooth Classic BR/EDR L2CAP socket** (`AF_BLUETOOTH`, `BTPROTO_L2CAP`, `PSM 0x1001`, Apple AAP). Android's public SDK restricts `createL2capChannel()` exclusively to **BLE CoC** and blocks unrooted apps from opening raw BR/EDR L2CAP PSMs, so Apple AAP cannot be supported reliably on unrooted Android without root/system privileges.

---

> **Want to add or verify a model?** Open an Issue / PR with the exact Bluetooth name of your earbuds and the copied protocol log from the app!
