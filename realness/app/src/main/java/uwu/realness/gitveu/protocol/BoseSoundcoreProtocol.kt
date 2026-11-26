package uwu.realness.gitveu.protocol

import java.io.ByteArrayOutputStream

/**
 * Протокол Bose BMAP RFCOMM SPP (по базе kde-headphones/BoseBackend.cpp).
 * Формат пакета: [block, function, op & 0x0F, payloadLen, payload...]
 */
object BoseProtocol {
    const val OP_GET: Byte = 1
    const val OP_SET_GET: Byte = 2
    const val OP_STATUS: Byte = 3
    const val OP_START: Byte = 5
    const val OP_RESULT: Byte = 6

    const val BLOCK_INFO: Byte = 0
    const val BLOCK_SETTINGS: Byte = 1
    const val BLOCK_STATUS: Byte = 2
    const val BLOCK_NOTIFY: Byte = 9
    const val BLOCK_AUDIO_MODES: Byte = 31

    const val FUNC_PRODUCT_NAME: Byte = 2
    const val FUNC_BATTERY: Byte = 2
    const val FUNC_CNC: Byte = 5
    const val FUNC_ANR: Byte = 6
    const val FUNC_EQUALIZER: Byte = 7
    const val FUNC_MULTIPOINT: Byte = 10
    const val FUNC_AUTO_PAUSE: Byte = 24
    const val FUNC_AUTO_ANSWER: Byte = 27
    const val FUNC_CURRENT_MODE: Byte = 3

    data class Packet(val block: Int, val function: Int, val op: Int, val payload: ByteArray)

    fun buildPacket(block: Byte, function: Byte, op: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size.coerceAtMost(255)
        val out = ByteArray(4 + len)
        out[0] = block
        out[1] = function
        out[2] = (op.toInt() and 0x0F).toByte()
        out[3] = len.toByte()
        if (len > 0) System.arraycopy(payload, 0, out, 4, len)
        return out
    }

    fun buildNotifyEnable(): ByteArray = buildPacket(BLOCK_NOTIFY, 2, OP_SET_GET, byteArrayOf(0x01, 0x37))
    fun buildBatteryReq(): ByteArray = buildPacket(BLOCK_STATUS, FUNC_BATTERY, OP_GET)
    fun buildCncReq(): ByteArray = buildPacket(BLOCK_SETTINGS, FUNC_CNC, OP_GET)
    fun buildAnrReq(): ByteArray = buildPacket(BLOCK_SETTINGS, FUNC_ANR, OP_GET)
    fun buildMultipointReq(): ByteArray = buildPacket(BLOCK_SETTINGS, FUNC_MULTIPOINT, OP_GET)
    fun buildAutoPauseReq(): ByteArray = buildPacket(BLOCK_SETTINGS, FUNC_AUTO_PAUSE, OP_GET)
    fun buildAutoAnswerReq(): ByteArray = buildPacket(BLOCK_SETTINGS, FUNC_AUTO_ANSWER, OP_GET)

    fun buildCncSet(mode: BudsProtocol.AncMode): ByteArray {
        // CNC: 0 = max ANC (10), 10 = transparency/aware (0 ANC)
        val raw: Byte = when (mode) {
            BudsProtocol.AncMode.NOISE_REDUCTION -> 0x00
            BudsProtocol.AncMode.TRANSPARENCY -> 0x0A
            BudsProtocol.AncMode.OFF -> 0x0B // CNC disabled
        }
        val enabled: Byte = if (mode == BudsProtocol.AncMode.OFF) 0x00 else 0x01
        return buildPacket(BLOCK_SETTINGS, FUNC_CNC, OP_SET_GET, byteArrayOf(raw, enabled))
    }

    fun buildCncLevelSet(level: HuaweiProtocol.AncLevel): ByteArray {
        // Bose CNC 0..10 (0 = max ANC, 3 = medium, 6 = mild)
        val raw: Byte = when (level) {
            HuaweiProtocol.AncLevel.ULTRA, HuaweiProtocol.AncLevel.DYNAMIC -> 0x00
            HuaweiProtocol.AncLevel.GENERAL -> 0x03
            HuaweiProtocol.AncLevel.COMFORT -> 0x06
        }
        return buildPacket(BLOCK_SETTINGS, FUNC_CNC, OP_SET_GET, byteArrayOf(raw, 0x01))
    }

    fun buildAnrSet(mode: BudsProtocol.AncMode): ByteArray {
        val v: Byte = when (mode) {
            BudsProtocol.AncMode.NOISE_REDUCTION -> 0x01 // High
            BudsProtocol.AncMode.TRANSPARENCY -> 0x03    // Low
            BudsProtocol.AncMode.OFF -> 0x00
        }
        return buildPacket(BLOCK_SETTINGS, FUNC_ANR, OP_SET_GET, byteArrayOf(v))
    }

    fun buildMultipointSet(on: Boolean): ByteArray =
        buildPacket(BLOCK_SETTINGS, FUNC_MULTIPOINT, OP_SET_GET, byteArrayOf(if (on) 0x07 else 0x00))

    fun buildAutoPauseSet(on: Boolean): ByteArray =
        buildPacket(BLOCK_SETTINGS, FUNC_AUTO_PAUSE, OP_SET_GET, byteArrayOf(if (on) 0x01 else 0x00))

    fun buildAutoAnswerSet(on: Boolean): ByteArray =
        buildPacket(BLOCK_SETTINGS, FUNC_AUTO_ANSWER, OP_SET_GET, byteArrayOf(if (on) 0x01 else 0x00))

    fun buildEqReq(): ByteArray = buildPacket(BLOCK_SETTINGS, FUNC_EQUALIZER, OP_GET)
    fun buildEqBandSet(bandIdx: Int, gainDb: Int): ByteArray =
        buildPacket(BLOCK_SETTINGS, FUNC_EQUALIZER, OP_SET_GET, byteArrayOf(gainDb.coerceIn(-10, 10).toByte(), bandIdx.toByte()))

    fun buildRename(newName: String): ByteArray {
        val bytes = newName.trim().toByteArray(Charsets.UTF_8).let { if (it.size > 31) it.copyOf(31) else it }
        return buildPacket(BLOCK_SETTINGS, FUNC_PRODUCT_NAME, OP_SET_GET, bytes)
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off + 4 <= total) {
            val block = buf[off].toInt() and 0xFF
            val func = buf[off + 1].toInt() and 0xFF
            val op = buf[off + 2].toInt() and 0x0F
            val len = buf[off + 3].toInt() and 0xFF
            if (off + 4 + len > total) break
            val payload = if (len > 0) buf.copyOfRange(off + 4, off + 4 + len) else ByteArray(0)
            packets.add(Packet(block, func, op, payload))
            off += 4 + len
        }
        return packets to (if (off < total) buf.copyOfRange(off, total) else ByteArray(0))
    }

    fun parseBattery(p: ByteArray): List<BudsProtocol.BatteryInfo> {
        if (p.isEmpty()) return emptyList()
        val out = mutableListOf<BudsProtocol.BatteryInfo>()
        if (p.size >= 4 && p.size % 4 == 0) {
            var i = 0
            while (i + 3 < p.size) {
                val lvl = p[i].toInt() and 0xFF
                val id = p[i + 3].toInt() and 0xFF
                if (lvl in 0..100) {
                    when (id) {
                        2 -> out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.LEFT_EAR, lvl, false))
                        1 -> out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.RIGHT_EAR, lvl, false))
                        3 -> out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.CASE, lvl, false))
                        0, 4 -> {
                            out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.LEFT_EAR, lvl, false))
                            out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.RIGHT_EAR, lvl, false))
                        }
                    }
                }
                i += 4
            }
        } else {
            val lvl = p[0].toInt() and 0xFF
            if (lvl in 0..100) {
                out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.LEFT_EAR, lvl, false))
                out.add(BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.RIGHT_EAR, lvl, false))
            }
        }
        return out
    }
}

/**
 * Протокол Anker Soundcore RFCOMM SPP (по базе kde-headphones/SoundcoreBackend.cpp).
 * TX: [08 EE 00 00 00, cmdHi, cmdLo, totalLenLo, totalLenHi, body..., sum8]
 * RX: [09 FF 00 00 01, cmdHi, cmdLo, totalLenLo, totalLenHi, body..., sum8]
 */
object SoundcoreProtocol {
    private val TX_PREFIX = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)
    private val RX_PREFIX = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

    const val CMD_STATE: Int = 0x0101
    const val CMD_BATTERY_LEVEL: Int = 0x0103
    const val CMD_BATTERY_CHARGING: Int = 0x0104
    const val CMD_GAMING_UPDATE: Int = 0x0111
    const val CMD_AUTO_PAUSE: Int = 0x0181
    const val CMD_GAMING_SET: Int = 0x0187
    const val CMD_SET_EQ: Int = 0x0281
    const val CMD_SURROUND_SET: Int = 0x0286
    const val CMD_SOUND_UPDATE: Int = 0x0601
    const val CMD_SOUND_SET: Int = 0x0681
    const val CMD_DUAL_CONN: Int = 0x0B84
    const val CMD_GAMING_LIBERTY: Int = 0x1085

    data class Packet(val command: Int, val body: ByteArray)

    fun buildPacket(cmd: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val totalLen = 5 + 4 + body.size + 1
        val out = ByteArray(totalLen)
        System.arraycopy(TX_PREFIX, 0, out, 0, 5)
        out[5] = ((cmd ushr 8) and 0xFF).toByte()
        out[6] = (cmd and 0xFF).toByte()
        out[7] = (totalLen and 0xFF).toByte()
        out[8] = ((totalLen ushr 8) and 0xFF).toByte()
        if (body.isNotEmpty()) System.arraycopy(body, 0, out, 9, body.size)
        var sum = 0
        for (i in 0 until totalLen - 1) sum = (sum + (out[i].toInt() and 0xFF)) and 0xFF
        out[totalLen - 1] = sum.toByte()
        return out
    }

    fun buildStateReq(): ByteArray = buildPacket(CMD_STATE)

    fun buildAncSet(mode: BudsProtocol.AncMode, lastSoundBody: ByteArray?): ByteArray {
        val ambient: Byte = when (mode) {
            BudsProtocol.AncMode.NOISE_REDUCTION -> 0x00
            BudsProtocol.AncMode.TRANSPARENCY -> 0x01
            BudsProtocol.AncMode.OFF -> 0x02
        }
        val body = if (lastSoundBody != null && lastSoundBody.size >= 4) {
            lastSoundBody.copyOf().also { it[0] = ambient }
        } else {
            byteArrayOf(ambient, 0x00, 0x01, 0x00)
        }
        return buildPacket(CMD_SOUND_SET, body)
    }

    fun buildAncLevelSet(level: HuaweiProtocol.AncLevel, lastSoundBody: ByteArray?): ByteArray {
        // Soundcore ANC sub-modes: 0x00 = Transport (Max), 0x01 = Outdoor (Mid), 0x02 = Indoor (Comfort)
        val sub: Byte = when (level) {
            HuaweiProtocol.AncLevel.ULTRA, HuaweiProtocol.AncLevel.DYNAMIC -> 0x00
            HuaweiProtocol.AncLevel.GENERAL -> 0x01
            HuaweiProtocol.AncLevel.COMFORT -> 0x02
        }
        val body = if (lastSoundBody != null && lastSoundBody.size >= 4) {
            lastSoundBody.copyOf().also {
                it[0] = 0x00
                it[1] = sub
            }
        } else {
            byteArrayOf(0x00, sub, 0x01, 0x00)
        }
        return buildPacket(CMD_SOUND_SET, body)
    }

    fun buildSurroundSet(on: Boolean): ByteArray =
        buildPacket(CMD_SURROUND_SET, byteArrayOf(if (on) 0x01 else 0x00))

    fun buildGameModeSet(on: Boolean, isLiberty: Boolean = false): ByteArray =
        buildPacket(if (isLiberty) CMD_GAMING_LIBERTY else CMD_GAMING_SET, byteArrayOf(if (on) 0x01 else 0x00))

    /**
     * Soundcore 8-band Custom EQ (0x0281): [0xFE, 0xFE, 8 × (db*10 + 120)].
     */
    fun buildCustomEqSet(gainsDb: List<Int>): ByteArray {
        val body = ByteArray(2 + 8)
        body[0] = 0xFE.toByte()
        body[1] = 0xFE.toByte()
        for (i in 0 until 8) {
            val db = gainsDb.getOrElse(i) { 0 }.coerceIn(-6, 6)
            body[2 + i] = (db * 10 + 120).coerceIn(0, 255).toByte()
        }
        return buildPacket(CMD_SET_EQ, body)
    }

    fun buildDualConnSet(on: Boolean): ByteArray =
        buildPacket(CMD_DUAL_CONN, byteArrayOf(if (on) 0x01 else 0x00))

    fun buildAutoPauseSet(on: Boolean): ByteArray =
        buildPacket(CMD_AUTO_PAUSE, byteArrayOf(if (on) 0x01 else 0x00))

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off < total) {
            if (off + 5 > total) break
            var match = true
            for (k in 0..4) {
                if (buf[off + k] != RX_PREFIX[k]) { match = false; break }
            }
            if (!match) { off++; continue }
            if (off + 10 > total) break
            val cmd = ((buf[off + 5].toInt() and 0xFF) shl 8) or (buf[off + 6].toInt() and 0xFF)
            val pktLen = (buf[off + 7].toInt() and 0xFF) or ((buf[off + 8].toInt() and 0xFF) shl 8)
            if (pktLen < 10 || pktLen > 4096) { off++; continue }
            if (off + pktLen > total) break
            val bodyLen = pktLen - 10
            val body = if (bodyLen > 0) buf.copyOfRange(off + 9, off + 9 + bodyLen) else ByteArray(0)
            packets.add(Packet(cmd, body))
            off += pktLen
        }
        return packets to (if (off < total) buf.copyOfRange(off, total) else ByteArray(0))
    }

    fun scaleBat(raw: Int, maxSteps: Int = 5): Int {
        if (raw < 0) return -1
        if (raw > maxSteps && raw <= 100) return raw
        if (raw > 100) return -1
        return ((raw * 100) / maxSteps).coerceIn(0, 100)
    }
}
