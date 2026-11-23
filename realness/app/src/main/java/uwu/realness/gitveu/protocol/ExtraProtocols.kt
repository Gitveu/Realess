package uwu.realness.gitveu.protocol

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/**
 * Протоколы Sennheiser GAIA, Jabra, Google Pixel Buds (Maestro HDLC/RPC) и JBL GATT
 * (по базе kde-headphones).
 */
object SennheiserProtocol {
    const val SOF: Byte = 0xFF.toByte()
    const val VENDOR: Int = 0x0495
    const val TYPE_MASK: Int = 0x0180
    const val TYPE_NOTIFICATION: Int = 1
    const val TYPE_RESPONSE: Int = 2

    const val CMD_REGISTER_NOTIFY: Int = 0x0007
    const val CMD_GET_CHARGING: Int = 0x0602
    const val CMD_GET_BATTERY: Int = 0x0603
    const val CMD_SET_AUTO_ANSWER: Int = 0x080A
    const val CMD_GET_AUTO_ANSWER: Int = 0x080B
    const val CMD_SET_AUTO_PAUSE: Int = 0x080C
    const val CMD_GET_AUTO_PAUSE: Int = 0x080D
    const val CMD_SET_TRANSPARENT: Int = 0x1804
    const val CMD_GET_TRANSPARENT: Int = 0x1805
    const val CMD_SET_ANC_ENABLED: Int = 0x1A04
    const val CMD_GET_ANC_ENABLED: Int = 0x1A05
    const val CMD_GET_EQ_CONFIG: Int = 0x1000
    const val CMD_SET_EQ_BAND: Int = 0x1001
    const val CMD_GET_EQ_GAINS: Int = 0x1003
    const val CMD_SET_SPATIAL: Int = 0x3604
    const val CMD_GET_SPATIAL: Int = 0x3605

    fun buildEqGainsReq(): ByteArray = buildPacket(CMD_GET_EQ_GAINS, byteArrayOf(0x00))
    fun buildEqBandSet(bandIdx: Int, gainDb: Int): ByteArray =
        buildPacket(CMD_SET_EQ_BAND, byteArrayOf(bandIdx.toByte(), (gainDb.coerceIn(-6, 6) * 10).toByte()))

    data class Packet(val vendor: Int, val baseCmd: Int, val type: Int, val payload: ByteArray)

    fun buildPacket(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size.coerceAtMost(255)
        val out = ByteArray(8 + len)
        out[0] = SOF
        out[1] = 0x03
        out[2] = 0x00
        out[3] = len.toByte()
        out[4] = ((VENDOR ushr 8) and 0xFF).toByte()
        out[5] = (VENDOR and 0xFF).toByte()
        out[6] = ((cmd ushr 8) and 0xFF).toByte()
        out[7] = (cmd and 0xFF).toByte()
        if (len > 0) System.arraycopy(payload, 0, out, 8, len)
        return out
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off < total) {
            if (buf[off] != SOF) { off++; continue }
            if (off + 8 > total) break
            val flags = buf[off + 2].toInt() and 0xFF
            val hasCsum = (flags and 0x01) != 0
            val longLen = (flags and 0x02) != 0
            val hdrSize = if (longLen) 9 else 8
            if (off + hdrSize > total) break
            val pLen = if (longLen) {
                ((buf[off + 3].toInt() and 0xFF) shl 8) or (buf[off + 4].toInt() and 0xFF)
            } else {
                buf[off + 3].toInt() and 0xFF
            }
            val fullLen = hdrSize + pLen + (if (hasCsum) 1 else 0)
            if (pLen > 4096) { off++; continue }
            if (off + fullLen > total) break
            val vOff = if (longLen) off + 5 else off + 4
            val vendor = ((buf[vOff].toInt() and 0xFF) shl 8) or (buf[vOff + 1].toInt() and 0xFF)
            val cmd = ((buf[vOff + 2].toInt() and 0xFF) shl 8) or (buf[vOff + 3].toInt() and 0xFF)
            val payload = if (pLen > 0) buf.copyOfRange(off + hdrSize, off + hdrSize + pLen) else ByteArray(0)
            packets.add(Packet(vendor, cmd and TYPE_MASK.inv(), (cmd ushr 7) and 0x03, payload))
            off += fullLen
        }
        return packets to (if (off < total) buf.copyOfRange(off, total) else ByteArray(0))
    }
}

object JabraProtocol {
    const val HOST_ADDR: Byte = 0x04
    const val DEV_ADDR: Byte = 0x09
    const val TYPE_QUERY: Int = 0x40
    const val TYPE_CMD: Int = 0x80
    const val TYPE_REPLY: Int = 0xC0

    const val FEAT_BATTERY: Int = 0x1202
    const val FEAT_EQ_MODE: Int = 0x137E
    const val FEAT_NOISE_MODE: Int = 0x1387
    const val FEAT_AMBIENCE: Int = 0x13BE

    private val seqCounter = AtomicInteger(1)

    data class Packet(val type: Int, val feature: Int, val payload: ByteArray)

    fun buildPacket(type: Int, feature: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(6 + data.size)
        out[0] = HOST_ADDR
        out[1] = DEV_ADDR
        out[2] = (seqCounter.getAndIncrement() and 0xFF).toByte()
        out[3] = ((type and 0xC0) or ((6 + data.size) and 0x3F)).toByte()
        out[4] = ((feature ushr 8) and 0xFF).toByte()
        out[5] = (feature and 0xFF).toByte()
        if (data.isNotEmpty()) System.arraycopy(data, 0, out, 6, data.size)
        return out
    }

    fun buildBatteryReq(): ByteArray = buildPacket(TYPE_QUERY, FEAT_BATTERY)
    fun buildAmbienceReq(): ByteArray = buildPacket(TYPE_QUERY, FEAT_AMBIENCE, byteArrayOf(0x01))
    fun buildNoiseModeReq(): ByteArray = buildPacket(TYPE_QUERY, FEAT_NOISE_MODE)

    fun buildAmbienceSet(mode: T300Protocol.AncMode): ByteArray {
        val m: Byte = when (mode) {
            T300Protocol.AncMode.OFF -> 0x01
            T300Protocol.AncMode.TRANSPARENCY -> 0x02
            T300Protocol.AncMode.NOISE_REDUCTION -> 0x04
        }
        return buildPacket(TYPE_CMD, FEAT_AMBIENCE, byteArrayOf(0x01, m))
    }

    fun buildNoiseModeSet(mode: T300Protocol.AncMode): ByteArray {
        val m: Byte = when (mode) {
            T300Protocol.AncMode.OFF -> 0x00
            T300Protocol.AncMode.NOISE_REDUCTION -> 0x01
            T300Protocol.AncMode.TRANSPARENCY -> 0x03
        }
        return buildPacket(TYPE_CMD, FEAT_NOISE_MODE, byteArrayOf(m))
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off + 6 <= total) {
            val a0 = buf[off]
            val a1 = buf[off + 1]
            if (!((a0 == DEV_ADDR && a1 == HOST_ADDR) || (a0 == HOST_ADDR && a1 == DEV_ADDR))) {
                off++
                continue
            }
            val tb = buf[off + 3].toInt() and 0xFF
            val type = tb and 0xC0
            val len = tb and 0x3F
            if (len < 6) { off++; continue }
            if (off + len > total) break
            val feat = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
            val payload = if (len > 6) buf.copyOfRange(off + 6, off + len) else ByteArray(0)
            packets.add(Packet(type, feat, payload))
            off += len
        }
        return packets to (if (off < total) buf.copyOfRange(off, total) else ByteArray(0))
    }

    fun parseBattery(p: ByteArray): List<T300Protocol.BatteryInfo> {
        if (p.size < 2) return emptyList()
        val flags = p[0].toInt() and 0xFF
        val mainLvl = p[1].toInt() and 0xFF
        if (mainLvl > 100) return emptyList()
        val chg = (flags and 0x01) != 0
        val out = mutableListOf<T300Protocol.BatteryInfo>()
        if ((flags and 0x80) != 0 && p.size >= 5) {
            val count = p[4].toInt() and 0xFF
            if (p.size >= 5 + count * 3) {
                for (i in 0 until count) {
                    val o = 5 + i * 3
                    val lvl = p[o].toInt() and 0xFF
                    val unit = p[o + 2].toInt() and 0xFF
                    if (lvl in 0..100) {
                        when (unit) {
                            5 -> out.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, lvl, false))
                            4 -> out.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, lvl, false))
                            21 -> out.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.CASE, lvl, false))
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, mainLvl, chg))
            out.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, mainLvl, chg))
        }
        return out
    }
}

object PixelBudsProtocol {
    const val FLAG: Byte = 0x7E
    const val ESCAPE: Byte = 0x7D
    const val ESCAPE_MASK: Int = 0x20

    val PROBE_CHANNELS = intArrayOf(18, 19, 21, 23, 24, 26)
    private val CHANNEL_REMOTES = intArrayOf(2, 3, 5, 4, 6)

    val SERVICE_MAESTRO: Long = hash65599("maestro_pw.Maestro")
    val METHOD_GET_SW_INFO: Long = hash65599("GetSoftwareInfo")
    val METHOD_SUB_RUNTIME: Long = hash65599("SubscribeRuntimeInfo")
    val METHOD_WRITE_SETTING: Long = hash65599("WriteSetting")
    val METHOD_READ_SETTING: Long = hash65599("ReadSetting")
    val METHOD_SUB_SETTINGS: Long = hash65599("SubscribeToSettingsChanges")

    const val SETTING_OHD: Int = 2
    const val SETTING_MULTIPOINT: Int = 11
    const val SETTING_ANC: Int = 13

    data class RpcPacket(
        val type: Int = 0,
        val channel: Int = 0,
        val service: Long = 0,
        val method: Long = 0,
        val payload: ByteArray = ByteArray(0)
    )

    private fun hash65599(s: String): Long {
        var h = s.length.toLong() and 0xFFFFFFFFL
        var coeff = 65599L
        for (ch in s) {
            h = (h + coeff * (ch.code.toLong() and 0xFFL)) and 0xFFFFFFFFL
            coeff = (coeff * 65599L) and 0xFFFFFFFFL
        }
        return h
    }

    private fun channelAddress(channel: Int): Int {
        if (channel < 18 || channel >= 18 + 2 * CHANNEL_REMOTES.size) return 0
        val idx = channel - 18
        val local = if (idx < CHANNEL_REMOTES.size) 10 else 13
        val remote = CHANNEL_REMOTES[idx % CHANNEL_REMOTES.size]
        return (local shl 6) or (remote shl 10)
    }

    private fun putVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v ushr 7 != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    private fun putVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
        putVarint(out, ((field shl 3) or 0).toLong())
        putVarint(out, value)
    }

    private fun putFixed32Field(out: ByteArrayOutputStream, field: Int, value: Long) {
        putVarint(out, ((field shl 3) or 5).toLong())
        for (i in 0..3) out.write(((value ushr (8 * i)) and 0xFFL).toInt())
    }

    private fun putBytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        putVarint(out, ((field shl 3) or 2).toLong())
        putVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    fun buildRpcFrame(channel: Int, method: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        val rpc = ByteArrayOutputStream()
        putVarintField(rpc, 2, channel.toLong())
        putFixed32Field(rpc, 3, SERVICE_MAESTRO)
        putFixed32Field(rpc, 4, method)
        if (payload.isNotEmpty()) putBytesField(rpc, 5, payload)
        val rpcBytes = rpc.toByteArray()

        val body = ByteArrayOutputStream()
        var addr = channelAddress(channel)
        while (addr ushr 7 != 0) {
            body.write((addr and 0x7F) shl 1)
            addr = addr ushr 7
        }
        body.write(((addr and 0x7F) shl 1) or 1)
        body.write(0x03) // UI frame control
        body.write(rpcBytes)
        val bodyBytes = body.toByteArray()
        val crc = CRC32().apply { update(bodyBytes) }.value
        for (i in 0..3) body.write(((crc ushr (8 * i)) and 0xFFL).toInt())
        val raw = body.toByteArray()

        val out = ByteArrayOutputStream(raw.size + 8)
        out.write(FLAG.toInt())
        for (b in raw) {
            if (b == FLAG || b == ESCAPE) {
                out.write(ESCAPE.toInt())
                out.write((b.toInt() and 0xFF) xor ESCAPE_MASK)
            } else {
                out.write(b.toInt() and 0xFF)
            }
        }
        out.write(FLAG.toInt())
        return out.toByteArray()
    }

    fun buildReadSetting(channel: Int, settingId: Int): ByteArray {
        val msg = ByteArrayOutputStream()
        putVarintField(msg, 4, settingId.toLong())
        return buildRpcFrame(channel, METHOD_READ_SETTING, msg.toByteArray())
    }

    fun buildWriteSettingVarint(channel: Int, settingId: Int, value: Long): ByteArray {
        val sv = ByteArrayOutputStream()
        putVarintField(sv, settingId, value)
        val msg = ByteArrayOutputStream()
        putBytesField(msg, 4, sv.toByteArray())
        return buildRpcFrame(channel, METHOD_WRITE_SETTING, msg.toByteArray())
    }

    const val SETTING_USER_EQ: Int = 16

    fun buildWriteUserEq(channel: Int, gainsDb: List<Int>): ByteArray {
        val eq = ByteArrayOutputStream()
        for (i in 0 until 5) {
            val f = gainsDb.getOrElse(i) { 0 }.coerceIn(-6, 6).toFloat()
            if (f != 0f) {
                putFixed32Field(eq, i + 1, java.lang.Float.floatToRawIntBits(f).toLong() and 0xFFFFFFFFL)
            }
        }
        val sv = ByteArrayOutputStream()
        putBytesField(sv, SETTING_USER_EQ, eq.toByteArray())
        val msg = ByteArrayOutputStream()
        putBytesField(msg, 4, sv.toByteArray())
        return buildRpcFrame(channel, METHOD_WRITE_SETTING, msg.toByteArray())
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<RpcPacket>, ByteArray> {
        val packets = mutableListOf<RpcPacket>()
        var off = 0
        val total = buf.size
        while (off < total) {
            val start = (off until total).firstOrNull { buf[it] == FLAG } ?: return packets to ByteArray(0)
            val end = ((start + 1) until total).firstOrNull { buf[it] == FLAG }
                ?: return packets to buf.copyOfRange(start, total)
            if (end > start + 1) {
                val unesc = ByteArrayOutputStream()
                var i = start + 1
                var ok = true
                while (i < end) {
                    val b = buf[i]
                    if (b == ESCAPE) {
                        if (++i >= end) { ok = false; break }
                        unesc.write((buf[i].toInt() and 0xFF) xor ESCAPE_MASK)
                    } else {
                        unesc.write(b.toInt() and 0xFF)
                    }
                    i++
                }
                val raw = unesc.toByteArray()
                if (ok && raw.size >= 6) {
                    val dataEnd = raw.size - 4
                    var n = 0
                    while (n < dataEnd && n < 5) {
                        val b = raw[n].toInt() and 0xFF
                        if ((b and 1) != 0) break
                        n++
                    }
                    val rpcStart = n + 2
                    if (rpcStart < dataEnd) {
                        decodeRpc(raw.copyOfRange(rpcStart, dataEnd))?.let { packets.add(it) }
                    }
                }
            }
            off = end
        }
        return packets to ByteArray(0)
    }

    data class ProtoField(val num: Int, val wire: Int, val varint: Long = 0, val bytes: ByteArray = ByteArray(0))

    fun readProtoFields(data: ByteArray): List<ProtoField> {
        val list = mutableListOf<ProtoField>()
        var pos = 0
        fun readVarint(): Long? {
            var res = 0L
            var shift = 0
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                res = res or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
            }
            return null
        }
        while (pos < data.size) {
            val tag = readVarint() ?: break
            val num = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint() ?: break
                    list.add(ProtoField(num, wire, varint = v))
                }
                1 -> {
                    if (pos + 8 > data.size) break
                    pos += 8
                }
                2 -> {
                    val l = (readVarint() ?: break).toInt()
                    if (l < 0 || pos + l > data.size) break
                    list.add(ProtoField(num, wire, bytes = data.copyOfRange(pos, pos + l)))
                    pos += l
                }
                5 -> {
                    if (pos + 4 > data.size) break
                    var v = 0L
                    for (i in 0..3) v = v or ((data[pos + i].toInt() and 0xFF).toLong() shl (8 * i))
                    pos += 4
                    list.add(ProtoField(num, wire, varint = v))
                }
                else -> break
            }
        }
        return list
    }

    private fun decodeRpc(data: ByteArray): RpcPacket? {
        val fields = readProtoFields(data)
        if (fields.isEmpty()) return null
        var type = 0; var ch = 0; var svc = 0L; var mth = 0L; var p = ByteArray(0)
        for (f in fields) {
            when (f.num) {
                1 -> type = f.varint.toInt()
                2 -> ch = f.varint.toInt()
                3 -> svc = f.varint
                4 -> mth = f.varint
                5 -> p = f.bytes
            }
        }
        return RpcPacket(type, ch, svc, mth, p)
    }
}

object JblProtocol {
    const val HEADER: Byte = 0xAA.toByte()
    const val CMD_STATUS_GET: Int = 0x21
    const val CMD_STATUS_RET: Int = 0x22
    const val CMD_BATTERY: Int = 0x25
    const val CMD_ANC_SWITCH: Int = 0x31
    const val CMD_AUTO_PLAY: Int = 0x35
    const val CMD_EQ_PRESET: Int = 0x40
    const val CMD_NOISE: Int = 0x91
    const val CMD_SPATIAL: Int = 0x9D

    data class Packet(val command: Int, val payload: ByteArray)

    fun buildPacket(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(3 + payload.size)
        out[0] = HEADER
        out[1] = (cmd and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    fun buildBatteryReq(): ByteArray = buildPacket(CMD_BATTERY)
    fun buildNoiseReq(): ByteArray = buildPacket(CMD_NOISE, byteArrayOf(0x11))
    fun buildStatusReq(field: Byte): ByteArray = buildPacket(CMD_STATUS_GET, byteArrayOf(field))

    fun buildAncSet(mode: T300Protocol.AncMode): ByteArray {
        val anc: Byte = if (mode == T300Protocol.AncMode.NOISE_REDUCTION) 0x01 else 0x00
        val amb: Byte = if (mode == T300Protocol.AncMode.TRANSPARENCY) 0x01 else 0x00
        return buildPacket(CMD_NOISE, byteArrayOf(0x10, 0x01, anc, 0x02, amb, 0x03, 0x00))
    }

    fun buildEqSet(preset: T300Protocol.EqPreset): ByteArray {
        val code: Byte = when (preset) {
            T300Protocol.EqPreset.ORIGINAL_SOUND -> 0x00 // Off/Studio
            T300Protocol.EqPreset.SERENADE -> 0x02       // Vocal
            T300Protocol.EqPreset.PURE_BASS -> 0x07      // Club
            T300Protocol.EqPreset.DEEP_BASS -> 0x03      // Bass
        }
        return buildPacket(CMD_EQ_PRESET, byteArrayOf(code))
    }

    fun buildAutoPlaySet(on: Boolean): ByteArray = buildPacket(CMD_AUTO_PLAY, byteArrayOf(if (on) 0x01 else 0x00))
    fun buildSpatialSet(on: Boolean): ByteArray = buildPacket(CMD_SPATIAL, byteArrayOf(0x01, if (on) 0x01 else 0x00, 0x01))

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off < total) {
            if (buf[off] != HEADER) { off++; continue }
            if (off + 3 > total) break
            val cmd = buf[off + 1].toInt() and 0xFF
            val len = buf[off + 2].toInt() and 0xFF
            if (off + 3 + len > total) break
            val payload = if (len > 0) buf.copyOfRange(off + 3, off + 3 + len) else ByteArray(0)
            packets.add(Packet(cmd, payload))
            off += 3 + len
        }
        return packets to (if (off < total) buf.copyOfRange(off, total) else ByteArray(0))
    }
}
