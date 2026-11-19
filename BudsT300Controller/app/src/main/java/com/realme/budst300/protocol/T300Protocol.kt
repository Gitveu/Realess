package com.realme.budst300.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Описание протокола TL TWS для Realme Buds T300.
 *
 * Формат транспортного кадра OPPOv1:
 * Byte 0: SOF (0xAA)
 * Byte 1..K: Длина пакета (varint 7-bit, для <128 байт — 1 байт = TL_LEN + 2)
 * Byte K+1: Флаги (0x00 = Single package)
 * Byte K+2: Зарезервировано (0x00)
 * Затем внутренний кадр TL (5+N байт):
 *   Offset 0..1: Command ID (little-endian)
 *   Offset 2:    Device Address (0x00)
 *   Offset 3..4: Payload Length (little-endian)
 *   Offset 5..N: Payload
 */
object T300Protocol {

    const val DEVICE_ADDRESS_DEFAULT: Byte = 0x00
    const val OPPO_SOF: Byte = 0xAA.toByte()

    // Команды TL-протокола
    const val CMD_BATTERY_REQ: Int = 0x0106      // 262 decimal
    const val CMD_BATTERY_RSP: Int = 0x8106      // 33030 decimal (CMD | 0x8000)
    const val CMD_ANC_STATE_REQ: Int = 0x010C    // 268 decimal
    const val CMD_ANC_STATE_RSP: Int = 0x810C    // 33036 decimal
    const val CMD_ANC_SET: Int = 0x0404          // 1028 decimal
    const val CMD_ANC_RSP: Int = 0x8404          // 33796 decimal
    const val CMD_NOTIFICATION_REGISTER: Int = 0x0201 // 513 decimal (Регистрация на события)
    const val CMD_NOTIFICATION_REGISTER_RSP: Int = 0x8201 // 33281 decimal
    const val CMD_EVENT: Int = 0x0204            // 516 decimal (Асинхронные события)
    const val CMD_NOTIFICATION: Int = 0x8400     // Асинхронные уведомления от устройства

    // CCCD UUID для включения notify/indicate
    const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"

    enum class AncMode(val modeByte: Byte, val title: String) {
        NOISE_REDUCTION(0x08, "Шумоподавление"),
        OFF(0x01, "Выкл."),
        TRANSPARENCY(0x02, "Прозрачность");

        companion object {
            fun fromByte(b: Byte): AncMode? = entries.firstOrNull { it.modeByte == b }
        }
    }

    enum class ComponentRole(val id: Int, val title: String) {
        LEFT_EAR(1, "Левый"),
        RIGHT_EAR(2, "Правый"),
        CASE(3, "Кейс");

        companion object {
            fun fromId(id: Int): ComponentRole? = entries.firstOrNull { it.id == id }
        }
    }

    data class BatteryInfo(
        val role: ComponentRole,
        val level: Int,          // 0..100% (-1 если нет данных)
        val isCharging: Boolean
    )

    data class T300State(
        val leftBattery: BatteryInfo = BatteryInfo(ComponentRole.LEFT_EAR, -1, false),
        val rightBattery: BatteryInfo = BatteryInfo(ComponentRole.RIGHT_EAR, -1, false),
        val caseBattery: BatteryInfo = BatteryInfo(ComponentRole.CASE, -1, false),
        val ancMode: AncMode? = null
    )

    data class Frame(
        val command: Int,
        val address: Byte = DEVICE_ADDRESS_DEFAULT,
        val payload: ByteArray = ByteArray(0)
    ) {
        fun toByteArray(): ByteArray {
            val buffer = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(command.toShort())
            buffer.put(address)
            buffer.putShort(payload.size.toShort())
            if (payload.isNotEmpty()) {
                buffer.put(payload)
            }
            return buffer.array()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Frame
            return command == other.command && address == other.address && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + address
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Оборачивает внутренний TL-кадр в транспортный кадр OPPOv1 (SOF 0xAA).
     */
    fun wrapOppoFrame(tlFrame: ByteArray): ByteArray {
        val payloadLen = tlFrame.size + 2 // +2 для байтов Flags и Reserved
        val lenBytes = encodeVarInt(payloadLen)
        val header = ByteArray(1 + lenBytes.size + 2)
        header[0] = OPPO_SOF
        System.arraycopy(lenBytes, 0, header, 1, lenBytes.size)
        val offset = 1 + lenBytes.size
        header[offset] = 0x00     // Flags: Single package
        header[offset + 1] = 0x00 // Reserved
        return header + tlFrame
    }

    private fun encodeVarInt(value: Int): ByteArray {
        if (value == 0) return byteArrayOf(0)
        var v = value
        val list = mutableListOf<Byte>()
        while (v != 0) {
            var b = (v and 0x7F)
            v = v ushr 7
            if (v != 0) b = b or 0x80
            list.add(b.toByte())
        }
        return list.toByteArray()
    }

    /**
     * Создание кадра запроса батареи: 0x0106 -> [0x06, 0x01, 0x00, 0x00, 0x00]
     */
    fun buildBatteryRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray {
        return Frame(
            command = CMD_BATTERY_REQ,
            address = address,
            payload = ByteArray(0)
        ).toByteArray()
    }

    /**
     * Создание кадра запроса текущего режима ANC: 0x010C -> [0x0C, 0x01, addr, 0x02, 0x00, 0x01, 0x01]
     */
    fun buildAncStateRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_ANC_STATE_REQ, address, byteArrayOf(0x01, 0x01)).toByteArray()

    /**
     * Регистрация на получение асинхронных уведомлений (Opcode 0x0201).
     * eventType: 0x03 = ANC, 0x01 = батарея, 0x02 = статус наушников
     */
    fun buildRegisterNotificationFrame(eventType: Byte, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_NOTIFICATION_REGISTER, address, byteArrayOf(eventType)).toByteArray()

    /**
     * Создание кадра переключения ANC:
     * 0x0404 -> [0x04, 0x04, addr, 0x03, 0x00, 0x01, 0x01, modeByte]
     */
    fun buildAncCommandFrame(mode: AncMode, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray {
        val payload = byteArrayOf(0x01, 0x01, mode.modeByte)
        return Frame(
            command = CMD_ANC_SET,
            address = address,
            payload = payload
        ).toByteArray()
    }

    /**
     * Парсер сырого кадра (поддерживает как кадры в обёртке OPPO 0xAA, так и чистые TL-кадры).
     */
    fun parseFrame(data: ByteArray): Frame? {
        val unwrapped = unwrapOppo(data) ?: data
        if (unwrapped.size < 5) return null
        val buffer = ByteBuffer.wrap(unwrapped).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.short.toInt() and 0xFFFF
        val address = buffer.get()
        val length = buffer.short.toInt() and 0xFFFF
        if (unwrapped.size < 5 + length) return null

        val payload = ByteArray(length)
        if (length > 0) {
            buffer.get(payload)
        }
        return Frame(command, address, payload)
    }

    private fun unwrapOppo(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data[0] != OPPO_SOF || data.size < 4) return null
        var offset = 1
        var len = 0
        var shift = 0
        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift)
            offset++
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (offset + 2 <= data.size) {
            val flags = data[offset].toInt() and 0xFF
            val hasFsn = (flags and 0x03) != 0
            val skip = if (hasFsn) 3 else 2
            val payloadStart = offset + skip
            if (payloadStart <= data.size) {
                return data.copyOfRange(payloadStart, data.size)
            }
        }
        return null
    }

    /**
     * Потоковый разбор пакетов из буфера сокета/GATT.
     * Автоматически распознаёт SOF 0xAA (OPPO wrapper) и чистые TL wire frames.
     */
    fun parseFramesFromStreamBuffer(streamBuffer: ByteArray): Pair<List<Frame>, ByteArray> {
        val frames = mutableListOf<Frame>()
        var offset = 0
        val total = streamBuffer.size

        while (offset < total) {
            if (streamBuffer[offset] == OPPO_SOF) {
                // OPPO-пакет
                var varLenOffset = offset + 1
                var linkDataLen = 0
                var shift = 0
                var varLenBytesCount = 0
                while (varLenOffset < total && varLenBytesCount < 4) {
                    val b = streamBuffer[varLenOffset].toInt() and 0xFF
                    linkDataLen = linkDataLen or ((b and 0x7F) shl shift)
                    varLenOffset++
                    varLenBytesCount++
                    if ((b and 0x80) == 0) break
                    shift += 7
                }

                val fullPacketLen = 1 + varLenBytesCount + linkDataLen
                if (offset + fullPacketLen <= total) {
                    val packetBytes = streamBuffer.copyOfRange(offset, offset + fullPacketLen)
                    parseFrame(packetBytes)?.let { frames.add(it) }
                    offset += fullPacketLen
                } else {
                    // Неполный OPPO пакет — ждём остаток
                    break
                }
            } else if (offset + 5 <= total) {
                // Чистый TL-пакет
                val cmd = (streamBuffer[offset].toInt() and 0xFF) or ((streamBuffer[offset + 1].toInt() and 0xFF) shl 8)
                val addr = streamBuffer[offset + 2]
                val len = (streamBuffer[offset + 3].toInt() and 0xFF) or ((streamBuffer[offset + 4].toInt() and 0xFF) shl 8)
                val fullFrameSize = 5 + len

                if (offset + fullFrameSize <= total) {
                    val payload = if (len > 0) {
                        streamBuffer.copyOfRange(offset + 5, offset + fullFrameSize)
                    } else {
                        ByteArray(0)
                    }
                    frames.add(Frame(command = cmd, address = addr, payload = payload))
                    offset += fullFrameSize
                } else {
                    // Неполный чистый кадр
                    break
                }
            } else {
                break
            }
        }

        val remaining = if (offset < total) {
            streamBuffer.copyOfRange(offset, total)
        } else {
            ByteArray(0)
        }

        return Pair(frames, remaining)
    }

    /**
     * Парсинг ответа батареи (Opcode 0x8106).
     *
     * Структура payload:
     * Byte 0: Status code (0x00 = Success)
     * Byte 1: Count (кол-во записей, обычно 3)
     * Затем Count записей по 2 байта:
     *   Offset 0: Component ID (1 = Left, 2 = Right, 3 = Case)
     *   Offset 1: bits 0..6: level (0..100), bit 7: isCharging
     */
    fun parseBatteryPayload(payload: ByteArray): List<BatteryInfo> {
        if (payload.size < 2) return emptyList()
        val status = payload[0].toInt()
        if (status != 0) return emptyList()

        val count = payload[1].toInt() and 0xFF
        val result = mutableListOf<BatteryInfo>()
        var offset = 2

        for (i in 0 until count) {
            if (offset + 1 >= payload.size) break
            val componentId = payload[offset].toInt() and 0xFF
            val rawValue = payload[offset + 1].toInt() and 0xFF

            val level = rawValue and 0x7F
            val isCharging = (rawValue and 0x80) != 0

            val role = ComponentRole.fromId(componentId)
            if (role != null) {
                result.add(BatteryInfo(role, level, isCharging))
            }
            offset += 2
        }
        return result
    }

    /**
     * Парсинг ответа ANC (Opcode 0x8404).
     * Возвращает true, если смена подтверждена (status == 0).
     */
    fun parseAncResponse(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val status = payload[0].toInt()
        return status == 0
    }

    /**
     * Парсинг ответа состояния ANC (Opcode 0x810C).
     * Ожидается payload: [status (0x00), action (0x01), type (0x01), noiseStatus]
     */
    fun parseAncStatePayload(p: ByteArray): AncMode? {
        if (p.size < 4 || p[0].toInt() != 0 || p[1].toInt() != 1) return null
        return AncMode.fromByte(p[3])
    }

    /**
     * Парсинг асинхронного события смены ANC (тип 0x03).
     * Если size >= 4: [03, action, type, noiseStatus], режим в p[3] при action==1.
     * Если size == 3: [03, type, noiseStatus], режим в p[2].
     */
    fun parseAncEventPayload(p: ByteArray): AncMode? {
        if (p.isEmpty() || p[0].toInt() != 0x03) return null
        return when {
            p.size >= 4 -> if (p[1].toInt() == 1) AncMode.fromByte(p[3]) else null
            p.size == 3 -> AncMode.fromByte(p[2])
            else -> null
        }
    }
}
