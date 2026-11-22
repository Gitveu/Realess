# realness 🎧

**English** | [Русский](README.ru.md)

Standalone Android app (**realness**, package `uwu.realness.gitveu`, Kotlin + Jetpack Compose) and protocol documentation for direct control of **15 earbud brands across 13 protocol families** — **realme**, **OnePlus**, **OPPO**, **HUAWEI**, **HONOR / HONOR CHOICE**, **Nothing / CMF**, **Samsung Galaxy Buds**, **Sony**, **Bose**, **Soundcore**, **Xiaomi / Redmi**, **Sennheiser**, **Jabra**, **Google Pixel Buds**, and **JBL** — no vendor companion apps, no accounts, no cloud, directly over Bluetooth.

**Latest version:** `v1.7 (build 8)` — release builds (`realness 1.7.apk` and `realness-tiny 1.7.apk`)  
**Protocol references:** `realme Link.apkm` (`com.realme.iot.headset.tl.*`), [QuickBuds](https://github.com/spizganed/QuickBuds), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), and [kde-headphones](https://github.com/RodiAda/kde-headphones).

---

## 📱 Supported Earbuds

The full list of supported brands, models, protocols, and feature support is maintained in a dedicated document:

👉 **[SUPPORTED_DEVICES.md](SUPPORTED_DEVICES.md)** ([Русский](SUPPORTED_DEVICES.ru.md))

Supported brands at a glance (13 protocol families):
- **realme Buds & DIZO** (T300, T200, T310, T500, Air 5 / 6 / 7 / 8 / **Air 8 Pro**, Buds Clip, Wireless) — `TL + OPPOv1`
- **OnePlus Buds** (Buds / 3 / 3V / 4 / Ace / Clip / Flow / N / Pro..Pro 4 / Z / Nord Buds..4 Pro) — `TL + OPPOv1`
- **OPPO Enco** (Air..Air5 Pro / Buds..Buds3 Pro+ / Clip / Free..Free4 / X..X4 / R / W / M) — `TL + OPPOv1`
- **HUAWEI** (FreeBuds 3 / 4i / 5i / 6i / Pro..Pro 5 / SE..SE 4 / Studio / FreeClip / FreeLace) — `Huawei SPP`
- **HONOR** (Earbuds 2 / S2, **HONOR CHOICE Earbuds X8** / X5 / X5 Pro / X3 / X2 / X) — `Huawei SPP` & `HONOR CHOICE SPP`
- **Nothing & CMF** (all 23 models: Ear (1)/(2)/(a)/(3)/(3a)/(stick)/(open), Headphone, **CMF Buds Neo** / Buds / Pro / 2 / Clip Pro) — `Nothing SPP`
- **Samsung Galaxy Buds** (Buds / Buds+ / Live / Pro / Buds2 / FE / Buds3 / Core / Buds4) — `Galaxy Buds SPP`
- **Sony** (WH-1000XM3..XM6, WF-1000XM3..XM5, LinkBuds, WH-CH, ULT WEAR) — `Sony MDR V1/V2 SPP`
- **Bose** (QuietComfort 35 / 45 / QC / QC Ultra, NC 700, SoundSport) — `Bose BMAP SPP`
- **Anker Soundcore** (Liberty, Space, Life, Sport X) — `Soundcore SPP`
- **Xiaomi / Redmi / POCO** (Xiaomi Buds 3..5 Pro, Redmi Buds 4..6 Pro, POCO Buds) — `Xiaomi SPP (+ SAFER+ Auth)`
- **Sennheiser** (MOMENTUM, ACCENTUM, CX True Wireless) — `Sennheiser GAIA v3 SPP`
- **Jabra** (Elite 4..10 / 75t / 85t, Evolve2) — `Jabra SPP`
- **Google Pixel Buds** (Pro, Pro 2, A-Series, 2a) — `Maestro HDLC + Pigweed RPC SPP`
- **JBL** (Tour, Live, Tune, Wave, Reflect, Club) — `JBL BLE GATT`

The **Model** side drawer (`☰`) lets you keep **Automatic** detection or manually select any supported model/protocol. When no earbuds are connected, the app remembers and displays the **last connected device** instead of a hardcoded placeholder.

---

## 🚀 Features

### Zero-Touch Auto-Connect
No scanning dialogs or manual device selection. The app listens to system Bluetooth events (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, `ACTION_CONNECTION_STATE_CHANGED`, `ACTION_STATE_CHANGED`). As soon as the earbuds connect to Android over A2DP, `realness` automatically opens the control channel. When both earbuds are placed back into the case, the status transitions to `In case / disconnected`.

### Battery Level (L / Case / R)
- Exact battery percentage (`0–100%`) for the left earbud, right earbud, and charging case, with a charging indicator `⚡`.
- Proper closed-case handling: when the case is closed/offline, the firmware sends `03 00` — the parser normalizes this to `-1`, the UI shows `—`, and the inactive slot does not clutter the battery log.
- Instant event-driven sync + periodic polling every 2 seconds.

### Noise Control (ANC)
Quick switching and reading of all three modes: **Noise Cancellation**, **Off**, and **Transparency**, with auto-scaling typography so labels never wrap.

### Sound Effects (Equalizer)
- Presets: **Serenade**, **Original Sound**, **Pure Bass**, **Deep Bass**.
- Detection of an active custom EQ profile by name via command `0x0122`, highlighted in the UI.

### Touch Controls (Button Gestures)
Read and customize actions for **Double Tap**, **Triple Tap**, and **Touch & Hold** independently for the left and right earbuds, filtered to valid actions per gesture.

### Spatial Audio (3D Sound)
Toggle and read mode state. Includes built-in local demo playback of the original audio samples from realme Link (`headset_spatial_audio_fix.mp3` and `headset_spatial_audio_off.m4a`) via `MediaPlayer` with instant switching on the fly.

### High-Definition Audio (LDAC / LHDC 5.0) & Dynamic Audio
- High-resolution codec toggle (`0x18`): **LDAC** on Buds T200, **LHDC 5.0** on Buds Air 7 Pro.
- **Dynamic Audio** toggle (`0x1D`) for Buds T200 and Buds Air 7 Pro.

### Dual-Device Connection
- Toggle simultaneous connection to 2 devices (`0x11`) on Buds T200 and Buds Air 7 Pro.
- Displays **connection history** and active devices (name, MAC, status) via `0x0112` / `0x8112` and async `0x06` events.

### Smart Wear-Detection Features (Buds Air 7 Pro)
- **Auto Play/Pause** (`0x04`) — pauses playback when removing an earbud and resumes when putting it back on.
- **Auto Answer** (`0x08`) — automatically answers incoming calls when taking out and wearing the earbuds.

### Game Mode
Toggle and read low-latency mode (`0x06`), synced when toggled via an earbud touch gesture.

### Usage Time Statistics (L / R)
Reads cumulative usage time for left and right earbuds directly from headset memory (`0x0128` / `0x8128`) — travels with the earbuds just like in realme Link.

### Live Protocol Console
Collapsible log panel showing incoming/outgoing frames in real time, with copy-to-clipboard and clear buttons.

> **Intentionally excluded:** realme Link app/cloud-side features (**MindFlow**, **Golden Hearing / Hearing Test**, **AI Translate**, **Earbud Fit Test**, **Find My Phone**) as well as potentially risky operations (OTA firmware updates, factory reset).

---

## 🔬 Transport

### Bluetooth Classic RFCOMM SPP (Primary)
The earbuds pair as a standard headset (A2DP / HFP), while the control channel runs over RFCOMM SPP:
- Primary realme / OPPO / OnePlus vendor UUID: `0000079A-D102-11E1-9B23-00025B00A5A5`
- Standard SPP UUID (fallback): `00001101-0000-1000-8000-00805F9B34FB`
- Backup vendor UUIDs: `db764ac8-4b08-7f25-aafe-59d03c27bae4`, `db764ac8-4b08-7f25-aafe-59d03c27bae3`

```kotlin
device.createRfcommSocketToServiceRecord(REALME_VENDOR_SPP_UUID)
```

### BLE GATT (Fallback)
If the SPP socket is unavailable, the app automatically falls back to BLE GATT with auto-detection of write (`PROPERTY_WRITE` / `PROPERTY_WRITE_NO_RESPONSE`) and notification (`PROPERTY_NOTIFY`, CCCD `00002902-0000-1000-8000-00805f9b34fb`) characteristics.

---

## 🧩 Wire Frame Format

Two-layer encapsulation: an inner TL frame is wrapped inside an OPPOv1 transport frame.

### OPPOv1 Transport Framing (SOF `0xAA`)
| Offset | Field |
|---|---|
| `0` | `0xAA` — Start of Frame |
| `1..K` | Packet length (7-bit varint, equals `TL_FRAME_LENGTH + 2`; 1 byte for packets < 128 bytes) |
| `K+1` | Flags (`0x00` — single packet) |
| `K+2` | Reserved (`0x00`) |
| `K+3..` | Inner TL frame |

### Inner TL Frame
| Offset | Field |
|---|---|
| `0..1` | Command ID (uint16 little-endian) |
| `2` | Device Address (`0x00`) |
| `3..4` | Payload Length (uint16 little-endian) |
| `5..N` | Payload |

---

## 📡 Protocol Reference

All frames are transmitted over RFCOMM SPP wrapped in OPPOv1 (`SOF 0xAA`).

| Feature | TX Cmd | TX Payload | RX Cmd | RX Payload |
|---|:---:|---|:---:|---|
| Battery query | `0x0106` | — | `0x8106` | `[00, count, (slot, level\|flag)...]` |
| Set ANC mode | `0x0404` | `01 01 <mode>` | `0x8404` | `[00]` |
| Query ANC mode | `0x010C` | `01 01` | `0x810C` | `[00, 01, 01, <mode>]` |
| Query SwitchFeature | `0x010D` | `01 <featureId>` | `0x810D` | `[00, count, (featureId, val)...]` |
| Set SwitchFeature | `0x0403` | `<featureId> <01/00>` | `0x8403` | `[00]` |
| MultiConnect device list | `0x0112` | — | `0x8112` | `[00, count, (MAC 6B, entryLen, state, flag, nameLen, UTF-8)...]` |
| L/R usage time stats | `0x0128` | — | `0x8128` | `[00, count, (role 1B, minutes 4B LE)...]` |
| Query EQ preset | `0x010F` | — | `0x810F` | `[00, <eqId>]` |
| Set EQ preset | `0x0406` | `<eqId>` | `0x8406` | `[00]` |
| Query custom EQ | `0x0122` | — | `0x8122` | `[00, count, (selected, …, name)...]` |
| Query button settings | `0x0108` | `0E 01 03 04 … 00` | `0x8108` | `[00, count, (dev, 1, act, func)...]` |
| Set button setting | `0x0401` | `01 dev 01 act func` | `0x8401` | `[00]` |
| Subscribe to events | `0x0201` | `<eventType>` | `0x8201` | `[00]` |
| Async events | — | — | `0x0204` | `[03, …]`, `[05, val]`, `[06, …]` |

**`SwitchFeature` IDs (`0x010D` / `0x0403`):**
- `0x04` — Auto Play/Pause (wear detection, Air 7 Pro)
- `0x06` — Game Mode
- `0x08` — Auto Answer (Air 7 Pro)
- `0x11` — Dual-Device Connection (T200 / Air 7 Pro)
- `0x18` — High-Definition Audio: LDAC (T200) / LHDC 5.0 (Air 7 Pro)
- `0x1B` — Spatial Audio (3D Sound)
- `0x1D` — Dynamic Audio (T200 / Air 7 Pro)

**Key Values:**
- `noiseStatus`: `0x08` ANC / `0x01` Off / `0x02` Transparency
- `componentId`: `0x01` left / `0x02` right / `0x03` case; `value & 0x7F` — level, `value & 0x80` — charging flag
- `eqId`: `0x02` Serenade / `0x00` Original Sound / `0x03` Pure Bass / `0x01` Deep Bass
- `actionId`: `0x02` double tap / `0x03` triple tap / `0x04` touch & hold
- `eventType`: `0x03` ANC / `0x05` Game Mode / `0x01` battery / `0x02` earbud status / `0x06` MultiConnect

**Full OPPOv1 Frame Examples:**

```text
Battery query:       AA 07 00 00 06 01 00 00 00
L/R usage stats:     AA 07 00 00 28 01 00 00 00
ANC ON:              AA 0A 00 00 04 04 00 03 00 01 01 08
ANC OFF:             AA 0A 00 00 04 04 00 03 00 01 01 01
Transparency:        AA 0A 00 00 04 04 00 03 00 01 01 02
Game Mode ON:        AA 09 00 00 03 04 00 02 00 06 01
Spatial Audio ON:    AA 09 00 00 03 04 00 02 00 1B 01
LDAC / LHDC 5.0 ON:  AA 09 00 00 03 04 00 02 00 18 01
Auto Play/Pause ON:  AA 09 00 00 03 04 00 02 00 04 01
Auto Answer ON:      AA 09 00 00 03 04 00 02 00 08 01
```

Detailed protocol and architecture documentation is available in [AGENTS.md](AGENTS.md).

---

## 📁 Repository Structure

```text
Realess/
├── README.md                      # Project overview (English)
├── README.ru.md                   # Project overview (Russian)
├── SUPPORTED_DEVICES.md           # Full supported models table (English)
├── SUPPORTED_DEVICES.ru.md        # Full supported models table (Russian)
├── AGENTS.md                      # Detailed protocol & app documentation (English)
├── AGENTS.ru.md                   # Detailed protocol & app documentation (Russian)
├── .github/workflows/             # GitHub Actions for building & publishing signed APKs to Releases
├── tiny-realness/                 # Pure Java 8 source & build.sh without Gradle or AndroidX
└── realness/
    ├── app/src/main/java/uwu/realness/gitveu/
    │   ├── protocol/              # 13 protocol families (realme/OPPO, Huawei, Nothing, Galaxy, Sony, Bose, Soundcore, Xiaomi, Sennheiser, Jabra, Pixel Buds, JBL)
    │   ├── ble/                   # T300BleManager.kt — multi-protocol SPP socket, GATT fallback
    │   ├── viewmodel/             # BudsViewModel.kt — StateFlow, auto-connect, last-device & manual model
    │   └── ui/                    # MainActivity.kt — Material3 UI + Model Picker Drawer
    ├── app/src/main/res/          # values/, values-ru/, xml/locales_config.xml, raw/, icons
    ├── build.gradle.kts
    └── settings.gradle.kts
```

### Stack & Localization
- **Main build (`realness`):** Kotlin, Jetpack Compose (Material3), Coroutines / Flow, R8 minify + resource shrinking, MinSdk 26 (Android 8.0+), TargetSdk 34.
- **Ultra-lightweight build (`realness-tiny`):** pure Java 8 + Android SDK with no Gradle or AndroidX (`aapt2 -> javac -> R8 -> zipalign -> apksigner`), package `uwu.realness.gitveu.tiny` (installs alongside the main app, full protocol feature set without the 1.5 MB demo audio).
- **Localization:** English by default (`values/strings.xml`), full Russian localization (`values-ru/strings.xml`), per-app language support on Android 13+ (`res/xml/locales_config.xml`). Technical protocol logs are in English.
- **Icon:** adaptive icon (90°-rotated realme Link symbol with a diagonal slash `/` on a `#B662FE` → `#4F7FFF` gradient background, monochrome layer for Android 13+, and legacy mipmap).

---

## 📲 Installation

1. Download `realness <version>.apk` (main version) or `realness-tiny <version>.apk` (~33 KB) from [GitHub Releases](https://github.com/Gitveu/realess-t300/releases/latest) and install on Android 8.0+.
2. Grant the "Nearby devices" permission (`BLUETOOTH_CONNECT`).
3. Connect supported realme Buds earbuds — the app will detect and connect to them automatically.

### Building from Source
The signing keystore is stored in **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) and is **never committed to the repository**:
- **On GitHub (from any device):** push changes or trigger the `Build Release APKs` workflow in the **Actions** tab — signed `realness 1.x.apk` and `realness-tiny 1.x.apk` are automatically published to [GitHub Releases](https://github.com/Gitveu/realess-t300/releases) and uploaded as workflow artifacts.
- **Locally:** pass the environment variables `REALNESS_KEYSTORE_PATH`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD` (or place a local `keystore.properties` in the repo root; it is in `.gitignore`):

```bash
git clone https://github.com/Gitveu/realess-t300.git
cd realess-t300

# Main release build (Gradle + R8):
cd realness && ./gradlew assembleRelease

# Ultra-lightweight release build (pure Java, no Gradle):
cd .. && ./tiny-realness/build.sh
```

Repository: [github.com/Gitveu/realess-t300](https://github.com/Gitveu/realess-t300) (`main` branch).
