# realme Link / realness — Protocol & App Documentation

**English** | [Русский](AGENTS.ru.md)

Last updated: 2026-11-23  
Status: supports **13 protocol families across 15 brands** — **realme / OnePlus / OPPO / DIZO** (`TL + OPPOv1`), **HUAWEI / HONOR** (`Huawei SPP`), **HONOR CHOICE** (`HONOR CHOICE SPP`), **Nothing / CMF** (`Nothing SPP`, all 23 models), **Samsung Galaxy Buds** (`Galaxy Buds SPP`), **Sony** (`Sony MDR V1/V2 SPP`), **Bose** (`Bose BMAP SPP`), **Anker Soundcore** (`Soundcore SPP`), **Xiaomi / Redmi / POCO** (`Xiaomi SPP + SAFER+`), **Sennheiser** (`GAIA v3 SPP`), **Jabra** (`Jabra SPP`), **Google Pixel Buds** (`Maestro HDLC + Pigweed RPC`), and **JBL** (`JBL BLE GATT`); **realness** app (`uwu.realness.gitveu`, version `1.8`, `build 9`, native Material 3 Expressive release builds + `realness-tiny` classic compact build). Full list: [SUPPORTED_DEVICES.md](SUPPORTED_DEVICES.md).

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
* **Primary realme vendor UUID**:
  `0000079A-D102-11E1-9B23-00025B00A5A5`
* **OnePlus Buds / OPPO Enco vendor UUID (QuickBuds)**:
  `00001107-D102-11E1-9B23-00025B00A5A5`
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

## 13. Huawei SPP Protocol (based on OpenFreebuds)

Implemented in `HuaweiProtocol.kt` (`realness`) and `HuaweiProto.java` (`tiny-realness`) using [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds):

* **RFCOMM SPP Transport**:
  * Standard SPP UUID: `00001101-0000-1000-8000-00805F9B34FB` (channel 1: FreeBuds 3 / 6i / Pro / Pro 2 / Pro 3 / Pro 4 / Pro 5 / FreeClip / FreeClip 2 / SE 2 / SE 4 / Studio / FreeLace Pro 2 / Honor Earbuds S2);
  * Huawei Private SPP UUID: `96cc203e-5068-46ad-b32d-e316f5e069ba` (channel 16: FreeBuds 4i / 5i / SE / FreeLace Pro / Honor Earbuds 2).
* **Frame Format**: `[0x5A, lenHi, lenLo, 0x00, cmdHi, cmdLo, TLV(type, len, value)..., crcHi, crcLo]` (`CRC16-XMODEM`, total wire length = `length + 5`).
* **Commands**:
  * Battery: read `0x0108` (params `1, 2, 3`), notify `0x0127` (param 2 = `[L, R, Case]`, param 3 = charging flags, param 1 = global battery for Studio/FreeLace);
  * ANC & intensity levels: read `0x2B2A`, write `0x2B04` (modes `0=Off, 1=ANC, 2=Transparency`; ANC levels `1=Comfort, 0=General, 2=Ultra, 3=Dynamic`), legacy event `0x2B03`;
  * Low Latency (Game Mode): `0x2B6C` (read param 2, write param 1);
  * Auto Play/Pause: read `0x2B11`, write `0x2B10`;
  * Sound Quality Preference: read `0x2BA3` (param 2), write `0x2BA2` (param 1);
  * Equalizer: read `0x2B4A` (param 2 = active ID, param 8 = 36-byte custom EQ blocks), write `0x2B49` (param 1);
  * Dual-Device Connection: read `0x2B2F`, write `0x2B2E`, enumerate devices `0x2B31`, change event `0x2B36`;
  * Touch Gestures: double tap `0x0120`/`0x011F`, triple tap `0x0126`/`0x0125`, long press `0x2B17`/`0x2B16`.
* **HONOR CHOICE Earbuds variant** (X8 / X5 / X5 Pro / X3 / X2 / X, based on [noctalia/honor-earbuds](https://github.com/noctalia-dev/community-plugins/tree/main/honor-earbuds)): uses the same `0x5A` + CRC16-XMODEM framing (`0x0108` battery, `0x2B2A` ANC read), with **1-byte** `0x2B04` ANC write (`[0=Off, 1=ANC, 2=Transparency]`) and `0x2701` EQ write (`[0=Balanced, 1=Bass, 2=Vocal]`).

---

## 14. Nothing / CMF & Samsung Galaxy Buds Protocols

### 14.1. Nothing / CMF RFCOMM SPP (`NothingProtocol.kt` / `NothingProto.java`)
Based on [swift-nothing-ear](https://github.com/bestK1ngArthur/swift-nothing-ear), [omarchy-nothing-ear](https://github.com/r-witz/omarchy-nothing-ear), and [kde-headphones](https://github.com/RodiAda/kde-headphones):
* **RFCOMM UUID**: `AEAC4A03-DFF5-498F-843A-34487CF133EB` (+ Standard SPP fallback).
* **Frame Format**: `[0x55, 0x60, 0x01, cmdLo, cmdHi, lenLo, lenHi, opId, payload..., crcLo, crcHi]` (`CRC16/MODBUS`, init `0xFFFF`, poly `0xA001`).
* **Commands**: Handshake `0xC006`/`0x4006`; Battery `0xC007`/`0x4007`/`0xE001` (parts `2=L, 3=R, 4=Case, 6=Main`); ANC `0xC01E`/`0x401E`/`0xE003`/`0xF00F` (`1=High, 2=Mid, 3=Low, 4=Adaptive, 5=Off, 7=Transparency`); Classic EQ `0xC01F`/`0xF010` & ListeningMode EQ `0xC050`/`0xF01D`; In-Ear `0xC00E`/`0xF004`; Low Latency `0xC041`/`0xF040`; Ultra Bass `0xC04E`/`0xF051`.

### 14.2. Samsung Galaxy Buds RFCOMM SPP (`GalaxyProtocol.kt` / `GalaxyProto.java`)
Based on [kde-headphones](https://github.com/RodiAda/kde-headphones):
* **RFCOMM UUIDs**: Modern `2E73A4AD-332D-41FC-90E2-16BEF06523F2`, Legacy Buds (2019) `00001102-0000-1000-8000-00805F9B34FD`, Buds+ `00001101-0000-1000-8000-00805F9B34FB`.
* **Frame Format**: Modern `[0xFD, sizeLo, sizeHi&0x03, msgId, payload..., crcLo, crcHi, 0xDD]` and Legacy `[0xFE, 0x00, size, msgId, payload..., crcLo, crcHi, 0xEE]` (`CRC16-CCITT` over `msgId + payload`, stored little-endian).
* **Messages**: Push `0x61` (`ExtendedStatusUpdated`) & `0x60` (`StatusUpdated`); Handshake `0x88` (`ManagerInfo [01 02 22]`); ANC `0x78` (modern) / `0x98` (Buds Live) / `0x80` (Buds/Buds+); EQ `0x86`; Seamless Connection `0xAF`.

### 14.3. Additional `kde-headphones` Protocols (`SonyProtocol.kt`, `BoseSoundcoreProtocol.kt`, `XiaomiProtocol.kt`, `ExtraProtocols.kt` / `ExtraProtos.java`)
* **Sony MDR (V1/V2)**: UUIDs `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` (V2) / `96CC203E-5068-46AD-B32D-E316F5E069BA` (V1); framing `0x3E..0x3C` with `0x3D` escape, sum8 checksum, ACK `0x01`; Battery `0x22`/`0x23`/`0x25`, NCASM `0x66`..`0x69`, EQ `0x56`..`0x59`.
* **Bose BMAP**: Standard SPP `00001101`; `[block, func, op, len, payload]`; Battery `0x02/0x02`, ANC `0x01/0x06`, Multipoint `0x01/0x07`.
* **Anker Soundcore**: Standard SPP `00001101`; `08 EE 00 00 00` TX / `09 FF 00 00 01` RX + sum8; Status `0x01/0x01`, Battery `0x01/0x03`, ANC `0x06/0x01`/`0x81`, Game Mode `0x01/0x11`.
* **Xiaomi / Redmi / POCO**: UUID `0000FD2D-0000-1000-8000-00805F9B34FB`; `FE DC BA .. EF` + full **SAFER+** challenge-response auth (`0x50`/`0x51`); Battery `0x09`, ANC `0x08`, Ear Detection `0x0E`, Config `0xF2`/`0xF3`/`0xF4` (AutoAnswer `0x03`, Multipoint `0x04`, EQ `0x07`, ANC Strength `0x0B`).
* **Sennheiser GAIA v3**: UUID `A2129FF3-081B-4C45-8AFE-469D9C4842EC`; `0xFF 0x03`, vendor `0x0495`; Battery `0x06/0x02`, ANC `0x05/0x01..0x04`, Spatial `0x06/0x06`.
* **Jabra**: Standard SPP `00001101`; `04 09 seq (type|len) feat16 payload`; Battery `0x0101`, ANC `0x0104`, Multipoint `0x0107`.
* **Google Pixel Buds**: UUID `25E97FF7-24CE-4C4C-8951-F764A708F7B5`; HDLC (`0x7E`, `0x7D` escape, CRC32) + Pigweed RPC (`maestro_pw.Maestro`); Battery stream + settings `11` (ANC), `5` (In-Ear), `21` (Multipoint).
* **JBL BLE GATT**: Service `65786365-6c70-6f69-6e74-2e636f6d0000`; `0xAA cmd len payload`; Battery `0x25`, Noise `0x91`, EQ `0x40`, AutoPlay `0x35`, Spatial `0x9D`.
* **Apple AirPods / Beats note**: Uses raw BR/EDR L2CAP `PSM 0x1001` in `kde-headphones`, which unrooted Android SDK blocks.

---

## 15. realness App Architecture

1. **Stack**: Kotlin, Jetpack Compose (Material3), Coroutines/Flow, MinSdk 26, TargetSdk 34, package `uwu.realness.gitveu` (project folder `realness/`). An ultra-lightweight build `tiny-realness/` (`uwu.realness.gitveu.tiny`) in pure Java 8 without Gradle/AndroidX is also available (`aapt2 -> javac -> R8 -> zipalign -> apksigner`, ~58 KB).
2. **Versioning & Release Builds**:
   * Always increment `versionCode` and `versionName` after every change in `realness/app/build.gradle.kts` and `tiny-realness/AndroidManifest.xml` (current: `versionName = "1.7"`, `versionCode = 8`).
   * All builds are produced in **release** mode (`./gradlew assembleRelease` with R8 minify + shrinkResources, and `./tiny-realness/build.sh`).
   * The signing keystore (`realness-release.keystore`) is **never stored in git** (added to `.gitignore` along with `keystore.properties`) — it is stored in **GitHub Actions Secrets** (`REALNESS_KEYSTORE_BASE64`, `REALNESS_KEYSTORE_PASSWORD`, `REALNESS_KEY_ALIAS`, `REALNESS_KEY_PASSWORD`) and locally on the developer's machine.
   * Compiled `.apk` files are **not committed to git** (`*.apk` is in `.gitignore`). The `.github/workflows/build-release.yml` workflow automatically builds signed `realness <versionName>.apk` and `realness-tiny <versionName>.apk` and publishes them to **GitHub Releases** under tag `v<versionName>`.
3. **Source Code Structure**:
   * `T300Protocol.kt`, `HuaweiProtocol.kt`, `NothingProtocol.kt`, `GalaxyProtocol.kt`, `SonyProtocol.kt`, `BoseSoundcoreProtocol.kt`, `XiaomiProtocol.kt`, `ExtraProtocols.kt`: frame builders/parsers for all 13 protocol families and `BudsModel` capability flags grouped across 15 brands.
   * `T300BleManager.kt`: session-generation-guarded RFCOMM SPP & BLE GATT manager (prevents overlapping connect races, emits `unexpectedDisconnectFlow` for automatic recovery on TWS role switch), streaming packet readers for all 13 protocols, manual model override, async events, and state polling.
   * `BudsViewModel.kt`: reactive `StateFlow` state, duplicate-`ACTION_ACL_CONNECTED`-safe auto-connect, automatic backoff reconnect on transient SPP drops while A2DP stays online, `SharedPreferences` persistence for **last connected device** and **manual model override**, unsupported device detection, `MediaPlayer` demo playback controller.
   * `MainActivity.kt`: Material3 UI — **Model Picker Side Drawer** (`ModalNavigationDrawer` with Automatic + brand-grouped models), unsupported device banner, battery cards, ANC selector (+ intensity levels for Huawei & Nothing), EQ presets (+ custom profiles), touch gesture editor, Spatial Audio / LDAC / LHDC 5.0 / Sound Quality / Dynamic Audio / Ultra Bass / Game Mode / Dual-Device / Seamless Connection / Auto Play/Pause / Auto Answer toggles, L/R usage time stats, collapsible log console, dynamic version and build number.
4. **Localization**:
   * Default language is English (`values/strings.xml`), full Russian localization (`values-ru/strings.xml`), `res/xml/locales_config.xml`.
   * Technical protocol logs are always output in English.
5. **Build & Repository**:
   * GitHub repository: `https://github.com/Gitveu/Realess.git` (`main` branch).
   * Release APKs (`realness <versionName>.apk` and `realness-tiny <versionName>.apk`) are published automatically to GitHub Releases by GitHub Actions.
