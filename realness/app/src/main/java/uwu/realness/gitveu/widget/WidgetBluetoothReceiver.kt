package uwu.realness.gitveu.widget

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import uwu.realness.gitveu.RealnessApp

/**
 * Manifest-registered receiver that keeps Home Screen Widgets automatically updated even when
 * MainActivity is closed:
 * - On Bluetooth connect (ACL / A2DP / HFP): wakes up via goAsync() and fetches fresh battery & ANC
 * - On Bluetooth disconnect / BT off: clears live L/R battery immediately (keeping last known case)
 * - On HFP battery broadcast: updates single-battery headsets immediately
 * - On periodic non-waking widget sync alarm: refreshes battery + relative case timestamp
 */
class WidgetBluetoothReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val ACTION_WIDGET_SYNC = "uwu.realness.gitveu.widget.ACTION_WIDGET_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = RealnessApp.from(context)

        when (action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    app.cancelPeriodicWidgetSync()
                    if (WidgetStateStore.markDisconnected(context)) {
                        app.updateWidgets()
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = extractDevice(intent) ?: return
                if (!app.hasAnyWidgets()) return
                app.runBroadcastSync(goAsync()) {
                    app.syncWidgetOnBluetoothConnected(device)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = extractDevice(intent) ?: return
                if (WidgetStateStore.markDisconnected(context, device.address)) {
                    app.cancelPeriodicWidgetSync()
                    app.updateWidgets()
                }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val device = extractDevice(intent) ?: return
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!app.hasAnyWidgets()) return
                        app.runBroadcastSync(goAsync()) {
                            app.syncWidgetOnBluetoothConnected(device)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (WidgetStateStore.markDisconnected(context, device.address)) {
                            app.cancelPeriodicWidgetSync()
                            app.updateWidgets()
                        }
                    }
                }
            }
            ACTION_BATTERY_LEVEL_CHANGED -> {
                val device = extractDevice(intent) ?: return
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                if (level in 0..100) {
                    app.bleManager.updateHfpBatteryFromBroadcast(device.address, level)
                    if (WidgetStateStore.updateSingleBatteryIfTracked(context, device.address, level)) {
                        app.updateWidgets()
                    }
                }
            }
            ACTION_WIDGET_SYNC -> {
                if (!app.hasAnyWidgets()) {
                    app.cancelPeriodicWidgetSync()
                    return
                }
                app.runBroadcastSync(goAsync()) {
                    app.syncWidgetFromActiveDevice()
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
