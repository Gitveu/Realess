# realme Link / realness — Protocol & App Documentation

**English** | [Русский](AGENTS.ru.md)

Last updated: 2026-11-22  
Status: supports **realme Buds T300**, **realme Buds T200**, and **realme Buds Air 7 Pro**; **realness** app (`uwu.realness.gitveu`, version `1.4`, `build 5`, release builds).

---

## 1. Purpose & Context

The goal of this project is to fully document the TL TWS communication protocol for realme Buds earbuds and build a standalone Android app (**realness**, package `uwu.realness.gitveu`) that communicates directly with the earbuds without realme Link:
1. Reading exact battery levels for left earbud, right earbud, and charging case (including charging state and proper closed-case handling);
2. Controlling and immediately reading noise control modes (ANC: Noise Cancellation, Off, Transparency);
3. Toggling and reading Game Mode (low-latency mode);
4. Reading and switching equalizer presets (Serenade, Original Sound, Pure Bass, Deep Bass), as well as detecting custom EQ profiles;
5. Reading and configuring touch gesture controls for both earbuds (double tap, triple tap, touch & hold);
6. Controlling and reading Spatial Audio (3D Sound) with local demo playback of the original audio samples;
7. Supporting model-specific features: high-definition audio (LDAC on T200 / LHDC 5.0 on Air 7 Pro), Dynamic Audio (T200 / Air 7 Pro), Dual-Device Connection & connection history (T200 / Air 7 Pro), Auto Play/Pause and Auto Answer (Air 7 Pro);
8. Reading cumulative L/R usage time statistics from earbud memory (`0x0128`/`0x8128`);
9. Subscribing to asynchronous headset events and detecting unsupported devices (warning banner for third-party headsets).

Reverse-engineering source artifact: `realme Link.apkm` (package `com.realme.link`, TL TWS stack `com.realme.iot.headset.tl.*`).

---

## 2. Transport Architecture & Auto-Connect

### 2.1. Bluetooth Classic RFCOMM SPP (Primary Transport)

realme Buds earbuds pair with Android as a standard Bluetooth Classic headset (A2DP / HFP). The control data channel operates over **RFCOMM SPP**:
* **Primary realme / OPPO / OnePlus vendor UUID**:
  `0000079A-D102-11E1-9B23-00025B00A5A5`
* **Standard SPP UUID (Fallback)**:
  `00001101-0000-1000-8000-00805F9B34FB`
* **Backup vendor UUIDs**:
  `db764ac8-4b08-7f25-aafe-59d03c27bae4`, `db764ac8-4b08-7f25-aafe-59d03c27bae3`

In `T300BleManager.kt`, connection is established via:
```kotlin
device.createRfcommSocketToServiceRecord(REALME_VENDOR_SPP_UUID)
```
Reads and writes go directly through `socket.inputStream` and `socket.outputStream`.

### 2.2. BLE GATT (Fallback Transport)
If the SPP socket is unavailable, the app supports automatic fallback to BLE GATT via auto-detection of write (`PROPERTY_WRITE` / `PROPERTY_WRITE_NO_RESPONSE`) and notification (`PROPERTY_NOTIFY` / `CCCD 00002902-0000-1000-8000-00805f9b34fb`) characteristics.

### 2.3. Zero-Touch Auto-Discovery & Reconnect
* The app registers a system `BroadcastReceiver` (`ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`, `ACTION_CONNECTION_STATE_CHANGED`).
* When the earbuds are taken out of the case, the phone connects the A2DP profile and the app automatically opens the SPP control channel with no user interaction.
* When both earbuds are placed back into the case, the status automatically switches to `In case / disconnected`.

---

## 3. Packet Format (Wire Frames)

Communication between the phone and earbuds uses two layers: an inner **TL** protocol frame is always wrapped inside an **OPPOv1** transport frame.

### 3.1. OPPOv1 Transport Framing (SOF `0xAA`)
* **Byte 0**: `0xAA` (Start of Frame).
* **Bytes 1..K**: Packet length (7-bit varint, equals `TL_FRAME_LENGTH + 2`). Occupies 1 byte for packets < 128 bytes.
* **Byte K+1**: Flags (`0x00` = single packet, no fragmentation).
* **Byte K+2**: Reserved (`0x00`).
* **Offset K+3..**: Inner TL frame.

### 3.2. Inner TL Frame (TL Wire Frame)
* **Offset 0..1**: Command ID (2 bytes, little-endian).
* **Offset 2**: Device Address (byte `0x00`).
* **Offset 3..4**: Payload Length (2 bytes, little-endian).
* **Offset 5..N**: Command payload.

---

## 4. Battery Level

### 4.1. Request & Response
* **Request command**: `0x0106` (262 decimal, `CMD_BATTERY_REQ`), empty payload.
  * TL Wire: `06 01 00 00 00`
  * OPPOv1 Wire: `AA 07 00 00 06 01 00 00 00`
* **Response command**: `0x8106` (33030 decimal, `CMD_BATTERY_RSP`).

### 4.2. Response Format
Payload: `[status (0x00), count (N), (componentId, value)...]`
* `status`: `0x00` = Success.
* `count`: number of reported slots (typically 2 or 3).
* Each entry (2 bytes):
  * Byte 0 (`componentId`):
    * `0x01` = **Left earbud** (`LEFT_EAR`)
    * `0x02` = **Right earbud** (`RIGHT_EAR`)
    * `0x03` = **Charging case** (`CASE`)
  * Byte 1 (`value`):
    * `value & 0x7F` — battery level (0..100%).
    * `(value & 0x80) != 0` — charging flag (1 = charging).

### 4.3. Offline Case Normalization
When the case is closed or has no earbud inside, the firmware sends `03 00` (slot 3, level 0, not charging). This indicates the case is offline rather than at 0% charge. The parser normalizes this value to `-1` (the UI displays a dash `—` and omits the inactive case from the battery log).

---

## 5. Noise Control (ANC)

### 5.1. Query Current Mode
* **Command**: `0x010C` (268 decimal, `CMD_ANC_STATE_REQ`), payload: `01 01`.
  * TL Wire: `0C 01 00 02 00 01 01`
  * OPPOv1 Wire: `AA 07 00 00 0C 01 00 02 00 01 01`
* **Response**: `0x810C` (33036 decimal, `CMD_ANC_STATE_RSP`).
  * Payload: `[status (0x00), action (0x01), type (0x01), noiseStatus]`

### 5.2. Set Mode
* **Command**: `0x0404` (1028 decimal, `CMD_ANC_SET`), payload: `01 01 <noiseStatus>`.
* **`noiseStatus` values**:
  * `0x08` = **Noise Cancellation** (Wire: `AA 0A 00 00 04 04 00 03 00 01 01 08`)
  * `0x01` = **Off** (Wire: `AA 0A 00 00 04 04 00 03 00 01 01 01`)
  * `0x02` = **Transparency** (Wire: `AA 0A 00 00 04 04 00 03 00 01 01 02`)
* **Ack response**: `0x8404` (33796 decimal, `CMD_ANC_RSP`), payload: `[status]` (`0x00` = Success).

---

## 6. Asynchronous Events & Subscription

### 6.1. Event Registration
* **Command**: `0x0201` (513 decimal, `CMD_NOTIFICATION_REGISTER`).
* **Payload**: `[eventType]` (1 byte each):
  * `0x03` — ANC mode change events (toggled via earbud touch sensor);
  * `0x05` — feature switch events (Game Mode);
  * `0x01` — battery events;
  * `0x02` — earbud status (in-ear / in-case).
* **Response**: `0x8201` (33281 decimal), payload `[status]` (`0x00` = Success).

### 6.2. Incoming Device Events (`CMD_EVENT 0x0204`)
All device-initiated events arrive with opcode **`0x0204`** (516 decimal):
* **ANC event (`payload[0] == 0x03`)**: active mode is in `payload[3]` (when `payload.size >= 4`) or `payload[2]`.
* **Game Mode event (`payload[0] == 0x05`)**: `[05, value]` (`value == 1` ON, `0` OFF).
* **Other events**: trigger a debounced full-state refresh via `scheduleStateRefresh(120 ms)`.

---

## 7. Game Mode

* **Subsystem**: `SwitchFeature` (ID `0x06`).
* **Query**: `0x010D` (269 decimal), payload: `[01, 06]`.
  * OPPOv1 Wire: `AA 07 00 00 0D 01 00 02 00 01 06`
* **Response**: `0x810D` (33037 decimal), payload: `[status (0x00), count, (06, value)...]` (`value == 1` ON, `0` OFF).
* **Set**: `0x0403` (1027 decimal), payload: `[06, 01]` (ON) or `[06, 00]` (OFF).
  * Enable Wire: `AA 07 00 00 03 04 00 02 00 06 01`
  * Disable Wire: `AA 07 00 00 03 04 00 02 00 06 00`
* **Ack**: `0x8403` (33795 decimal), payload `[status]`.

---

## 8. Equalizer (Sound Effects)

### 8.1. Reading & Setting Presets
* **Query preset**: `0x010F` (271 decimal), empty payload.
  * OPPOv1 Wire: `AA 05 00 00 0F 01 00 00 00`
* **Response**: `0x810F` (33039 decimal), payload: `[status (0x00), eqId]`.
* **Set preset**: `0x0406` (1030 decimal), payload: `[eqId]`.
* **Ack**: `0x8406` (33798 decimal), payload `[status]`.

### 8.2. Preset Table
| Equalizer Preset | Wire `eqId` | Set OPPOv1 Wire |
|---|:---:|---|
| **Serenade** (Clear Vocals) | `0x02` | `AA 06 00 00 06 04 00 01 00 02` |
| **Original Sound** | `0x00` | `AA 06 00 00 06 04 00 01 00 00` |
| **Pure Bass** | `0x03` | `AA 06 00 00 06 04 00 01 00 03` |
| **Deep Bass** | `0x01` | `AA 06 00 00 06 04 00 01 00 01` |
| **Custom EQ** | `> 0x03` / `0xFF` | Detected via command `0x0122` |

### 8.3. Custom Profiles
* **Query profile list**: `0x0122` (290 decimal), response `0x8122` (33058 decimal).
* Contains `isSelected` active flag, `eqId`, UTF-8 profile name, and frequency bands. When a custom profile is active, the UI highlights a badge with its name.

---

## 9. Touch Controls (Button Settings)

### 9.1. Reading Settings
* **Command**: `0x0108` (264 decimal), payload (15 bytes):
  `0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00`
  * OPPOv1 Wire: `AA 16 00 00 08 01 00 0F 00 0E 01 03 04 05 06 0B 0C 08 0D 11 1F 1A 1B 00`
* **Response**: `0x8108` (33032 decimal), payload: `[status (0x00), count (N), (deviceType, button, actionId, functionId)...]`.
  * Each entry (4 bytes):
    * `deviceType`: `0x01` = Left earbud, `0x02` = Right earbud;
    * `button`: `0x01` (touch panel);
    * `actionId`: `0x02` = Double tap, `0x03` = Triple tap, `0x04` = Touch & hold (1 s);
    * `functionId`: assigned action ID.

### 9.2. Updating a Setting
* **Command**: `0x0401` (1025 decimal), payload (5 bytes):
  `[01, deviceType, 01, actionId, functionId]`
  * OPPOv1 Wire: `AA 0C 00 00 01 04 00 05 00 01 <deviceType> 01 <actionId> <functionId>`
* **Ack**: `0x8401` (33793 decimal), payload `[status]`.

### 9.3. Gesture Function Table
| Action | Wire `functionId` | Allowed Gestures |
|---|:---:|---|
| **Play / Pause** | `0x01` | Double tap |
| **Next Track** | `0x06` | Double tap, Triple tap |
| **Previous Track** | `0x05` | Triple tap |
| **Voice Assistant** | `0x03` | Double tap, Triple tap, Touch & hold |
| **Noise Control (ANC)** | `0x08` | Touch & hold |
| **Switch Devices** | `0x0D` | Triple tap |
| **Game Mode** | `0x11` | Triple tap, Touch & hold |
| **Volume Up** | `0x0B` | Triple tap |
| **Volume Down** | `0x0C` | Triple tap |
| **None** | `0x00` | Any gesture |

---

## 10. Spatial Audio (3D Sound)

* **Subsystem**: `SwitchFeature` (ID `0x1B` / 27 decimal).
* **Query**: `0x010D` (269 decimal), payload: `[01, 1B]`.
  * OPPOv1 Wire: `AA 09 00 00 0D 01 00 02 00 01 1B`
* **Response**: `0x810D` (33037 decimal), payload: `[status (0x00), count, (1B, value)]` (`value == 1` ON, `0` OFF).
* **Set**: `0x0403` (1027 decimal), payload: `[1B, 01]` (ON) or `[1B, 00]` (OFF).
  * Enable Wire: `AA 09 00 00 03 04 00 02 00 1B 01`
  * Disable Wire: `AA 09 00 00 03 04 00 02 00 1B 00`
* **Ack**: `0x8403` (33795 decimal), payload `[status]`.
* **Demo audio from realme Link**: demo playback is a **local phone feature** (via Android `MediaPlayer`), not a command sent to the earbuds. The app bundles the original tracks `headset_spatial_audio_fix.mp3` (3D) and `headset_spatial_audio_off.m4a` (stereo) with instant switching on the fly.

---

## 11. Model-Specific Features: realme Buds T200 & Buds Air 7 Pro `[from code]`

Based on static analysis of `ItemFactory`, `TLHeadsetConnectionImp`, `PollCommandManager`, `SetCommandManager`, `NotificationCommandManager`, and `CommandUtil` in `realme Link.apkm` (features are strictly gated by `BudsModel`, since T300 firmware returns `status=0, val=0` for unsupported `SwitchFeature` IDs):

### 11.1. High-Definition Audio (LDAC on T200 / LHDC 5.0 on Air 7 Pro)
* **Subsystem**: `SwitchFeature` (ID `0x18` / 24 decimal, `settingType = 31`, `ItemFactory.I/J`, `TLHeadsetConnectionImp.J5`).
* **Query**: `0x010D`, payload `[01, 18]` (OPPOv1 Wire: `AA 09 00 00 0D 01 00 02 00 01 18`).
* **Response**: `0x810D`, payload `[00, count, (18, value)...]` (`value == 1` ON, `0` OFF).
* **Set**: `0x0403`, payload `[18, 01]` / `[18, 00]` (OPPOv1 Wire: `AA 09 00 00 03 04 00 02 00 18 01`).
* **Ack**: `0x8403`, payload `[status]`.

### 11.2. Dynamic Audio (T200 / Air 7 Pro)
* **Subsystem**: `SwitchFeature` (ID `0x1D` / 29 decimal, `settingType = 36`, `TLHeadsetConnectionImp.E5`).
* **Query**: `0x010D`, payload `[01, 1D]` (OPPOv1 Wire: `AA 09 00 00 0D 01 00 02 00 01 1D`).
* **Response**: `0x810D`, payload `[00, count, (1D, value)...]` (`value == 1` ON, `0` OFF).
* **Set**: `0x0403`, payload `[1D, 01]` / `[1D, 00]` (OPPOv1 Wire: `AA 09 00 00 03 04 00 02 00 1D 01`).
* **Ack**: `0x8403`, payload `[status]`.

### 11.3. Dual-Device Connection (T200 / Air 7 Pro)
* **Toggle (`SwitchFeature` `0x11` / 17 decimal, `settingType = 20`, `TLHeadsetConnectionImp.D5`)**:
  * **Query**: `0x010D`, payload `[01, 11]` (OPPOv1 Wire: `AA 09 00 00 0D 01 00 02 00 01 11`).
  * **Response**: `0x810D`, payload `[00, count, (11, value)...]`.
  * **Set**: `0x0403`, payload `[11, 01]` / `[11, 00]` (OPPOv1 Wire: `AA 09 00 00 03 04 00 02 00 11 01`).
  * **Ack**: `0x8403`, payload `[status]`.
* **Connection history / device list (`PollCommandManager.w`, `CommandUtil.h`)**:
  * **Query**: `0x0112` (274 decimal), empty payload (OPPOv1 Wire: `AA 07 00 00 12 01 00 00 00`).
  * **Response**: `0x8112` (33042 decimal), payload `[status (0x00), count, (6B MAC, entryLen, connectState, flag, nameLen, UTF-8 name, ...)...]`.
  * **Event**: `0x0204` with type `0x06` (`NotificationCommandManager.k`, case 6).

### 11.4. Auto Play/Pause — Wear Detection (Air 7 Pro) `[from code]`
* **Subsystem**: `SwitchFeature` (ID `0x04` / 4 decimal, `settingType = 11`, `ItemFactory.i0/B`, `TLHeadsetConnectionImp.L5`).
* **Query**: `0x010D`, payload `[01, 04]` (OPPOv1 Wire: `AA 09 00 00 0D 01 00 02 00 01 04`).
* **Response**: `0x810D`, payload `[00, count, (04, value)...]` (`value == 1` ON, `0` OFF).
* **Set**: `0x0403`, payload `[04, 01]` / `[04, 00]` (OPPOv1 Wire: `AA 09 00 00 03 04 00 02 00 04 01`).
* **Ack**: `0x8403`, payload `[status]`.

### 11.5. Auto Answer Calls (Air 7 Pro) `[from code]`
* **Subsystem**: `SwitchFeature` (ID `0x08` / 8 decimal, `settingType = 16`, `ItemFactory.o`, `TLHeadsetConnectionImp.A5`).
* **Query**: `0x010D`, payload `[01, 08]` (OPPOv1 Wire: `AA 09 00 00 0D 01 00 02 00 01 08`).
* **Response**: `0x810D`, payload `[00, count, (08, value)...]` (`value == 1` ON, `0` OFF).
* **Set**: `0x0403`, payload `[08, 01]` / `[08, 00]` (OPPOv1 Wire: `AA 09 00 00 03 04 00 02 00 08 01`).
* **Ack**: `0x8403`, payload `[status]`.

### 11.6. L / R Usage Time Statistics
* **Query**: `0x0128` (296 decimal), empty payload (OPPOv1 Wire: `AA 07 00 00 28 01 00 00 00`).
* **Response**: `0x8128` (33064 decimal), payload `[status (0x00), count, (roleByte 1B, totalMinutes 4B uint32 LE)...]` (`roleByte`: `0x01` = L, `0x02` = R).

### 11.7. realme Link App-Side Features (Intentionally Omitted)
* **MindFlow** (`ItemFactory.r0`, `settingType = 0`) — local/cloud O Relax white-noise player;
* **Golden Hearing / Hearing Test**, **AI Translate**, **Earbud Fit Test**, **Find My Phone** — interactive/cloud features of the official realme Link app.

---

## 12. Protocol Summary Table (realme Buds T300 / T200 / Air 7 Pro)

All frames are transmitted over RFCOMM SPP (`UUID: 0000079A-D102-11E1-9B23-00025B00A5A5`) wrapped in OPPOv1 (`SOF 0xAA`):

| Feature | TX Cmd | TX Payload | RX Cmd | RX Payload | Status |
|---|:---:|---|:---:|---|:---:|
| **Battery query** | `0x0106` | *empty* | `0x8106` | `[00, count, (slot, level\|flag)...]` | Verified (log) |
| **Set ANC** | `0x0404` | `01 01 <mode>` | `0x8404` | `[00]` | Verified (log) |
| **Query ANC** | `0x010C` | `01 01` | `0x810C` | `[00, 01, 01, <mode>]` | Verified (log) |
| **Query Game Mode** | `0x010D` | `01 06` | `0x810D` | `[00, count, (06, val)...]` | Verified (log) |
| **Set Game Mode** | `0x0403` | `06 <01/00>` | `0x8403` | `[00]` | Verified (log) |
| **Query Spatial Audio** | `0x010D` | `01 1B` | `0x810D` | `[00, count, (1B, val)...]` | Verified (log) |
| **Set Spatial Audio** | `0x0403` | `1B <01/00>` | `0x8403` | `[00]` | Verified (log) |
| **Query LDAC / LHDC 5.0** | `0x010D` | `01 18` | `0x810D` | `[00, count, (18, val)...]` | Verified (T200) / `[from code]` |
| **Set LDAC / LHDC 5.0** | `0x0403` | `18 <01/00>` | `0x8403` | `[00]` | Verified (T200) / `[from code]` |
| **Query Dynamic Audio** | `0x010D` | `01 1D` | `0x810D` | `[00, count, (1D, val)...]` | Verified (T200) / `[from code]` |
| **Set Dynamic Audio** | `0x0403` | `1D <01/00>` | `0x8403` | `[00]` | Verified (T200) / `[from code]` |
| **Query Dual Connection** | `0x010D` | `01 11` | `0x810D` | `[00, count, (11, val)...]` | Verified (T200) / `[from code]` |
| **Set Dual Connection** | `0x0403` | `11 <01/00>` | `0x8403` | `[00]` | Verified (T200) / `[from code]` |
| **MultiConnect device list** | `0x0112` | *empty* | `0x8112` | `[00, count, (MAC, len, state, ...)]` | Verified (T200) |
| **Query Auto Play/Pause (Air 7 Pro)** | `0x010D` | `01 04` | `0x810D` | `[00, count, (04, val)...]` | `[from code]` |
| **Set Auto Play/Pause** | `0x0403` | `04 <01/00>` | `0x8403` | `[00]` | `[from code]` |
| **Query Auto Answer (Air 7 Pro)** | `0x010D` | `01 08` | `0x810D` | `[00, count, (08, val)...]` | `[from code]` |
| **Set Auto Answer** | `0x0403` | `08 <01/00>` | `0x8403` | `[00]` | `[from code]` |
| **L/R usage time stats** | `0x0128` | *empty* | `0x8128` | `[00, count, (role, min4B LE)...]` | `[from code]` |
| **Query EQ preset** | `0x010F` | *empty* | `0x810F` | `[00, <eqId>]` | Verified (log) |
| **Set EQ preset** | `0x0406` | `<eqId>` | `0x8406` | `[00]` | Verified (log) |
| **Query custom EQ** | `0x0122` | *empty* | `0x8122` | `[00, count, (selected, ..., name)...]` | `[from code]` |
| **Query button settings** | `0x0108` | `0E 01 03 04 ... 00` | `0x8108` | `[00, count, (dev, 1, act, func)...]` | `[from code]` |
| **Set button setting** | `0x0401` | `01 dev 01 act func` | `0x8401` | `[00]` | `[from code]` |
| **Subscribe to events** | `0x0201` | `<eventType>` | `0x8201` | `[00]` | `[from code]` |
| **Async events** | — | — | `0x0204` | `[03, ...]`, `[05, val]`, `[06, ...]` | Verified (log) |

---

## 13. realness App Architecture

1. **Stack**: Kotlin, Jetpack Compose (Material3), Coroutines/Flow, MinSdk 26, TargetSdk 34, package `uwu.realness.gitveu`. An ultra-lightweight build `tiny-realness/` (`uwu.realness.gitveu.tiny`) in pure Java 8 without Gradle/AndroidX is also available (`aapt2 -> javac -> R8 -> zipalign -> apksigner`, ~33 KB).
2. **Versioning & Release Builds**:
   * Always increment `versionCode` and `versionName` after every change in `BudsT300Controller/app/build.gradle.kts` and `tiny-realness/AndroidManifest.xml` (current: `versionName = "1.4"`, `versionCode = 5`).
   * All builds are produced in **release** mode (`./gradlew assembleRelease` with R8 minify + shrinkResources, and `./tiny-realness/build.sh`).
   * The signing keystore (`realness-release.keystore`) is **never stored in git** (added to `.gitignore` along with `keystore.properties`) — it is stored in **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) and locally on the developer's machine.
   * Compiled `.apk` files are **not committed to git** (`*.apk` is in `.gitignore`). The `.github/workflows/build-release.yml` workflow automatically builds signed `realness <versionName>.apk` and `realness-tiny <versionName>.apk` and publishes them to **GitHub Releases** under tag `v<versionName>`.
3. **Source Code Structure**:
   * `T300Protocol.kt`: TL + OPPOv1 frame builder and parser, `BudsModel` with capability flags (`BUDS_T300`, `BUDS_T200`, `BUDS_AIR_7_PRO`).
   * `T300BleManager.kt`: RFCOMM SPP socket management (and GATT fallback), streaming packet reader, command queue, `0x0201`/`0x0204` notification subscription, debounced state sync.
   * `BudsViewModel.kt`: reactive `StateFlow` state, auto-connect on Bluetooth events, unsupported device detection, `MediaPlayer` demo playback controller.
   * `MainActivity.kt`: Material3 UI — unsupported device banner, battery cards, ANC selector, EQ presets, touch gesture editor, Spatial Audio / LDAC / LHDC 5.0 / Dynamic Audio / Game Mode / Dual-Device Connection / Auto Play/Pause / Auto Answer toggles, L/R usage time stats, collapsible log console, dynamic version and build number.
4. **Localization**:
   * Default language is English (`values/strings.xml`), full Russian localization (`values-ru/strings.xml`), `res/xml/locales_config.xml`.
   * Technical protocol logs are always output in English.
5. **Build & Repository**:
   * GitHub repository: `https://github.com/Gitveu/realess-t300.git` (`main` branch).
   * Release APKs (`realness <versionName>.apk` and `realness-tiny <versionName>.apk`) are published automatically to GitHub Releases by GitHub Actions.
