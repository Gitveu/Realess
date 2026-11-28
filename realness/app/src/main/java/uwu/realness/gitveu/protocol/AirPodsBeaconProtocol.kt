package uwu.realness.gitveu.protocol

import uwu.realness.gitveu.protocol.BudsProtocol.AncMode
import uwu.realness.gitveu.protocol.BudsProtocol.BatteryInfo
import uwu.realness.gitveu.protocol.BudsProtocol.ComponentRole
import uwu.realness.gitveu.protocol.BudsProtocol.EqPreset

/**
 * Passive Apple Continuity BLE Proximity Pairing beacon decoder (Company ID 0x004C, type 0x07)
 * supporting both genuine Apple AirPods / Beats and Chinese AirPods replicas (Airoha / Huilian /
 * Bluetrum / JL / Wildberries "A.Pods / Air Pro 3 / Air 4" clones).
 *
 * Also includes Airoha RACE protocol framing (auracast-research/race-toolkit) for active ANC,
 * battery, game mode, and SDK chip readout on Airoha 1562AE / 1562F / 1571AM replicas without FlyCC.
 */
object AirPodsBeaconProtocol {

    const val APPLE_COMPANY_ID = 0x004C
    const val PROXIMITY_TYPE: Byte = 0x07
    // Genuine Apple frames are >= 25 bytes (len 0x19); many JL/Bluetrum/Huilian clones emit 10..24 bytes
    const val MIN_GENUINE_LENGTH = 25
    const val MIN_CLONE_LENGTH = 10

    data class BeaconTelemetry(
        val modelCode: Int,
        val modelName: String,
        val isSingleBattery: Boolean,
        val isLikelyReplica: Boolean,
        val leftLevel: Int,       // 0..100 or -1
        val rightLevel: Int,      // 0..100 or -1
        val caseLevel: Int,       // 0..100 or -1
        val leftCharging: Boolean,
        val rightCharging: Boolean,
        val caseCharging: Boolean,
        val leftInEar: Boolean,
        val rightInEar: Boolean,
        val lidOpen: Boolean,
        val lidCounter: Int
    )

    fun parseManufacturerData(data: ByteArray?, exactMacMatch: Boolean = false): BeaconTelemetry? {
        if (data == null || data.size < MIN_CLONE_LENGTH) return null
        if (data[0] != PROXIMITY_TYPE) return null
        // Byte 2 is 0x01 for Proximity Pairing battery telemetry; 0x00 is a pairing/MAC frame (not battery!)
        if ((data[2].toInt() and 0xFF) != 0x01) return null

        val modelCode = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val knownModel = resolveModel(modelCode)
        // Accept genuine Apple model IDs as well as clone IDs (0xXX20 or byte-swapped 0x20XX)
        val isCloneModelCode = knownModel == null &&
            ((modelCode and 0xFF) == 0x20 || ((modelCode ushr 8) and 0xFF) == 0x20)
        if (knownModel == null && !isCloneModelCode) return null

        val status = data[5].toInt() and 0xFF
        val flipped = (status and 0x02) == 0

        val podsByte = data[6].toInt() and 0xFF
        val caseAndCharge = data[7].toInt() and 0xFF
        val lidByte = data[8].toInt() and 0xFF

        val rawPrimary = (podsByte ushr 4) and 0x0F
        val rawSecondary = podsByte and 0x0F
        val rawLeft = if (flipped) rawPrimary else rawSecondary
        val rawRight = if (flipped) rawSecondary else rawPrimary
        val rawCase = caseAndCharge and 0x0F

        val chargeFlags = (caseAndCharge ushr 4) and 0x0F
        val primaryCharging = (chargeFlags and 0x01) != 0
        val secondaryCharging = (chargeFlags and 0x02) != 0
        val leftCharging = if (flipped) primaryCharging else secondaryCharging
        val rightCharging = if (flipped) secondaryCharging else primaryCharging
        val caseCharging = (chargeFlags and 0x04) != 0

        val leftInEar = if (flipped) (status and 0x08) != 0 else (status and 0x02) != 0
        val rightInEar = if (flipped) (status and 0x02) != 0 else (status and 0x08) != 0

        val lidOpen = (lidByte and 0x08) == 0
        val lidCounter = lidByte and 0x07

        val leftPct = decodeBatteryNibble(rawLeft)
        val rightPct = decodeBatteryNibble(rawRight)
        val casePct = decodeBatteryNibble(rawCase)

        // Heuristic replica detection:
        // 1. BLE advertising MAC equals Classic BR/EDR MAC (genuine Apple AirPods ALWAYS use a rotating RPA)
        // 2. Truncated payload (< 25 bytes, missing Apple's encrypted iCloud tail)
        // 3. Non-Apple model code (e.g. "AirPods Pro 3" / swapped endianness)
        // 4. All-zero or all-0xFF tail bytes where genuine AirPods broadcast encrypted random tokens
        val tailAllUniform = if (data.size >= MIN_GENUINE_LENGTH) {
            val first = data[14]
            (14 until 24).all { data[it] == first }
        } else {
            true
        }
        val isLikelyReplica = exactMacMatch || data.size < MIN_GENUINE_LENGTH || isCloneModelCode || tailAllUniform

        val modelName = knownModel?.first ?: "AirPods Replica (0x%04X)".format(modelCode)
        val isSingle = knownModel?.second ?: false

        val singleLvl = if (isSingle) {
            leftPct.takeIf { it in 0..100 } ?: rightPct
        } else {
            -1
        }

        return BeaconTelemetry(
            modelCode = modelCode,
            modelName = modelName,
            isSingleBattery = isSingle,
            isLikelyReplica = isLikelyReplica,
            leftLevel = if (isSingle) singleLvl else leftPct,
            rightLevel = if (isSingle) singleLvl else rightPct,
            caseLevel = if (isSingle) -1 else casePct,
            leftCharging = if (isSingle) (leftCharging || rightCharging) else leftCharging,
            rightCharging = if (isSingle) (leftCharging || rightCharging) else rightCharging,
            caseCharging = if (isSingle) false else caseCharging,
            leftInEar = leftInEar,
            rightInEar = rightInEar,
            lidOpen = lidOpen,
            lidCounter = lidCounter
        )
    }

    /**
     * Detects Chinese AirPods clone secondary frames (`07 0F 00 <model2B> <6-byte BR/EDR MAC>...`),
     * e.g. JieLi/Bluetrum clones broadcasting their own public MAC inside Apple 0x004C manufacturer data.
     * Returns the spoofed model name when matched (so the caller can flag `isLikelyReplica` without
     * misinterpreting the MAC bytes as battery telemetry).
     */
    fun parseCloneMacFrame(data: ByteArray?, activeBtMac: String?): String? {
        if (data == null || data.size < 11) return null
        if (data[0] != PROXIMITY_TYPE || (data[2].toInt() and 0xFF) != 0x00) return null
        val modelCode = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val macBytes = activeBtMac?.split(":")?.mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (macBytes != null && macBytes.size == 6) {
            val forwardMatch = (0 until 6).all { data[5 + it] == macBytes[it] }
            val reverseMatch = (0 until 6).all { data[5 + it] == macBytes[5 - it] }
            if (!forwardMatch && !reverseMatch) return null
        }
        return resolveModel(modelCode)?.first ?: "AirPods Replica (0x%04X)".format(modelCode)
    }

    private fun decodeBatteryNibble(nibble: Int): Int {
        return when {
            nibble in 0..10 -> (nibble * 10).coerceIn(0, 100)
            nibble in 11..14 -> 100 // Some JL/Bluetrum clones report 0x0B..0x0E for full charge
            else -> -1              // 0x0F = disconnected / unavailable
        }
    }

    private fun resolveModel(code: Int): Pair<String, Boolean>? = when (code) {
        0x0220 -> "AirPods" to false
        0x0F20 -> "AirPods 2" to false
        0x1320 -> "AirPods 3" to false
        0x1920 -> "AirPods 4" to false
        0x1B20 -> "AirPods 4 (ANC)" to false
        0x0E20 -> "AirPods Pro" to false
        0x1420 -> "AirPods Pro 2" to false
        0x2420 -> "AirPods Pro 2 (USB-C)" to false
        0x0A20 -> "AirPods Max" to true
        0x1F20 -> "AirPods Max (USB-C)" to true
        0x0B20 -> "Powerbeats Pro" to false
        0x1120 -> "Beats Studio Buds" to false
        0x1620 -> "Beats Studio Buds+" to false
        0x1220 -> "Beats Fit Pro" to false
        0x0C20 -> "Beats Solo Pro" to true
        0x0620 -> "Beats Solo3" to true
        0x2520 -> "Beats Solo 4" to true
        0x2620 -> "Beats Solo Buds" to false
        0x0920 -> "Beats Studio3" to true
        0x1720 -> "Beats Studio Pro" to true
        0x1020 -> "Beats Flex" to true
        0x0520 -> "BeatsX" to true
        0x0320 -> "Powerbeats3" to true
        0x0D20 -> "Powerbeats 4" to true
        else -> null
    }
}

/**
 * Airoha RACE (Remote Access Control Engine) protocol used by Airoha AB1562AE / AB1562F /
 * AB1562E / AB1571AM AirPods replicas (TigerBuilder / HR / FlyCC / CloudCC).
 *
 * Frame format (verified from auracast-research/race-toolkit):
 *   [0x05, type (0x5A=CMD, 0x5B=RSP, 0x5D=IND), lenLo, lenHi, cmdLo, cmdHi, payload...]
 *   where len = 2 + payload.size (includes cmdId).
 */
object AirohaRaceProtocol {
    const val HEAD: Byte = 0x05
    const val TYPE_CMD: Byte = 0x5A
    const val TYPE_RSP: Byte = 0x5B
    const val TYPE_IND: Byte = 0x5D

    const val CMD_READ_SDK_VERSION: Int = 0x0301
    const val CMD_GET_BATTERY: Int = 0x0CD6
    const val CMD_GAME_MODE: Int = 0x0E06
    const val CMD_ANC_PASSTHROUGH: Int = 0x1200
    const val CMD_GET_BUILD_VERSION: Int = 0x1E08

    data class Packet(val type: Int, val cmdId: Int, val payload: ByteArray)

    fun buildPacket(cmdId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 2 + payload.size
        val out = ByteArray(6 + payload.size)
        out[0] = HEAD
        out[1] = TYPE_CMD
        out[2] = (len and 0xFF).toByte()
        out[3] = ((len ushr 8) and 0xFF).toByte()
        out[4] = (cmdId and 0xFF).toByte()
        out[5] = ((cmdId ushr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 6, payload.size)
        return out
    }

    fun buildSdkVersionReq(): ByteArray = buildPacket(CMD_READ_SDK_VERSION)
    fun buildBuildVersionReq(): ByteArray = buildPacket(CMD_GET_BUILD_VERSION)

    /** Query agent (0x00) or partner (0x01) battery level (0..100%). */
    fun buildBatteryReq(isPartner: Boolean): ByteArray =
        buildPacket(CMD_GET_BATTERY, byteArrayOf(if (isPartner) 0x01 else 0x00))

    fun buildAncStatusReq(): ByteArray = buildPacket(CMD_ANC_PASSTHROUGH, byteArrayOf(0x0A))

    fun buildAncSet(mode: AncMode): ByteArray = when (mode) {
        AncMode.OFF -> buildPacket(CMD_ANC_PASSTHROUGH, byteArrayOf(0x01))
        AncMode.NOISE_REDUCTION -> buildPacket(CMD_ANC_PASSTHROUGH, byteArrayOf(0x00, 0x01, 0x00))
        AncMode.TRANSPARENCY -> buildPacket(CMD_ANC_PASSTHROUGH, byteArrayOf(0x00, 0x04, 0x01))
    }

    fun buildGameModeSet(enabled: Boolean): ByteArray =
        buildPacket(CMD_GAME_MODE, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val out = mutableListOf<Packet>()
        var off = 0
        while (off + 6 <= buf.size) {
            if (buf[off] != HEAD) {
                off++
                continue
            }
            val type = buf[off + 1].toInt() and 0xFF
            if (type != 0x5A && type != 0x5B && type != 0x5C && type != 0x5D) {
                off++
                continue
            }
            val len = (buf[off + 2].toInt() and 0xFF) or ((buf[off + 3].toInt() and 0xFF) shl 8)
            if (len < 2 || len > 512) {
                off++
                continue
            }
            val fullLen = 4 + len
            if (off + fullLen > buf.size) break
            val cmdId = (buf[off + 4].toInt() and 0xFF) or ((buf[off + 5].toInt() and 0xFF) shl 8)
            val payload = if (len > 2) buf.copyOfRange(off + 6, off + fullLen) else ByteArray(0)
            out.add(Packet(type, cmdId, payload))
            off += fullLen
        }
        return out to (if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0))
    }

    fun parseAsciiInfo(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val cleaned = payload
            .dropWhile { it.toInt() == 0 }
            .takeWhile { it.toInt() != 0 }
            .filter { it in 0x20..0x7E }
            .toByteArray()
        return String(cleaned, Charsets.US_ASCII).trim().takeIf { it.length >= 2 }
    }

    fun parseBattery(payload: ByteArray): BatteryInfo? {
        // Common Airoha RACE 0x0CD6 response: [status(0=ok), agentOrPartner(0/1), level(0..100)]
        if (payload.size >= 3 && payload[0].toInt() == 0) {
            val isPartner = (payload[1].toInt() and 0xFF) == 1
            val lvl = payload[2].toInt() and 0xFF
            if (lvl in 0..100) {
                return BatteryInfo(
                    role = if (isPartner) ComponentRole.RIGHT_EAR else ComponentRole.LEFT_EAR,
                    level = lvl,
                    isCharging = false
                )
            }
        } else if (payload.size >= 2) {
            val lvl = payload[1].toInt() and 0xFF
            if (lvl in 0..100) {
                return BatteryInfo(ComponentRole.LEFT_EAR, lvl, false)
            }
        }
        return null
    }

    fun parseAncMode(payload: ByteArray): AncMode? {
        if (payload.size < 2) return null
        val sub = payload[0].toInt() and 0xFF
        val statusOrMode = payload[1].toInt() and 0xFF
        return when (sub) {
            0x01 -> AncMode.OFF
            0x00 -> if (payload.size >= 4 && (payload[3].toInt() and 0xFF) == 0x01) AncMode.TRANSPARENCY else AncMode.NOISE_REDUCTION
            0x0A -> when {
                payload.size >= 3 && (payload[2].toInt() and 0xFF) == 0x00 -> AncMode.OFF
                payload.size >= 4 && (payload[3].toInt() and 0xFF) == 0x01 -> AncMode.TRANSPARENCY
                statusOrMode == 0x00 -> AncMode.NOISE_REDUCTION
                else -> null
            }
            else -> null
        }
    }
}

/**
 * Huilian / StarFun `C0` command channel protocol (verified from spacedouut/OpenHuilian)
 * used by Huilian 247 / 277 / 377 / 377H3 / H2S Ultra / H3Pro Max3 AirPods replicas.
 *
 * Transport: Bluetooth Classic SPP (standard UUID 00001101 / RFCOMM channel 3).
 * Request:  [0xC0, cmd, payload...]
 * Response: [0xC0, cmd, len, payload...]
 */
object HuilianProtocol {
    const val MAGIC: Byte = 0xC0.toByte()

    const val CMD_MODEL_CODE: Int = 0x03
    const val CMD_MODEL_STR: Int = 0x04
    const val CMD_BATTERY: Int = 0x09
    const val CMD_EQ_GET: Int = 0x52
    const val CMD_EQ_SET: Int = 0x62
    const val CMD_EAR_DETECT_GET: Int = 0x55
    const val CMD_EAR_DETECT_SET: Int = 0x65
    const val CMD_ANC_GET: Int = 0x59
    const val CMD_ANC_SET: Int = 0x69
    const val CMD_GAME_MODE_GET: Int = 0x5E
    const val CMD_GAME_MODE_SET: Int = 0x6E
    const val CMD_RENAME_SET: Int = 0x64

    data class Packet(val cmd: Int, val payload: ByteArray)

    fun buildRequest(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = MAGIC
        out[1] = (cmd and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    fun buildModelReq(): ByteArray = buildRequest(CMD_MODEL_CODE)
    fun buildModelStrReq(): ByteArray = buildRequest(CMD_MODEL_STR)
    fun buildBatteryReq(): ByteArray = buildRequest(CMD_BATTERY)
    fun buildAncReq(): ByteArray = buildRequest(CMD_ANC_GET)
    fun buildGameModeReq(): ByteArray = buildRequest(CMD_GAME_MODE_GET)
    fun buildEarDetectReq(): ByteArray = buildRequest(CMD_EAR_DETECT_GET)
    fun buildEqReq(): ByteArray = buildRequest(CMD_EQ_GET)

    fun buildAncSet(mode: AncMode): ByteArray {
        // OpenHuilian: {0=off, 1=ANC, 2=transparency, 5=adaptive}
        val idx: Byte = when (mode) {
            AncMode.OFF -> 0x00
            AncMode.NOISE_REDUCTION -> 0x01
            AncMode.TRANSPARENCY -> 0x02
        }
        return buildRequest(CMD_ANC_SET, byteArrayOf(idx))
    }

    fun buildGameModeSet(enabled: Boolean): ByteArray =
        buildRequest(CMD_GAME_MODE_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildEarDetectSet(enabled: Boolean): ByteArray =
        buildRequest(CMD_EAR_DETECT_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildEqSet(preset: EqPreset): ByteArray {
        val idx: Byte = when (preset) {
            EqPreset.ORIGINAL_SOUND -> 0x00
            EqPreset.DEEP_BASS -> 0x01
            EqPreset.SERENADE -> 0x02
            EqPreset.PURE_BASS -> 0x03
        }
        return buildRequest(CMD_EQ_SET, byteArrayOf(idx))
    }

    fun buildRenameSet(newName: String): ByteArray {
        val bytes = newName.trim().toByteArray(Charsets.UTF_8).take(31).toByteArray()
        return buildRequest(CMD_RENAME_SET, bytes)
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val out = mutableListOf<Packet>()
        var off = 0
        while (off + 3 <= buf.size) {
            if (buf[off] != MAGIC) {
                off++
                continue
            }
            val cmd = buf[off + 1].toInt() and 0xFF
            val len = buf[off + 2].toInt() and 0xFF
            if (len > 220) {
                off++
                continue
            }
            val fullLen = 3 + len
            if (off + fullLen > buf.size) break
            val payload = if (len > 0) buf.copyOfRange(off + 3, off + fullLen) else ByteArray(0)
            out.add(Packet(cmd, payload))
            off += fullLen
        }
        return out to (if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0))
    }

    fun parseAscii(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val cleaned = payload.takeWhile { it.toInt() != 0 }.filter { it in 0x20..0x7E }.toByteArray()
        return String(cleaned, Charsets.US_ASCII).trim().takeIf { it.length >= 2 }
    }

    fun parseBattery(payload: ByteArray): List<BatteryInfo> {
        if (payload.size < 3) return emptyList()
        val out = mutableListOf<BatteryInfo>()
        fun decode(b: Byte, role: ComponentRole) {
            val v = b.toInt() and 0xFF
            val pct = v and 0x7F
            val chg = (v and 0x80) != 0
            if (pct in 0..100) out.add(BatteryInfo(role, pct, chg))
        }
        decode(payload[0], ComponentRole.LEFT_EAR)
        decode(payload[1], ComponentRole.RIGHT_EAR)
        decode(payload[2], ComponentRole.CASE)
        return out
    }

    fun parseAncMode(payload: ByteArray): AncMode? {
        if (payload.isEmpty()) return null
        return when (payload[0].toInt() and 0xFF) {
            0x00 -> AncMode.OFF
            0x01, 0x05 -> AncMode.NOISE_REDUCTION
            0x02 -> AncMode.TRANSPARENCY
            else -> null
        }
    }

    fun parseEqPreset(payload: ByteArray): EqPreset? {
        if (payload.isEmpty()) return null
        return when (payload[0].toInt() and 0xFF) {
            0x00 -> EqPreset.ORIGINAL_SOUND
            0x01 -> EqPreset.DEEP_BASS
            0x02 -> EqPreset.SERENADE
            0x03 -> EqPreset.PURE_BASS
            else -> null
        }
    }
}

/**
 * Zhuhai JieLi (JL_SPP) RCSP protocol (`FE DC BA [flags] [op] [lenHi] [lenLo] [payload...] EF`)
 * with SAFER+ E1 mutual authentication (verified from `huzaifanur/audionic-ble`, MIT license,
 * and confirmed via SDP `JL_SPP` + `FE010000-1234-5678-ABCD-00805F9B34FB` on JL AirPods replicas).
 */
object JieliRcspProtocol {
    val HEADER: ByteArray = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
    const val FOOTER: Byte = 0xEF.toByte()

    val AUTH_RESET: ByteArray = byteArrayOf(
        0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(),
        0x06, 0x00, 0x02, 0x00, 0x01, 0xEF.toByte()
    )
    val AUTH_PASS: ByteArray = byteArrayOf(0x02, 'p'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 's'.code.toByte())

    private val DEFAULT_KEY = intArrayOf(
        0x06, 0x77, 0x5F, 0x87, 0x91, 0x8D, 0xD4, 0x23,
        0x00, 0x5D, 0xF1, 0xD8, 0xCF, 0x0C, 0x14, 0x2B
    )
    private val ADDRESS = intArrayOf(0x11, 0x22, 0x33, 0x33, 0x22, 0x11)
    private val PERM = intArrayOf(8, 11, 12, 15, 2, 1, 6, 5, 10, 9, 14, 13, 0, 7, 4, 3)
    private val KEY_CONSTANTS = intArrayOf(
        233, 229, 223, 193, 179, 167, 149, 131,
        233, 229, 223, 193, 179, 167, 149, 131
    )
    private val EXP = IntArray(256).also { table ->
        var v = 1
        for (i in 0 until 256) {
            table[i] = v % 256
            v = (v * 45) % 257
        }
    }
    private val LOG = IntArray(256).also { table ->
        for (i in 0 until 256) table[EXP[i]] = i
    }

    const val OP_GET_TARGET_INFO = 0x03
    const val OP_GET_SYS_INFO = 0x07
    const val OP_SET_SYS_INFO = 0x08
    const val OP_SYS_INFO_AUTO_UPDATE = 0x09
    const val OP_SEARCH_DEV = 0x19
    const val OP_ADV_SETTINGS = 0xC0
    const val OP_ADV_GET_INFO = 0xC1
    const val OP_ADV_DEVICE_NOTIFY = 0xC2

    const val FUNC_PUBLIC = 0xFF
    const val ATTR_EQ = 4
    const val ATTR_EQ_PRESET = 12
    const val ATTR_VOICE_MODE = 13
    const val ATTR_ALL_VOICE_MODES = 14

    const val ADV_BATTERY = 0
    const val ADV_NAME = 1
    const val ADV_WORK_MODE = 5
    const val ADV_IN_EAR = 8
    const val ADV_ANC_LIST = 10

    data class Packet(
        val flags: Int,
        val opcode: Int,
        val sequence: Int,
        val data: ByteArray,
        val status: Int = 0
    ) {
        val isCommand: Boolean get() = (flags and 0x80) != 0
    }

    sealed class StreamItem {
        data class AuthMsg(val raw: ByteArray) : StreamItem()
        data class Rcsp(val packet: Packet) : StreamItem()
    }

    fun buildAuthChallenge(): ByteArray {
        val out = ByteArray(17)
        val rand = ByteArray(16)
        java.security.SecureRandom().nextBytes(rand)
        out[0] = 0x00
        System.arraycopy(rand, 0, out, 1, 16)
        return out
    }

    fun encryptedChallenge(challenge17: ByteArray): ByteArray? {
        if (challenge17.size != 17 || challenge17[0].toInt() != 0) return null
        val data = IntArray(16) { challenge17[it + 1].toInt() and 0xFF }
        val first = safer(data, expandKeys(DEFAULT_KEY), tweak = false)
        val combined = IntArray(16) { i -> ((first[i] xor data[i]) + ADDRESS[i % 6]) and 0xFF }
        val modified = IntArray(16) { i ->
            val addMode = i == 0 || i == 2 || i == 4 || i == 6 || i == 9 || i == 11 || i == 13 || i == 15
            if (addMode) (DEFAULT_KEY[i] + KEY_CONSTANTS[i]) and 0xFF
            else (DEFAULT_KEY[i] xor KEY_CONSTANTS[i]) and 0xFF
        }
        val enc = safer(combined, expandKeys(modified), tweak = true)
        return ByteArray(17).also { out ->
            out[0] = 0x01
            for (i in 0 until 16) out[i + 1] = enc[i].toByte()
        }
    }

    private fun expandKeys(key: IntArray): Array<IntArray> {
        val work = IntArray(17)
        var xor = 0
        for (i in 0 until 16) {
            work[i] = key[i] and 0xFF
            xor = xor xor work[i]
        }
        work[16] = xor
        val keys = Array(17) { IntArray(16) }
        for (i in 0 until 16) keys[0][i] = work[i]
        for (j in 1..16) {
            for (k in 0 until 17) {
                work[k] = ((work[k] shl 3) or (work[k] ushr 5)) and 0xFF
            }
            for (i in 0 until 16) {
                keys[j][i] = (work[(j + i) % 17] + EXP[EXP[(17 * (j + 1) + i + 1) % 256]]) and 0xFF
            }
        }
        return keys
    }

    private fun mix(data: IntArray, key: IntArray, reverse: Boolean): IntArray {
        val out = IntArray(16)
        for (i in 0 until 16) {
            val useXor = (((0x9999 ushr i) and 1) != 0) != reverse
            out[i] = if (useXor) (data[i] xor key[i]) and 0xFF else (data[i] + key[i]) and 0xFF
        }
        return out
    }

    private fun safer(input: IntArray, keys: Array<IntArray>, tweak: Boolean): IntArray {
        val initial = input.copyOf()
        var cur = input.copyOf()
        for (roundIndex in 0 until 8) {
            if (tweak && roundIndex == 2) {
                cur = mix(cur, initial, false)
            }
            cur = mix(cur, keys[roundIndex * 2], false)
            for (i in 0 until 16) {
                cur[i] = if (((0x9999 ushr i) and 1) != 0) EXP[cur[i]] else LOG[cur[i]]
            }
            cur = mix(cur, keys[roundIndex * 2 + 1], true)
            for (layer in 0 until 4) {
                for (i in 0 until 16 step 2) {
                    val a = cur[i]
                    val b = cur[i + 1]
                    cur[i] = (2 * a + b) and 0xFF
                    cur[i + 1] = (a + b) and 0xFF
                }
                if (layer < 3) {
                    val next = IntArray(16)
                    for (i in 0 until 16) next[i] = cur[PERM[i]]
                    cur = next
                }
            }
        }
        return mix(cur, keys[16], false)
    }

    fun buildFrame(opcode: Int, seq: Int, data: ByteArray = ByteArray(0), needResponse: Boolean = true): ByteArray {
        val flags = if (needResponse) 0xC0 else 0x80
        val payloadLen = 1 + data.size
        val out = ByteArray(8 + payloadLen)
        out[0] = HEADER[0]
        out[1] = HEADER[1]
        out[2] = HEADER[2]
        out[3] = flags.toByte()
        out[4] = (opcode and 0xFF).toByte()
        out[5] = ((payloadLen ushr 8) and 0xFF).toByte()
        out[6] = (payloadLen and 0xFF).toByte()
        out[7] = (seq and 0xFF).toByte()
        if (data.isNotEmpty()) System.arraycopy(data, 0, out, 8, data.size)
        out[7 + payloadLen] = FOOTER
        return out
    }

    private fun packLtv(type: Int, data: ByteArray): ByteArray {
        val out = ByteArray(2 + data.size)
        out[0] = (data.size + 1).toByte()
        out[1] = (type and 0xFF).toByte()
        if (data.isNotEmpty()) System.arraycopy(data, 0, out, 2, data.size)
        return out
    }

    fun parseLtvs(data: ByteArray, startOffset: Int = 0): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var off = startOffset
        while (off < data.size) {
            val size = data[off].toInt() and 0xFF
            if (size < 1 || off + 1 + size > data.size) break
            val type = data[off + 1].toInt() and 0xFF
            val value = if (size > 1) data.copyOfRange(off + 2, off + 1 + size) else ByteArray(0)
            out.add(type to value)
            off += 1 + size
        }
        return out
    }

    fun buildGetTargetInfo(seq: Int): ByteArray =
        buildFrame(OP_GET_TARGET_INFO, seq, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

    fun buildGetAdvInfo(seq: Int): ByteArray =
        buildFrame(OP_ADV_GET_INFO, seq, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

    fun buildGetSysInfo(seq: Int): ByteArray {
        val mask = (1 shl ATTR_EQ) or (1 shl ATTR_EQ_PRESET) or (1 shl ATTR_VOICE_MODE) or (1 shl ATTR_ALL_VOICE_MODES)
        return buildFrame(
            OP_GET_SYS_INFO,
            seq,
            byteArrayOf(
                FUNC_PUBLIC.toByte(),
                ((mask ushr 24) and 0xFF).toByte(),
                ((mask ushr 16) and 0xFF).toByte(),
                ((mask ushr 8) and 0xFF).toByte(),
                (mask and 0xFF).toByte()
            )
        )
    }

    fun buildSetAnc(seq: Int, mode: AncMode, reportedTransparency: ByteArray?): ByteArray {
        val payload = ByteArray(9)
        when (mode) {
            AncMode.OFF -> payload[0] = 0x00
            AncMode.NOISE_REDUCTION -> payload[0] = 0x01
            AncMode.TRANSPARENCY -> {
                payload[0] = 0x02
                if (reportedTransparency != null && reportedTransparency.size == 9) {
                    System.arraycopy(reportedTransparency, 1, payload, 1, 8)
                } else {
                    // Default 50% transparency (max=100, cur=50)
                    payload[2] = 0x64
                    payload[4] = 0x64
                    payload[6] = 0x32
                    payload[8] = 0x32
                }
            }
        }
        return buildFrame(OP_SET_SYS_INFO, seq, byteArrayOf(FUNC_PUBLIC.toByte()) + packLtv(ATTR_VOICE_MODE, payload))
    }

    fun buildSetGameMode(seq: Int, enabled: Boolean): ByteArray =
        buildFrame(OP_ADV_SETTINGS, seq, packLtv(ADV_WORK_MODE, byteArrayOf(if (enabled) 0x01 else 0x02)))

    fun buildSetEqPreset(seq: Int, preset: EqPreset): ByteArray {
        val modeByte: Byte = when (preset) {
            EqPreset.ORIGINAL_SOUND -> 0x00
            EqPreset.DEEP_BASS -> 0x01
            EqPreset.SERENADE -> 0x02
            EqPreset.PURE_BASS -> 0x03
        }
        return buildFrame(OP_SET_SYS_INFO, seq, byteArrayOf(FUNC_PUBLIC.toByte()) + packLtv(ATTR_EQ, byteArrayOf(modeByte)))
    }

    fun buildSetCustomEq(seq: Int, gains: List<Int>, dynamic: Boolean = true): ByteArray {
        val clamped = gains.take(10).map { it.coerceIn(-12, 12).toByte() }.toByteArray()
        val body = if (dynamic) {
            byteArrayOf(0x86.toByte(), clamped.size.toByte()) + clamped
        } else {
            val ten = ByteArray(10) { i -> clamped.getOrElse(i) { 0 } }
            byteArrayOf(0x06) + ten
        }
        return buildFrame(OP_SET_SYS_INFO, seq, byteArrayOf(FUNC_PUBLIC.toByte()) + packLtv(ATTR_EQ, body))
    }

    fun buildRename(seq: Int, newName: String): ByteArray {
        val bytes = newName.trim().toByteArray(Charsets.UTF_8).take(32).toByteArray()
        return buildFrame(OP_ADV_SETTINGS, seq, packLtv(ADV_NAME, bytes))
    }

    fun buildFindBuds(seq: Int, enabled: Boolean): ByteArray =
        buildFrame(
            OP_SEARCH_DEV,
            seq,
            byteArrayOf(0x01, if (enabled) 0x01 else 0x00, 0x00, if (enabled) 0x0A else 0x00, 0x00, 0x01)
        )

    fun parseStream(buf: ByteArray, acceptRawAuth: Boolean): Pair<List<StreamItem>, ByteArray> {
        val out = mutableListOf<StreamItem>()
        var off = 0
        while (off < buf.size) {
            val b0 = buf[off].toInt() and 0xFF
            if (b0 == 0xFE) {
                if (off + 3 > buf.size) break
                if (buf[off + 1] == HEADER[1] && buf[off + 2] == HEADER[2]) {
                    if (off + 7 > buf.size) break
                    val flags = buf[off + 3].toInt() and 0xFF
                    val op = buf[off + 4].toInt() and 0xFF
                    val size = ((buf[off + 5].toInt() and 0xFF) shl 8) or (buf[off + 6].toInt() and 0xFF)
                    val minSize = if ((flags and 0x80) != 0) 1 else 2
                    if (size > 4096 || size < minSize) {
                        off++
                        continue
                    }
                    val fullLen = size + 8
                    if (off + fullLen > buf.size) break
                    if (buf[off + fullLen - 1] != FOOTER) {
                        off++
                        continue
                    }
                    val payload = buf.copyOfRange(off + 7, off + 7 + size)
                    val pkt = if ((flags and 0x80) != 0) {
                        Packet(flags, op, payload[0].toInt() and 0xFF, payload.copyOfRange(1, payload.size), 0)
                    } else {
                        Packet(
                            flags,
                            op,
                            payload[1].toInt() and 0xFF,
                            payload.copyOfRange(2, payload.size),
                            payload[0].toInt() and 0xFF
                        )
                    }
                    out.add(StreamItem.Rcsp(pkt))
                    off += fullLen
                    continue
                }
            }
            if (acceptRawAuth && (b0 == 0x00 || b0 == 0x01 || b0 == 0x02)) {
                val expected = if (b0 == 0x02) 5 else 17
                if (off + expected > buf.size) break
                val raw = buf.copyOfRange(off, off + expected)
                if (b0 == 0x02 && !raw.contentEquals(AUTH_PASS)) {
                    off++
                    continue
                }
                out.add(StreamItem.AuthMsg(raw))
                off += expected
                continue
            }
            off++
        }
        return out to (if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0))
    }

    fun parseBatteryTriplet(data: ByteArray, offset: Int = 0): List<BatteryInfo> {
        if (offset + 3 > data.size) return emptyList()
        val out = mutableListOf<BatteryInfo>()
        val roles = arrayOf(ComponentRole.LEFT_EAR, ComponentRole.RIGHT_EAR, ComponentRole.CASE)
        for (i in 0 until 3) {
            val raw = data[offset + i].toInt() and 0xFF
            val level = raw and 0x7F
            val chg = (raw and 0x80) != 0
            if (level in 0..100) out.add(BatteryInfo(roles[i], level, chg))
        }
        return out
    }
}
