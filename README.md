# realness 🎧

**English** | [Русский](README.ru.md)

Standalone Android app (**realness**, package `uwu.realness.gitveu`, Kotlin + Jetpack Compose) and **TL TWS** protocol documentation for direct control of **realme Buds** earbuds — no **realme Link**, no accounts, no cloud, directly over Bluetooth.

**Latest version:** `v1.4 (build 5)` — release builds (`realness 1.4.apk` and `realness-tiny 1.4.apk`)  
**Reverse-engineering source:** `realme Link.apkm` (package `com.realme.link`, stack `com.realme.iot.headset.tl.*`).

---

## 📱 Supported Earbuds

The list of supported models is actively growing — **new realme Buds models will be added gradually** based on protocol analysis and user feedback.

| Model | Core Features | Model-Specific Features | Status |
|---|---|---|---|
| **realme Buds T300** | All core features | — | Verified on device |
| **realme Buds T200** | All core features | LDAC, Dynamic Audio, Dual-Device Connection + connection history | Verified on device |
| **realme Buds Air 7 Pro** | All core features | LHDC 5.0, Dynamic Audio, Dual-Device Connection + connection history, Auto Play/Pause, Auto Answer | Verified on device |

> **How to help add a new model:** open an Issue / PR with the exact Bluetooth name of your earbuds and screenshots of your model's settings from the official realme Link app (and, if possible, the copied protocol log from the app).

If a third-party headset is connected to the phone (e.g., Honor, Redmi, Galaxy Buds, etc.), the app detects it and displays a clear unsupported-device warning.

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
realess-t300/
├── README.md                      # Project overview (English)
├── README.ru.md                   # Project overview (Russian)
├── AGENTS.md                      # Detailed protocol & app documentation (English)
├── AGENTS.ru.md                   # Detailed protocol & app documentation (Russian)
├── .github/workflows/             # GitHub Actions for building signed release APKs via Secrets
├── realness 1.4.apk               # Main release build (Jetpack Compose + Material3 + R8)
├── realness-tiny 1.4.apk          # Ultra-lightweight release build via tinyapk-lab (~33 KB)
├── tiny-realness/                 # Pure Java 8 source & build.sh without Gradle or AndroidX
└── BudsT300Controller/
    ├── app/src/main/java/uwu/realness/gitveu/
    │   ├── protocol/              # T300Protocol.kt — TL + OPPOv1 frames, BudsModel & capabilities
    │   ├── ble/                   # T300BleManager.kt — SPP socket, GATT fallback, queue, events
    │   ├── viewmodel/             # BudsViewModel.kt — StateFlow, auto-connect, demo player
    │   └── ui/                    # MainActivity.kt — Material3 UI
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

1. Download [realness 1.4.apk](realness%201.4.apk) (main version) or [realness-tiny 1.4.apk](realness-tiny%201.4.apk) (~33 KB) and install on Android 8.0+.
2. Grant the "Nearby devices" permission (`BLUETOOTH_CONNECT`).
3. Connect supported realme Buds earbuds — the app will detect and connect to them automatically.

### Building from Source
The signing keystore is stored in **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) and is **never committed to the repository**:
- **On GitHub (from any device):** trigger the `Build Release APKs` workflow in the **Actions** tab and download the signed `realness 1.x.apk` and `realness-tiny 1.x.apk` artifacts.
- **Locally:** pass the environment variables `REALNESS_KEYSTORE_PATH`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD` (or place a local `keystore.properties` in the repo root; it is in `.gitignore`):

```bash
git clone https://github.com/Gitveu/realess-t300.git
cd realess-t300

# Main release build (Gradle + R8):
cd BudsT300Controller && ./gradlew assembleRelease

# Ultra-lightweight release build (pure Java, no Gradle):
cd .. && ./tiny-realness/build.sh
```

Repository: [github.com/Gitveu/realess-t300](https://github.com/Gitveu/realess-t300) (`main` branch).
