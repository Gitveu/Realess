package uwu.realness.gitveu.widget

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import uwu.realness.gitveu.RealnessApp

/**
 * Manifest-registered Bluetooth receiver that keeps Home Screen Widgets accurate even when
 * MainActivity is closed: clears live earbud battery immediately on disconnect / BT off, and
 * updates single-battery headsets on Android HFP battery level broadcasts.
 */
class WidgetBluetoothReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = RealnessApp.from(context)

        when (action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (WidgetStateStore.markDisconnected(context)) {
                        app.updateWidgets()
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = extractDevice(intent) ?: return
                if (WidgetStateStore.markDisconnected(context, device.address)) {
                    app.updateWidgets()
                }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    val device = extractDevice(intent) ?: return
                    if (WidgetStateStore.markDisconnected(context, device.address)) {
                        app.updateWidgets()
                    }
                }
            }
            ACTION_BATTERY_LEVEL_CHANGED -> {
                val device = extractDevice(intent) ?: return
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                if (level in 0..100 && WidgetStateStore.updateSingleBatteryIfTracked(context, device.address, level)) {
                    app.updateWidgets()
                }
            }
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
