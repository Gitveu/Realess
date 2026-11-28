package uwu.realness.gitveu.protocol

import uwu.realness.gitveu.protocol.BudsProtocol.AncMode
import uwu.realness.gitveu.protocol.BudsProtocol.BatteryInfo
import uwu.realness.gitveu.protocol.BudsProtocol.ComponentRole
import uwu.realness.gitveu.protocol.BudsProtocol.EqPreset
import kotlin.math.roundToInt

/**
 * Open-source protocols for popular budget audio brands:
 * 1. Edifier (edifier-proto: 0xAA TX / 0xBB RX + sum + 0x2019 checksum)
 * 2. SoundPEATS (Wasabules/OpenPeats: 0xFF 0x04 GAIA-style SPP/BLE framing)
 * 3. QCY (hui1601/Quicky: BLE GATT 0xA001 + 0xFF TLV framing & 0x521C beacon)
 * 4. Baseus (elaxptr/baseus-desktop: Plain 0xBA/0xAA & CRC16/MODBUS 0x78 0x9C)
 * 5. Moondrop (pubglite55/SpaceTravel-Protocol: Qualcomm GAIA v3 vendor 0x001D)
 */

// ═════════════════════════════════════════════════════════════════════════════
// 1. EDIFIER (SPP / BLE: 0xAA -> device, 0xBB <- device, checksum = sum + 0x2019)
// ═════════════════════════════════════════════════════════════════════════════
object EdifierProtocol {
    const val MAGIC_TX: Byte = 0xAA.toByte()
    const val MAGIC_RX: Byte = 0xBB.toByte()
    const val CRC_OFFSET: Int = 0x2019

    const val CMD_GAME_MODE_GET: Int = 0x08
    const val CMD_GAME_MODE_SET: Int = 0x09
    const val CMD_ANC_MODE: Int = 0xC1
    const val CMD_BATTERY_GET: Int = 0xD0
    const val CMD_EQ_GET: Int = 0xC4
    const val CMD_EQ_SET: Int = 0xC5

    data class Packet(val cmd: Int, val payload: ByteArray)

    fun buildPacket(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 1 + payload.size
        val out = ByteArray(2 + len + 2)
        out[0] = MAGIC_TX
        out[1] = (len and 0xFF).toByte()
        out[2] = (cmd and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 3, payload.size)
        var sum = CRC_OFFSET
        for (i in 0 until 2 + len) sum += (out[i].toInt() and 0xFF)
        out[2 + len] = ((sum ushr 8) and 0xFF).toByte()
        out[2 + len + 1] = (sum and 0xFF).toByte()
        return out
    }

    fun buildBatteryReq(): ByteArray = buildPacket(CMD_BATTERY_GET)
    fun buildAncReq(): ByteArray = buildPacket(CMD_ANC_MODE)
    fun buildAncSet(mode: AncMode): ByteArray {
        val m: Byte = when (mode) {
            AncMode.OFF -> 0x01
            AncMode.NOISE_REDUCTION -> 0x02
            AncMode.TRANSPARENCY -> 0x03
        }
        return buildPacket(CMD_ANC_MODE, byteArrayOf(m, 0x00))
    }

    fun buildGameModeReq(): ByteArray = buildPacket(CMD_GAME_MODE_GET)
    fun buildGameModeSet(enabled: Boolean): ByteArray =
        buildPacket(CMD_GAME_MODE_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildEqSet(preset: EqPreset): ByteArray {
        val id: Byte = when (preset) {
            EqPreset.ORIGINAL_SOUND -> 0x00
            EqPreset.SERENADE -> 0x01
            EqPreset.DEEP_BASS -> 0x02
            EqPreset.PURE_BASS -> 0x03
        }
        return buildPacket(CMD_EQ_SET, byteArrayOf(id))
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val out = mutableListOf<Packet>()
        var off = 0
        while (off + 5 <= buf.size) {
            if (buf[off] != MAGIC_RX && buf[off] != 0xCC.toByte()) {
                off++
                continue
            }
            val len = buf[off + 1].toInt() and 0xFF
            if (len < 1 || len > 128) {
                off++
                continue
            }
            val fullLen = 2 + len + 2
            if (off + fullLen > buf.size) break
            val cmd = buf[off + 2].toInt() and 0xFF
            val payload = if (len > 1) buf.copyOfRange(off + 3, off + 2 + len) else ByteArray(0)
            out.add(Packet(cmd, payload))
            off += fullLen
        }
        return out to (if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0))
    }

    fun parseAncMode(payload: ByteArray): AncMode? {
        if (payload.isEmpty()) return null
        return when (payload[0].toInt() and 0xFF) {
            0x00, 0x01 -> AncMode.OFF
            0x02 -> AncMode.NOISE_REDUCTION
            0x03 -> AncMode.TRANSPARENCY
            else -> null
        }
    }

    fun parseBattery(payload: ByteArray): List<BatteryInfo> {
        val out = mutableListOf<BatteryInfo>()
        if (payload.size == 1) {
            val lvl = payload[0].toInt() and 0xFF
            if (lvl in 0..100) {
                out.add(BatteryInfo(ComponentRole.LEFT_EAR, lvl, false))
                out.add(BatteryInfo(ComponentRole.RIGHT_EAR, lvl, false))
            }
        } else if (payload.size >= 2) {
            val l = payload[0].toInt() and 0x7F
            val r = payload[1].toInt() and 0x7F
            if (l in 0..100) out.add(BatteryInfo(ComponentRole.LEFT_EAR, l, (payload[0].toInt() and 0x80) != 0))
            if (r in 0..100) out.add(BatteryInfo(ComponentRole.RIGHT_EAR, r, (payload[1].toInt() and 0x80) != 0))
            if (payload.size >= 3) {
                val c = payload[2].toInt() and 0x7F
                if (c in 0..100) out.add(BatteryInfo(ComponentRole.CASE, c, (payload[2].toInt() and 0x80) != 0))
            }
        }
        return out
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 2. SOUNDPEATS (Wasabules/OpenPeats: FF 04 00 len vendor(2B) cmd(2B) payload)
// ═════════════════════════════════════════════════════════════════════════════
object SoundPeatsProtocol {
    const val SOF: Byte = 0xFF.toByte()
    const val VERSION: Byte = 0x04
    const val VENDOR_CTRL: Int = 0x000A
    const val VENDOR_EQ: Int = 0x001D

    const val CMD_BAT_LEFT_GET: Int = 0x0306
    const val CMD_BAT_RIGHT_GET: Int = 0x0307
    const val CMD_BAT_BOX_GET: Int = 0x0323
    const val CMD_IN_EAR_GET: Int = 0x030C
    const val CMD_IN_EAR_SET: Int = 0x030D
    const val CMD_GAME_MODE_GET: Int = 0x030E
    const val CMD_GAME_MODE_SET: Int = 0x030F
    const val CMD_ANC_GET: Int = 0x0310
    const val CMD_ANC_SET: Int = 0x0311
    const val CMD_MULTIPOINT_GET: Int = 0x0314
    const val CMD_MULTIPOINT_SET: Int = 0x0315
    const val CMD_LDAC_GET: Int = 0x0338
    const val CMD_LDAC_SET: Int = 0x0339
    const val CMD_EQ_APPLY_V2: Int = 0x0E02

    val EQ_FREQS_HZ = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    data class Packet(val vendor: Int, val cmd: Int, val payload: ByteArray)

    fun buildPacket(cmd: Int, payload: ByteArray = ByteArray(0), vendor: Int = VENDOR_CTRL): ByteArray {
        val len = payload.size.coerceAtMost(255)
        val out = ByteArray(8 + len)
        out[0] = SOF
        out[1] = VERSION
        out[2] = 0x00
        out[3] = len.toByte()
        out[4] = ((vendor ushr 8) and 0xFF).toByte()
        out[5] = (vendor and 0xFF).toByte()
        out[6] = ((cmd ushr 8) and 0xFF).toByte()
        out[7] = (cmd and 0xFF).toByte()
        if (len > 0) System.arraycopy(payload, 0, out, 8, len)
        return out
    }

    fun buildLeftBatReq(): ByteArray = buildPacket(CMD_BAT_LEFT_GET)
    fun buildRightBatReq(): ByteArray = buildPacket(CMD_BAT_RIGHT_GET)
    fun buildCaseBatReq(): ByteArray = buildPacket(CMD_BAT_BOX_GET)
    fun buildAncReq(): ByteArray = buildPacket(CMD_ANC_GET)
    fun buildGameModeReq(): ByteArray = buildPacket(CMD_GAME_MODE_GET)
    fun buildInEarReq(): ByteArray = buildPacket(CMD_IN_EAR_GET)
    fun buildMultipointReq(): ByteArray = buildPacket(CMD_MULTIPOINT_GET)
    fun buildLdacReq(): ByteArray = buildPacket(CMD_LDAC_GET)

    fun buildAncSet(mode: AncMode): ByteArray {
        val m: Byte = when (mode) {
            AncMode.OFF -> 0x00
            AncMode.NOISE_REDUCTION -> 0x01
            AncMode.TRANSPARENCY -> 0x02
        }
        return buildPacket(CMD_ANC_SET, byteArrayOf(m))
    }

    fun buildGameModeSet(enabled: Boolean): ByteArray =
        buildPacket(CMD_GAME_MODE_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildInEarSet(enabled: Boolean): ByteArray =
        buildPacket(CMD_IN_EAR_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildMultipointSet(enabled: Boolean): ByteArray =
        buildPacket(CMD_MULTIPOINT_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildLdacSet(enabled: Boolean): ByteArray =
        buildPacket(CMD_LDAC_SET, byteArrayOf(if (enabled) 0x01 else 0x00))

    /**
     * Builds OpenPeats v2 parametric EQ frames (one frame per band, vendorId = 0x001D).
     */
    fun buildCustomEqFrames(gainsDb: List<Int>): List<ByteArray> {
        val total = EQ_FREQS_HZ.size
        return EQ_FREQS_HZ.mapIndexed { idx, freq ->
            val g = gainsDb.getOrElse(idx) { 0 }.coerceIn(-12, 12)
            val masterGainScaled = 0
            val freqScaled = (freq * 3).coerceIn(60, 60000)
            val gainScaled = g * 60
            val qScaled = (1.41 * 4096.0).roundToInt()
            val payload = byteArrayOf(
                total.toByte(),
                (idx + 1).toByte(),
                ((masterGainScaled ushr 8) and 0xFF).toByte(),
                (masterGainScaled and 0xFF).toByte(),
                ((freqScaled ushr 8) and 0xFF).toByte(),
                (freqScaled and 0xFF).toByte(),
                ((gainScaled ushr 8) and 0xFF).toByte(),
                (gainScaled and 0xFF).toByte(),
                ((qScaled ushr 8) and 0xFF).toByte(),
                (qScaled and 0xFF).toByte()
            )
            buildPacket(CMD_EQ_APPLY_V2, payload, VENDOR_EQ)
        }
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val out = mutableListOf<Packet>()
        var off = 0
        while (off + 8 <= buf.size) {
            if (buf[off] != SOF || buf[off + 1] != VERSION) {
                off++
                continue
            }
            val pLen = buf[off + 3].toInt() and 0xFF
            val fullLen = 8 + pLen
            if (off + fullLen > buf.size) break
            val vendor = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
            val cmd = ((buf[off + 6].toInt() and 0xFF) shl 8) or (buf[off + 7].toInt() and 0xFF)
            val payload = if (pLen > 0) buf.copyOfRange(off + 8, off + fullLen) else ByteArray(0)
            out.add(Packet(vendor, cmd, payload))
            off += fullLen
        }
        return out to (if (off < buf.size) buf.copyOfRange(off, buf.size) else ByteArray(0))
    }

    fun parseAncMode(payload: ByteArray): AncMode? {
        if (payload.isEmpty()) return null
        return when (payload[0].toInt() and 0xFF) {
            0x00 -> AncMode.OFF
            0x01, 0x03, 0x04 -> AncMode.NOISE_REDUCTION
            0x02 -> AncMode.TRANSPARENCY
            else -> null
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 3. QCY (hui1601/Quicky: BLE GATT 0xA001, [0xFF, bodyLen, cmd, pLen, params])
// ═════════════════════════════════════════════════════════════════════════════
object QcyProtocol {
    const val SOF: Byte = 0xFF.toByte()
    const val MANUFACTURER_ID: Int = 0x521C

    const val CMD_IN_EAR: Int = 0x06
    const val CMD_GAME_MODE: Int = 0x09
    const val CMD_ANC_MODE: Int = 0x0C
    const val CMD_LDAC: Int = 0x23
    const val CMD_SPATIAL: Int = 0x2D
    const val CMD_BATTERY: Int = 0x2F
    const val CMD_REQUEST_DATA: Int = 0xFE

    data class TlvCommand(val cmd: Int, val params: ByteArray)

    fun buildFrame(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val bodyLen = 2 + params.size
        val out = ByteArray(2 + bodyLen)
        out[0] = SOF
        out[1] = (bodyLen and 0xFF).toByte()
        out[2] = (cmd and 0xFF).toByte()
        out[3] = (params.size and 0xFF).toByte()
        if (params.isNotEmpty()) System.arraycopy(params, 0, out, 4, params.size)
        return out
    }

    fun buildQuery(targetCmd: Int): ByteArray = buildFrame(CMD_REQUEST_DATA, byteArrayOf(targetCmd.toByte()))
    fun buildAncSet(mode: AncMode): ByteArray {
        val m: Byte = when (mode) {
            AncMode.OFF -> 0x00
            AncMode.NOISE_REDUCTION -> 0x01
            AncMode.TRANSPARENCY -> 0x02
        }
        return buildFrame(CMD_ANC_MODE, byteArrayOf(m))
    }

    fun buildGameModeSet(enabled: Boolean): ByteArray =
        buildFrame(CMD_GAME_MODE, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildInEarSet(enabled: Boolean): ByteArray =
        buildFrame(CMD_IN_EAR, byteArrayOf(if (enabled) 0x01 else 0x02))

    fun buildSpatialSet(enabled: Boolean): ByteArray =
        buildFrame(CMD_SPATIAL, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildLdacSet(enabled: Boolean): ByteArray =
        buildFrame(CMD_LDAC, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun parseNotification(raw: ByteArray): List<TlvCommand> {
        if (raw.size < 4 || raw[0] != SOF) return emptyList()
        val bodyLen = (raw[1].toInt() and 0xFF).coerceAtMost(raw.size - 2)
        val end = 2 + bodyLen
        val out = mutableListOf<TlvCommand>()
        var off = 2
        while (off + 2 <= end) {
            val cmd = raw[off].toInt() and 0xFF
            val pLen = raw[off + 1].toInt() and 0xFF
            off += 2
            if (off + pLen > end) break
            out.add(TlvCommand(cmd, raw.copyOfRange(off, off + pLen)))
            off += pLen
        }
        return out
    }

    fun parseBatteryTriplet(params: ByteArray): List<BatteryInfo> {
        if (params.size < 3) return emptyList()
        val out = mutableListOf<BatteryInfo>()
        fun decode(b: Byte, role: ComponentRole) {
            val v = b.toInt() and 0xFF
            val pct = v and 0x7F
            val chg = (v and 0x80) != 0
            if (pct in 0..100) out.add(BatteryInfo(role, pct, chg))
        }
        decode(params[0], ComponentRole.LEFT_EAR)
        decode(params[1], ComponentRole.RIGHT_EAR)
        decode(params[2], ComponentRole.CASE)
        return out
    }

    fun parseAncMode(params: ByteArray): AncMode? {
        if (params.isEmpty()) return null
        return when (params[0].toInt() and 0xFF) {
            0x00 -> AncMode.OFF
            0x01 -> AncMode.NOISE_REDUCTION
            0x02 -> AncMode.TRANSPARENCY
            else -> null
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 4. BASEUS (elaxptr/baseus-desktop: Plain 0xBA/0xAA & CRC 0x78 0x9C)
// ═════════════════════════════════════════════════════════════════════════════
object BaseusProtocol {
    const val PLAIN_TX: Byte = 0xBA.toByte()
    const val PLAIN_RX: Byte = 0xAA.toByte()
    const val CRC_MAGIC_0: Byte = 0x78
    const val CRC_MAGIC_1: Byte = 0x9C.toByte()

    const val CMD_BATTERY: Int = 0x02
    const val CMD_GAME_MODE: Int = 0x23
    const val CMD_CASE_BATTERY: Int = 0x27
    const val CMD_ANC_OFF: Int = 0x30
    const val CMD_ANC_TRANSP: Int = 0x32
    const val CMD_ANC_ON: Int = 0x33
    const val CMD_ANC_ACK: Int = 0x34
    const val CMD_EQ_QUERY: Int = 0x42
    const val CMD_EQ_SET: Int = 0x43

    data class Frame(val cmd: Int, val payload: ByteArray, val isCrc: Boolean)

    fun buildPlain(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = PLAIN_TX
        out[1] = (cmd and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    fun buildCrc(isWrite: Boolean, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val n = 1 + payload.size
        val totalLen = 7 + n + 2
        val out = ByteArray(totalLen)
        out[0] = CRC_MAGIC_0
        out[1] = CRC_MAGIC_1
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = 0x02
        out[5] = if (isWrite) 0x03 else 0x01
        out[6] = (n and 0xFF).toByte()
        out[7] = (cmd and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 8, payload.size)
        val crc = crc16Modbus(out, 0, totalLen - 2)
        out[totalLen - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[totalLen - 1] = (crc and 0xFF).toByte()
        return out
    }

    fun buildBatteryReq(useCrc: Boolean = false): ByteArray =
        if (useCrc) buildCrc(false, CMD_BATTERY) else buildPlain(CMD_BATTERY)

    fun buildCaseReq(useCrc: Boolean = false): ByteArray =
        if (useCrc) buildCrc(false, CMD_CASE_BATTERY) else buildPlain(CMD_CASE_BATTERY)

    fun buildAncSet(mode: AncMode, useCrc: Boolean = false): ByteArray {
        val cmd = when (mode) {
            AncMode.OFF -> CMD_ANC_OFF
            AncMode.TRANSPARENCY -> CMD_ANC_TRANSP
            AncMode.NOISE_REDUCTION -> CMD_ANC_ON
        }
        return if (useCrc) buildCrc(true, cmd, byteArrayOf(0x01)) else buildPlain(cmd, byteArrayOf(0x01))
    }

    fun buildGameModeSet(enabled: Boolean, useCrc: Boolean = false): ByteArray {
        val b = byteArrayOf(if (enabled) 0x01 else 0x00)
        return if (useCrc) buildCrc(true, CMD_GAME_MODE, b) else buildPlain(CMD_GAME_MODE, b)
    }

    fun buildEqSet(preset: EqPreset, useCrc: Boolean = false): ByteArray {
        val b: Byte = when (preset) {
            EqPreset.ORIGINAL_SOUND -> 0x00
            EqPreset.DEEP_BASS -> 0x01
            EqPreset.SERENADE -> 0x02
            EqPreset.PURE_BASS -> 0x03
        }
        return if (useCrc) buildCrc(true, CMD_EQ_SET, byteArrayOf(b)) else buildPlain(CMD_EQ_SET, byteArrayOf(b))
    }

    fun decodeFrame(raw: ByteArray): Frame? {
        if (raw.size >= 2 && raw[0] == PLAIN_RX) {
            val cmd = raw[1].toInt() and 0xFF
            val payload = if (raw.size > 2) raw.copyOfRange(2, raw.size) else ByteArray(0)
            return Frame(cmd, payload, isCrc = false)
        }
        if (raw.size >= 10 && raw[0] == CRC_MAGIC_0 && raw[1] == CRC_MAGIC_1) {
            val len = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            if (len < 10 || len > raw.size) return null
            val n = raw[6].toInt() and 0xFF
            if (n < 1 || 7 + n + 2 > len) return null
            val cmd = raw[7].toInt() and 0xFF
            val payload = if (n > 1) raw.copyOfRange(8, 7 + n) else ByteArray(0)
            return Frame(cmd, payload, isCrc = true)
        }
        return null
    }

    fun parseBudsBattery(payload: ByteArray): List<BatteryInfo> {
        if (payload.size < 4) return emptyList()
        val out = mutableListOf<BatteryInfo>()
        val l = payload[0].toInt() and 0xFF
        val r = payload[2].toInt() and 0xFF
        if (l in 0..100) out.add(BatteryInfo(ComponentRole.LEFT_EAR, l, false))
        if (r in 0..100) out.add(BatteryInfo(ComponentRole.RIGHT_EAR, r, false))
        return out
    }

    fun parseCaseBattery(payload: ByteArray): BatteryInfo? {
        if (payload.size < 2) return null
        val pct = payload[0].toInt() and 0xFF
        if (pct !in 0..100) return null
        return BatteryInfo(ComponentRole.CASE, pct, payload[1].toInt() != 0)
    }

    private fun crc16Modbus(data: ByteArray, offset: Int, len: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else (crc ushr 1)
            }
        }
        return crc and 0xFFFF
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 5. MOONDROP (SpaceTravel-Protocol: Qualcomm GAIA V3, Vendor ID 0x001D)
// ═════════════════════════════════════════════════════════════════════════════
object MoondropProtocol {
    const val SOF: Byte = 0xFF.toByte()
    const val VENDOR_ID: Int = 0x001D

    const val FEAT_CORE: Int = 0x00
    const val FEAT_EARBUD: Int = 0x01
    const val FEAT_ANC: Int = 0x02
    const val FEAT_EQ: Int = 0x05

    const val CMD_GET_ANC_STATE: Int = 0x01
    const val CMD_SET_ANC_STATE: Int = 0x02
    const val CMD_GET_ANC_MODE: Int = 0x03
    const val CMD_SET_ANC_MODE: Int = 0x04
    const val CMD_GET_BATTERY: Int = 0x05

    data class Packet(val vendor: Int, val feature: Int, val cmd: Int, val payload: ByteArray)

    fun buildBlePacket(feature: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val cmdVal = ((feature and 0x7F) shl 9) or (cmd and 0x7F)
        val out = ByteArray(4 + payload.size)
        out[0] = ((VENDOR_ID ushr 8) and 0xFF).toByte()
        out[1] = (VENDOR_ID and 0xFF).toByte()
        out[2] = ((cmdVal ushr 8) and 0xFF).toByte()
        out[3] = (cmdVal and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    fun buildSppPacket(feature: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val cmdVal = ((feature and 0x7F) shl 9) or (cmd and 0x7F)
        val out = ByteArray(8 + payload.size)
        out[0] = SOF
        out[1] = 0x03
        out[2] = 0x00
        out[3] = (payload.size and 0xFF).toByte()
        out[4] = ((VENDOR_ID ushr 8) and 0xFF).toByte()
        out[5] = (VENDOR_ID and 0xFF).toByte()
        out[6] = ((cmdVal ushr 8) and 0xFF).toByte()
        out[7] = (cmdVal and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    fun buildAncSet(mode: AncMode, ble: Boolean): ByteArray {
        // Space Travel / Golden Ages: 1=Off, 2=ANC, 4=Transparency
        val m: Byte = when (mode) {
            AncMode.OFF -> 0x01
            AncMode.NOISE_REDUCTION -> 0x02
            AncMode.TRANSPARENCY -> 0x04
        }
        return if (ble) buildBlePacket(FEAT_ANC, CMD_SET_ANC_MODE, byteArrayOf(m))
        else buildSppPacket(FEAT_ANC, CMD_SET_ANC_MODE, byteArrayOf(m))
    }

    fun buildAncGet(ble: Boolean): ByteArray =
        if (ble) buildBlePacket(FEAT_ANC, CMD_GET_ANC_MODE)
        else buildSppPacket(FEAT_ANC, CMD_GET_ANC_MODE)

    fun buildBatteryGet(ble: Boolean): ByteArray =
        if (ble) buildBlePacket(FEAT_CORE, CMD_GET_BATTERY)
        else buildSppPacket(FEAT_CORE, CMD_GET_BATTERY)

    fun buildEqPresetSet(preset: EqPreset, ble: Boolean): ByteArray {
        // Space Travel DSP tunings: 0=Monitor, 1=Reference, 2=Basshead
        val id: Byte = when (preset) {
            EqPreset.SERENADE -> 0x00
            EqPreset.ORIGINAL_SOUND -> 0x01
            EqPreset.DEEP_BASS, EqPreset.PURE_BASS -> 0x02
        }
        return if (ble) buildBlePacket(FEAT_EQ, 0x02, byteArrayOf(id))
        else buildSppPacket(FEAT_EQ, 0x02, byteArrayOf(id))
    }

    fun parseBlePacket(raw: ByteArray): Packet? {
        if (raw.size < 4) return null
        val vendor = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val cmdVal = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val feature = (cmdVal ushr 9) and 0x7F
        val cmd = cmdVal and 0x7F
        val payload = if (raw.size > 4) raw.copyOfRange(4, raw.size) else ByteArray(0)
        return Packet(vendor, feature, cmd, payload)
    }

    fun parseAncMode(payload: ByteArray): AncMode? {
        if (payload.isEmpty()) return null
        val v = payload.last().toInt() and 0xFF
        return when (v) {
            0x00, 0x01 -> AncMode.OFF
            0x02, 0x03 -> AncMode.NOISE_REDUCTION
            0x04 -> AncMode.TRANSPARENCY
            else -> null
        }
    }
}
