# realness

**English** | [Русский](README.ru.md)

Standalone open-source Android app (`uwu.realness.gitveu`) for direct Bluetooth control of **15 earbud brands across 13 protocol families** — no vendor companion apps, no accounts, no cloud services.

- **Latest release:** `v1.8 (build 9)` — `realness 1.8.apk` and `realness-tiny 1.8.apk`
- **Supported models:** [SUPPORTED_DEVICES.md](SUPPORTED_DEVICES.md) ([Русский](SUPPORTED_DEVICES.ru.md))
- **Technical & protocol reference:** [AGENTS.md](AGENTS.md) ([Русский](AGENTS.ru.md))

---

## Two Editions

| Edition | Package | Size | UI & Stack | Notes |
|---|---|---|---|---|
| **realness** | `uwu.realness.gitveu` | ~1.6 MB | Kotlin, Jetpack Compose, native **Material 3 Expressive** (Material You dynamic color, interactive spline EQ graph, bundled realme Spatial Audio demo) | Recommended main build |
| **realness-tiny** | `uwu.realness.gitveu.tiny` | ~65 KB | Pure Java 8 + Android SDK (no Gradle, no AndroidX), classic compact dark UI | Installs side-by-side with `realness`, full 13-protocol support |

---

## Supported Brands (13 Protocol Families)

Full per-model capability matrix: **[SUPPORTED_DEVICES.md](SUPPORTED_DEVICES.md)**.

- **realme Buds & DIZO** — T300, T200, T310, T500, Air 5 / 6 / 7 / 8 / Air 8 Pro, Buds Clip, Wireless (`TL + OPPOv1`)
- **OnePlus Buds** — Buds / 3 / 3V / 4, Ace, Clip, Flow, N, Pro..Pro 4, Z, Nord Buds..4 Pro (`TL + OPPOv1`)
- **OPPO Enco** — Air..Air5 Pro, Buds..Buds3 Pro+, Clip, Free..Free4, X..X4, R, W, M (`TL + OPPOv1`)
- **HUAWEI** — FreeBuds 3 / 4i / 5i / 6i / Pro..Pro 5 / SE..SE 4 / Studio / FreeClip / FreeLace (`Huawei SPP`)
- **HONOR** — Earbuds 2 / S2, HONOR CHOICE Earbuds X8 / X5 / X5 Pro / X3 / X2 / X (`Huawei SPP` & `HONOR CHOICE SPP`)
- **Nothing & CMF** — all 23 models: Ear (1)/(2)/(a)/(3)/(3a)/(stick)/(open), Headphone, CMF Buds / Neo / Pro / 2 / Clip Pro (`Nothing SPP`)
- **Samsung Galaxy Buds** — Buds, Buds+, Live, Pro, Buds2, FE, Buds3, Core, Buds4 (`Galaxy Buds SPP`)
- **Sony** — WH-1000XM3..XM6, WF-1000XM3..XM5, LinkBuds, WH-CH, ULT WEAR (`Sony MDR V1/V2 SPP`)
- **Bose** — QuietComfort 35 / 45 / QC / QC Ultra, NC 700, SoundSport (`Bose BMAP SPP`)
- **Anker Soundcore** — Liberty, Space, Life, Sport X (`Soundcore SPP`)
- **Xiaomi / Redmi / POCO** — Xiaomi Buds 3..5 Pro, Redmi Buds 4 Active / 4..6 Pro / 8 Active, POCO Buds (`Xiaomi SPP + SAFER+`)
- **Sennheiser** — MOMENTUM, ACCENTUM, CX True Wireless (`GAIA v3 SPP`)
- **Jabra** — Elite 4..10 / 75t / 85t, Evolve2 (`Jabra SPP`)
- **Google Pixel Buds** — Pro, Pro 2, A-Series, 2a (`Maestro HDLC + Pigweed RPC`)
- **JBL** — Tour, Live, Tune, Wave, Reflect, Club (`JBL BLE GATT`)

The **Model** side drawer lets you keep **Automatic** detection or pick any model/protocol manually. When disconnected, `realness` remembers and displays the last connected device instead of a hardcoded placeholder.

---

## Features

- **Zero-Touch Auto-Connect:** Listens to Android Bluetooth events (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, A2DP state changes) and opens the SPP/GATT control link automatically as soon as earbuds connect. Includes backoff auto-reconnect on transient RFCOMM drops.
- **Battery Levels (L / Case / R):** Exact percentages with charging indicator and closed-case normalization (offline case reports `—` instead of `0%`).
- **Noise Control (ANC):** Noise Cancellation, Off, Transparency, plus ANC intensity levels (Comfort / General / Ultra / Dynamic or Low / Mid / High / Adaptive) on Huawei, Nothing/CMF, and Xiaomi.
- **Sound Effects & Protocol-Aware Custom Equalizer:**
  - Brand-native presets.
  - Interactive custom EQ curve editor tailored to each protocol's native band count and dB range:
    - **realme / OnePlus / OPPO**: 6 bands (`62..16k`, `-6..+6 dB`), multiple named profiles (create / edit / select / delete).
    - **HUAWEI**: 10 bands (`-6..+6 dB`), multiple named profiles.
    - **Xiaomi / Redmi**: 10 bands (`-10..+10 dB`, save & reset via RCSP `0x0037`).
    - **Sony MDR**: 6 bands (`-10..+10 dB`).
    - **Anker Soundcore**: 8 bands (`-6..+6 dB`).
    - **Sennheiser GAIA** & **Pixel Buds**: 5 bands (`-6..+6 dB`).
    - **Nothing / CMF** (`-6..+6 dB`) & **Bose** (`-10..+10 dB`): 3 bands (Bass / Mid / Treble).
- **Universal Find My Earbuds:** Works on **all** Bluetooth headsets via a synthesized escalating stereo locator tone (`15% -> 100%` over 20s) with Left / Both / Right channel panning and automatic media volume restoration on Stop, plus optional native firmware chime on supported models (realme / OnePlus / OPPO, Nothing / CMF, Galaxy Buds).
- **Rename Earbuds:** Updates headset firmware name where supported (Bose BMAP), attempts Android `BluetoothDevice.setAlias()` when allowed by the OS, and saves a persistent per-MAC display alias inside `realness`.
- **Low Latency / Game Mode:** Supported on realme / OnePlus / OPPO, Xiaomi / Redmi (including Redmi Buds 4 Active via `0x002F`), HUAWEI, Nothing / CMF, Samsung Galaxy Buds, and Soundcore.
- **Touch Controls:** Configure Double Tap, Triple Tap, and Touch & Hold independently for Left and Right earbuds (realme / OnePlus / OPPO, HUAWEI).
- **Spatial Audio, Hi-Res Codecs & Wear Detection:** Spatial Audio (with local realme demo samples), LDAC / LHDC 5.0 / Sound Quality Priority, Dynamic Audio / Ultra Bass, Dual-Device / Seamless Connection with device history, Auto Play/Pause, Auto Answer, and L/R cumulative usage time.
- **Live Protocol Console:** Collapsible real-time hex/event log with copy and clear actions.

> **Intentionally omitted:** vendor cloud features (MindFlow, Hearing Test, AI Translate, Fit Test, Find My Phone) and risky write operations (OTA firmware flashing, factory reset).

---

## Repository Structure

```text
Realess/
├── README.md / README.ru.md                  # Project overview
├── SUPPORTED_DEVICES.md / .ru.md             # Full supported models matrix
├── AGENTS.md / AGENTS.ru.md                  # Engineering rules & byte-level protocol docs
├── .github/workflows/build-release.yml       # CI release builds & GitHub Releases publishing
├── tiny-realness/                            # Pure Java 8 ultra-lightweight edition + build.sh
│   └── src/uwu/realness/gitveu/tiny/         # Protocol, BudsLink, FindTone, MainActivity, ExtraProtos
└── realness/                                 # Main Jetpack Compose Material 3 Expressive edition
    └── app/src/main/java/uwu/realness/gitveu/
        ├── protocol/                         # 13 protocol families & BudsModel capability flags
        ├── ble/                              # T300BleManager.kt & T300GattConfig.kt
        ├── audio/                            # FindBudsPlayer.kt (universal escalating stereo tone)
        ├── viewmodel/                        # BudsViewModel.kt (StateFlow, auto-connect, rename)
        └── ui/                               # ExpressiveTheme.kt, ExpressiveSheets.kt, MainActivity.kt
```

---

## Installation & Building

1. Download `realness <version>.apk` or `realness-tiny <version>.apk` from [GitHub Releases](https://github.com/Gitveu/Realess/releases/latest) (Android 8.0+ / API 26+).
2. Grant the **Nearby devices** permission (`BLUETOOTH_CONNECT`).
3. Connect your earbuds via Android Bluetooth — `realness` detects and connects automatically.

### Versioning

- **Feature releases:** `1.x` (e.g. `1.8`, `1.9`).
- **Small fixes / hotfixes:** `1.x.y` (e.g. `1.8.1`, `1.8.2`).
- `versionCode` increases by `+1` on every release.

### Build from Source

Release signing credentials are stored in **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) and are never committed to Git. For local signed builds, provide `REALNESS_KEYSTORE_*` environment variables or a gitignored `keystore.properties` in the repository root:

```bash
git clone https://github.com/Gitveu/Realess.git
cd Realess

# Main Material 3 Expressive release build (Gradle + R8)
cd realness && ./gradlew assembleRelease

# Ultra-lightweight pure Java 8 release build
cd .. && ./tiny-realness/build.sh
```

---

## Protocol Credits

Reverse-engineering and open-source protocol references: `realme Link` (`com.realme.iot.headset.tl.*`), [QuickBuds](https://github.com/spizganed/QuickBuds), [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds), [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds), [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), [kde-headphones](https://github.com/RodiAda/kde-headphones), and [MiBudsClient](https://github.com/CesurPolat/MiBudsClient).
