package uwu.realness.gitveu.protocol

import androidx.annotation.StringRes
import uwu.realness.gitveu.R
import java.io.ByteArrayOutputStream

/**
 * Протокол Huawei SPP (по базе OpenFreebuds: https://github.com/melianmiko/OpenFreebuds).
 *
 * Формат кадра:
 *   Byte 0:       0x5A ('Z') — Start of Frame
 *   Byte 1..2:    length (uint16 big-endian, равно body.size + 1)
 *   Byte 3:       0x00
 *   Byte 4..5:    commandId (uint16 big-endian)
 *   Byte 6..N:    TLV-параметры [type (1B), len (1B), value (len B)]...
 *   Last 2 bytes: CRC16-XMODEM (uint16 big-endian) по всем байтам до CRC
 *
 * Полная длина кадра на проводе = length + 5 байт.
 */
object HuaweiProtocol {

    const val SOF: Byte = 0x5A

    // Команды Huawei SPP
    const val CMD_BATTERY_READ: Int = 0x0108
    const val CMD_BATTERY_NOTIFY: Int = 0x0127
    const val CMD_DUAL_TAP_WRITE: Int = 0x011F
    const val CMD_DUAL_TAP_READ: Int = 0x0120
    const val CMD_TRIPLE_TAP_WRITE: Int = 0x0125
    const val CMD_TRIPLE_TAP_READ: Int = 0x0126

    const val CMD_ANC_LEGACY_EVENT: Int = 0x2B03
    const val CMD_ANC_WRITE: Int = 0x2B04
    const val CMD_ANC_READ: Int = 0x2B2A

    const val CMD_AUTO_PAUSE_WRITE: Int = 0x2B10
    const val CMD_AUTO_PAUSE_READ: Int = 0x2B11

    const val CMD_LONG_TAP_WRITE: Int = 0x2B16
    const val CMD_LONG_TAP_READ: Int = 0x2B17

    const val CMD_DUAL_CONNECT_WRITE: Int = 0x2B2E
    const val CMD_DUAL_CONNECT_READ: Int = 0x2B2F
    const val CMD_DUAL_CONNECT_ENUMERATE: Int = 0x2B31
    const val CMD_DUAL_CONNECT_CHANGE_EVENT: Int = 0x2B36

    const val CMD_EQ_WRITE: Int = 0x2B49
    const val CMD_EQ_READ: Int = 0x2B4A
    const val CMD_HONOR_EQ_WRITE: Int = 0x2701

    const val CMD_LOW_LATENCY: Int = 0x2B6C

    const val CMD_SOUND_QUALITY_WRITE: Int = 0x2BA2
    const val CMD_SOUND_QUALITY_READ: Int = 0x2BA3

    enum class AncLevel(val levelByte: Byte, @StringRes val titleRes: Int) {
        COMFORT(0x01, R.string.anc_level_comfort),
        GENERAL(0x00, R.string.anc_level_general),
        ULTRA(0x02, R.string.anc_level_ultra),
        DYNAMIC(0x03, R.string.anc_level_dynamic);

        companion object {
            fun fromByte(b: Byte): AncLevel? = entries.firstOrNull { it.levelByte == b }
        }
    }

    data class Packet(
        val commandId: Int,
        val params: Map<Int, ByteArray>
    ) {
        fun param(type: Int): ByteArray? = params[type]
    }

    private val CRC16_TAB = intArrayOf(
        0, 4129, 8258, 12387, 16516, 20645, 24774, 28903, -32504, -28375, -24246, -20117, -15988, -11859,
        -7730, -3601, 4657, 528, 12915, 8786, 21173, 17044, 29431, 25302, -27847, -31976, -19589, -23718, -11331,
        -15460, -3073, -7202, 9314, 13379, 1056, 5121, 25830, 29895, 17572, 21637, -23190, -19125, -31448,
        -27383, -6674, -2609, -14932, -10867, 13907, 9842, 5649, 1584, 30423, 26358, 22165, 18100, -18597,
        -22662, -26855, -30920, -2081, -6146, -10339, -14404, 18628, 22757, 26758, 30887, 2112, 6241, 10242,
        14371, -13876, -9747, -5746, -1617, -30392, -26263, -22262, -18133, 23285, 19156, 31415, 27286, 6769,
        2640, 14899, 10770, -9219, -13348, -1089, -5218, -25735, -29864, -17605, -21734, 27814, 31879, 19684,
        23749, 11298, 15363, 3168, 7233, -4690, -625, -12820, -8755, -21206, -17141, -29336, -25271, 32407,
        28342, 24277, 20212, 15891, 11826, 7761, 3696, -97, -4162, -8227, -12292, -16613, -20678, -24743,
        -28808, -28280, -32343, -20022, -24085, -12020, -16083, -3762, -7825, 4224, 161, 12482, 8419, 20484,
        16421, 28742, 24679, -31815, -27752, -23557, -19494, -15555, -11492, -7297, -3234, 689, 4752, 8947,
        13010, 16949, 21012, 25207, 29270, -18966, -23093, -27224, -31351, -2706, -6833, -10964, -15091, 13538,
        9411, 5280, 1153, 29798, 25671, 21540, 17413, -22565, -18438, -30823, -26696, -6305, -2178, -14563,
        -10436, 9939, 14066, 1681, 5808, 26199, 30326, 17941, 22068, -9908, -13971, -1778, -5841, -26168,
        -30231, -18038, -22101, 22596, 18533, 30726, 26663, 6336, 2273, 14466, 10403, -13443, -9380, -5313,
        -1250, -29703, -25640, -21573, -17510, 19061, 23124, 27191, 31254, 2801, 6864, 10931, 14994, -722,
        -4849, -8852, -12979, -16982, -21109, -25112, -29239, 31782, 27655, 23652, 19525, 15522, 11395, 7392,
        3265, -4321, -194, -12451, -8324, -20581, -16454, -28711, -24584, 28183, 32310, 20053, 24180, 11923,
        16050, 3793, 7920
    )

    fun crc16Xmodem(data: ByteArray, length: Int = data.size): Int {
        var s = 0
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xFF
            s = (CRC16_TAB[((s ushr 8) xor b) and 0xFF] xor (s shl 8)) and 0xFFFF
        }
        return s
    }

    fun buildPacket(cmd: Int, params: List<Pair<Int, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((cmd ushr 8) and 0xFF)
        body.write(cmd and 0xFF)
        for ((pType, pVal) in params) {
            body.write(pType and 0xFF)
            body.write(pVal.size and 0xFF)
            if (pVal.isNotEmpty()) body.write(pVal)
        }
        val bodyBytes = body.toByteArray()
        val lenField = bodyBytes.size + 1

        val raw = ByteArray(4 + bodyBytes.size + 2)
        raw[0] = SOF
        raw[1] = ((lenField ushr 8) and 0xFF).toByte()
        raw[2] = (lenField and 0xFF).toByte()
        raw[3] = 0x00
        System.arraycopy(bodyBytes, 0, raw, 4, bodyBytes.size)

        val crc = crc16Xmodem(raw, 4 + bodyBytes.size)
        raw[4 + bodyBytes.size] = ((crc ushr 8) and 0xFF).toByte()
        raw[5 + bodyBytes.size] = (crc and 0xFF).toByte()
        return raw
    }

    fun buildReadRequest(cmd: Int, vararg paramTypes: Int): ByteArray =
        buildPacket(cmd, paramTypes.map { it to ByteArray(0) })

    fun buildByteWrite(cmd: Int, paramType: Int, value: Byte): ByteArray =
        buildPacket(cmd, listOf(paramType to byteArrayOf(value)))

    fun buildBatteryRequest(): ByteArray = buildReadRequest(CMD_BATTERY_READ, 1, 2, 3)

    fun buildAncReadRequest(): ByteArray = buildReadRequest(CMD_ANC_READ, 1, 2)

    fun buildAncModeWrite(mode: T300Protocol.AncMode): ByteArray {
        val modeByte: Byte = when (mode) {
            T300Protocol.AncMode.OFF -> 0x00
            T300Protocol.AncMode.NOISE_REDUCTION -> 0x01
            T300Protocol.AncMode.TRANSPARENCY -> 0x02
        }
        val second: Byte = if (modeByte.toInt() == 0) 0x00 else 0xFF.toByte()
        return buildPacket(CMD_ANC_WRITE, listOf(1 to byteArrayOf(modeByte, second)))
    }

    /**
     * HONOR CHOICE Earbuds (X8 / X5 / X5 Pro / X3 / X2 / X):
     * 1-байтовое значение режима ANC в param 1 команды 0x2B04.
     */
    fun buildHonorChoiceAncWrite(mode: T300Protocol.AncMode): ByteArray {
        val modeByte: Byte = when (mode) {
            T300Protocol.AncMode.OFF -> 0x00
            T300Protocol.AncMode.NOISE_REDUCTION -> 0x01
            T300Protocol.AncMode.TRANSPARENCY -> 0x02
        }
        return buildByteWrite(CMD_ANC_WRITE, 1, modeByte)
    }

    /**
     * HONOR CHOICE Earbuds EQ write: команда 0x2701, param 1 = 1 байт (0=Balanced, 1=Bass, 2=Vocal).
     */
    fun buildHonorChoiceEqWrite(preset: T300Protocol.EqPreset): ByteArray {
        val code: Byte = when (preset) {
            T300Protocol.EqPreset.ORIGINAL_SOUND -> 0x00
            T300Protocol.EqPreset.DEEP_BASS,
            T300Protocol.EqPreset.PURE_BASS -> 0x01
            T300Protocol.EqPreset.SERENADE -> 0x02
        }
        return buildByteWrite(CMD_HONOR_EQ_WRITE, 1, code)
    }

    fun buildAncLevelWrite(level: AncLevel): ByteArray =
        buildPacket(CMD_ANC_WRITE, listOf(1 to byteArrayOf(0x01, level.levelByte)))

    fun buildAutoPauseRead(): ByteArray = buildReadRequest(CMD_AUTO_PAUSE_READ, 1)
    fun buildAutoPauseWrite(enabled: Boolean): ByteArray =
        buildByteWrite(CMD_AUTO_PAUSE_WRITE, 1, if (enabled) 0x01 else 0x00)

    fun buildLowLatencyRead(): ByteArray = buildReadRequest(CMD_LOW_LATENCY, 2)
    fun buildLowLatencyWrite(enabled: Boolean): ByteArray =
        buildByteWrite(CMD_LOW_LATENCY, 1, if (enabled) 0x01 else 0x00)

    fun buildSoundQualityRead(): ByteArray = buildReadRequest(CMD_SOUND_QUALITY_READ, 1)
    fun buildSoundQualityWrite(highQuality: Boolean): ByteArray =
        buildByteWrite(CMD_SOUND_QUALITY_WRITE, 1, if (highQuality) 0x01 else 0x00)

    fun buildDualConnectRead(): ByteArray = buildReadRequest(CMD_DUAL_CONNECT_READ, 1)
    fun buildDualConnectWrite(enabled: Boolean): ByteArray =
        buildByteWrite(CMD_DUAL_CONNECT_WRITE, 1, if (enabled) 0x01 else 0x00)

    fun buildDualConnectEnumerate(): ByteArray = buildReadRequest(CMD_DUAL_CONNECT_ENUMERATE, 1)

    fun buildEqRead(): ByteArray = buildReadRequest(CMD_EQ_READ, 1, 2, 3, 4, 5, 6, 7, 8)
    fun buildEqWrite(huaweiEqId: Byte): ByteArray = buildByteWrite(CMD_EQ_WRITE, 1, huaweiEqId)

    fun buildDoubleTapRead(): ByteArray = buildReadRequest(CMD_DUAL_TAP_READ, 1, 2)
    fun buildTripleTapRead(): ByteArray = buildReadRequest(CMD_TRIPLE_TAP_READ, 1, 2)
    fun buildLongTapRead(): ByteArray = buildReadRequest(CMD_LONG_TAP_READ, 1, 2)

    fun buildKeyWrite(gesture: T300Protocol.TouchGesture, side: T300Protocol.TouchSide, code: Byte): ByteArray {
        val cmd = when (gesture) {
            T300Protocol.TouchGesture.DOUBLE_TAP -> CMD_DUAL_TAP_WRITE
            T300Protocol.TouchGesture.TRIPLE_TAP -> CMD_TRIPLE_TAP_WRITE
            T300Protocol.TouchGesture.LONG_PRESS -> CMD_LONG_TAP_WRITE
        }
        val paramType = if (side == T300Protocol.TouchSide.LEFT) 1 else 2
        return buildByteWrite(cmd, paramType, code)
    }

    /**
     * Потоковый парсер пакетов Huawei SPP (SOF 0x5A).
     * Возвращает (список распознанных пакетов, необработанный хвост буфера).
     */
    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size

        while (off < total) {
            if (buf[off] != SOF) {
                off++
                continue
            }
            if (off + 4 > total) break
            if (buf[off + 3].toInt() != 0) {
                off++
                continue
            }
            val length = ((buf[off + 1].toInt() and 0xFF) shl 8) or (buf[off + 2].toInt() and 0xFF)
            if (length < 3 || length > 2048) {
                off++
                continue
            }
            val fullLen = length + 5
            if (off + fullLen > total) break

            val cmd = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
            val params = LinkedHashMap<Int, ByteArray>()
            var pos = off + 6
            val bodyEnd = off + 3 + length
            while (pos + 1 < bodyEnd) {
                val pType = buf[pos].toInt() and 0xFF
                val pLen = buf[pos + 1].toInt() and 0xFF
                val valStart = pos + 2
                val valEnd = valStart + pLen
                if (valEnd > bodyEnd) break
                params[pType] = buf.copyOfRange(valStart, valEnd)
                pos = valEnd
            }
            packets.add(Packet(cmd, params))
            off += fullLen
        }

        val remaining = if (off < total) buf.copyOfRange(off, total) else ByteArray(0)
        return Pair(packets, remaining)
    }

    // Маппинг EQ между нашим UI (EqPreset) и Huawei EQ ID
    fun eqPresetToHuaweiId(preset: T300Protocol.EqPreset, pro3Ids: Boolean): Byte {
        return if (pro3Ids) {
            when (preset) {
                T300Protocol.EqPreset.ORIGINAL_SOUND -> 5
                T300Protocol.EqPreset.DEEP_BASS -> 1
                T300Protocol.EqPreset.PURE_BASS -> 2
                T300Protocol.EqPreset.SERENADE -> 9
            }
        } else {
            when (preset) {
                T300Protocol.EqPreset.ORIGINAL_SOUND -> 1
                T300Protocol.EqPreset.DEEP_BASS -> 2
                T300Protocol.EqPreset.PURE_BASS -> 3
                T300Protocol.EqPreset.SERENADE -> 9
            }
        }
    }

    fun huaweiIdToEqPreset(id: Int, pro3Ids: Boolean): T300Protocol.EqPreset? {
        return if (pro3Ids) {
            when (id) {
                5 -> T300Protocol.EqPreset.ORIGINAL_SOUND
                1 -> T300Protocol.EqPreset.DEEP_BASS
                2 -> T300Protocol.EqPreset.PURE_BASS
                9 -> T300Protocol.EqPreset.SERENADE
                else -> null
            }
        } else {
            when (id) {
                1 -> T300Protocol.EqPreset.ORIGINAL_SOUND
                2 -> T300Protocol.EqPreset.DEEP_BASS
                3 -> T300Protocol.EqPreset.PURE_BASS
                9 -> T300Protocol.EqPreset.SERENADE
                else -> null
            }
        }
    }

    // Парсинг кастомных EQ-режимов из param 8 (блоки по 36 байт)
    fun parseCustomEqNames(data: ByteArray): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var off = 0
        while (off + 36 <= data.size) {
            val modeId = data[off].toInt() // signed byte
            val countLines = data[off + 1].toInt() and 0xFF
            val labelStart = off + 2 + countLines
            val blockEnd = off + 36
            if (labelStart < blockEnd) {
                var zeroIdx = labelStart
                while (zeroIdx < blockEnd && data[zeroIdx].toInt() != 0) zeroIdx++
                val name = String(data, labelStart, zeroIdx - labelStart, Charsets.UTF_8).trim()
                if (name.isNotEmpty()) result[modeId] = name
            }
            off += 36
        }
        return result
    }

    // Маппинг действий жестов Huawei <-> TouchAction
    fun huaweiTapCodeToAction(
        code: Int,
        gesture: T300Protocol.TouchGesture,
        isFreeBuds3: Boolean
    ): T300Protocol.TouchAction {
        if (gesture == T300Protocol.TouchGesture.LONG_PRESS) {
            return when (code) {
                10 -> T300Protocol.TouchAction.ANC_TOGGLE
                0 -> T300Protocol.TouchAction.VOICE_ASSISTANT
                18 -> T300Protocol.TouchAction.VOLUME_UP
                19 -> T300Protocol.TouchAction.VOLUME_DOWN
                else -> T300Protocol.TouchAction.NONE
            }
        }
        if (isFreeBuds3) {
            return when (code) {
                0 -> T300Protocol.TouchAction.VOICE_ASSISTANT
                1 -> T300Protocol.TouchAction.PLAY_PAUSE
                3 -> T300Protocol.TouchAction.ANC_TOGGLE
                4 -> T300Protocol.TouchAction.NEXT_TRACK
                else -> T300Protocol.TouchAction.NONE
            }
        }
        return when (code) {
            1 -> T300Protocol.TouchAction.PLAY_PAUSE
            2 -> T300Protocol.TouchAction.NEXT_TRACK
            7 -> T300Protocol.TouchAction.PREV_TRACK
            0 -> T300Protocol.TouchAction.VOICE_ASSISTANT
            else -> T300Protocol.TouchAction.NONE
        }
    }

    fun actionToHuaweiTapCode(
        action: T300Protocol.TouchAction,
        gesture: T300Protocol.TouchGesture,
        isFreeBuds3: Boolean
    ): Byte {
        if (gesture == T300Protocol.TouchGesture.LONG_PRESS) {
            return when (action) {
                T300Protocol.TouchAction.ANC_TOGGLE -> 10
                T300Protocol.TouchAction.VOICE_ASSISTANT -> 0
                T300Protocol.TouchAction.VOLUME_UP -> 18
                T300Protocol.TouchAction.VOLUME_DOWN -> 19
                else -> (-1).toByte()
            }
        }
        if (isFreeBuds3) {
            return when (action) {
                T300Protocol.TouchAction.VOICE_ASSISTANT -> 0
                T300Protocol.TouchAction.PLAY_PAUSE -> 1
                T300Protocol.TouchAction.ANC_TOGGLE -> 3
                T300Protocol.TouchAction.NEXT_TRACK -> 4
                else -> (-1).toByte()
            }
        }
        return when (action) {
            T300Protocol.TouchAction.PLAY_PAUSE -> 1
            T300Protocol.TouchAction.NEXT_TRACK -> 2
            T300Protocol.TouchAction.PREV_TRACK -> 7
            T300Protocol.TouchAction.VOICE_ASSISTANT -> 0
            else -> (-1).toByte()
        }
    }

    fun getAvailableActions(
        model: T300Protocol.BudsModel,
        gesture: T300Protocol.TouchGesture
    ): List<T300Protocol.TouchAction> {
        return when (gesture) {
            T300Protocol.TouchGesture.LONG_PRESS -> {
                if (model == T300Protocol.BudsModel.HUAWEI_FREECLIP_2 ||
                    model == T300Protocol.BudsModel.HUAWEI_FREEBUDS_SE_4
                ) {
                    listOf(
                        T300Protocol.TouchAction.ANC_TOGGLE,
                        T300Protocol.TouchAction.VOICE_ASSISTANT,
                        T300Protocol.TouchAction.VOLUME_UP,
                        T300Protocol.TouchAction.VOLUME_DOWN,
                        T300Protocol.TouchAction.NONE
                    )
                } else {
                    listOf(
                        T300Protocol.TouchAction.ANC_TOGGLE,
                        T300Protocol.TouchAction.NONE
                    )
                }
            }
            T300Protocol.TouchGesture.DOUBLE_TAP,
            T300Protocol.TouchGesture.TRIPLE_TAP -> {
                if (model == T300Protocol.BudsModel.HUAWEI_FREEBUDS_3) {
                    listOf(
                        T300Protocol.TouchAction.PLAY_PAUSE,
                        T300Protocol.TouchAction.NEXT_TRACK,
                        T300Protocol.TouchAction.ANC_TOGGLE,
                        T300Protocol.TouchAction.VOICE_ASSISTANT,
                        T300Protocol.TouchAction.NONE
                    )
                } else {
                    listOf(
                        T300Protocol.TouchAction.PLAY_PAUSE,
                        T300Protocol.TouchAction.NEXT_TRACK,
                        T300Protocol.TouchAction.PREV_TRACK,
                        T300Protocol.TouchAction.VOICE_ASSISTANT,
                        T300Protocol.TouchAction.NONE
                    )
                }
            }
        }
    }
}
