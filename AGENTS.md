# realness — Engineering Guide & Protocol Reference

**English** | [Русский](AGENTS.ru.md)

Last updated: 2026-11-24  
Current release: `v1.9 (build 10)` — **14 protocol families across 16 brands** (`realness` in native Material 3 Expressive + Material You widgets + `realness-tiny` in classic compact UI). Full model matrix: [SUPPORTED_DEVICES.md](SUPPORTED_DEVICES.md).

---

## 1. Release, Versioning & Engineering Rules

### 1.1. Semantic Versioning Policy
- **Feature / UI / protocol releases:** use `1.x` (e.g. `1.8`, `1.9`).
- **Small fixes / hotfixes** (e.g. fixing an EQ preset mapping, protocol byte bug, or UI glitch): use `1.x.y` (e.g. `1.8.1`, `1.8.2`, `1.9.1`).
- **`versionCode`:** ALWAYS increment monotonically by `+1` on every release (`9 -> 10 -> 11`), even for `1.x.y` patch releases.
- **Sync both builds:** keep `versionName` and `versionCode` identical in:
  - `realness/app/build.gradle.kts`
  - `tiny-realness/AndroidManifest.xml`
- **APK names & GitHub Releases:** outputs are always named `realness <versionName>.apk` and `realness-tiny <versionName>.apk`. `.github/workflows/build-release.yml` reads `versionName` from `realness/app/build.gradle.kts` and publishes under tag `v<versionName>` (works identically for `1.x` and `1.x.y`).
- **Docs-only commits** (only `*.md` files) do NOT require bumping `versionName` / `versionCode` and do not trigger `build-release.yml`.

### 1.2. Build, Signing & Compatibility
- Always build **release** variants:
  - `cd realness && ./gradlew assembleRelease` (R8 minify + resource shrinking + `lintVitalRelease`)
  - `./tiny-realness/build.sh` (pure Java 8: `aapt2 -> javac -> R8 -> zipalign -> apksigner`)
- Keep `compileSdk = 34` / `targetSdk = 34` compatible with GitHub Actions (`platforms;android-34`, `build-tools;34.0.0`, `material3:1.3.1`).
- **Never commit** `.apk` files, `realness-release.keystore`, or `keystore.properties` (all are in `.gitignore`). CI decodes the release keystore from GitHub Secrets (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`).
- Verify local signed APKs with `apksigner verify --print-certs` before claiming release readiness.

### 1.3. UI & Localization Rules
- **`realness` (`uwu.realness.gitveu`):** native **Material 3 Expressive** (`ExpressiveTheme.kt`, `ExpressiveSheets.kt`, `MainActivity.kt`):
  - `enableEdgeToEdge()`, Android 12+ Material You dynamic dark/light color schemes (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) with warm tonal fallback for Android 8–11.
  - Use `MaterialTheme.colorScheme.*` tonal tokens (`surfaceContainerLowest..Highest`, `primaryContainer`, `secondaryContainer`) — avoid hardcoded dark hex colors.
  - Expressive rounded surfaces (`24.dp`/`28.dp` cards, `CircleShape` pills), `SingleChoiceSegmentedButtonRow`, `Icons.Rounded.*` throughout, zero emojis in UI, tabular numerals (`fontFeatureSettings = "tnum"`) for battery %, dB, volume %, and usage times, strict `4.dp` spacing grid.
- **`realness-tiny` (`uwu.realness.gitveu.tiny`):** keep on its classic compact zero-dependency dark UI (~65 KB, zero AndroidX/Kotlin).
- **Localization parity:** `realness/app/src/main/res/values/strings.xml` (English default) and `values-ru/strings.xml` (Russian) MUST have 100% matching key sets (`diff -u`). Technical protocol logs are always emitted in English.

### 1.4. Git & Authorship Hygiene
- Stage files **explicitly** by path (`git add <files>`) — never use `git add -A` or `git add .`.
- Inspect `git diff --cached --name-only` and check for secrets, local absolute paths, or binary artifacts before committing.
- Preserve repository author `Gitveu <Gitveu@users.noreply.github.com>`. Never add `Co-Authored-By` trailers, AI labels, or emojis in commit messages.
- Verify `git show -s --format=fuller HEAD` and `git log -1 --format=%B` after committing.
- Repository remote: `https://github.com/Gitveu/Realess.git` (`main` branch).

---

## 2. App Architecture & Code Map

### 2.1. `realness/` (Kotlin + Jetpack Compose)
- `protocol/`:
  - `T300Protocol.kt`: realme / OnePlus / OPPO / DIZO TL+OPPOv1 framing, `BudsModel` enum (15 brands, capability flags, `customEqSpec()`, `supportsGameMode`, `supportsHardwareFindBuds`, `supportsFirmwareRename`), `T300State`, `CustomEqProfile`, `CustomEqSpec`.
  - `HuaweiProtocol.kt`, `NothingProtocol.kt`, `GalaxyProtocol.kt`, `SonyProtocol.kt`, `BoseSoundcoreProtocol.kt`, `XiaomiProtocol.kt`, `ExtraProtocols.kt` (Sennheiser, Jabra, Pixel Buds, JBL).
- `ble/`:
  - `T300GattConfig.kt`: all vendor & standard RFCOMM SPP UUIDs and BLE GATT service/characteristic UUIDs.
  - `T300BleManager.kt`: `sessionGen`-guarded RFCOMM SPP & BLE GATT connection manager (`isConnectedOrConnectingTo` prevents duplicate connect races), streaming packet parsers for all 13 families, polling, async event handling, Custom EQ read/write, hardware Find Buds, Bose firmware rename, and `unexpectedDisconnectFlow` for transient drop recovery.
- `audio/`:
  - `FindBudsPlayer.kt`: universal escalating stereo `AudioTrack` locator tone (`FindBudsChannel.LEFT / BOTH / RIGHT`, dual-harmonic triple-chirp `2950 Hz + 3450 Hz`, smooth `15% -> 100%` ramp over 20 s, saves and restores `STREAM_MUSIC` volume on Stop).
- `viewmodel/BudsViewModel.kt`: reactive `StateFlow`, zero-touch A2DP/ACL auto-connect, exponential backoff reconnect on transient drops, persistent last-device / manual model override / per-MAC rename alias in `SharedPreferences`, `MediaPlayer` spatial demo, `FindBudsPlayer` controller, and 3-tier device rename (`Bose firmware -> BluetoothDevice.setAlias() -> per-MAC local alias`).
- `ui/`:
  - `ExpressiveTheme.kt`: `RealnessExpressiveTheme` (Material You dynamic + tonal fallback).
  - `ExpressiveSheets.kt`: `CustomEqEditorSheet` (interactive cubic-Bezier EQ graph `ModalBottomSheet`), `FindBudsSheet` (`ModalBottomSheet`), `RenameDeviceDialog` (`AlertDialog`).
  - `MainActivity.kt`: `ModalNavigationDrawer` model picker, hero status card with Find/Rename actions, battery strip, ANC selector, EQ & custom profiles, touch gesture settings, spatial audio, model-gated toggles, usage time, collapsible protocol log console.

### 2.2. `tiny-realness/` (Pure Java 8)
- `Protocol.java`, `HuaweiProto.java`, `NothingProto.java`, `GalaxyProto.java`, `ExtraProtos.java`: compact pure-Java builders/parsers for all 13 protocol families.
- `BudsLink.java`: `sessionGen`-guarded RFCOMM SPP & BLE GATT link manager.
- `FindTone.java`: compact escalating stereo `AudioTrack` locator tone.
- `MainActivity.java`: classic zero-dependency programmatic dark UI with model picker dialog, Find Buds dialog, Rename dialog, and SeekBar Custom EQ dialog.

---

## 3. Universal Features

### 3.1. Universal Find My Earbuds
- **Audio locator (ALL headsets):** plays a high-visibility triple-chirp stereo PCM loop over `AudioTrack` (`USAGE_MEDIA`), escalating system `STREAM_MUSIC` volume from 15% to 100% over 20 seconds with Left / Both / Right panning, restoring original media volume immediately on Stop or app destroy.
- **Optional native firmware ring:**
  - realme / OnePlus / OPPO: `0x0400`, payload `[01]` (on) / `[00]` (off)
  - Nothing / CMF: `0xF002`, payload `[sideByte (0x01=L, 0x03=R, 0x06=Both), 01/00]`
  - Samsung Galaxy Buds: `0xA0` (start) / `0xA1` (stop), empty payload

### 3.2. Rename Earbuds
1. **Firmware rename** (when connected & supported): Bose BMAP `Block=1, Func=2, Op=SET_GET`, UTF-8 name up to 31 bytes.
2. **Android OS alias:** calls `BluetoothDevice.setAlias(String)` directly on API 31+ (reflection on API 26–30). When Android 12+ requires Companion Device Manager association, launches `CompanionDeviceManager.associate()` (1-tap system approval) and completes `device.setAlias()` immediately after approval so Android Bluetooth settings reflect the new name. Empty input calls `setAlias(null)` to restore the default name.
3. **Persistent app & widget alias:** saved per MAC in `SharedPreferences` (`custom_alias_<MAC>` / `alias_<MAC>`).

### 3.3. Material You Widgets & Last Known Case Battery
- `RealnessApp` holds process-wide `T300BleManager` and `LogitechUsbManager` singletons; `WidgetStateStore` persists battery snapshot, ANC state, and per-MAC **last known case battery + timestamp** whenever `caseBattery.level in 0..100` is received.
- `RealnessWidgetProvider` (4×2 Material You card) and `RealnessCompactWidgetProvider` (4×1 pill) display L / Case (with relative timestamp when closed) / R and trigger live or background-reconnect ANC switches via `goAsync()`.

---

## 4. Protocol Family 1: realme / OnePlus / OPPO / DIZO (`TL + OPPOv1`)

Reverse-engineered from `realme Link.apkm` (`com.realme.iot.headset.tl.*`) and [QuickBuds](https://github.com/spizganed/QuickBuds).

### 4.1. Transport & Framing
- **RFCOMM SPP UUIDs:**
  - realme primary: `0000079A-D102-11E1-9B23-00025B00A5A5`
  - OnePlus / OPPO primary: `00001107-D102-11E1-9B23-00025B00A5A5`
  - Standard SPP fallback: `00001101-0000-1000-8000-00805F9B34FB`
  - Backup vendor UUIDs: `db764ac8-4b08-7f25-aafe-59d03c27bae4`, `db764ac8-4b08-7f25-aafe-59d03c27bae3`
- **BLE GATT Fallback:** auto-detects writable & notifiable characteristics (`CCCD 00002902-0000-1000-8000-00805f9b34fb`).
- **Outer OPPOv1 Frame:** `[0xAA, lenVarint (TL_LEN + 2), flags (0x00), reserved (0x00), innerTL...]`
- **Inner TL Frame:** `[cmdLo, cmdHi, devAddr (0x00), payloadLenLo, payloadLenHi, payload...]`

### 4.2. Command Table

| Feature | TX Cmd | TX Payload | RX Cmd | RX Payload |
|---|:---:|---|:---:|---|
| Battery query | `0x0106` | *empty* | `0x8106` | `[00, count, (slot, level\|chgBit)...]` (`03 00` = offline case -> `-1`) |
| Query ANC | `0x010C` | `01 01` | `0x810C` | `[00, 01, 01, mode]` (`08`=ANC, `01`=Off, `02`=Transparency) |
| Set ANC | `0x0404` | `01 01 <mode>` | `0x8404` | `[00]` |
| Query SwitchFeature | `0x010D` | `01 <featId>` | `0x810D` | `[00, count, (featId, 01/00)...]` |
| Set SwitchFeature | `0x0403` | `<featId> <01/00>` | `0x8403` | `[00]` |
| Query EQ preset | `0x010F` | *empty* | `0x810F` | `[00, eqId]` (`02`=Serenade, `00`=Original, `03`=Pure Bass, `01`=Deep Bass) |
| Set EQ preset | `0x0406` | `<eqId>` | `0x8406` | `[00]` |
| Query custom EQ profiles | `0x0122` | *empty* | `0x8122` | `[00, count, (selected, tag 2B, id, nameLen, name, bands, [freq u16 LE, gain s8]...)...]` |
| Create/Save/Delete custom EQ | `0x0418` | `<action: 1/2/3> <tag 2B> <id> <nameLen> <name> <bands> [freq u16 LE, gain s8]...` | `0x8418` | `[00]` |
| Find My Earbuds (hardware) | `0x0400` | `<01/00>` | — | — |
| Query touch settings | `0x0108` | `0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00` | `0x8108` | `[00, count, (dev 1=L/2=R, 01, act 2/3/4, func)...]` |
| Set touch setting | `0x0401` | `01 <dev> 01 <act> <func>` | `0x8401` | `[00]` |
| MultiConnect device list | `0x0112` | *empty* | `0x8112` | `[00, count, (6B MAC rev, entryLen, state, flag, nameLen, UTF-8)...]` |
| L/R usage time stats | `0x0128` | *empty* | `0x8128` | `[00, count, (role 1B, totalMinutes u32 LE)...]` |
| Subscribe to events | `0x0201` | `<eventType>` (`01,02,03,05,06`) | `0x8201` | `[00]` |
| Async device events | — | — | `0x0204` | `[03, ...]` ANC, `[05, val]` Game Mode, `[06, ...]` MultiConnect |

- **`SwitchFeature` IDs (`0x010D` / `0x0403`):** `0x04` Auto Play/Pause, `0x06` Game Mode, `0x08` Auto Answer, `0x11` Dual-Device Connection, `0x18` LDAC / LHDC 5.0, `0x1B` Spatial Audio, `0x1D` Dynamic Audio.
- **Touch `functionId`:** `0x01` Play/Pause, `0x06` Next, `0x05` Prev, `0x03` Voice Assistant, `0x08` ANC, `0x0D` Switch Devices, `0x11` Game Mode, `0x0B` Vol+, `0x0C` Vol-, `0x00` None.
- **Spatial Audio demo:** local `MediaPlayer` playback of bundled `headset_spatial_audio_fix.mp3` (3D) and `headset_spatial_audio_off.m4a` (stereo).

---

## 5. Protocol Families 2–3: HUAWEI & HONOR (`Huawei SPP` & `HONOR CHOICE SPP`)

Based on [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) and [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds).

- **UUIDs:** Standard SPP `00001101-0000-1000-8000-00805F9B34FB` (channel 1) and Huawei Private `96cc203e-5068-46ad-b32d-e316f5e069ba` (channel 16: FreeBuds 4i / 5i / SE / FreeLace Pro / Honor Earbuds 2).
- **Frame:** `[0x5A, lenHi, lenLo, 0x00, cmdHi, cmdLo, TLV(type, len, val)..., crc16XmodemHi, crc16XmodemLo]`.
- **Commands:**
  - Battery: `0x0108` read, `0x0127` notify (param 2 `[L, R, Case]`, param 3 charging flags, param 1 global).
  - ANC: `0x2B2A` read, `0x2B04` write (`0=Off, 1=ANC, 2=Transparency`; levels `1=Comfort, 0=General, 2=Ultra, 3=Dynamic`), legacy event `0x2B03`.
  - Low Latency: `0x2B6C`; Auto Play/Pause: `0x2B11`/`0x2B10`; Sound Quality Priority: `0x2BA3`/`0x2BA2`.
  - EQ & Custom EQ (10 bands, `-6..+6 dB`): read `0x2B4A` (param 2 active ID, param 8 36-byte custom entries `[id, count, gains(0.1 dB s8), UTF-8 name]`), write `0x2B49` (params `1=id, 2=bandCount, 3=gains, 4=name, 5=action(1=save, 2=delete)`).
  - Dual-Device: `0x2B2F`/`0x2B2E`, enumerate `0x2B31`, event `0x2B36`.
  - Gestures: double tap `0x0120`/`0x011F`, triple tap `0x0126`/`0x0125`, long press `0x2B17`/`0x2B16`.
- **HONOR CHOICE variant** (X8 / X5 / X5 Pro / X3 / X2 / X): same `0x5A` framing, 1-byte `0x2B04` ANC write (`[0/1/2]`), `0x2701` EQ write (`[0=Balanced, 1=Bass, 2=Vocal]`).

---

## 6. Protocol Families 4–5: Nothing / CMF & Samsung Galaxy Buds

### 6.1. Nothing / CMF (`Nothing SPP`)
Based on [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), and [kde-headphones](https://github.com/RodiAda/kde-headphones).
- **UUID:** `AEAC4A03-DFF5-498F-843A-34487CF133EB` (+ Standard SPP fallback).
- **Frame:** `[0x55, 0x60, 0x01, cmdLo, cmdHi, lenLo, lenHi, opId, payload..., crc16ModbusLo, crc16ModbusHi]`.
- **Commands:** Handshake `0xC006`/`0x4006`; Battery `0xC007`/`0x4007`/`0xE001` (`2=L, 3=R, 4=Case, 6=Main`); ANC `0xC01E`/`0x401E`/`0xE003`/`0xF00F` (`1=High, 2=Mid, 3=Low, 4=Adaptive, 5=Off, 7=Transparency`); Classic EQ `0xC01F`/`0xF010`, ListeningMode EQ `0xC050`/`0xF01D`; 3-band float Custom EQ `0xC044`/`0x4044`/`0xF041` (`[3, totalGain f32 LE, 3 × (type, gain, freq, Q)]`); In-Ear `0xC00E`/`0xF004`; Low Latency `0xC041`/`0xF040`; Ultra Bass `0xC04E`/`0xF051`; Ring Buds `0xF002`.

### 6.2. Samsung Galaxy Buds (`Galaxy Buds SPP`)
Based on [kde-headphones](https://github.com/RodiAda/kde-headphones).
- **UUIDs:** Modern `2E73A4AD-332D-41FC-90E2-16BEF06523F2`, Legacy Buds (2019) `00001102-0000-1000-8000-00805F9B34FD`, Buds+ `00001101-0000-1000-8000-00805F9B34FB`.
- **Frames:** Modern `[0xFD, sizeLo, sizeHi&0x03, msgId, payload..., crc16CcittLo, crc16CcittHi, 0xDD]` and Legacy `[0xFE, 0x00, size, msgId, payload..., crcLo, crcHi, 0xEE]`.
- **Messages:** Push `0x61` (`ExtendedStatusUpdated`) & `0x60` (`StatusUpdated`); Handshake `0x88` (`ManagerInfo [01 02 22]`); ANC `0x78` / `0x98` / `0x80`; EQ `0x86`; Gaming Mode `0x87`; Seamless Connection `0xAF`; Find My Earbuds `0xA0` (start) / `0xA1` (stop).

---

## 7. Protocol Family 6: Xiaomi / Redmi / POCO (`Xiaomi SPP + SAFER+`)

Based on [kde-headphones](https://github.com/RodiAda/kde-headphones) and [MiBudsClient](https://github.com/CesurPolat/MiBudsClient).

- **UUID:** `0000FD2D-0000-1000-8000-00805F9B34FB` (+ Standard SPP fallback).
- **Frame:** `[0xFE, 0xDC, 0xBA, type, opcode, bodyLenHi, bodyLenLo, (status if rsp), seq, payload..., 0xEF]`.
- **SAFER+ Authentication:** Host initiates `0x50 [00, 16B rand]`, responds to earbud `0x50` challenge using SAFER+ cipher (`0x50` response), confirms via `0x51`.
- **Device & Run Info:** `0x03` (`FF FF FF FF`) battery triplet (`idx 0x07`), `0x07` (`FF FF FF FF`) ANC (`idx 0x09`) & ear detection (`idx 0x0A`), `0x02` async status push.
- **RCSP Config (`0xF3` Get / `0xF2` Set / `0xF4` Notify):**
  - `0xF3` Get payload is a **2-byte big-endian key** `[keyHi, keyLo]` (no leading length byte).
  - `0xF2` Set payload is `[2 + valLen, keyHi, keyLo, val...]`.
  - Records in `0xF3`/`0xF4` responses support both normal `[len, keyHi, keyLo, val...]` and extended `0xFF` `[0xFF, keyHi, keyLo, lenHi, lenLo, val...]` records.
  - Keys: `0x0003` Auto Answer, `0x0004` Multipoint, `0x0007` EQ preset, `0x000B` ANC strength, **`0x002F` Low Latency** (Redmi Buds 4 Active & Xiaomi/Redmi lineup), **`0x0037` 10-band Custom EQ** (`-10..+10 dB`, sign-magnitude gain `g >= 0 ? g : 128 - g`, reset `[04, 01, 01, 01]`).

---

## 8. Protocol Families 7–14 (`kde-headphones`, `HeadsetControl`, `g435-hub`)

- **Sony MDR (V1/V2):** UUIDs `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (V2) / `96CC203E-5068-46AD-B32D-E316F5E069BA` (V1); framing `0x3E..0x3C` with `0x3D` escape, sum8 checksum, ACK `0x01`; Battery `0x22`/`0x23`/`0x25`, NCASM `0x66..0x69`, EQ presets & 6-band Custom EQ `0x56..0x59` (`0xA0`, `gain + 10`).
- **Bose BMAP:** Standard SPP `00001101`; `[block, func, op, len, payload]`; Battery `0x02/0x02`, ANC `0x01/0x05` (CNC) & `0x01/0x06` (ANR), 3-band EQ `0x01/0x07`, Multipoint `0x01/0x0A`, Auto-Pause `0x01/0x18`, Auto-Answer `0x01/0x1B`, **Firmware Rename** `0x01/0x02` (`OP_SET_GET`, UTF-8 <= 31B).
- **Anker Soundcore:** Standard SPP `00001101`; `08 EE 00 00 00` TX / `09 FF 00 00 01` RX + sum8; State `0x0101`, Battery `0x0103`/`0x0104`, ANC `0x0601`/`0x0681`, Game Mode `0x0187` (`0x1085` Liberty), 8-band Custom EQ `0x0281` (`[FE FE, 8 × (db*10 + 120)]`), Dual-Conn `0x0B84`, Auto-Pause `0x0181`.
- **Sennheiser GAIA v3:** UUID `A2129FF3-081B-4C45-8AFE-469D9C4842EC`; `0xFF 0x03`, vendor `0x0495`; Battery `0x0603`/`0x0602`, ANC `0x1A04`/`0x1A05`, Transparency `0x1804`/`0x1805`, 5-band Custom EQ `0x1003`/`0x1001`, Spatial `0x3604`/`0x3605`, Auto-Pause `0x080C`/`0x080D`, Auto-Answer `0x080A`/`0x080B`.
- **Jabra:** Standard SPP `00001101`; `04 09 seq (type|len) feat16 payload`; Battery `0x1202`, Ambience `0x13BE`, Noise Mode `0x1387`.
- **Google Pixel Buds:** UUID `25E97FF7-24CE-4C4C-8951-F764A708F7B5`; HDLC (`0x7E`, `0x7D` escape, CRC32) + Pigweed RPC (`maestro_pw.Maestro`); Runtime battery stream + settings `13` (ANC), `2` (OHD), `11` (Multipoint), `16` (5-band float User EQ).
- **JBL BLE GATT:** Service `65786365-6c70-6f69-6e74-2e636f6d0000`; `0xAA cmd len payload`; Battery `0x25`, Noise `0x91`, Status `0x21`/`0x22`, EQ `0x40`, Auto-Play `0x35`, Spatial `0x9D`.
- **Logitech G733 / G435 (`LogitechProtocol` + `LogitechUsbManager`):**
  - **G733 (`046D:0AB5 / 0AFE / 0B1F`, USB-OTG HID++ 2.0):** 20-byte long reports `[0x11, 0xFF, ...]`; Battery `0x08 0x0A` (BE mV `resp[4..5]`, charging `resp[6]==0x03`, 8-point G633/G733 curve), Sidetone `0x07 0x1A <0..100>`, RGB Lights `0x04 0x3C` (strips zone `0x01` + logo zone `0x00`), Auto power-off `0x07 0x21 <0..90>`.
  - **G435 (`046D:0ACB`, USB-OTG Vendor FFC0 Report 6 + Bluetooth):** Feature reports `REPORT_ID=6` (`ENTER_BOOTLOADER 01 03` -> `GET_CONNECT 0xE3` -> `GET_MODE 0xE0` / `SET_MODE 0xE1 01` -> 3× `OTA GET_BATTERY 00 31` median -> `finally EXIT_BOOTLOADER 08 03`), exact 22-point Li-Po curve + `G435BatteryEstimator` (smoothing, charging inference, trend/18h spec hours-left). Over Bluetooth: HFP `BluetoothDevice.getBatteryLevel()` + BLE Battery Service (`0x180F` / `0x2A19`).
- **Apple AirPods / Beats note:** Uses raw BR/EDR L2CAP `PSM 0x1001` in `kde-headphones`, which unrooted Android SDK does not permit.
