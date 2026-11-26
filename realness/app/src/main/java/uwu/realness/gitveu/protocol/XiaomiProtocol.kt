package uwu.realness.gitveu.protocol

import java.util.concurrent.atomic.AtomicInteger

/**
 * Протокол Xiaomi / Redmi Buds RFCOMM SPP
 * (по базе kde-headphones/XiaomiBackend.cpp, UUID 0000FD2D-0000-1000-8000-00805F9B34FB).
 *
 * Формат кадра:
 *   FE DC BA | type(1) | opcode(1) | len16BE | [0x00 if response] | seq(1) | payload | EF
 * Включает полную SAFER+ аутентификацию challenge-response (0x50 / 0x51).
 */
object XiaomiProtocol {
    const val TYPE_PHONE_REQ: Int = 0xC4
    const val TYPE_RESPONSE: Int = 0x04
    const val REQUEST_BIT: Int = 0x40

    const val OP_DEVICE_INFO: Int = 0x02
    const val OP_ANC: Int = 0x08
    const val OP_RUN_INFO: Int = 0x09
    const val OP_STATUS: Int = 0x0E
    const val OP_AUTH_CHALLENGE: Int = 0x50
    const val OP_AUTH_CONFIRM: Int = 0x51
    const val OP_SET_CONFIG: Int = 0xF2
    const val OP_GET_CONFIG: Int = 0xF3
    const val OP_NOTIFY_CONFIG: Int = 0xF4

    const val CFG_AUTO_ANSWER: Int = 0x03
    const val CFG_MULTIPOINT: Int = 0x04
    const val CFG_EQ_PRESET: Int = 0x07
    const val CFG_EFFECT_STRENGTH: Int = 0x0B
    const val CFG_LOW_LATENCY: Int = 0x2F
    const val CFG_CUSTOM_EQ: Int = 0x37

    private val seqCounter = AtomicInteger(1)

    data class Packet(val type: Int, val opcode: Int, val seq: Int, val payload: ByteArray) {
        val isResponse: Boolean get() = (type and REQUEST_BIT) == 0
    }

    // SAFER+ tables for Xiaomi challenge-response
    private val expTab = IntArray(256)
    private val logTab = IntArray(256)
    private val biasTab = Array(16) { IntArray(16) }

    init {
        for (i in 0..255) {
            val v = powMod(45, i, 257)
            expTab[i] = if (v == 256) 0 else v
            logTab[expTab[i]] = i
        }
        for (k in 0..15) {
            for (j in 0..15) {
                val v = powMod(45, powMod(45, 17 * (k + 2) + j + 1, 257), 257)
                biasTab[k][j] = if (v == 256) 0 else v
            }
        }
    }

    private fun powMod(b: Int, e: Int, m: Int): Int {
        var res = 1
        var base = b % m
        var exp = e
        while (exp > 0) {
            if ((exp and 1) != 0) res = (res * base) % m
            base = (base * base) % m
            exp = exp ushr 1
        }
        return res
    }

    private fun xorLane(i: Int): Boolean = ((0x9999 ushr i) and 1) != 0

    private fun linearLayer(v: IntArray) {
        val shuffle = intArrayOf(8, 11, 12, 15, 2, 1, 6, 5, 10, 9, 14, 13, 0, 7, 4, 3)
        for (pass in 0..3) {
            var i = 0
            while (i < 16) {
                val a = v[i]
                val b = v[i + 1]
                v[i] = (2 * a + b) and 0xFF
                v[i + 1] = (a + b) and 0xFF
                i += 2
            }
            if (pass == 3) break
            val copy = v.copyOf()
            for (j in 0..15) v[j] = copy[shuffle[j]]
        }
    }

    fun challengeResponse(challenge: ByteArray): ByteArray {
        val input = intArrayOf(0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33)
        val keys = Array(17) { IntArray(16) }
        for (i in 0..15) keys[0][i] = challenge[i].toInt() and 0xFF
        keys[0][15] = keys[0][15] xor 6
        val reg = IntArray(17)
        for (i in 0..15) {
            reg[i] = keys[0][i]
            reg[16] = reg[16] xor keys[0][i]
        }
        for (k in 1..16) {
            for (i in 0..16) reg[i] = ((reg[i] shl 3) or (reg[i] ushr 5)) and 0xFF
            for (i in 0..15) keys[k][i] = (reg[(k + i) % 17] + biasTab[k - 1][i]) and 0xFF
        }
        val v = input.copyOf()
        for (round in 0..7) {
            if (round == 2) {
                for (i in 0..15) v[i] = if (xorLane(i)) (v[i] xor input[i]) else ((v[i] + input[i]) and 0xFF)
            }
            val k1 = keys[2 * round]
            val k2 = keys[2 * round + 1]
            for (i in 0..15) {
                v[i] = if (xorLane(i)) {
                    (expTab[v[i] xor k1[i]] + k2[i]) and 0xFF
                } else {
                    logTab[(v[i] + k1[i]) and 0xFF] xor k2[i]
                }
            }
            linearLayer(v)
        }
        val out = ByteArray(16)
        for (i in 0..15) {
            val b = if (xorLane(i)) (v[i] xor keys[16][i]) else ((v[i] + keys[16][i]) and 0xFF)
            out[i] = (b and 0xFF).toByte()
        }
        return out
    }

    fun nextSeq(): Int = seqCounter.getAndIncrement() and 0xFF

    fun encodeFrame(type: Int, opcode: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val isRsp = (type and REQUEST_BIT) == 0
        val len = payload.size + (if (isRsp) 2 else 1)
        val out = ByteArray(7 + len + 1)
        out[0] = 0xFE.toByte()
        out[1] = 0xDC.toByte()
        out[2] = 0xBA.toByte()
        out[3] = (type and 0xFF).toByte()
        out[4] = (opcode and 0xFF).toByte()
        out[5] = ((len ushr 8) and 0xFF).toByte()
        out[6] = (len and 0xFF).toByte()
        var pos = 7
        if (isRsp) out[pos++] = 0x00
        out[pos++] = (seq and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, pos, payload.size)
            pos += payload.size
        }
        out[pos] = 0xEF.toByte()
        return out
    }

    fun buildRequest(opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        encodeFrame(TYPE_PHONE_REQ, opcode, nextSeq(), payload)

    fun buildResponse(opcode: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        encodeFrame(TYPE_RESPONSE, opcode, seq, payload)

    fun buildAuthStart(): ByteArray {
        val rand = ByteArray(17)
        rand[0] = 0x00
        for (i in 1..16) rand[i] = (i * 17).toByte()
        return buildRequest(OP_AUTH_CHALLENGE, rand)
    }

    fun buildDeviceInfoReq(): ByteArray = buildRequest(OP_DEVICE_INFO, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    fun buildRunInfoReq(): ByteArray = buildRequest(OP_RUN_INFO, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    fun buildGetConfigReq(key: Int): ByteArray =
        buildRequest(OP_GET_CONFIG, byteArrayOf(((key ushr 8) and 0xFF).toByte(), (key and 0xFF).toByte()))

    fun buildSetConfig(key: Int, value: ByteArray): ByteArray {
        val p = ByteArray(3 + value.size)
        p[0] = (2 + value.size).toByte()
        p[1] = ((key ushr 8) and 0xFF).toByte()
        p[2] = (key and 0xFF).toByte()
        System.arraycopy(value, 0, p, 3, value.size)
        return buildRequest(OP_SET_CONFIG, p)
    }

    fun buildLowLatencyReq(): ByteArray = buildGetConfigReq(CFG_LOW_LATENCY)
    fun buildLowLatencySet(enabled: Boolean): ByteArray =
        buildSetConfig(CFG_LOW_LATENCY, byteArrayOf(if (enabled) 0x01 else 0x00))

    fun buildCustomEqReq(): ByteArray = buildGetConfigReq(CFG_CUSTOM_EQ)

    /**
     * Save custom EQ curve via RCSP config 0x0037 (MiBudsClient/equalizer.py).
     * Sign-magnitude gain encoding: positive = gain, negative = 128 - gain (i.e. 0x80 | abs(gain)).
     */
    fun buildCustomEqSave(freqs: List<Int>, gains: List<Int>, bound: Int = 10): ByteArray {
        val n = minOf(freqs.size, gains.size)
        val header = byteArrayOf(0x01, 0x0A, 0x01, 0x01, 0x01, 0x00, n.toByte())
        val out = ByteArray(header.size + n * 3)
        System.arraycopy(header, 0, out, 0, header.size)
        var off = header.size
        for (i in 0 until n) {
            val f = freqs[i].coerceIn(1, 65535)
            val g = gains[i].coerceIn(-bound, bound)
            out[off++] = ((f ushr 8) and 0xFF).toByte()
            out[off++] = (f and 0xFF).toByte()
            out[off++] = (if (g >= 0) g else (128 - g)).toByte()
        }
        return buildSetConfig(CFG_CUSTOM_EQ, out)
    }

    fun buildCustomEqReset(): ByteArray =
        buildSetConfig(CFG_CUSTOM_EQ, byteArrayOf(0x04, 0x01, 0x01, 0x01))

    /**
     * Parse config records from 0xF3 / 0xF4 payload: [len, keyHi, keyLo, val...]
     */
    fun parseConfigRecords(p: ByteArray): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        var pos = 0
        while (pos + 3 <= p.size) {
            val len = p[pos].toInt() and 0xFF
            val key = ((p[pos + 1].toInt() and 0xFF) shl 8) or (p[pos + 2].toInt() and 0xFF)
            if (len == 0xFF) {
                if (pos + 5 > p.size) break
                val extLen = ((p[pos + 3].toInt() and 0xFF) shl 8) or (p[pos + 4].toInt() and 0xFF)
                val end = pos + 5 + extLen
                if (end > p.size) break
                out[key] = p.copyOfRange(pos + 5, end)
                pos = end
            } else {
                val end = pos + 1 + len
                if (len < 2 || end > p.size) break
                out[key] = p.copyOfRange(pos + 3, end)
                pos = end
            }
        }
        return out
    }

    /**
     * Parse custom EQ record (config 0x0037) -> Pair(freqs, gains).
     */
    fun parseCustomEqValue(v: ByteArray): Pair<List<Int>, List<Int>>? {
        if (v.size < 7 || v[0].toInt() != 1) return null
        val nameLen = v[5].toInt() and 0xFF
        val off = 6 + nameLen
        if (off >= v.size) return null
        val count = v[off].toInt() and 0xFF
        if (count == 0 || off + 1 + count * 3 > v.size) return null
        val freqs = ArrayList<Int>(count)
        val gains = ArrayList<Int>(count)
        var pos = off + 1
        for (i in 0 until count) {
            val freq = ((v[pos].toInt() and 0xFF) shl 8) or (v[pos + 1].toInt() and 0xFF)
            val raw = v[pos + 2].toInt() and 0xFF
            val gain = if (raw < 128) raw else (128 - raw)
            freqs.add(freq)
            gains.add(gain)
            pos += 3
        }
        return freqs to gains
    }

    fun buildAncSet(mode: BudsProtocol.AncMode): ByteArray {
        val m: Byte = when (mode) {
            BudsProtocol.AncMode.OFF -> 0x00
            BudsProtocol.AncMode.NOISE_REDUCTION -> 0x01
            BudsProtocol.AncMode.TRANSPARENCY -> 0x02
        }
        return buildRequest(OP_ANC, byteArrayOf(0x02, 0x04, m))
    }

    fun buildAncStrengthSet(level: HuaweiProtocol.AncLevel): ByteArray {
        val code: Byte = when (level) {
            HuaweiProtocol.AncLevel.COMFORT -> 0x01 // Light
            HuaweiProtocol.AncLevel.GENERAL -> 0x00 // Balanced
            HuaweiProtocol.AncLevel.ULTRA -> 0x02   // Deep
            HuaweiProtocol.AncLevel.DYNAMIC -> 0x03 // Adaptive
        }
        return buildSetConfig(CFG_EFFECT_STRENGTH, byteArrayOf(0x01, code))
    }

    fun buildEarDetectionSet(on: Boolean): ByteArray =
        buildRequest(OP_ANC, byteArrayOf(0x02, 0x06, if (on) 0x00 else 0x01))

    fun buildEqSet(preset: BudsProtocol.EqPreset): ByteArray {
        val id: Byte = when (preset) {
            BudsProtocol.EqPreset.ORIGINAL_SOUND -> 0x00 // Standard
            BudsProtocol.EqPreset.SERENADE -> 0x01       // Vocal
            BudsProtocol.EqPreset.PURE_BASS -> 0x06      // Treble boost
            BudsProtocol.EqPreset.DEEP_BASS -> 0x05      // Bass boost
        }
        return buildSetConfig(CFG_EQ_PRESET, byteArrayOf(id))
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off < total) {
            if (off + 3 > total) break
            if (buf[off] != 0xFE.toByte() || buf[off + 1] != 0xDC.toByte() || buf[off + 2] != 0xBA.toByte()) {
                off++
                continue
            }
            if (off + 7 > total) break
            val type = buf[off + 3].toInt() and 0xFF
            val opcode = buf[off + 4].toInt() and 0xFF
            val len = ((buf[off + 5].toInt() and 0xFF) shl 8) or (buf[off + 6].toInt() and 0xFF)
            if (len < 1 || len > 1024) { off++; continue }
            val fullLen = 7 + len + 1
            if (off + fullLen > total) break
            if (buf[off + fullLen - 1] != 0xEF.toByte()) { off++; continue }
            val isRsp = (type and REQUEST_BIT) == 0
            val hdrSkip = if (isRsp) 2 else 1
            if (len >= hdrSkip) {
                val seq = buf[off + 7 + (if (isRsp) 1 else 0)].toInt() and 0xFF
                val pStart = off + 7 + hdrSkip
                val pEnd = off + 7 + len
                val payload = if (pEnd > pStart) buf.copyOfRange(pStart, pEnd) else ByteArray(0)
                packets.add(Packet(type, opcode, seq, payload))
            }
            off += fullLen
        }
        return packets to (if (off < total) buf.copyOfRange(off, total) else ByteArray(0))
    }

    fun parseBatteryTriplet(p: ByteArray, offset: Int): List<BudsProtocol.BatteryInfo> {
        if (p.size < offset + 3) return emptyList()
        fun decode(idx: Int, role: BudsProtocol.ComponentRole): BudsProtocol.BatteryInfo {
            val v = p[offset + idx].toInt() and 0xFF
            if (v == 0xFF || (v and 0x7F) > 100) return BudsProtocol.BatteryInfo(role, -1, false)
            return BudsProtocol.BatteryInfo(role, v and 0x7F, (v and 0x80) != 0)
        }
        return listOf(
            decode(0, BudsProtocol.ComponentRole.LEFT_EAR),
            decode(1, BudsProtocol.ComponentRole.RIGHT_EAR),
            decode(2, BudsProtocol.ComponentRole.CASE)
        )
    }
}
