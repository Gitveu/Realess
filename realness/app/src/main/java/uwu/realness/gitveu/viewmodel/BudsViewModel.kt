package uwu.realness.gitveu.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaPlayer
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import uwu.realness.gitveu.R
import uwu.realness.gitveu.RealnessApp
import uwu.realness.gitveu.audio.FindBudsChannel
import uwu.realness.gitveu.audio.FindBudsPlayer
import uwu.realness.gitveu.ble.ActiveCodecInfo
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.ble.BluetoothCodecHelper
import uwu.realness.gitveu.protocol.HuaweiProtocol
import uwu.realness.gitveu.protocol.BudsProtocol.AncMode
import uwu.realness.gitveu.protocol.BudsProtocol.BatteryInfo
import uwu.realness.gitveu.protocol.BudsProtocol.BudsModel
import uwu.realness.gitveu.protocol.BudsProtocol.ComponentRole
import uwu.realness.gitveu.protocol.BudsProtocol.CustomEqProfile
import uwu.realness.gitveu.protocol.BudsProtocol.EqPreset
import uwu.realness.gitveu.protocol.BudsProtocol.BudsState
import uwu.realness.gitveu.protocol.BudsProtocol.TouchAction
import uwu.realness.gitveu.protocol.BudsProtocol.TouchGesture
import uwu.realness.gitveu.protocol.BudsProtocol.TouchSide
import uwu.realness.gitveu.usb.LogitechUsbManager
import uwu.realness.gitveu.widget.WidgetBluetoothReceiver
import uwu.realness.gitveu.widget.WidgetStateStore

enum class RenameScope {
    FIRMWARE,
    OS_ALIAS,
    NEEDS_CDM_ASSOCIATION,
    LOCAL_ALIAS,
    RESET
}

enum class SystemDeviceStatus {
    NO_PERMISSIONS,
    NO_PAIRED_DEVICE,
    UNSUPPORTED_DEVICE,  // Подключено аудиоустройство, но модель не поддерживается
    DEVICE_DISCONNECTED, // Последнее подключённое устройство сейчас отключено (в кейсе)
    DEVICE_CONNECTED_OS, // Поддерживаемые наушники подключены в Android Bluetooth / USB
    APP_LINK_ACTIVE      // Подключен канал управления (SPP / GATT / USB HID)
}

@SuppressLint("MissingPermission")
class BudsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "realness_prefs"
        private const val KEY_MANUAL_MODEL = "manual_model"
        private const val KEY_LAST_MAC = "last_device_mac"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_LAST_MODEL = "last_device_model"
        private const val KEY_ALIAS_PREFIX = "custom_alias_"
    }

    private val app = application as RealnessApp
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bleManager = app.bleManager
    private val usbManager = app.usbManager
    private val findBudsPlayer = FindBudsPlayer(application)
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private var lastConnectedAddress: String? = null
    private var lastConnectedModel: BudsModel = BudsModel.UNKNOWN
    private var autoReconnectJob: Job? = null
    private var reconnectAttempt = 0

    val usbHeadsetInfo: StateFlow<LogitechUsbManager.UsbHeadsetInfo?> = usbManager.detectedUsbHeadset

    val connectionState: StateFlow<BleConnectionState> = combine(
        bleManager.connectionState,
        usbManager.connected
    ) { bleState, usbConn ->
        if (usbConn) BleConnectionState.CONNECTED else bleState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.DISCONNECTED)

    val budsState: StateFlow<BudsState> = combine(
        bleManager.budsState,
        usbManager.connected,
        usbManager.telemetry
    ) { bleState, usbConn, usbTel ->
        if (usbConn && usbTel != null) {
            val lvl = if (usbTel.online) usbTel.percent else -1
            BudsState(
                leftBattery = BatteryInfo(ComponentRole.LEFT_EAR, lvl, usbTel.charging),
                rightBattery = BatteryInfo(ComponentRole.RIGHT_EAR, lvl, usbTel.charging),
                caseBattery = BatteryInfo(ComponentRole.CASE, -1, false),
                batteryVoltageMv = usbTel.voltageMv,
                batteryHoursLeft = usbTel.hoursLeft,
                sidetoneLevel = usbTel.sidetoneLevel,
                lightsEnabled = usbTel.lightsEnabled,
                inactiveMinutes = usbTel.inactiveMinutes
            )
        } else {
            bleState
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BudsState())

    private val _deviceStatus = MutableStateFlow(SystemDeviceStatus.NO_PAIRED_DEVICE)
    val deviceStatus: StateFlow<SystemDeviceStatus> = _deviceStatus.asStateFlow()

    private val _detectedBudsDevice = MutableStateFlow<BluetoothDevice?>(null)
    val detectedBudsDevice: StateFlow<BluetoothDevice?> = _detectedBudsDevice.asStateFlow()

    private val _autoDetectedModel = MutableStateFlow(BudsModel.UNKNOWN)
    val autoDetectedModel: StateFlow<BudsModel> = _autoDetectedModel.asStateFlow()

    private val _manualModelOverride = MutableStateFlow(
        BudsModel.fromNameOrNull(prefs.getString(KEY_MANUAL_MODEL, null))
    )
    val manualModelOverride: StateFlow<BudsModel?> = _manualModelOverride.asStateFlow()

    private val _detectedModel = MutableStateFlow(
        _manualModelOverride.value ?: BudsModel.fromNameOrNull(prefs.getString(KEY_LAST_MODEL, null)) ?: BudsModel.UNKNOWN
    )
    val detectedModel: StateFlow<BudsModel> = _detectedModel.asStateFlow()

    private val _lastDeviceName = MutableStateFlow<String?>(prefs.getString(KEY_LAST_NAME, null))
    val lastDeviceName: StateFlow<String?> = _lastDeviceName.asStateFlow()

    private val _lastDeviceMac = MutableStateFlow<String?>(prefs.getString(KEY_LAST_MAC, null))
    val lastDeviceMac: StateFlow<String?> = _lastDeviceMac.asStateFlow()

    private val _unsupportedDeviceName = MutableStateFlow<String?>(null)
    val unsupportedDeviceName: StateFlow<String?> = _unsupportedDeviceName.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private var demoPlayer: MediaPlayer? = null
    private val _isPlayingDemo = MutableStateFlow(false)
    val isPlayingDemo: StateFlow<Boolean> = _isPlayingDemo.asStateFlow()

    val findIsPlaying: StateFlow<Boolean> = findBudsPlayer.isPlaying
    val findVolumeProgress: StateFlow<Float> = findBudsPlayer.volumeProgress
    val findChannel: StateFlow<FindBudsChannel> = findBudsPlayer.channel

    private val _customDeviceAlias = MutableStateFlow<String?>(
        prefs.getString(KEY_LAST_MAC, null)?.let { prefs.getString(KEY_ALIAS_PREFIX + it, null) }
    )
    val customDeviceAlias: StateFlow<String?> = _customDeviceAlias.asStateFlow()

    // Last known case battery (level 0..100, timestampMs) for active/last device
    private val _lastCaseBattery = MutableStateFlow<Pair<Int, Long>?>(
        WidgetStateStore.getLastCaseBattery(application, prefs.getString(KEY_LAST_MAC, null))
    )
    val lastCaseBattery: StateFlow<Pair<Int, Long>?> = _lastCaseBattery.asStateFlow()

    // Active A2DP Bluetooth audio codec & bitrate
    private val _activeCodec = MutableStateFlow<ActiveCodecInfo?>(null)
    val activeCodec: StateFlow<ActiveCodecInfo?> = _activeCodec.asStateFlow()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && (isSupportedBuds(device) || _manualModelOverride.value != null)) {
                        val autoModel = BudsModel.fromDeviceName(device.name)
                        val effectiveModel = _manualModelOverride.value ?: autoModel
                        if (effectiveModel.isSupported) {
                            if (bleManager.isConnectedOrConnectingTo(device.address, effectiveModel)) {
                                return
                            }
                            saveLastDevice(device, effectiveModel)
                            _detectedBudsDevice.value = device
                            _autoDetectedModel.value = autoModel
                            _detectedModel.value = effectiveModel
                            _unsupportedDeviceName.value = null
                            _deviceStatus.value = SystemDeviceStatus.DEVICE_CONNECTED_OS
                            connectToDevice(device, effectiveModel)
                            return
                        }
                    }
                    checkAndAutoConnect()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && device.address == lastConnectedAddress) {
                        autoReconnectJob?.cancel()
                        bleManager.disconnect()
                        lastConnectedAddress = null
                    }
                    checkAndAutoConnect()
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    checkAndAutoConnect()
                }
                BluetoothCodecHelper.ACTION_CODEC_CONFIG_CHANGED -> {
                    refreshActiveCodec()
                }
                WidgetBluetoothReceiver.ACTION_BATTERY_LEVEL_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val level = intent.getIntExtra(WidgetBluetoothReceiver.EXTRA_BATTERY_LEVEL, -1)
                    if (device != null && level in 0..100) {
                        bleManager.updateHfpBatteryFromBroadcast(device.address, level)
                    }
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (action) {
                LogitechUsbManager.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    usbManager.onPermissionResult(device, granted)
                    checkAndAutoConnect()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val info = usbManager.scanDevices()
                    if (info != null && !info.hasPermission) {
                        usbManager.requestPermission(info.device)
                    }
                    checkAndAutoConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usbManager.scanDevices()
                    checkAndAutoConnect()
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy as? BluetoothA2dp
                checkAndAutoConnect()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = null
            }
        }
    }

    init {
        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WidgetBluetoothReceiver.ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothCodecHelper.ACTION_CODEC_CONFIG_CHANGED)
        }
        val usbFilter = IntentFilter().apply {
            addAction(LogitechUsbManager.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(bluetoothReceiver, btFilter, Context.RECEIVER_EXPORTED)
            application.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(bluetoothReceiver, btFilter)
            application.registerReceiver(usbReceiver, usbFilter)
        }

        try {
            bluetoothAdapter?.getProfileProxy(application, profileListener, BluetoothProfile.A2DP)
        } catch (_: Exception) {}

        viewModelScope.launch {
            bleManager.logFlow.collect { newLog ->
                _recentLogs.update { (it + newLog).takeLast(100) }
            }
        }

        viewModelScope.launch {
            usbManager.logFlow.collect { newLog ->
                _recentLogs.update { (it + newLog).takeLast(100) }
            }
        }

        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED) {
                    reconnectAttempt = 0
                    autoReconnectJob?.cancel()
                    _deviceStatus.value = SystemDeviceStatus.APP_LINK_ACTIVE
                } else if (_deviceStatus.value == SystemDeviceStatus.APP_LINK_ACTIVE) {
                    _deviceStatus.value = SystemDeviceStatus.DEVICE_CONNECTED_OS
                }
                syncWidgets()
            }
        }

        // Persist last known case battery + sync widgets whenever state changes
        viewModelScope.launch {
            budsState.collect { st ->
                val mac = _detectedBudsDevice.value?.address ?: _lastDeviceMac.value
                if (!mac.isNullOrBlank() && st.caseBattery.level in 0..100) {
                    val now = System.currentTimeMillis()
                    WidgetStateStore.saveLastCaseBattery(app, mac, st.caseBattery.level, now)
                    _lastCaseBattery.value = st.caseBattery.level to now
                }
                syncWidgets()
            }
        }

        // Seamless automatic reconnect on transient RFCOMM drops
        viewModelScope.launch {
            bleManager.unexpectedDisconnectFlow.collect {
                scheduleAutoReconnect()
            }
        }

        // Initial USB scan (works even without Bluetooth permissions!)
        usbManager.scanDevices()
        checkUsbDeviceState()
    }

    private fun syncWidgets() {
        val usb = usbManager.detectedUsbHeadset.value
        val activeName = _customDeviceAlias.value?.takeIf { it.isNotBlank() }
            ?: usb?.displayName
            ?: _detectedBudsDevice.value?.name
            ?: _lastDeviceName.value
        val activeMac = usb?.usbId ?: _detectedBudsDevice.value?.address ?: _lastDeviceMac.value
        val model = _detectedModel.value
        val connected = connectionState.value == BleConnectionState.CONNECTED
        WidgetStateStore.saveSnapshot(
            context = app,
            deviceName = activeName,
            deviceMac = activeMac,
            model = model,
            connected = connected,
            state = budsState.value
        )
        app.updateWidgets()
    }

    private fun scheduleAutoReconnect() {
        if (!_hasPermissions.value) return
        autoReconnectJob?.cancel()
        if (reconnectAttempt >= 2) {
            val activeDev = _detectedBudsDevice.value
            val connectedA2dp = try { a2dpProfile?.connectedDevices ?: emptyList() } catch (_: Exception) { emptyList() }
            if (activeDev != null && connectedA2dp.any { it.address == activeDev.address }) {
                bleManager.connectStandardBluetoothFallback(activeDev, _detectedModel.value)
                return
            }
        }
        val delayMs = when (reconnectAttempt) {
            0 -> 900L
            1 -> 1800L
            2 -> 3200L
            else -> 5000L
        }
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(4)
        autoReconnectJob = viewModelScope.launch {
            delay(delayMs)
            checkAndAutoConnect()
        }
    }

    private fun loadDeviceMetadata(mac: String?) {
        _customDeviceAlias.value = if (mac != null) prefs.getString(KEY_ALIAS_PREFIX + mac, null) else null
        _lastCaseBattery.value = WidgetStateStore.getLastCaseBattery(app, mac)
    }

    private fun saveLastDevice(device: BluetoothDevice, model: BudsModel) {
        val name = device.name ?: model.displayName
        val mac = device.address
        _lastDeviceName.value = name
        _lastDeviceMac.value = mac
        loadDeviceMetadata(mac)
        prefs.edit()
            .putString(KEY_LAST_MAC, mac)
            .putString(KEY_LAST_NAME, name)
            .putString(KEY_LAST_MODEL, model.name)
            .apply()
    }

    private fun saveLastUsbDevice(info: LogitechUsbManager.UsbHeadsetInfo) {
        _lastDeviceName.value = info.displayName
        _lastDeviceMac.value = info.usbId
        loadDeviceMetadata(info.usbId)
        prefs.edit()
            .putString(KEY_LAST_MAC, info.usbId)
            .putString(KEY_LAST_NAME, info.displayName)
            .putString(KEY_LAST_MODEL, info.model.name)
            .apply()
    }

    private fun connectToDevice(device: BluetoothDevice, model: BudsModel) {
        if (bleManager.isConnectedOrConnectingTo(device.address, model)) return
        lastConnectedAddress = device.address
        lastConnectedModel = model
        bleManager.connect(device, model)
    }

    fun selectManualModel(model: BudsModel?) {
        _manualModelOverride.value = model
        prefs.edit().apply {
            if (model == null) remove(KEY_MANUAL_MODEL) else putString(KEY_MANUAL_MODEL, model.name)
        }.apply()

        autoReconnectJob?.cancel()
        reconnectAttempt = 0
        bleManager.disconnect()
        lastConnectedAddress = null
        checkAndAutoConnect()
    }

    fun onPermissionsGranted() {
        _hasPermissions.value = true
        checkAndAutoConnect()
    }

    fun onPermissionsDenied() {
        _hasPermissions.value = false
        if (!checkUsbDeviceState()) {
            _deviceStatus.value = SystemDeviceStatus.NO_PERMISSIONS
        }
    }

    fun requestUsbPermission() {
        usbManager.requestPermission()
    }

    private fun checkUsbDeviceState(): Boolean {
        val usbInfo = usbManager.scanDevices() ?: return false
        saveLastUsbDevice(usbInfo)
        _detectedBudsDevice.value = null
        _autoDetectedModel.value = usbInfo.model
        _detectedModel.value = _manualModelOverride.value ?: usbInfo.model
        _unsupportedDeviceName.value = null
        _deviceStatus.value = if (usbInfo.hasPermission && usbManager.connected.value) {
            SystemDeviceStatus.APP_LINK_ACTIVE
        } else {
            SystemDeviceStatus.DEVICE_CONNECTED_OS
        }
        syncWidgets()
        return true
    }

    fun checkAndAutoConnect() {
        // 0. Check Logitech USB-OTG dongle first (works with or without BT permissions)
        if (checkUsbDeviceState()) {
            return
        }

        if (!_hasPermissions.value) return

        try {
            val connectedA2dp = (a2dpProfile?.connectedDevices ?: emptyList())
                .filter { isSupportedBuds(it) || !BluetoothCodecHelper.isNonHeadsetDevice(it) }
            val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            val manual = _manualModelOverride.value

            // 1. Active supported A2DP headset (or any A2DP headset when manual override is chosen)
            val connectedSupported = connectedA2dp.firstOrNull { isSupportedBuds(it) }
                ?: if (manual != null) connectedA2dp.firstOrNull() else null

            if (connectedSupported != null) {
                val autoModel = BudsModel.fromDeviceName(connectedSupported.name)
                val effectiveModel = manual ?: autoModel
                saveLastDevice(connectedSupported, effectiveModel)

                _detectedBudsDevice.value = connectedSupported
                _autoDetectedModel.value = autoModel
                _detectedModel.value = effectiveModel
                _unsupportedDeviceName.value = null
                refreshActiveCodec(connectedSupported)

                val alreadyActive = bleManager.isConnectedOrConnectingTo(connectedSupported.address, effectiveModel)
                _deviceStatus.value = if (alreadyActive && bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                    SystemDeviceStatus.APP_LINK_ACTIVE
                } else {
                    SystemDeviceStatus.DEVICE_CONNECTED_OS
                }

                if (!alreadyActive) {
                    connectToDevice(connectedSupported, effectiveModel)
                }
                syncWidgets()
                return
            }

            // 2. Unrecognized A2DP Bluetooth audio device -> connect via Standard Bluetooth Battery (HFP + BLE BAS)
            val connectedGeneric = connectedA2dp.firstOrNull()
            if (connectedGeneric != null) {
                val effectiveModel = BudsModel.GENERIC_BT
                saveLastDevice(connectedGeneric, effectiveModel)

                _detectedBudsDevice.value = connectedGeneric
                _autoDetectedModel.value = effectiveModel
                _detectedModel.value = effectiveModel
                _unsupportedDeviceName.value = null
                refreshActiveCodec(connectedGeneric)

                val alreadyActive = bleManager.isConnectedOrConnectingTo(connectedGeneric.address, effectiveModel)
                _deviceStatus.value = if (alreadyActive && bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                    SystemDeviceStatus.APP_LINK_ACTIVE
                } else {
                    SystemDeviceStatus.DEVICE_CONNECTED_OS
                }

                if (!alreadyActive) {
                    connectToDevice(connectedGeneric, effectiveModel)
                }
                syncWidgets()
                return
            }

            // No active A2DP device -> close stale link
            _activeCodec.value = null
            autoReconnectJob?.cancel()
            reconnectAttempt = 0
            if (bleManager.connectionState.value != BleConnectionState.DISCONNECTED) {
                bleManager.disconnect()
            }
            lastConnectedAddress = null

            // 3. No active A2DP device -> show last connected device (with last known case battery!)
            val savedMac = prefs.getString(KEY_LAST_MAC, null)
            val savedName = prefs.getString(KEY_LAST_NAME, null)
            val savedModel = BudsModel.fromNameOrNull(prefs.getString(KEY_LAST_MODEL, null)) ?: BudsModel.UNKNOWN
            val bondedLast = if (savedMac != null) bonded.firstOrNull { it.address == savedMac } else null

            _unsupportedDeviceName.value = null
            if (savedMac != null && savedName != null) {
                _detectedBudsDevice.value = bondedLast
                _lastDeviceName.value = bondedLast?.name ?: savedName
                _lastDeviceMac.value = savedMac
                loadDeviceMetadata(savedMac)
                val autoFromSaved = BudsModel.fromDeviceName(bondedLast?.name ?: savedName).takeIf { it.isSupported } ?: savedModel
                _autoDetectedModel.value = autoFromSaved
                _detectedModel.value = manual ?: autoFromSaved
                _deviceStatus.value = SystemDeviceStatus.DEVICE_DISCONNECTED
            } else {
                _detectedBudsDevice.value = null
                _lastDeviceName.value = null
                _lastDeviceMac.value = null
                _customDeviceAlias.value = null
                _lastCaseBattery.value = null
                _autoDetectedModel.value = BudsModel.UNKNOWN
                _detectedModel.value = manual ?: BudsModel.UNKNOWN
                _deviceStatus.value = SystemDeviceStatus.NO_PAIRED_DEVICE
            }
            syncWidgets()
        } catch (_: Exception) {
            // SecurityException when permissions revoked
        }
    }

    fun refreshActiveCodec(device: BluetoothDevice? = _detectedBudsDevice.value) {
        if (usbManager.connected.value || device == null) {
            _activeCodec.value = null
            return
        }
        _activeCodec.value = BluetoothCodecHelper.readActiveCodec(a2dpProfile, device)
    }

    private fun isSupportedBuds(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return BudsModel.fromDeviceName(device.name).isSupported
    }

    fun manualReconnect() {
        val usb = usbManager.detectedUsbHeadset.value
        if (usb != null) {
            if (!usb.hasPermission) usbManager.requestPermission(usb.device)
            else usbManager.refreshNow()
            return
        }
        val device = _detectedBudsDevice.value ?: return
        val model = _manualModelOverride.value ?: BudsModel.fromDeviceName(device.name)
        if (model.isSupported) {
            connectToDevice(device, model)
        }
    }

    fun setAncMode(mode: AncMode) {
        bleManager.setAncMode(mode)
    }

    fun setAncLevel(level: HuaweiProtocol.AncLevel) {
        bleManager.setAncLevel(level)
    }

    fun setGameMode(enabled: Boolean) {
        bleManager.setGameMode(enabled)
    }

    fun refreshGameMode() {
        bleManager.refreshGameMode()
    }

    fun refreshBattery() {
        if (usbManager.connected.value) usbManager.refreshNow()
        else bleManager.refreshBattery()
    }

    fun refreshAncState() {
        bleManager.refreshAncState()
    }

    fun setEqPreset(preset: EqPreset) {
        bleManager.setEqPreset(preset)
    }

    fun refreshEq() {
        bleManager.refreshEq()
        bleManager.refreshAllEq()
    }

    fun saveCustomEq(
        gains: List<Int>,
        profileId: Int? = null,
        profileName: String? = null,
        createNew: Boolean = false
    ) {
        bleManager.setCustomEq(
            gains = gains,
            profileId = profileId,
            profileName = profileName,
            createNew = createNew,
            delete = false
        )
    }

    fun selectCustomEqProfile(profile: CustomEqProfile) {
        bleManager.selectCustomEqProfile(profile)
    }

    fun deleteCustomEqProfile(profile: CustomEqProfile) {
        bleManager.setCustomEq(
            gains = profile.gains,
            profileId = profile.id,
            profileName = profile.name,
            createNew = false,
            delete = true
        )
    }

    fun resetCustomEq() {
        val spec = _detectedModel.value.customEqSpec() ?: return
        val flat = List(spec.bandLabels.size) { 0 }
        if (_detectedModel.value.isXiaomi) {
            bleManager.setCustomEq(flat, delete = true)
        } else {
            val activeId = budsState.value.activeCustomEqId
            val activeName = budsState.value.activeCustomEqName
            bleManager.setCustomEq(flat, profileId = activeId, profileName = activeName)
        }
    }

    fun startFindBuds(channel: FindBudsChannel = findChannel.value) {
        stopDemoAudio()
        findBudsPlayer.start(channel)
    }

    fun stopFindBuds() {
        findBudsPlayer.stop()
        if (budsState.value.hardwareFindActive) {
            bleManager.setHardwareFindBuds(false, findChannel.value)
        }
    }

    fun setFindChannel(channel: FindBudsChannel) {
        findBudsPlayer.setChannel(channel)
        if (budsState.value.hardwareFindActive && _detectedModel.value.isNothing) {
            bleManager.setHardwareFindBuds(true, channel)
        }
    }

    fun setHardwareFindBuds(enabled: Boolean) {
        bleManager.setHardwareFindBuds(enabled, findChannel.value)
    }

    private fun trySetOsAlias(device: BluetoothDevice, alias: String?): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val res = device.setAlias(alias)
                res == BluetoothStatusCodes.SUCCESS
            } else {
                val m = BluetoothDevice::class.java.getMethod("setAlias", String::class.java)
                val res = m.invoke(device, alias)
                (res == true) || (res == 0)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Renames the currently active or last-known device:
     * 1) Firmware rename if supported (Bose BMAP)
     * 2) Android BluetoothDevice.setAlias() (direct on API 31+; signals NEEDS_CDM_ASSOCIATION if Android 12+ requires CDM approval)
     * 3) Persistent per-MAC custom display alias in realness + widgets
     */
    fun renameCurrentDevice(newName: String): RenameScope? {
        val device = _detectedBudsDevice.value
        val mac = usbManager.detectedUsbHeadset.value?.usbId ?: device?.address ?: _lastDeviceMac.value ?: return null
        val trimmed = newName.trim()

        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_ALIAS_PREFIX + mac).apply()
            _customDeviceAlias.value = null
            if (device != null) {
                trySetOsAlias(device, null)
            }
            syncWidgets()
            return RenameScope.RESET
        }

        val fwRenamed = if (_detectedModel.value.supportsFirmwareRename) {
            bleManager.renameFirmwareDevice(trimmed)
        } else {
            false
        }

        val osAliasUpdated = if (device != null) trySetOsAlias(device, trimmed) else false

        prefs.edit().putString(KEY_ALIAS_PREFIX + mac, trimmed).apply()
        _customDeviceAlias.value = trimmed
        syncWidgets()

        return when {
            fwRenamed -> RenameScope.FIRMWARE
            osAliasUpdated -> RenameScope.OS_ALIAS
            device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RenameScope.NEEDS_CDM_ASSOCIATION
            else -> RenameScope.LOCAL_ALIAS
        }
    }

    /**
     * Called immediately after CompanionDeviceManager grants association on Android 12+,
     * so BluetoothDevice.setAlias() succeeds and updates the real name in Android Bluetooth settings.
     */
    fun completeOsAliasRename(newName: String): RenameScope {
        val device = _detectedBudsDevice.value ?: return RenameScope.LOCAL_ALIAS
        val trimmed = newName.trim()
        val ok = trySetOsAlias(device, trimmed.ifEmpty { null })
        return if (ok) RenameScope.OS_ALIAS else RenameScope.LOCAL_ALIAS
    }

    fun setSpatialAudio(enabled: Boolean) {
        bleManager.setSpatialAudio(enabled)
        if (_isPlayingDemo.value) {
            startDemoAudio()
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        bleManager.setSpatialAudioMode(mode)
    }

    fun refreshSpatialAudio() {
        bleManager.refreshSpatialAudio()
    }

    fun setHighQualityAudio(enabled: Boolean) {
        bleManager.setHighQualityAudio(enabled)
    }

    fun refreshHighQualityAudio() {
        bleManager.refreshHighQualityAudio()
    }

    fun setDynamicAudio(enabled: Boolean) {
        bleManager.setDynamicAudio(enabled)
    }

    fun setBassLevel(level: Int) {
        bleManager.setBassLevel(level)
    }

    fun refreshDynamicAudio() {
        bleManager.refreshDynamicAudio()
    }

    fun setDualConnection(enabled: Boolean) {
        bleManager.setDualConnection(enabled)
    }

    fun refreshDualConnection() {
        bleManager.refreshDualConnection()
    }

    fun refreshMultiConnectInfo() {
        bleManager.refreshMultiConnectInfo()
    }

    fun setAutoPlayPause(enabled: Boolean) {
        bleManager.setAutoPlayPause(enabled)
    }

    fun refreshAutoPlayPause() {
        bleManager.refreshAutoPlayPause()
    }

    fun setAutoAnswer(enabled: Boolean) {
        bleManager.setAutoAnswer(enabled)
    }

    fun refreshAutoAnswer() {
        bleManager.refreshAutoAnswer()
    }

    // Logitech G733 USB-OTG controls
    fun setSidetone(level: Int) {
        usbManager.setG733Sidetone(level)
    }

    fun setLights(enabled: Boolean) {
        usbManager.setG733Lights(enabled)
    }

    fun setInactiveTime(minutes: Int) {
        usbManager.setG733InactiveTime(minutes)
    }

    fun toggleDemoAudio() {
        if (_isPlayingDemo.value) {
            stopDemoAudio()
        } else {
            startDemoAudio()
        }
    }

    fun startDemoAudio() {
        stopDemoAudio()
        val isSpatial = budsState.value.spatialAudio == true
        val resId = if (isSpatial) R.raw.headset_spatial_audio_fix else R.raw.headset_spatial_audio_off
        try {
            demoPlayer = MediaPlayer.create(getApplication(), resId)?.apply {
                isLooping = true
                setOnCompletionListener { _isPlayingDemo.value = false }
                start()
            }
            _isPlayingDemo.value = (demoPlayer != null)
        } catch (_: Exception) {
            _isPlayingDemo.value = false
        }
    }

    fun stopDemoAudio() {
        try {
            demoPlayer?.stop()
            demoPlayer?.release()
        } catch (_: Exception) {}
        demoPlayer = null
        _isPlayingDemo.value = false
    }

    fun setKeySetting(side: TouchSide, gesture: TouchGesture, action: TouchAction) {
        bleManager.setKeySetting(side, gesture, action)
    }

    fun refreshKeySettings() {
        bleManager.refreshKeySettings()
    }

    fun clearLogs() {
        _recentLogs.value = emptyList()
    }

    fun refreshAll() {
        if (usbManager.connected.value) {
            usbManager.refreshNow()
            return
        }
        refreshActiveCodec()
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            val model = _detectedModel.value
            if (model.supportsAnc) bleManager.refreshAncState()
            if (model.supportsGameMode) bleManager.refreshGameMode()
            if (model.supportsSpatialAudio) bleManager.refreshSpatialAudio()
            if (model.supportsHighQualityAudio) bleManager.refreshHighQualityAudio()
            if (model.supportsDynamicAudio) bleManager.refreshDynamicAudio()
            if (model.supportsDualConnection) {
                bleManager.refreshDualConnection()
                bleManager.refreshMultiConnectInfo()
            }
            if (model.supportsAutoPlayPause) bleManager.refreshAutoPlayPause()
            if (model.supportsAutoAnswer) bleManager.refreshAutoAnswer()
            if (model.supportsEq) {
                bleManager.refreshEq()
                bleManager.refreshAllEq()
            }
            if (model.supportsTouchSettings) bleManager.refreshKeySettings()
            if (model.supportsUseTime) bleManager.refreshUseTime()
            bleManager.refreshBattery()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDemoAudio()
        stopFindBuds()
        findBudsPlayer.release()
        try {
            getApplication<Application>().unregisterReceiver(bluetoothReceiver)
            getApplication<Application>().unregisterReceiver(usbReceiver)
            a2dpProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (_: Exception) {}
        // Keep shared bleManager/usbManager alive in RealnessApp so home screen widgets stay responsive
    }
}
