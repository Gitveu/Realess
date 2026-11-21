package com.realme.budst300.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import com.realme.budst300.protocol.T300Protocol
import com.realme.budst300.protocol.T300Protocol.AncMode
import com.realme.budst300.protocol.T300Protocol.ComponentRole
import com.realme.budst300.protocol.T300Protocol.EqPreset
import com.realme.budst300.protocol.T300Protocol.TouchSide
import com.realme.budst300.protocol.T300Protocol.TouchGesture
import com.realme.budst300.protocol.T300Protocol.TouchAction
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
        private const val POLL_INTERVAL_MS = 2_000L
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
            log("Auto-refreshing state ($reason)")
            refreshAncState()
            delay(80)
            refreshGameMode()
            delay(80)
            refreshSpatialAudio()
            delay(80)
            if (_t300State.value.highQualityAudio != null) {
                refreshHighQualityAudio()
                delay(80)
            }
            if (_t300State.value.dynamicAudio != null) {
                refreshDynamicAudio()
                delay(80)
            }
            if (_t300State.value.dualConnection != null) {
                refreshDualConnection()
                delay(80)
                refreshMultiConnectInfo()
                delay(80)
            }
            refreshEq()
            delay(80)
            refreshBattery()
            delay(80)
            refreshKeySettings()
        }
    }

    /**
     * Connect to Buds T300:
     * 1. Try direct RFCOMM SPP socket first (as in realme Link).
     * 2. Fall back to BLE GATT if SPP fails.
     */
    fun connect(device: BluetoothDevice) {
        disconnect()
        _connectionState.value = BleConnectionState.CONNECTING
        val name = device.name ?: "Buds T300"
        log("Starting connection to $name (${device.address})...")

        scope.launch {
            // Attempt 1: RFCOMM SPP
            val sppConnected = tryConnectSpp(device)
            if (sppConnected) {
                return@launch
            }

            // Attempt 2: BLE GATT Fallback
            log("SPP sockets unavailable. Falling back to BLE GATT...")
            connectGatt(device)
        }
    }

    /**
     * Attempt connection via RFCOMM BluetoothSocket
     */
    private suspend fun tryConnectSpp(device: BluetoothDevice): Boolean {
        bluetoothAdapter?.cancelDiscovery()

        for (uuid in T300GattConfig.KNOWN_SPP_UUIDS) {
            val isRealmeDefault = (uuid == T300GattConfig.REALME_VENDOR_SPP_UUID)
            val uuidLabel = if (isRealmeDefault) "Realme Vendor SPP" else "SPP $uuid"
            log("Attempting SPP ($uuidLabel)...")

            val success = tryConnectSingleSocket(device, uuid, insecure = false) ||
                          tryConnectSingleSocket(device, uuid, insecure = true)

            if (success) {
                activeTransport = ActiveTransport.RFCOMM_SPP
                _connectionState.value = BleConnectionState.CONNECTED
                log("✓ SPP connected successfully via $uuidLabel!")

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
                    log("SPP connection closed: ${e.message}")
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
        _t300State.value = T300Protocol.T300State()
        log("Connection closed")
    }

    /**
     * Set ANC Mode
     */
    fun setAncMode(mode: AncMode) {
        val frame = T300Protocol.buildAncCommandFrame(mode)
        log("TX ANC [${mode.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(ancMode = mode)
    }

    /**
     * Request battery levels
     */
    fun refreshBattery() {
        val frame = T300Protocol.buildBatteryRequestFrame()
        log("TX Battery Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request current ANC mode
     */
    fun refreshAncState() {
        val frame = T300Protocol.buildAncStateRequestFrame()
        log("TX ANC State Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request Game Mode state
     */
    fun refreshGameMode() {
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_GAME_MODE)
        log("TX GameMode Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Game Mode (ON / OFF)
     */
    fun setGameMode(enabled: Boolean) {
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_GAME_MODE, enabled)
        log("TX GameMode [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(gameMode = enabled)
    }

    /**
     * Request Spatial Audio state (Feature 27 / 0x1B)
     */
    fun refreshSpatialAudio() {
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_SPATIAL_AUDIO)
        log("TX SpatialAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Spatial Audio (ON / OFF)
     */
    fun setSpatialAudio(enabled: Boolean) {
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_SPATIAL_AUDIO, enabled)
        log("TX SpatialAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(spatialAudio = enabled)
    }

    /**
     * Request High-Definition Audio / LDAC state (Feature 24 / 0x18)
     */
    fun refreshHighQualityAudio() {
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO)
        log("TX HighQualityAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set High-Definition Audio / LDAC (ON / OFF)
     */
    fun setHighQualityAudio(enabled: Boolean) {
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO, enabled)
        log("TX HighQualityAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(highQualityAudio = enabled)
    }

    /**
     * Request Dynamic Audio state (Feature 29 / 0x1D)
     */
    fun refreshDynamicAudio() {
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_DYNAMIC_AUDIO)
        log("TX DynamicAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Dynamic Audio (ON / OFF)
     */
    fun setDynamicAudio(enabled: Boolean) {
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_DYNAMIC_AUDIO, enabled)
        log("TX DynamicAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(dynamicAudio = enabled)
    }

    /**
     * Request Dual-Device Connection state (Feature 17 / 0x11)
     */
    fun refreshDualConnection() {
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_DUAL_CONNECTION)
        log("TX DualConnection Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Dual-Device Connection (ON / OFF)
     */
    fun setDualConnection(enabled: Boolean) {
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_DUAL_CONNECTION, enabled)
        log("TX DualConnection [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(dualConnection = enabled)
    }

    /**
     * Request Multi-Connect device list (Opcode 0x0112)
     */
    fun refreshMultiConnectInfo() {
        val frame = T300Protocol.buildMultiConnectInfoRequestFrame()
        log("TX MultiConnect Info Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request current EQ preset (Opcode 0x010F)
     */
    fun refreshEq() {
        val frame = T300Protocol.buildEqRequestFrame()
        log("TX EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request custom EQ curves (Opcode 0x0122)
     */
    fun refreshAllEq() {
        val frame = T300Protocol.buildAllEqRequestFrame()
        log("TX All EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set EQ preset (Opcode 0x0406)
     */
    fun setEqPreset(preset: EqPreset) {
        val frame = T300Protocol.buildEqCommandFrame(preset)
        log("TX EQ [${preset.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(
            eqPreset = preset,
            activeCustomEqName = null
        )
    }

    /**
     * Request touch button settings (Opcode 0x0108)
     */
    fun refreshKeySettings() {
        val frame = T300Protocol.buildKeyFunctionRequestFrame()
        log("TX KeySettings Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set touch button setting (Opcode 0x0401)
     */
    fun setKeySetting(side: TouchSide, gesture: TouchGesture, action: TouchAction) {
        val frame = T300Protocol.buildKeyFunctionCommandFrame(side, gesture, action)
        log("TX KeySetting [${side.name} ${gesture.name} -> ${action.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)

        // Optimistic UI update
        val currentMap = _t300State.value.touchSettings.toMutableMap()
        currentMap[Pair(side, gesture)] = action
        _t300State.value = _t300State.value.copy(touchSettings = currentMap)
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
                            log("Error: RFCOMM socket not ready")
                            return@withLock
                        }
                        try {
                            out.write(wireData)
                            out.flush()
                        } catch (e: Exception) {
                            log("Error sending SPP: ${e.message}")
                        }
                    }
                    ActiveTransport.BLE_GATT -> {
                        val gatt = bluetoothGatt
                        val char = writeCharacteristic
                        if (gatt == null || char == null) {
                            log("Error: GATT write characteristic not ready")
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
                            log("Error sending GATT: ${e.message}")
                        }
                    }
                    ActiveTransport.NONE -> {
                        log("Error: no active connection")
                    }
                }
            }
        }
    }

    private fun finishConnectionReady() {
        scope.launch {
            delay(150)
            log("✓ Device ready! Requesting state...")
            refreshAncState()
            delay(80)
            refreshGameMode()
            delay(80)
            refreshSpatialAudio()
            delay(80)
            refreshHighQualityAudio()
            delay(80)
            refreshDynamicAudio()
            delay(80)
            refreshDualConnection()
            delay(80)
            refreshMultiConnectInfo()
            delay(80)
            refreshEq()
            delay(80)
            refreshAllEq()
            delay(80)
            refreshBattery()
            delay(80)
            refreshKeySettings()
            delay(80)
            registerNotifications()
            startPolling()
        }
    }

    private fun registerNotifications() {
        // Subscribe to async device events (3 = ANC, 5 = GameMode, 1 = Battery, 2 = Status, 6 = MultiConnect)
        for (eventId in byteArrayOf(0x03, 0x05, 0x01, 0x02, 0x06)) {
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
                delay(60)
                refreshGameMode()
                delay(60)
                refreshSpatialAudio()
                delay(60)
                if (_t300State.value.highQualityAudio != null) {
                    refreshHighQualityAudio()
                    delay(60)
                }
                if (_t300State.value.dynamicAudio != null) {
                    refreshDynamicAudio()
                    delay(60)
                }
                if (_t300State.value.dualConnection != null) {
                    refreshDualConnection()
                    delay(60)
                }
                refreshEq()
                delay(60)
                refreshBattery()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT connected (status=$status). Discovering services...")
                    _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected (status=$status)")
                    disconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT service discovery failed: status=$status")
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
                log("GATT: write/notify characteristics not found")
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
                val batteries = normalizeBatteries(parsed)
                val validBatteries = batteries.filter { it.level >= 0 }
                val batteryStrs = validBatteries.map {
                    val roleName = when (it.role) {
                        ComponentRole.LEFT_EAR -> "Left"
                        ComponentRole.RIGHT_EAR -> "Right"
                        ComponentRole.CASE -> "Case"
                    }
                    "$roleName: ${it.level}%${if (it.isCharging) " ⚡" else ""}"
                }
                log("RX Battery [$hexPayload] -> $batteryStrs")
                val isSnapshot = frame.payload.size >= 2 && frame.payload[0].toInt() == 0
                updateBatteryState(batteries, isSnapshot = isSnapshot)
            }
            T300Protocol.CMD_ANC_STATE_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val mode = T300Protocol.parseAncStatePayload(frame.payload)
                if (mode != null) {
                    log("RX ANC State: ${mode.name}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                }
            }
            T300Protocol.CMD_SWITCH_FEATURE_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val gm = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_GAME_MODE)
                if (gm != null) {
                    log("RX GameMode State: ${if (gm) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(gameMode = gm)
                }
                val sa = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_SPATIAL_AUDIO)
                if (sa != null) {
                    log("RX SpatialAudio State: ${if (sa) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(spatialAudio = sa)
                }
                val hq = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO)
                if (hq != null) {
                    log("RX HighQualityAudio (LDAC) State: ${if (hq) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(highQualityAudio = hq)
                }
                val da = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_DYNAMIC_AUDIO)
                if (da != null) {
                    log("RX DynamicAudio State: ${if (da) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(dynamicAudio = da)
                }
                val dc = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_DUAL_CONNECTION)
                if (dc != null) {
                    log("RX DualConnection State: ${if (dc) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(dualConnection = dc)
                }
            }
            T300Protocol.CMD_SWITCH_FEATURE_SET_RSP -> {
                val ok = T300Protocol.parseSwitchFeatureSetResponse(frame.payload)
                log("RX SwitchFeature Set Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshGameMode()
                    refreshSpatialAudio()
                    if (_t300State.value.highQualityAudio != null) refreshHighQualityAudio()
                    if (_t300State.value.dynamicAudio != null) refreshDynamicAudio()
                    if (_t300State.value.dualConnection != null) {
                        refreshDualConnection()
                        refreshMultiConnectInfo()
                    }
                }
            }
            T300Protocol.CMD_MULTI_CONNECT_INFO_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val devices = T300Protocol.parseMultiConnectInfoPayload(frame.payload, hasStatusByte = true)
                if (devices != null) {
                    log("RX MultiConnect Devices: ${devices.map { "${it.name} (${it.mac}, state=${it.connectState})" }}")
                    _t300State.value = _t300State.value.copy(connectedDevices = devices)
                }
            }
            T300Protocol.CMD_NOTIFICATION_REGISTER_RSP -> {
                log("RX Event Reg Rsp [$hexPayload]")
            }
            T300Protocol.CMD_ANC_RSP -> {
                val ok = T300Protocol.parseAncResponse(frame.payload)
                log("RX ANC Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshAncState()
                }
            }
            T300Protocol.CMD_EQ_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val rawId = T300Protocol.parseRawEqId(frame.payload)
                val preset = T300Protocol.parseEqPayload(frame.payload)
                if (preset != null) {
                    log("RX EQ State: ${preset.name} (id=$rawId)")
                    _t300State.value = _t300State.value.copy(
                        eqPreset = preset,
                        activeCustomEqName = null
                    )
                } else if (rawId != null) {
                    log("RX EQ State: Custom (rawId=$rawId)")
                    _t300State.value = _t300State.value.copy(
                        eqPreset = null
                    )
                    refreshAllEq()
                }
            }
            T300Protocol.CMD_EQ_SET_RSP -> {
                val ok = T300Protocol.parseEqSetResponse(frame.payload)
                log("RX EQ Set Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshEq()
                }
            }
            T300Protocol.CMD_ALL_EQ_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val customList = T300Protocol.parseAllEqPayload(frame.payload)
                log("RX All EQ: ${customList.map { "${it.name}${if (it.isSelected) " [active]" else ""}" }}")
                val activeCustom = customList.firstOrNull { it.isSelected }
                if (activeCustom != null) {
                    _t300State.value = _t300State.value.copy(
                        eqPreset = null,
                        activeCustomEqName = activeCustom.name
                    )
                }
            }
            T300Protocol.CMD_KEY_FUNCTION_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val items = T300Protocol.parseKeyFunctionPayload(frame.payload)
                if (items.isNotEmpty()) {
                    log("RX KeySettings: ${items.map { "${it.side.name} ${it.gesture.name} = ${it.action.name}" }}")
                    val currentMap = _t300State.value.touchSettings.toMutableMap()
                    for (item in items) {
                        currentMap[Pair(item.side, item.gesture)] = item.action
                    }
                    _t300State.value = _t300State.value.copy(touchSettings = currentMap)
                }
            }
            T300Protocol.CMD_KEY_FUNCTION_SET_RSP -> {
                val ok = T300Protocol.parseKeyFunctionSetResponse(frame.payload)
                log("RX KeySetting Set Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshKeySettings()
                }
            }
            T300Protocol.CMD_EVENT -> {
                log("RX Event $hexCmd [$hexPayload]")
                if (frame.payload.isNotEmpty() && frame.payload[0].toInt() == 0x03) {
                    val mode = T300Protocol.parseAncEventPayload(frame.payload)
                    if (mode != null) {
                        log("RX Event: ANC switched to ${mode.name}")
                        _t300State.value = _t300State.value.copy(ancMode = mode)
                    }
                } else if (frame.payload.isNotEmpty() && frame.payload[0] == T300Protocol.EVENT_TYPE_GAME_MODE) {
                    val gm = T300Protocol.parseGameModeEventPayload(frame.payload)
                    if (gm != null) {
                        log("RX Event: GameMode switched to ${if (gm) "ON" else "OFF"}")
                        _t300State.value = _t300State.value.copy(gameMode = gm)
                    }
                } else if (frame.payload.size > 1 && frame.payload[0] == T300Protocol.EVENT_TYPE_MULTI_CONNECT) {
                    val subPayload = frame.payload.copyOfRange(1, frame.payload.size)
                    val devices = T300Protocol.parseMultiConnectInfoPayload(subPayload, hasStatusByte = false)
                    if (devices != null) {
                        log("RX Event: MultiConnect devices updated: ${devices.map { "${it.name} (state=${it.connectState})" }}")
                        _t300State.value = _t300State.value.copy(connectedDevices = devices)
                    }
                } else {
                    val eventType = if (frame.payload.isNotEmpty()) {
                        "0x%02X".format(frame.payload[0])
                    } else {
                        "empty"
                    }
                    scheduleStateRefresh("event $eventType")
                }
            }
            T300Protocol.CMD_NOTIFICATION -> {
                log("RX Event $hexCmd [$hexPayload]")
                val mode = T300Protocol.parseAncEventPayload(frame.payload)
                val gm = T300Protocol.parseGameModeEventPayload(frame.payload)
                if (mode != null) {
                    log("RX Event: ANC switched to ${mode.name}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                } else if (gm != null) {
                    log("RX Event: GameMode switched to ${if (gm) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(gameMode = gm)
                } else {
                    val eventType = if (frame.payload.isNotEmpty()) {
                        "0x%02X".format(frame.payload[0])
                    } else {
                        "empty"
                    }
                    scheduleStateRefresh("event $eventType")
                }
            }
            else -> {
                log("RX Unknown frame: $hexCmd [$hexPayload]")
                scheduleStateRefresh("frame $hexCmd")
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
