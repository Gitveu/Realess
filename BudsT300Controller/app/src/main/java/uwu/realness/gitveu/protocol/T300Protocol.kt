package uwu.realness.gitveu.protocol

import androidx.annotation.StringRes
import uwu.realness.gitveu.R
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
    const val CMD_SWITCH_FEATURE_REQ: Int = 0x010D // 269 decimal (Запрос переключателей/функций)
    const val CMD_SWITCH_FEATURE_RSP: Int = 0x810D // 33037 decimal
    const val CMD_EQ_REQ: Int = 0x010F           // 271 decimal (Запрос пресета эквалайзера)
    const val CMD_EQ_RSP: Int = 0x810F           // 33039 decimal
    const val CMD_MULTI_CONNECT_INFO_REQ: Int = 0x0112 // 274 decimal (Запрос списка подключённых устройств)
    const val CMD_MULTI_CONNECT_INFO_RSP: Int = 0x8112 // 33042 decimal
    const val CMD_ALL_EQ_REQ: Int = 0x0122       // 290 decimal (Запрос пользовательских кривых EQ)
    const val CMD_ALL_EQ_RSP: Int = 0x8122       // 33058 decimal
    const val CMD_USE_TIME_REQ: Int = 0x0128     // 296 decimal (Запрос статистики использования L/R)
    const val CMD_USE_TIME_RSP: Int = 0x8128     // 33064 decimal
    const val CMD_KEY_FUNCTION_REQ: Int = 0x0108     // 264 decimal (Запрос настроек кнопок)
    const val CMD_KEY_FUNCTION_RSP: Int = 0x8108     // 33032 decimal
    const val CMD_KEY_FUNCTION_SET: Int = 0x0401     // 1025 decimal (Установка настроек кнопок)
    const val CMD_KEY_FUNCTION_SET_RSP: Int = 0x8401 // 33793 decimal
    const val CMD_ANC_SET: Int = 0x0404          // 1028 decimal
    const val CMD_ANC_RSP: Int = 0x8404          // 33796 decimal
    const val CMD_SWITCH_FEATURE_SET: Int = 0x0403 // 1027 decimal (Установка переключателей/функций)
    const val CMD_SWITCH_FEATURE_SET_RSP: Int = 0x8403 // 33795 decimal
    const val CMD_EQ_SET: Int = 0x0406           // 1030 decimal (Установка пресета эквалайзера)
    const val CMD_EQ_SET_RSP: Int = 0x8406       // 33798 decimal
    const val CMD_NOTIFICATION_REGISTER: Int = 0x0201 // 513 decimal (Регистрация на события)
    const val CMD_NOTIFICATION_REGISTER_RSP: Int = 0x8201 // 33281 decimal
    const val CMD_EVENT: Int = 0x0204            // 516 decimal (Асинхронные события)
    const val CMD_NOTIFICATION: Int = 0x8400     // Асинхронные уведомления от устройства

    // Идентификаторы переключателей (SwitchFeature)
    const val FEATURE_ID_GAME_MODE: Byte = 0x06          // 6 decimal (Игровой режим)
    const val FEATURE_ID_DUAL_CONNECTION: Byte = 0x11    // 17 decimal (Подключение к двум устройствам)
    const val FEATURE_ID_HIGH_QUALITY_AUDIO: Byte = 0x18 // 24 decimal (Звук высокой четкости / LDAC)
    const val FEATURE_ID_SPATIAL_AUDIO: Byte = 0x1B      // 27 decimal (Пространственное аудио / 3D Sound)
    const val FEATURE_ID_DYNAMIC_AUDIO: Byte = 0x1D      // 29 decimal (Динамическое аудио)
    const val EVENT_TYPE_GAME_MODE: Byte = 0x05
    const val EVENT_TYPE_MULTI_CONNECT: Byte = 0x06

    // CCCD UUID для включения notify/indicate
    const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"

    enum class AncMode(val modeByte: Byte, @StringRes val titleRes: Int) {
        NOISE_REDUCTION(0x08, R.string.anc_noise_reduction),
        OFF(0x01, R.string.anc_off),
        TRANSPARENCY(0x02, R.string.anc_transparency);

        companion object {
            fun fromByte(b: Byte): AncMode? = entries.firstOrNull { it.modeByte == b }
        }
    }

    enum class ComponentRole(val id: Int, @StringRes val titleRes: Int) {
        LEFT_EAR(1, R.string.battery_left),
        RIGHT_EAR(2, R.string.battery_right),
        CASE(3, R.string.battery_case);

        companion object {
            fun fromId(id: Int): ComponentRole? = entries.firstOrNull { it.id == id }
        }
    }

    enum class EqPreset(val eqId: Byte, @StringRes val titleRes: Int) {
        SERENADE(0x02, R.string.eq_serenade),
        ORIGINAL_SOUND(0x00, R.string.eq_original_sound),
        PURE_BASS(0x03, R.string.eq_pure_bass),
        DEEP_BASS(0x01, R.string.eq_deep_bass);

        companion object {
            fun fromByte(b: Byte): EqPreset? = entries.firstOrNull { it.eqId == b }
        }
    }

    data class CustomEqSummary(
        val eqId: Int,
        val name: String,
        val isSelected: Boolean
    )

    enum class TouchSide(val deviceType: Byte, @StringRes val titleRes: Int) {
        LEFT(0x01, R.string.touch_side_left),
        RIGHT(0x02, R.string.touch_side_right);

        companion object {
            fun fromDeviceType(b: Byte): TouchSide? = entries.firstOrNull { it.deviceType == b }
        }
    }

    enum class TouchGesture(val actionId: Byte, @StringRes val titleRes: Int) {
        DOUBLE_TAP(0x02, R.string.gesture_double_tap),
        TRIPLE_TAP(0x03, R.string.gesture_triple_tap),
        LONG_PRESS(0x04, R.string.gesture_long_press);

        companion object {
            fun fromActionId(b: Byte): TouchGesture? = entries.firstOrNull { it.actionId == b }
        }
    }

    enum class TouchAction(val functionId: Byte, @StringRes val titleRes: Int) {
        PLAY_PAUSE(0x01, R.string.action_play_pause),
        NEXT_TRACK(0x06, R.string.action_next_track),
        PREV_TRACK(0x05, R.string.action_prev_track),
        VOICE_ASSISTANT(0x03, R.string.action_voice_assistant),
        ANC_TOGGLE(0x08, R.string.action_anc_toggle),
        DEVICE_SWITCH(0x0D, R.string.action_device_switch),
        GAME_MODE(0x11, R.string.action_game_mode),
        VOLUME_UP(0x0B, R.string.action_volume_up),
        VOLUME_DOWN(0x0C, R.string.action_volume_down),
        NONE(0x00, R.string.action_none);

        companion object {
            fun fromFunctionId(b: Byte): TouchAction {
                if (b.toInt() == 4) return VOICE_ASSISTANT
                return entries.firstOrNull { it.functionId == b } ?: NONE
            }
        }
    }

    fun getAvailableActions(gesture: TouchGesture): List<TouchAction> {
        return when (gesture) {
            TouchGesture.DOUBLE_TAP -> listOf(
                TouchAction.PLAY_PAUSE,
                TouchAction.NEXT_TRACK,
                TouchAction.VOICE_ASSISTANT,
                TouchAction.NONE
            )
            TouchGesture.TRIPLE_TAP -> listOf(
                TouchAction.PREV_TRACK,
                TouchAction.NEXT_TRACK,
                TouchAction.VOLUME_UP,
                TouchAction.VOLUME_DOWN,
                TouchAction.DEVICE_SWITCH,
                TouchAction.VOICE_ASSISTANT,
                TouchAction.GAME_MODE,
                TouchAction.NONE
            )
            TouchGesture.LONG_PRESS -> listOf(
                TouchAction.ANC_TOGGLE,
                TouchAction.VOICE_ASSISTANT,
                TouchAction.GAME_MODE,
                TouchAction.NONE
            )
        }
    }

    data class KeySettingItem(
        val side: TouchSide,
        val gesture: TouchGesture,
        val action: TouchAction
    )

    data class BatteryInfo(
        val role: ComponentRole,
        val level: Int,          // 0..100% (-1 если нет данных)
        val isCharging: Boolean
    )

    data class ConnectedDeviceInfo(
        val mac: String,
        val name: String,
        val connectState: Int,
        val flag: Int
    ) {
        val isConnected: Boolean get() = connectState != 0
    }

    data class T300State(
        val leftBattery: BatteryInfo = BatteryInfo(ComponentRole.LEFT_EAR, -1, false),
        val rightBattery: BatteryInfo = BatteryInfo(ComponentRole.RIGHT_EAR, -1, false),
        val caseBattery: BatteryInfo = BatteryInfo(ComponentRole.CASE, -1, false),
        val ancMode: AncMode? = null,
        val gameMode: Boolean? = null,
        val spatialAudio: Boolean? = null,
        val highQualityAudio: Boolean? = null,
        val dynamicAudio: Boolean? = null,
        val dualConnection: Boolean? = null,
        val connectedDevices: List<ConnectedDeviceInfo> = emptyList(),
        val leftUseTimeMinutes: Int? = null,
        val rightUseTimeMinutes: Int? = null,
        val eqPreset: EqPreset? = null,
        val activeCustomEqName: String? = null,
        val touchSettings: Map<Pair<TouchSide, TouchGesture>, TouchAction> = emptyMap()
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
     * Запрос состояния переключателя (SwitchFeature, Opcode 0x010D).
     * Для игрового режима featureId = 0x06 (FEATURE_ID_GAME_MODE).
     * Payload: [0x01, featureId]
     */
    fun buildSwitchFeatureRequestFrame(featureId: Byte = FEATURE_ID_GAME_MODE, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_SWITCH_FEATURE_REQ, address, byteArrayOf(0x01, featureId)).toByteArray()

    /**
     * Установка переключателя (SwitchFeature, Opcode 0x0403).
     * Для игрового режима featureId = 0x06 (FEATURE_ID_GAME_MODE), value = 0x01 (вкл) / 0x00 (выкл).
     * Payload: [featureId, value]
     */
    fun buildSwitchFeatureCommandFrame(featureId: Byte = FEATURE_ID_GAME_MODE, enabled: Boolean, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_SWITCH_FEATURE_SET, address, byteArrayOf(featureId, if (enabled) 0x01 else 0x00)).toByteArray()

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
     * Запрос текущего пресета эквалайзера (Opcode 0x010F).
     * Payload: пустой.
     */
    fun buildEqRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_EQ_REQ, address, ByteArray(0)).toByteArray()

    /**
     * Установка пресета эквалайзера (Opcode 0x0406).
     * Payload: [eqId] (1 байт).
     */
    fun buildEqCommandFrame(preset: EqPreset, address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_EQ_SET, address, byteArrayOf(preset.eqId)).toByteArray()

    /**
     * Запрос списка пользовательских эквалайзеров (Opcode 0x0122).
     * Payload: пустой.
     */
    fun buildAllEqRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_ALL_EQ_REQ, address, ByteArray(0)).toByteArray()

    private val KEY_FUNCTION_REQ_PAYLOAD = byteArrayOf(
        0x0E, // count = 14
        0x01, 0x03, 0x04, 0x05, 0x06, 0x0B, 0x0C, 0x08, 0x0D, 0x11, 0x1F, 0x1A, 0x1B, 0x00
    )

    /**
     * Запрос текущих настроек кнопок (Opcode 0x0108).
     */
    fun buildKeyFunctionRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_KEY_FUNCTION_REQ, address, KEY_FUNCTION_REQ_PAYLOAD).toByteArray()

    /**
     * Установка настройки кнопки (Opcode 0x0401).
     * Payload: [0x01, deviceType, 0x01, actionId, functionId]
     */
    fun buildKeyFunctionCommandFrame(
        side: TouchSide,
        gesture: TouchGesture,
        action: TouchAction,
        address: Byte = DEVICE_ADDRESS_DEFAULT
    ): ByteArray {
        val payload = byteArrayOf(
            0x01,           // count = 1
            side.deviceType,
            0x01,           // button = 1
            gesture.actionId,
            action.functionId
        )
        return Frame(CMD_KEY_FUNCTION_SET, address, payload).toByteArray()
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
                // Если кейс закрыт / неактивен, наушники присылают 03 00 (0% без зарядки).
                // В таком случае заряд считается недоступным (-1 / "—").
                val effectiveLevel = if (role == ComponentRole.CASE && level == 0 && !isCharging) -1 else level
                result.add(BatteryInfo(role, effectiveLevel, isCharging))
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

    /**
     * Парсинг ответа состояния SwitchFeature (Opcode 0x810D).
     * Структура payload: [status (0x00), count (N), (featureId, value)...]
     * Возвращает значение для указанного featureId: true = вкл, false = выкл, null = ошибка/не найдено.
     */
    fun parseSwitchFeaturePayload(p: ByteArray, targetFeatureId: Byte = FEATURE_ID_GAME_MODE): Boolean? {
        if (p.size < 4 || p[0].toInt() != 0) return null
        val count = p[1].toInt() and 0xFF
        var offset = 2
        for (i in 0 until count) {
            if (offset + 1 >= p.size) break
            val featureId = p[offset]
            val value = p[offset + 1]
            if (featureId == targetFeatureId) {
                return value.toInt() == 1
            }
            offset += 2
        }
        return null
    }

    /**
     * Парсинг ответа подтверждения установки переключателя (Opcode 0x8403).
     * Payload: [status] (0x00 = Success).
     */
    fun parseSwitchFeatureSetResponse(p: ByteArray): Boolean {
        if (p.isEmpty()) return false
        return p[0].toInt() == 0
    }

    /**
     * Парсинг асинхронного события переключателя (из CMD_EVENT 0x0204, тип события 0x05).
     * Payload: [0x05, value] -> value: 1 = вкл, 0 = выкл.
     */
    fun parseGameModeEventPayload(p: ByteArray): Boolean? {
        if (p.size < 2 || p[0] != EVENT_TYPE_GAME_MODE) return null
        return p[1].toInt() == 1
    }

    /**
     * Парсинг ответа текущего пресета EQ (Opcode 0x810F).
     * Структура payload: [status (0x00), eqId]
     */
    fun parseEqPayload(p: ByteArray): EqPreset? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        return EqPreset.fromByte(p[1])
    }

    /**
     * Возвращает сырой eqId из ответа 0x810F (даже если это кастомный ID).
     */
    fun parseRawEqId(p: ByteArray): Int? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        return p[1].toInt() and 0xFF
    }

    /**
     * Парсинг ответа подтверждения установки пресета EQ (Opcode 0x8406).
     * Payload: [status] (0x00 = Success).
     */
    fun parseEqSetResponse(p: ByteArray): Boolean {
        if (p.isEmpty()) return false
        return p[0].toInt() == 0
    }

    /**
     * Парсинг списка всех пользовательских кривых EQ (Opcode 0x8122).
     * Формат по CommandUtil.b(1, bArrE):
     * [0] status (0 = success)
     * [1] count
     * Для каждого элемента:
     *   isSelected (1 байт, 1 = активен)
     *   minValue (1 байт)
     *   maxValue (1 байт)
     *   eqId (1 байт)
     *   nameLength (1 байт)
     *   name (nameLength байт UTF-8)
     *   freqCount (1 байт)
     *   freqCount * (2 байта частота + 1 байт dB)
     */
    fun parseAllEqPayload(p: ByteArray): List<CustomEqSummary> {
        if (p.size < 2 || p[0].toInt() != 0) return emptyList()
        val count = p[1].toInt() and 0xFF
        val result = mutableListOf<CustomEqSummary>()
        var offset = 2
        for (i in 0 until count) {
            if (offset + 5 > p.size) break
            val isSelected = (p[offset].toInt() and 0xFF) == 1
            val eqId = p[offset + 3].toInt() and 0xFF
            val nameLength = p[offset + 4].toInt() and 0xFF
            offset += 5
            if (offset + nameLength > p.size) break
            val name = String(p, offset, nameLength, Charsets.UTF_8)
            offset += nameLength
            if (offset >= p.size) {
                result.add(CustomEqSummary(eqId, name, isSelected))
                break
            }
            val freqCount = p[offset].toInt() and 0xFF
            offset += 1 + freqCount * 3
            result.add(CustomEqSummary(eqId, name, isSelected))
        }
        return result
    }

    /**
     * Парсинг ответа настроек кнопок (Opcode 0x8108).
     * Формат: [status (0x00), count (N), (deviceType, button, action, function)...]
     */
    fun parseKeyFunctionPayload(p: ByteArray): List<KeySettingItem> {
        if (p.size < 2 || p[0].toInt() != 0) return emptyList()
        val count = p[1].toInt() and 0xFF
        if (p.size < 2 + count * 4) return emptyList()

        val result = mutableListOf<KeySettingItem>()
        var offset = 2
        for (i in 0 until count) {
            val devType = p[offset]
            val button = p[offset + 1]
            val actionId = p[offset + 2]
            val funcId = p[offset + 3]

            val side = TouchSide.fromDeviceType(devType)
            val gesture = TouchGesture.fromActionId(actionId)
            val action = TouchAction.fromFunctionId(funcId)

            if (side != null && gesture != null) {
                result.add(KeySettingItem(side, gesture, action))
            }
            offset += 4
        }
        return result
    }

    /**
     * Парсинг ответа подтверждения установки настройки кнопки (Opcode 0x8401).
     * Payload: [status] (0x00 = Success).
     */
    fun parseKeyFunctionSetResponse(p: ByteArray): Boolean {
        if (p.isEmpty()) return false
        return p[0].toInt() == 0
    }

    /**
     * Запрос списка подключённых устройств при Dual Device Connection (Opcode 0x0112).
     * Payload: пустой.
     */
    fun buildMultiConnectInfoRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_MULTI_CONNECT_INFO_REQ, address, ByteArray(0)).toByteArray()

    /**
     * Парсинг ответа / события списка подключённых устройств (Opcode 0x8112 / Event 0x06).
     * Формат по CommandUtil.h(String, int, byte[]):
     *   [startOffset] count (N)
     *   Для каждого устройства:
     *     6 байт MAC
     *     1 байт entryLen (длина остатка записи после entryLen)
     *     1 байт connectState
     *     1 байт flag
     *     1 байт nameLen
     *     nameLen байт UTF-8 имя устройства
     *     (entryLen - nameLen - 3) байт резерв/пропуск
     */
    fun parseMultiConnectInfoPayload(p: ByteArray, hasStatusByte: Boolean = true): List<ConnectedDeviceInfo>? {
        val countOffset = if (hasStatusByte) 1 else 0
        if (hasStatusByte && (p.isEmpty() || p[0].toInt() != 0)) return null
        if (p.size <= countOffset) return null

        val count = p[countOffset].toInt() and 0xFF
        if (count == 0) return emptyList()

        val result = mutableListOf<ConnectedDeviceInfo>()
        var offset = countOffset + 1
        for (i in 0 until count) {
            if (offset + 10 > p.size) break
            val macBytes = p.copyOfRange(offset, offset + 6)
            val mac = macBytes.reversedArray().joinToString(":") { "%02X".format(it) }
            val entryLen = p[offset + 6].toInt() and 0xFF
            val connectState = p[offset + 7].toInt() and 0xFF
            val flag = p[offset + 8].toInt() and 0xFF
            val nameLen = p[offset + 9].toInt() and 0xFF
            val nameStart = offset + 10
            val nameEnd = nameStart + nameLen
            if (nameEnd > p.size) break
            val name = String(p, nameStart, nameLen, Charsets.UTF_8).trimEnd('\u0000')
            result.add(ConnectedDeviceInfo(mac, name, connectState, flag))
            val extraSkip = (entryLen - nameLen - 3).coerceAtLeast(0)
            offset = nameEnd + extraSkip
        }
        return result
    }

    /**
     * Запрос статистики общего времени использования наушников (Opcode 0x0128 / 296).
     * Payload: пустой.
     */
    fun buildUseTimeRequestFrame(address: Byte = DEVICE_ADDRESS_DEFAULT): ByteArray =
        Frame(CMD_USE_TIME_REQ, address, ByteArray(0)).toByteArray()

    /**
     * Парсинг ответа времени использования наушников (Opcode 0x8128 / 33064).
     * Формат по CommandUtil.o(1, bArrE) и UseTimeInfo(offset, bArr):
     *   [0] status (0x00 = Success)
     *   [1] count (N)
     *   Далее N записей по 5 байт:
     *     [0] roleByte (1 = Left, 2 = Right, 3 = Case)
     *     [1..4] totalMinutes (uint32 little-endian)
     */
    fun parseUseTimePayload(p: ByteArray): Map<ComponentRole, Int>? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        val count = p[1].toInt() and 0xFF
        if (count == 0 || p.size < 2 + count * 5) return null

        val result = mutableMapOf<ComponentRole, Int>()
        var offset = 2
        for (i in 0 until count) {
            val roleId = p[offset].toInt() and 0xFF
            val minutes = (p[offset + 1].toInt() and 0xFF) or
                ((p[offset + 2].toInt() and 0xFF) shl 8) or
                ((p[offset + 3].toInt() and 0xFF) shl 16) or
                ((p[offset + 4].toInt() and 0xFF) shl 24)
            val role = ComponentRole.fromId(roleId)
            if (role != null) {
                result[role] = minutes
            }
            offset += 5
        }
        return result
    }
}
