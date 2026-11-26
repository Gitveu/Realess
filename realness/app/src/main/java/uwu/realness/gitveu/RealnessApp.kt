package uwu.realness.gitveu

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.ble.T300BleManager
import uwu.realness.gitveu.protocol.T300Protocol
import uwu.realness.gitveu.usb.LogitechUsbManager
import uwu.realness.gitveu.widget.RealnessCompactWidgetProvider
import uwu.realness.gitveu.widget.RealnessMiniWidgetProvider
import uwu.realness.gitveu.widget.RealnessSquareWidgetProvider
import uwu.realness.gitveu.widget.RealnessVerticalWidgetProvider
import uwu.realness.gitveu.widget.RealnessWidgetProvider
import uwu.realness.gitveu.widget.WidgetBluetoothReceiver
import uwu.realness.gitveu.widget.WidgetStateStore

class RealnessApp : Application() {

    lateinit var bleManager: T300BleManager
        private set

    lateinit var usbManager: LogitechUsbManager
        private set

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncMutex = Mutex()
    @Volatile private var lastWidgetSig: String? = null
    @Volatile private var lastUnlockSyncMs: Long = 0L

    private val screenUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            if (!hasAnyWidgets()) return
            val now = System.currentTimeMillis()
            if (now - lastUnlockSyncMs < 15_000L) return
            lastUnlockSyncMs = now
            appScope.launch {
                syncWidgetFromActiveDevice()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = T300BleManager(this)
        usbManager = LogitechUsbManager(this)

        // Process-wide observer: keep Home Screen Widgets live even when MainActivity is closed
        appScope.launch {
            combine(
                bleManager.connectionState,
                bleManager.t300State,
                usbManager.connected,
                usbManager.telemetry
            ) { bleConn, bleSt, usbConn, usbTel ->
                QuadState(bleConn, bleSt, usbConn, usbTel)
            }.collect { q ->
                if (!hasAnyWidgets()) return@collect
                persistAndPushIfChanged(q)
            }
        }

        // Refresh widgets silently on screen unlock while process is alive
        try {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenUnlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenUnlockReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private data class QuadState(
        val bleConn: BleConnectionState,
        val bleSt: T300Protocol.T300State,
        val usbConn: Boolean,
        val usbTel: LogitechUsbManager.UsbTelemetry?
    )

    @SuppressLint("MissingPermission")
    private fun persistAndPushIfChanged(q: QuadState) {
        val snap = WidgetStateStore.loadSnapshot(this)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val usbInfo = usbManager.detectedUsbHeadset.value

        val connected: Boolean
        val mac: String?
        val model: T300Protocol.BudsModel
        val name: String?
        val state: T300Protocol.T300State

        if (q.usbConn && q.usbTel != null) {
            connected = true
            mac = usbInfo?.usbId ?: snap.deviceMac
            model = usbInfo?.model ?: snap.model
            val alias = mac?.let { prefs.getString(KEY_ALIAS_PREFIX + it, null) }?.takeIf { it.isNotBlank() }
            name = alias ?: usbInfo?.displayName ?: snap.deviceName
            val lvl = if (q.usbTel.online) q.usbTel.percent else -1
            state = T300Protocol.T300State(
                leftBattery = T300Protocol.BatteryInfo(T300Protocol.ComponentRole.LEFT_EAR, lvl, q.usbTel.charging),
                rightBattery = T300Protocol.BatteryInfo(T300Protocol.ComponentRole.RIGHT_EAR, lvl, q.usbTel.charging),
                caseBattery = T300Protocol.BatteryInfo(T300Protocol.ComponentRole.CASE, -1, false)
            )
        } else {
            connected = q.bleConn == BleConnectionState.CONNECTED
            val dev = bleManager.activeDevice
            mac = dev?.address ?: snap.deviceMac
            model = bleManager.currentModel.takeIf { it.isSupported } ?: snap.model
            val alias = mac?.let { prefs.getString(KEY_ALIAS_PREFIX + it, null) }?.takeIf { it.isNotBlank() }
            val btName = try { dev?.name } catch (_: SecurityException) { null }
            name = alias ?: btName ?: snap.deviceName
            state = q.bleSt
        }

        val sig = buildString {
            append(connected).append('|')
            append(mac).append('|')
            append(name).append('|')
            append(model.name).append('|')
            append(state.leftBattery.level).append(':').append(state.leftBattery.isCharging).append('|')
            append(state.rightBattery.level).append(':').append(state.rightBattery.isCharging).append('|')
            append(state.caseBattery.level).append(':').append(state.caseBattery.isCharging).append('|')
            append(state.ancMode?.name).append('|')
            append(state.isStandardBluetoothFallback)
        }
        if (sig == lastWidgetSig) return
        lastWidgetSig = sig

        WidgetStateStore.saveSnapshot(
            context = this,
            deviceName = name,
            deviceMac = mac,
            model = model,
            connected = connected,
            state = state
        )
        updateWidgets()

        if (connected) {
            schedulePeriodicWidgetSync()
        }
    }

    fun hasAnyWidgets(): Boolean {
        val mgr = AppWidgetManager.getInstance(this)
        return WIDGET_PROVIDERS.any { cls ->
            mgr.getAppWidgetIds(ComponentName(this, cls)).isNotEmpty()
        }
    }

    fun runBroadcastSync(
        pendingResult: BroadcastReceiver.PendingResult?,
        timeoutMs: Long = 8_000L,
        block: suspend () -> Unit
    ) {
        appScope.launch {
            try {
                withTimeoutOrNull(timeoutMs) {
                    block()
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Throwable) {}
            }
        }
    }

    fun updateWidgets() {
        RealnessWidgetProvider.updateAll(this)
        RealnessCompactWidgetProvider.updateAll(this)
        RealnessSquareWidgetProvider.updateAll(this)
        RealnessVerticalWidgetProvider.updateAll(this)
        RealnessMiniWidgetProvider.updateAll(this)
    }

    fun schedulePeriodicWidgetSync() {
        if (!hasAnyWidgets()) {
            cancelPeriodicWidgetSync()
            return
        }
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = periodicSyncPendingIntent()
            val triggerAt = SystemClock.elapsedRealtime() + PERIODIC_SYNC_INTERVAL_MS
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                triggerAt,
                PERIODIC_SYNC_INTERVAL_MS,
                pi
            )
        } catch (_: Throwable) {}
    }

    fun cancelPeriodicWidgetSync() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(periodicSyncPendingIntent())
        } catch (_: Throwable) {}
    }

    private fun periodicSyncPendingIntent(): PendingIntent {
        val intent = Intent(this, WidgetBluetoothReceiver::class.java).apply {
            action = WidgetBluetoothReceiver.ACTION_WIDGET_SYNC
        }
        return PendingIntent.getBroadcast(
            this,
            910,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resolveModelForDevice(device: BluetoothDevice): T300Protocol.BudsModel {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manual = T300Protocol.BudsModel.fromNameOrNull(prefs.getString(KEY_MANUAL_MODEL, null))
        if (manual != null && manual.isSupported) return manual
        val auto = try {
            T300Protocol.BudsModel.fromDeviceName(device.name)
        } catch (_: SecurityException) {
            T300Protocol.BudsModel.UNKNOWN
        }
        return if (auto.isSupported) auto else T300Protocol.BudsModel.GENERIC_BT
    }

    private fun isDeviceConnectedAtOsLevel(device: BluetoothDevice): Boolean {
        return try {
            val m = BluetoothDevice::class.java.getMethod("isConnected")
            (m.invoke(device) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun findOsConnectedAudioDevice(): BluetoothDevice? {
        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        val adapter = btMgr.adapter ?: return null
        if (!adapter.isEnabled) return null

        val bonded = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            return null
        }
        if (bonded.isEmpty()) return null

        val snap = WidgetStateStore.loadSnapshot(this)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preferredMac = snap.deviceMac?.takeUnless { it.startsWith("usb:") }
            ?: prefs.getString(KEY_LAST_MAC, null)?.takeUnless { it.startsWith("usb:") }

        // Check preferred/last-used headset first
        if (!preferredMac.isNullOrBlank()) {
            val preferred = bonded.firstOrNull { it.address.equals(preferredMac, ignoreCase = true) }
            if (preferred != null && isDeviceConnectedAtOsLevel(preferred)) {
                return preferred
            }
        }

        // Next prefer any connected recognized audio headset
        val connectedBonded = bonded.filter { isDeviceConnectedAtOsLevel(it) }
        val connectedSupported = connectedBonded.firstOrNull { dev ->
            try { T300Protocol.BudsModel.fromDeviceName(dev.name).isSupported } catch (_: Throwable) { false }
        }
        if (connectedSupported != null) return connectedSupported

        // Finally any connected audio device (A2DP/HFP active)
        val a2dpActive = try {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) { false }
        if (a2dpActive) {
            return connectedBonded.firstOrNull()
        }
        return null
    }

    /**
     * Triggered automatically from WidgetBluetoothReceiver when a Bluetooth headset connects
     * (ACL_CONNECTED / A2DP STATE_CONNECTED / HEADSET STATE_CONNECTED) even if MainActivity is closed.
     */
    @SuppressLint("MissingPermission")
    suspend fun syncWidgetOnBluetoothConnected(device: BluetoothDevice) = syncMutex.withLock {
        if (!hasAnyWidgets()) return@withLock
        val model = resolveModelForDevice(device)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alias = prefs.getString(KEY_ALIAS_PREFIX + device.address, null)?.takeIf { it.isNotBlank() }
        val devName = alias ?: try { device.name } catch (_: SecurityException) { null } ?: model.displayName

        // Save device identity immediately so persistCurrentBleState uses the right MAC & model
        prefs.edit()
            .putString(KEY_LAST_MAC, device.address)
            .putString(KEY_LAST_NAME, try { device.name } catch (_: SecurityException) { null } ?: devName)
            .putString(KEY_LAST_MODEL, model.name)
            .apply()

        if (bleManager.isConnectedOrConnectingTo(device.address, model)) {
            if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                bleManager.refreshBattery()
                if (model.supportsAnc) bleManager.refreshAncState()
                delay(450)
                persistDeviceState(devName, device.address, model)
                updateWidgets()
            }
            schedulePeriodicWidgetSync()
            return@withLock
        }

        // Brief settle delay so headset RFCOMM/GATT stack is ready after ACL/A2DP connect
        delay(900)
        bleManager.connect(device, model)
        waitForInitialTelemetry(timeoutMs = 5_000L)
        persistDeviceState(devName, device.address, model)
        updateWidgets()
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            schedulePeriodicWidgetSync()
        }
    }

    /**
     * Silent background refresh triggered by widget onUpdate(), screen unlock, 5-min non-waking alarm,
     * or tapping the Case panel on a widget.
     */
    @SuppressLint("MissingPermission")
    suspend fun syncWidgetFromActiveDevice() = syncMutex.withLock {
        if (!hasAnyWidgets()) {
            cancelPeriodicWidgetSync()
            return@withLock
        }

        // 1. Live USB headset
        if (usbManager.connected.value) {
            usbManager.refreshNow()
            delay(450)
            updateWidgets()
            schedulePeriodicWidgetSync()
            return@withLock
        }

        // 2. Already connected via BleManager -> query fresh battery & ANC
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            val activeDev = bleManager.activeDevice
            if (activeDev != null && !isDeviceConnectedAtOsLevel(activeDev)) {
                bleManager.disconnect()
                WidgetStateStore.markDisconnected(this, activeDev.address)
                lastWidgetSig = null
                updateWidgets()
                cancelPeriodicWidgetSync()
                return@withLock
            }
            bleManager.refreshBattery()
            if (bleManager.currentModel.supportsAnc) {
                bleManager.refreshAncState()
            }
            delay(450)
            persistCurrentBleState()
            updateWidgets()
            schedulePeriodicWidgetSync()
            return@withLock
        }

        // 3. Check if a bonded audio headset is currently connected at Android OS Bluetooth level
        val osDevice = findOsConnectedAudioDevice()
        if (osDevice == null) {
            // No headset connected -> ensure stale L/R levels are cleared and refresh relative case timestamp
            WidgetStateStore.markDisconnected(this)
            lastWidgetSig = null
            updateWidgets()
            cancelPeriodicWidgetSync()
            return@withLock
        }

        val model = resolveModelForDevice(osDevice)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alias = prefs.getString(KEY_ALIAS_PREFIX + osDevice.address, null)?.takeIf { it.isNotBlank() }
        val devName = alias ?: try { osDevice.name } catch (_: SecurityException) { null } ?: model.displayName

        bleManager.connect(osDevice, model)
        waitForInitialTelemetry(timeoutMs = 5_000L)
        persistDeviceState(devName, osDevice.address, model)
        updateWidgets()
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            schedulePeriodicWidgetSync()
        }
    }

    private suspend fun waitForInitialTelemetry(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && bleManager.connectionState.value != BleConnectionState.CONNECTED) {
            delay(120)
        }
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            // Wait briefly for initial battery packet to arrive (up to ~1.4s)
            val dataDeadline = System.currentTimeMillis() + 1_400L
            while (System.currentTimeMillis() < dataDeadline) {
                val st = bleManager.t300State.value
                if (st.leftBattery.level >= 0 || st.rightBattery.level >= 0 || st.caseBattery.level >= 0) {
                    break
                }
                delay(120)
            }
            // One explicit refresh if still empty
            val st = bleManager.t300State.value
            if (st.leftBattery.level < 0 && st.rightBattery.level < 0) {
                bleManager.refreshBattery()
                delay(350)
            }
        }
    }

    /**
     * Executes an ANC mode change or refresh triggered directly from a Home Screen Widget.
     * Reuses the live BleManager connection if active; otherwise connects briefly to the
     * active/saved bonded headset so widgets work even when the Activity is closed.
     */
    @SuppressLint("MissingPermission")
    suspend fun executeWidgetAction(targetAnc: T300Protocol.AncMode?, cycleAnc: Boolean = false) {
        if (targetAnc == null && !cycleAnc) {
            syncWidgetFromActiveDevice()
            return
        }

        syncMutex.withLock {
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

            if (usbManager.connected.value) {
                usbManager.refreshNow()
                delay(400)
                updateWidgets()
                return@withLock
            }

            if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                if (effectiveAnc != null) {
                    bleManager.setAncMode(effectiveAnc)
                }
                bleManager.refreshBattery()
                delay(350)
                persistCurrentBleState()
                updateWidgets()
                return@withLock
            }

            // Try OS-connected device first, then saved MAC
            val targetDevice: BluetoothDevice? = findOsConnectedAudioDevice() ?: run {
                val mac = snap.deviceMac
                if (mac.isNullOrBlank() || mac.startsWith("usb:")) return@run null
                val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter: BluetoothAdapter = btMgr?.adapter ?: return@run null
                try {
                    adapter.bondedDevices?.firstOrNull { it.address.equals(mac, ignoreCase = true) }
                        ?: adapter.getRemoteDevice(mac)
                } catch (_: Throwable) {
                    null
                }
            }

            if (targetDevice == null) {
                WidgetStateStore.markDisconnected(this)
                lastWidgetSig = null
                updateWidgets()
                return@withLock
            }

            val model = resolveModelForDevice(targetDevice)
            if (!model.isSupported) {
                updateWidgets()
                return@withLock
            }

            bleManager.connect(targetDevice, model)
            waitForInitialTelemetry(timeoutMs = 4_500L)
            if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                if (effectiveAnc != null && model.supportsAnc) {
                    bleManager.setAncMode(effectiveAnc)
                    delay(350)
                }
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val alias = prefs.getString(KEY_ALIAS_PREFIX + targetDevice.address, null)?.takeIf { it.isNotBlank() }
                val devName = alias ?: try { targetDevice.name } catch (_: SecurityException) { null } ?: model.displayName
                persistDeviceState(devName, targetDevice.address, model)
                schedulePeriodicWidgetSync()
            } else {
                WidgetStateStore.markDisconnected(this)
                lastWidgetSig = null
            }
            updateWidgets()
        }
    }

    private fun persistDeviceState(name: String?, mac: String?, model: T300Protocol.BudsModel) {
        val st = bleManager.t300State.value
        val connected = bleManager.connectionState.value == BleConnectionState.CONNECTED
        lastWidgetSig = null
        WidgetStateStore.saveSnapshot(
            context = this,
            deviceName = name,
            deviceMac = mac,
            model = model,
            connected = connected,
            state = st
        )
    }

    private fun persistCurrentBleState() {
        val snap = WidgetStateStore.loadSnapshot(this)
        val dev = bleManager.activeDevice
        val mac = dev?.address ?: snap.deviceMac
        val model = bleManager.currentModel.takeIf { it.isSupported } ?: snap.model
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alias = mac?.let { prefs.getString(KEY_ALIAS_PREFIX + it, null) }?.takeIf { it.isNotBlank() }
        val name = alias ?: try { dev?.name } catch (_: SecurityException) { null } ?: snap.deviceName
        persistDeviceState(name, mac, model)
    }

    companion object {
        private const val PREFS_NAME = "realness_prefs"
        private const val KEY_MANUAL_MODEL = "manual_model"
        private const val KEY_LAST_MAC = "last_device_mac"
        private const val KEY_LAST_NAME = "last_device_name"
        private const val KEY_LAST_MODEL = "last_device_model"
        private const val KEY_ALIAS_PREFIX = "custom_alias_"
        private const val PERIODIC_SYNC_INTERVAL_MS = 5 * 60 * 1000L

        private val WIDGET_PROVIDERS = listOf(
            RealnessWidgetProvider::class.java,
            RealnessCompactWidgetProvider::class.java,
            RealnessSquareWidgetProvider::class.java,
            RealnessVerticalWidgetProvider::class.java,
            RealnessMiniWidgetProvider::class.java
        )

        fun from(context: Context): RealnessApp = context.applicationContext as RealnessApp
    }
}
