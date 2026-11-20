package com.realme.budst300.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realme.budst300.ble.BleConnectionState
import com.realme.budst300.ble.T300BleManager
import com.realme.budst300.protocol.T300Protocol.AncMode
import com.realme.budst300.protocol.T300Protocol.EqPreset
import com.realme.budst300.protocol.T300Protocol.T300State
import com.realme.budst300.protocol.T300Protocol.TouchSide
import com.realme.budst300.protocol.T300Protocol.TouchGesture
import com.realme.budst300.protocol.T300Protocol.TouchAction
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

    fun setKeySetting(side: TouchSide, gesture: TouchGesture, action: TouchAction) {
        bleManager.setKeySetting(side, gesture, action)
    }

    fun refreshKeySettings() {
        bleManager.refreshKeySettings()
    }

    fun refreshAll() {
        if (connectionState.value == BleConnectionState.CONNECTED) {
            bleManager.refreshAncState()
            bleManager.refreshGameMode()
            bleManager.refreshEq()
            bleManager.refreshKeySettings()
            bleManager.refreshBattery()
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(bluetoothReceiver)
            a2dpProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (_: Exception) {}
        bleManager.disconnect()
    }
}
