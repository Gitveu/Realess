package uwu.realness.gitveu.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import uwu.realness.gitveu.audio.FindBudsChannel
import uwu.realness.gitveu.protocol.BoseProtocol
import uwu.realness.gitveu.protocol.GalaxyProtocol
import uwu.realness.gitveu.protocol.HuaweiProtocol
import uwu.realness.gitveu.protocol.JabraProtocol
import uwu.realness.gitveu.protocol.JblProtocol
import uwu.realness.gitveu.protocol.NothingProtocol
import uwu.realness.gitveu.protocol.PixelBudsProtocol
import uwu.realness.gitveu.protocol.SennheiserProtocol
import uwu.realness.gitveu.protocol.SonyProtocol
import uwu.realness.gitveu.protocol.SoundcoreProtocol
import uwu.realness.gitveu.protocol.T300Protocol
import uwu.realness.gitveu.protocol.XiaomiProtocol
import java.util.concurrent.atomic.AtomicInteger
import uwu.realness.gitveu.protocol.T300Protocol.AncMode
import uwu.realness.gitveu.protocol.T300Protocol.ComponentRole
import uwu.realness.gitveu.protocol.T300Protocol.CustomEqProfile
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
    private var connectJob: Job? = null
    private val sessionGen = AtomicInteger(0)

    // GATT
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var activeTransport = ActiveTransport.NONE
    private var activeModel = T300Protocol.BudsModel.UNKNOWN
    private var activeDeviceAddress: String? = null
    private val huaweiMultiDevices = mutableMapOf<Int, T300Protocol.ConnectedDeviceInfo>()
    private var galaxyManagerInfoSent = false
    private var nothingLastBassLevel = 3
    private var sonySeq = 0
    private var soundcoreLastSound: ByteArray? = null
    private var xiaomiAuthenticated = false
    private var pixelActiveChannel = 18
    private var pixelProbeIndex = 0
    private var jblAcc = ByteArray(0)

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _t300State = MutableStateFlow(T300Protocol.T300State())
    val t300State: StateFlow<T300Protocol.T300State> = _t300State.asStateFlow()

    private val _logFlow = MutableSharedFlow<String>(replay = 50)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private val _unexpectedDisconnectFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val unexpectedDisconnectFlow: SharedFlow<Unit> = _unexpectedDisconnectFlow.asSharedFlow()

    fun isConnectedOrConnectingTo(address: String?, model: T300Protocol.BudsModel): Boolean {
        if (address == null) return false
        return activeDeviceAddress == address &&
            activeModel == model &&
            _connectionState.value != BleConnectionState.DISCONNECTED
    }

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
     * Connect to supported headset (all 13 protocol families).
     * Guards against concurrent duplicate connect() calls using sessionGen & connectJob.
     */
    fun connect(device: BluetoothDevice, overrideModel: T300Protocol.BudsModel? = null) {
        val targetModel = overrideModel ?: T300Protocol.BudsModel.fromDeviceName(device.name)
        if (isConnectedOrConnectingTo(device.address, targetModel)) {
            return
        }

        val gen = sessionGen.incrementAndGet()
        closeResourcesOnly()
        activeModel = targetModel
        activeDeviceAddress = device.address
        _connectionState.value = BleConnectionState.CONNECTING
        val name = device.name ?: "Headset"
        log("Starting connection to $name (${device.address}, model=$activeModel, proto=${activeModel.protocolFamily})...")

        connectJob = scope.launch {
            if (gen != sessionGen.get()) return@launch

            // JBL uses BLE GATT directly
            if (activeModel.isJbl) {
                log("Connecting JBL via BLE GATT...")
                connectGatt(device, gen)
                return@launch
            }

            // Attempt 1: RFCOMM SPP
            val sppConnected = tryConnectSpp(device, gen)
            if (sppConnected || gen != sessionGen.get()) {
                return@launch
            }

            if (!activeModel.isRealme) {
                log("${activeModel.protocolFamily} SPP socket unavailable")
                if (gen == sessionGen.get()) {
                    disconnectInternal(emitUnexpected = true)
                }
                return@launch
            }

            // Attempt 2: BLE GATT Fallback (realme / OnePlus / OPPO)
            log("SPP sockets unavailable. Falling back to BLE GATT...")
            connectGatt(device, gen)
        }
    }

    /**
     * Attempt connection via RFCOMM BluetoothSocket
     */
    private suspend fun tryConnectSpp(device: BluetoothDevice, gen: Int): Boolean {
        bluetoothAdapter?.cancelDiscovery()

        val candidateUuids = when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                if (activeModel.prefersHuaweiPrivateUuid) T300GattConfig.HUAWEI_SPP_UUIDS_PORT16_FIRST
                else T300GattConfig.HUAWEI_SPP_UUIDS_PORT1_FIRST
            }
            T300Protocol.ProtocolFamily.HONOR_CHOICE_SPP -> T300GattConfig.HUAWEI_SPP_UUIDS_PORT1_FIRST
            T300Protocol.ProtocolFamily.NOTHING_SPP -> T300GattConfig.NOTHING_SPP_UUIDS
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> when (activeModel) {
                T300Protocol.BudsModel.GALAXY_BUDS -> T300GattConfig.GALAXY_LEGACY_SPP_UUIDS
                T300Protocol.BudsModel.GALAXY_BUDS_PLUS -> T300GattConfig.GALAXY_PLUS_SPP_UUIDS
                else -> T300GattConfig.GALAXY_MODERN_SPP_UUIDS
            }
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> T300GattConfig.SONY_SPP_UUIDS
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> T300GattConfig.XIAOMI_SPP_UUIDS
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> T300GattConfig.SENNHEISER_SPP_UUIDS
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> T300GattConfig.PIXEL_BUDS_SPP_UUIDS
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP,
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP,
            T300Protocol.ProtocolFamily.JABRA_SPP -> T300GattConfig.STANDARD_SPP_ONLY_UUIDS
            else -> {
                if (activeModel.isOppoOrOnePlus) T300GattConfig.OPPO_ONEPLUS_SPP_UUIDS
                else T300GattConfig.KNOWN_SPP_UUIDS
            }
        }

        for (uuid in candidateUuids) {
            if (gen != sessionGen.get()) return false
            val uuidLabel = when (uuid) {
                T300GattConfig.REALME_VENDOR_SPP_UUID -> "Realme Vendor SPP"
                T300GattConfig.OPPO_ONEPLUS_SPP_UUID -> "OPPO/OnePlus SPP"
                T300GattConfig.HUAWEI_VENDOR_SPP_UUID -> "Huawei Private SPP"
                T300GattConfig.NOTHING_SPP_UUID -> "Nothing SPP"
                T300GattConfig.GALAXY_SPP_UUID -> "Galaxy Buds SPP"
                T300GattConfig.GALAXY_LEGACY_SPP_UUID -> "Galaxy Buds Legacy SPP"
                T300GattConfig.SONY_V2_SPP_UUID -> "Sony MDR V2 SPP"
                T300GattConfig.SONY_V1_SPP_UUID -> "Sony MDR V1 SPP"
                T300GattConfig.XIAOMI_SPP_UUID -> "Xiaomi SPP"
                T300GattConfig.SENNHEISER_SPP_UUID -> "Sennheiser GAIA SPP"
                T300GattConfig.PIXEL_BUDS_SPP_UUID -> "Pixel Buds Maestro SPP"
                T300GattConfig.STANDARD_SPP_UUID -> "Standard SPP"
                else -> "SPP $uuid"
            }
            log("Attempting SPP ($uuidLabel)...")

            val success = tryConnectSingleSocket(device, uuid, insecure = false, gen = gen) ||
                          tryConnectSingleSocket(device, uuid, insecure = true, gen = gen)

            if (success) {
                if (gen != sessionGen.get()) {
                    try { bluetoothSocket?.close() } catch (_: Exception) {}
                    return false
                }
                activeTransport = ActiveTransport.RFCOMM_SPP
                _connectionState.value = BleConnectionState.CONNECTED
                log("✓ SPP connected successfully via $uuidLabel!")

                startSocketReader(gen)
                finishConnectionReady(gen)
                return true
            }
        }
        return false
    }

    private suspend fun tryConnectSingleSocket(device: BluetoothDevice, uuid: UUID, insecure: Boolean, gen: Int): Boolean {
        return withContext(Dispatchers.IO) {
            if (gen != sessionGen.get()) return@withContext false
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

                if (socket.isConnected && gen == sessionGen.get()) {
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
    private fun startSocketReader(gen: Int) {
        socketReadJob?.cancel()
        socketReadJob = scope.launch(Dispatchers.IO) {
            val stream = socketInputStream ?: return@launch
            val buffer = ByteArray(1024)
            var accumulated = ByteArray(0)

            try {
                while (isActive && gen == sessionGen.get() && bluetoothSocket?.isConnected == true) {
                    val count = stream.read(buffer)
                    if (count <= 0) break

                    val incoming = buffer.copyOfRange(0, count)
                    log("RX raw [${incoming.joinToString(" ") { "%02X".format(it) }}]")
                    accumulated += incoming

                    when (activeModel.protocolFamily) {
                        T300Protocol.ProtocolFamily.HUAWEI_SPP,
                        T300Protocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                            val (packets, remaining) = HuaweiProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingHuaweiPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                            val (packets, remaining) = NothingProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingNothingPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                            val (packets, remaining) = GalaxyProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingGalaxyPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.SONY_MDR_SPP -> {
                            val (packets, remaining) = SonyProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSonyPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                            val (packets, remaining) = BoseProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingBosePacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                            val (packets, remaining) = SoundcoreProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSoundcorePacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                            val (packets, remaining) = XiaomiProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingXiaomiPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                            val (packets, remaining) = SennheiserProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSennheiserPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.JABRA_SPP -> {
                            val (packets, remaining) = JabraProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingJabraPacket(pkg)
                        }
                        T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                            val (packets, remaining) = PixelBudsProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingPixelBudsPacket(pkg)
                        }
                        else -> {
                            val (frames, remaining) = T300Protocol.parseFramesFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (frame in frames) handleIncomingFrame(frame)
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive && gen == sessionGen.get()) {
                    log("SPP connection closed: ${e.message}")
                }
            } finally {
                if (gen == sessionGen.get() && _connectionState.value == BleConnectionState.CONNECTED) {
                    disconnectInternal(emitUnexpected = true)
                }
            }
        }
    }

    /**
     * Подключение через BLE GATT
     */
    private fun connectGatt(device: BluetoothDevice, gen: Int) {
        if (gen != sessionGen.get()) return
        _connectionState.value = BleConnectionState.CONNECTING
        activeTransport = ActiveTransport.BLE_GATT

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_AUTO)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private fun closeResourcesOnly() {
        connectJob?.cancel()
        connectJob = null

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
        huaweiMultiDevices.clear()
        galaxyManagerInfoSent = false
        sonySeq = 0
        soundcoreLastSound = null
        xiaomiAuthenticated = false
        pixelActiveChannel = 18
        pixelProbeIndex = 0
        jblAcc = ByteArray(0)
    }

    private fun disconnectInternal(emitUnexpected: Boolean) {
        sessionGen.incrementAndGet()
        closeResourcesOnly()
        activeModel = T300Protocol.BudsModel.UNKNOWN
        activeDeviceAddress = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        if (!emitUnexpected) {
            _t300State.value = T300Protocol.T300State()
        }
        log("Connection closed")
        if (emitUnexpected) {
            _unexpectedDisconnectFlow.tryEmit(Unit)
        }
    }

    /**
     * Отключение всех транспортов
     */
    fun disconnect() {
        disconnectInternal(emitUnexpected = false)
    }

    /**
     * Set ANC Mode
     */
    fun setAncMode(mode: AncMode) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                val pkt = HuaweiProtocol.buildAncModeWrite(mode)
                log("TX Huawei ANC [${mode.name}]")
                sendRawBytes(pkt)
                _t300State.value = _t300State.value.copy(ancMode = mode)
                scope.launch { delay(180); refreshAncState() }
            }
            T300Protocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                val pkt = HuaweiProtocol.buildHonorChoiceAncWrite(mode)
                log("TX HonorChoice ANC [${mode.name}]")
                sendRawBytes(pkt)
                _t300State.value = _t300State.value.copy(ancMode = mode)
                scope.launch { delay(180); refreshAncState() }
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                val pkt = NothingProtocol.buildAncModeWrite(mode, _t300State.value.ancLevel)
                log("TX Nothing ANC [${mode.name}]")
                sendRawBytes(pkt)
                _t300State.value = _t300State.value.copy(ancMode = mode)
                scope.launch { delay(180); refreshAncState() }
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                val pkt = GalaxyProtocol.buildAncWrite(mode, activeModel)
                log("TX Galaxy ANC [${mode.name}]")
                sendRawBytes(pkt)
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> {
                log("TX Sony ANC [${mode.name}]")
                sendRawBytes(SonyProtocol.buildAncSet(sonySeq, mode))
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose ANC [${mode.name}]")
                if (activeModel == T300Protocol.BudsModel.BOSE_QC_45_35) {
                    sendRawBytes(BoseProtocol.buildAnrSet(mode))
                } else {
                    sendRawBytes(BoseProtocol.buildCncSet(mode))
                }
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                log("TX Soundcore ANC [${mode.name}]")
                sendRawBytes(SoundcoreProtocol.buildAncSet(mode, soundcoreLastSound))
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                log("TX Xiaomi ANC [${mode.name}]")
                sendRawBytes(XiaomiProtocol.buildAncSet(mode))
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                log("TX Sennheiser ANC [${mode.name}]")
                when (mode) {
                    AncMode.NOISE_REDUCTION -> {
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_TRANSPARENT, byteArrayOf(0x00)))
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_ANC_ENABLED, byteArrayOf(0x01)))
                    }
                    AncMode.TRANSPARENCY -> {
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_ANC_ENABLED, byteArrayOf(0x00)))
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_TRANSPARENT, byteArrayOf(0x01)))
                    }
                    AncMode.OFF -> {
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_ANC_ENABLED, byteArrayOf(0x00)))
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_TRANSPARENT, byteArrayOf(0x00)))
                    }
                }
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.JABRA_SPP -> {
                log("TX Jabra ANC [${mode.name}]")
                if (activeModel == T300Protocol.BudsModel.JABRA_ELITE_CLASSIC) {
                    sendRawBytes(JabraProtocol.buildNoiseModeSet(mode))
                } else {
                    sendRawBytes(JabraProtocol.buildAmbienceSet(mode))
                }
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                log("TX PixelBuds ANC [${mode.name}]")
                val v = when (mode) {
                    AncMode.OFF -> 1L
                    AncMode.NOISE_REDUCTION -> 2L
                    AncMode.TRANSPARENCY -> 3L
                }
                sendRawBytes(PixelBudsProtocol.buildWriteSettingVarint(pixelActiveChannel, PixelBudsProtocol.SETTING_ANC, v))
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            T300Protocol.ProtocolFamily.JBL_GATT -> {
                log("TX JBL ANC [${mode.name}]")
                sendRawBytes(JblProtocol.buildAncSet(mode))
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            else -> {
                val frame = T300Protocol.buildAncCommandFrame(mode)
                log("TX ANC [${mode.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
        }
    }

    /**
     * Set ANC intensity level (Huawei / Nothing / Xiaomi)
     */
    fun setAncLevel(level: HuaweiProtocol.AncLevel) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                val pkt = HuaweiProtocol.buildAncLevelWrite(level)
                log("TX Huawei ANC Level [${level.name}]")
                sendRawBytes(pkt)
                _t300State.value = _t300State.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
                scope.launch { delay(180); refreshAncState() }
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                val pkt = NothingProtocol.buildAncLevelWrite(level)
                log("TX Nothing ANC Level [${level.name}]")
                sendRawBytes(pkt)
                _t300State.value = _t300State.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
                scope.launch { delay(180); refreshAncState() }
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                log("TX Xiaomi ANC Level [${level.name}]")
                sendRawBytes(XiaomiProtocol.buildAncStrengthSet(level))
                _t300State.value = _t300State.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
            }
            else -> return
        }
    }

    /**
     * Request battery levels
     */
    fun refreshBattery() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP,
            T300Protocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                log("TX Huawei/Honor Battery Req")
                sendRawBytes(HuaweiProtocol.buildBatteryRequest())
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing Battery Req")
                sendRawBytes(NothingProtocol.buildBatteryRequest())
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP,
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                // Push-based protocols
            }
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> {
                sendRawBytes(SonyProtocol.buildBatteryReq(sonySeq, 0x00))
                sendRawBytes(SonyProtocol.buildBatteryReq(sonySeq, 0x01))
                sendRawBytes(SonyProtocol.buildBatteryReq(sonySeq, 0x02))
            }
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> sendRawBytes(BoseProtocol.buildBatteryReq())
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> sendRawBytes(SoundcoreProtocol.buildStateReq())
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> if (xiaomiAuthenticated) sendRawBytes(XiaomiProtocol.buildDeviceInfoReq())
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_BATTERY))
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_CHARGING))
            }
            T300Protocol.ProtocolFamily.JABRA_SPP -> sendRawBytes(JabraProtocol.buildBatteryReq())
            T300Protocol.ProtocolFamily.JBL_GATT -> sendRawBytes(JblProtocol.buildBatteryReq())
            else -> {
                val frame = T300Protocol.buildBatteryRequestFrame()
                log("TX Battery Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
        }
    }

    /**
     * Request current ANC mode
     */
    fun refreshAncState() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP,
            T300Protocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                log("TX Huawei/Honor ANC Req")
                sendRawBytes(HuaweiProtocol.buildAncReadRequest())
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing ANC Req")
                sendRawBytes(NothingProtocol.buildAncReadRequest())
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP,
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {}
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> sendRawBytes(SonyProtocol.buildAncReq(sonySeq))
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildCncReq())
                sendRawBytes(BoseProtocol.buildAnrReq())
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> if (xiaomiAuthenticated) sendRawBytes(XiaomiProtocol.buildRunInfoReq())
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_ANC_ENABLED))
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_TRANSPARENT))
            }
            T300Protocol.ProtocolFamily.JABRA_SPP -> {
                sendRawBytes(JabraProtocol.buildAmbienceReq())
                sendRawBytes(JabraProtocol.buildNoiseModeReq())
            }
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_ANC))
            }
            T300Protocol.ProtocolFamily.JBL_GATT -> sendRawBytes(JblProtocol.buildNoiseReq())
            else -> {
                val frame = T300Protocol.buildAncStateRequestFrame()
                log("TX ANC State Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
        }
    }

    /**
     * Request Game Mode / Low Latency state
     */
    fun refreshGameMode() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei LowLatency Req")
                sendRawBytes(HuaweiProtocol.buildLowLatencyRead())
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing LowLatency Req")
                sendRawBytes(NothingProtocol.buildLowLatencyRead())
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                if (xiaomiAuthenticated) {
                    log("TX Xiaomi LowLatency Req (0x002F)")
                    sendRawBytes(XiaomiProtocol.buildLowLatencyReq())
                }
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_GAME_MODE)
                log("TX GameMode Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set Game Mode / Low Latency (ON / OFF)
     */
    fun setGameMode(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei LowLatency [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuaweiProtocol.buildLowLatencyWrite(enabled))
                _t300State.value = _t300State.value.copy(gameMode = enabled)
                scope.launch { delay(500); refreshGameMode() }
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing LowLatency [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(NothingProtocol.buildLowLatencyWrite(enabled))
                _t300State.value = _t300State.value.copy(gameMode = enabled)
                scope.launch { delay(250); refreshGameMode() }
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                if (!xiaomiAuthenticated) return
                log("TX Xiaomi LowLatency (0x002F) [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(XiaomiProtocol.buildLowLatencySet(enabled))
                _t300State.value = _t300State.value.copy(gameMode = enabled)
                scope.launch { delay(220); refreshGameMode() }
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                val legacy = activeModel == T300Protocol.BudsModel.GALAXY_BUDS
                log("TX Galaxy GamingMode (0x87) [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(GalaxyProtocol.buildGameModeWrite(enabled, legacy))
                _t300State.value = _t300State.value.copy(gameMode = enabled)
            }
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                val isLiberty = activeModel == T300Protocol.BudsModel.SOUNDCORE_LIBERTY_4_NC
                log("TX Soundcore Gaming [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(SoundcoreProtocol.buildGameModeSet(enabled, isLiberty))
                _t300State.value = _t300State.value.copy(gameMode = enabled)
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_GAME_MODE, enabled)
                log("TX GameMode [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(gameMode = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Spatial Audio state (Feature 27 / 0x1B)
     */
    fun refreshSpatialAudio() {
        if (!activeModel.isRealme) return
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_SPATIAL_AUDIO)
        log("TX SpatialAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Spatial Audio (ON / OFF)
     */
    fun setSpatialAudio(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_SPATIAL, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _t300State.value = _t300State.value.copy(spatialAudio = enabled)
            }
            T300Protocol.ProtocolFamily.JBL_GATT -> {
                sendRawBytes(JblProtocol.buildSpatialSet(enabled))
                _t300State.value = _t300State.value.copy(spatialAudio = enabled)
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_SPATIAL_AUDIO, enabled)
                log("TX SpatialAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(spatialAudio = enabled)
            }
            else -> return
        }
    }

    /**
     * Request High-Definition Audio (realme LDAC/LHDC 5.0 or Huawei Sound Quality Preference)
     */
    fun refreshHighQualityAudio() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei SoundQuality Req")
                sendRawBytes(HuaweiProtocol.buildSoundQualityRead())
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO)
                log("TX HighQualityAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set High-Definition Audio (ON / OFF)
     */
    fun setHighQualityAudio(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei SoundQuality [${if (enabled) "QUALITY" else "CONNECTIVITY"}]")
                sendRawBytes(HuaweiProtocol.buildSoundQualityWrite(enabled))
                _t300State.value = _t300State.value.copy(highQualityAudio = enabled)
                scope.launch { delay(200); refreshHighQualityAudio() }
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_HIGH_QUALITY_AUDIO, enabled)
                log("TX HighQualityAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(highQualityAudio = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Dynamic Audio / Ultra Bass state
     */
    fun refreshDynamicAudio() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing BassBoost Req")
                sendRawBytes(NothingProtocol.buildBassBoostRead())
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_DYNAMIC_AUDIO)
                log("TX DynamicAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set Dynamic Audio / Ultra Bass (ON / OFF)
     */
    fun setDynamicAudio(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing BassBoost [${if (enabled) "ON" else "OFF"}, lvl=$nothingLastBassLevel]")
                sendRawBytes(NothingProtocol.buildBassBoostWrite(enabled, nothingLastBassLevel))
                _t300State.value = _t300State.value.copy(dynamicAudio = enabled)
                scope.launch { delay(200); refreshDynamicAudio() }
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_DYNAMIC_AUDIO, enabled)
                log("TX DynamicAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(dynamicAudio = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Dual-Device Connection state
     */
    fun refreshDualConnection() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei DualConnect Req")
                sendRawBytes(HuaweiProtocol.buildDualConnectRead())
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_DUAL_CONNECTION)
                log("TX DualConnection Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set Dual-Device Connection / Seamless Connection (ON / OFF)
     */
    fun setDualConnection(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei DualConnect [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuaweiProtocol.buildDualConnectWrite(enabled))
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
                scope.launch { delay(250); refreshDualConnection() }
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                log("TX Galaxy Seamless [${if (enabled) "ON" else "OFF"}]")
                val legacy = activeModel == T300Protocol.BudsModel.GALAXY_BUDS
                sendRawBytes(GalaxyProtocol.buildSeamlessWrite(enabled, legacy))
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
            }
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildMultipointSet(enabled))
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
            }
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                sendRawBytes(SoundcoreProtocol.buildDualConnSet(enabled))
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                sendRawBytes(XiaomiProtocol.buildSetConfig(XiaomiProtocol.CFG_MULTIPOINT, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
            }
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildWriteSettingVarint(pixelActiveChannel, PixelBudsProtocol.SETTING_MULTIPOINT, if (enabled) 1L else 0L))
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_DUAL_CONNECTION, enabled)
                log("TX DualConnection [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(dualConnection = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Multi-Connect device list
     */
    fun refreshMultiConnectInfo() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                huaweiMultiDevices.clear()
                log("TX Huawei DualConnect Enum")
                sendRawBytes(HuaweiProtocol.buildDualConnectEnumerate())
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildMultiConnectInfoRequestFrame()
                log("TX MultiConnect Info Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Request Auto Play/Pause (Wear Detection) state
     */
    fun refreshAutoPlayPause() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei AutoPause Req")
                sendRawBytes(HuaweiProtocol.buildAutoPauseRead())
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing InEar Req")
                sendRawBytes(NothingProtocol.buildInEarRead())
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_AUTO_PLAY_PAUSE)
                log("TX AutoPlayPause Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set Auto Play/Pause (ON / OFF)
     */
    fun setAutoPlayPause(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei AutoPause [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuaweiProtocol.buildAutoPauseWrite(enabled))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
                scope.launch { delay(200); refreshAutoPlayPause() }
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing InEar [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(NothingProtocol.buildInEarWrite(enabled))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
                scope.launch { delay(200); refreshAutoPlayPause() }
            }
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildAutoPauseSet(enabled))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                sendRawBytes(SoundcoreProtocol.buildAutoPauseSet(enabled))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                sendRawBytes(XiaomiProtocol.buildEarDetectionSet(enabled))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_AUTO_PAUSE, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildWriteSettingVarint(pixelActiveChannel, PixelBudsProtocol.SETTING_OHD, if (enabled) 1L else 0L))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            T300Protocol.ProtocolFamily.JBL_GATT -> {
                sendRawBytes(JblProtocol.buildAutoPlaySet(enabled))
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_AUTO_PLAY_PAUSE, enabled)
                log("TX AutoPlayPause [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(autoPlayPause = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Auto Answer state (Feature 8 / 0x08)
     */
    fun refreshAutoAnswer() {
        if (!activeModel.isRealme) return
        val frame = T300Protocol.buildSwitchFeatureRequestFrame(T300Protocol.FEATURE_ID_AUTO_ANSWER)
        log("TX AutoAnswer Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Auto Answer (ON / OFF)
     */
    fun setAutoAnswer(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildAutoAnswerSet(enabled))
                _t300State.value = _t300State.value.copy(autoAnswer = enabled)
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                sendRawBytes(XiaomiProtocol.buildSetConfig(XiaomiProtocol.CFG_AUTO_ANSWER, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _t300State.value = _t300State.value.copy(autoAnswer = enabled)
            }
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_AUTO_ANSWER, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _t300State.value = _t300State.value.copy(autoAnswer = enabled)
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildSwitchFeatureCommandFrame(T300Protocol.FEATURE_ID_AUTO_ANSWER, enabled)
                log("TX AutoAnswer [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(autoAnswer = enabled)
            }
            else -> return
        }
    }

    /**
     * Request total usage time for Left/Right earbuds (realme Opcode 0x0128)
     */
    fun refreshUseTime() {
        if (!activeModel.isRealme) return
        val frame = T300Protocol.buildUseTimeRequestFrame()
        log("TX UseTime Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request current EQ preset and custom EQ parameters
     */
    fun refreshEq() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei EQ Req")
                sendRawBytes(HuaweiProtocol.buildEqRead())
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing EQ Req")
                sendRawBytes(NothingProtocol.buildEqRead(activeModel.nothingUsesListeningModeEq))
                if (activeModel.supportsCustomEq) {
                    sendRawBytes(NothingProtocol.buildCustomEqRead())
                }
            }
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> {
                sendRawBytes(SonyProtocol.buildEqReq(sonySeq))
            }
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildEqReq())
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                if (xiaomiAuthenticated) {
                    sendRawBytes(XiaomiProtocol.buildGetConfigReq(XiaomiProtocol.CFG_EQ_PRESET))
                    sendRawBytes(XiaomiProtocol.buildCustomEqReq())
                }
            }
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildEqGainsReq())
            }
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_USER_EQ))
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildEqRequestFrame()
                log("TX EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Request custom EQ curves (realme Opcode 0x0122)
     */
    fun refreshAllEq() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildAllEqRequestFrame()
                log("TX All EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                if (activeModel.supportsCustomEq) sendRawBytes(NothingProtocol.buildCustomEqRead())
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                if (xiaomiAuthenticated) sendRawBytes(XiaomiProtocol.buildCustomEqReq())
            }
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                sendRawBytes(HuaweiProtocol.buildEqRead())
            }
            else -> return
        }
    }

    /**
     * Save, create, or delete custom EQ across all supported protocols.
     */
    fun setCustomEq(
        gains: List<Int>,
        profileId: Int? = null,
        profileName: String? = null,
        createNew: Boolean = false,
        delete: Boolean = false
    ) {
        val spec = activeModel.customEqSpec() ?: return
        val clampedGains = List(spec.bandLabels.size) { idx ->
            gains.getOrElse(idx) { 0 }.coerceIn(spec.minDb, spec.maxDb)
        }
        val currentState = _t300State.value
        val freqs = currentState.customEqFreqs.takeIf { it.size == clampedGains.size } ?: spec.defaultFreqs

        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val existing = currentState.customEqProfiles
                val existingProfile = existing.firstOrNull { it.id == profileId }
                val targetId = when {
                    createNew -> ((existing.maxOfOrNull { it.id } ?: 0) + 1).coerceAtLeast(1)
                    profileId != null -> profileId
                    existing.isNotEmpty() -> existing.first().id
                    else -> 1
                }
                val action = when {
                    delete -> T300Protocol.CUSTOM_EQ_ACTION_DELETE
                    createNew || (existingProfile == null && existing.isEmpty()) -> T300Protocol.CUSTOM_EQ_ACTION_CREATE
                    else -> T300Protocol.CUSTOM_EQ_ACTION_SAVE
                }
                val name = (profileName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: existingProfile?.name
                    ?: "Custom $targetId")
                val tag = existingProfile?.tag
                    ?: existing.firstOrNull()?.tag
                    ?: byteArrayOf((-6).toByte(), 6.toByte())
                val profile = CustomEqProfile(
                    id = targetId,
                    name = name,
                    freqs = freqs,
                    gains = clampedGains,
                    isSelected = !delete,
                    tag = tag
                )
                val frame = T300Protocol.buildCustomEqSetFrame(action, profile)
                log("TX CustomEQ [action=$action, id=$targetId, name=$name, gains=$clampedGains]")
                sendData(frame)
                if (!delete) {
                    _t300State.value = currentState.copy(
                        eqPreset = null,
                        activeCustomEqId = targetId,
                        activeCustomEqName = name,
                        customEqGains = clampedGains,
                        customEqFreqs = freqs
                    )
                }
                scope.launch {
                    delay(220)
                    refreshAllEq()
                    delay(120)
                    refreshEq()
                }
            }
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                val existing = currentState.customEqProfiles
                val targetId = when {
                    createNew -> ((existing.maxOfOrNull { it.id } ?: 10) + 1).coerceAtLeast(11)
                    profileId != null -> profileId
                    existing.isNotEmpty() -> existing.first().id
                    else -> 11
                }
                val name = profileName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: existing.firstOrNull { it.id == targetId }?.name
                    ?: "Custom $targetId"
                log("TX Huawei CustomEQ [id=$targetId, name=$name, del=$delete, gains=$clampedGains]")
                sendRawBytes(HuaweiProtocol.buildCustomEqWrite(targetId, name, clampedGains, delete))
                if (!delete) {
                    _t300State.value = currentState.copy(
                        eqPreset = null,
                        activeCustomEqId = targetId,
                        activeCustomEqName = name,
                        customEqGains = clampedGains,
                        customEqFreqs = freqs
                    )
                }
                scope.launch { delay(280); refreshEq() }
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                val bass = clampedGains.getOrElse(0) { 0 }
                val mid = clampedGains.getOrElse(1) { 0 }
                val treble = clampedGains.getOrElse(2) { 0 }
                log("TX Nothing CustomEQ [B=$bass, M=$mid, T=$treble]")
                sendRawBytes(NothingProtocol.buildCustomEqWrite(bass, mid, treble))
                scope.launch {
                    delay(120)
                    sendRawBytes(NothingProtocol.buildSelectCustomEq(activeModel.nothingUsesListeningModeEq))
                }
                _t300State.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = listOf(bass, mid, treble)
                )
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                if (!xiaomiAuthenticated) return
                if (delete) {
                    log("TX Xiaomi CustomEQ Reset (0x0037)")
                    sendRawBytes(XiaomiProtocol.buildCustomEqReset())
                    _t300State.value = currentState.copy(
                        customEqGains = List(clampedGains.size) { 0 }
                    )
                } else {
                    log("TX Xiaomi CustomEQ Save (0x0037) [gains=$clampedGains]")
                    sendRawBytes(XiaomiProtocol.buildCustomEqSave(freqs, clampedGains, spec.maxDb))
                    _t300State.value = currentState.copy(
                        eqPreset = null,
                        activeCustomEqName = "Custom",
                        customEqGains = clampedGains,
                        customEqFreqs = freqs
                    )
                }
                scope.launch { delay(220); sendRawBytes(XiaomiProtocol.buildCustomEqReq()) }
            }
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> {
                log("TX Sony CustomEQ [gains=$clampedGains]")
                sendRawBytes(SonyProtocol.buildCustomEqSet(sonySeq, clampedGains))
                _t300State.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose CustomEQ [gains=$clampedGains]")
                scope.launch {
                    for (i in clampedGains.indices) {
                        sendRawBytes(BoseProtocol.buildEqBandSet(i, clampedGains[i]))
                        delay(50)
                    }
                }
                _t300State.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                log("TX Soundcore CustomEQ [gains=$clampedGains]")
                sendRawBytes(SoundcoreProtocol.buildCustomEqSet(clampedGains))
                _t300State.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                log("TX Sennheiser CustomEQ [gains=$clampedGains]")
                scope.launch {
                    for (i in clampedGains.indices) {
                        sendRawBytes(SennheiserProtocol.buildEqBandSet(i, clampedGains[i]))
                        delay(50)
                    }
                }
                _t300State.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                log("TX PixelBuds CustomEQ [gains=$clampedGains]")
                sendRawBytes(PixelBudsProtocol.buildWriteUserEq(pixelActiveChannel, clampedGains))
                _t300State.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            else -> return
        }
    }

    /**
     * Activate an existing saved custom EQ profile (realme / Huawei).
     */
    fun selectCustomEqProfile(profile: CustomEqProfile) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.REALME_TL -> {
                setCustomEq(
                    gains = profile.gains,
                    profileId = profile.id,
                    profileName = profile.name,
                    createNew = false,
                    delete = false
                )
            }
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei Select CustomEQ [id=${profile.id}, name=${profile.name}]")
                sendRawBytes(HuaweiProtocol.buildEqWrite(profile.id.toByte()))
                _t300State.value = _t300State.value.copy(
                    eqPreset = null,
                    activeCustomEqId = profile.id,
                    activeCustomEqName = profile.name,
                    customEqGains = profile.gains,
                    customEqFreqs = profile.freqs
                )
                scope.launch { delay(220); refreshEq() }
            }
            else -> return
        }
    }

    /**
     * Trigger optional native firmware Find My Earbuds chime where supported.
     */
    fun setHardwareFindBuds(enabled: Boolean, channel: FindBudsChannel = FindBudsChannel.BOTH) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.REALME_TL -> {
                log("TX Realme FindBuds [${if (enabled) "ON" else "OFF"}]")
                sendData(T300Protocol.buildFindBudsFrame(enabled))
                _t300State.value = _t300State.value.copy(hardwareFindActive = enabled)
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                val sideByte: Byte = when (channel) {
                    FindBudsChannel.LEFT -> 0x01
                    FindBudsChannel.RIGHT -> 0x03
                    FindBudsChannel.BOTH -> 0x06
                }
                log("TX Nothing RingBuds [side=$channel, ${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(NothingProtocol.buildRingBuds(sideByte, enabled))
                _t300State.value = _t300State.value.copy(hardwareFindActive = enabled)
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                val legacy = activeModel == T300Protocol.BudsModel.GALAXY_BUDS
                log("TX Galaxy FindBuds [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(GalaxyProtocol.buildFindBudsWrite(enabled, legacy))
                _t300State.value = _t300State.value.copy(hardwareFindActive = enabled)
            }
            else -> return
        }
    }

    /**
     * Rename device in headset firmware where protocol supports it (Bose BMAP).
     */
    fun renameFirmwareDevice(newName: String): Boolean {
        if (_connectionState.value != BleConnectionState.CONNECTED) return false
        return when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose Rename -> \"$newName\"")
                sendRawBytes(BoseProtocol.buildRename(newName))
                true
            }
            else -> false
        }
    }

    /**
     * Set EQ preset
     */
    fun setEqPreset(preset: EqPreset) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                val hwId = HuaweiProtocol.eqPresetToHuaweiId(preset, activeModel.huaweiEqUsesPro3Ids)
                log("TX Huawei EQ [${preset.name} -> id=$hwId]")
                sendRawBytes(HuaweiProtocol.buildEqWrite(hwId))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
                scope.launch { delay(200); refreshEq() }
            }
            T300Protocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                log("TX HonorChoice EQ [${preset.name}]")
                sendRawBytes(HuaweiProtocol.buildHonorChoiceEqWrite(preset))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
            }
            T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing EQ [${preset.name}]")
                sendRawBytes(NothingProtocol.buildEqWrite(preset, activeModel.nothingUsesListeningModeEq))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
                scope.launch { delay(200); refreshEq() }
            }
            T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                log("TX Galaxy EQ [${preset.name}]")
                val legacy = activeModel == T300Protocol.BudsModel.GALAXY_BUDS
                sendRawBytes(GalaxyProtocol.buildEqWrite(preset, legacy))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
            }
            T300Protocol.ProtocolFamily.SONY_MDR_SPP -> {
                log("TX Sony EQ [${preset.name}]")
                sendRawBytes(SonyProtocol.buildEqSet(sonySeq, preset))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
            }
            T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                log("TX Xiaomi EQ [${preset.name}]")
                sendRawBytes(XiaomiProtocol.buildEqSet(preset))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
            }
            T300Protocol.ProtocolFamily.JBL_GATT -> {
                log("TX JBL EQ [${preset.name}]")
                sendRawBytes(JblProtocol.buildEqSet(preset))
                _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = null)
            }
            else -> {
                val frame = T300Protocol.buildEqCommandFrame(preset)
                log("TX EQ [${preset.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _t300State.value = _t300State.value.copy(
                    eqPreset = preset,
                    activeCustomEqName = null
                )
            }
        }
    }

    /**
     * Request touch button settings
     */
    fun refreshKeySettings() {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
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
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildKeyFunctionRequestFrame()
                log("TX KeySettings Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set touch button setting
     */
    fun setKeySetting(side: TouchSide, gesture: TouchGesture, action: TouchAction) {
        when (activeModel.protocolFamily) {
            T300Protocol.ProtocolFamily.HUAWEI_SPP -> {
                val isFb3 = activeModel == T300Protocol.BudsModel.HUAWEI_FREEBUDS_3
                val code = HuaweiProtocol.actionToHuaweiTapCode(action, gesture, isFb3)
                log("TX Huawei Key [${side.name} ${gesture.name} -> ${action.name} (code=$code)]")
                sendRawBytes(HuaweiProtocol.buildKeyWrite(gesture, side, code))
                val currentMap = _t300State.value.touchSettings.toMutableMap()
                currentMap[Pair(side, gesture)] = action
                _t300State.value = _t300State.value.copy(touchSettings = currentMap)
            }
            T300Protocol.ProtocolFamily.REALME_TL -> {
                val frame = T300Protocol.buildKeyFunctionCommandFrame(side, gesture, action)
                log("TX KeySetting [${side.name} ${gesture.name} -> ${action.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)

                val currentMap = _t300State.value.touchSettings.toMutableMap()
                currentMap[Pair(side, gesture)] = action
                _t300State.value = _t300State.value.copy(touchSettings = currentMap)
            }
            else -> return
        }
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

    private fun finishConnectionReady(gen: Int) {
        scope.launch {
            delay(150)
            if (gen != sessionGen.get()) return@launch
            log("✓ Device ready! Requesting state (${activeModel.protocolFamily})...")

            when (activeModel.protocolFamily) {
                T300Protocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                    // Galaxy Buds push 0x61 unsolicited
                    return@launch
                }
                T300Protocol.ProtocolFamily.XIAOMI_SPP -> {
                    log("TX Xiaomi Auth Challenge")
                    sendRawBytes(XiaomiProtocol.buildAuthStart())
                    startPolling(gen)
                    return@launch
                }
                T300Protocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                    sendRawBytes(BoseProtocol.buildNotifyEnable())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildBatteryReq())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildCncReq())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildAnrReq())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildEqReq())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildMultipointReq())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildAutoPauseReq())
                    delay(80)
                    sendRawBytes(BoseProtocol.buildAutoAnswerReq())
                    startPolling(gen)
                    return@launch
                }
                T300Protocol.ProtocolFamily.SOUNDCORE_SPP -> {
                    sendRawBytes(SoundcoreProtocol.buildStateReq())
                    startPolling(gen)
                    return@launch
                }
                T300Protocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                    for (feat in byteArrayOf(3, 8, 11, 12, 13)) {
                        sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_REGISTER_NOTIFY, byteArrayOf(feat)))
                        delay(50)
                    }
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_BATTERY))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_CHARGING))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_ANC_ENABLED))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_TRANSPARENT))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_AUTO_PAUSE))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_AUTO_ANSWER))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_SPATIAL))
                    delay(60)
                    sendRawBytes(SennheiserProtocol.buildEqGainsReq())
                    startPolling(gen)
                    return@launch
                }
                T300Protocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                    for (ch in PixelBudsProtocol.PROBE_CHANNELS) {
                        if (gen != sessionGen.get()) return@launch
                        sendRawBytes(PixelBudsProtocol.buildRpcFrame(ch, PixelBudsProtocol.METHOD_GET_SW_INFO))
                        delay(120)
                    }
                    return@launch
                }
                T300Protocol.ProtocolFamily.JBL_GATT -> {
                    sendRawBytes(JblProtocol.buildBatteryReq())
                    delay(120)
                    sendRawBytes(JblProtocol.buildNoiseReq())
                    delay(120)
                    sendRawBytes(JblProtocol.buildStatusReq(0x31))
                    delay(100)
                    sendRawBytes(JblProtocol.buildStatusReq(0x34))
                    delay(100)
                    sendRawBytes(JblProtocol.buildStatusReq(0x38))
                    startPolling(gen)
                    return@launch
                }
                T300Protocol.ProtocolFamily.NOTHING_SPP -> {
                    log("TX Nothing Handshake")
                    sendRawBytes(NothingProtocol.buildHandshakeRequest())
                    delay(100)
                }
                else -> {}
            }

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
                if (activeModel.isRealme) {
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
            if (activeModel.isRealme) {
                registerNotifications()
            }
            startPolling(gen)
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

    private fun startPolling(gen: Int) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && gen == sessionGen.get() && _connectionState.value == BleConnectionState.CONNECTED) {
                val interval = when {
                    activeModel.isBose || activeModel.isSennheiser || activeModel.isJabra || activeModel.isSony || activeModel.isXiaomi -> 10_000L
                    activeModel.isSoundcore || activeModel.isJbl -> 5_000L
                    else -> POLL_INTERVAL_MS
                }
                delay(interval)
                if (gen != sessionGen.get() || _connectionState.value != BleConnectionState.CONNECTED) break
                if (activeModel.isBose || activeModel.isSoundcore || activeModel.isSennheiser || activeModel.isJabra || activeModel.isSony || activeModel.isXiaomi || activeModel.isJbl) {
                    refreshBattery()
                    delay(80)
                    if (activeModel.supportsAnc) refreshAncState()
                    continue
                }
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

            // Prioritize JBL GATT service when connecting to JBL
            if (activeModel.isJbl) {
                val jblSvc = gatt.getService(T300GattConfig.JBL_GATT_SERVICE)
                if (jblSvc != null) {
                    for (char in jblSvc.characteristics) {
                        val props = char.properties
                        val isWritable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        val isNotifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                        if (isWritable && foundWrite == null) foundWrite = char
                        if (isNotifiable && foundNotify == null) foundNotify = char
                    }
                }
            }

            if (foundWrite == null || foundNotify == null) {
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
            finishConnectionReady(sessionGen.get())
        }

        private fun handleGattRx(bytes: ByteArray) {
            if (activeModel.isJbl) {
                jblAcc += bytes
                val (pkts, rem) = JblProtocol.parsePacketsFromStreamBuffer(jblAcc)
                jblAcc = rem
                for (pkt in pkts) handleIncomingJblPacket(pkt)
            } else {
                T300Protocol.parseFrame(bytes)?.let { handleIncomingFrame(it) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleGattRx(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { bytes -> handleGattRx(bytes) }
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
                val profiles = T300Protocol.parseCustomEqProfilesPayload(frame.payload)
                log("RX All EQ: ${profiles.map { "${it.name}(id=${it.id})${if (it.isSelected) " [active]" else ""}" }}")
                val activeCustom = profiles.firstOrNull { it.isSelected }
                _t300State.value = if (activeCustom != null) {
                    _t300State.value.copy(
                        eqPreset = null,
                        activeCustomEqId = activeCustom.id,
                        activeCustomEqName = activeCustom.name,
                        customEqProfiles = profiles,
                        customEqGains = activeCustom.gains,
                        customEqFreqs = activeCustom.freqs
                    )
                } else {
                    _t300State.value.copy(
                        customEqProfiles = profiles,
                        customEqGains = _t300State.value.customEqGains.ifEmpty { profiles.firstOrNull()?.gains ?: emptyList() },
                        customEqFreqs = _t300State.value.customEqFreqs.ifEmpty { profiles.firstOrNull()?.freqs ?: emptyList() }
                    )
                }
            }
            T300Protocol.CMD_CUSTOM_EQ_SET_RSP -> {
                val ok = frame.payload.isNotEmpty() && frame.payload[0].toInt() == 0
                log("RX CustomEQ Set Rsp [$hexPayload] -> ${if (ok) "OK" else "ERR"}")
                if (ok) {
                    scope.launch {
                        delay(120)
                        refreshAllEq()
                        delay(100)
                        refreshEq()
                    }
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
                if (data != null && data.isNotEmpty()) {
                    val modeByte = data.last().toInt() and 0xFF
                    val ancMode = when (modeByte) {
                        0 -> AncMode.OFF
                        1 -> AncMode.NOISE_REDUCTION
                        2 -> AncMode.TRANSPARENCY
                        else -> null
                    }
                    val ancLevel = if (!activeModel.isHonorChoice && data.size >= 2 && modeByte == 1) {
                        HuaweiProtocol.AncLevel.fromByte(data[0])
                    } else null
                    if (ancMode != null) {
                        log("RX Huawei/Honor ANC: ${ancMode.name}${if (ancLevel != null) " (${ancLevel.name})" else ""}")
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
                val modeId = if (cur != null && cur.isNotEmpty()) cur[0].toInt() else null
                val customProfiles = HuaweiProtocol.parseCustomEqProfiles(customRaw, activeId = modeId)
                val activeCustom = customProfiles.firstOrNull { it.isSelected }
                if (modeId != null) {
                    val preset = HuaweiProtocol.huaweiIdToEqPreset(modeId, activeModel.huaweiEqUsesPro3Ids)
                    if (preset != null) {
                        log("RX Huawei EQ: ${preset.name} (id=$modeId)")
                        _t300State.value = _t300State.value.copy(
                            eqPreset = preset,
                            activeCustomEqId = null,
                            activeCustomEqName = null,
                            customEqProfiles = customProfiles
                        )
                    } else {
                        val customName = activeCustom?.name ?: "EQ #$modeId"
                        log("RX Huawei EQ Custom: $customName (id=$modeId)")
                        _t300State.value = _t300State.value.copy(
                            eqPreset = null,
                            activeCustomEqId = modeId,
                            activeCustomEqName = customName,
                            customEqProfiles = customProfiles,
                            customEqGains = activeCustom?.gains ?: _t300State.value.customEqGains,
                            customEqFreqs = activeCustom?.freqs ?: _t300State.value.customEqFreqs
                        )
                    }
                } else if (customProfiles.isNotEmpty()) {
                    _t300State.value = _t300State.value.copy(customEqProfiles = customProfiles)
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

    /**
     * Обработка входящего пакета Nothing / CMF SPP (SOF 0x55)
     */
    private fun handleIncomingNothingPacket(pkg: NothingProtocol.Packet) {
        val hexCmd = "0x" + pkg.commandId.toString(16).uppercase().padStart(4, '0')
        val p = pkg.payload
        when (pkg.commandId) {
            NothingProtocol.CMD_HANDSHAKE_RSP -> {
                log("RX Nothing Handshake ($hexCmd, ${p.size}B)")
            }
            NothingProtocol.CMD_BATTERY_RSP,
            NothingProtocol.CMD_BATTERY_EVENT -> {
                val batteries = NothingProtocol.parseBatteryPayload(p)
                if (batteries.isNotEmpty()) {
                    val valid = batteries.filter { it.level >= 0 }.map {
                        val roleName = when (it.role) {
                            ComponentRole.LEFT_EAR -> "Left"
                            ComponentRole.RIGHT_EAR -> "Right"
                            ComponentRole.CASE -> "Case"
                        }
                        "$roleName: ${it.level}%${if (it.isCharging) " ⚡" else ""}"
                    }
                    log("RX Nothing Battery ($hexCmd) -> $valid")
                    updateBatteryState(batteries, isSnapshot = false)
                }
            }
            NothingProtocol.CMD_ANC_RSP,
            NothingProtocol.CMD_ANC_EVENT -> {
                val parsed = NothingProtocol.parseAncPayload(p)
                if (parsed != null) {
                    val (mode, level) = parsed
                    log("RX Nothing ANC: ${mode.name}${if (level != null) " (${level.name})" else ""}")
                    _t300State.value = _t300State.value.copy(ancMode = mode, ancLevel = level ?: _t300State.value.ancLevel)
                }
            }
            NothingProtocol.CMD_EQ_CLASSIC_RSP,
            NothingProtocol.CMD_EQ_LISTENING_RSP -> {
                if (p.isNotEmpty()) {
                    val id = p[0].toInt() and 0xFF
                    val useListening = pkg.commandId == NothingProtocol.CMD_EQ_LISTENING_RSP
                    val (preset, customName) = NothingProtocol.nothingIdToEqPreset(id, useListening)
                    log("RX Nothing EQ: ${preset?.name ?: customName ?: "id=$id"}")
                    _t300State.value = _t300State.value.copy(eqPreset = preset, activeCustomEqName = customName)
                }
            }
            NothingProtocol.CMD_CUSTOM_EQ_RSP -> {
                val gains = NothingProtocol.parseCustomEqPayload(p)
                if (gains != null) {
                    log("RX Nothing CustomEQ: $gains")
                    _t300State.value = _t300State.value.copy(customEqGains = gains)
                }
            }
            NothingProtocol.CMD_IN_EAR_RSP -> {
                if (p.size >= 3) {
                    val on = (p[2].toInt() and 0xFF) == 1
                    log("RX Nothing InEar: ${if (on) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(autoPlayPause = on)
                }
            }
            NothingProtocol.CMD_LOW_LATENCY_RSP -> {
                if (p.isNotEmpty()) {
                    val on = (p[0].toInt() and 0xFF) == 1
                    log("RX Nothing LowLatency: ${if (on) "ON" else "OFF"}")
                    _t300State.value = _t300State.value.copy(gameMode = on)
                }
            }
            NothingProtocol.CMD_BASS_BOOST_RSP -> {
                if (p.isNotEmpty()) {
                    val on = (p[0].toInt() and 0xFF) == 1
                    if (p.size >= 2) {
                        val lvl = p[1].toInt() and 0xFF
                        if (lvl in 1..5) nothingLastBassLevel = lvl
                    }
                    log("RX Nothing BassBoost: ${if (on) "ON" else "OFF"} (lvl=$nothingLastBassLevel)")
                    _t300State.value = _t300State.value.copy(dynamicAudio = on)
                }
            }
            else -> {
                log("RX Nothing $hexCmd (${p.size}B)")
            }
        }
    }

    /**
     * Обработка входящего пакета Samsung Galaxy Buds SPP (0xFD..0xDD / 0xFE..0xEE)
     */
    private fun handleIncomingGalaxyPacket(pkg: GalaxyProtocol.Packet) {
        val hexCmd = "0x" + pkg.msgId.toString(16).uppercase().padStart(2, '0')
        val p = pkg.payload
        when (pkg.msgId) {
            GalaxyProtocol.MSG_EXTENDED_STATUS_UPDATED -> {
                val parsed = GalaxyProtocol.parseExtendedStatus(p, activeModel)
                if (parsed != null) {
                    val valid = parsed.batteries.filter { it.level >= 0 }.map {
                        val roleName = when (it.role) {
                            ComponentRole.LEFT_EAR -> "Left"
                            ComponentRole.RIGHT_EAR -> "Right"
                            ComponentRole.CASE -> "Case"
                        }
                        "$roleName: ${it.level}%${if (it.isCharging) " ⚡" else ""}"
                    }
                    log("RX Galaxy ExtendedStatus -> $valid, anc=${parsed.ancMode}, eq=${parsed.eqPreset ?: parsed.customEqName}")
                    updateBatteryState(parsed.batteries, isSnapshot = true)
                    _t300State.value = _t300State.value.copy(
                        ancMode = parsed.ancMode ?: _t300State.value.ancMode,
                        eqPreset = parsed.eqPreset,
                        activeCustomEqName = parsed.customEqName,
                        dualConnection = parsed.seamlessConnection ?: _t300State.value.dualConnection
                    )
                }
                if (!galaxyManagerInfoSent) {
                    galaxyManagerInfoSent = true
                    val legacy = activeModel == T300Protocol.BudsModel.GALAXY_BUDS
                    log("TX Galaxy ManagerInfo (0x88)")
                    sendRawBytes(GalaxyProtocol.buildManagerInfo(legacy))
                }
            }
            GalaxyProtocol.MSG_STATUS_UPDATED -> {
                val batteries = GalaxyProtocol.parseStatusUpdated(p)
                if (batteries.isNotEmpty()) {
                    val valid = batteries.filter { it.level >= 0 }.map {
                        val roleName = when (it.role) {
                            ComponentRole.LEFT_EAR -> "Left"
                            ComponentRole.RIGHT_EAR -> "Right"
                            ComponentRole.CASE -> "Case"
                        }
                        "$roleName: ${it.level}%"
                    }
                    log("RX Galaxy StatusUpdated -> $valid")
                    updateBatteryState(batteries, isSnapshot = true)
                }
            }
            GalaxyProtocol.MSG_NOISE_CONTROLS_UPDATE -> {
                if (p.isNotEmpty()) {
                    val mode = GalaxyProtocol.mapNoiseControlByte(p[0].toInt() and 0xFF)
                    if (mode != null) {
                        log("RX Galaxy ANC: ${mode.name}")
                        _t300State.value = _t300State.value.copy(ancMode = mode)
                    }
                }
            }
            GalaxyProtocol.MSG_AMBIENT_UPDATE -> {
                if (p.isNotEmpty()) {
                    val mode = if ((p[0].toInt() and 0xFF) == 1) AncMode.TRANSPARENCY else AncMode.OFF
                    log("RX Galaxy Ambient: ${mode.name}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                }
            }
            GalaxyProtocol.MSG_NOISE_REDUCTION_UPDATE -> {
                if (p.isNotEmpty()) {
                    val mode = if ((p[0].toInt() and 0xFF) == 1) AncMode.NOISE_REDUCTION else AncMode.OFF
                    log("RX Galaxy NoiseReduction: ${mode.name}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                }
            }
            else -> {
                log("RX Galaxy $hexCmd (${p.size}B)")
            }
        }
    }

    /**
     * Sony MDR (V1 / V2) incoming packet handler
     */
    private fun handleIncomingSonyPacket(pkg: SonyProtocol.Packet) {
        if (pkg.dataType == SonyProtocol.TYPE_ACK.toInt()) {
            sonySeq = pkg.seq and 1
            return
        }
        sendRawBytes(SonyProtocol.buildAck(pkg.seq))
        val p = pkg.payload
        if (p.isEmpty()) return
        val cmd = p[0].toInt() and 0xFF
        when (cmd) {
            SonyProtocol.CMD_POWER_RET_STATUS,
            SonyProtocol.CMD_POWER_NTFY_STATUS -> {
                if (p.size < 4) return
                val inq = p[1].toInt() and 0xFF
                val batteries = mutableListOf<T300Protocol.BatteryInfo>()
                when (inq) {
                    0x00 -> { // Single battery
                        val lvl = (p[2].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val chg = (p[3].toInt() and 0xFF) == 1
                        batteries.add(T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, chg))
                        batteries.add(T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, chg))
                    }
                    0x01 -> if (p.size >= 6) { // Left & Right
                        val ll = (p[2].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val lc = (p[3].toInt() and 0xFF) == 1
                        val rl = (p[4].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val rc = (p[5].toInt() and 0xFF) == 1
                        batteries.add(T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, ll, lc))
                        batteries.add(T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, rl, rc))
                    }
                    0x02 -> { // Cradle
                        val cl = (p[2].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val cc = (p[3].toInt() and 0xFF) == 1
                        batteries.add(T300Protocol.BatteryInfo(ComponentRole.CASE, if (cl == 0 && !cc) -1 else cl, cc))
                    }
                }
                if (batteries.isNotEmpty()) updateBatteryState(batteries, isSnapshot = false)
            }
            SonyProtocol.CMD_NCASM_RET_PARAM,
            SonyProtocol.CMD_NCASM_NTFY_PARAM -> {
                if (p.size >= 5) {
                    val totalOn = (p[3].toInt() and 0xFF) != 0
                    val ncAsmMode = p[4].toInt() and 0xFF
                    val mode = when {
                        !totalOn -> AncMode.OFF
                        ncAsmMode == 0 -> AncMode.NOISE_REDUCTION
                        else -> AncMode.TRANSPARENCY
                    }
                    log("RX Sony ANC: ${mode.name}")
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                }
            }
            SonyProtocol.CMD_EQ_RET_PARAM,
            SonyProtocol.CMD_EQ_NTFY_PARAM -> {
                if (p.size >= 3) {
                    val presetId = p[2].toInt() and 0xFF
                    val eq = when (presetId) {
                        0x00 -> EqPreset.ORIGINAL_SOUND
                        0x04 -> EqPreset.SERENADE
                        0x01 -> EqPreset.PURE_BASS
                        0x05 -> EqPreset.DEEP_BASS
                        else -> null
                    }
                    var gains = _t300State.value.customEqGains
                    if (p.size >= 4) {
                        val bCount = p[3].toInt() and 0xFF
                        if (bCount in 5..6 && p.size >= 4 + bCount) {
                            gains = List(bCount) { idx -> ((p[4 + idx].toInt() and 0xFF) - 10).coerceIn(-10, 10) }
                        }
                    }
                    val customLabel = when {
                        eq != null -> null
                        presetId == 0xA0 -> "Custom"
                        else -> "Preset #$presetId"
                    }
                    _t300State.value = _t300State.value.copy(
                        eqPreset = eq,
                        activeCustomEqName = customLabel,
                        customEqGains = gains
                    )
                }
            }
        }
    }

    /**
     * Bose BMAP incoming packet handler
     */
    private fun handleIncomingBosePacket(pkg: BoseProtocol.Packet) {
        if (pkg.op != BoseProtocol.OP_STATUS.toInt() && pkg.op != BoseProtocol.OP_RESULT.toInt()) return
        val p = pkg.payload
        when (pkg.block) {
            BoseProtocol.BLOCK_STATUS.toInt() -> {
                if (pkg.function == BoseProtocol.FUNC_BATTERY.toInt()) {
                    val bats = BoseProtocol.parseBattery(p)
                    if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = false)
                }
            }
            BoseProtocol.BLOCK_SETTINGS.toInt() -> {
                if (p.isEmpty()) return
                when (pkg.function) {
                    BoseProtocol.FUNC_CNC.toInt() -> if (p.size >= 2) {
                        val steps = (p[0].toInt() and 0xFF).coerceAtLeast(2)
                        val raw = p[1].toInt() and 0xFF
                        val mode = when {
                            raw == 0 -> AncMode.NOISE_REDUCTION
                            raw >= steps - 1 -> AncMode.TRANSPARENCY
                            else -> AncMode.NOISE_REDUCTION
                        }
                        _t300State.value = _t300State.value.copy(ancMode = mode)
                    }
                    BoseProtocol.FUNC_ANR.toInt() -> {
                        val mode = when (p[0].toInt() and 0xFF) {
                            0 -> AncMode.OFF
                            3 -> AncMode.TRANSPARENCY
                            else -> AncMode.NOISE_REDUCTION
                        }
                        _t300State.value = _t300State.value.copy(ancMode = mode)
                    }
                    BoseProtocol.FUNC_EQUALIZER.toInt() -> {
                        val gains = when {
                            p.size >= 9 -> listOf(p[0].toInt(), p[3].toInt(), p[6].toInt())
                            p.size >= 3 -> listOf(p[0].toInt(), p[1].toInt(), p[2].toInt())
                            else -> null
                        }
                        if (gains != null) {
                            val clamped = gains.map { it.coerceIn(-10, 10) }
                            log("RX Bose EQ: $clamped")
                            _t300State.value = _t300State.value.copy(customEqGains = clamped)
                        }
                    }
                    BoseProtocol.FUNC_MULTIPOINT.toInt() -> {
                        _t300State.value = _t300State.value.copy(dualConnection = (p[0].toInt() and 0x01) != 0)
                    }
                    BoseProtocol.FUNC_AUTO_PAUSE.toInt() -> {
                        _t300State.value = _t300State.value.copy(autoPlayPause = (p[0].toInt() and 0xFF) != 0)
                    }
                    BoseProtocol.FUNC_AUTO_ANSWER.toInt() -> {
                        _t300State.value = _t300State.value.copy(autoAnswer = (p[0].toInt() and 0xFF) != 0)
                    }
                }
            }
        }
    }

    /**
     * Anker Soundcore incoming packet handler
     */
    private fun handleIncomingSoundcorePacket(pkg: SoundcoreProtocol.Packet) {
        val b = pkg.body
        when (pkg.command) {
            SoundcoreProtocol.CMD_BATTERY_LEVEL -> {
                if (b.size >= 2) {
                    val l = SoundcoreProtocol.scaleBat(b[0].toInt() and 0xFF)
                    val r = SoundcoreProtocol.scaleBat(b[1].toInt() and 0xFF)
                    val cur = _t300State.value
                    updateBatteryState(listOf(
                        T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, l, cur.leftBattery.isCharging),
                        T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, r, cur.rightBattery.isCharging)
                    ), isSnapshot = false)
                } else if (b.size == 1) {
                    val g = SoundcoreProtocol.scaleBat(b[0].toInt() and 0xFF)
                    val cur = _t300State.value
                    updateBatteryState(listOf(
                        T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, g, cur.leftBattery.isCharging),
                        T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, g, cur.rightBattery.isCharging)
                    ), isSnapshot = false)
                }
            }
            SoundcoreProtocol.CMD_BATTERY_CHARGING -> {
                val cur = _t300State.value
                if (b.size >= 2) {
                    updateBatteryState(listOf(
                        cur.leftBattery.copy(isCharging = b[0].toInt() == 1),
                        cur.rightBattery.copy(isCharging = b[1].toInt() == 1)
                    ), isSnapshot = false)
                } else if (b.size == 1) {
                    val c = b[0].toInt() == 1
                    updateBatteryState(listOf(cur.leftBattery.copy(isCharging = c), cur.rightBattery.copy(isCharging = c)), isSnapshot = false)
                }
            }
            SoundcoreProtocol.CMD_SOUND_UPDATE -> {
                if (b.size >= 4) {
                    soundcoreLastSound = b
                    val mode = when (b[0].toInt() and 0xFF) {
                        0 -> AncMode.NOISE_REDUCTION
                        1 -> AncMode.TRANSPARENCY
                        else -> AncMode.OFF
                    }
                    _t300State.value = _t300State.value.copy(ancMode = mode)
                }
            }
            SoundcoreProtocol.CMD_GAMING_UPDATE -> {
                if (b.isNotEmpty()) _t300State.value = _t300State.value.copy(gameMode = b[0].toInt() == 1)
            }
            SoundcoreProtocol.CMD_STATE -> {
                // Single-headphone (Q30/Q35/Q45) vs Dual-bud state prefix
                if (b.size >= 6) {
                    val isSingle = activeModel == T300Protocol.BudsModel.SOUNDCORE_LIFE_Q30 ||
                        activeModel == T300Protocol.BudsModel.SOUNDCORE_SPACE_Q45
                    if (isSingle) {
                        val lvl = SoundcoreProtocol.scaleBat(b[0].toInt() and 0xFF)
                        val chg = b[1].toInt() == 1
                        updateBatteryState(listOf(
                            T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, chg),
                            T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, chg)
                        ), isSnapshot = true)
                    } else {
                        val l = SoundcoreProtocol.scaleBat(b[2].toInt() and 0xFF)
                        val r = SoundcoreProtocol.scaleBat(b[3].toInt() and 0xFF)
                        val lc = b[4].toInt() == 1
                        val rc = b[5].toInt() == 1
                        updateBatteryState(listOf(
                            T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, l, lc),
                            T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, r, rc),
                            T300Protocol.BatteryInfo(ComponentRole.CASE, -1, false)
                        ), isSnapshot = true)
                    }
                }
            }
        }
    }

    /**
     * Xiaomi / Redmi Buds incoming packet handler (with SAFER+ Auth)
     */
    private fun handleIncomingXiaomiPacket(pkg: XiaomiProtocol.Packet) {
        val p = pkg.payload
        when (pkg.opcode) {
            XiaomiProtocol.OP_AUTH_CHALLENGE -> {
                if (pkg.isResponse) {
                    sendRawBytes(XiaomiProtocol.buildRequest(XiaomiProtocol.OP_AUTH_CONFIRM, byteArrayOf(0x01, 0x00)))
                } else if (p.size >= 17) {
                    val challenge = p.copyOfRange(1, 17)
                    val answer = XiaomiProtocol.challengeResponse(challenge)
                    val reply = ByteArray(17)
                    reply[0] = 0x01
                    System.arraycopy(answer, 0, reply, 1, 16)
                    sendRawBytes(XiaomiProtocol.buildResponse(XiaomiProtocol.OP_AUTH_CHALLENGE, pkg.seq, reply))
                }
            }
            XiaomiProtocol.OP_AUTH_CONFIRM -> {
                if (!pkg.isResponse) {
                    sendRawBytes(XiaomiProtocol.buildResponse(XiaomiProtocol.OP_AUTH_CONFIRM, pkg.seq, byteArrayOf(0x01)))
                    if (!xiaomiAuthenticated) {
                        xiaomiAuthenticated = true
                        log("Xiaomi auth confirmed; querying state...")
                        scope.launch {
                            sendRawBytes(XiaomiProtocol.buildDeviceInfoReq()); delay(80)
                            sendRawBytes(XiaomiProtocol.buildRunInfoReq()); delay(80)
                            sendRawBytes(XiaomiProtocol.buildLowLatencyReq()); delay(80)
                            sendRawBytes(XiaomiProtocol.buildGetConfigReq(XiaomiProtocol.CFG_EQ_PRESET)); delay(80)
                            sendRawBytes(XiaomiProtocol.buildCustomEqReq()); delay(80)
                            sendRawBytes(XiaomiProtocol.buildGetConfigReq(XiaomiProtocol.CFG_MULTIPOINT)); delay(80)
                            sendRawBytes(XiaomiProtocol.buildGetConfigReq(XiaomiProtocol.CFG_AUTO_ANSWER))
                        }
                    }
                }
            }
            XiaomiProtocol.OP_DEVICE_INFO -> {
                var i = 0
                while (i + 1 < p.size) {
                    val l = p[i].toInt() and 0xFF
                    if (l < 1) break
                    val idx = p[i + 1].toInt() and 0xFF
                    if (idx == 0x07 && l >= 4) {
                        val bats = XiaomiProtocol.parseBatteryTriplet(p, i + 2)
                        if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
                    }
                    i += l + 1
                }
            }
            XiaomiProtocol.OP_RUN_INFO -> {
                var i = 0
                while (i + 2 < p.size) {
                    val l = p[i].toInt() and 0xFF
                    if (l < 1) break
                    val idx = p[i + 1].toInt() and 0xFF
                    val v = p[i + 2].toInt() and 0xFF
                    if (idx == 0x09 && l >= 2 && v <= 2) {
                        val mode = when (v) { 1 -> AncMode.NOISE_REDUCTION; 2 -> AncMode.TRANSPARENCY; else -> AncMode.OFF }
                        _t300State.value = _t300State.value.copy(ancMode = mode)
                    } else if (idx == 0x0A && l >= 2) {
                        _t300State.value = _t300State.value.copy(autoPlayPause = v == 0)
                    }
                    i += l + 1
                }
            }
            XiaomiProtocol.OP_STATUS -> {
                if (!pkg.isResponse) {
                    var i = 0
                    while (i + 1 < p.size) {
                        val l = p[i].toInt() and 0xFF
                        if (l < 1) break
                        val idx = p[i + 1].toInt() and 0xFF
                        if (idx == 0x00 && l >= 4) {
                            val bats = XiaomiProtocol.parseBatteryTriplet(p, i + 2)
                            if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
                        } else if (idx == 0x04 && l >= 2 && i + 2 < p.size) {
                            val v = p[i + 2].toInt() and 0xFF
                            if (v <= 2) {
                                val mode = when (v) { 1 -> AncMode.NOISE_REDUCTION; 2 -> AncMode.TRANSPARENCY; else -> AncMode.OFF }
                                _t300State.value = _t300State.value.copy(ancMode = mode)
                            }
                        }
                        i += l + 1
                    }
                    sendRawBytes(XiaomiProtocol.buildResponse(XiaomiProtocol.OP_STATUS, pkg.seq))
                }
            }
            XiaomiProtocol.OP_GET_CONFIG,
            XiaomiProtocol.OP_NOTIFY_CONFIG -> {
                val records = XiaomiProtocol.parseConfigRecords(p)
                for ((key, data) in records) {
                    if (data.isEmpty()) continue
                    val v = data[0].toInt() and 0xFF
                    when (key) {
                        XiaomiProtocol.CFG_AUTO_ANSWER -> {
                            _t300State.value = _t300State.value.copy(autoAnswer = v != 0)
                        }
                        XiaomiProtocol.CFG_MULTIPOINT -> {
                            _t300State.value = _t300State.value.copy(dualConnection = v != 0)
                        }
                        XiaomiProtocol.CFG_LOW_LATENCY -> {
                            log("RX Xiaomi LowLatency (0x002F): ${if (v != 0) "ON" else "OFF"}")
                            _t300State.value = _t300State.value.copy(gameMode = v != 0)
                        }
                        XiaomiProtocol.CFG_EQ_PRESET -> {
                            val eq = when (v) {
                                0x00, 0x15 -> EqPreset.ORIGINAL_SOUND
                                0x01 -> EqPreset.SERENADE
                                0x06 -> EqPreset.PURE_BASS
                                0x05 -> EqPreset.DEEP_BASS
                                else -> null
                            }
                            val customName = when {
                                eq != null -> null
                                v == 0xFF || v == 0x07 -> "Custom"
                                else -> "EQ #$v"
                            }
                            _t300State.value = _t300State.value.copy(eqPreset = eq, activeCustomEqName = customName)
                        }
                        XiaomiProtocol.CFG_CUSTOM_EQ -> {
                            val parsed = XiaomiProtocol.parseCustomEqValue(data)
                            if (parsed != null) {
                                log("RX Xiaomi CustomEQ (0x0037): freqs=${parsed.first}, gains=${parsed.second}")
                                _t300State.value = _t300State.value.copy(
                                    customEqFreqs = parsed.first,
                                    customEqGains = parsed.second
                                )
                            }
                        }
                        XiaomiProtocol.CFG_EFFECT_STRENGTH -> if (data.size >= 2 && v == 1) {
                            val lvl = when (data[1].toInt() and 0xFF) {
                                1 -> HuaweiProtocol.AncLevel.COMFORT
                                0 -> HuaweiProtocol.AncLevel.GENERAL
                                2 -> HuaweiProtocol.AncLevel.ULTRA
                                3 -> HuaweiProtocol.AncLevel.DYNAMIC
                                else -> null
                            }
                            if (lvl != null) _t300State.value = _t300State.value.copy(ancLevel = lvl)
                        }
                    }
                }
                if (pkg.opcode == XiaomiProtocol.OP_NOTIFY_CONFIG && !pkg.isResponse) {
                    sendRawBytes(XiaomiProtocol.buildResponse(XiaomiProtocol.OP_NOTIFY_CONFIG, pkg.seq))
                }
            }
        }
    }

    /**
     * Sennheiser GAIA incoming packet handler
     */
    private fun handleIncomingSennheiserPacket(pkg: SennheiserProtocol.Packet) {
        if (pkg.vendor != SennheiserProtocol.VENDOR) return
        if (pkg.type != SennheiserProtocol.TYPE_RESPONSE && pkg.type != SennheiserProtocol.TYPE_NOTIFICATION) return
        val p = pkg.payload
        if (p.isEmpty()) return
        val v = p[0].toInt() and 0xFF
        when (pkg.baseCmd) {
            SennheiserProtocol.CMD_GET_BATTERY -> if (v <= 100) {
                val cur = _t300State.value
                updateBatteryState(listOf(
                    T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, v, cur.leftBattery.isCharging),
                    T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, v, cur.rightBattery.isCharging)
                ), isSnapshot = false)
            }
            SennheiserProtocol.CMD_GET_CHARGING -> {
                val chg = v != 0
                val cur = _t300State.value
                updateBatteryState(listOf(cur.leftBattery.copy(isCharging = chg), cur.rightBattery.copy(isCharging = chg)), isSnapshot = false)
            }
            SennheiserProtocol.CMD_GET_ANC_ENABLED -> {
                if (v != 0) _t300State.value = _t300State.value.copy(ancMode = AncMode.NOISE_REDUCTION)
                else if (_t300State.value.ancMode == AncMode.NOISE_REDUCTION) _t300State.value = _t300State.value.copy(ancMode = AncMode.OFF)
            }
            SennheiserProtocol.CMD_GET_TRANSPARENT -> {
                if (v != 0) _t300State.value = _t300State.value.copy(ancMode = AncMode.TRANSPARENCY)
                else if (_t300State.value.ancMode == AncMode.TRANSPARENCY) _t300State.value = _t300State.value.copy(ancMode = AncMode.OFF)
            }
            SennheiserProtocol.CMD_GET_AUTO_PAUSE -> _t300State.value = _t300State.value.copy(autoPlayPause = v != 0)
            SennheiserProtocol.CMD_GET_AUTO_ANSWER -> _t300State.value = _t300State.value.copy(autoAnswer = v != 0)
            SennheiserProtocol.CMD_GET_SPATIAL -> _t300State.value = _t300State.value.copy(spatialAudio = v != 0)
            SennheiserProtocol.CMD_GET_EQ_GAINS -> if (p.size >= 5) {
                val startOff = if (p.size >= 6) p.size - 5 else 0
                val raw = List(5) { idx -> p[startOff + idx].toInt() } // signed bytes
                val scaled = if (raw.any { kotlin.math.abs(it) > 6 }) raw.map { (it / 10).coerceIn(-6, 6) } else raw.map { it.coerceIn(-6, 6) }
                log("RX Sennheiser EQ: $scaled")
                _t300State.value = _t300State.value.copy(customEqGains = scaled)
            }
        }
    }

    /**
     * Jabra incoming packet handler
     */
    private fun handleIncomingJabraPacket(pkg: JabraProtocol.Packet) {
        val p = pkg.payload
        when (pkg.feature) {
            JabraProtocol.FEAT_BATTERY -> {
                val bats = JabraProtocol.parseBattery(p)
                if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = false)
            }
            JabraProtocol.FEAT_AMBIENCE -> {
                val raw = if (p.size == 1) (p[0].toInt() and 0xFF) else if (p.size >= 6) (p[3].toInt() and 0xFF) else -1
                val mode = when (raw) {
                    1 -> AncMode.OFF
                    2 -> AncMode.TRANSPARENCY
                    4 -> AncMode.NOISE_REDUCTION
                    else -> null
                }
                if (mode != null) _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            JabraProtocol.FEAT_NOISE_MODE -> if (p.isNotEmpty()) {
                val mode = when (p[0].toInt() and 0xFF) {
                    0 -> AncMode.OFF
                    1 -> AncMode.NOISE_REDUCTION
                    3 -> AncMode.TRANSPARENCY
                    else -> null
                }
                if (mode != null) _t300State.value = _t300State.value.copy(ancMode = mode)
            }
        }
    }

    /**
     * Google Pixel Buds Maestro HDLC/RPC incoming packet handler
     */
    private fun handleIncomingPixelBudsPacket(pkg: PixelBudsProtocol.RpcPacket) {
        if (pkg.method == PixelBudsProtocol.METHOD_GET_SW_INFO && pkg.channel > 0) {
            pixelActiveChannel = pkg.channel
            log("✓ Pixel Buds Maestro channel=$pixelActiveChannel")
            scope.launch {
                sendRawBytes(PixelBudsProtocol.buildRpcFrame(pixelActiveChannel, PixelBudsProtocol.METHOD_SUB_RUNTIME))
                delay(60)
                sendRawBytes(PixelBudsProtocol.buildRpcFrame(pixelActiveChannel, PixelBudsProtocol.METHOD_SUB_SETTINGS))
                delay(60)
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_ANC))
                delay(60)
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_OHD))
                delay(60)
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_MULTIPOINT))
                delay(60)
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_USER_EQ))
            }
            return
        }
        if (pkg.method == PixelBudsProtocol.METHOD_SUB_RUNTIME) {
            for (f in PixelBudsProtocol.readProtoFields(pkg.payload)) {
                if (f.num == 6 && f.wire == 2) {
                    val bats = mutableListOf<T300Protocol.BatteryInfo>()
                    for (part in PixelBudsProtocol.readProtoFields(f.bytes)) {
                        if (part.wire != 2) continue
                        var lvl = -1; var chg = false
                        for (v in PixelBudsProtocol.readProtoFields(part.bytes)) {
                            if (v.num == 1 && v.wire == 0) lvl = v.varint.toInt()
                            else if (v.num == 2 && v.wire == 0) chg = v.varint == 2L
                        }
                        if (lvl !in 0..100) lvl = -1
                        when (part.num) {
                            1 -> bats.add(T300Protocol.BatteryInfo(ComponentRole.CASE, lvl, chg))
                            2 -> bats.add(T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, chg))
                            3 -> bats.add(T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, chg))
                        }
                    }
                    if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
                }
            }
            return
        }
        if (pkg.method == PixelBudsProtocol.METHOD_READ_SETTING || pkg.method == PixelBudsProtocol.METHOD_SUB_SETTINGS) {
            for (f in PixelBudsProtocol.readProtoFields(pkg.payload)) {
                if (f.num == 4 && f.wire == 2) {
                    for (sv in PixelBudsProtocol.readProtoFields(f.bytes)) {
                        if (sv.num == PixelBudsProtocol.SETTING_USER_EQ && sv.wire == 2) {
                            val gains = MutableList(5) { 0 }
                            for (bf in PixelBudsProtocol.readProtoFields(sv.bytes)) {
                                if (bf.wire == 5 && bf.num in 1..5) {
                                    val flt = java.lang.Float.intBitsToFloat(bf.varint.toInt())
                                    gains[bf.num - 1] = flt.toInt().coerceIn(-6, 6)
                                }
                            }
                            log("RX PixelBuds EQ: $gains")
                            _t300State.value = _t300State.value.copy(customEqGains = gains)
                            continue
                        }
                        if (sv.wire != 0) continue
                        when (sv.num) {
                            PixelBudsProtocol.SETTING_OHD -> _t300State.value = _t300State.value.copy(autoPlayPause = sv.varint != 0L)
                            PixelBudsProtocol.SETTING_MULTIPOINT -> _t300State.value = _t300State.value.copy(dualConnection = sv.varint != 0L)
                            PixelBudsProtocol.SETTING_ANC -> {
                                val mode = when (sv.varint.toInt()) {
                                    1 -> AncMode.OFF
                                    2, 4 -> AncMode.NOISE_REDUCTION
                                    3 -> AncMode.TRANSPARENCY
                                    else -> null
                                }
                                if (mode != null) _t300State.value = _t300State.value.copy(ancMode = mode)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * JBL GATT incoming packet handler
     */
    private fun handleIncomingJblPacket(pkg: JblProtocol.Packet) {
        val p = pkg.payload
        when (pkg.command) {
            JblProtocol.CMD_BATTERY -> if (p.size >= 5 && p[0].toInt() == 0x01) {
                fun dec(v: Int, f: Int): Pair<Int, Boolean> {
                    val lvl = v and 0x7F
                    if (v == 0xFF || lvl > 100) return -1 to false
                    return lvl to (((v and 0x80) != 0) || f == 1)
                }
                val (l1, c1) = dec(p[3].toInt() and 0xFF, p[1].toInt() and 0xFF)
                val (l2, c2) = dec(p[4].toInt() and 0xFF, p[2].toInt() and 0xFF)
                val (lc, cc) = if (p.size >= 7) dec(p[6].toInt() and 0xFF, p[5].toInt() and 0xFF) else (-1 to false)
                updateBatteryState(listOf(
                    T300Protocol.BatteryInfo(ComponentRole.LEFT_EAR, l1, c1),
                    T300Protocol.BatteryInfo(ComponentRole.RIGHT_EAR, l2, c2),
                    T300Protocol.BatteryInfo(ComponentRole.CASE, lc, cc)
                ), isSnapshot = true)
            }
            JblProtocol.CMD_NOISE -> if (p.size >= 3 && (p[0].toInt() == 0x12 || p[0].toInt() == 0x10)) {
                var ancOn = false; var ambOn = false
                var i = 1
                while (i + 1 < p.size) {
                    val slot = p[i].toInt() and 0xFF
                    val on = (p[i + 1].toInt() and 0xFF) != 0
                    if (slot == 1) ancOn = on
                    if (slot == 2 || slot == 3) ambOn = ambOn || on
                    i += 2
                }
                val mode = when {
                    ancOn -> AncMode.NOISE_REDUCTION
                    ambOn -> AncMode.TRANSPARENCY
                    else -> AncMode.OFF
                }
                _t300State.value = _t300State.value.copy(ancMode = mode)
            }
            JblProtocol.CMD_STATUS_RET -> if (p.size >= 2) {
                when (p[0].toInt() and 0xFF) {
                    0x31 -> if ((p[1].toInt() and 0xFF) != 0) _t300State.value = _t300State.value.copy(ancMode = AncMode.NOISE_REDUCTION)
                    0x32 -> if ((p[1].toInt() and 0xFF) != 0) _t300State.value = _t300State.value.copy(ancMode = AncMode.TRANSPARENCY)
                    0x34 -> {
                        val code = p[1].toInt() and 0xFF
                        val eq = when (code) {
                            0 -> EqPreset.ORIGINAL_SOUND
                            2 -> EqPreset.SERENADE
                            7 -> EqPreset.PURE_BASS
                            3 -> EqPreset.DEEP_BASS
                            else -> null
                        }
                        _t300State.value = _t300State.value.copy(eqPreset = eq, activeCustomEqName = if (eq == null) "EQ #$code" else null)
                    }
                    0x38 -> _t300State.value = _t300State.value.copy(autoPlayPause = (p[1].toInt() and 0xFF) != 0)
                }
            }
        }
    }
}
