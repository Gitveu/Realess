package uwu.realness.gitveu.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import uwu.realness.gitveu.audio.FindBudsChannel
import uwu.realness.gitveu.protocol.AirPodsBeaconProtocol
import uwu.realness.gitveu.protocol.AirohaRaceProtocol
import uwu.realness.gitveu.protocol.BaseusProtocol
import uwu.realness.gitveu.protocol.BoseProtocol
import uwu.realness.gitveu.protocol.EdifierProtocol
import uwu.realness.gitveu.protocol.GalaxyProtocol
import uwu.realness.gitveu.protocol.HuilianProtocol
import uwu.realness.gitveu.protocol.HuaweiProtocol
import uwu.realness.gitveu.protocol.JabraProtocol
import uwu.realness.gitveu.protocol.JblProtocol
import uwu.realness.gitveu.protocol.JieliRcspProtocol
import uwu.realness.gitveu.protocol.MoondropProtocol
import uwu.realness.gitveu.protocol.NothingProtocol
import uwu.realness.gitveu.protocol.PixelBudsProtocol
import uwu.realness.gitveu.protocol.QcyProtocol
import uwu.realness.gitveu.protocol.SennheiserProtocol
import uwu.realness.gitveu.protocol.SonyProtocol
import uwu.realness.gitveu.protocol.SoundPeatsProtocol
import uwu.realness.gitveu.protocol.SoundcoreProtocol
import uwu.realness.gitveu.protocol.BudsProtocol
import uwu.realness.gitveu.protocol.XiaomiProtocol
import java.util.concurrent.atomic.AtomicInteger
import uwu.realness.gitveu.protocol.BudsProtocol.AncMode
import uwu.realness.gitveu.protocol.BudsProtocol.ComponentRole
import uwu.realness.gitveu.protocol.BudsProtocol.CustomEqProfile
import uwu.realness.gitveu.protocol.BudsProtocol.EqPreset
import uwu.realness.gitveu.protocol.BudsProtocol.TouchSide
import uwu.realness.gitveu.protocol.BudsProtocol.TouchGesture
import uwu.realness.gitveu.protocol.BudsProtocol.TouchAction
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
    BLE_GATT,
    BLE_BEACON,
    BT_BATTERY_ONLY
}

@SuppressLint("MissingPermission")
class BudsConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BudsConnectionManager"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val BACKGROUND_POLL_INTERVAL_MS = 60_000L
        private const val EVENT_REFRESH_DELAY_MS = 120L
        private const val SPP_CONNECT_TIMEOUT_MS = 3_500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    @Volatile private var isForegroundMode: Boolean = false

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
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null
    private var activeBtDevice: BluetoothDevice? = null

    private var activeTransport = ActiveTransport.NONE
    private var activeModel = BudsProtocol.BudsModel.UNKNOWN
    private var activeDeviceAddress: String? = null
    private val huaweiMultiDevices = mutableMapOf<Int, BudsProtocol.ConnectedDeviceInfo>()
    private var galaxyManagerInfoSent = false
    private var nothingLastBassLevel = 3
    private var sonySeq = 0
    private var soundcoreLastSound: ByteArray? = null
    private var xiaomiAuthenticated = false
    private var pixelActiveChannel = 18
    private var pixelProbeIndex = 0
    private var jblAcc = ByteArray(0)
    private var jblGattRetryAttempt = 0
    private var isBatteryOnlyGattProbe = false
    private var baseusUseCrc = false
    private var qcyBatteryCharacteristic: BluetoothGattCharacteristic? = null
    private var airPodsScanCallback: ScanCallback? = null
    private var airPodsBestRssi: Int = Int.MIN_VALUE
    private var airPodsBestAddr: String? = null
    private var airPodsMatchedExactMac: Boolean = false
    private var lastBeaconLogMs: Long = 0L
    private val lastAirPodsBeaconByMac = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AirPodsBeaconProtocol.BeaconTelemetry>>()
    private var airPodsScanStopJob: Job? = null
    private var promotedReplicaFamily: BudsProtocol.ProtocolFamily? = null
    private var isReplicaGattProbe: Boolean = false
    private var replicaProbeJob: Job? = null
    private var jieliSeq: Int = 1
    private var jieliAuthDone: Boolean = false
    private var jieliHostChallenge: ByteArray? = null
    private val jieliVoiceDetails = mutableMapOf<Int, ByteArray>()
    private var jieliDynamicEq: Boolean = true

    private fun nextJieliSeq(): Int {
        val s = jieliSeq
        jieliSeq = if (jieliSeq >= 255) 1 else jieliSeq + 1
        return s
    }

    private val effectiveFamily: BudsProtocol.ProtocolFamily
        get() = promotedReplicaFamily ?: activeModel.protocolFamily

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _budsState = MutableStateFlow(BudsProtocol.BudsState())
    val budsState: StateFlow<BudsProtocol.BudsState> = _budsState.asStateFlow()

    private val _logFlow = MutableSharedFlow<String>(replay = 50)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private val _unexpectedDisconnectFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val unexpectedDisconnectFlow: SharedFlow<Unit> = _unexpectedDisconnectFlow.asSharedFlow()

    fun isConnectedOrConnectingTo(address: String?, model: BudsProtocol.BudsModel): Boolean {
        if (address == null) return false
        return activeDeviceAddress == address &&
            activeModel == model &&
            _connectionState.value != BleConnectionState.DISCONNECTED
    }

    val activeDevice: BluetoothDevice? get() = activeBtDevice
    val currentModel: BudsProtocol.BudsModel get() = activeModel

    fun setForegroundMode(foreground: Boolean) {
        if (isForegroundMode == foreground) return
        isForegroundMode = foreground
        val gen = sessionGen.get()
        if (_connectionState.value == BleConnectionState.CONNECTED) {
            if (activeTransport == ActiveTransport.BLE_BEACON) {
                if (foreground) {
                    startAirPodsBeaconScan(gen)
                } else {
                    airPodsScanStopJob?.cancel()
                    airPodsScanStopJob = scope.launch {
                        delay(6_000L)
                        if (!isForegroundMode && gen == sessionGen.get()) {
                            stopAirPodsBeaconScan()
                        }
                    }
                }
                return
            }
            if (foreground) {
                scope.launch {
                    refreshBattery()
                    if (activeModel.supportsAnc && activeTransport != ActiveTransport.BT_BATTERY_ONLY) {
                        delay(60)
                        refreshAncState()
                    }
                }
            }
            startPolling(gen)
        }
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
    fun connect(device: BluetoothDevice, overrideModel: BudsProtocol.BudsModel? = null) {
        val targetModel = overrideModel ?: BudsProtocol.BudsModel.fromDeviceName(device.name)
        if (isConnectedOrConnectingTo(device.address, targetModel)) {
            return
        }

        val gen = sessionGen.incrementAndGet()
        closeResourcesOnly()
        activeModel = targetModel
        activeDeviceAddress = device.address
        activeBtDevice = device
        _connectionState.value = BleConnectionState.CONNECTING
        val name = device.name ?: "Headset"
        log("Starting connection to $name (${device.address}, model=$activeModel, proto=${activeModel.protocolFamily})...")

        connectJob = scope.launch {
            if (gen != sessionGen.get()) return@launch

            // Apple AirPods / Beats: start passive BLE beacon immediately, then silently probe Airoha RACE & Huilian StarFun!
            if (activeModel.isAirPods) {
                enterAirPodsBeaconMode(device, gen, runAutoProbe = true)
                return@launch
            }

            // Explicit Huilian StarFun replica (SPP ch 3 / 0x00001101 -> fallback to BLE beacon)
            if (activeModel.isHuilianReplica) {
                val ok = tryConnectHuilianSpp(device, gen)
                if (!ok && gen == sessionGen.get()) {
                    log("Huilian StarFun SPP unavailable -> falling back to AirPods Replica BLE beacon")
                    enterAirPodsBeaconMode(device, gen, runAutoProbe = false)
                }
                return@launch
            }

            // Explicit JieLi (JL_SPP) RCSP replica (FE010000 / 0x00001101 SPP -> AE00 GATT -> BLE beacon)
            if (activeModel.isJieliReplica) {
                val ok = tryConnectJieliSpp(device, gen, silentProbe = false)
                if (!ok && gen == sessionGen.get()) {
                    log("JieLi JL_SPP unavailable -> attempting JieLi BLE GATT (0xAE00)...")
                    jblGattRetryAttempt = 0
                    connectGatt(device, gen, transport = BluetoothDevice.TRANSPORT_LE, isBatteryOnlyProbe = false)
                }
                return@launch
            }

            // Logitech G435 / G733 or Standard Bluetooth (HFP + BLE Battery Service 0x180F/0x2A19)
            if (activeModel.isLogitech || activeModel.isGenericBluetooth) {
                enterStandardBluetoothBatteryMode(
                    device = device,
                    gen = gen,
                    isFallback = activeModel.isGenericBluetooth,
                    tryBleBas = true
                )
                return@launch
            }

            // BLE GATT-primary protocols (JBL, QCY, Baseus, Moondrop) with automatic fallback to HFP battery
            if (activeModel.isJbl || activeModel.isQcy || activeModel.isBaseus || activeModel.isMoondrop) {
                jblGattRetryAttempt = 0
                baseusUseCrc = activeModel == BudsProtocol.BudsModel.BASEUS_BP1_ULTRA
                log("Connecting ${activeModel.protocolFamily} via BLE GATT (TRANSPORT_LE)...")
                connectGatt(device, gen, transport = BluetoothDevice.TRANSPORT_LE, isBatteryOnlyProbe = false)
                return@launch
            }

            // Attempt 1: RFCOMM SPP
            val sppConnected = tryConnectSpp(device, gen)
            if (sppConnected || gen != sessionGen.get()) {
                return@launch
            }

            if (!activeModel.isRealme && !activeModel.isEdifier && !activeModel.isSoundPeats && !activeModel.isAirohaReplica) {
                log("${activeModel.protocolFamily} SPP socket unavailable")
                if (gen == sessionGen.get()) {
                    disconnectInternal(emitUnexpected = true)
                }
                return@launch
            }

            // Attempt 2: BLE GATT Fallback (realme / OnePlus / OPPO / Edifier / SoundPEATS / Airoha RACE)
            log("SPP sockets unavailable. Falling back to BLE GATT...")
            jblGattRetryAttempt = 0
            connectGatt(device, gen)
        }
    }

    /**
     * Connects (or switches) directly into Standard Bluetooth Battery Mode (HFP getBatteryLevel + optional BLE BAS 0x180F).
     * Used for generic Bluetooth audio devices and as an automatic fallback when vendor GATT/SPP is unavailable (e.g. JBL WAVE200TWS).
     */
    fun connectStandardBluetoothFallback(device: BluetoothDevice, model: BudsProtocol.BudsModel) {
        val gen = sessionGen.incrementAndGet()
        closeResourcesOnly()
        activeModel = model
        activeDeviceAddress = device.address
        activeBtDevice = device
        connectJob = scope.launch {
            enterStandardBluetoothBatteryMode(device, gen, isFallback = true, tryBleBas = false)
        }
    }

    private fun enterAirPodsBeaconMode(device: BluetoothDevice, gen: Int, runAutoProbe: Boolean = false) {
        if (gen != sessionGen.get()) return
        activeTransport = ActiveTransport.BLE_BEACON
        activeBtDevice = device
        airPodsBestRssi = Int.MIN_VALUE
        airPodsBestAddr = null
        airPodsMatchedExactMac = false
        lastBeaconLogMs = 0L

        // Instant zero-radio SDP UUID fingerprint check:
        // Genuine Apple AirPods NEVER advertise JieLi Vendor SPP (FE010000) or Standard SPP (0x1101)
        val cachedUuids = try { device.uuids?.map { it.uuid } ?: emptyList() } catch (_: Throwable) { emptyList() }
        val hasJieliVendorSpp = cachedUuids.any { it == BluetoothConfig.JIELI_VENDOR_SPP_UUID }
        val hasStandardSpp = cachedUuids.any { it == BluetoothConfig.STANDARD_SPP_UUID }
        val sdpIndicatesReplica = hasJieliVendorSpp || hasStandardSpp

        val cachedBeacon = lastAirPodsBeaconByMac[device.address]?.let { (ts, tel) ->
            if (System.currentTimeMillis() - ts <= 10 * 60_000L) tel else null
        }

        _budsState.value = BudsProtocol.BudsState(
            leftBattery = cachedBeacon?.let { BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, it.leftLevel, it.leftCharging) }
                ?: BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, -1, false),
            rightBattery = cachedBeacon?.let { BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, it.rightLevel, it.rightCharging) }
                ?: BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, -1, false),
            caseBattery = cachedBeacon?.let { BudsProtocol.BatteryInfo(ComponentRole.CASE, it.caseLevel, it.caseCharging) }
                ?: BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false),
            isPassiveBeaconMode = true,
            caseLidOpen = cachedBeacon?.lidOpen,
            beaconModelName = cachedBeacon?.modelName,
            isLikelyReplica = sdpIndicatesReplica || cachedBeacon?.isLikelyReplica == true || activeModel.brand == BudsProtocol.Brand.AIRPODS_CLONE,
            replicaChipInfo = if (hasJieliVendorSpp) "JieLi (JL_SPP)" else null
        )
        _connectionState.value = BleConnectionState.CONNECTED
        log("AirPods / Beats passive BLE beacon listener active (Company ID 0x004C)")
        if (hasJieliVendorSpp) {
            log("SDP fingerprint: JieLi Vendor SPP (FE010000) detected -> AirPods Replica!")
        } else if (hasStandardSpp) {
            log("SDP fingerprint: Standard SPP (0x1101) detected -> AirPods Replica!")
        }
        // Seed single-battery fallback from HFP until BLE proximity frame arrives
        if (cachedBeacon == null || (cachedBeacon.leftLevel < 0 && cachedBeacon.rightLevel < 0)) {
            pollStandardBluetoothBattery(device)
        }
        startAirPodsBeaconScan(gen)

        // For any connected AirPods*, silently probe JieLi RCSP -> Airoha RACE (GATT/SPP) -> Huilian StarFun (C0)
        // so clones with standard "AirPods (...)" names auto-unlock ANC/Game Mode without manual picker!
        if (runAutoProbe && (activeModel.brand == BudsProtocol.Brand.APPLE || activeModel.brand == BudsProtocol.Brand.AIRPODS_CLONE)) {
            startSilentReplicaAutoProbe(device, gen, hasJieliVendorSpp, hasStandardSpp)
        }
    }

    private fun startSilentReplicaAutoProbe(
        device: BluetoothDevice,
        gen: Int,
        hasJieliVendorSpp: Boolean = false,
        hasStandardSpp: Boolean = false
    ) {
        replicaProbeJob?.cancel()
        replicaProbeJob = scope.launch {
            delay(350)
            if (gen != sessionGen.get() || promotedReplicaFamily != null) return@launch

            // Step 1: If SDP exposes JieLi Vendor SPP (FE010000) or Standard SPP, silently probe JieLi RCSP first!
            if (hasJieliVendorSpp || hasStandardSpp) {
                log("AirPods auto-probe: checking JieLi RCSP (JL_SPP)...")
                if (tryConnectJieliSpp(device, gen, silentProbe = true)) return@launch
                if (gen != sessionGen.get() || promotedReplicaFamily != null) return@launch
            }

            // Step 2: Probe BLE GATT for Airoha RACE (AIROHA_SERVICE) or JieLi RCSP (0xAE00)
            log("AirPods auto-probe: checking Airoha RACE / JieLi GATT...")
            isReplicaGattProbe = true
            try {
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
            } catch (_: Throwable) {
                isReplicaGattProbe = false
            }

            val deadline = System.currentTimeMillis() + 3_200L
            while (System.currentTimeMillis() < deadline &&
                gen == sessionGen.get() &&
                isReplicaGattProbe &&
                promotedReplicaFamily == null
            ) {
                delay(120)
            }
            if (gen != sessionGen.get() || promotedReplicaFamily != null) return@launch

            if (isReplicaGattProbe) {
                isReplicaGattProbe = false
                bluetoothGatt?.let { g ->
                    try { g.disconnect(); g.close() } catch (_: Exception) {}
                }
                bluetoothGatt = null
            }

            // Step 3: Silently probe Airoha RACE SPP -> Huilian StarFun C0 (RFCOMM ch 3 / 0x1101)
            log("AirPods auto-probe: checking Airoha SPP & Huilian StarFun (C0)...")
            if (tryProbeAirohaSppSilent(device, gen)) return@launch
            if (gen != sessionGen.get() || promotedReplicaFamily != null) return@launch
            tryConnectHuilianSpp(device, gen, silentProbe = true)
        }
    }

    private suspend fun tryConnectJieliSpp(
        device: BluetoothDevice,
        gen: Int,
        silentProbe: Boolean
    ): Boolean {
        val candidateUuids = BluetoothConfig.JIELI_SPP_UUIDS
        for (uuid in candidateUuids) {
            if (gen != sessionGen.get()) return false
            val label = if (uuid == BluetoothConfig.JIELI_VENDOR_SPP_UUID) "JieLi Vendor SPP (FE010000)" else "Standard SPP (JL_SPP)"
            if (!silentProbe) log("Attempting SPP ($label)...")
            val sock = openRfcommSocketWithTimeout(device, uuid, 2_200L) ?: continue
            if (gen != sessionGen.get()) {
                try { sock.close() } catch (_: Exception) {}
                return false
            }

            if (silentProbe) {
                // Send JieLi RCSP auth reset + challenge + direct GetADVInfo (0xC1) to verify real JieLi RCSP response
                val confirmed = try {
                    val out = sock.outputStream
                    val inp = sock.inputStream
                    jieliHostChallenge = JieliRcspProtocol.buildAuthChallenge()
                    jieliAuthDone = false
                    out.write(JieliRcspProtocol.AUTH_RESET)
                    out.flush()
                    delay(180)
                    out.write(jieliHostChallenge!!)
                    out.flush()
                    delay(220)
                    out.write(JieliRcspProtocol.buildGetAdvInfo(nextJieliSeq()))
                    out.flush()
                    val probeDeadline = System.currentTimeMillis() + 1_300L
                    val buf = ByteArray(256)
                    var acc = ByteArray(0)
                    var gotJieli = false
                    while (System.currentTimeMillis() < probeDeadline && gen == sessionGen.get()) {
                        val avail = try { inp.available() } catch (_: Exception) { 0 }
                        if (avail > 0) {
                            val n = inp.read(buf, 0, minOf(avail, buf.size))
                            if (n > 0) {
                                val slice = buf.copyOfRange(0, n)
                                log("RX JieLi probe [$label]: ${slice.joinToString(" ") { "%02X".format(it) }}")
                                acc += slice
                                val (items, rem) = JieliRcspProtocol.parseStream(acc, acceptRawAuth = true)
                                acc = rem
                                if (items.isNotEmpty()) {
                                    gotJieli = true
                                    bluetoothSocket = sock
                                    socketInputStream = inp
                                    socketOutputStream = out
                                    promotedReplicaFamily = BudsProtocol.ProtocolFamily.JIELI_RCSP
                                    activeTransport = ActiveTransport.RFCOMM_SPP
                                    _connectionState.value = BleConnectionState.CONNECTED
                                    log("✓ Auto-detected JieLi RCSP replica via $label!")
                                    for (item in items) handleIncomingJieliItem(item)
                                    break
                                }
                            }
                        }
                        delay(70)
                    }
                    gotJieli
                } catch (_: Throwable) {
                    false
                }
                if (!confirmed) {
                    try { sock.close() } catch (_: Exception) {}
                    continue
                }
                startSocketReader(gen)
                if (jieliAuthDone) {
                    requestJieliFullState()
                }
                startPolling(gen)
                return true
            } else {
                bluetoothSocket = sock
                socketInputStream = sock.inputStream
                socketOutputStream = sock.outputStream
                activeTransport = ActiveTransport.RFCOMM_SPP
                _connectionState.value = BleConnectionState.CONNECTED
                log("✓ SPP connected successfully via $label!")
                startSocketReader(gen)
                finishConnectionReady(gen)
                return true
            }
        }
        return false
    }

    private suspend fun tryProbeAirohaSppSilent(device: BluetoothDevice, gen: Int): Boolean {
        val sock = openRfcommSocketWithTimeout(device, BluetoothConfig.AIROHA_RACE_SPP_UUID, 1_800L)
            ?: return false
        if (gen != sessionGen.get()) {
            try { sock.close() } catch (_: Exception) {}
            return false
        }
        bluetoothSocket = sock
        socketInputStream = sock.inputStream
        socketOutputStream = sock.outputStream
        promotedReplicaFamily = BudsProtocol.ProtocolFamily.AIROHA_RACE
        activeTransport = ActiveTransport.RFCOMM_SPP
        log("✓ Auto-detected Airoha RACE replica via SPP!")
        startSocketReader(gen)
        finishConnectionReady(gen)
        return true
    }

    private suspend fun tryConnectHuilianSpp(
        device: BluetoothDevice,
        gen: Int,
        silentProbe: Boolean = false
    ): Boolean {
        // Try standard SPP UUID first, then RFCOMM channel 3 via reflection (OpenHuilian StarFun channel)
        val sock = openRfcommSocketWithTimeout(device, BluetoothConfig.STANDARD_SPP_UUID, 2_000L)
            ?: openRfcommChannelWithTimeout(device, 3, 2_000L)
            ?: return false

        if (gen != sessionGen.get()) {
            try { sock.close() } catch (_: Exception) {}
            return false
        }

        if (silentProbe) {
            // Verify the peer actually speaks Huilian C0 before promoting (avoids false positives on standard SPP)
            val confirmed = try {
                val out = sock.outputStream
                val inp = sock.inputStream
                out.write(HuilianProtocol.buildModelReq())
                out.flush()
                delay(120)
                out.write(HuilianProtocol.buildBatteryReq())
                out.flush()
                val probeDeadline = System.currentTimeMillis() + 1_100L
                val buf = ByteArray(128)
                var gotC0 = false
                while (System.currentTimeMillis() < probeDeadline && gen == sessionGen.get()) {
                    val avail = try { inp.available() } catch (_: Exception) { 0 }
                    if (avail > 0) {
                        val n = inp.read(buf, 0, minOf(avail, buf.size))
                        if (n > 0) {
                            val slice = buf.copyOfRange(0, n)
                            val (pkts, _) = HuilianProtocol.parsePacketsFromStreamBuffer(slice)
                            if (pkts.isNotEmpty()) {
                                gotC0 = true
                                for (pkt in pkts) handleIncomingHuilianPacket(pkt)
                                break
                            }
                        }
                    }
                    delay(80)
                }
                gotC0
            } catch (_: Throwable) {
                false
            }
            if (!confirmed) {
                try { sock.close() } catch (_: Exception) {}
                return false
            }
            promotedReplicaFamily = BudsProtocol.ProtocolFamily.HUILIAN_SPP
            log("✓ Auto-detected Huilian StarFun replica via SPP (C0)!")
        }

        bluetoothSocket = sock
        socketInputStream = sock.inputStream
        socketOutputStream = sock.outputStream
        activeTransport = ActiveTransport.RFCOMM_SPP
        _connectionState.value = BleConnectionState.CONNECTED
        startSocketReader(gen)
        finishConnectionReady(gen)
        return true
    }

    private suspend fun openRfcommSocketWithTimeout(
        device: BluetoothDevice,
        uuid: UUID,
        timeoutMs: Long
    ): BluetoothSocket? {
        var socket: BluetoothSocket? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val s = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    socket = s
                    s.connect()
                    s
                }
            } ?: run {
                try { socket?.close() } catch (_: Exception) {}
                null
            }
        } catch (_: Throwable) {
            try { socket?.close() } catch (_: Exception) {}
            null
        }
    }

    private suspend fun openRfcommChannelWithTimeout(
        device: BluetoothDevice,
        channel: Int,
        timeoutMs: Long
    ): BluetoothSocket? {
        var socket: BluetoothSocket? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    val s = m.invoke(device, channel) as? BluetoothSocket ?: return@withContext null
                    socket = s
                    s.connect()
                    s
                }
            } ?: run {
                try { socket?.close() } catch (_: Exception) {}
                null
            }
        } catch (_: Throwable) {
            try { socket?.close() } catch (_: Exception) {}
            null
        }
    }

    private fun startAirPodsBeaconScan(gen: Int) {
        stopAirPodsBeaconScan()
        val scanner: BluetoothLeScanner = try {
            bluetoothAdapter?.bluetoothLeScanner
        } catch (_: Throwable) {
            null
        } ?: run {
            log("BLE scanner unavailable for AirPods beacon; using HFP battery only")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (gen != sessionGen.get()) return
                if (activeTransport != ActiveTransport.BLE_BEACON && promotedReplicaFamily == null && !activeModel.isAirohaReplica && !activeModel.isHuilianReplica && !activeModel.isJieliReplica) return
                val mfg = result.scanRecord?.getManufacturerSpecificData(AirPodsBeaconProtocol.APPLE_COMPANY_ID)
                    ?: return
                val addr = result.device?.address
                val rssi = result.rssi
                val exactMacMatch = !addr.isNullOrBlank() && addr.equals(activeDeviceAddress, ignoreCase = true)

                // Check clone secondary/pairing frame (07 0F 00 <model> <6B MAC>) — confirms replica without corrupting battery
                val cloneMacModel = AirPodsBeaconProtocol.parseCloneMacFrame(mfg, activeDeviceAddress)
                if (cloneMacModel != null) {
                    if (exactMacMatch) airPodsMatchedExactMac = true
                    val cur = _budsState.value
                    if (!cur.isLikelyReplica || cur.beaconModelName == null) {
                        log("RX AirPods Replica BLE MAC frame [$cloneMacModel, addr=$addr, rssi=$rssi]")
                    }
                    _budsState.value = cur.copy(
                        beaconModelName = cur.beaconModelName ?: cloneMacModel,
                        isLikelyReplica = true
                    )
                    return
                }

                val tel = AirPodsBeaconProtocol.parseManufacturerData(mfg, exactMacMatch = exactMacMatch) ?: return

                // Reject distant neighbor AirPods beacons (e.g. -95 dBm in another room) unless MAC matches active headset
                if (!exactMacMatch && (airPodsMatchedExactMac || rssi < -85)) {
                    return
                }
                if (exactMacMatch) {
                    airPodsMatchedExactMac = true
                    airPodsBestAddr = addr
                    airPodsBestRssi = rssi
                } else {
                    if (airPodsBestAddr != null && addr != airPodsBestAddr && rssi < airPodsBestRssi - 8) {
                        return
                    }
                    airPodsBestAddr = addr
                    if (rssi > airPodsBestRssi) airPodsBestRssi = rssi
                }

                activeDeviceAddress?.let { mac ->
                    lastAirPodsBeaconByMac[mac] = System.currentTimeMillis() to tel
                }

                val now = System.currentTimeMillis()
                val cur = _budsState.value
                if (now - lastBeaconLogMs >= 5_000L || cur.leftBattery.level != tel.leftLevel || cur.caseBattery.level != tel.caseLevel) {
                    lastBeaconLogMs = now
                    log("RX AirPods BLE Beacon [${tel.modelName}, L=${tel.leftLevel}%, R=${tel.rightLevel}%, C=${tel.caseLevel}%, lid=${if (tel.lidOpen) "open" else "closed"}, rssi=$rssi, replica=${tel.isLikelyReplica}]")
                }

                val inActiveReplica = promotedReplicaFamily != null || activeModel.isAirohaReplica || activeModel.isHuilianReplica || activeModel.isJieliReplica
                _budsState.value = cur.copy(
                    leftBattery = if (inActiveReplica && cur.leftBattery.level in 0..100) cur.leftBattery else BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, tel.leftLevel, tel.leftCharging),
                    rightBattery = if (inActiveReplica && cur.rightBattery.level in 0..100) cur.rightBattery else BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, tel.rightLevel, tel.rightCharging),
                    caseBattery = if (tel.caseLevel in 0..100) BudsProtocol.BatteryInfo(ComponentRole.CASE, tel.caseLevel, tel.caseCharging) else cur.caseBattery,
                    isPassiveBeaconMode = !inActiveReplica,
                    caseLidOpen = tel.lidOpen,
                    beaconModelName = tel.modelName,
                    isLikelyReplica = cur.isLikelyReplica || tel.isLikelyReplica || activeModel.brand == BudsProtocol.Brand.AIRPODS_CLONE
                )
            }
        }
        airPodsScanCallback = callback

        try {
            val filter = ScanFilter.Builder()
                .setManufacturerData(
                    AirPodsBeaconProtocol.APPLE_COMPANY_ID,
                    byteArrayOf(AirPodsBeaconProtocol.PROXIMITY_TYPE),
                    byteArrayOf(0xFF.toByte())
                )
                .build()
            val settingsBuilder = ScanSettings.Builder()
                .setScanMode(
                    if (isForegroundMode) ScanSettings.SCAN_MODE_LOW_LATENCY
                    else ScanSettings.SCAN_MODE_LOW_POWER
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settingsBuilder.setLegacy(false)
            }
            val settings = settingsBuilder.build()
            scanner.startScan(listOf(filter), settings, callback)
            // When in background (widget/popup sync), stop BLE scan automatically after 6s to save battery
            if (!isForegroundMode) {
                airPodsScanStopJob?.cancel()
                airPodsScanStopJob = scope.launch {
                    delay(6_000L)
                    if (!isForegroundMode && gen == sessionGen.get()) {
                        stopAirPodsBeaconScan()
                    }
                }
            }
        } catch (t: Throwable) {
            log("AirPods BLE scan start error: ${t.message}")
            airPodsScanCallback = null
        }
    }

    private fun stopAirPodsBeaconScan() {
        airPodsScanStopJob?.cancel()
        airPodsScanStopJob = null
        val cb = airPodsScanCallback ?: return
        airPodsScanCallback = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {}
    }

    private fun enterStandardBluetoothBatteryMode(
        device: BluetoothDevice,
        gen: Int,
        isFallback: Boolean,
        tryBleBas: Boolean
    ) {
        if (gen != sessionGen.get()) return
        // Close any failed vendor GATT cleanly before switching to HFP battery monitoring
        bluetoothGatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (_: Exception) {}
        }
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        batteryLevelCharacteristic = null

        activeTransport = ActiveTransport.BT_BATTERY_ONLY
        activeBtDevice = device
        _budsState.value = BudsProtocol.BudsState(isStandardBluetoothFallback = isFallback)
        _connectionState.value = BleConnectionState.CONNECTED
        log("Standard Bluetooth battery mode active (HFP${if (tryBleBas) " + BLE BAS 0x180F" else ""})")
        pollStandardBluetoothBattery(device)
        if (tryBleBas) {
            connectGatt(device, gen, transport = BluetoothDevice.TRANSPORT_AUTO, isBatteryOnlyProbe = true)
        }
        startPolling(gen)
    }

    fun updateHfpBatteryFromBroadcast(address: String, level: Int) {
        if (level !in 0..100) return
        if (activeTransport != ActiveTransport.BT_BATTERY_ONLY) return
        if (!address.equals(activeDeviceAddress, ignoreCase = true)) return
        val hours = if (activeModel == BudsProtocol.BudsModel.LOGITECH_G435) (level / 100f) * 18.0f else null
        log("RX BT HFP Battery broadcast: $level%")
        updateBatteryState(
            listOf(
                BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, level, false),
                BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, level, false),
                BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false)
            ),
            isSnapshot = true
        )
        if (hours != null) {
            _budsState.value = _budsState.value.copy(batteryHoursLeft = hours)
        }
    }

    /**
     * Attempt connection via RFCOMM BluetoothSocket
     */
    private suspend fun tryConnectSpp(device: BluetoothDevice, gen: Int): Boolean {
        bluetoothAdapter?.cancelDiscovery()

        val candidateUuids = when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                if (activeModel.prefersHuaweiPrivateUuid) BluetoothConfig.HUAWEI_SPP_UUIDS_PORT16_FIRST
                else BluetoothConfig.HUAWEI_SPP_UUIDS_PORT1_FIRST
            }
            BudsProtocol.ProtocolFamily.HONOR_CHOICE_SPP -> BluetoothConfig.HUAWEI_SPP_UUIDS_PORT1_FIRST
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> BluetoothConfig.NOTHING_SPP_UUIDS
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> when (activeModel) {
                BudsProtocol.BudsModel.GALAXY_BUDS -> BluetoothConfig.GALAXY_LEGACY_SPP_UUIDS
                BudsProtocol.BudsModel.GALAXY_BUDS_PLUS -> BluetoothConfig.GALAXY_PLUS_SPP_UUIDS
                else -> BluetoothConfig.GALAXY_MODERN_SPP_UUIDS
            }
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> BluetoothConfig.SONY_SPP_UUIDS
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> BluetoothConfig.XIAOMI_SPP_UUIDS
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> BluetoothConfig.SENNHEISER_SPP_UUIDS
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> BluetoothConfig.PIXEL_BUDS_SPP_UUIDS
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP,
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP,
            BudsProtocol.ProtocolFamily.JABRA_SPP,
            BudsProtocol.ProtocolFamily.EDIFIER_SPP,
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> BluetoothConfig.STANDARD_SPP_ONLY_UUIDS
            BudsProtocol.ProtocolFamily.AIROHA_RACE -> BluetoothConfig.AIROHA_RACE_SPP_UUIDS
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> BluetoothConfig.JIELI_SPP_UUIDS
            else -> {
                if (activeModel.isOppoOrOnePlus) BluetoothConfig.OPPO_ONEPLUS_SPP_UUIDS
                else BluetoothConfig.KNOWN_SPP_UUIDS
            }
        }

        for (uuid in candidateUuids) {
            if (gen != sessionGen.get()) return false
            val uuidLabel = when (uuid) {
                BluetoothConfig.REALME_VENDOR_SPP_UUID -> "Realme Vendor SPP"
                BluetoothConfig.OPPO_ONEPLUS_SPP_UUID -> "OPPO/OnePlus SPP"
                BluetoothConfig.HUAWEI_VENDOR_SPP_UUID -> "Huawei Private SPP"
                BluetoothConfig.NOTHING_SPP_UUID -> "Nothing SPP"
                BluetoothConfig.GALAXY_SPP_UUID -> "Galaxy Buds SPP"
                BluetoothConfig.GALAXY_LEGACY_SPP_UUID -> "Galaxy Buds Legacy SPP"
                BluetoothConfig.SONY_V2_SPP_UUID -> "Sony MDR V2 SPP"
                BluetoothConfig.SONY_V1_SPP_UUID -> "Sony MDR V1 SPP"
                BluetoothConfig.XIAOMI_SPP_UUID -> "Xiaomi SPP"
                BluetoothConfig.SENNHEISER_SPP_UUID -> "Sennheiser GAIA SPP"
                BluetoothConfig.PIXEL_BUDS_SPP_UUID -> "Pixel Buds Maestro SPP"
                BluetoothConfig.STANDARD_SPP_UUID -> "Standard SPP"
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

                    when (effectiveFamily) {
                        BudsProtocol.ProtocolFamily.HUAWEI_SPP,
                        BudsProtocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                            val (packets, remaining) = HuaweiProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingHuaweiPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                            val (packets, remaining) = NothingProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingNothingPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                            val (packets, remaining) = GalaxyProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingGalaxyPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> {
                            val (packets, remaining) = SonyProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSonyPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                            val (packets, remaining) = BoseProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingBosePacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                            val (packets, remaining) = SoundcoreProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSoundcorePacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                            val (packets, remaining) = XiaomiProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingXiaomiPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                            val (packets, remaining) = SennheiserProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSennheiserPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.JABRA_SPP -> {
                            val (packets, remaining) = JabraProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingJabraPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                            val (packets, remaining) = PixelBudsProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingPixelBudsPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.EDIFIER_SPP -> {
                            val (packets, remaining) = EdifierProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingEdifierPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                            val (packets, remaining) = SoundPeatsProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingSoundPeatsPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.AIROHA_RACE -> {
                            val (packets, remaining) = AirohaRaceProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingAirohaPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                            val (packets, remaining) = HuilianProtocol.parsePacketsFromStreamBuffer(accumulated)
                            accumulated = remaining
                            for (pkg in packets) handleIncomingHuilianPacket(pkg)
                        }
                        BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                            val (items, remaining) = JieliRcspProtocol.parseStream(accumulated, acceptRawAuth = !jieliAuthDone)
                            accumulated = remaining
                            for (item in items) handleIncomingJieliItem(item)
                        }
                        else -> {
                            val (frames, remaining) = BudsProtocol.parseFramesFromStreamBuffer(accumulated)
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
    private fun connectGatt(
        device: BluetoothDevice,
        gen: Int,
        transport: Int = BluetoothDevice.TRANSPORT_AUTO,
        isBatteryOnlyProbe: Boolean = false
    ) {
        if (gen != sessionGen.get()) return
        isBatteryOnlyGattProbe = isBatteryOnlyProbe
        if (!isBatteryOnlyProbe) {
            _connectionState.value = BleConnectionState.CONNECTING
            activeTransport = ActiveTransport.BLE_GATT
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, transport)
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
        batteryLevelCharacteristic = null
        activeBtDevice = null

        activeTransport = ActiveTransport.NONE
        huaweiMultiDevices.clear()
        galaxyManagerInfoSent = false
        sonySeq = 0
        soundcoreLastSound = null
        xiaomiAuthenticated = false
        pixelActiveChannel = 18
        pixelProbeIndex = 0
        jblAcc = ByteArray(0)
        jblGattRetryAttempt = 0
        isBatteryOnlyGattProbe = false
        baseusUseCrc = false
        qcyBatteryCharacteristic = null
        promotedReplicaFamily = null
        isReplicaGattProbe = false
        replicaProbeJob?.cancel()
        replicaProbeJob = null
        jieliSeq = 1
        jieliAuthDone = false
        jieliHostChallenge = null
        jieliVoiceDetails.clear()
        jieliDynamicEq = true
        stopAirPodsBeaconScan()
    }

    private fun disconnectInternal(emitUnexpected: Boolean) {
        sessionGen.incrementAndGet()
        closeResourcesOnly()
        activeModel = BudsProtocol.BudsModel.UNKNOWN
        activeDeviceAddress = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        if (!emitUnexpected) {
            _budsState.value = BudsProtocol.BudsState()
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
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                val pkt = HuaweiProtocol.buildAncModeWrite(mode)
                log("TX Huawei ANC [${mode.name}]")
                sendRawBytes(pkt)
                _budsState.value = _budsState.value.copy(ancMode = mode)
                scope.launch { delay(180); refreshAncState() }
            }
            BudsProtocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                val pkt = HuaweiProtocol.buildHonorChoiceAncWrite(mode)
                log("TX HonorChoice ANC [${mode.name}]")
                sendRawBytes(pkt)
                _budsState.value = _budsState.value.copy(ancMode = mode)
                scope.launch { delay(180); refreshAncState() }
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                val pkt = NothingProtocol.buildAncModeWrite(mode, _budsState.value.ancLevel)
                log("TX Nothing ANC [${mode.name}]")
                sendRawBytes(pkt)
                _budsState.value = _budsState.value.copy(ancMode = mode)
                scope.launch { delay(180); refreshAncState() }
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                val pkt = GalaxyProtocol.buildAncWrite(mode, activeModel)
                log("TX Galaxy ANC [${mode.name}]")
                sendRawBytes(pkt)
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> {
                log("TX Sony ANC [${mode.name}]")
                sendRawBytes(SonyProtocol.buildAncSet(sonySeq, mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose ANC [${mode.name}]")
                if (activeModel == BudsProtocol.BudsModel.BOSE_QC_45_35) {
                    sendRawBytes(BoseProtocol.buildAnrSet(mode))
                } else {
                    sendRawBytes(BoseProtocol.buildCncSet(mode))
                }
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                log("TX Soundcore ANC [${mode.name}]")
                sendRawBytes(SoundcoreProtocol.buildAncSet(mode, soundcoreLastSound))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                log("TX Xiaomi ANC [${mode.name}]")
                sendRawBytes(XiaomiProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
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
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.JABRA_SPP -> {
                log("TX Jabra ANC [${mode.name}]")
                if (activeModel == BudsProtocol.BudsModel.JABRA_ELITE_CLASSIC) {
                    sendRawBytes(JabraProtocol.buildNoiseModeSet(mode))
                } else {
                    sendRawBytes(JabraProtocol.buildAmbienceSet(mode))
                }
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                log("TX PixelBuds ANC [${mode.name}]")
                val v = when (mode) {
                    AncMode.OFF -> 1L
                    AncMode.NOISE_REDUCTION -> 2L
                    AncMode.TRANSPARENCY -> 3L
                }
                sendRawBytes(PixelBudsProtocol.buildWriteSettingVarint(pixelActiveChannel, PixelBudsProtocol.SETTING_ANC, v))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.JBL_GATT -> {
                log("TX JBL ANC [${mode.name}]")
                sendRawBytes(JblProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.EDIFIER_SPP -> {
                log("TX Edifier ANC [${mode.name}]")
                sendRawBytes(EdifierProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                log("TX SoundPEATS ANC [${mode.name}]")
                sendRawBytes(SoundPeatsProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.QCY_GATT -> {
                log("TX QCY ANC [${mode.name}]")
                sendRawBytes(QcyProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.BASEUS_GATT -> {
                log("TX Baseus ANC [${mode.name}]")
                sendRawBytes(BaseusProtocol.buildAncSet(mode, baseusUseCrc))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.MOONDROP_GAIA -> {
                log("TX Moondrop ANC [${mode.name}]")
                sendRawBytes(MoondropProtocol.buildAncSet(mode, ble = activeTransport == ActiveTransport.BLE_GATT))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.AIROHA_RACE -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX Airoha RACE ANC [${mode.name}]")
                sendRawBytes(AirohaRaceProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX Huilian StarFun ANC [${mode.name}]")
                sendRawBytes(HuilianProtocol.buildAncSet(mode))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX JieLi RCSP ANC [${mode.name}]")
                sendRawBytes(JieliRcspProtocol.buildSetAnc(nextJieliSeq(), mode, jieliVoiceDetails[2]))
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            else -> {
                val frame = BudsProtocol.buildAncCommandFrame(mode)
                log("TX ANC [${mode.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
        }
    }

    /**
     * Set ANC intensity level (Huawei / Nothing / Xiaomi / realme / OnePlus / OPPO / Bose / Soundcore / Galaxy)
     */
    fun setAncLevel(level: HuaweiProtocol.AncLevel) {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                val pkt = HuaweiProtocol.buildAncLevelWrite(level)
                log("TX Huawei ANC Level [${level.name}]")
                sendRawBytes(pkt)
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
                scope.launch { delay(180); refreshAncState() }
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                val pkt = NothingProtocol.buildAncLevelWrite(level)
                log("TX Nothing ANC Level [${level.name}]")
                sendRawBytes(pkt)
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
                scope.launch { delay(180); refreshAncState() }
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                log("TX Xiaomi ANC Level [${level.name}]")
                sendRawBytes(XiaomiProtocol.buildAncStrengthSet(level))
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildAncLevelCommandFrame(level)
                log("TX Realme ANC Level [${level.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose CNC Level [${level.name}]")
                sendRawBytes(BoseProtocol.buildCncLevelSet(level))
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                log("TX Soundcore ANC Level [${level.name}]")
                sendRawBytes(SoundcoreProtocol.buildAncLevelSet(level, soundcoreLastSound))
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                log("TX Galaxy ANC Level [${level.name}]")
                sendRawBytes(GalaxyProtocol.buildAncLevelWrite(level, activeModel))
                _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION, ancLevel = level)
            }
            else -> return
        }
    }

    /**
     * Request battery levels
     */
    fun refreshBattery() {
        if (activeTransport == ActiveTransport.BT_BATTERY_ONLY) {
            activeBtDevice?.let { pollStandardBluetoothBattery(it) }
            val gatt = bluetoothGatt
            val bChar = batteryLevelCharacteristic
            if (gatt != null && bChar != null) {
                try { gatt.readCharacteristic(bChar) } catch (_: Exception) {}
            }
            return
        }
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP,
            BudsProtocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                log("TX Huawei/Honor Battery Req")
                sendRawBytes(HuaweiProtocol.buildBatteryRequest())
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing Battery Req")
                sendRawBytes(NothingProtocol.buildBatteryRequest())
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP,
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                // Push-based protocols
            }
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> {
                sendRawBytes(SonyProtocol.buildBatteryReq(sonySeq, 0x00))
                sendRawBytes(SonyProtocol.buildBatteryReq(sonySeq, 0x01))
                sendRawBytes(SonyProtocol.buildBatteryReq(sonySeq, 0x02))
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> sendRawBytes(BoseProtocol.buildBatteryReq())
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> sendRawBytes(SoundcoreProtocol.buildStateReq())
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> if (xiaomiAuthenticated) sendRawBytes(XiaomiProtocol.buildDeviceInfoReq())
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_BATTERY))
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_CHARGING))
            }
            BudsProtocol.ProtocolFamily.JABRA_SPP -> sendRawBytes(JabraProtocol.buildBatteryReq())
            BudsProtocol.ProtocolFamily.JBL_GATT -> sendRawBytes(JblProtocol.buildBatteryReq())
            BudsProtocol.ProtocolFamily.AIRPODS_BLE_BEACON -> {
                activeBtDevice?.let { pollStandardBluetoothBattery(it) }
                if (airPodsScanCallback == null) startAirPodsBeaconScan(sessionGen.get())
            }
            BudsProtocol.ProtocolFamily.EDIFIER_SPP -> sendRawBytes(EdifierProtocol.buildBatteryReq())
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                sendRawBytes(SoundPeatsProtocol.buildLeftBatReq())
                sendRawBytes(SoundPeatsProtocol.buildRightBatReq())
                sendRawBytes(SoundPeatsProtocol.buildCaseBatReq())
            }
            BudsProtocol.ProtocolFamily.QCY_GATT -> {
                val gatt = bluetoothGatt
                val qBat = qcyBatteryCharacteristic
                if (gatt != null && qBat != null) {
                    try { gatt.readCharacteristic(qBat) } catch (_: Exception) {}
                }
                sendRawBytes(QcyProtocol.buildQuery(QcyProtocol.CMD_BATTERY))
            }
            BudsProtocol.ProtocolFamily.BASEUS_GATT -> {
                sendRawBytes(BaseusProtocol.buildBatteryReq(baseusUseCrc))
                sendRawBytes(BaseusProtocol.buildCaseReq(baseusUseCrc))
            }
            BudsProtocol.ProtocolFamily.MOONDROP_GAIA -> {
                sendRawBytes(MoondropProtocol.buildBatteryGet(ble = activeTransport == ActiveTransport.BLE_GATT))
            }
            BudsProtocol.ProtocolFamily.AIROHA_RACE -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) {
                    activeBtDevice?.let { pollStandardBluetoothBattery(it) }
                    if (airPodsScanCallback == null) startAirPodsBeaconScan(sessionGen.get())
                } else {
                    sendRawBytes(AirohaRaceProtocol.buildBatteryReq(isPartner = false))
                    sendRawBytes(AirohaRaceProtocol.buildBatteryReq(isPartner = true))
                }
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) {
                    activeBtDevice?.let { pollStandardBluetoothBattery(it) }
                } else {
                    sendRawBytes(HuilianProtocol.buildBatteryReq())
                }
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) {
                    activeBtDevice?.let { pollStandardBluetoothBattery(it) }
                } else {
                    sendRawBytes(JieliRcspProtocol.buildGetAdvInfo(nextJieliSeq()))
                }
            }
            BudsProtocol.ProtocolFamily.LOGITECH_HID -> {
                activeBtDevice?.let { pollStandardBluetoothBattery(it) }
                val gatt = bluetoothGatt
                val bChar = batteryLevelCharacteristic
                if (gatt != null && bChar != null) {
                    try { gatt.readCharacteristic(bChar) } catch (_: Exception) {}
                }
            }
            else -> {
                val frame = BudsProtocol.buildBatteryRequestFrame()
                log("TX Battery Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
        }
    }

    /**
     * Request current ANC mode
     */
    fun refreshAncState() {
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP,
            BudsProtocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                log("TX Huawei/Honor ANC Req")
                sendRawBytes(HuaweiProtocol.buildAncReadRequest())
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing ANC Req")
                sendRawBytes(NothingProtocol.buildAncReadRequest())
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP,
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {}
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> sendRawBytes(SonyProtocol.buildAncReq(sonySeq))
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildCncReq())
                sendRawBytes(BoseProtocol.buildAnrReq())
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> if (xiaomiAuthenticated) {
                sendRawBytes(XiaomiProtocol.buildRunInfoReq())
                sendRawBytes(XiaomiProtocol.buildGetConfigReq(XiaomiProtocol.CFG_EFFECT_STRENGTH))
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_ANC_ENABLED))
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_GET_TRANSPARENT))
            }
            BudsProtocol.ProtocolFamily.JABRA_SPP -> {
                sendRawBytes(JabraProtocol.buildAmbienceReq())
                sendRawBytes(JabraProtocol.buildNoiseModeReq())
            }
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_ANC))
            }
            BudsProtocol.ProtocolFamily.JBL_GATT -> sendRawBytes(JblProtocol.buildNoiseReq())
            BudsProtocol.ProtocolFamily.AIRPODS_BLE_BEACON -> {}
            BudsProtocol.ProtocolFamily.EDIFIER_SPP -> sendRawBytes(EdifierProtocol.buildAncReq())
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> sendRawBytes(SoundPeatsProtocol.buildAncReq())
            BudsProtocol.ProtocolFamily.QCY_GATT -> sendRawBytes(QcyProtocol.buildQuery(QcyProtocol.CMD_ANC_MODE))
            BudsProtocol.ProtocolFamily.BASEUS_GATT -> {}
            BudsProtocol.ProtocolFamily.MOONDROP_GAIA -> sendRawBytes(MoondropProtocol.buildAncGet(ble = activeTransport == ActiveTransport.BLE_GATT))
            BudsProtocol.ProtocolFamily.AIROHA_RACE -> {
                if (activeTransport != ActiveTransport.BLE_BEACON) {
                    sendRawBytes(AirohaRaceProtocol.buildAncStatusReq())
                }
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport != ActiveTransport.BLE_BEACON) {
                    sendRawBytes(HuilianProtocol.buildAncReq())
                }
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport != ActiveTransport.BLE_BEACON) {
                    sendRawBytes(JieliRcspProtocol.buildGetSysInfo(nextJieliSeq()))
                }
            }
            else -> {
                val frame = BudsProtocol.buildAncStateRequestFrame()
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
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei LowLatency Req")
                sendRawBytes(HuaweiProtocol.buildLowLatencyRead())
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing LowLatency Req")
                sendRawBytes(NothingProtocol.buildLowLatencyRead())
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                if (xiaomiAuthenticated) {
                    log("TX Xiaomi LowLatency Req (0x002F)")
                    sendRawBytes(XiaomiProtocol.buildLowLatencyReq())
                }
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_GAME_MODE)
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
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei LowLatency [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuaweiProtocol.buildLowLatencyWrite(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
                scope.launch { delay(500); refreshGameMode() }
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing LowLatency [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(NothingProtocol.buildLowLatencyWrite(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
                scope.launch { delay(250); refreshGameMode() }
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                if (!xiaomiAuthenticated) return
                log("TX Xiaomi LowLatency (0x002F) [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(XiaomiProtocol.buildLowLatencySet(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
                scope.launch { delay(220); refreshGameMode() }
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                val legacy = activeModel == BudsProtocol.BudsModel.GALAXY_BUDS
                log("TX Galaxy GamingMode (0x87) [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(GalaxyProtocol.buildGameModeWrite(enabled, legacy))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                val isLiberty = activeModel == BudsProtocol.BudsModel.SOUNDCORE_LIBERTY_4_NC
                log("TX Soundcore Gaming [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(SoundcoreProtocol.buildGameModeSet(enabled, isLiberty))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.EDIFIER_SPP -> {
                log("TX Edifier GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(EdifierProtocol.buildGameModeSet(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                log("TX SoundPEATS GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(SoundPeatsProtocol.buildGameModeSet(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.QCY_GATT -> {
                log("TX QCY GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(QcyProtocol.buildGameModeSet(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.BASEUS_GATT -> {
                log("TX Baseus GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(BaseusProtocol.buildGameModeSet(enabled, baseusUseCrc))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.AIROHA_RACE -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX Airoha RACE GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(AirohaRaceProtocol.buildGameModeSet(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX Huilian StarFun GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuilianProtocol.buildGameModeSet(enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX JieLi RCSP GameMode [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(JieliRcspProtocol.buildSetGameMode(nextJieliSeq(), enabled))
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_GAME_MODE, enabled)
                log("TX GameMode [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(gameMode = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Spatial Audio state (realme Feature 0x1B / Nothing 0xC04F)
     */
    fun refreshSpatialAudio() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                if (!activeModel.supportsNothingSpatial) return
                log("TX Nothing SpatialAudio Req")
                sendRawBytes(NothingProtocol.buildSpatialRead())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_SPATIAL_AUDIO)
                log("TX SpatialAudio Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            else -> return
        }
    }

    /**
     * Set Spatial Audio (ON / OFF)
     */
    fun setSpatialAudio(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                val modes = activeModel.nothingSpatialModes
                val mode = if (enabled) modes.firstOrNull { it != NothingProtocol.SPATIAL_OFF } ?: NothingProtocol.SPATIAL_FIXED else NothingProtocol.SPATIAL_OFF
                setSpatialAudioMode(mode)
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_SPATIAL, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _budsState.value = _budsState.value.copy(spatialAudio = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                log("TX Soundcore Surround [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(SoundcoreProtocol.buildSurroundSet(enabled))
                _budsState.value = _budsState.value.copy(spatialAudio = enabled)
            }
            BudsProtocol.ProtocolFamily.JBL_GATT -> {
                sendRawBytes(JblProtocol.buildSpatialSet(enabled))
                _budsState.value = _budsState.value.copy(spatialAudio = enabled)
            }
            BudsProtocol.ProtocolFamily.QCY_GATT -> {
                sendRawBytes(QcyProtocol.buildSpatialSet(enabled))
                _budsState.value = _budsState.value.copy(spatialAudio = enabled)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_SPATIAL_AUDIO, enabled)
                log("TX SpatialAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(spatialAudio = enabled)
            }
            else -> return
        }
    }

    /**
     * Set multi-mode Nothing / CMF Spatial Audio (0=Off, 1=Fixed, 2=Head-tracking, 3=Concert, 4=Cinema)
     */
    fun setSpatialAudioMode(mode: Int) {
        if (!activeModel.isNothing || !activeModel.supportsNothingSpatial) return
        log("TX Nothing SpatialAudio [mode=$mode]")
        sendRawBytes(NothingProtocol.buildSpatialWrite(mode))
        _budsState.value = _budsState.value.copy(
            spatialAudioMode = mode,
            spatialAudio = mode != NothingProtocol.SPATIAL_OFF
        )
        scope.launch { delay(200); refreshSpatialAudio() }
    }

    /**
     * Request High-Definition Audio (realme LDAC/LHDC 5.0 or Huawei Sound Quality Preference)
     */
    fun refreshHighQualityAudio() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei SoundQuality Req")
                sendRawBytes(HuaweiProtocol.buildSoundQualityRead())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_HIGH_QUALITY_AUDIO)
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
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei SoundQuality [${if (enabled) "QUALITY" else "CONNECTIVITY"}]")
                sendRawBytes(HuaweiProtocol.buildSoundQualityWrite(enabled))
                _budsState.value = _budsState.value.copy(highQualityAudio = enabled)
                scope.launch { delay(200); refreshHighQualityAudio() }
            }
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                log("TX SoundPEATS LDAC [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(SoundPeatsProtocol.buildLdacSet(enabled))
                _budsState.value = _budsState.value.copy(highQualityAudio = enabled)
            }
            BudsProtocol.ProtocolFamily.QCY_GATT -> {
                log("TX QCY LDAC [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(QcyProtocol.buildLdacSet(enabled))
                _budsState.value = _budsState.value.copy(highQualityAudio = enabled)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_HIGH_QUALITY_AUDIO, enabled)
                log("TX HighQualityAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(highQualityAudio = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Dynamic Audio / Ultra Bass state
     */
    fun refreshDynamicAudio() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing BassBoost Req")
                sendRawBytes(NothingProtocol.buildBassBoostRead())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_DYNAMIC_AUDIO)
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
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing BassBoost [${if (enabled) "ON" else "OFF"}, lvl=$nothingLastBassLevel]")
                sendRawBytes(NothingProtocol.buildBassBoostWrite(enabled, nothingLastBassLevel))
                _budsState.value = _budsState.value.copy(dynamicAudio = enabled, bassLevel = nothingLastBassLevel)
                scope.launch { delay(200); refreshDynamicAudio() }
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_DYNAMIC_AUDIO, enabled)
                log("TX DynamicAudio [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(dynamicAudio = enabled)
            }
            else -> return
        }
    }

    /**
     * Set Nothing / CMF Enhanced Bass intensity level (1..5)
     */
    fun setBassLevel(level: Int) {
        if (!activeModel.isNothing) return
        val clamped = level.coerceIn(1, 5)
        nothingLastBassLevel = clamped
        log("TX Nothing BassBoost Level [$clamped]")
        sendRawBytes(NothingProtocol.buildBassBoostWrite(true, clamped))
        _budsState.value = _budsState.value.copy(dynamicAudio = true, bassLevel = clamped)
        scope.launch { delay(200); refreshDynamicAudio() }
    }

    /**
     * Request Dual-Device Connection state
     */
    fun refreshDualConnection() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei DualConnect Req")
                sendRawBytes(HuaweiProtocol.buildDualConnectRead())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_DUAL_CONNECTION)
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
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei DualConnect [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuaweiProtocol.buildDualConnectWrite(enabled))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
                scope.launch { delay(250); refreshDualConnection() }
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                log("TX Galaxy Seamless [${if (enabled) "ON" else "OFF"}]")
                val legacy = activeModel == BudsProtocol.BudsModel.GALAXY_BUDS
                sendRawBytes(GalaxyProtocol.buildSeamlessWrite(enabled, legacy))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildMultipointSet(enabled))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                sendRawBytes(SoundcoreProtocol.buildDualConnSet(enabled))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                sendRawBytes(XiaomiProtocol.buildSetConfig(XiaomiProtocol.CFG_MULTIPOINT, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildWriteSettingVarint(pixelActiveChannel, PixelBudsProtocol.SETTING_MULTIPOINT, if (enabled) 1L else 0L))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                sendRawBytes(SoundPeatsProtocol.buildMultipointSet(enabled))
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_DUAL_CONNECTION, enabled)
                log("TX DualConnection [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(dualConnection = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Multi-Connect device list
     */
    fun refreshMultiConnectInfo() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                huaweiMultiDevices.clear()
                log("TX Huawei DualConnect Enum")
                sendRawBytes(HuaweiProtocol.buildDualConnectEnumerate())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildMultiConnectInfoRequestFrame()
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
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei AutoPause Req")
                sendRawBytes(HuaweiProtocol.buildAutoPauseRead())
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing InEar Req")
                sendRawBytes(NothingProtocol.buildInEarRead())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_AUTO_PLAY_PAUSE)
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
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei AutoPause [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(HuaweiProtocol.buildAutoPauseWrite(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
                scope.launch { delay(200); refreshAutoPlayPause() }
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing InEar [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(NothingProtocol.buildInEarWrite(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
                scope.launch { delay(200); refreshAutoPlayPause() }
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildAutoPauseSet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                sendRawBytes(SoundcoreProtocol.buildAutoPauseSet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                sendRawBytes(XiaomiProtocol.buildEarDetectionSet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_AUTO_PAUSE, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildWriteSettingVarint(pixelActiveChannel, PixelBudsProtocol.SETTING_OHD, if (enabled) 1L else 0L))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.JBL_GATT -> {
                sendRawBytes(JblProtocol.buildAutoPlaySet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                sendRawBytes(SoundPeatsProtocol.buildInEarSet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.QCY_GATT -> {
                sendRawBytes(QcyProtocol.buildInEarSet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                sendRawBytes(HuilianProtocol.buildEarDetectSet(enabled))
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_AUTO_PLAY_PAUSE, enabled)
                log("TX AutoPlayPause [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(autoPlayPause = enabled)
            }
            else -> return
        }
    }

    /**
     * Request Auto Answer state (Feature 8 / 0x08)
     */
    fun refreshAutoAnswer() {
        if (!activeModel.isRealme) return
        val frame = BudsProtocol.buildSwitchFeatureRequestFrame(BudsProtocol.FEATURE_ID_AUTO_ANSWER)
        log("TX AutoAnswer Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Set Auto Answer (ON / OFF)
     */
    fun setAutoAnswer(enabled: Boolean) {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildAutoAnswerSet(enabled))
                _budsState.value = _budsState.value.copy(autoAnswer = enabled)
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                sendRawBytes(XiaomiProtocol.buildSetConfig(XiaomiProtocol.CFG_AUTO_ANSWER, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _budsState.value = _budsState.value.copy(autoAnswer = enabled)
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildPacket(SennheiserProtocol.CMD_SET_AUTO_ANSWER, byteArrayOf(if (enabled) 0x01 else 0x00)))
                _budsState.value = _budsState.value.copy(autoAnswer = enabled)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildSwitchFeatureCommandFrame(BudsProtocol.FEATURE_ID_AUTO_ANSWER, enabled)
                log("TX AutoAnswer [${if (enabled) "ON" else "OFF"}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                _budsState.value = _budsState.value.copy(autoAnswer = enabled)
            }
            else -> return
        }
    }

    /**
     * Request total usage time for Left/Right earbuds (realme Opcode 0x0128)
     */
    fun refreshUseTime() {
        if (!activeModel.isRealme) return
        val frame = BudsProtocol.buildUseTimeRequestFrame()
        log("TX UseTime Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
        sendData(frame)
    }

    /**
     * Request current EQ preset and custom EQ parameters
     */
    fun refreshEq() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei EQ Req")
                sendRawBytes(HuaweiProtocol.buildEqRead())
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing EQ Req")
                sendRawBytes(NothingProtocol.buildEqRead(activeModel.nothingUsesListeningModeEq))
                if (activeModel.supportsCustomEq) {
                    sendRawBytes(NothingProtocol.buildCustomEqRead())
                }
            }
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> {
                sendRawBytes(SonyProtocol.buildEqReq(sonySeq))
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                sendRawBytes(BoseProtocol.buildEqReq())
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                if (xiaomiAuthenticated) {
                    sendRawBytes(XiaomiProtocol.buildGetConfigReq(XiaomiProtocol.CFG_EQ_PRESET))
                    sendRawBytes(XiaomiProtocol.buildCustomEqReq())
                }
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                sendRawBytes(SennheiserProtocol.buildEqGainsReq())
            }
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                sendRawBytes(PixelBudsProtocol.buildReadSetting(pixelActiveChannel, PixelBudsProtocol.SETTING_USER_EQ))
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildEqRequestFrame()
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
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildAllEqRequestFrame()
                log("TX All EQ Req: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                if (activeModel.supportsCustomEq) sendRawBytes(NothingProtocol.buildCustomEqRead())
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                if (xiaomiAuthenticated) sendRawBytes(XiaomiProtocol.buildCustomEqReq())
            }
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
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
        val spec = activeModel.customEqSpec() ?: if (effectiveFamily == BudsProtocol.ProtocolFamily.JIELI_RCSP) {
            BudsProtocol.BudsModel.AIRPODS_CLONE_JIELI.customEqSpec()
        } else null
        if (spec == null) return
        val clampedGains = List(spec.bandLabels.size) { idx ->
            gains.getOrElse(idx) { 0 }.coerceIn(spec.minDb, spec.maxDb)
        }
        val currentState = _budsState.value
        val freqs = currentState.customEqFreqs.takeIf { it.size == clampedGains.size } ?: spec.defaultFreqs

        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val existing = currentState.customEqProfiles
                val existingProfile = existing.firstOrNull { it.id == profileId }
                val targetId = when {
                    createNew -> ((existing.maxOfOrNull { it.id } ?: 0) + 1).coerceAtLeast(1)
                    profileId != null -> profileId
                    existing.isNotEmpty() -> existing.first().id
                    else -> 1
                }
                val action = when {
                    delete -> BudsProtocol.CUSTOM_EQ_ACTION_DELETE
                    createNew || (existingProfile == null && existing.isEmpty()) -> BudsProtocol.CUSTOM_EQ_ACTION_CREATE
                    else -> BudsProtocol.CUSTOM_EQ_ACTION_SAVE
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
                val frame = BudsProtocol.buildCustomEqSetFrame(action, profile)
                log("TX CustomEQ [action=$action, id=$targetId, name=$name, gains=$clampedGains]")
                sendData(frame)
                if (!delete) {
                    _budsState.value = currentState.copy(
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
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
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
                    _budsState.value = currentState.copy(
                        eqPreset = null,
                        activeCustomEqId = targetId,
                        activeCustomEqName = name,
                        customEqGains = clampedGains,
                        customEqFreqs = freqs
                    )
                }
                scope.launch { delay(280); refreshEq() }
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                val bass = clampedGains.getOrElse(0) { 0 }
                val mid = clampedGains.getOrElse(1) { 0 }
                val treble = clampedGains.getOrElse(2) { 0 }
                log("TX Nothing CustomEQ [B=$bass, M=$mid, T=$treble]")
                sendRawBytes(NothingProtocol.buildCustomEqWrite(bass, mid, treble))
                scope.launch {
                    delay(120)
                    sendRawBytes(NothingProtocol.buildSelectCustomEq(activeModel.nothingUsesListeningModeEq))
                }
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = listOf(bass, mid, treble)
                )
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                if (!xiaomiAuthenticated) return
                if (delete) {
                    log("TX Xiaomi CustomEQ Reset (0x0037)")
                    sendRawBytes(XiaomiProtocol.buildCustomEqReset())
                    _budsState.value = currentState.copy(
                        customEqGains = List(clampedGains.size) { 0 }
                    )
                } else {
                    log("TX Xiaomi CustomEQ Save (0x0037) [gains=$clampedGains]")
                    sendRawBytes(XiaomiProtocol.buildCustomEqSave(freqs, clampedGains, spec.maxDb))
                    _budsState.value = currentState.copy(
                        eqPreset = null,
                        activeCustomEqName = "Custom",
                        customEqGains = clampedGains,
                        customEqFreqs = freqs
                    )
                }
                scope.launch { delay(220); sendRawBytes(XiaomiProtocol.buildCustomEqReq()) }
            }
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> {
                log("TX Sony CustomEQ [gains=$clampedGains]")
                sendRawBytes(SonyProtocol.buildCustomEqSet(sonySeq, clampedGains))
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose CustomEQ [gains=$clampedGains]")
                scope.launch {
                    for (i in clampedGains.indices) {
                        sendRawBytes(BoseProtocol.buildEqBandSet(i, clampedGains[i]))
                        delay(50)
                    }
                }
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                log("TX Soundcore CustomEQ [gains=$clampedGains]")
                sendRawBytes(SoundcoreProtocol.buildCustomEqSet(clampedGains))
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
                log("TX Sennheiser CustomEQ [gains=$clampedGains]")
                scope.launch {
                    for (i in clampedGains.indices) {
                        sendRawBytes(SennheiserProtocol.buildEqBandSet(i, clampedGains[i]))
                        delay(50)
                    }
                }
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                log("TX PixelBuds CustomEQ [gains=$clampedGains]")
                sendRawBytes(PixelBudsProtocol.buildWriteUserEq(pixelActiveChannel, clampedGains))
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                log("TX SoundPEATS Parametric CustomEQ [gains=$clampedGains]")
                scope.launch {
                    for (frame in SoundPeatsProtocol.buildCustomEqFrames(clampedGains)) {
                        sendRawBytes(frame)
                        delay(45)
                    }
                }
                _budsState.value = currentState.copy(
                    eqPreset = null,
                    activeCustomEqName = "Custom",
                    customEqGains = clampedGains
                )
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX JieLi RCSP CustomEQ [gains=$clampedGains, dynamic=$jieliDynamicEq]")
                sendRawBytes(JieliRcspProtocol.buildSetCustomEq(nextJieliSeq(), clampedGains, jieliDynamicEq))
                _budsState.value = currentState.copy(
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
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                setCustomEq(
                    gains = profile.gains,
                    profileId = profile.id,
                    profileName = profile.name,
                    createNew = false,
                    delete = false
                )
            }
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                log("TX Huawei Select CustomEQ [id=${profile.id}, name=${profile.name}]")
                sendRawBytes(HuaweiProtocol.buildEqWrite(profile.id.toByte()))
                _budsState.value = _budsState.value.copy(
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
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                log("TX Realme FindBuds [${if (enabled) "ON" else "OFF"}]")
                sendData(BudsProtocol.buildFindBudsFrame(enabled))
                _budsState.value = _budsState.value.copy(hardwareFindActive = enabled)
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                val sideByte: Byte = when (channel) {
                    FindBudsChannel.LEFT -> 0x01
                    FindBudsChannel.RIGHT -> 0x03
                    FindBudsChannel.BOTH -> 0x06
                }
                log("TX Nothing RingBuds [side=$channel, ${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(NothingProtocol.buildRingBuds(sideByte, enabled))
                _budsState.value = _budsState.value.copy(hardwareFindActive = enabled)
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                val legacy = activeModel == BudsProtocol.BudsModel.GALAXY_BUDS
                log("TX Galaxy FindBuds [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(GalaxyProtocol.buildFindBudsWrite(enabled, legacy))
                _budsState.value = _budsState.value.copy(hardwareFindActive = enabled)
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX JieLi RCSP FindBuds [${if (enabled) "ON" else "OFF"}]")
                sendRawBytes(JieliRcspProtocol.buildFindBuds(nextJieliSeq(), enabled))
                _budsState.value = _budsState.value.copy(hardwareFindActive = enabled)
            }
            else -> return
        }
    }

    /**
     * Rename device in headset firmware where protocol supports it (Bose BMAP).
     */
    fun renameFirmwareDevice(newName: String): Boolean {
        if (_connectionState.value != BleConnectionState.CONNECTED) return false
        return when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
                log("TX Bose Rename -> \"$newName\"")
                sendRawBytes(BoseProtocol.buildRename(newName))
                true
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return false
                log("TX Huilian Rename -> \"$newName\"")
                sendRawBytes(HuilianProtocol.buildRenameSet(newName))
                true
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return false
                log("TX JieLi RCSP Rename -> \"$newName\"")
                sendRawBytes(JieliRcspProtocol.buildRename(nextJieliSeq(), newName))
                true
            }
            else -> false
        }
    }

    private fun applyStandardEqState(preset: EqPreset) {
        val cur = _budsState.value
        _budsState.value = cur.copy(
            eqPreset = preset,
            activeCustomEqId = null,
            activeCustomEqName = null,
            customEqProfiles = cur.customEqProfiles.map { if (it.isSelected) it.copy(isSelected = false) else it }
        )
    }

    /**
     * Set EQ preset
     */
    fun setEqPreset(preset: EqPreset) {
        when (effectiveFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                val hwId = HuaweiProtocol.eqPresetToHuaweiId(preset, activeModel.huaweiEqUsesPro3Ids)
                log("TX Huawei EQ [${preset.name} -> id=$hwId]")
                sendRawBytes(HuaweiProtocol.buildEqWrite(hwId))
                applyStandardEqState(preset)
                scope.launch { delay(200); refreshEq() }
            }
            BudsProtocol.ProtocolFamily.HONOR_CHOICE_SPP -> {
                log("TX HonorChoice EQ [${preset.name}]")
                sendRawBytes(HuaweiProtocol.buildHonorChoiceEqWrite(preset))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing EQ [${preset.name}]")
                val isBuds2a = activeModel == BudsProtocol.BudsModel.CMF_BUDS_2A
                sendRawBytes(NothingProtocol.buildEqWrite(preset, activeModel.nothingUsesListeningModeEq, isBuds2a))
                applyStandardEqState(preset)
                scope.launch { delay(200); refreshEq() }
            }
            BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                log("TX Galaxy EQ [${preset.name}]")
                val legacy = activeModel == BudsProtocol.BudsModel.GALAXY_BUDS
                sendRawBytes(GalaxyProtocol.buildEqWrite(preset, legacy))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.SONY_MDR_SPP -> {
                log("TX Sony EQ [${preset.name}]")
                sendRawBytes(SonyProtocol.buildEqSet(sonySeq, preset))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                log("TX Xiaomi EQ [${preset.name}]")
                sendRawBytes(XiaomiProtocol.buildEqSet(preset))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.JBL_GATT -> {
                log("TX JBL EQ [${preset.name}]")
                sendRawBytes(JblProtocol.buildEqSet(preset))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.EDIFIER_SPP -> {
                log("TX Edifier EQ [${preset.name}]")
                sendRawBytes(EdifierProtocol.buildEqSet(preset))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.BASEUS_GATT -> {
                log("TX Baseus EQ [${preset.name}]")
                sendRawBytes(BaseusProtocol.buildEqSet(preset, baseusUseCrc))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.MOONDROP_GAIA -> {
                log("TX Moondrop EQ [${preset.name}]")
                sendRawBytes(MoondropProtocol.buildEqPresetSet(preset, ble = activeTransport == ActiveTransport.BLE_GATT))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX Huilian EQ [${preset.name}]")
                sendRawBytes(HuilianProtocol.buildEqSet(preset))
                applyStandardEqState(preset)
            }
            BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                if (activeTransport == ActiveTransport.BLE_BEACON) return
                log("TX JieLi RCSP EQ [${preset.name}]")
                sendRawBytes(JieliRcspProtocol.buildSetEqPreset(nextJieliSeq(), preset))
                applyStandardEqState(preset)
            }
            else -> {
                val frame = BudsProtocol.buildEqCommandFrame(preset)
                log("TX EQ [${preset.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)
                applyStandardEqState(preset)
            }
        }
    }

    /**
     * Request touch button settings
     */
    fun refreshKeySettings() {
        when (activeModel.protocolFamily) {
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
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
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing Gestures Req")
                sendRawBytes(NothingProtocol.buildGestureRead())
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildKeyFunctionRequestFrame()
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
            BudsProtocol.ProtocolFamily.HUAWEI_SPP -> {
                val isFb3 = activeModel == BudsProtocol.BudsModel.HUAWEI_FREEBUDS_3
                val code = HuaweiProtocol.actionToHuaweiTapCode(action, gesture, isFb3)
                log("TX Huawei Key [${side.name} ${gesture.name} -> ${action.name} (code=$code)]")
                sendRawBytes(HuaweiProtocol.buildKeyWrite(gesture, side, code))
                val currentMap = _budsState.value.touchSettings.toMutableMap()
                currentMap[Pair(side, gesture)] = action
                _budsState.value = _budsState.value.copy(touchSettings = currentMap)
            }
            BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
                log("TX Nothing Gesture [${side.name} ${gesture.name} -> ${action.name}]")
                sendRawBytes(NothingProtocol.buildGestureWrite(side, gesture, action))
                val currentMap = _budsState.value.touchSettings.toMutableMap()
                currentMap[Pair(side, gesture)] = action
                _budsState.value = _budsState.value.copy(touchSettings = currentMap)
            }
            BudsProtocol.ProtocolFamily.REALME_TL -> {
                val frame = BudsProtocol.buildKeyFunctionCommandFrame(side, gesture, action)
                log("TX KeySetting [${side.name} ${gesture.name} -> ${action.name}]: ${frame.joinToString(" ") { "%02X".format(it) }}")
                sendData(frame)

                val currentMap = _budsState.value.touchSettings.toMutableMap()
                currentMap[Pair(side, gesture)] = action
                _budsState.value = _budsState.value.copy(touchSettings = currentMap)
            }
            else -> return
        }
    }

    /**
     * Отправка TL-кадра (оборачивается в OPPOv1 SOF 0xAA).
     */
    private fun sendData(data: ByteArray) {
        sendRawBytes(BudsProtocol.wrapOppoFrame(data))
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
                    ActiveTransport.BLE_BEACON -> {
                        // Passive read-only beacon transport
                    }
                    ActiveTransport.BT_BATTERY_ONLY,
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
            log("✓ Device ready! Requesting state ($effectiveFamily)...")

            when (effectiveFamily) {
                BudsProtocol.ProtocolFamily.GALAXY_BUDS_SPP -> {
                    // Galaxy Buds push 0x61 unsolicited
                    return@launch
                }
                BudsProtocol.ProtocolFamily.XIAOMI_SPP -> {
                    log("TX Xiaomi Auth Challenge")
                    sendRawBytes(XiaomiProtocol.buildAuthStart())
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.BOSE_BMAP_SPP -> {
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
                BudsProtocol.ProtocolFamily.SOUNDCORE_SPP -> {
                    sendRawBytes(SoundcoreProtocol.buildStateReq())
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.SENNHEISER_GAIA_SPP -> {
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
                BudsProtocol.ProtocolFamily.PIXEL_BUDS_SPP -> {
                    for (ch in PixelBudsProtocol.PROBE_CHANNELS) {
                        if (gen != sessionGen.get()) return@launch
                        sendRawBytes(PixelBudsProtocol.buildRpcFrame(ch, PixelBudsProtocol.METHOD_GET_SW_INFO))
                        delay(120)
                    }
                    return@launch
                }
                BudsProtocol.ProtocolFamily.JBL_GATT -> {
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
                BudsProtocol.ProtocolFamily.EDIFIER_SPP -> {
                    sendRawBytes(EdifierProtocol.buildBatteryReq())
                    delay(80)
                    sendRawBytes(EdifierProtocol.buildAncReq())
                    delay(80)
                    sendRawBytes(EdifierProtocol.buildGameModeReq())
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.SOUNDPEATS_SPP -> {
                    sendRawBytes(SoundPeatsProtocol.buildLeftBatReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildRightBatReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildCaseBatReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildAncReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildGameModeReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildInEarReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildMultipointReq())
                    delay(60)
                    sendRawBytes(SoundPeatsProtocol.buildLdacReq())
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.QCY_GATT -> {
                    sendRawBytes(QcyProtocol.buildQuery(QcyProtocol.CMD_BATTERY))
                    delay(80)
                    sendRawBytes(QcyProtocol.buildQuery(QcyProtocol.CMD_ANC_MODE))
                    delay(80)
                    sendRawBytes(QcyProtocol.buildQuery(QcyProtocol.CMD_GAME_MODE))
                    delay(80)
                    sendRawBytes(QcyProtocol.buildQuery(QcyProtocol.CMD_IN_EAR))
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.BASEUS_GATT -> {
                    sendRawBytes(BaseusProtocol.buildBatteryReq(baseusUseCrc))
                    delay(80)
                    sendRawBytes(BaseusProtocol.buildCaseReq(baseusUseCrc))
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.MOONDROP_GAIA -> {
                    val isBle = activeTransport == ActiveTransport.BLE_GATT
                    sendRawBytes(MoondropProtocol.buildBatteryGet(isBle))
                    delay(90)
                    sendRawBytes(MoondropProtocol.buildAncGet(isBle))
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.AIROHA_RACE -> {
                    _budsState.value = _budsState.value.copy(
                        isLikelyReplica = true,
                        isPassiveBeaconMode = false,
                        activeReplicaAnc = true,
                        activeReplicaGameMode = true,
                        activeReplicaProtocolName = "Airoha RACE"
                    )
                    sendRawBytes(AirohaRaceProtocol.buildSdkVersionReq())
                    delay(70)
                    sendRawBytes(AirohaRaceProtocol.buildBuildVersionReq())
                    delay(70)
                    sendRawBytes(AirohaRaceProtocol.buildBatteryReq(isPartner = false))
                    delay(70)
                    sendRawBytes(AirohaRaceProtocol.buildBatteryReq(isPartner = true))
                    delay(70)
                    sendRawBytes(AirohaRaceProtocol.buildAncStatusReq())
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.HUILIAN_SPP -> {
                    _budsState.value = _budsState.value.copy(
                        isLikelyReplica = true,
                        isPassiveBeaconMode = false,
                        activeReplicaAnc = true,
                        activeReplicaGameMode = true,
                        activeReplicaAutoPause = true,
                        activeReplicaEq = true,
                        activeReplicaProtocolName = "Huilian StarFun"
                    )
                    sendRawBytes(HuilianProtocol.buildModelReq())
                    delay(60)
                    sendRawBytes(HuilianProtocol.buildModelStrReq())
                    delay(60)
                    sendRawBytes(HuilianProtocol.buildBatteryReq())
                    delay(60)
                    sendRawBytes(HuilianProtocol.buildAncReq())
                    delay(60)
                    sendRawBytes(HuilianProtocol.buildGameModeReq())
                    delay(60)
                    sendRawBytes(HuilianProtocol.buildEarDetectReq())
                    delay(60)
                    sendRawBytes(HuilianProtocol.buildEqReq())
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.JIELI_RCSP -> {
                    _budsState.value = _budsState.value.copy(
                        isLikelyReplica = true,
                        isPassiveBeaconMode = false,
                        activeReplicaAnc = true,
                        activeReplicaGameMode = true,
                        activeReplicaEq = true,
                        activeReplicaCustomEq = true,
                        activeReplicaProtocolName = "JieLi RCSP"
                    )
                    jieliAuthDone = false
                    jieliHostChallenge = JieliRcspProtocol.buildAuthChallenge()
                    log("TX JieLi RCSP Auth Reset + Challenge")
                    sendRawBytes(JieliRcspProtocol.AUTH_RESET)
                    delay(250)
                    jieliHostChallenge?.let { sendRawBytes(it) }
                    delay(450)
                    // Also send GetTargetInfo + GetADVInfo in case firmware accepts RCSP without auth
                    if (!jieliAuthDone && gen == sessionGen.get()) {
                        sendRawBytes(JieliRcspProtocol.buildGetTargetInfo(nextJieliSeq()))
                        delay(80)
                        sendRawBytes(JieliRcspProtocol.buildGetAdvInfo(nextJieliSeq()))
                        delay(80)
                        sendRawBytes(JieliRcspProtocol.buildGetSysInfo(nextJieliSeq()))
                    }
                    startPolling(gen)
                    return@launch
                }
                BudsProtocol.ProtocolFamily.NOTHING_SPP -> {
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
            val frame = BudsProtocol.buildRegisterNotificationFrame(eventId)
            sendData(frame)
        }
    }

    private fun startPolling(gen: Int) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && gen == sessionGen.get() && _connectionState.value == BleConnectionState.CONNECTED) {
                val foreground = isForegroundMode
                val interval = when {
                    !foreground -> BACKGROUND_POLL_INTERVAL_MS
                    activeTransport == ActiveTransport.BT_BATTERY_ONLY || activeModel.isLogitech || activeModel.isGenericBluetooth -> 15_000L
                    activeModel.isBose || activeModel.isSennheiser || activeModel.isJabra || activeModel.isSony || activeModel.isXiaomi -> 10_000L
                    activeModel.isSoundcore || activeModel.isJbl ||
                        effectiveFamily == BudsProtocol.ProtocolFamily.JIELI_RCSP ||
                        effectiveFamily == BudsProtocol.ProtocolFamily.AIROHA_RACE ||
                        effectiveFamily == BudsProtocol.ProtocolFamily.HUILIAN_SPP -> 5_000L
                    else -> POLL_INTERVAL_MS
                }
                delay(interval)
                if (gen != sessionGen.get() || _connectionState.value != BleConnectionState.CONNECTED) break

                // Power-saving background mode: 0 packets while screen is off; 1 battery ping/min when screen is on
                if (!isForegroundMode) {
                    val screenOn = try { powerManager?.isInteractive != false } catch (_: Throwable) { true }
                    if (!screenOn) continue
                    refreshBattery()
                    continue
                }

                if (activeTransport == ActiveTransport.BT_BATTERY_ONLY || activeModel.isLogitech || activeModel.isGenericBluetooth) {
                    refreshBattery()
                    continue
                }
                if (effectiveFamily == BudsProtocol.ProtocolFamily.JIELI_RCSP ||
                    effectiveFamily == BudsProtocol.ProtocolFamily.AIROHA_RACE ||
                    effectiveFamily == BudsProtocol.ProtocolFamily.HUILIAN_SPP
                ) {
                    refreshBattery()
                    delay(80)
                    refreshAncState()
                    continue
                }
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

    private fun pollStandardBluetoothBattery(device: BluetoothDevice) {
        try {
            val m = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            val raw = m.invoke(device) as? Int ?: -1
            if (raw in 0..100) {
                val hours = if (activeModel == BudsProtocol.BudsModel.LOGITECH_G435) (raw / 100f) * 18.0f else null
                log("RX BT HFP Battery (${device.name}): $raw%")
                updateBatteryState(
                    listOf(
                        BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, raw, false),
                        BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, raw, false),
                        BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false)
                    ),
                    isSnapshot = true
                )
                if (hours != null) {
                    _budsState.value = _budsState.value.copy(batteryHoursLeft = hours)
                }
            }
        } catch (_: Throwable) {
            // getBatteryLevel not exposed on this OEM/API
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != bluetoothGatt) {
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT connected (status=$status). Discovering services...")
                    if (!isBatteryOnlyGattProbe && !isReplicaGattProbe) {
                        _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected (status=$status)")
                    if (isReplicaGattProbe) {
                        // Silent AirPods replica GATT probe failed -> stay smoothly in passive BLE beacon mode
                        isReplicaGattProbe = false
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                    } else if (isBatteryOnlyGattProbe || activeTransport == ActiveTransport.BT_BATTERY_ONLY) {
                        // Keep HFP battery monitoring active even if BLE GATT probe fails/disconnects
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        batteryLevelCharacteristic = null
                    } else if (activeModel.isAirohaReplica || activeModel.isJieliReplica) {
                        val dev = activeBtDevice
                        val gen = sessionGen.get()
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        if (dev != null) {
                            log("${activeModel.protocolFamily} GATT unavailable (status=$status) -> switching to AirPods Replica BLE beacon + HFP mode")
                            enterAirPodsBeaconMode(dev, gen)
                        } else {
                            disconnect()
                        }
                    } else if (activeModel.isJbl || activeModel.isQcy || activeModel.isBaseus || activeModel.isMoondrop || activeModel.isEdifier || activeModel.isSoundPeats) {
                        val dev = activeBtDevice
                        val gen = sessionGen.get()
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        if (dev != null && jblGattRetryAttempt == 0) {
                            jblGattRetryAttempt = 1
                            log("${activeModel.protocolFamily} BLE GATT failed (status=$status), retrying with TRANSPORT_AUTO...")
                            connectGatt(dev, gen, transport = BluetoothDevice.TRANSPORT_AUTO, isBatteryOnlyProbe = false)
                        } else if (dev != null) {
                            log("${activeModel.protocolFamily} vendor GATT unavailable (status=$status) -> falling back to standard Bluetooth battery (HFP)")
                            enterStandardBluetoothBatteryMode(dev, gen, isFallback = true, tryBleBas = false)
                        } else {
                            disconnect()
                        }
                    } else {
                        disconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT service discovery failed: status=$status")
                if (isReplicaGattProbe) {
                    isReplicaGattProbe = false
                    try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                    return
                }
                if (isBatteryOnlyGattProbe || activeTransport == ActiveTransport.BT_BATTERY_ONLY) return
                if (activeModel.isJbl) {
                    val dev = activeBtDevice
                    if (dev != null) {
                        enterStandardBluetoothBatteryMode(dev, sessionGen.get(), isFallback = true, tryBleBas = false)
                        return
                    }
                }
                disconnect()
                return
            }

            // Silent Airoha RACE / JieLi RCSP GATT probe for any AirPods* device
            if (isReplicaGattProbe) {
                val airohaSvc = gatt.getService(BluetoothConfig.AIROHA_SERVICE)
                val airohaW = airohaSvc?.getCharacteristic(BluetoothConfig.AIROHA_WRITE)
                val airohaN = airohaSvc?.getCharacteristic(BluetoothConfig.AIROHA_NOTIFY)
                val jieliSvc = gatt.getService(BluetoothConfig.JIELI_GATT_SERVICE)
                val jieliW = jieliSvc?.getCharacteristic(BluetoothConfig.JIELI_GATT_WRITE)
                val jieliN = jieliSvc?.getCharacteristic(BluetoothConfig.JIELI_GATT_NOTIFY)
                isReplicaGattProbe = false
                if (airohaW != null && airohaN != null) {
                    log("✓ Auto-detected Airoha RACE service on AirPods* device -> enabling active ANC/Game control!")
                    promotedReplicaFamily = BudsProtocol.ProtocolFamily.AIROHA_RACE
                    activeTransport = ActiveTransport.BLE_GATT
                    writeCharacteristic = airohaW
                    notifyCharacteristic = airohaN
                    enableNotifications(gatt, airohaN)
                } else if (jieliW != null && jieliN != null) {
                    log("✓ Auto-detected JieLi RCSP GATT (0xAE00) on AirPods* device -> enabling active ANC/EQ/Game control!")
                    promotedReplicaFamily = BudsProtocol.ProtocolFamily.JIELI_RCSP
                    activeTransport = ActiveTransport.BLE_GATT
                    writeCharacteristic = jieliW
                    notifyCharacteristic = jieliN
                    enableNotifications(gatt, jieliN)
                } else {
                    log("AirPods auto-probe: neither AIROHA_SERVICE nor JIELI 0xAE00 found on GATT")
                    try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                }
                return
            }

            // Standard Bluetooth / Logitech G435 Bluetooth mode: read BLE Battery Service (0x180F / 0x2A19)
            if (isBatteryOnlyGattProbe || activeModel.isLogitech || activeModel.isGenericBluetooth) {
                val bas = gatt.getService(BluetoothConfig.BATTERY_SERVICE)
                val lvlChar = bas?.getCharacteristic(BluetoothConfig.BATTERY_LEVEL_CHAR)
                if (lvlChar != null) {
                    batteryLevelCharacteristic = lvlChar
                    log("✓ Found BLE Battery Service (0x180F/0x2A19)")
                    try {
                        gatt.readCharacteristic(lvlChar)
                        if ((lvlChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            enableNotifications(gatt, lvlChar)
                        }
                    } catch (_: Exception) {}
                } else {
                    log("BLE Battery Service (0x180F) not present; using Android HFP battery level")
                }
                return
            }

            var foundWrite: BluetoothGattCharacteristic? = null
            var foundNotify: BluetoothGattCharacteristic? = null

            // Prioritize vendor GATT services for Airoha / JBL / QCY / Moondrop / SoundPEATS
            if (activeModel.isAirohaReplica) {
                val airohaSvc = gatt.getService(BluetoothConfig.AIROHA_SERVICE)
                if (airohaSvc != null) {
                    foundWrite = airohaSvc.getCharacteristic(BluetoothConfig.AIROHA_WRITE)
                    foundNotify = airohaSvc.getCharacteristic(BluetoothConfig.AIROHA_NOTIFY)
                } else {
                    val dev = activeBtDevice
                    if (dev != null) {
                        log("Airoha RACE service not exposed -> switching to AirPods Replica BLE beacon + HFP mode")
                        try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
                        bluetoothGatt = null
                        enterAirPodsBeaconMode(dev, sessionGen.get())
                        return
                    }
                }
            } else if (activeModel.isJieliReplica) {
                val jieliSvc = gatt.getService(BluetoothConfig.JIELI_GATT_SERVICE)
                if (jieliSvc != null) {
                    foundWrite = jieliSvc.getCharacteristic(BluetoothConfig.JIELI_GATT_WRITE)
                    foundNotify = jieliSvc.getCharacteristic(BluetoothConfig.JIELI_GATT_NOTIFY)
                } else {
                    val dev = activeBtDevice
                    if (dev != null) {
                        log("JieLi GATT (0xAE00) not exposed -> switching to AirPods Replica BLE beacon + HFP mode")
                        try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
                        bluetoothGatt = null
                        enterAirPodsBeaconMode(dev, sessionGen.get())
                        return
                    }
                }
            } else if (activeModel.isQcy) {
                val qcySvc = gatt.getService(BluetoothConfig.QCY_GATT_SERVICE)
                if (qcySvc != null) {
                    foundWrite = qcySvc.getCharacteristic(BluetoothConfig.QCY_GATT_WRITE)
                    foundNotify = qcySvc.getCharacteristic(BluetoothConfig.QCY_GATT_NOTIFY)
                    qcyBatteryCharacteristic = qcySvc.getCharacteristic(BluetoothConfig.QCY_GATT_BATTERY)
                }
            } else if (activeModel.isMoondrop || activeModel.isSoundPeats) {
                val gaiaSvc = gatt.getService(BluetoothConfig.GAIA_BLE_SERVICE)
                if (gaiaSvc != null) {
                    foundWrite = gaiaSvc.getCharacteristic(BluetoothConfig.GAIA_BLE_WRITE)
                    foundNotify = gaiaSvc.getCharacteristic(BluetoothConfig.GAIA_BLE_NOTIFY)
                }
            } else if (activeModel.isJbl) {
                val jblSvc = gatt.getService(BluetoothConfig.JBL_GATT_SERVICE)
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
                } else {
                    log("JBL GATT service (65786365-...) not exposed by this model -> standard Bluetooth battery fallback")
                    val dev = activeBtDevice
                    if (dev != null) {
                        enterStandardBluetoothBatteryMode(dev, sessionGen.get(), isFallback = true, tryBleBas = false)
                        return
                    }
                }
            }

            if (foundWrite == null || foundNotify == null) {
                for (serviceUuid in BluetoothConfig.KNOWN_CANDIDATE_SERVICES) {
                    val service = gatt.getService(serviceUuid) ?: continue
                    if (serviceUuid == BluetoothConfig.NORDIC_UART_SERVICE) {
                        foundWrite = service.getCharacteristic(BluetoothConfig.NORDIC_UART_RX_WRITE)
                        foundNotify = service.getCharacteristic(BluetoothConfig.NORDIC_UART_TX_NOTIFY)
                    } else if (serviceUuid == BluetoothConfig.AIROHA_SERVICE) {
                        foundWrite = service.getCharacteristic(BluetoothConfig.AIROHA_WRITE)
                        foundNotify = service.getCharacteristic(BluetoothConfig.AIROHA_NOTIFY)
                    }
                    if (foundWrite != null && foundNotify != null) break
                }
            }

            if ((foundWrite == null || foundNotify == null) && BluetoothConfig.AUTO_DETECT_FALLBACK) {
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
                val dev = activeBtDevice
                if (dev != null && (activeModel.isQcy || activeModel.isBaseus || activeModel.isMoondrop || activeModel.isEdifier || activeModel.isSoundPeats)) {
                    log("${activeModel.protocolFamily} GATT service not found -> falling back to standard Bluetooth battery (HFP)")
                    enterStandardBluetoothBatteryMode(dev, sessionGen.get(), isFallback = true, tryBleBas = false)
                } else {
                    disconnect()
                }
                return
            }

            writeCharacteristic = foundWrite
            notifyCharacteristic = foundNotify
            _connectionState.value = BleConnectionState.SUBSCRIBING
            enableNotifications(gatt, foundNotify)
        }

        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BluetoothConfig.CCCD_UUID)
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
            if (isBatteryOnlyGattProbe || activeModel.isLogitech || activeModel.isGenericBluetooth) return
            _connectionState.value = BleConnectionState.CONNECTED
            finishConnectionReady(sessionGen.get())
        }

        private fun handleBatteryCharValue(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val lvl = bytes[0].toInt() and 0xFF
            if (lvl in 0..100) {
                val hours = if (activeModel == BudsProtocol.BudsModel.LOGITECH_G435) (lvl / 100f) * 18.0f else null
                log("RX BLE Battery Service (0x2A19): $lvl%")
                updateBatteryState(
                    listOf(
                        BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, false),
                        BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, false),
                        BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false)
                    ),
                    isSnapshot = true
                )
                if (hours != null) {
                    _budsState.value = _budsState.value.copy(batteryHoursLeft = hours)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BluetoothConfig.BATTERY_LEVEL_CHAR) {
                handleBatteryCharValue(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BluetoothConfig.BATTERY_LEVEL_CHAR) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleBatteryCharValue(it) }
            }
        }

        private fun handleGattRx(bytes: ByteArray) {
            if (isBatteryOnlyGattProbe || activeModel.isLogitech || activeModel.isGenericBluetooth) {
                handleBatteryCharValue(bytes)
            } else if (activeModel.isJbl) {
                jblAcc += bytes
                val (pkts, rem) = JblProtocol.parsePacketsFromStreamBuffer(jblAcc)
                jblAcc = rem
                for (pkt in pkts) handleIncomingJblPacket(pkt)
            } else if (activeModel.isQcy) {
                handleIncomingQcyBytes(bytes)
            } else if (activeModel.isBaseus) {
                handleIncomingBaseusBytes(bytes)
            } else if (activeModel.isMoondrop) {
                handleIncomingMoondropBytes(bytes)
            } else if (activeModel.isEdifier) {
                jblAcc += bytes
                val (pkts, rem) = EdifierProtocol.parsePacketsFromStreamBuffer(jblAcc)
                jblAcc = rem
                for (pkt in pkts) handleIncomingEdifierPacket(pkt)
            } else if (activeModel.isSoundPeats) {
                jblAcc += bytes
                val (pkts, rem) = SoundPeatsProtocol.parsePacketsFromStreamBuffer(jblAcc)
                jblAcc = rem
                for (pkt in pkts) handleIncomingSoundPeatsPacket(pkt)
            } else if (activeModel.isAirohaReplica || promotedReplicaFamily == BudsProtocol.ProtocolFamily.AIROHA_RACE) {
                jblAcc += bytes
                val (pkts, rem) = AirohaRaceProtocol.parsePacketsFromStreamBuffer(jblAcc)
                jblAcc = rem
                for (pkt in pkts) handleIncomingAirohaPacket(pkt)
            } else if (activeModel.isJieliReplica || promotedReplicaFamily == BudsProtocol.ProtocolFamily.JIELI_RCSP) {
                jblAcc += bytes
                val (items, rem) = JieliRcspProtocol.parseStream(jblAcc, acceptRawAuth = !jieliAuthDone)
                jblAcc = rem
                for (item in items) handleIncomingJieliItem(item)
            } else {
                BudsProtocol.parseFrame(bytes)?.let { handleIncomingFrame(it) }
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
    private fun handleIncomingFrame(frame: BudsProtocol.Frame) {
        val hexCmd = "0x" + frame.command.toString(16).uppercase().padStart(4, '0')
        val hexPayload = frame.payload.joinToString(" ") { "%02X".format(it) }

        when (frame.command) {
            BudsProtocol.CMD_BATTERY_RSP -> {
                val parsed = BudsProtocol.parseBatteryPayload(frame.payload)
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
            BudsProtocol.CMD_ANC_STATE_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val mode = BudsProtocol.parseAncStatePayload(frame.payload)
                val level = BudsProtocol.parseAncStateLevel(frame.payload)
                if (mode != null) {
                    log("RX ANC State: ${mode.name}${if (level != null) " (${level.name})" else ""}")
                    _budsState.value = _budsState.value.copy(
                        ancMode = mode,
                        ancLevel = level ?: _budsState.value.ancLevel
                    )
                }
            }
            BudsProtocol.CMD_SWITCH_FEATURE_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val gm = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_GAME_MODE)
                if (gm != null) {
                    log("RX GameMode State: ${if (gm) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(gameMode = gm)
                }
                val sa = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_SPATIAL_AUDIO)
                if (sa != null) {
                    log("RX SpatialAudio State: ${if (sa) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(spatialAudio = sa)
                }
                val hq = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_HIGH_QUALITY_AUDIO)
                if (hq != null) {
                    val codecLabel = if (activeModel.usesLhdc) "LHDC 5.0" else "LDAC"
                    log("RX HighQualityAudio ($codecLabel) State: ${if (hq) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(highQualityAudio = hq)
                }
                val da = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_DYNAMIC_AUDIO)
                if (da != null) {
                    log("RX DynamicAudio State: ${if (da) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(dynamicAudio = da)
                }
                val dc = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_DUAL_CONNECTION)
                if (dc != null) {
                    log("RX DualConnection State: ${if (dc) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(dualConnection = dc)
                }
                val app = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_AUTO_PLAY_PAUSE)
                if (app != null) {
                    log("RX AutoPlayPause State: ${if (app) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(autoPlayPause = app)
                }
                val aa = BudsProtocol.parseSwitchFeaturePayload(frame.payload, BudsProtocol.FEATURE_ID_AUTO_ANSWER)
                if (aa != null) {
                    log("RX AutoAnswer State: ${if (aa) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(autoAnswer = aa)
                }
            }
            BudsProtocol.CMD_SWITCH_FEATURE_SET_RSP -> {
                val ok = BudsProtocol.parseSwitchFeatureSetResponse(frame.payload)
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
            BudsProtocol.CMD_MULTI_CONNECT_INFO_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val devices = BudsProtocol.parseMultiConnectInfoPayload(frame.payload, hasStatusByte = true)
                if (devices != null) {
                    log("RX MultiConnect Devices: ${devices.map { "${it.name} (${it.mac}, state=${it.connectState})" }}")
                    _budsState.value = _budsState.value.copy(connectedDevices = devices)
                }
            }
            BudsProtocol.CMD_USE_TIME_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val useTimes = BudsProtocol.parseUseTimePayload(frame.payload)
                if (useTimes != null) {
                    val leftMin = useTimes[ComponentRole.LEFT_EAR]
                    val rightMin = useTimes[ComponentRole.RIGHT_EAR]
                    log("RX UseTime: L=${leftMin ?: -1}m, R=${rightMin ?: -1}m")
                    _budsState.value = _budsState.value.copy(
                        leftUseTimeMinutes = leftMin ?: _budsState.value.leftUseTimeMinutes,
                        rightUseTimeMinutes = rightMin ?: _budsState.value.rightUseTimeMinutes
                    )
                }
            }
            BudsProtocol.CMD_NOTIFICATION_REGISTER_RSP -> {
                log("RX Event Reg Rsp [$hexPayload]")
            }
            BudsProtocol.CMD_ANC_RSP -> {
                val ok = BudsProtocol.parseAncResponse(frame.payload)
                log("RX ANC Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshAncState()
                }
            }
            BudsProtocol.CMD_EQ_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val rawId = BudsProtocol.parseRawEqId(frame.payload)
                val preset = BudsProtocol.parseEqPayload(frame.payload)
                if (preset != null) {
                    log("RX EQ State: ${preset.name} (id=$rawId)")
                    applyStandardEqState(preset)
                } else if (rawId != null) {
                    log("RX EQ State: Custom (rawId=$rawId)")
                    _budsState.value = _budsState.value.copy(
                        eqPreset = null
                    )
                    refreshAllEq()
                }
            }
            BudsProtocol.CMD_EQ_SET_RSP -> {
                val ok = BudsProtocol.parseEqSetResponse(frame.payload)
                log("RX EQ Set Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshEq()
                }
            }
            BudsProtocol.CMD_ALL_EQ_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val profiles = BudsProtocol.parseCustomEqProfilesPayload(frame.payload)
                log("RX All EQ: ${profiles.map { "${it.name}(id=${it.id})${if (it.isSelected) " [active]" else ""}" }}")
                val activeCustom = profiles.firstOrNull { it.isSelected }
                _budsState.value = if (activeCustom != null) {
                    _budsState.value.copy(
                        eqPreset = null,
                        activeCustomEqId = activeCustom.id,
                        activeCustomEqName = activeCustom.name,
                        customEqProfiles = profiles,
                        customEqGains = activeCustom.gains,
                        customEqFreqs = activeCustom.freqs
                    )
                } else {
                    _budsState.value.copy(
                        customEqProfiles = profiles,
                        customEqGains = _budsState.value.customEqGains.ifEmpty { profiles.firstOrNull()?.gains ?: emptyList() },
                        customEqFreqs = _budsState.value.customEqFreqs.ifEmpty { profiles.firstOrNull()?.freqs ?: emptyList() }
                    )
                }
            }
            BudsProtocol.CMD_CUSTOM_EQ_SET_RSP -> {
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
            BudsProtocol.CMD_KEY_FUNCTION_RSP -> {
                log("RX $hexCmd [$hexPayload]")
                val items = BudsProtocol.parseKeyFunctionPayload(frame.payload)
                if (items.isNotEmpty()) {
                    log("RX KeySettings: ${items.map { "${it.side.name} ${it.gesture.name} = ${it.action.name}" }}")
                    val currentMap = _budsState.value.touchSettings.toMutableMap()
                    for (item in items) {
                        currentMap[Pair(item.side, item.gesture)] = item.action
                    }
                    _budsState.value = _budsState.value.copy(touchSettings = currentMap)
                }
            }
            BudsProtocol.CMD_KEY_FUNCTION_SET_RSP -> {
                val ok = BudsProtocol.parseKeyFunctionSetResponse(frame.payload)
                log("RX KeySetting Set Rsp [$hexPayload] -> ${if (ok) "✓ Confirmed" else "✗ Error"}")
                if (ok) {
                    refreshKeySettings()
                }
            }
            BudsProtocol.CMD_EVENT -> {
                log("RX Event $hexCmd [$hexPayload]")
                if (frame.payload.isNotEmpty() && frame.payload[0].toInt() == 0x03) {
                    val mode = BudsProtocol.parseAncEventPayload(frame.payload)
                    val level = BudsProtocol.parseAncEventLevel(frame.payload)
                    if (mode != null) {
                        log("RX Event: ANC switched to ${mode.name}${if (level != null) " (${level.name})" else ""}")
                        _budsState.value = _budsState.value.copy(
                            ancMode = mode,
                            ancLevel = level ?: _budsState.value.ancLevel
                        )
                    }
                } else if (frame.payload.isNotEmpty() && frame.payload[0] == BudsProtocol.EVENT_TYPE_GAME_MODE) {
                    val gm = BudsProtocol.parseGameModeEventPayload(frame.payload)
                    if (gm != null) {
                        log("RX Event: GameMode switched to ${if (gm) "ON" else "OFF"}")
                        _budsState.value = _budsState.value.copy(gameMode = gm)
                    }
                } else if (frame.payload.size > 1 && frame.payload[0] == BudsProtocol.EVENT_TYPE_MULTI_CONNECT) {
                    val subPayload = frame.payload.copyOfRange(1, frame.payload.size)
                    val devices = BudsProtocol.parseMultiConnectInfoPayload(subPayload, hasStatusByte = false)
                    if (devices != null) {
                        log("RX Event: MultiConnect devices updated: ${devices.map { "${it.name} (state=${it.connectState})" }}")
                        _budsState.value = _budsState.value.copy(connectedDevices = devices)
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
            BudsProtocol.CMD_NOTIFICATION -> {
                log("RX Event $hexCmd [$hexPayload]")
                val mode = BudsProtocol.parseAncEventPayload(frame.payload)
                val gm = BudsProtocol.parseGameModeEventPayload(frame.payload)
                if (mode != null) {
                    log("RX Event: ANC switched to ${mode.name}")
                    _budsState.value = _budsState.value.copy(ancMode = mode)
                } else if (gm != null) {
                    log("RX Event: GameMode switched to ${if (gm) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(gameMode = gm)
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

    private fun normalizeBatteries(list: List<BudsProtocol.BatteryInfo>): List<BudsProtocol.BatteryInfo> =
        list.map {
            if (it.role == ComponentRole.CASE && it.level == 0 && !it.isCharging)
                BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false)
            else it
        }

    private fun updateBatteryState(batteries: List<BudsProtocol.BatteryInfo>, isSnapshot: Boolean = false) {
        var current = _budsState.value
        if (isSnapshot) {
            val left = batteries.firstOrNull { it.role == ComponentRole.LEFT_EAR }
                ?: BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, -1, false)
            val right = batteries.firstOrNull { it.role == ComponentRole.RIGHT_EAR }
                ?: BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, -1, false)
            val case = batteries.firstOrNull { it.role == ComponentRole.CASE }
                ?: BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false)

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
        _budsState.value = current
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
                val batteries = mutableListOf<BudsProtocol.BatteryInfo>()

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
                    batteries.add(BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lLvl, leftChg))
                    batteries.add(BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, rLvl, rightChg))
                    batteries.add(BudsProtocol.BatteryInfo(ComponentRole.CASE, cLvl, caseChg))
                } else if (p1 != null && p1.isNotEmpty()) {
                    val gLvl = (p1[0].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                    val gChg = p3 != null && p3.any { it.toInt() == 1 }
                    batteries.add(BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, gLvl, gChg))
                    batteries.add(BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, gLvl, gChg))
                    batteries.add(BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false))
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
                        _budsState.value = _budsState.value.copy(ancMode = ancMode, ancLevel = ancLevel)
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
                    _budsState.value = _budsState.value.copy(gameMode = on)
                }
            }
            HuaweiProtocol.CMD_AUTO_PAUSE_READ -> {
                val v = pkg.param(1)
                if (v != null && v.isNotEmpty()) {
                    val on = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei AutoPause: ${if (on) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(autoPlayPause = on)
                }
            }
            HuaweiProtocol.CMD_SOUND_QUALITY_READ -> {
                val v = pkg.param(2)
                if (v != null && v.isNotEmpty()) {
                    val hq = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei SoundQuality: ${if (hq) "QUALITY" else "CONNECTIVITY"}")
                    _budsState.value = _budsState.value.copy(highQualityAudio = hq)
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
                        _budsState.value = _budsState.value.copy(
                            eqPreset = preset,
                            activeCustomEqId = null,
                            activeCustomEqName = null,
                            customEqProfiles = customProfiles
                        )
                    } else {
                        val customName = activeCustom?.name ?: "EQ #$modeId"
                        log("RX Huawei EQ Custom: $customName (id=$modeId)")
                        _budsState.value = _budsState.value.copy(
                            eqPreset = null,
                            activeCustomEqId = modeId,
                            activeCustomEqName = customName,
                            customEqProfiles = customProfiles,
                            customEqGains = activeCustom?.gains ?: _budsState.value.customEqGains,
                            customEqFreqs = activeCustom?.freqs ?: _budsState.value.customEqFreqs
                        )
                    }
                } else if (customProfiles.isNotEmpty()) {
                    _budsState.value = _budsState.value.copy(customEqProfiles = customProfiles)
                }
            }
            HuaweiProtocol.CMD_DUAL_CONNECT_READ -> {
                val v = pkg.param(1)
                if (v != null && v.isNotEmpty()) {
                    val on = (v[0].toInt() and 0xFF) == 1
                    log("RX Huawei DualConnect: ${if (on) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(dualConnection = on)
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
                    huaweiMultiDevices[idx] = BudsProtocol.ConnectedDeviceInfo(mac, name, connState, 0)
                    val sorted = huaweiMultiDevices.entries.sortedBy { it.key }.map { it.value }
                    log("RX Huawei DualConnect Device: $name ($mac, state=$connState)")
                    _budsState.value = _budsState.value.copy(connectedDevices = sorted)
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
                val isFb3 = activeModel == BudsProtocol.BudsModel.HUAWEI_FREEBUDS_3
                val left = pkg.param(1)
                val right = pkg.param(2)
                val map = _budsState.value.touchSettings.toMutableMap()
                if (left != null && left.isNotEmpty()) {
                    map[Pair(TouchSide.LEFT, gesture)] =
                        HuaweiProtocol.huaweiTapCodeToAction(left[0].toInt(), gesture, isFb3)
                }
                if (right != null && right.isNotEmpty()) {
                    map[Pair(TouchSide.RIGHT, gesture)] =
                        HuaweiProtocol.huaweiTapCodeToAction(right[0].toInt(), gesture, isFb3)
                }
                log("RX Huawei Key [${gesture.name}] updated")
                _budsState.value = _budsState.value.copy(touchSettings = map)
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
            NothingProtocol.CMD_ANC_EVENT,
            NothingProtocol.CMD_ANC_EVENT_C -> {
                val parsed = NothingProtocol.parseAncPayload(p)
                if (parsed != null) {
                    val (mode, level) = parsed
                    log("RX Nothing ANC: ${mode.name}${if (level != null) " (${level.name})" else ""}")
                    _budsState.value = _budsState.value.copy(ancMode = mode, ancLevel = level ?: _budsState.value.ancLevel)
                }
            }
            NothingProtocol.CMD_EQ_CLASSIC_RSP,
            NothingProtocol.CMD_EQ_LISTENING_RSP -> {
                val id = NothingProtocol.parseEqIdFromPayload(p)
                if (id != null) {
                    val useListening = pkg.commandId == NothingProtocol.CMD_EQ_LISTENING_RSP
                    val (preset, customName) = NothingProtocol.nothingIdToEqPreset(id, useListening)
                    log("RX Nothing EQ: ${preset?.name ?: customName ?: "id=$id"}")
                    if (preset != null) {
                        applyStandardEqState(preset)
                    } else {
                        _budsState.value = _budsState.value.copy(eqPreset = null, activeCustomEqName = customName)
                    }
                }
            }
            NothingProtocol.CMD_CUSTOM_EQ_RSP -> {
                val gains = NothingProtocol.parseCustomEqPayload(p)
                if (gains != null) {
                    log("RX Nothing CustomEQ: $gains")
                    _budsState.value = _budsState.value.copy(customEqGains = gains)
                }
            }
            NothingProtocol.CMD_IN_EAR_RSP -> {
                val on = NothingProtocol.parseInEarPayload(p)
                if (on != null) {
                    log("RX Nothing InEar: ${if (on) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(autoPlayPause = on)
                }
            }
            NothingProtocol.CMD_LOW_LATENCY_RSP -> {
                if (p.isNotEmpty()) {
                    val on = (p[0].toInt() and 0xFF) == 1
                    log("RX Nothing LowLatency: ${if (on) "ON" else "OFF"}")
                    _budsState.value = _budsState.value.copy(gameMode = on)
                }
            }
            NothingProtocol.CMD_BASS_BOOST_RSP -> {
                val parsed = NothingProtocol.parseBassBoostPayload(p)
                if (parsed != null) {
                    val (on, lvl) = parsed
                    nothingLastBassLevel = lvl
                    log("RX Nothing BassBoost: ${if (on) "ON" else "OFF"} (lvl=$lvl)")
                    _budsState.value = _budsState.value.copy(dynamicAudio = on, bassLevel = lvl)
                }
            }
            NothingProtocol.CMD_SPATIAL_RSP -> {
                val mode = NothingProtocol.parseSpatialPayload(p)
                if (mode != null) {
                    log("RX Nothing SpatialAudio: mode=$mode")
                    _budsState.value = _budsState.value.copy(
                        spatialAudioMode = mode,
                        spatialAudio = mode != NothingProtocol.SPATIAL_OFF
                    )
                }
            }
            NothingProtocol.CMD_GESTURE_RSP -> {
                val items = NothingProtocol.parseGesturesPayload(p)
                if (items.isNotEmpty()) {
                    log("RX Nothing Gestures: ${items.size} mappings")
                    val map = _budsState.value.touchSettings.toMutableMap()
                    for (it in items) map[Pair(it.side, it.gesture)] = it.action
                    _budsState.value = _budsState.value.copy(touchSettings = map)
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
                    _budsState.value = _budsState.value.copy(
                        ancMode = parsed.ancMode ?: _budsState.value.ancMode,
                        eqPreset = parsed.eqPreset,
                        activeCustomEqName = parsed.customEqName,
                        dualConnection = parsed.seamlessConnection ?: _budsState.value.dualConnection
                    )
                }
                if (!galaxyManagerInfoSent) {
                    galaxyManagerInfoSent = true
                    val legacy = activeModel == BudsProtocol.BudsModel.GALAXY_BUDS
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
                        _budsState.value = _budsState.value.copy(ancMode = mode)
                    }
                }
            }
            GalaxyProtocol.MSG_AMBIENT_UPDATE -> {
                if (p.isNotEmpty()) {
                    val mode = if ((p[0].toInt() and 0xFF) == 1) AncMode.TRANSPARENCY else AncMode.OFF
                    log("RX Galaxy Ambient: ${mode.name}")
                    _budsState.value = _budsState.value.copy(ancMode = mode)
                }
            }
            GalaxyProtocol.MSG_NOISE_REDUCTION_UPDATE -> {
                if (p.isNotEmpty()) {
                    val mode = if ((p[0].toInt() and 0xFF) == 1) AncMode.NOISE_REDUCTION else AncMode.OFF
                    log("RX Galaxy NoiseReduction: ${mode.name}")
                    _budsState.value = _budsState.value.copy(ancMode = mode)
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
                val batteries = mutableListOf<BudsProtocol.BatteryInfo>()
                when (inq) {
                    0x00 -> { // Single battery
                        val lvl = (p[2].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val chg = (p[3].toInt() and 0xFF) == 1
                        batteries.add(BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, chg))
                        batteries.add(BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, chg))
                    }
                    0x01 -> if (p.size >= 6) { // Left & Right
                        val ll = (p[2].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val lc = (p[3].toInt() and 0xFF) == 1
                        val rl = (p[4].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val rc = (p[5].toInt() and 0xFF) == 1
                        batteries.add(BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, ll, lc))
                        batteries.add(BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, rl, rc))
                    }
                    0x02 -> { // Cradle
                        val cl = (p[2].toInt() and 0xFF).let { if (it in 0..100) it else -1 }
                        val cc = (p[3].toInt() and 0xFF) == 1
                        batteries.add(BudsProtocol.BatteryInfo(ComponentRole.CASE, if (cl == 0 && !cc) -1 else cl, cc))
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
                    _budsState.value = _budsState.value.copy(ancMode = mode)
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
                    var gains = _budsState.value.customEqGains
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
                    _budsState.value = _budsState.value.copy(
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
                        val lvl = when {
                            raw <= 1 -> HuaweiProtocol.AncLevel.ULTRA
                            raw <= 4 -> HuaweiProtocol.AncLevel.GENERAL
                            else -> HuaweiProtocol.AncLevel.COMFORT
                        }
                        _budsState.value = _budsState.value.copy(
                            ancMode = mode,
                            ancLevel = if (mode == AncMode.NOISE_REDUCTION) lvl else _budsState.value.ancLevel
                        )
                    }
                    BoseProtocol.FUNC_ANR.toInt() -> {
                        val mode = when (p[0].toInt() and 0xFF) {
                            0 -> AncMode.OFF
                            3 -> AncMode.TRANSPARENCY
                            else -> AncMode.NOISE_REDUCTION
                        }
                        _budsState.value = _budsState.value.copy(ancMode = mode)
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
                            _budsState.value = _budsState.value.copy(customEqGains = clamped)
                        }
                    }
                    BoseProtocol.FUNC_MULTIPOINT.toInt() -> {
                        _budsState.value = _budsState.value.copy(dualConnection = (p[0].toInt() and 0x01) != 0)
                    }
                    BoseProtocol.FUNC_AUTO_PAUSE.toInt() -> {
                        _budsState.value = _budsState.value.copy(autoPlayPause = (p[0].toInt() and 0xFF) != 0)
                    }
                    BoseProtocol.FUNC_AUTO_ANSWER.toInt() -> {
                        _budsState.value = _budsState.value.copy(autoAnswer = (p[0].toInt() and 0xFF) != 0)
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
                    val cur = _budsState.value
                    updateBatteryState(listOf(
                        BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, l, cur.leftBattery.isCharging),
                        BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, r, cur.rightBattery.isCharging)
                    ), isSnapshot = false)
                } else if (b.size == 1) {
                    val g = SoundcoreProtocol.scaleBat(b[0].toInt() and 0xFF)
                    val cur = _budsState.value
                    updateBatteryState(listOf(
                        BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, g, cur.leftBattery.isCharging),
                        BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, g, cur.rightBattery.isCharging)
                    ), isSnapshot = false)
                }
            }
            SoundcoreProtocol.CMD_BATTERY_CHARGING -> {
                val cur = _budsState.value
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
                    val lvl = when (b[1].toInt() and 0xFF) {
                        0 -> HuaweiProtocol.AncLevel.ULTRA
                        1 -> HuaweiProtocol.AncLevel.GENERAL
                        2 -> HuaweiProtocol.AncLevel.COMFORT
                        else -> null
                    }
                    _budsState.value = _budsState.value.copy(
                        ancMode = mode,
                        ancLevel = if (mode == AncMode.NOISE_REDUCTION) (lvl ?: _budsState.value.ancLevel) else _budsState.value.ancLevel
                    )
                }
            }
            SoundcoreProtocol.CMD_GAMING_UPDATE -> {
                if (b.isNotEmpty()) _budsState.value = _budsState.value.copy(gameMode = b[0].toInt() == 1)
            }
            SoundcoreProtocol.CMD_STATE -> {
                // Single-headphone (Q30/Q35/Q45) vs Dual-bud state prefix
                if (b.size >= 6) {
                    val isSingle = activeModel == BudsProtocol.BudsModel.SOUNDCORE_LIFE_Q30 ||
                        activeModel == BudsProtocol.BudsModel.SOUNDCORE_SPACE_Q45
                    if (isSingle) {
                        val lvl = SoundcoreProtocol.scaleBat(b[0].toInt() and 0xFF)
                        val chg = b[1].toInt() == 1
                        updateBatteryState(listOf(
                            BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, chg),
                            BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, chg)
                        ), isSnapshot = true)
                    } else {
                        val l = SoundcoreProtocol.scaleBat(b[2].toInt() and 0xFF)
                        val r = SoundcoreProtocol.scaleBat(b[3].toInt() and 0xFF)
                        val lc = b[4].toInt() == 1
                        val rc = b[5].toInt() == 1
                        updateBatteryState(listOf(
                            BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, l, lc),
                            BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, r, rc),
                            BudsProtocol.BatteryInfo(ComponentRole.CASE, -1, false)
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
                        _budsState.value = _budsState.value.copy(ancMode = mode)
                    } else if (idx == 0x0A && l >= 2) {
                        _budsState.value = _budsState.value.copy(autoPlayPause = v == 0)
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
                                _budsState.value = _budsState.value.copy(ancMode = mode)
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
                            _budsState.value = _budsState.value.copy(autoAnswer = v != 0)
                        }
                        XiaomiProtocol.CFG_MULTIPOINT -> {
                            _budsState.value = _budsState.value.copy(dualConnection = v != 0)
                        }
                        XiaomiProtocol.CFG_LOW_LATENCY -> {
                            log("RX Xiaomi LowLatency (0x002F): ${if (v != 0) "ON" else "OFF"}")
                            _budsState.value = _budsState.value.copy(gameMode = v != 0)
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
                            _budsState.value = _budsState.value.copy(eqPreset = eq, activeCustomEqName = customName)
                        }
                        XiaomiProtocol.CFG_CUSTOM_EQ -> {
                            val parsed = XiaomiProtocol.parseCustomEqValue(data)
                            if (parsed != null) {
                                log("RX Xiaomi CustomEQ (0x0037): freqs=${parsed.first}, gains=${parsed.second}")
                                _budsState.value = _budsState.value.copy(
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
                            if (lvl != null) _budsState.value = _budsState.value.copy(ancLevel = lvl)
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
                val cur = _budsState.value
                updateBatteryState(listOf(
                    BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, v, cur.leftBattery.isCharging),
                    BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, v, cur.rightBattery.isCharging)
                ), isSnapshot = false)
            }
            SennheiserProtocol.CMD_GET_CHARGING -> {
                val chg = v != 0
                val cur = _budsState.value
                updateBatteryState(listOf(cur.leftBattery.copy(isCharging = chg), cur.rightBattery.copy(isCharging = chg)), isSnapshot = false)
            }
            SennheiserProtocol.CMD_GET_ANC_ENABLED -> {
                if (v != 0) _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION)
                else if (_budsState.value.ancMode == AncMode.NOISE_REDUCTION) _budsState.value = _budsState.value.copy(ancMode = AncMode.OFF)
            }
            SennheiserProtocol.CMD_GET_TRANSPARENT -> {
                if (v != 0) _budsState.value = _budsState.value.copy(ancMode = AncMode.TRANSPARENCY)
                else if (_budsState.value.ancMode == AncMode.TRANSPARENCY) _budsState.value = _budsState.value.copy(ancMode = AncMode.OFF)
            }
            SennheiserProtocol.CMD_GET_AUTO_PAUSE -> _budsState.value = _budsState.value.copy(autoPlayPause = v != 0)
            SennheiserProtocol.CMD_GET_AUTO_ANSWER -> _budsState.value = _budsState.value.copy(autoAnswer = v != 0)
            SennheiserProtocol.CMD_GET_SPATIAL -> _budsState.value = _budsState.value.copy(spatialAudio = v != 0)
            SennheiserProtocol.CMD_GET_EQ_GAINS -> if (p.size >= 5) {
                val startOff = if (p.size >= 6) p.size - 5 else 0
                val raw = List(5) { idx -> p[startOff + idx].toInt() } // signed bytes
                val scaled = if (raw.any { kotlin.math.abs(it) > 6 }) raw.map { (it / 10).coerceIn(-6, 6) } else raw.map { it.coerceIn(-6, 6) }
                log("RX Sennheiser EQ: $scaled")
                _budsState.value = _budsState.value.copy(customEqGains = scaled)
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
                if (mode != null) _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            JabraProtocol.FEAT_NOISE_MODE -> if (p.isNotEmpty()) {
                val mode = when (p[0].toInt() and 0xFF) {
                    0 -> AncMode.OFF
                    1 -> AncMode.NOISE_REDUCTION
                    3 -> AncMode.TRANSPARENCY
                    else -> null
                }
                if (mode != null) _budsState.value = _budsState.value.copy(ancMode = mode)
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
                    val bats = mutableListOf<BudsProtocol.BatteryInfo>()
                    for (part in PixelBudsProtocol.readProtoFields(f.bytes)) {
                        if (part.wire != 2) continue
                        var lvl = -1; var chg = false
                        for (v in PixelBudsProtocol.readProtoFields(part.bytes)) {
                            if (v.num == 1 && v.wire == 0) lvl = v.varint.toInt()
                            else if (v.num == 2 && v.wire == 0) chg = v.varint == 2L
                        }
                        if (lvl !in 0..100) lvl = -1
                        when (part.num) {
                            1 -> bats.add(BudsProtocol.BatteryInfo(ComponentRole.CASE, lvl, chg))
                            2 -> bats.add(BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, chg))
                            3 -> bats.add(BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, chg))
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
                            _budsState.value = _budsState.value.copy(customEqGains = gains)
                            continue
                        }
                        if (sv.wire != 0) continue
                        when (sv.num) {
                            PixelBudsProtocol.SETTING_OHD -> _budsState.value = _budsState.value.copy(autoPlayPause = sv.varint != 0L)
                            PixelBudsProtocol.SETTING_MULTIPOINT -> _budsState.value = _budsState.value.copy(dualConnection = sv.varint != 0L)
                            PixelBudsProtocol.SETTING_ANC -> {
                                val mode = when (sv.varint.toInt()) {
                                    1 -> AncMode.OFF
                                    2, 4 -> AncMode.NOISE_REDUCTION
                                    3 -> AncMode.TRANSPARENCY
                                    else -> null
                                }
                                if (mode != null) _budsState.value = _budsState.value.copy(ancMode = mode)
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
                    BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, l1, c1),
                    BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, l2, c2),
                    BudsProtocol.BatteryInfo(ComponentRole.CASE, lc, cc)
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
                _budsState.value = _budsState.value.copy(ancMode = mode)
            }
            JblProtocol.CMD_STATUS_RET -> if (p.size >= 2) {
                when (p[0].toInt() and 0xFF) {
                    0x31 -> if ((p[1].toInt() and 0xFF) != 0) _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION)
                    0x32 -> if ((p[1].toInt() and 0xFF) != 0) _budsState.value = _budsState.value.copy(ancMode = AncMode.TRANSPARENCY)
                    0x34 -> {
                        val code = p[1].toInt() and 0xFF
                        val eq = when (code) {
                            0 -> EqPreset.ORIGINAL_SOUND
                            2 -> EqPreset.SERENADE
                            7 -> EqPreset.PURE_BASS
                            3 -> EqPreset.DEEP_BASS
                            else -> null
                        }
                        _budsState.value = _budsState.value.copy(eqPreset = eq, activeCustomEqName = if (eq == null) "EQ #$code" else null)
                    }
                    0x38 -> _budsState.value = _budsState.value.copy(autoPlayPause = (p[1].toInt() and 0xFF) != 0)
                }
            }
        }
    }

    private fun handleIncomingEdifierPacket(pkg: EdifierProtocol.Packet) {
        when (pkg.cmd) {
            EdifierProtocol.CMD_BATTERY_GET -> {
                val bats = EdifierProtocol.parseBattery(pkg.payload)
                if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
            }
            EdifierProtocol.CMD_ANC_MODE -> {
                EdifierProtocol.parseAncMode(pkg.payload)?.let {
                    _budsState.value = _budsState.value.copy(ancMode = it)
                }
            }
            EdifierProtocol.CMD_GAME_MODE_GET,
            EdifierProtocol.CMD_GAME_MODE_SET -> if (pkg.payload.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(gameMode = (pkg.payload[0].toInt() and 0xFF) != 0)
            }
        }
    }

    private fun handleIncomingSoundPeatsPacket(pkg: SoundPeatsProtocol.Packet) {
        val baseCmd = pkg.cmd and 0x7FFF
        val p = pkg.payload
        when (baseCmd) {
            SoundPeatsProtocol.CMD_BAT_LEFT_GET -> if (p.isNotEmpty()) {
                val lvl = p[0].toInt() and 0xFF
                if (lvl in 0..100) updateBatteryState(listOf(BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, false)), isSnapshot = false)
            }
            SoundPeatsProtocol.CMD_BAT_RIGHT_GET -> if (p.isNotEmpty()) {
                val lvl = p[0].toInt() and 0xFF
                if (lvl in 0..100) updateBatteryState(listOf(BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, false)), isSnapshot = false)
            }
            SoundPeatsProtocol.CMD_BAT_BOX_GET -> if (p.isNotEmpty()) {
                val lvl = p[0].toInt() and 0xFF
                if (lvl in 0..100) updateBatteryState(listOf(BudsProtocol.BatteryInfo(ComponentRole.CASE, lvl, false)), isSnapshot = false)
            }
            SoundPeatsProtocol.CMD_ANC_GET, SoundPeatsProtocol.CMD_ANC_SET -> {
                SoundPeatsProtocol.parseAncMode(p)?.let { _budsState.value = _budsState.value.copy(ancMode = it) }
            }
            SoundPeatsProtocol.CMD_GAME_MODE_GET, SoundPeatsProtocol.CMD_GAME_MODE_SET -> if (p.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(gameMode = (p[0].toInt() and 0xFF) != 0)
            }
            SoundPeatsProtocol.CMD_IN_EAR_GET, SoundPeatsProtocol.CMD_IN_EAR_SET -> if (p.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(autoPlayPause = (p[0].toInt() and 0xFF) != 0)
            }
            SoundPeatsProtocol.CMD_MULTIPOINT_GET, SoundPeatsProtocol.CMD_MULTIPOINT_SET -> if (p.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(dualConnection = (p[0].toInt() and 0xFF) != 0)
            }
            SoundPeatsProtocol.CMD_LDAC_GET, SoundPeatsProtocol.CMD_LDAC_SET -> if (p.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(highQualityAudio = (p[0].toInt() and 0xFF) != 0)
            }
        }
    }

    private fun handleIncomingQcyBytes(raw: ByteArray) {
        for (tlv in QcyProtocol.parseNotification(raw)) {
            when (tlv.cmd) {
                QcyProtocol.CMD_BATTERY -> {
                    val bats = QcyProtocol.parseBatteryTriplet(tlv.params)
                    if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
                }
                QcyProtocol.CMD_ANC_MODE -> {
                    QcyProtocol.parseAncMode(tlv.params)?.let { _budsState.value = _budsState.value.copy(ancMode = it) }
                }
                QcyProtocol.CMD_GAME_MODE -> if (tlv.params.isNotEmpty()) {
                    _budsState.value = _budsState.value.copy(gameMode = (tlv.params[0].toInt() and 0xFF) != 0)
                }
                QcyProtocol.CMD_IN_EAR -> if (tlv.params.isNotEmpty()) {
                    _budsState.value = _budsState.value.copy(autoPlayPause = (tlv.params[0].toInt() and 0xFF) == 0x01)
                }
                QcyProtocol.CMD_SPATIAL -> if (tlv.params.isNotEmpty()) {
                    _budsState.value = _budsState.value.copy(spatialAudio = (tlv.params[0].toInt() and 0xFF) != 0)
                }
                QcyProtocol.CMD_LDAC -> if (tlv.params.isNotEmpty()) {
                    _budsState.value = _budsState.value.copy(highQualityAudio = (tlv.params[0].toInt() and 0xFF) != 0)
                }
            }
        }
    }

    private fun handleIncomingBaseusBytes(raw: ByteArray) {
        val frame = BaseusProtocol.decodeFrame(raw) ?: return
        if (frame.isCrc) baseusUseCrc = true
        when (frame.cmd) {
            BaseusProtocol.CMD_BATTERY -> {
                val bats = BaseusProtocol.parseBudsBattery(frame.payload)
                if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = false)
            }
            BaseusProtocol.CMD_CASE_BATTERY -> {
                BaseusProtocol.parseCaseBattery(frame.payload)?.let {
                    updateBatteryState(listOf(it), isSnapshot = false)
                }
            }
            BaseusProtocol.CMD_GAME_MODE -> if (frame.payload.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(gameMode = (frame.payload[0].toInt() and 0xFF) != 0)
            }
            BaseusProtocol.CMD_ANC_TRANSP -> _budsState.value = _budsState.value.copy(ancMode = AncMode.TRANSPARENCY)
            BaseusProtocol.CMD_ANC_ON -> _budsState.value = _budsState.value.copy(ancMode = AncMode.NOISE_REDUCTION)
            BaseusProtocol.CMD_ANC_ACK -> if (frame.payload.isNotEmpty() && frame.payload[0].toInt() == 0) {
                _budsState.value = _budsState.value.copy(ancMode = AncMode.OFF)
            }
        }
    }

    private fun handleIncomingMoondropBytes(raw: ByteArray) {
        val pkt = MoondropProtocol.parseBlePacket(raw) ?: return
        when (pkt.feature) {
            MoondropProtocol.FEAT_ANC -> {
                MoondropProtocol.parseAncMode(pkt.payload)?.let {
                    _budsState.value = _budsState.value.copy(ancMode = it)
                }
            }
            MoondropProtocol.FEAT_CORE -> if (pkt.cmd == MoondropProtocol.CMD_GET_BATTERY && pkt.payload.isNotEmpty()) {
                val lvl = pkt.payload.last().toInt() and 0xFF
                if (lvl in 0..100) {
                    updateBatteryState(listOf(
                        BudsProtocol.BatteryInfo(ComponentRole.LEFT_EAR, lvl, false),
                        BudsProtocol.BatteryInfo(ComponentRole.RIGHT_EAR, lvl, false)
                    ), isSnapshot = false)
                }
            }
        }
    }

    private fun handleIncomingAirohaPacket(pkg: AirohaRaceProtocol.Packet) {
        when (pkg.cmdId) {
            AirohaRaceProtocol.CMD_READ_SDK_VERSION,
            AirohaRaceProtocol.CMD_GET_BUILD_VERSION -> {
                AirohaRaceProtocol.parseAsciiInfo(pkg.payload)?.let { info ->
                    log("RX Airoha RACE chip/SDK: $info")
                    _budsState.value = _budsState.value.copy(
                        isLikelyReplica = true,
                        replicaChipInfo = info
                    )
                }
            }
            AirohaRaceProtocol.CMD_GET_BATTERY -> {
                AirohaRaceProtocol.parseBattery(pkg.payload)?.let { bat ->
                    updateBatteryState(listOf(bat), isSnapshot = false)
                }
            }
            AirohaRaceProtocol.CMD_ANC_PASSTHROUGH -> {
                AirohaRaceProtocol.parseAncMode(pkg.payload)?.let { mode ->
                    _budsState.value = _budsState.value.copy(ancMode = mode)
                }
            }
        }
    }

    private fun handleIncomingHuilianPacket(pkg: HuilianProtocol.Packet) {
        when (pkg.cmd) {
            HuilianProtocol.CMD_MODEL_CODE,
            HuilianProtocol.CMD_MODEL_STR -> {
                HuilianProtocol.parseAscii(pkg.payload)?.let { info ->
                    log("RX Huilian StarFun model: $info")
                    _budsState.value = _budsState.value.copy(
                        isLikelyReplica = true,
                        replicaChipInfo = info,
                        activeReplicaProtocolName = "Huilian StarFun"
                    )
                }
            }
            HuilianProtocol.CMD_BATTERY -> {
                val bats = HuilianProtocol.parseBattery(pkg.payload)
                if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
            }
            HuilianProtocol.CMD_ANC_GET,
            HuilianProtocol.CMD_ANC_SET -> {
                HuilianProtocol.parseAncMode(pkg.payload)?.let { mode ->
                    _budsState.value = _budsState.value.copy(ancMode = mode)
                }
            }
            HuilianProtocol.CMD_GAME_MODE_GET,
            HuilianProtocol.CMD_GAME_MODE_SET -> if (pkg.payload.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(gameMode = (pkg.payload[0].toInt() and 0xFF) != 0)
            }
            HuilianProtocol.CMD_EAR_DETECT_GET,
            HuilianProtocol.CMD_EAR_DETECT_SET -> if (pkg.payload.isNotEmpty()) {
                _budsState.value = _budsState.value.copy(autoPlayPause = (pkg.payload[0].toInt() and 0xFF) != 0)
            }
            HuilianProtocol.CMD_EQ_GET,
            HuilianProtocol.CMD_EQ_SET -> {
                HuilianProtocol.parseEqPreset(pkg.payload)?.let { eq ->
                    _budsState.value = _budsState.value.copy(eqPreset = eq)
                }
            }
        }
    }

    private fun requestJieliFullState() {
        scope.launch {
            sendRawBytes(JieliRcspProtocol.buildGetAdvInfo(nextJieliSeq()))
            delay(80)
            sendRawBytes(JieliRcspProtocol.buildGetSysInfo(nextJieliSeq()))
        }
    }

    private fun handleIncomingJieliItem(item: JieliRcspProtocol.StreamItem) {
        when (item) {
            is JieliRcspProtocol.StreamItem.AuthMsg -> {
                val raw = item.raw
                when (raw[0].toInt() and 0xFF) {
                    0x01 -> {
                        val expected = jieliHostChallenge?.let { JieliRcspProtocol.encryptedChallenge(it) }
                        if (expected != null && raw.contentEquals(expected)) {
                            log("✓ JieLi Auth step 1 verified! Sending PASS...")
                            sendRawBytes(JieliRcspProtocol.AUTH_PASS)
                        } else {
                            log("JieLi Auth: device challenge response mismatch (non-default key?), still sending PASS...")
                            sendRawBytes(JieliRcspProtocol.AUTH_PASS)
                        }
                    }
                    0x00 -> {
                        val resp = JieliRcspProtocol.encryptedChallenge(raw)
                        if (resp != null) {
                            log("✓ JieLi Auth step 2: answering device challenge...")
                            sendRawBytes(resp)
                        }
                    }
                    0x02 -> {
                        if (raw.contentEquals(JieliRcspProtocol.AUTH_PASS)) {
                            jieliAuthDone = true
                            log("✓ JieLi RCSP mutual authentication passed!")
                            _budsState.value = _budsState.value.copy(
                                isLikelyReplica = true,
                                isPassiveBeaconMode = false,
                                activeReplicaAnc = true,
                                activeReplicaGameMode = true,
                                activeReplicaEq = true,
                                activeReplicaCustomEq = true,
                                activeReplicaProtocolName = "JieLi RCSP"
                            )
                            requestJieliFullState()
                        }
                    }
                }
            }
            is JieliRcspProtocol.StreamItem.Rcsp -> {
                handleIncomingJieliPacket(item.packet)
            }
        }
    }

    private fun handleIncomingJieliPacket(pkg: JieliRcspProtocol.Packet) {
        val data = pkg.data
        when (pkg.opcode) {
            JieliRcspProtocol.OP_ADV_GET_INFO -> {
                if (pkg.status != 0) return
                jieliAuthDone = true
                for ((type, value) in JieliRcspProtocol.parseLtvs(data)) {
                    when (type) {
                        JieliRcspProtocol.ADV_BATTERY -> {
                            val bats = JieliRcspProtocol.parseBatteryTriplet(value)
                            if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
                        }
                        JieliRcspProtocol.ADV_NAME -> {
                            val name = String(value.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.UTF_8).trim()
                            if (name.isNotEmpty()) {
                                log("RX JieLi ADV name: $name")
                                _budsState.value = _budsState.value.copy(
                                    isLikelyReplica = true,
                                    replicaChipInfo = "JieLi RCSP ($name)",
                                    activeReplicaProtocolName = "JieLi RCSP"
                                )
                            }
                        }
                        JieliRcspProtocol.ADV_WORK_MODE -> if (value.isNotEmpty()) {
                            val gameOn = (value[0].toInt() and 0xFF) == 1
                            _budsState.value = _budsState.value.copy(gameMode = gameOn)
                        }
                        JieliRcspProtocol.ADV_IN_EAR -> if (value.isNotEmpty()) {
                            _budsState.value = _budsState.value.copy(autoPlayPause = (value[0].toInt() and 0xFF) != 0)
                        }
                    }
                }
            }
            JieliRcspProtocol.OP_ADV_DEVICE_NOTIFY -> {
                if (data.size >= 17) {
                    val bats = JieliRcspProtocol.parseBatteryTriplet(data, offset = 14)
                    if (bats.isNotEmpty()) updateBatteryState(bats, isSnapshot = true)
                }
            }
            JieliRcspProtocol.OP_GET_SYS_INFO,
            JieliRcspProtocol.OP_SYS_INFO_AUTO_UPDATE -> {
                if (data.isEmpty() || (data[0].toInt() and 0xFF) != JieliRcspProtocol.FUNC_PUBLIC) return
                for ((attr, value) in JieliRcspProtocol.parseLtvs(data, startOffset = 1)) {
                    when (attr) {
                        JieliRcspProtocol.ATTR_VOICE_MODE -> if (value.size >= 9) {
                            val modeByte = value[0].toInt() and 0xFF
                            jieliVoiceDetails[modeByte] = value.copyOfRange(0, 9)
                            val anc = when (modeByte) {
                                0 -> AncMode.OFF
                                1 -> AncMode.NOISE_REDUCTION
                                2 -> AncMode.TRANSPARENCY
                                else -> null
                            }
                            if (anc != null) {
                                _budsState.value = _budsState.value.copy(ancMode = anc)
                            }
                        }
                        JieliRcspProtocol.ATTR_ALL_VOICE_MODES -> if (value.isNotEmpty()) {
                            val count = value[0].toInt() and 0xFF
                            var off = 1
                            repeat(count) {
                                if (off + 9 <= value.size) {
                                    val entry = value.copyOfRange(off, off + 9)
                                    jieliVoiceDetails[entry[0].toInt() and 0xFF] = entry
                                    off += 9
                                }
                            }
                        }
                        JieliRcspProtocol.ATTR_EQ -> if (value.isNotEmpty()) {
                            val modeByte = value[0].toInt() and 0xFF
                            val dynamic = (modeByte and 0x80) != 0
                            jieliDynamicEq = dynamic
                            val gains = if (dynamic && value.size >= 2) {
                                val n = (value[1].toInt() and 0xFF).coerceIn(1, 32)
                                if (value.size >= 2 + n) (0 until n).map { value[2 + it].toInt() } else null
                            } else if (!dynamic && value.size >= 11) {
                                (0 until 10).map { value[1 + it].toInt() }
                            } else null
                            if (gains != null) {
                                _budsState.value = _budsState.value.copy(customEqGains = gains)
                            }
                        }
                    }
                }
            }
        }
    }
}
