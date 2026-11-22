package uwu.realness.gitveu.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import uwu.realness.gitveu.protocol.HuaweiProtocol
import uwu.realness.gitveu.protocol.T300Protocol
import uwu.realness.gitveu.protocol.T300Protocol.AncMode
import uwu.realness.gitveu.protocol.T300Protocol.ComponentRole
import uwu.realness.gitveu.protocol.T300Protocol.EqPreset
import uwu.realness.gitveu.protocol.T300Protocol.TouchSide
import uwu.realness.gitveu.protocol.T300Protocol.TouchGesture
import uwu.realness.gitveu.protocol.T300Protocol.TouchAction
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
    private var activeModel = T300Protocol.BudsModel.UNKNOWN
    private val huaweiMultiDevices = mutableMapOf<Int, T300Protocol.ConnectedDeviceInfo>()

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
            if (activeModel.supportsAnc) {
                refreshAncState()
                delay(80)
            }
            if (activeModel.supportsGameMode) {
                refreshGameMode()
                delay(80)
            }
            if (activeModel.supportsSpatialAudio) {
                refreshSpatialAudio()
                delay(80)
            }
            if (activeModel.supportsHighQualityAudio) {
                refreshHighQualityAudio()
                delay(80)
            }
            if (activeModel.supportsDynamicAudio) {
                refreshDynamicAudio()
                delay(80)
            }
            if (activeModel.supportsDualConnection) {
                refreshDualConnection()
                delay(80)
                refreshMultiConnectInfo()
                delay(80)
            }
            if (activeModel.supportsAutoPlayPause) {
                refreshAutoPlayPause()
                delay(80)
            }
            if (activeModel.supportsAutoAnswer) {
                refreshAutoAnswer()
                delay(80)
            }
            if (activeModel.supportsEq) {
                refreshEq()
                delay(80)
            }
            refreshBattery()
            delay(80)
            if (activeModel.supportsTouchSettings) {
                refreshKeySettings()
            }
        }
    }

    /**
     * Connect to supported headset (realme Buds / HUAWEI FreeBuds / Honor):
     * 1. Try direct RFCOMM SPP socket first.
     * 2. Fall back to BLE GATT for realme models if SPP fails.
     */
    fun connect(device: BluetoothDevice) {
        disconnect()
        activeModel = T300Protocol.BudsModel.fromDeviceName(device.name)
        _connectionState.value = BleConnectionState.CONNECTING
        val name = device.name ?: "Headset"
        log("Starting connection to $name (${device.address}, model=$activeModel)...")

        scope.launch {
            // Attempt 1: RFCOMM SPP
            val sppConnected = tryConnectSpp(device)
            if (sppConnected) {
                return@launch
            }

            if (activeModel.isHuawei) {
                log("Huawei SPP socket unavailable")
                disconnect()
                return@launch
            }

            // Attempt 2: BLE GATT Fallback (realme)
            log("SPP sockets unavailable. Falling back to BLE GATT...")
            connectGatt(device)
        }
    }

    /**
     * Attempt connection via RFCOMM BluetoothSocket
     */
    private suspend fun tryConnectSpp(device: BluetoothDevice): Boolean {
        bluetoothAdapter?.cancelDiscovery()

        val candidateUuids = when {
            activeModel.isHuawei && activeModel.prefersHuaweiPrivateUuid -> T300GattConfig.HUAWEI_SPP_UUIDS_PORT16_FIRST
            activeModel.isHuawei -> T300GattConfig.HUAWEI_SPP_UUIDS_PORT1_FIRST
            else -> T300GattConfig.KNOWN_SPP_UUIDS
        }

        for (uuid in candidateUuids) {
            val uuidLabel = when (uuid) {
                T300GattConfig.REALME_VENDOR_SPP_UUID -> "Realme Vendor SPP"
                T300GattConfig.HUAWEI_VENDOR_SPP_UUID -> "Huawei Private SPP"
                T300GattConfig.STANDARD_SPP_UUID -> "Standard SPP"
                else -> "SPP $uuid"
            }
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

                    if (activeModel.isHuawei) {
                        val (packets, remaining) = HuaweiProtocol.parsePacketsFromStreamBuffer(accumulated)
                        accumulated = remaining
                        for (pkg in packets) {
                            handleIncomingHuaweiPacket(pkg)
                        }
                    } else {
                        val (frames, remaining) = T300Protocol.parseFramesFromStreamBuffer(accumulated)
                        accumulated = remaining
                        for (frame in frames) {
                            handleIncomingFrame(frame)
                        }
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
        activeModel = T300Protocol.BudsModel.UNKNOWN
        huaweiMultiDevices.clear()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _t300State.value = T300Protocol.T300State()
        log("Connection closed")
    }

    /**
     * Set ANC Mode
     */
    fun setAncMode(mode: AncMode) {
        if (activeModel.isHuawei) {
            val pkt = HuaweiProtocol.buildAncModeWrite(mode)
            log("TX Huawei ANC [${mode.name}]")
            sendRawBytes(pkt)
            _t300State.value = _t300State.value.copy(ancMode = mode)
            scope.launch { delay(180); refreshAncState() }
            return
        }
        val frame = T300Protocol.buildAncCommandFrame(mode)
        log("TX ANC [${mode.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(ancMode = mode)
    }

    /**
     * Set Huawei ANC intensity level (Comfort / General / Ultra / Dynamic)
     */
    fun setAncLevel(level: HuaweiProtocol.AncLevel) {
        if (!activeModel.isHuawei) return
        val pkt = HuaweiProtocol.buildAncLevelWrite(level)
        log("TX Huawei ANC Level [${level.name}]")
        sendRawBytes(pkt)
        _t300State.value = _t300State.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
        scope.launch { delay(180); refreshAncState() }
    }

    /**
     * Request battery levels
     */
    fun refreshBattery() {
        if (activeModel.isHuawei) {
            log("TX Huawei Battery Req")
            sendRawBytes(HuaweiProtocol.buildBatteryRequest())
            return
        }
        val frame = T300Protocol.buildBatteryRequestFrame()
        log("TX Battery Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request current ANC mode
     */
    fun refreshAncState() {
        if (activeModel.isHuawei) {
            log("TX Huawei ANC Req")
            sendRawBytes(HuaweiProtocol.buildAncReadRequest())
            return
        }
        val frame = T300Protocol.buildAncStateRequestFrame()
        log("TX ANC State Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request Game Mode / Low Latency state
     */
    fun refreshGameMode() {
        if (activeModel.isHuawei) {
            log("TX Huawei LowLatency Req")
            sendRawBytes(HuaweiProtocol.buildLowLatencyRead())
            return
        }
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_GAME_MODE)
        log("TX GameMode Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Game Mode / Low Latency (ON / OFF)
     */
    fun setGameMode(enabled: Boolean) {
        if (activeModel.isHuawei) {
            log("TX Huawei LowLatency [${if (enabled) "ON" else "OFF"}]")
            sendRawBytes(HuaweiProtocol.buildLowLatencyWrite(enabled))
            _t300State.value = _t300State.value.copy(gameMode = enabled)
            scope.launch { delay(500); refreshGameMode() }
            return
        }
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_GAME_MODE, enabled)
        log("TX GameMode [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(gameMode = enabled)
    }

    /**
     * Request Spatial Audio state (Feature 27 / 0x1B)
     */
    fun refreshSpatialAudio() {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_SPATIAL_AUDIO)
        log("TX SpatialAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Spatial Audio (ON / OFF)
     */
    fun setSpatialAudio(enabled: Boolean) {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_SPATIAL_AUDIO, enabled)
        log("TX SpatialAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(spatialAudio = enabled)
    }

    /**
     * Request High-Definition Audio (realme LDAC/LHDC 5.0 or Huawei Sound Quality Preference)
     */
    fun refreshHighQualityAudio() {
        if (activeModel.isHuawei) {
            log("TX Huawei SoundQuality Req")
            sendRawBytes(HuaweiProtocol.buildSoundQualityRead())
            return
        }
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO)
        log("TX HighQualityAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set High-Definition Audio (ON / OFF)
     */
    fun setHighQualityAudio(enabled: Boolean) {
        if (activeModel.isHuawei) {
            log("TX Huawei SoundQuality [${if (enabled) "QUALITY" else "CONNECTIVITY"}]")
            sendRawBytes(HuaweiProtocol.buildSoundQualityWrite(enabled))
            _t300State.value = _t300State.value.copy(highQualityAudio = enabled)
            scope.launch { delay(200); refreshHighQualityAudio() }
            return
        }
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO, enabled)
        log("TX HighQualityAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(highQualityAudio = enabled)
    }

    /**
     * Request Dynamic Audio state (Feature 29 / 0x1D)
     */
    fun refreshDynamicAudio() {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_DYNAMIC_AUDIO)
        log("TX DynamicAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Dynamic Audio (ON / OFF)
     */
    fun setDynamicAudio(enabled: Boolean) {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_DYNAMIC_AUDIO, enabled)
        log("TX DynamicAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(dynamicAudio = enabled)
    }

    /**
     * Request Dual-Device Connection state
     */
    fun refreshDualConnection() {
        if (activeModel.isHuawei) {
            log("TX Huawei DualConnect Req")
            sendRawBytes(HuaweiProtocol.buildDualConnectRead())
            return
        }
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_DUAL_CONNECTION)
        log("TX DualConnection Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Dual-Device Connection (ON / OFF)
     */
    fun setDualConnection(enabled: Boolean) {
        if (activeModel.isHuawei) {
            log("TX Huawei DualConnect [${if (enabled) "ON" else "OFF"}]")
            sendRawBytes(HuaweiProtocol.buildDualConnectWrite(enabled))
            _t300State.value = _t300State.value.copy(dualConnection = enabled)
            scope.launch { delay(250); refreshDualConnection() }
            return
        }
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_DUAL_CONNECTION, enabled)
        log("TX DualConnection [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(dualConnection = enabled)
    }

    /**
     * Request Multi-Connect device list
     */
    fun refreshMultiConnectInfo() {
        if (activeModel.isHuawei) {
            huaweiMultiDevices.clear()
            log("TX Huawei DualConnect Enum")
            sendRawBytes(HuaweiProtocol.buildDualConnectEnumerate())
            return
        }
        val frame = T300Protocol.buildMultiConnectInfoRequestFrame()
        log("TX MultiConnect Info Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request Auto Play/Pause (Wear Detection) state
     */
    fun refreshAutoPlayPause() {
        if (activeModel.isHuawei) {
            log("TX Huawei AutoPause Req")
            sendRawBytes(HuaweiProtocol.buildAutoPauseRead())
            return
        }
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_AUTO_PLAY_PAUSE)
        log("TX AutoPlayPause Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Auto Play/Pause (ON / OFF)
     */
    fun setAutoPlayPause(enabled: Boolean) {
        if (activeModel.isHuawei) {
            log("TX Huawei AutoPause [${if (enabled) "ON" else "OFF"}]")
            sendRawBytes(HuaweiProtocol.buildAutoPauseWrite(enabled))
            _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            scope.launch { delay(200); refreshAutoPlayPause() }
            return
        }
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_AUTO_PLAY_PAUSE, enabled)
        log("TX AutoPlayPause [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
    }

    /**
     * Request Auto Answer state (Feature 8 / 0x08)
     */
    fun refreshAutoAnswer() {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_AUTO_ANSWER)
        log("TX AutoAnswer Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Auto Answer (ON / OFF)
     */
    fun setAutoAnswer(enabled: Boolean) {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_AUTO_ANSWER, enabled)
        log("TX AutoAnswer [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(autoAnswer = enabled)
    }

    /**
     * Request total usage time for Left/Right earbuds (realme Opcode 0x0128)
     */
    fun refreshUseTime() {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildUseTimeRequestFrame()
        log("TX UseTime Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request current EQ preset
     */
    fun refreshEq() {
        if (activeModel.isHuawei) {
            log("TX Huawei EQ Req")
            sendRawBytes(HuaweiProtocol.buildEqRead())
            return
        }
        val frame = T300Protocol.buildEqRequestFrame()
        log("TX EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request custom EQ curves (realme Opcode 0x0122)
     */
    fun refreshAllEq() {
        if (activeModel.isHuawei) return
        val frame = T300Protocol.buildAllEqRequestFrame()
        log("TX All EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set EQ preset
     */
    fun setEqPreset(preset: EqPreset) {
        if (activeModel.isHuawei) {
            val hwId = HuaweiProtocol.eqPresetToHuaweiId(preset, activeModel.huaweiEqUsesPro3Ids)
            log("TX Huawei EQ [${preset.name} -> id=$hwId]")
            sendRawBytes(HuaweiProtocol.buildEqWrite(hwId))
            _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
            scope.launch { delay(200); refreshEq() }
            return
        }
        val frame = T300Protocol.buildEqCommandFrame(preset)
        log("TX EQ [${preset.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
        _t300State.value = _t300State.value.copy(
            eqPreset = preset,
            activeCustomEqName = null
        )
    }

    /**
     * Request touch button settings
     */
    fun refreshKeySettings() {
        if (activeModel.isHuawei) {
            scope.launch {
                if (activeModel.supportsDoubleTap) {
                    sendRawBytes(HuaweiProtocol.buildDoubleTapRead())
                    delay(70)
                }
                if (activeModel.supportsTripleTap) {
                    sendRawBytes(HuaweiProtocol.buildTripleTapRead())
                    delay(70)
                }
                if (activeModel.supportsLongPress) {
                    sendRawBytes(HuaweiProtocol.buildLongTapRead())
                }
            }
            return
        }
        val frame = T300Protocol.buildKeyFunctionRequestFrame()
        log("TX KeySettings Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set touch button setting
     */
    fun setKeySetting(side: TouchSide, gesture: TouchGesture, action: TouchAction) {
        if (activeModel.isHuawei) {
            val isFb3 = activeModel == T300Protocol.BudsModel.HUAWEI_FREEBUDS_3
            val code = HuaweiProtocol.actionToHuaweiTapCode(action, gesture, isFb3)
            log("TX Huawei Key [${side.name} ${gesture.name} -> ${action.name} (code=$code)]")
            sendRawBytes(HuaweiProtocol.buildKeyWrite(gesture, side, code))
            val currentMap = _t300State.value.touchSettings.toMutableMap()
            currentMap[Pair(side, gesture)] = action
            _t300State.value = _t300State.value.copy(touchSettings = currentMap)
            return
        }
        val frame = T300Protocol.buildKeyFunctionCommandFrame(side, gesture, action)
        log("TX KeySetting [${side.name} ${gesture.name} -> ${action.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)

        // Optimistic UI update
        val currentMap = _t300State.value.touchSettings.toMutableMap()
        currentMap[Pair(side, gesture)] = action
        _t300State.value = _t300State.value.copy(touchSettings = currentMap)
    }

    /**
     * Отправка TL-кадра (оборачивается в OPPOv1 SOF 0xAA).
     */
    private fun sendData(data: ByteArray) {
        sendRawBytes(T300Protocol.wrapOppoFrame(data))
    }

    /**
     * Отправка готового wire-массива байтов в активный транспорт (SPP или GATT).
     */
    private fun sendRawBytes(wireData: ByteArray) {
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
            if (activeModel.supportsAnc) {
                refreshAncState()
                delay(80)
            }
            if (activeModel.supportsGameMode) {
                refreshGameMode()
                delay(80)
            }
            if (activeModel.supportsSpatialAudio) {
                refreshSpatialAudio()
                delay(80)
            }
            if (activeModel.supportsHighQualityAudio) {
                refreshHighQualityAudio()
                delay(80)
            }
            if (activeModel.supportsDynamicAudio) {
                refreshDynamicAudio()
                delay(80)
            }
            if (activeModel.supportsDualConnection) {
                refreshDualConnection()
                delay(80)
                refreshMultiConnectInfo()
                delay(80)
            }
            if (activeModel.supportsAutoPlayPause) {
                refreshAutoPlayPause()
                delay(80)
            }
            if (activeModel.supportsAutoAnswer) {
                refreshAutoAnswer()
                delay(80)
            }
            if (activeModel.supportsEq) {
                refreshEq()
                delay(80)
                if (!activeModel.isHuawei) {
                    refreshAllEq()
                    delay(80)
                }
            }
            refreshBattery()
            delay(80)
            if (activeModel.supportsTouchSettings) {
                refreshKeySettings()
                delay(80)
            }
            if (activeModel.supportsUseTime) {
                refreshUseTime()
                delay(80)
            }
            if (!activeModel.isHuawei) {
                registerNotifications()
            }
            startPolling()
        }
    }

    private fun registerNotifications() {
        // Subscribe to async device events (3 = ANC, 5 = GameMode, 1 = Battery, 2 = Status, 6 = MultiConnect)
        val events = if (activeModel.supportsDualConnection) {
            byteArrayOf(0x03, 0x05, 0x01, 0x02, 0x06)
        } else {
            byteArrayOf(0x03, 0x05, 0x01, 0x02)
        }
        for (eventId in events) {
            val frame = T300Protocol.buildRegisterNotificationFrame(eventId)
            sendData(frame)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _connectionState.value == BleConnectionState.CONNECTED) {
                delay(POLL_INTERVAL_MS)
                if (activeModel.supportsAnc) {
                    refreshAncState()
                    delay(60)
                }
                if (activeModel.supportsGameMode) {
                    refreshGameMode()
                    delay(60)
                }
                if (activeModel.supportsSpatialAudio) {
                    refreshSpatialAudio()
                    delay(60)
                }
                if (activeModel.supportsHighQualityAudio) {
                    refreshHighQualityAudio()
                    delay(60)
                }
                if (activeModel.supportsDynamicAudio) {
                    refreshDynamicAudio()
                    delay(60)
                }
                if (activeModel.supportsDualConnection) {
                    refreshDualConnection()
                    delay(60)
                }
                if (activeModel.supportsAutoPlayPause) {
                    refreshAutoPlayPause()
                    delay(60)
                }
                if (activeModel.supportsAutoAnswer) {
                    refreshAutoAnswer()
                    delay(60)
                }
                if (activeModel.supportsEq) {
                    refreshEq()
                    delay(60)
                }
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
                    val codecLabel = if (activeModel.usesLhdc) "LHDC 5.0" else "LDAC"
                    log("RX HighQualityAudio ($codecLabel) State: ${if (hq) "ON" else "OFF"}")
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
                val app = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_AUTO_PLAY_PAUSE)
                if (app != null) {
                    log("RX AutoPlayPause State: ${if (app) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(autoPlayPause = app)
                }
                val aa = T300Protocol.parseSwitchFeaturePayload(frame.payload, T300Protocol.FEATURE_ID_AUTO_ANSWER)
                if (aa != null) {
                    log("RX AutoAnswer State: ${if (aa) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(autoAnswer = aa)
                }
            }
            T300Protocol.CMD_SWITCH_FEATURE_SET_RSP -> {
                val ok = T300Protocol.parseSwitchFeatureSetResponse(frame.payload)
                log("RX SwitchFeature Set Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshGameMode()
                    refreshSpatialAudio()
                    if (activeModel.supportsHighQualityAudio) refreshHighQualityAudio()
                    if (activeModel.supportsDynamicAudio) refreshDynamicAudio()
                    if (activeModel.supportsDualConnection) {
                        refreshDualConnection()
                        refreshMultiConnectInfo()
                    }
                    if (activeModel.supportsAutoPlayPause) refreshAutoPlayPause()
                    if (activeModel.supportsAutoAnswer) refreshAutoAnswer()
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
            T300Protocol.CMD_USE_TIME_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val useTimes = T300Protocol.parseUseTimePayload(frame.payload)
                if (useTimes != null) {
                    val leftMin = useTimes[ComponentRole.LEFT_EAR]
                    val rightMin = useTimes[ComponentRole.RIGHT_EAR]
                    log("RX UseTime: L=${leftMin ?: -1}m, R=${rightMin ?: -1}m")
                    _t300State.value = _t300State.value.copy(
                        leftUseTimeMinutes = leftMin ?: _t300State.value.leftUseTimeMinutes,
                        rightUseTimeMinutes = rightMin ?: _t300State.value.rightUseTimeMinutes
                    )
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

    /**
     * Обработка входящего пакета Huawei SPP (SOF 0x5A, по базе OpenFreebuds)
     */
    private fun handleIncomingHuaweiPacket(pkg: HuaweiProtocol.Packet) {
        val hexCmd = "0x" + pkg.commandId.toString(16).uppercase().padStart(4, '0')
        when (pkg.commandId) {
            HuaweiProtocol.CMD_BATTERY_READ,
            HuaweiProtocol.CMD_BATTERY_NOTIFY -> {
                val p1 = pkg.param(1) // global (1B)
                val p2 = pkg.param(2) // TWS [L, R, Case] (3B)
                val p3 = pkg.param(3) // charging flags (1B or 3B)
                val batteries = mutableListOf<T300Protocol.BatteryInfo>()

                if (p2 != null && p2.size >= 3) {
                    val leftChg = p3 != null && p3.size >= 3 && p3[0].toInt() == 1
                    val rightChg = p3 != null && p3.size >= 3 && p3[1].toInt() == 1
                    val caseChg = p3 != null && p3.size >= 3 && p3[2].toInt() == 1
                    val lRaw = p2[0].toInt() and 0xFF
                    val rRaw = p2[1].toInt() and 0xFF
                    val cRaw = p2[2].toInt() and 0xFF
                    val lLvl = if (lRaw in 1..100 || (lRaw == 0 && leftChg)) lRaw else -1
                    val rLvl = if (rRaw in 1..100 || (rRaw == 0 && rightChg)) rRaw else -1
                    val cLvl = if (cRaw in 1..100 || (cRaw == 0 && caseChg)) cRaw else -1
                    batteries.add(T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, lLvl, leftChg))
                    batteries.add(T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, rLvl, rightChg))
                    batteries.add(T300Protocol.BatteryInfo(ComponentRole.CASE, cLvl, caseChg))
                } else if (p1 != null && p1.isNotEmpty()) {
                    val gLvl = (p1[0].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                    val gChg = p3 != null && p3.any { it.toInt() == 1 }
                    batteries.add(T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, gLvl, gChg))
                    batteries.add(T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, gLvl, gChg))
                    batteries.add(T300Protocol.BatteryInfo(ComponentRole.CASE, -1, false))
                }

                if (batteries.isNotEmpty()) {
                    val valid = batteries.filter { it.level >= 0 }.map {
                        val roleName = when (it.role) {
                            ComponentRole.LEFT_EAR -> "Left"
                            ComponentRole.RIGHT_EAR -> "Right"
                            ComponentRole.CASE -> "Case"
                        }
                        "$roleName: ${it.level}%${if (it.isCharging) " ⚡" else ""}"
                    }
                    log("RX Huawei Battery ($hexCmd) -> $valid")
                    updateBatteryState(batteries, isSnapshot = true)
                }
            }
            HuaweiProtocol.CMD_ANC_READ -> {
                val data = pkg.param(1)
                if (data != null && data.size >= 2) {
                    val levelByte = data[0]
                    val modeByte = data[1].toInt() and 0xFF
                    val ancMode = when (modeByte) {
                        0 -> AncMode.OFF
                        1 -> AncMode.NOISE_REDUCTION
                        2 -> AncMode.TRANSPARENCY
                        else -> null
                    }
                    val ancLevel = if (modeByte == 1) HuaweiProtocol.AncLevel.fromByte(levelByte) else null
                    if (ancMode != null) {
                        log("RX Huawei ANC: ${ancMode.name}${if (ancLevel != null) " (${ancLevel.name})" else ""}")
                        _t300State.value = _t300State.value.copy(ancMode = ancMode, ancLevel = ancLevel)
                    }
                }
            }
            HuaweiProtocol.CMD_ANC_LEGACY_EVENT -> {
                log("RX Huawei ANC Event ($hexCmd)")
                refreshAncState()
            }
            HuaweiProtocol.CMD_LOW_LATENCY -> {
                val v = pkg.param(2)
                if (v != null && v.isNotEmpty()) {
                    val on = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei LowLatency: ${if (on) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(gameMode = on)
                }
            }
            HuaweiProtocol.CMD_AUTO_PAUSE_READ -> {
                val v = pkg.param(1)
                if (v != null && v.isNotEmpty()) {
                    val on = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei AutoPause: ${if (on) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(autoPlayPause = on)
                }
            }
            HuaweiProtocol.CMD_SOUND_QUALITY_READ -> {
                val v = pkg.param(2)
                if (v != null && v.isNotEmpty()) {
                    val hq = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei SoundQuality: ${if (hq) "QUALITY" else "CONNECTIVITY"}")
                    _t300State.value = _t300State.value.copy(highQualityAudio = hq)
                }
            }
            HuaweiProtocol.CMD_EQ_READ -> {
                val cur = pkg.param(2)
                val customRaw = pkg.param(8)
                val customNames = if (customRaw != null && customRaw.isNotEmpty()) {
                    HuaweiProtocol.parseCustomEqNames(customRaw)
                } else emptyMap()
                if (cur != null && cur.isNotEmpty()) {
                    val modeId = cur[0].toInt() // signed byte
                    val preset = HuaweiProtocol.huaweiIdToEqPreset(modeId, activeModel.huaweiEqUsesPro3Ids)
                    if (preset != null) {
                        log("RX Huawei EQ: ${preset.name} (id=$modeId)")
                        _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
                    } else {
                        val customName = customNames[modeId] ?: "EQ #$modeId"
                        log("RX Huawei EQ Custom: $customName (id=$modeId)")
                        _t300State.value = _t300State.value.copy(eqPreset = null, activeCustomEqName = customName)
                    }
                }
            }
            HuaweiProtocol.CMD_DUAL_CONNECT_READ -> {
                val v = pkg.param(1)
                if (v != null && v.isNotEmpty()) {
                    val on = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei DualConnect: ${if (on) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(dualConnection = on)
                }
            }
            HuaweiProtocol.CMD_DUAL_CONNECT_ENUMERATE -> {
                val macBytes = pkg.param(4)
                val idxBytes = pkg.param(3)
                val stateBytes = pkg.param(5)
                val nameBytes = pkg.param(9)
                if (macBytes != null && macBytes.size >= 6) {
                    val mac = macBytes.take(6).joinToString(":") { "%02X".format(it) }
                    val idx = if (idxBytes != null && idxBytes.isNotEmpty()) idxBytes[0].toInt() and 0xFF else huaweiMultiDevices.size
                    val connState = if (stateBytes != null && stateBytes.isNotEmpty()) stateBytes[0].toInt() and 0xFF else 0
                    val name = if (nameBytes != null && nameBytes.isNotEmpty()) {
                        String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    } else mac
                    huaweiMultiDevices[idx] = T300Protocol.ConnectedDeviceInfo(mac, name, connState, 0)
                    val sorted = huaweiMultiDevices.entries.sortedBy { it.key }.map { it.value }
                    log("RX Huawei DualConnect Device: $name ($mac, state=$connState)")
                    _t300State.value = _t300State.value.copy(connectedDevices = sorted)
                }
            }
            HuaweiProtocol.CMD_DUAL_CONNECT_CHANGE_EVENT -> {
                log("RX Huawei DualConnect Change Event")
                refreshMultiConnectInfo()
            }
            HuaweiProtocol.CMD_DUAL_TAP_READ,
            HuaweiProtocol.CMD_TRIPLE_TAP_READ,
            HuaweiProtocol.CMD_LONG_TAP_READ -> {
                val gesture = when (pkg.commandId) {
                    HuaweiProtocol.CMD_DUAL_TAP_READ -> TouchGesture.DOUBLE_TAP
                    HuaweiProtocol.CMD_TRIPLE_TAP_READ -> TouchGesture.TRIPLE_TAP
                    else -> TouchGesture.LONG_PRESS
                }
                val isFb3 = activeModel == T300Protocol.BudsModel.HUAWEI_FREEBUDS_3
                val left = pkg.param(1)
                val right = pkg.param(2)
                val map = _t300State.value.touchSettings.toMutableMap()
                if (left != null && left.isNotEmpty()) {
                    map[Pair(TouchSide.LEFT, gesture)] =
                        HuaweiProtocol.huaweiTapCodeToAction(left[0].toInt(), gesture, isFb3)
                }
                if (right != null && right.isNotEmpty()) {
                    map[Pair(TouchSide.RIGHT, gesture)] =
                        HuaweiProtocol.huaweiTapCodeToAction(right[0].toInt(), gesture, isFb3)
                }
                log("RX Huawei Key [${gesture.name}] updated")
                _t300State.value = _t300State.value.copy(touchSettings = map)
            }
            else -> {
                log("RX Huawei $hexCmd (${pkg.params.size} params)")
            }
        }
    }
}
