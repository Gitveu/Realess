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
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.ble.T300BleManager
import uwu.realness.gitveu.protocol.T300Protocol.AncMode
import uwu.realness.gitveu.protocol.T300Protocol.EqPreset
import uwu.realness.gitveu.protocol.T300Protocol.T300State
import uwu.realness.gitveu.protocol.T300Protocol.TouchSide
import uwu.realness.gitveu.protocol.T300Protocol.TouchGesture
import uwu.realness.gitveu.protocol.T300Protocol.TouchAction
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SystemDeviceStatus {
    NO_PERMISSIONS,
    NO_PAIRED_DEVICE,
    DEVICE_DISCONNECTED, // Сопряжено, но сейчас отключено в Bluetooth (в кейсе)
    DEVICE_CONNECTED_OS, // Подключено в Android Bluetooth как аудиоустройство
    APP_LINK_ACTIVE      // Подключен канал управления (SPP/GATT)
}

@SuppressLint("MissingPermission")
class BudsViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = T300BleManager(application)
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var a2dpProfile: BluetoothA2dp? = null

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val t300State: StateFlow<T300State> = bleManager.t300State

    private val _deviceStatus = MutableStateFlow(SystemDeviceStatus.DEVICE_DISCONNECTED)
    val deviceStatus: StateFlow<SystemDeviceStatus> = _deviceStatus.asStateFlow()

    private val _detectedBudsDevice = MutableStateFlow<BluetoothDevice?>(null)
    val detectedBudsDevice: StateFlow<BluetoothDevice?> = _detectedBudsDevice.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private var demoPlayer: MediaPlayer? = null
    private val _isPlayingDemo = MutableStateFlow(false)
    val isPlayingDemo: StateFlow<Boolean> = _isPlayingDemo.asStateFlow()

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
                    if (isBudsT300(device)) {
                        _detectedBudsDevice.value = device
                        _deviceStatus.value = SystemDeviceStatus.DEVICE_CONNECTED_OS
                        bleManager.connect(device!!)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (isBudsT300(device)) {
                        _deviceStatus.value = SystemDeviceStatus.DEVICE_DISCONNECTED
                        bleManager.disconnect()
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    checkAndAutoConnect()
                }
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
        // Регистрация системного ресивера событий Bluetooth
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

        // Логирование протокола
        viewModelScope.launch {
            bleManager.logFlow.collect { newLog ->
                _recentLogs.update { (it + newLog).takeLast(60) }
            }
        }

        // Обновление общего статуса
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == BleConnectionState.CONNECTED) {
                    _deviceStatus.value = SystemDeviceStatus.APP_LINK_ACTIVE
                } else if (_deviceStatus.value == SystemDeviceStatus.APP_LINK_ACTIVE) {
                    _deviceStatus.value = SystemDeviceStatus.DEVICE_CONNECTED_OS
                }
            }
        }
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
     * Автоматическое обнаружение и автоподключение к Buds T300 без ручных действий.
     */
    fun checkAndAutoConnect() {
        if (!_hasPermissions.value) return

        try {
            val bonded = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            val budsDevice = bonded.firstOrNull { isBudsT300(it) }

            if (budsDevice == null) {
                _detectedBudsDevice.value = null
                _deviceStatus.value = SystemDeviceStatus.NO_PAIRED_DEVICE
                return
            }

            _detectedBudsDevice.value = budsDevice

            // Проверяем, подключены ли наушники в системе прямо сейчас (A2DP профиль)
            val isA2dpConnected = a2dpProfile?.connectedDevices?.any { it.address == budsDevice.address } == true

            if (isA2dpConnected) {
                _deviceStatus.value = SystemDeviceStatus.DEVICE_CONNECTED_OS
                // Если наш канал управления ещё не подключён — подключаемся немедленно
                if (bleManager.connectionState.value == BleConnectionState.DISCONNECTED) {
                    bleManager.connect(budsDevice)
                }
            } else {
                _deviceStatus.value = SystemDeviceStatus.DEVICE_DISCONNECTED
                if (bleManager.connectionState.value != BleConnectionState.DISCONNECTED) {
                    bleManager.disconnect()
                }
            }
        } catch (e: Exception) {
            // Исключения безопасности при отсутствии разрешений
        }
    }

    private fun isBudsT300(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val name = device.name ?: ""
        return name.contains("T300", ignoreCase = true) ||
               name.contains("T200", ignoreCase = true) ||
               name.contains("Buds", ignoreCase = true) ||
               name.contains("realme", ignoreCase = true)
    }

    fun manualReconnect() {
        val device = _detectedBudsDevice.value ?: return
        bleManager.connect(device)
    }

    fun setAncMode(mode: AncMode) {
        bleManager.setAncMode(mode)
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
            bleManager.refreshAncState()
            bleManager.refreshGameMode()
            bleManager.refreshSpatialAudio()
            bleManager.refreshHighQualityAudio()
            bleManager.refreshDynamicAudio()
            bleManager.refreshDualConnection()
            bleManager.refreshMultiConnectInfo()
            bleManager.refreshEq()
            bleManager.refreshKeySettings()
            bleManager.refreshBattery()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDemoAudio()
        try {
            getApplication<Application>().unregisterReceiver(bluetoothReceiver)
            a2dpProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (_: Exception) {}
        bleManager.disconnect()
    }
}
