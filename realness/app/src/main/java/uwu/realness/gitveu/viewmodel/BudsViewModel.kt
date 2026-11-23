package uwu.realness.gitveu.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uwu.realness.gitveu.R
import uwu.realness.gitveu.audio.FindBudsChannel
import uwu.realness.gitveu.audio.FindBudsPlayer
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.ble.T300BleManager
import uwu.realness.gitveu.protocol.HuaweiProtocol
import uwu.realness.gitveu.protocol.T300Protocol.AncMode
import uwu.realness.gitveu.protocol.T300Protocol.BudsModel
import uwu.realness.gitveu.protocol.T300Protocol.CustomEqProfile
import uwu.realness.gitveu.protocol.T300Protocol.EqPreset
import uwu.realness.gitveu.protocol.T300Protocol.T300State
import uwu.realness.gitveu.protocol.T300Protocol.TouchSide
import uwu.realness.gitveu.protocol.T300Protocol.TouchGesture
import uwu.realness.gitveu.protocol.T300Protocol.TouchAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class RenameScope {
    FIRMWARE,
    OS_ALIAS,
    LOCAL_ALIAS,
    RESET
}

enum class SystemDeviceStatus {
    NO_PERMISSIONS,
    NO_PAIRED_DEVICE,
    UNSUPPORTED_DEVICE,  // Подключено аудиоустройство, но модель не поддерживается
    DEVICE_DISCONNECTED, // Последнее подключённое устройство сейчас отключено (в кейсе)
    DEVICE_CONNECTED_OS, // Поддерживаемые наушники подключены в Android Bluetooth как аудиоустройство
    APP_LINK_ACTIVE      // Подключен канал управления (SPP/GATT)
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

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bleManager = T300BleManager(application)
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

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val t300State: StateFlow<T300State> = bleManager.t300State

    private val _deviceStatus = MutableStateFlow(SystemDeviceStatus.NO_PAIRED_DEVICE)
    val deviceStatus: StateFlow<SystemDeviceStatus> = _deviceStatus.asStateFlow()

    private val _detectedBudsDevice = MutableStateFlow<BluetoothDevice?>(null)
    val detectedBudsDevice: StateFlow<BluetoothDevice?> = _detectedBudsDevice.asStateFlow()

    // Автоматически определённая модель подключённого (или последнего) устройства
    private val _autoDetectedModel = MutableStateFlow(BudsModel.UNKNOWN)
    val autoDetectedModel: StateFlow<BudsModel> = _autoDetectedModel.asStateFlow()

    // Ручной выбор модели из боковой панели (null = Автоматически)
    private val _manualModelOverride = MutableStateFlow(
        BudsModel.fromNameOrNull(prefs.getString(KEY_MANUAL_MODEL, null))
    )
    val manualModelOverride: StateFlow<BudsModel?> = _manualModelOverride.asStateFlow()

    // Активная модель (manualOverride ?: autoDetectedModel)
    private val _detectedModel = MutableStateFlow(
        _manualModelOverride.value ?: BudsModel.fromNameOrNull(prefs.getString(KEY_LAST_MODEL, null)) ?: BudsModel.UNKNOWN
    )
    val detectedModel: StateFlow<BudsModel> = _detectedModel.asStateFlow()

    // Последнее подключённое устройство (сохраняется между запусками, вместо подстановки T300)
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
                            // Never disrupt an active or in-flight connection to the same device & model!
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
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(bluetoothReceiver, filter)
        }

        try {
            bluetoothAdapter?.getProfileProxy(application, profileListener, BluetoothProfile.A2DP)
        } catch (_: Exception) {}

        viewModelScope.launch {
            bleManager.logFlow.collect { newLog ->
                _recentLogs.update { (it + newLog).takeLast(80) }
            }
        }

        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED) {
                    reconnectAttempt = 0
                    autoReconnectJob?.cancel()
                    _deviceStatus.value = SystemDeviceStatus.APP_LINK_ACTIVE
                } else if (_deviceStatus.value == SystemDeviceStatus.APP_LINK_ACTIVE) {
                    _deviceStatus.value = SystemDeviceStatus.DEVICE_CONNECTED_OS
                }
            }
        }

        // Seamless automatic reconnect on transient RFCOMM drops (e.g. TWS L/R master switch)
        viewModelScope.launch {
            bleManager.unexpectedDisconnectFlow.collect {
                scheduleAutoReconnect()
            }
        }
    }

    private fun scheduleAutoReconnect() {
        if (!_hasPermissions.value) return
        autoReconnectJob?.cancel()
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

    private fun loadCustomAlias(mac: String?) {
        _customDeviceAlias.value = if (mac != null) prefs.getString(KEY_ALIAS_PREFIX + mac, null) else null
    }

    private fun saveLastDevice(device: BluetoothDevice, model: BudsModel) {
        val name = device.name ?: model.displayName
        val mac = device.address
        _lastDeviceName.value = name
        _lastDeviceMac.value = mac
        loadCustomAlias(mac)
        prefs.edit()
            .putString(KEY_LAST_MAC, mac)
            .putString(KEY_LAST_NAME, name)
            .putString(KEY_LAST_MODEL, model.name)
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

        // Если сейчас подключено A2DP-устройство, сразу переподключаемся с выбранной моделью/протоколом
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
        _deviceStatus.value = SystemDeviceStatus.NO_PERMISSIONS
    }

    /**
     * Автоматическое обнаружение активного A2DP-устройства.
     * Если ничего не подключено — отображаем ПОСЛЕДНЕЕ подключённое устройство
     * (а не произвольную сопряжённую модель вроде T300).
     */
    fun checkAndAutoConnect() {
        if (!_hasPermissions.value) return

        try {
            val connectedA2dp = a2dpProfile?.connectedDevices ?: emptyList()
            val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            val manual = _manualModelOverride.value

            // 1. Ищем среди активно подключённых по A2DP поддерживаемую модель (или любую при ручном выборе)
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

                val alreadyActive = bleManager.isConnectedOrConnectingTo(connectedSupported.address, effectiveModel)
                _deviceStatus.value = if (alreadyActive && bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                    SystemDeviceStatus.APP_LINK_ACTIVE
                } else {
                    SystemDeviceStatus.DEVICE_CONNECTED_OS
                }

                if (!alreadyActive) {
                    connectToDevice(connectedSupported, effectiveModel)
                }
                return
            }

            // Нет активного поддерживаемого A2DP-устройства -> закрываем старый канал
            autoReconnectJob?.cancel()
            reconnectAttempt = 0
            if (bleManager.connectionState.value != BleConnectionState.DISCONNECTED) {
                bleManager.disconnect()
            }
            lastConnectedAddress = null

            // 2. Подключено другое (неподдерживаемое) аудиоустройство по A2DP
            val connectedUnsupported = connectedA2dp.firstOrNull()
            if (connectedUnsupported != null) {
                _detectedBudsDevice.value = null
                _autoDetectedModel.value = BudsModel.UNKNOWN
                _detectedModel.value = BudsModel.UNKNOWN
                _unsupportedDeviceName.value = connectedUnsupported.name ?: connectedUnsupported.address
                _deviceStatus.value = SystemDeviceStatus.UNSUPPORTED_DEVICE
                return
            }

            // 3. Ничего не подключено по A2DP -> показываем последнее подключённое устройство (если оно было)
            val savedMac = prefs.getString(KEY_LAST_MAC, null)
            val savedName = prefs.getString(KEY_LAST_NAME, null)
            val savedModel = BudsModel.fromNameOrNull(prefs.getString(KEY_LAST_MODEL, null)) ?: BudsModel.UNKNOWN
            val bondedLast = if (savedMac != null) bonded.firstOrNull { it.address == savedMac } else null

            _unsupportedDeviceName.value = null
            if (savedMac != null && savedName != null) {
                _detectedBudsDevice.value = bondedLast
                _lastDeviceName.value = bondedLast?.name ?: savedName
                _lastDeviceMac.value = savedMac
                loadCustomAlias(savedMac)
                val autoFromSaved = BudsModel.fromDeviceName(bondedLast?.name ?: savedName).takeIf { it.isSupported } ?: savedModel
                _autoDetectedModel.value = autoFromSaved
                _detectedModel.value = manual ?: autoFromSaved
                _deviceStatus.value = SystemDeviceStatus.DEVICE_DISCONNECTED
            } else {
                _detectedBudsDevice.value = null
                _lastDeviceName.value = null
                _lastDeviceMac.value = null
                _customDeviceAlias.value = null
                _autoDetectedModel.value = BudsModel.UNKNOWN
                _detectedModel.value = manual ?: BudsModel.UNKNOWN
                _deviceStatus.value = SystemDeviceStatus.NO_PAIRED_DEVICE
            }
        } catch (_: Exception) {
            // Исключения безопасности при отсутствии разрешений
        }
    }

    private fun isSupportedBuds(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return BudsModel.fromDeviceName(device.name).isSupported
    }

    fun manualReconnect() {
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
        bleManager.refreshBattery()
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
            val activeId = t300State.value.activeCustomEqId
            val activeName = t300State.value.activeCustomEqName
            bleManager.setCustomEq(flat, profileId = activeId, profileName = activeName)
        }
    }

    fun startFindBuds(channel: FindBudsChannel = findChannel.value) {
        stopDemoAudio()
        findBudsPlayer.start(channel)
    }

    fun stopFindBuds() {
        findBudsPlayer.stop()
        if (t300State.value.hardwareFindActive) {
            bleManager.setHardwareFindBuds(false, findChannel.value)
        }
    }

    fun setFindChannel(channel: FindBudsChannel) {
        findBudsPlayer.setChannel(channel)
        if (t300State.value.hardwareFindActive && _detectedModel.value.isNothing) {
            bleManager.setHardwareFindBuds(true, channel)
        }
    }

    fun setHardwareFindBuds(enabled: Boolean) {
        bleManager.setHardwareFindBuds(enabled, findChannel.value)
    }

    /**
     * Renames the currently active or last-known device:
     * 1) Firmware rename if supported (Bose BMAP)
     * 2) Android BluetoothDevice.setAlias() if permitted by OS
     * 3) Persistent per-MAC custom display alias in realness
     */
    fun renameCurrentDevice(newName: String): RenameScope? {
        val device = _detectedBudsDevice.value
        val mac = device?.address ?: _lastDeviceMac.value ?: return null
        val trimmed = newName.trim()

        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_ALIAS_PREFIX + mac).apply()
            _customDeviceAlias.value = null
            return RenameScope.RESET
        }

        val fwRenamed = if (_detectedModel.value.supportsFirmwareRename) {
            bleManager.renameFirmwareDevice(trimmed)
        } else {
            false
        }

        var osAliasUpdated = false
        if (device != null) {
            try {
                val m = BluetoothDevice::class.java.getMethod("setAlias", String::class.java)
                val res = m.invoke(device, trimmed)
                osAliasUpdated = (res == true) || (res == 0)
            } catch (_: Throwable) {
                osAliasUpdated = false
            }
        }

        prefs.edit().putString(KEY_ALIAS_PREFIX + mac, trimmed).apply()
        _customDeviceAlias.value = trimmed

        return when {
            fwRenamed -> RenameScope.FIRMWARE
            osAliasUpdated -> RenameScope.OS_ALIAS
            else -> RenameScope.LOCAL_ALIAS
        }
    }

    fun setSpatialAudio(enabled: Boolean) {
        bleManager.setSpatialAudio(enabled)
        if (_isPlayingDemo.value) {
            startDemoAudio()
        }
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

    fun toggleDemoAudio() {
        if (_isPlayingDemo.value) {
            stopDemoAudio()
        } else {
            startDemoAudio()
        }
    }

    fun startDemoAudio() {
        stopDemoAudio()
        val isSpatial = t300State.value.spatialAudio == true
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
        if (connectionState.value == BleConnectionState.CONNECTED) {
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
            a2dpProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (_: Exception) {}
        bleManager.disconnect()
    }
}
