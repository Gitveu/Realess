package uwu.realness.gitveu

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.ble.BudsConnectionManager
import uwu.realness.gitveu.overlay.ConnectionPopupManager
import uwu.realness.gitveu.protocol.BudsProtocol
import uwu.realness.gitveu.usb.LogitechUsbManager
import uwu.realness.gitveu.widget.RealnessCompactWidgetProvider
import uwu.realness.gitveu.widget.RealnessMiniWidgetProvider
import uwu.realness.gitveu.widget.RealnessSquareWidgetProvider
import uwu.realness.gitveu.widget.RealnessVerticalWidgetProvider
import uwu.realness.gitveu.widget.RealnessWidgetProvider
import uwu.realness.gitveu.widget.WidgetBluetoothReceiver
import uwu.realness.gitveu.widget.WidgetStateStore
import java.util.concurrent.atomic.AtomicInteger

class RealnessApp : Application() {

    lateinit var bleManager: BudsConnectionManager
        private set

    lateinit var usbManager: LogitechUsbManager
        private set

    lateinit var popupManager: ConnectionPopupManager
        private set

    fun isAppInForeground(): Boolean = startedActivities.get() > 0

    fun shouldHandleBluetoothEvents(): Boolean = hasAnyWidgets() || popupManager.isEnabledAndPermitted()

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncMutex = Mutex()
    private val startedActivities = AtomicInteger(0)
    private val powerManager: PowerManager? by lazy {
        getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    @Volatile private var lastWidgetSig: String? = null
    @Volatile private var lastDisplayedCaseAge: String? = null
    @Volatile private var lastWakeSyncMs: Long = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (!hasAnyWidgets()) return
            when (action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    // Refresh relative case age immediately (0 BT packets), then sync BT if needed
                    refreshCaseAgeIfChanged()
                    val now = System.currentTimeMillis()
                    if (now - lastWakeSyncMs < 6_000L) return
                    lastWakeSyncMs = now
                    appScope.launch {
                        syncWidgetFromActiveDevice()
                    }
                }
                Intent.ACTION_TIME_TICK -> {
                    // Fires once per minute ONLY while screen is ON (zero wakeups during sleep)
                    val screenOn = try { powerManager?.isInteractive != false } catch (_: Throwable) { true }
                    if (!screenOn) return
                    refreshCaseAgeIfChanged()
                    // Catch headsets connected at OS level if Android delayed ACL_CONNECTED
                    if (bleManager.connectionState.value == BleConnectionState.DISCONNECTED) {
                        appScope.launch {
                            if (findOsConnectedAudioDevice() != null) {
                                syncWidgetFromActiveDevice()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BudsConnectionManager(this)
        usbManager = LogitechUsbManager(this)
        popupManager = ConnectionPopupManager(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.incrementAndGet() == 1) {
                    bleManager.setForegroundMode(true)
                    if (!popupManager.isShowingPreview()) {
                        popupManager.dismiss(animate = false)
                    }
                    if (hasAnyWidgets()) {
                        updateWidgets()
                    }
                }
            }
            override fun onActivityStopped(activity: Activity) {
                val remaining = startedActivities.decrementAndGet().coerceAtLeast(0)
                startedActivities.set(remaining)
                if (remaining == 0) {
                    bleManager.setForegroundMode(false)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Process-wide observer: keep Home Screen Widgets live even when MainActivity is closed
        appScope.launch {
            combine(
                bleManager.connectionState,
                bleManager.budsState,
                usbManager.connected,
                usbManager.telemetry
            ) { bleConn, bleSt, usbConn, usbTel ->
                QuadState(bleConn, bleSt, usbConn, usbTel)
            }.collect { q ->
                if (!hasAnyWidgets()) return@collect
                persistAndPushIfChanged(q)
            }
        }

        // Refresh widgets on screen wake, unlock, and 1-min screen-on tick (0 battery when screen is off)
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_TICK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private data class QuadState(
        val bleConn: BleConnectionState,
        val bleSt: BudsProtocol.BudsState,
        val usbConn: Boolean,
        val usbTel: LogitechUsbManager.UsbTelemetry?
    )

    private fun refreshCaseAgeIfChanged() {
        val snap = WidgetStateStore.loadSnapshot(this)
        val ts = snap.lastCaseTimeMs ?: return
        if (snap.caseLevel in 0..100) return
        val age = WidgetStateStore.formatShortRelativeTime(this, ts)
        if (age != lastDisplayedCaseAge) {
            lastDisplayedCaseAge = age
            updateWidgets()
        }
    }

    @SuppressLint("MissingPermission")
    private fun persistAndPushIfChanged(q: QuadState) {
        val snap = WidgetStateStore.loadSnapshot(this)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val usbInfo = usbManager.detectedUsbHeadset.value

        val connected: Boolean
        val mac: String?
        val model: BudsProtocol.BudsModel
        val name: String?
        val state: BudsProtocol.BudsState

        if (q.usbConn && q.usbTel != null) {
            connected = true
            mac = usbInfo?.usbId ?: snap.deviceMac
            model = usbInfo?.model ?: snap.model
            val alias = mac?.let { prefs.getString(KEY_ALIAS_PREFIX + it, null) }?.takeIf { it.isNotBlank() }
            name = alias ?: usbInfo?.displayName ?: snap.deviceName
            val lvl = if (q.usbTel.online) q.usbTel.percent else -1
            state = BudsProtocol.BudsState(
                leftBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.LEFT_EAR, lvl, q.usbTel.charging),
                rightBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.RIGHT_EAR, lvl, q.usbTel.charging),
                caseBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.CASE, -1, false)
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

        val caseAge = snap.lastCaseTimeMs?.let { WidgetStateStore.formatShortRelativeTime(this, it) }
        val sig = buildString {
            append(connected).append('|')
            append(mac).append('|')
            append(name).append('|')
            append(model.name).append('|')
            append(state.leftBattery.level).append(':').append(state.leftBattery.isCharging).append('|')
            append(state.rightBattery.level).append(':').append(state.rightBattery.isCharging).append('|')
            append(state.caseBattery.level).append(':').append(state.caseBattery.isCharging).append('|')
            append(state.ancMode?.name).append('|')
            append(state.isStandardBluetoothFallback).append('|')
            append(caseAge)
        }
        if (sig == lastWidgetSig) return
        lastWidgetSig = sig
        lastDisplayedCaseAge = caseAge

        WidgetStateStore.saveSnapshot(
            context = this,
            deviceName = name,
            deviceMac = mac,
            model = model,
            connected = connected,
            state = state
        )
        updateWidgets()
        schedulePeriodicWidgetSync(connected = connected)
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
        val snap = WidgetStateStore.loadSnapshot(this)
        lastDisplayedCaseAge = snap.lastCaseTimeMs?.let { WidgetStateStore.formatShortRelativeTime(this, it) }
        RealnessWidgetProvider.updateAll(this)
        RealnessCompactWidgetProvider.updateAll(this)
        RealnessSquareWidgetProvider.updateAll(this)
        RealnessVerticalWidgetProvider.updateAll(this)
        RealnessMiniWidgetProvider.updateAll(this)
    }

    fun schedulePeriodicWidgetSync(connected: Boolean = bleManager.connectionState.value == BleConnectionState.CONNECTED) {
        if (!hasAnyWidgets()) {
            cancelPeriodicWidgetSync()
            return
        }
        val snap = WidgetStateStore.loadSnapshot(this)
        if (!connected && snap.lastCaseTimeMs == null) {
            cancelPeriodicWidgetSync()
            return
        }
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = periodicSyncPendingIntent()
            val delayMs = if (connected) PERIODIC_SYNC_CONNECTED_MS else PERIODIC_SYNC_IDLE_CASE_MS
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            // Non-waking ELAPSED_REALTIME one-shot (rescheduled on fire): 0 wakeups during sleep, no 15-min batching floor
            am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
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

    private fun resolveModelForDevice(device: BluetoothDevice): BudsProtocol.BudsModel {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manual = BudsProtocol.BudsModel.fromNameOrNull(prefs.getString(KEY_MANUAL_MODEL, null))
        if (manual != null && manual.isSupported) return manual
        val auto = try {
            BudsProtocol.BudsModel.fromDeviceName(device.name)
        } catch (_: SecurityException) {
            BudsProtocol.BudsModel.UNKNOWN
        }
        if (auto.isSupported) return auto
        // Fallback for renamed headsets: reuse remembered model for this MAC before falling back to GENERIC_BT
        val savedMac = prefs.getString(KEY_LAST_MAC, null)
        if (savedMac != null && savedMac.equals(device.address, ignoreCase = true)) {
            val savedModel = BudsProtocol.BudsModel.fromNameOrNull(prefs.getString(KEY_LAST_MODEL, null))
            if (savedModel != null && savedModel.isSupported) return savedModel
        }
        return BudsProtocol.BudsModel.GENERIC_BT
    }

    fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean =
        isDeviceConnectedAtOsLevel(device)

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
            try { BudsProtocol.BudsModel.fromDeviceName(dev.name).isSupported } catch (_: Throwable) { false }
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

    @SuppressLint("MissingPermission")
    private fun isLikelyAudioDevice(device: BluetoothDevice, fromAudioProfile: Boolean): Boolean {
        if (fromAudioProfile) return true
        val auto = try {
            BudsProtocol.BudsModel.fromDeviceName(device.name)
        } catch (_: SecurityException) {
            BudsProtocol.BudsModel.UNKNOWN
        }
        if (auto.isSupported) return true
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(KEY_LAST_MAC, null)
        if (savedMac != null && savedMac.equals(device.address, ignoreCase = true)) return true
        val majorClass = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        }
        return majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    /**
     * Triggered automatically from WidgetBluetoothReceiver when a Bluetooth headset connects
     * (ACL_CONNECTED / A2DP STATE_CONNECTED / HEADSET STATE_CONNECTED) even if MainActivity is closed.
     */
    @SuppressLint("MissingPermission")
    suspend fun syncWidgetOnBluetoothConnected(
        device: BluetoothDevice,
        fromAudioProfile: Boolean = true
    ) = syncMutex.withLock {
        if (!shouldHandleBluetoothEvents()) return@withLock
        if (!isLikelyAudioDevice(device, fromAudioProfile)) return@withLock

        val widgetsActive = hasAnyWidgets()
        val model = resolveModelForDevice(device)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alias = prefs.getString(KEY_ALIAS_PREFIX + device.address, null)?.takeIf { it.isNotBlank() }
        val devName = alias ?: try { device.name } catch (_: SecurityException) { null } ?: model.displayName

        prefs.edit()
            .putString(KEY_LAST_MAC, device.address)
            .putString(KEY_LAST_NAME, try { device.name } catch (_: SecurityException) { null } ?: devName)
            .putString(KEY_LAST_MODEL, model.name)
            .apply()

        // Show ColorOS/FreeBuds-style connection popup immediately on unlocked screen
        popupManager.showForBluetoothDevice(
            mac = device.address,
            deviceName = devName,
            model = model
        )

        if (bleManager.isConnectedOrConnectingTo(device.address, model)) {
            if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
                bleManager.refreshBattery()
                if (model.supportsAnc) bleManager.refreshAncState()
                delay(450)
                if (widgetsActive) {
                    persistDeviceState(devName, device.address, model)
                    updateWidgets()
                }
            }
            if (widgetsActive) {
                schedulePeriodicWidgetSync(connected = true)
            }
            return@withLock
        }

        delay(900)
        bleManager.connect(device, model)
        waitForInitialTelemetry(timeoutMs = 5_000L)
        if (widgetsActive) {
            persistDeviceState(devName, device.address, model)
            updateWidgets()
            schedulePeriodicWidgetSync(connected = bleManager.connectionState.value == BleConnectionState.CONNECTED)
        }
    }

    /**
     * Silent background refresh triggered by widget onUpdate(), screen wake/unlock, non-waking alarm,
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
            schedulePeriodicWidgetSync(connected = true)
            return@withLock
        }

        // 2. Already connected via BudsConnectionManager -> query fresh battery & ANC
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            val activeDev = bleManager.activeDevice
            if (activeDev != null && !isDeviceConnectedAtOsLevel(activeDev)) {
                bleManager.disconnect()
                WidgetStateStore.markDisconnected(this, activeDev.address)
                lastWidgetSig = null
                updateWidgets()
                schedulePeriodicWidgetSync(connected = false)
                return@withLock
            }
            bleManager.refreshBattery()
            if (bleManager.currentModel.supportsAnc) {
                bleManager.refreshAncState()
            }
            delay(450)
            persistCurrentBleState()
            updateWidgets()
            schedulePeriodicWidgetSync(connected = true)
            return@withLock
        }

        // 3. Check if a bonded audio headset is currently connected at Android OS Bluetooth level
        val osDevice = findOsConnectedAudioDevice()
        if (osDevice == null) {
            WidgetStateStore.markDisconnected(this)
            lastWidgetSig = null
            updateWidgets()
            schedulePeriodicWidgetSync(connected = false)
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
        schedulePeriodicWidgetSync(connected = bleManager.connectionState.value == BleConnectionState.CONNECTED)
    }

    private suspend fun waitForInitialTelemetry(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && bleManager.connectionState.value != BleConnectionState.CONNECTED) {
            delay(120)
        }
        if (bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            val dataDeadline = System.currentTimeMillis() + 1_400L
            while (System.currentTimeMillis() < dataDeadline) {
                val st = bleManager.budsState.value
                if (st.leftBattery.level >= 0 || st.rightBattery.level >= 0 || st.caseBattery.level >= 0) {
                    break
                }
                delay(120)
            }
            val st = bleManager.budsState.value
            if (st.leftBattery.level < 0 && st.rightBattery.level < 0) {
                bleManager.refreshBattery()
                delay(350)
            }
        }
    }

    /**
     * Executes an ANC mode change or refresh triggered directly from a Home Screen Widget.
     */
    @SuppressLint("MissingPermission")
    suspend fun executeWidgetAction(targetAnc: BudsProtocol.AncMode?, cycleAnc: Boolean = false) {
        if (targetAnc == null && !cycleAnc) {
            syncWidgetFromActiveDevice()
            return
        }

        syncMutex.withLock {
            val snap = WidgetStateStore.loadSnapshot(this)
            val effectiveAnc = when {
                targetAnc != null -> targetAnc
                cycleAnc -> when (snap.ancMode) {
                    BudsProtocol.AncMode.NOISE_REDUCTION -> BudsProtocol.AncMode.TRANSPARENCY
                    BudsProtocol.AncMode.TRANSPARENCY -> BudsProtocol.AncMode.OFF
                    else -> BudsProtocol.AncMode.NOISE_REDUCTION
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
                schedulePeriodicWidgetSync(connected = false)
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
                schedulePeriodicWidgetSync(connected = true)
            } else {
                WidgetStateStore.markDisconnected(this)
                lastWidgetSig = null
                schedulePeriodicWidgetSync(connected = false)
            }
            updateWidgets()
        }
    }

    private fun persistDeviceState(name: String?, mac: String?, model: BudsProtocol.BudsModel) {
        val st = bleManager.budsState.value
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
        private const val PERIODIC_SYNC_CONNECTED_MS = 3 * 60 * 1000L
        private const val PERIODIC_SYNC_IDLE_CASE_MS = 5 * 60 * 1000L

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
