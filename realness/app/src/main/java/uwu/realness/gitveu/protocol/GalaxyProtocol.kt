package uwu.realness.gitveu.protocol

/**
 * Протокол Samsung Galaxy Buds RFCOMM SPP
 * (по базе kde-headphones / GalaxyBudsClient).
 *
 * Формат современного кадра (Buds+ / Live / Pro / Buds2 / FE / Buds3 / Core / Buds4):
 *   Byte 0:        0xFD — Start of Frame
 *   Byte 1..2:     header (bits 0..9 = size = 1 + payload.size + 2, bit 12 = type) LE
 *   Byte 3:        msgId (1 byte)
 *   Byte 4..N:     payload
 *   Last 3 bytes:  CRC16-CCITT (uint16 LE по msgId + payload), затем 0xDD (EOM)
 *   Полная длина = 4 + size.
 *
 * Формат legacy кадра (Galaxy Buds 2019):
 *   Byte 0:        0xFE — Start of Frame
 *   Byte 1:        0x00 (type)
 *   Byte 2:        size (1 + payload.size + 2)
 *   Byte 3:        msgId
 *   Byte 4..N:     payload
 *   Last 3 bytes:  CRC16-CCITT (uint16 LE), затем 0xEE (EOM)
 */
object GalaxyProtocol {

    const val SOM_MODERN: Byte = 0xFD.toByte()
    const val EOM_MODERN: Byte = 0xDD.toByte()
    const val SOM_LEGACY: Byte = 0xFE.toByte()
    const val EOM_LEGACY: Byte = 0xEE.toByte()

    // Команды (msgId)
    const val MSG_STATUS_UPDATED: Int = 0x60
    const val MSG_EXTENDED_STATUS_UPDATED: Int = 0x61
    const val MSG_NOISE_CONTROLS_UPDATE: Int = 0x77
    const val MSG_NOISE_CONTROLS: Int = 0x78
    const val MSG_SET_AMBIENT: Int = 0x80
    const val MSG_AMBIENT_UPDATE: Int = 0x81
    const val MSG_SET_EQUALIZER: Int = 0x86
    const val MSG_MANAGER_INFO: Int = 0x88
    const val MSG_SET_NOISE_REDUCTION: Int = 0x98
    const val MSG_NOISE_REDUCTION_UPDATE: Int = 0x9B
    const val MSG_SET_SEAMLESS_CONNECTION: Int = 0xAF

    data class Packet(
        val msgId: Int,
        val payload: ByteArray
    )

    data class ParsedStatus(
        val batteries: List<T300Protocol.BatteryInfo>,
        val ancMode: T300Protocol.AncMode?,
        val eqPreset: T300Protocol.EqPreset?,
        val customEqName: String?,
        val seamlessConnection: Boolean?
    )

    fun crc16Ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0x0000
        for (i in offset until (offset + length)) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    fun buildPacket(msgId: Int, payload: ByteArray = ByteArray(0), legacy: Boolean = false): ByteArray {
        val size = 1 + payload.size + 2 // msgId + payload + crc(2)
        val crcBuf = ByteArray(1 + payload.size)
        crcBuf[0] = (msgId and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, crcBuf, 1, payload.size)
        }
        val crc = crc16Ccitt(crcBuf)

        val out = ByteArray(4 + size)
        if (legacy) {
            out[0] = SOM_LEGACY
            out[1] = 0x00
            out[2] = (size and 0xFF).toByte()
            out[3] = (msgId and 0xFF).toByte()
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, out, 4, payload.size)
            }
            val crcOff = 4 + payload.size
            out[crcOff] = (crc and 0xFF).toByte()
            out[crcOff + 1] = ((crc ushr 8) and 0xFF).toByte()
            out[crcOff + 2] = EOM_LEGACY
        } else {
            out[0] = SOM_MODERN
            out[1] = (size and 0xFF).toByte()
            out[2] = ((size ushr 8) and 0x03).toByte()
            out[3] = (msgId and 0xFF).toByte()
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, out, 4, payload.size)
            }
            val crcOff = 4 + payload.size
            out[crcOff] = (crc and 0xFF).toByte()
            out[crcOff + 1] = ((crc ushr 8) and 0xFF).toByte()
            out[crcOff + 2] = EOM_MODERN
        }
        return out
    }

    fun buildManagerInfo(legacy: Boolean = false): ByteArray =
        buildPacket(MSG_MANAGER_INFO, byteArrayOf(0x01, 0x02, 0x22), legacy)

    fun buildAncWrite(mode: T300Protocol.AncMode, model: T300Protocol.BudsModel): ByteArray {
        val legacy = model == T300Protocol.BudsModel.GALAXY_BUDS
        return when (model) {
            T300Protocol.BudsModel.GALAXY_BUDS_LIVE -> {
                val on: Byte = if (mode == T300Protocol.AncMode.NOISE_REDUCTION) 0x01 else 0x00
                buildPacket(MSG_SET_NOISE_REDUCTION, byteArrayOf(on), false)
            }
            T300Protocol.BudsModel.GALAXY_BUDS,
            T300Protocol.BudsModel.GALAXY_BUDS_PLUS -> {
                val on: Byte = if (mode == T300Protocol.AncMode.TRANSPARENCY) 0x01 else 0x00
                buildPacket(MSG_SET_AMBIENT, byteArrayOf(on), legacy)
            }
            else -> {
                val b: Byte = when (mode) {
                    T300Protocol.AncMode.OFF -> 0x00
                    T300Protocol.AncMode.NOISE_REDUCTION -> 0x01
                    T300Protocol.AncMode.TRANSPARENCY -> 0x02
                }
                buildPacket(MSG_NOISE_CONTROLS, byteArrayOf(b), false)
            }
        }
    }

    fun buildAncLevelWrite(level: HuaweiProtocol.AncLevel, model: T300Protocol.BudsModel): ByteArray {
        // Buds3 Pro / Buds4 support Adaptive ANC (0x03) on MSG_NOISE_CONTROLS
        val code: Byte = if (level == HuaweiProtocol.AncLevel.DYNAMIC &&
            (model == T300Protocol.BudsModel.GALAXY_BUDS_3_PRO || model == T300Protocol.BudsModel.GALAXY_BUDS_4)
        ) {
            0x03
        } else {
            0x01
        }
        return buildPacket(MSG_NOISE_CONTROLS, byteArrayOf(code), false)
    }

    fun buildEqWrite(preset: T300Protocol.EqPreset, legacy: Boolean): ByteArray {
        val code: Int = when (preset) {
            T300Protocol.EqPreset.ORIGINAL_SOUND -> 0 // Normal
            T300Protocol.EqPreset.DEEP_BASS -> 1      // Bass Boost
            T300Protocol.EqPreset.PURE_BASS -> 3      // Dynamic
            T300Protocol.EqPreset.SERENADE -> 4       // Clear
        }
        return if (legacy) {
            val payload = if (code == 0) byteArrayOf(0x00, 0x05) else byteArrayOf(0x01, (code + 4).toByte())
            buildPacket(MSG_SET_EQUALIZER, payload, true)
        } else {
            buildPacket(MSG_SET_EQUALIZER, byteArrayOf(code.toByte()), false)
        }
    }

    fun buildSeamlessWrite(enabled: Boolean, legacy: Boolean = false): ByteArray =
        buildPacket(MSG_SET_SEAMLESS_CONNECTION, byteArrayOf(if (enabled) 0x00 else 0x01), legacy)

    fun mapEqCode(code: Int): Pair<T300Protocol.EqPreset?, String?> = when (code) {
        0 -> T300Protocol.EqPreset.ORIGINAL_SOUND to null
        1 -> T300Protocol.EqPreset.DEEP_BASS to null
        2 -> null to "Soft"
        3 -> T300Protocol.EqPreset.PURE_BASS to null
        4 -> T300Protocol.EqPreset.SERENADE to null
        5 -> null to "Treble Boost"
        else -> null to null
    }

    fun mapNoiseControlByte(b: Int): T300Protocol.AncMode? = when (b) {
        0 -> T300Protocol.AncMode.OFF
        1, 3 -> T300Protocol.AncMode.NOISE_REDUCTION // 3 = Adaptive ANC on Buds3 Pro / 4 Pro
        2 -> T300Protocol.AncMode.TRANSPARENCY
        else -> null
    }

    fun parseExtendedStatus(p: ByteArray, model: T300Protocol.BudsModel): ParsedStatus? {
        if (p.size < 8) return null
        val rev = p[0].toInt() and 0xFF
        val batL = p[2].toInt() and 0xFF
        val batR = p[3].toInt() and 0xFF
        val placement = p[6].toInt() and 0xFF
        val batCase = p[7].toInt() and 0xFF

        val leftPlace = (placement ushr 4) and 0x0F
        val rightPlace = placement and 0x0F

        var leftChg = false
        var rightChg = false
        var caseChg = false
        if (p.size > 36 && rev >= 10) {
            val ch = p[36].toInt() and 0xFF
            leftChg = (ch and 0x10) != 0
            rightChg = (ch and 0x04) != 0
            caseChg = (ch and 0x01) != 0
        }

        val lVal = if (batL in 1..100 || (batL == 0 && leftPlace != 0)) batL else -1
        val rVal = if (batR in 1..100 || (batR == 0 && rightPlace != 0)) batR else -1
        val cVal = if (batCase in 1..100) batCase else -1

        val batteries = listOf(
            T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, lVal, leftChg),
            T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, rVal, rightChg),
            T300Protocol.BatteryInfo(T300Protocol.ComponentRole.CASE, cVal, caseChg)
        )

        val eqIdx = if (model == T300Protocol.BudsModel.GALAXY_BUDS_PLUS) 11 else 9
        val eqPair = if (p.size > eqIdx) mapEqCode(p[eqIdx].toInt() and 0xFF) else (null to null)

        val ancMode = when (model) {
            T300Protocol.BudsModel.GALAXY_BUDS_LIVE -> {
                if (p.size > 12) {
                    if ((p[12].toInt() and 0xFF) == 1) T300Protocol.AncMode.NOISE_REDUCTION else T300Protocol.AncMode.OFF
                } else null
            }
            T300Protocol.BudsModel.GALAXY_BUDS,
            T300Protocol.BudsModel.GALAXY_BUDS_PLUS -> null
            else -> {
                if (p.size > 12) mapNoiseControlByte(p[12].toInt() and 0xFF) else null
            }
        }

        val seamless = if (p.size > 19) (p[19].toInt() and 0xFF) == 0 else null

        return ParsedStatus(
            batteries = batteries,
            ancMode = ancMode,
            eqPreset = eqPair.first,
            customEqName = eqPair.second,
            seamlessConnection = seamless
        )
    }

    fun parseStatusUpdated(p: ByteArray): List<T300Protocol.BatteryInfo> {
        if (p.size < 7) return emptyList()
        val batL = p[1].toInt() and 0xFF
        val batR = p[2].toInt() and 0xFF
        val placement = p[5].toInt() and 0xFF
        val batCase = p[6].toInt() and 0xFF

        val leftPlace = (placement ushr 4) and 0x0F
        val rightPlace = placement and 0x0F

        val lVal = if (batL in 1..100 || (batL == 0 && leftPlace != 0)) batL else -1
        val rVal = if (batR in 1..100 || (batR == 0 && rightPlace != 0)) batR else -1
        val cVal = if (batCase in 1..100) batCase else -1

        return listOf(
            T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, lVal, false),
            T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, rVal, false),
            T300Protocol.BatteryInfo(T300Protocol.ComponentRole.CASE, cVal, false)
        )
    }

    /**
     * Потоковый парсер пакетов Samsung Galaxy Buds SPP (0xFD..0xDD и legacy 0xFE..0xEE).
     */
    fun parsePacketsFromStreamBuffer(buf: ByteArray): Pair<List<Packet>, ByteArray> {
        val packets = mutableListOf<Packet>()
        var off = 0
        val total = buf.size

        while (off < total) {
            val b0 = buf[off]
            if (b0 != SOM_MODERN && b0 != SOM_LEGACY) {
                off++
                continue
            }
            if (off + 4 > total) break

            val isLegacy = (b0 == SOM_LEGACY)
            val size = if (isLegacy) {
                buf[off + 2].toInt() and 0xFF
            } else {
                ((buf[off + 1].toInt() and 0xFF) or ((buf[off + 2].toInt() and 0x03) shl 8))
            }

            if (size < 3 || size > 1024) {
                off++
                continue
            }

            val fullLen = 4 + size
            if (off + fullLen > total) break

            val expectedEom = if (isLegacy) EOM_LEGACY else EOM_MODERN
            if (buf[off + fullLen - 1] != expectedEom) {
                off++
                continue
            }

            val msgId = buf[off + 3].toInt() and 0xFF
            val payloadLen = size - 3 // minus msgId(1) and crc(2)
            val payload = if (payloadLen > 0) {
                buf.copyOfRange(off + 4, off + 4 + payloadLen)
            } else {
                ByteArray(0)
            }
            packets.add(Packet(msgId, payload))
            off += fullLen
        }

        val remaining = if (off < total) buf.copyOfRange(off, total) else ByteArray(0)
        return Pair(packets, remaining)
    }

    const val MSG_GAMING_MODE: Int = 0x87
    const val MSG_FIND_MY_EARBUDS_START: Int = 0xA0
    const val MSG_FIND_MY_EARBUDS_STOP: Int = 0xA1

    fun buildGameModeWrite(enabled: Boolean, legacy: Boolean): ByteArray =
        buildPacket(MSG_GAMING_MODE, byteArrayOf(if (enabled) 0x01 else 0x00), legacy)

    fun buildFindBudsWrite(enabled: Boolean, legacy: Boolean): ByteArray =
        buildPacket(if (enabled) MSG_FIND_MY_EARBUDS_START else MSG_FIND_MY_EARBUDS_STOP, ByteArray(0), legacy)
}
