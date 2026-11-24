package uwu.realness.gitveu

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.*
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.ble.T300BleManager
import uwu.realness.gitveu.protocol.T300Protocol
import uwu.realness.gitveu.usb.LogitechUsbManager
import uwu.realness.gitveu.widget.RealnessCompactWidgetProvider
import uwu.realness.gitveu.widget.RealnessSquareWidgetProvider
import uwu.realness.gitveu.widget.RealnessVerticalWidgetProvider
import uwu.realness.gitveu.widget.RealnessWidgetProvider
import uwu.realness.gitveu.widget.WidgetStateStore

class RealnessApp : Application() {

    lateinit var bleManager: T300BleManager
        private set

    lateinit var usbManager: LogitechUsbManager
        private set

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        bleManager = T300BleManager(this)
        usbManager = LogitechUsbManager(this)
    }

    fun updateWidgets() {
        RealnessWidgetProvider.updateAll(this)
        RealnessCompactWidgetProvider.updateAll(this)
        RealnessSquareWidgetProvider.updateAll(this)
        RealnessVerticalWidgetProvider.updateAll(this)
    }

    /**
     * Executes an ANC mode change or refresh triggered directly from a Home Screen Widget.
     * Reuses the live BleManager connection if active; otherwise connects briefly to the
     * saved/bonded headset so widgets work even when the Activity is closed.
     */
    @SuppressLint("MissingPermission")
    suspend fun executeWidgetAction(targetAnc: T300Protocol.AncMode?, cycleAnc: Boolean = false) {
        val snap = WidgetStateStore.loadSnapshot(this)
        val effectiveAnc = when {
            targetAnc != null -> targetAnc
            cycleAnc -> when (snap.ancMode) {
                T300Protocol.AncMode.NOISE_REDUCTION -> T300Protocol.AncMode.TRANSPARENCY
                T300Protocol.AncMode.TRANSPARENCY -> T300Protocol.AncMode.OFF
                else -> T300Protocol.AncMode.NOISE_REDUCTION
            }
            else -> null
        }

        // If USB headset is connected, refresh USB directly
        if (usbManager.connected.value) {
            usbManager.refreshNow()
            delay(400)
            updateWidgets()
            return
        }

        // If Bluetooth link is already active, send directly
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            if (effectiveAnc != null) {
                bleManager.setAncMode(effectiveAnc)
            } else {
                bleManager.refreshBattery()
                bleManager.refreshAncState()
            }
            delay(300)
            persistCurrentBleState()
            updateWidgets()
            return
        }

        // Otherwise try a quick background reconnect to the last-known bonded headset
        val mac = snap.deviceMac
        val model = snap.model
        if (mac.isNullOrBlank() || !model.isSupported) {
            updateWidgets()
            return
        }

        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = btMgr?.adapter ?: return
        val device: BluetoothDevice = try {
            adapter.bondedDevices?.firstOrNull { it.address == mac } ?: adapter.getRemoteDevice(mac)
        } catch (_: Throwable) {
            return
        }

        // Only connect if device is actually connected at ACL/A2DP level or attempt quick SPP connect
        val connectedAlready = try {
            val state = btMgr.getConnectionState(device, BluetoothProfile.GATT)
            state == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) {
            false
        }
        val _unused = connectedAlready

        bleManager.connect(device, model)
        val deadline = System.currentTimeMillis() + 4_500L
        while (System.currentTimeMillis() < deadline && bleManager.connectionState.value != BleConnectionState.CONNECTED) {
            delay(120)
        }
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            delay(250)
            if (effectiveAnc != null) {
                bleManager.setAncMode(effectiveAnc)
                delay(350)
            } else {
                bleManager.refreshBattery()
                bleManager.refreshAncState()
                delay(450)
            }
            persistCurrentBleState()
        }
        updateWidgets()
    }

    private fun persistCurrentBleState() {
        val snap = WidgetStateStore.loadSnapshot(this)
        val st = bleManager.t300State.value
        val connected = bleManager.connectionState.value == BleConnectionState.CONNECTED
        WidgetStateStore.saveSnapshot(
            context = this,
            deviceName = snap.deviceName,
            deviceMac = snap.deviceMac,
            model = snap.model,
            connected = connected,
            state = st
        )
    }

    companion object {
        fun from(context: Context): RealnessApp = context.applicationContext as RealnessApp
    }
}
