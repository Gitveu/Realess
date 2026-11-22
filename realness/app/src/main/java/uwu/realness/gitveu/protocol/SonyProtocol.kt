package uwu.realness.gitveu.protocol

import java.io.ByteArrayOutputStream

/**
 * Протокол Sony MDR (V1 / V2) RFCOMM SPP
 * (по базе kde-headphones / SonyHeadphonesClient).
 *
 * Формат кадра:
 *   0x3E + escape( [dataType, seq, lenBE(4B), payload..., sum8] ) + 0x3C
 *   Escape: 0x3C -> 3D 2C, 0x3D -> 3D 2D, 0x3E -> 3D 2E
 */
object SonyProtocol {
    const val START_MARKER: Byte = 0x3E
    const val END_MARKER: Byte = 0x3C
    const val ESC_MARKER: Byte = 0x3D

    const val TYPE_ACK: Byte = 0x01
    const val TYPE_DATA_MDR: Byte = 0x0C // 12

    // MDR Commands (V2/V1 compatible)
    const val CMD_POWER_GET_STATUS: Int = 0x22
    const val CMD_POWER_RET_STATUS: Int = 0x23
    const val CMD_POWER_NTFY_STATUS: Int = 0x25

    const val CMD_EQ_GET_PARAM: Int = 0x56
    const val CMD_EQ_RET_PARAM: Int = 0x57
    const val CMD_EQ_SET_PARAM: Int = 0x58
    const val CMD_EQ_NTFY_PARAM: Int = 0x59

    const val CMD_NCASM_GET_PARAM: Int = 0x66
    const val CMD_NCASM_RET_PARAM: Int = 0x67
    const val CMD_NCASM_SET_PARAM: Int = 0x68
    const val CMD_NCASM_NTFY_PARAM: Int = 0x69

    data class Packet(
        val dataType: Int,
        val seq: Int,
        val payload: ByteArray
    )

    fun pack(dataType: Byte, seq: Int, payload: ByteArray): ByteArray {
        val raw = ByteArray(6 + payload.size + 1)
        raw[0] = dataType
        raw[1] = (seq and 0xFF).toByte()
        val len = payload.size
        raw[2] = ((len ushr 24) and 0xFF).toByte()
        raw[3] = ((len ushr 16) and 0xFF).toByte()
        raw[4] = ((len ushr 8) and 0xFF).toByte()
        raw[5] = (len and 0xFF).toByte()
        if (len > 0) System.arraycopy(payload, 0, raw, 6, len)
        var sum = 0
        for (i in 0 until 6 + len) sum = (sum + (raw[i].toInt() and 0xFF)) and 0xFF
        raw[6 + len] = sum.toByte()

        val out = ByteArrayOutputStream(raw.size + 8)
        out.write(START_MARKER.toInt())
        for (b in raw) {
            val u = b.toInt() and 0xFF
            if (u == 0x3C || u == 0x3D || u == 0x3E) {
                out.write(ESC_MARKER.toInt())
                out.write(u and 0xEF)
            } else {
                out.write(u)
            }
        }
        out.write(END_MARKER.toInt())
        return out.toByteArray()
    }

    fun buildAck(rxSeq: Int): ByteArray = pack(TYPE_ACK, (1 - (rxSeq and 1)), ByteArray(0))

    fun buildBatteryReq(seq: Int, inquiredType: Byte): ByteArray =
        pack(TYPE_DATA_MDR, seq, byteArrayOf(CMD_POWER_GET_STATUS.toByte(), inquiredType))

    fun buildAncReq(seq: Int): ByteArray =
        pack(TYPE_DATA_MDR, seq, byteArrayOf(CMD_NCASM_GET_PARAM.toByte(), 0x17))

    fun buildAncSet(seq: Int, mode: T300Protocol.AncMode): ByteArray {
        // MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS (0x17):
        // [0x68, 0x17, valueChange=0x01, ncAsmTotal(1=on,0=off), ncAsmMode(0=NC,1=ASM), asmMode(0=normal), asmLevel(10)]
        val payload = when (mode) {
            T300Protocol.AncMode.OFF -> byteArrayOf(CMD_NCASM_SET_PARAM.toByte(), 0x17, 0x01, 0x00, 0x00, 0x00, 0x00)
            T300Protocol.AncMode.NOISE_REDUCTION -> byteArrayOf(CMD_NCASM_SET_PARAM.toByte(), 0x17, 0x01, 0x01, 0x00, 0x00, 0x00)
            T300Protocol.AncMode.TRANSPARENCY -> byteArrayOf(CMD_NCASM_SET_PARAM.toByte(), 0x17, 0x01, 0x01, 0x01, 0x00, 0x10)
        }
        return pack(TYPE_DATA_MDR, seq, payload)
    }

    fun buildEqReq(seq: Int): ByteArray =
        pack(TYPE_DATA_MDR, seq, byteArrayOf(CMD_EQ_GET_PARAM.toByte(), 0x01))

    fun buildEqSet(seq: Int, preset: T300Protocol.EqPreset): ByteArray {
        val sonyPreset: Byte = when (preset) {
            T300Protocol.EqPreset.ORIGINAL_SOUND -> 0x00 // Off
            T300Protocol.EqPreset.SERENADE -> 0x04       // Vocal
            T300Protocol.EqPreset.PURE_BASS -> 0x01      // Bright / Excited
            T300Protocol.EqPreset.DEEP_BASS -> 0x05      // Bass Boost (Clear Bass)
        }
        return pack(TYPE_DATA_MDR, seq, byteArrayOf(CMD_EQ_SET_PARAM.toByte(), 0x01, sonyPreset, 0x00))
    }

    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size
        while (off < total) {
            val start = (off until total).firstOrNull { buf[it] == START_MARKER } ?: return packets to ByteArray(0)
            val end = ((start + 1) until total).firstOrNull { buf[it] == END_MARKER }
                ?: return packets to buf.copyOfRange(start, total)
            val rawOut = ByteArrayOutputStream()
            var i = start + 1
            var bad = false
            while (i < end) {
                val b = buf[i]
                if (b == ESC_MARKER) {
                    if (i + 1 >= end) { bad = true; break }
                    rawOut.write((buf[++i].toInt() and 0xFF) or 0x10)
                } else {
                    rawOut.write(b.toInt() and 0xFF)
                }
                i++
            }
            if (!bad) {
                val raw = rawOut.toByteArray()
                if (raw.size >= 7) {
                    val dataType = raw[0].toInt() and 0xFF
                    val seq = raw[1].toInt() and 0xFF
                    val len = ((raw[2].toInt() and 0xFF) shl 24) or
                        ((raw[3].toInt() and 0xFF) shl 16) or
                        ((raw[4].toInt() and 0xFF) shl 8) or
                        (raw[5].toInt() and 0xFF)
                    if (len >= 0 && 6 + len < raw.size) {
                        val payload = if (len > 0) raw.copyOfRange(6, 6 + len) else ByteArray(0)
                        packets.add(Packet(dataType, seq, payload))
                    }
                }
            }
            off = end + 1
        }
        return packets to ByteArray(0)
    }
}
