package com.realme.budst300.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.realme.budst300.protocol.T300Protocol
import com.realme.budst300.protocol.T300Protocol.AncMode
import com.realme.budst300.protocol.T300Protocol.ComponentRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    CONNECTED
}

enum class ActiveTransport {
    NONE,
    RFCOMM_SPP,
    BLE_GATT
}

@SuppressLint("MissingPermission")
class T300BleManager(private val context: Context) {

    companion object {
        private const val TAG = "T300BleManager"
        private const val POLL_INTERVAL_MS = 2_500L
        private const val EVENT_REFRESH_DELAY_MS = 120L
        private const val SPP_CONNECT_TIMEOUT_MS = 3_500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    // SPP Socket
    private var bluetoothSocket: BluetoothSocket? = null
    private var socketInputStream: InputStream? = null
    private var socketOutputStream: OutputStream? = null
    private var socketReadJob: Job? = null

    // GATT
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var activeTransport = ActiveTransport.NONE

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _t300State = MutableStateFlow(T300Protocol.T300State())
    val t300State: StateFlow<T300Protocol.T300State> = _t300State.asStateFlow()

    private val _logFlow = MutableSharedFlow<String>(replay = 50)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private var pollJob: Job? = null
    private var eventRefreshJob: Job? = null
    private val sendMutex = Mutex()

    private fun log(message: String) {
        Log.d(TAG, message)
        scope.launch { _logFlow.emit(message) }
    }

    private fun scheduleStateRefresh(reason: String) {
        if (_connectionState.value != BleConnectionState.CONNECTED) return
        eventRefreshJob?.cancel()
        eventRefreshJob = scope.launch {
            delay(EVENT_REFRESH_DELAY_MS)
            log("Авто-обновление состояния ($reason)")
            refreshAncState()
            delay(80)
            refreshBattery()
        }
    }

    /**
     * Подключение к наушникам Buds T300:
     * 1. В первую очередь пробуем прямой RFCOMM SPP-сокет (как в realme Link).
     * 2. В случае неудачи — fallback на BLE GATT.
     */
    fun connect(device: BluetoothDevice) {
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        val name = device.name ?: "Buds T300"
        log("Старт подключения к $name (${device.address})...")

        scope.launch {
            // Попытка 1: RFCOMM SPP
            val sppConnected = tryConnectSpp(device)
            if (sppConnected) {
                return@launch
            }

            // Попытка 2: BLE GATT Fallback
            log("SPP сокеты недоступны. Переход на BLE GATT...")
            connectGatt(device)
        }
    }

    /**
     * Попытка подключиться через RFCOMM BluetoothSocket
     */
    private suspend fun tryConnectSpp(device: BluetoothDevice): Boolean {
        bluetoothAdapter?.cancelDiscovery()

        for (uuid in T300GattConfig.KNOWN_SPP_UUIDS) {
            val isRealmeDefault = (uuid == T300GattConfig.REALME_VENDOR_SPP_UUID)
            val uuidLabel = if (isRealmeDefault) "Realme Vendor SPP" else "SPP $uuid"
            log("Попытка SPP ($uuidLabel)...")

            val success = tryConnectSingleSocket(device, uuid, insecure = false) ||
                          tryConnectSingleSocket(device, uuid, insecure = true)

            if (success) {
                activeTransport = ActiveTransport.RFCOMM_SPP
                _connectionState.value = BleConnectionState.CONNECTED
                log("✓ SPP успешно подключён через $uuidLabel!")

                startSocketReader()
                finishConnectionReady()
                return true
            }
        }
        return false
    }

    private suspend fun tryConnectSingleSocket(device: BluetoothDevice, uuid: UUID, insecure: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = if (insecure) {
                    device.createInsecureRfcommSocketToServiceRecord(uuid)
                } else {
                    device.createRfcommSocketToServiceRecord(uuid)
                }

                withTimeout(SPP_CONNECT_TIMEOUT_MS) {
                    socket.connect()
                }

                if (socket.isConnected) {
                    bluetoothSocket = socket
                    socketInputStream = socket.inputStream
                    socketOutputStream = socket.outputStream
                    true
                } else {
                    socket.close()
                    false
                }
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                false
            }
        }
    }

    /**
     * Запуск чтения потока байтов из RFCOMM-сокета
     */
    private fun startSocketReader() {
        socketReadJob?.cancel()
        socketReadJob = scope.launch(Dispatchers.IO) {
            val stream = socketInputStream ?: return@launch
            val buffer = ByteArray(1024)
            var accumulated = ByteArray(0)

            try {
                while (isActive && bluetoothSocket?.isConnected == true) {
                    val count = stream.read(buffer)
                    if (count <= 0) break

                    val incoming = buffer.copyOfRange(0, count)
                    log("RX raw [${incoming.joinToString(" ") { "%02X".format(it) }}]")
                    accumulated += incoming

                    val (frames, remaining) = T300Protocol.parseFramesFromStreamBuffer(accumulated)
                    accumulated = remaining

                    for (frame in frames) {
                        handleIncomingFrame(frame)
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    log("Разрыв соединения SPP: ${e.message}")
                }
            } finally {
                if (_connectionState.value == BleConnectionState.CONNECTED) {
                    disconnect()
                }
            }
        }
    }

    /**
     * Подключение через BLE GATT
     */
    private fun connectGatt(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.CONNECTING
        activeTransport = ActiveTransport.BLE_GATT

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_AUTO)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    /**
     * Отключение всех транспортов
     */
    fun disconnect() {
        pollJob?.cancel()
        pollJob = null

        eventRefreshJob?.cancel()
        eventRefreshJob = null

        socketReadJob?.cancel()
        socketReadJob = null

        try {
            socketInputStream?.close()
            socketOutputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        bluetoothSocket = null
        socketInputStream = null
        socketOutputStream = null

        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing GATT", e)
            }
        }
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null

        activeTransport = ActiveTransport.NONE
        _connectionState.value = BleConnectionState.DISCONNECTED
        log("Соединение разорвано")
    }

    /**
     * Переключение режима шумоподавления (ANC)
     */
    fun setAncMode(mode: AncMode) {
        val frame = T300Protocol.buildAncCommandFrame(mode)
        log("TX ANC [${mode.title}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(ancMode = mode)
    }

    /**
     * Принудительный запрос уровня заряда батареи
     */
    fun refreshBattery() {
        val frame = T300Protocol.buildBatteryRequestFrame()
        log("TX Battery Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Запрос текущего режима шумоподавления (ANC)
     */
    fun refreshAncState() {
        val frame = T300Protocol.buildAncStateRequestFrame()
        log("TX ANC State Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Отправка массива байтов в активный транспорт (SPP или GATT).
     * Кадры TL оборачиваются в транспортный формат OPPOv1 (SOF 0xAA).
     */
    private fun sendData(data: ByteArray) {
        val wireData = T300Protocol.wrapOppoFrame(data)
        log("TX Wire [${wireData.joinToString(" ") { "%02X".format(it) }}]")

        scope.launch {
            sendMutex.withLock {
                when (activeTransport) {
                    ActiveTransport.RFCOMM_SPP -> {
                        val out = socketOutputStream
                        if (out == null || bluetoothSocket?.isConnected != true) {
                            log("Ошибка: RFCOMM сокет не готов")
                            return@withLock
                        }
                        try {
                            out.write(wireData)
                            out.flush()
                        } catch (e: Exception) {
                            log("Ошибка отправки SPP: ${e.message}")
                        }
                    }
                    ActiveTransport.BLE_GATT -> {
                        val gatt = bluetoothGatt
                        val char = writeCharacteristic
                        if (gatt == null || char == null) {
                            log("Ошибка: GATT канал записи не готов")
                            return@withLock
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                } else {
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                }
                                gatt.writeCharacteristic(char, wireData, writeType)
                            } else {
                                char.value = wireData
                                if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                } else {
                                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                }
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(char)
                            }
                            delay(40)
                        } catch (e: Exception) {
                            log("Ошибка отправки GATT: ${e.message}")
                        }
                    }
                    ActiveTransport.NONE -> {
                        log("Ошибка: нет активного соединения")
                    }
                }
            }
        }
    }

    private fun finishConnectionReady() {
        scope.launch {
            delay(150) // Быстрый старт: сначала сразу режим ANC, затем батарея
            log("✓ Устройство готово! Запрашиваем состояние...")
            refreshAncState()
            delay(80)
            refreshBattery()
            delay(80)
            registerNotifications()
            startPolling()
        }
    }

    private fun registerNotifications() {
        // Подписка на асинхронные события от наушников (3 = ANC, 1 = батарея, 2 = статус)
        for (eventId in byteArrayOf(0x03, 0x01, 0x02)) {
            val frame = T300Protocol.buildRegisterNotificationFrame(eventId)
            sendData(frame)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED) {
                delay(POLL_INTERVAL_MS)
                refreshAncState()
                delay(80)
                refreshBattery()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT подключён (status=$status). Поиск сервисов...")
                    _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT отключён (status=$status)")
                    disconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Ошибка обнаружения GATT-сервисов: status=$status")
                disconnect()
                return
            }

            var foundWrite: BluetoothGattCharacteristic? = null
            var foundNotify: BluetoothGattCharacteristic? = null

            for (serviceUuid in T300GattConfig.KNOWN_CANDIDATE_SERVICES) {
                val service = gatt.getService(serviceUuid) ?: continue
                if (serviceUuid == T300GattConfig.NORDIC_UART_SERVICE) {
                    foundWrite = service.getCharacteristic(T300GattConfig.NORDIC_UART_RX_WRITE)
                    foundNotify = service.getCharacteristic(T300GattConfig.NORDIC_UART_TX_NOTIFY)
                } else if (serviceUuid == T300GattConfig.AIROHA_SERVICE) {
                    foundWrite = service.getCharacteristic(T300GattConfig.AIROHA_WRITE)
                    foundNotify = service.getCharacteristic(T300GattConfig.AIROHA_NOTIFY)
                }
                if (foundWrite != null && foundNotify != null) break
            }

            if ((foundWrite == null || foundNotify == null) && T300GattConfig.AUTO_DETECT_FALLBACK) {
                for (service in gatt.services) {
                    val sUuidStr = service.uuid.toString().lowercase()
                    if (sUuidStr.startsWith("00001800") || sUuidStr.startsWith("00001801")) continue

                    var candidateWrite: BluetoothGattCharacteristic? = null
                    var candidateNotify: BluetoothGattCharacteristic? = null

                    for (char in service.characteristics) {
                        val props = char.properties
                        val isWritable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        val isNotifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)

                        if (isWritable && candidateWrite == null) candidateWrite = char
                        if (isNotifiable && candidateNotify == null) candidateNotify = char
                    }

                    if (candidateWrite != null && candidateNotify != null) {
                        foundWrite = candidateWrite
                        foundNotify = candidateNotify
                        break
                    }
                }
            }

            if (foundWrite == null || foundNotify == null) {
                log("GATT: каналы записи/уведомлений не найдены")
                disconnect()
                return
            }

            writeCharacteristic = foundWrite
            notifyCharacteristic = foundNotify
            _connectionState.value = BleConnectionState.SUBSCRIBING
            enableNotifications(gatt, foundNotify)
        }

        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(T300GattConfig.CCCD_UUID)
            if (descriptor == null) {
                finishGattConnection()
                return
            }

            val value = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            finishGattConnection()
        }

        private fun finishGattConnection() {
            _connectionState.value = BleConnectionState.CONNECTED
            finishConnectionReady()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            T300Protocol.parseFrame(value)?.let { handleIncomingFrame(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { bytes ->
                T300Protocol.parseFrame(bytes)?.let { handleIncomingFrame(it) }
            }
        }
    }

    /**
     * Обработка распарсенного кадра TL-протокола
     */
    private fun handleIncomingFrame(frame: T300Protocol.Frame) {
        val hexCmd = "0x" + frame.command.toString(16).uppercase().padStart(4, '0')
        val hexPayload = frame.payload.joinToString(" ") { "%02X".format(it) }

        when (frame.command) {
            T300Protocol.CMD_BATTERY_RSP -> {
                val parsed = T300Protocol.parseBatteryPayload(frame.payload)
                log("RX Battery [$hexPayload] -> ${parsed.map { "${it.role.title}: ${it.level}%${if (it.isCharging) " ⚡" else ""}" }}")
                val batteries = normalizeBatteries(parsed)
                val isSnapshot = frame.payload.size >= 2 && frame.payload[0].toInt() == 0
                updateBatteryState(batteries, isSnapshot = isSnapshot)
            }
            T300Protocol.CMD_ANC_STATE_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val mode = T300Protocol.parseAncStatePayload(frame.payload)
                if (mode != null) {
                    log("RX ANC State: ${mode.title}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                }
            }
            T300Protocol.CMD_NOTIFICATION_REGISTER_RSP -> {
                log("RX Event Reg Rsp [$hexPayload]")
            }
            T300Protocol.CMD_ANC_RSP -> {
                val ok = T300Protocol.parseAncResponse(frame.payload)
                log("RX ANC Rsp [$hexPayload] -> ${if (ok) "✓ Подтверждено" else "✗ Ошибка"}")
                if (ok) {
                    refreshAncState()
                }
            }
            T300Protocol.CMD_EVENT -> {
                log("RX Event $hexCmd [$hexPayload]")
                if (frame.payload.isNotEmpty() && frame.payload[0].toInt() == 0x03) {
                    val mode = T300Protocol.parseAncEventPayload(frame.payload)
                    if (mode != null) {
                        log("RX Event: ANC переключён на ${mode.title}")
                        _t300State.value = _t300State.value.copy(ancMode = mode)
                    }
                } else {
                    val eventType = if (frame.payload.isNotEmpty()) {
                        "0x%02X".format(frame.payload[0])
                    } else {
                        "пустое"
                    }
                    scheduleStateRefresh("событие $eventType")
                }
            }
            T300Protocol.CMD_NOTIFICATION -> {
                log("RX Event $hexCmd [$hexPayload]")
                val mode = T300Protocol.parseAncEventPayload(frame.payload)
                if (mode != null) {
                    log("RX Event: ANC переключён на ${mode.title}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                } else {
                    val eventType = if (frame.payload.isNotEmpty()) {
                        "0x%02X".format(frame.payload[0])
                    } else {
                        "пустое"
                    }
                    scheduleStateRefresh("событие $eventType")
                }
            }
            else -> {
                log("RX Неизвестный кадр: $hexCmd [$hexPayload]")
                scheduleStateRefresh("кадр $hexCmd")
            }
        }
    }

    private fun normalizeBatteries(list: List<T300Protocol.BatteryInfo>): List<T300Protocol.BatteryInfo> =
        list.map {
            if (it.role == ComponentRole.CASE && it.level == 0 && !it.isCharging)
                T300Protocol.BatteryInfo(ComponentRole.CASE, -1, false)
            else it
        }

    private fun updateBatteryState(batteries: List<T300Protocol.BatteryInfo>, isSnapshot: Boolean = false) {
        var current = _t300State.value
        if (isSnapshot) {
            val left = batteries.firstOrNull { it.role == ComponentRole.LEFT_EAR }
                ?: T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, -1, false)
            val right = batteries.firstOrNull { it.role == ComponentRole.RIGHT_EAR }
                ?: T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, -1, false)
            val case = batteries.firstOrNull { it.role == ComponentRole.CASE }
                ?: T300Protocol.BatteryInfo(ComponentRole.CASE, -1, false)

            current = current.copy(
                leftBattery = left,
                rightBattery = right,
                caseBattery = case
            )
        } else {
            for (b in batteries) {
                current = when (b.role) {
                    ComponentRole.LEFT_EAR -> current.copy(leftBattery = b)
                    ComponentRole.RIGHT_EAR -> current.copy(rightBattery = b)
                    ComponentRole.CASE -> current.copy(caseBattery = b)
                }
            }
        }
        _t300State.value = current
    }
}
