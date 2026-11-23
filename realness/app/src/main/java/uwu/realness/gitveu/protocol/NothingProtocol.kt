package uwu.realness.gitveu.protocol

import java.util.concurrent.atomic.AtomicInteger

/**
 * Протокол Nothing Ear / CMF by Nothing RFCOMM SPP
 * (по базам swift-nothing-ear, omarchy-nothing-ear, kde-headphones).
 *
 * Формат кадра:
 *   Byte 0:       0x55 — Start of Frame
 *   Byte 1..2:    0x0160 LE (60 01; bit 0x0020 = CRC присутствует)
 *   Byte 3..4:    commandId (uint16 LE)
 *   Byte 5..6:    payloadLen (uint16 LE)
 *   Byte 7:       operationId (1..250, инкремент)
 *   Byte 8..N:    payload (payloadLen байт)
 *   Last 2 bytes: CRC16/MODBUS (uint16 LE) по байтам [0 .. 8 + payloadLen)
 */
object NothingProtocol {

    const val SOF: Byte = 0x55
    const val HEADER_FLAGS: Int = 0x0160
    private const val FLAG_HAS_CRC: Int = 0x0020

    // Команды Nothing / CMF SPP
    const val CMD_HANDSHAKE_REQ: Int = 0xC006
    const val CMD_HANDSHAKE_RSP: Int = 0x4006

    const val CMD_BATTERY_REQ: Int = 0xC007
    const val CMD_BATTERY_RSP: Int = 0x4007
    const val CMD_BATTERY_EVENT: Int = 0xE001

    const val CMD_IN_EAR_REQ: Int = 0xC00E
    const val CMD_IN_EAR_RSP: Int = 0x400E
    const val CMD_IN_EAR_SET: Int = 0xF004

    const val CMD_ANC_REQ: Int = 0xC01E
    const val CMD_ANC_RSP: Int = 0x401E
    const val CMD_ANC_EVENT: Int = 0xE003
    const val CMD_ANC_SET: Int = 0xF00F

    // Classic EQ (Nothing Ear / Headphone / CMF Buds Pro / Neckband Pro)
    const val CMD_EQ_CLASSIC_REQ: Int = 0xC01F
    const val CMD_EQ_CLASSIC_RSP: Int = 0x401F
    const val CMD_EQ_CLASSIC_SET: Int = 0xF010

    // ListeningMode EQ (CMF Buds / Buds 2 / 2a / 2 Plus / Buds Pro 2 / Headphone Pro)
    const val CMD_EQ_LISTENING_REQ: Int = 0xC050
    const val CMD_EQ_LISTENING_RSP: Int = 0x4050
    const val CMD_EQ_LISTENING_SET: Int = 0xF01D

    // Low Latency (Game Mode)
    const val CMD_LOW_LATENCY_REQ: Int = 0xC041
    const val CMD_LOW_LATENCY_RSP: Int = 0x4041
    const val CMD_LOW_LATENCY_SET: Int = 0xF040

    // Ultra Bass / Dynamic Bass Boost
    const val CMD_BASS_BOOST_REQ: Int = 0xC04E
    const val CMD_BASS_BOOST_RSP: Int = 0x404E
    const val CMD_BASS_BOOST_SET: Int = 0xF051

    // ANC коды Nothing
    const val ANC_HIGH: Byte = 0x01
    const val ANC_MID: Byte = 0x02
    const val ANC_LOW: Byte = 0x03
    const val ANC_ADAPTIVE: Byte = 0x04
    const val ANC_OFF: Byte = 0x05
    const val ANC_TRANSPARENCY: Byte = 0x07

    private val opCounter = AtomicInteger(1)

    data class Packet(
        val commandId: Int,
        val operationId: Int,
        val payload: ByteArray
    )

    private fun nextOpId(): Byte {
        val v = opCounter.getAndUpdate { if (it >= 250) 1 else it + 1 }
        return (v and 0xFF).toByte()
    }

    fun crc16Modbus(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    fun buildPacket(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val bodyLen = 8 + payload.size
        val raw = ByteArray(bodyLen + 2)
        raw[0] = SOF
        raw[1] = (HEADER_FLAGS and 0xFF).toByte()
        raw[2] = ((HEADER_FLAGS ushr 8) and 0xFF).toByte()
        raw[3] = (cmd and 0xFF).toByte()
        raw[4] = ((cmd ushr 8) and 0xFF).toByte()
        raw[5] = (payload.size and 0xFF).toByte()
        raw[6] = ((payload.size ushr 8) and 0xFF).toByte()
        raw[7] = nextOpId()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, raw, 8, payload.size)
        }
        val crc = crc16Modbus(raw, bodyLen)
        raw[bodyLen] = (crc and 0xFF).toByte()
        raw[bodyLen + 1] = ((crc ushr 8) and 0xFF).toByte()
        return raw
    }

    fun buildHandshakeRequest(): ByteArray = buildPacket(CMD_HANDSHAKE_REQ)
    fun buildBatteryRequest(): ByteArray = buildPacket(CMD_BATTERY_REQ)
    fun buildAncReadRequest(): ByteArray = buildPacket(CMD_ANC_REQ)

    fun buildAncModeWrite(mode: T300Protocol.AncMode, preferredLevel: HuaweiProtocol.AncLevel?): ByteArray {
        val code: Byte = when (mode) {
            T300Protocol.AncMode.OFF -> ANC_OFF
            T300Protocol.AncMode.TRANSPARENCY -> ANC_TRANSPARENCY
            T300Protocol.AncMode.NOISE_REDUCTION -> ancLevelToByte(preferredLevel ?: HuaweiProtocol.AncLevel.ULTRA)
        }
        return buildPacket(CMD_ANC_SET, byteArrayOf(0x01, code, 0x00))
    }

    fun buildAncLevelWrite(level: HuaweiProtocol.AncLevel): ByteArray =
        buildPacket(CMD_ANC_SET, byteArrayOf(0x01, ancLevelToByte(level), 0x00))

    fun ancLevelToByte(level: HuaweiProtocol.AncLevel): Byte = when (level) {
        HuaweiProtocol.AncLevel.COMFORT -> ANC_LOW
        HuaweiProtocol.AncLevel.GENERAL -> ANC_MID
        HuaweiProtocol.AncLevel.ULTRA -> ANC_HIGH
        HuaweiProtocol.AncLevel.DYNAMIC -> ANC_ADAPTIVE
    }

    fun byteToAncState(code: Int): Pair<T300Protocol.AncMode, HuaweiProtocol.AncLevel?>? = when (code) {
        1 -> T300Protocol.AncMode.NOISE_REDUCTION to HuaweiProtocol.AncLevel.ULTRA
        2 -> T300Protocol.AncMode.NOISE_REDUCTION to HuaweiProtocol.AncLevel.GENERAL
        3 -> T300Protocol.AncMode.NOISE_REDUCTION to HuaweiProtocol.AncLevel.COMFORT
        4 -> T300Protocol.AncMode.NOISE_REDUCTION to HuaweiProtocol.AncLevel.DYNAMIC
        5 -> T300Protocol.AncMode.OFF to null
        7 -> T300Protocol.AncMode.TRANSPARENCY to null
        else -> null
    }

    fun buildEqRead(useListeningMode: Boolean): ByteArray =
        buildPacket(if (useListeningMode) CMD_EQ_LISTENING_REQ else CMD_EQ_CLASSIC_REQ)

    fun buildEqWrite(preset: T300Protocol.EqPreset, useListeningMode: Boolean): ByteArray {
        val id = eqPresetToNothingId(preset, useListeningMode)
        val cmd = if (useListeningMode) CMD_EQ_LISTENING_SET else CMD_EQ_CLASSIC_SET
        return buildPacket(cmd, byteArrayOf(id, 0x00))
    }

    fun eqPresetToNothingId(preset: T300Protocol.EqPreset, useListeningMode: Boolean): Byte {
        return if (useListeningMode) {
            when (preset) {
                T300Protocol.EqPreset.ORIGINAL_SOUND -> 3 // Pop / Balanced
                T300Protocol.EqPreset.SERENADE -> 4       // Enhance Vocals
                T300Protocol.EqPreset.PURE_BASS -> 2      // Electronic
                T300Protocol.EqPreset.DEEP_BASS -> 1      // Rock
            }
        } else {
            when (preset) {
                T300Protocol.EqPreset.ORIGINAL_SOUND -> 0 // Balanced
                T300Protocol.EqPreset.SERENADE -> 1       // Voice
                T300Protocol.EqPreset.PURE_BASS -> 2      // More Treble
                T300Protocol.EqPreset.DEEP_BASS -> 3      // More Bass
            }
        }
    }

    fun nothingIdToEqPreset(id: Int, useListeningMode: Boolean): Pair<T300Protocol.EqPreset?, String?> {
        return if (useListeningMode) {
            when (id) {
                3 -> T300Protocol.EqPreset.ORIGINAL_SOUND to null
                4 -> T300Protocol.EqPreset.SERENADE to null
                2 -> T300Protocol.EqPreset.PURE_BASS to null
                1 -> T300Protocol.EqPreset.DEEP_BASS to null
                5 -> null to "Classical"
                6 -> null to "Custom"
                7 -> null to "Dirac Opteo"
                else -> null to "EQ #$id"
            }
        } else {
            when (id) {
                0 -> T300Protocol.EqPreset.ORIGINAL_SOUND to null
                1 -> T300Protocol.EqPreset.SERENADE to null
                2 -> T300Protocol.EqPreset.PURE_BASS to null
                3 -> T300Protocol.EqPreset.DEEP_BASS to null
                5 -> null to "Custom"
                else -> null to "EQ #$id"
            }
        }
    }

    fun buildInEarRead(): ByteArray = buildPacket(CMD_IN_EAR_REQ)
    fun buildInEarWrite(enabled: Boolean): ByteArray =
        buildPacket(CMD_IN_EAR_SET, byteArrayOf(0x01, 0x01, if (enabled) 0x01 else 0x00))

    fun buildLowLatencyRead(): ByteArray = buildPacket(CMD_LOW_LATENCY_REQ)
    fun buildLowLatencyWrite(enabled: Boolean): ByteArray =
        buildPacket(CMD_LOW_LATENCY_SET, byteArrayOf(if (enabled) 0x01 else 0x02))

    fun buildBassBoostRead(): ByteArray = buildPacket(CMD_BASS_BOOST_REQ)
    fun buildBassBoostWrite(enabled: Boolean, level: Int = 3): ByteArray =
        buildPacket(CMD_BASS_BOOST_SET, byteArrayOf(if (enabled) 0x01 else 0x00, (level.coerceIn(1, 5)).toByte()))

    const val CMD_CUSTOM_EQ_REQ: Int = 0xC044
    const val CMD_CUSTOM_EQ_RSP: Int = 0x4044
    const val CMD_CUSTOM_EQ_SET: Int = 0xF041
    const val CMD_RING_BUDS_SET: Int = 0xF002

    fun buildCustomEqRead(): ByteArray = buildPacket(CMD_CUSTOM_EQ_REQ)

    fun buildSelectCustomEq(useListeningMode: Boolean): ByteArray {
        val id: Byte = if (useListeningMode) 6 else 5
        val cmd = if (useListeningMode) CMD_EQ_LISTENING_SET else CMD_EQ_CLASSIC_SET
        return buildPacket(cmd, byteArrayOf(id, 0x00))
    }

    /**
     * 3-band Custom EQ for Nothing / CMF (swift-nothing-ear Bluetooth.swift):
     *   [count=3, totalGain(f32 LE), 3 × (filterType, gain f32 LE, freq f32 LE, Q f32 LE)]
     */
    fun buildCustomEqWrite(bassDb: Int, midDb: Int, trebleDb: Int): ByteArray {
        val bass = bassDb.coerceIn(-6, 6).toFloat()
        val mid = midDb.coerceIn(-6, 6).toFloat()
        val treble = trebleDb.coerceIn(-6, 6).toFloat()
        val maxGain = maxOf(0f, bass, mid, treble)
        val totalGain = -maxGain

        fun putFloatLe(arr: ByteArray, off: Int, v: Float) {
            val bits = java.lang.Float.floatToRawIntBits(v)
            arr[off] = (bits and 0xFF).toByte()
            arr[off + 1] = ((bits ushr 8) and 0xFF).toByte()
            arr[off + 2] = ((bits ushr 16) and 0xFF).toByte()
            arr[off + 3] = ((bits ushr 24) and 0xFF).toByte()
        }

        // Order matches swift-nothing-ear: PEAK (Mid), HIGH_SHELF (Treble), LOW_SHELF (Bass)
        val bands = listOf(
            Triple(0x01.toByte(), mid, 1000f to 1.0f),
            Triple(0x02.toByte(), treble, 6000f to 0.707f),
            Triple(0x00.toByte(), bass, 100f to 0.707f)
        )
        val pkt = ByteArray(1 + 4 + bands.size * 13)
        pkt[0] = bands.size.toByte()
        putFloatLe(pkt, 1, totalGain)
        var off = 5
        for ((ftype, gain, fq) in bands) {
            pkt[off] = ftype
            putFloatLe(pkt, off + 1, gain)
            putFloatLe(pkt, off + 5, fq.first)
            putFloatLe(pkt, off + 9, fq.second)
            off += 13
        }
        return buildPacket(CMD_CUSTOM_EQ_SET, pkt)
    }

    fun parseCustomEqPayload(p: ByteArray): List<Int>? {
        if (p.size < 5 + 3 * 13) return null
        fun readFloatLe(off: Int): Float {
            val bits = (p[off].toInt() and 0xFF) or
                ((p[off + 1].toInt() and 0xFF) shl 8) or
                ((p[off + 2].toInt() and 0xFF) shl 16) or
                ((p[off + 3].toInt() and 0xFF) shl 24)
            return java.lang.Float.intBitsToFloat(bits)
        }
        var bass = 0; var mid = 0; var treble = 0
        var off = 5
        val count = (p[0].toInt() and 0xFF).coerceAtMost(3)
        for (i in 0 until count) {
            if (off + 13 > p.size) break
            val ftype = p[off].toInt() and 0xFF
            val g = readFloatLe(off + 1).toInt().coerceIn(-6, 6)
            when (ftype) {
                0x00 -> bass = g
                0x01 -> mid = g
                0x02 -> treble = g
            }
            off += 13
        }
        return listOf(bass, mid, treble)
    }

    /**
     * Ring Nothing / CMF buds (0xF002): side = 0x01 Left, 0x03 Right, 0x06 Both/Unibody.
     */
    fun buildRingBuds(sideByte: Byte, enable: Boolean): ByteArray =
        buildPacket(CMD_RING_BUDS_SET, byteArrayOf(sideByte, if (enable) 0x01 else 0x00))

    fun parseBatteryPayload(payload: ByteArray): List<T300Protocol.BatteryInfo> {
        if (payload.isEmpty()) return emptyList()
        val count = payload[0].toInt() and 0xFF
        val result = mutableListOf<T300Protocol.BatteryInfo>()
        var off = 1
        for (i in 0 until count) {
            if (off + 1 >= payload.size) break
            val part = payload[off].toInt() and 0xFF
            val raw = payload[off + 1].toInt() and 0xFF
            off += 2
            val level = raw and 0x7F
            val charging = (raw and 0x80) != 0
            val validLevel = if (level in 0..100) level else -1
            when (part) {
                0x02 -> result.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, validLevel, charging))
                0x03 -> result.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, validLevel, charging))
                0x04 -> {
                    val effective = if (validLevel == 0 && !charging) -1 else validLevel
                    result.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.CASE, effective, charging))
                }
                0x06 -> {
                    // Single battery headphone (Headphone (1) / Neckband Pro)
                    result.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, validLevel, charging))
                    result.add(T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, validLevel, charging))
                }
            }
        }
        return result
    }

    /**
     * Парсинг ответа / события ANC (0x401E / 0xE003).
     * Payload содержит тройки (kind, value, extra):
     *   kind == 1 -> текущий режим (1=High, 2=Mid, 3=Low, 4=Adaptive, 5=Off, 7=Transparency)
     */
    fun parseAncPayload(payload: ByteArray): Pair<T300Protocol.AncMode, HuaweiProtocol.AncLevel?>? {
        var off = 0
        while (off + 1 < payload.size) {
            val kind = payload[off].toInt() and 0xFF
            val value = payload[off + 1].toInt() and 0xFF
            if (kind == 1) {
                return byteToAncState(value)
            }
            off += 3
        }
        return null
    }

    /**
     * Потоковый парсер пакетов Nothing / CMF SPP (SOF 0x55).
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
            if (off + 8 > total) break

            val flags = (buf[off + 1].toInt() and 0xFF) or ((buf[off + 2].toInt() and 0xFF) shl 8)
            val cmd = (buf[off + 3].toInt() and 0xFF) or ((buf[off + 4].toInt() and 0xFF) shl 8)
            val payloadLen = (buf[off + 5].toInt() and 0xFF) or ((buf[off + 6].toInt() and 0xFF) shl 8)
            val opId = buf[off + 7].toInt() and 0xFF

            if (payloadLen > 1024) {
                off++
                continue
            }

            val hasCrc = (flags and FLAG_HAS_CRC) != 0
            val fullLen = 8 + payloadLen + (if (hasCrc) 2 else 0)
            if (off + fullLen > total) break

            val payload = if (payloadLen > 0) {
                buf.copyOfRange(off + 8, off + 8 + payloadLen)
            } else {
                ByteArray(0)
            }
            packets.add(Packet(cmd, opId, payload))
            off += fullLen
        }

        val remaining = if (off < total) buf.copyOfRange(off, total) else ByteArray(0)
        return Pair(packets, remaining)
    }
}
