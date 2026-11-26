package uwu.realness.gitveu.widget

import android.bluetooth.BluetoothManager
import android.content.Context
import uwu.realness.gitveu.R
import uwu.realness.gitveu.protocol.BudsProtocol

/**
 * Persistent snapshot store shared between BudsViewModel, RealnessApp, and Home Screen Widgets.
 * Persists per-MAC last known case battery + timestamp whenever the case reports a live charge,
 * and ensures disconnected headsets never display stale L/R earbud battery percentages.
 */
object WidgetStateStore {

    private const val PREFS_NAME = "realness_widget_state"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_MAC = "device_mac"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_LEFT_LVL = "left_lvl"
    private const val KEY_LEFT_CHG = "left_chg"
    private const val KEY_RIGHT_LVL = "right_lvl"
    private const val KEY_RIGHT_CHG = "right_chg"
    private const val KEY_CASE_LVL = "case_lvl"
    private const val KEY_CASE_CHG = "case_chg"
    private const val KEY_ANC_MODE = "anc_mode"
    private const val KEY_SUPPORTS_ANC = "supports_anc"
    private const val KEY_SINGLE_BATTERY = "single_battery"
    private const val KEY_LAST_CASE_LVL_PREFIX = "last_case_lvl_"
    private const val KEY_LAST_CASE_TIME_PREFIX = "last_case_time_"

    data class WidgetSnapshot(
        val deviceName: String?,
        val deviceMac: String?,
        val model: BudsProtocol.BudsModel,
        val connected: Boolean,
        val leftLevel: Int,
        val leftCharging: Boolean,
        val rightLevel: Int,
        val rightCharging: Boolean,
        val caseLevel: Int,
        val caseCharging: Boolean,
        val lastCaseLevel: Int?,
        val lastCaseTimeMs: Long?,
        val ancMode: BudsProtocol.AncMode?,
        val supportsAnc: Boolean,
        val isSingleBattery: Boolean
    )

    fun saveLastCaseBattery(context: Context, mac: String, level: Int, nowMs: Long = System.currentTimeMillis()) {
        if (mac.isBlank() || level !in 0..100) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_LAST_CASE_LVL_PREFIX + mac, level)
            .putLong(KEY_LAST_CASE_TIME_PREFIX + mac, nowMs)
            .apply()
    }

    fun getLastCaseBattery(context: Context, mac: String?): Pair<Int, Long>? {
        if (mac.isNullOrBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lvl = prefs.getInt(KEY_LAST_CASE_LVL_PREFIX + mac, -1)
        val ts = prefs.getLong(KEY_LAST_CASE_TIME_PREFIX + mac, 0L)
        return if (lvl in 0..100 && ts > 0L) (lvl to ts) else null
    }

    fun saveSnapshot(
        context: Context,
        deviceName: String?,
        deviceMac: String?,
        model: BudsProtocol.BudsModel,
        connected: Boolean,
        state: BudsProtocol.BudsState
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFallback = state.isStandardBluetoothFallback || model.isGenericBluetooth
        val singleBattery = model.isSingleBatteryHeadset || isFallback
        val supportsAnc = if (isFallback) false else model.supportsAnc

        val editor = prefs.edit()
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_DEVICE_MAC, deviceMac)
            .putString(KEY_MODEL_NAME, model.name)
            .putBoolean(KEY_CONNECTED, connected)
            .putInt(KEY_LEFT_LVL, if (connected) state.leftBattery.level else -1)
            .putBoolean(KEY_LEFT_CHG, connected && state.leftBattery.isCharging)
            .putInt(KEY_RIGHT_LVL, if (connected) state.rightBattery.level else -1)
            .putBoolean(KEY_RIGHT_CHG, connected && state.rightBattery.isCharging)
            .putInt(KEY_CASE_LVL, if (connected && !singleBattery) state.caseBattery.level else -1)
            .putBoolean(KEY_CASE_CHG, connected && !singleBattery && state.caseBattery.isCharging)
            .putString(KEY_ANC_MODE, if (connected && supportsAnc) state.ancMode?.name else null)
            .putBoolean(KEY_SUPPORTS_ANC, supportsAnc)
            .putBoolean(KEY_SINGLE_BATTERY, singleBattery)

        if (connected && !singleBattery && !deviceMac.isNullOrBlank() && state.caseBattery.level in 0..100) {
            editor.putInt(KEY_LAST_CASE_LVL_PREFIX + deviceMac, state.caseBattery.level)
            editor.putLong(KEY_LAST_CASE_TIME_PREFIX + deviceMac, System.currentTimeMillis())
        }
        editor.apply()
    }

    /**
     * Marks the tracked device as disconnected and clears live L/R/Case levels immediately
     * (preserving last-known case battery + timestamp).
     */
    fun markDisconnected(context: Context, macFilter: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(KEY_DEVICE_MAC, null)
        if (macFilter != null && !macFilter.equals(savedMac, ignoreCase = true)) {
            return false
        }
        val wasConnected = prefs.getBoolean(KEY_CONNECTED, false)
        val hadLiveLevels = prefs.getInt(KEY_LEFT_LVL, -1) >= 0 ||
            prefs.getInt(KEY_RIGHT_LVL, -1) >= 0 ||
            prefs.getInt(KEY_CASE_LVL, -1) >= 0
        if (!wasConnected && !hadLiveLevels) return false

        prefs.edit()
            .putBoolean(KEY_CONNECTED, false)
            .putInt(KEY_LEFT_LVL, -1)
            .putBoolean(KEY_LEFT_CHG, false)
            .putInt(KEY_RIGHT_LVL, -1)
            .putBoolean(KEY_RIGHT_CHG, false)
            .putInt(KEY_CASE_LVL, -1)
            .putBoolean(KEY_CASE_CHG, false)
            .apply()
        return true
    }

    fun updateSingleBatteryIfTracked(context: Context, mac: String, level: Int): Boolean {
        if (level !in 0..100) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(KEY_DEVICE_MAC, null)
        if (!mac.equals(savedMac, ignoreCase = true)) return false
        if (!prefs.getBoolean(KEY_CONNECTED, false)) return false
        if (!prefs.getBoolean(KEY_SINGLE_BATTERY, false)) return false
        prefs.edit()
            .putInt(KEY_LEFT_LVL, level)
            .putInt(KEY_RIGHT_LVL, level)
            .apply()
        return true
    }

    fun loadSnapshot(context: Context): WidgetSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_DEVICE_MAC, null)
        val model = BudsProtocol.BudsModel.fromNameOrNull(prefs.getString(KEY_MODEL_NAME, null))
            ?: BudsProtocol.BudsModel.UNKNOWN
        val ancName = prefs.getString(KEY_ANC_MODE, null)
        val ancMode = ancName?.let { n -> BudsProtocol.AncMode.entries.firstOrNull { it.name == n } }
        val lastCase = getLastCaseBattery(context, mac)

        var connected = prefs.getBoolean(KEY_CONNECTED, false)
        // If Bluetooth is turned off, a non-USB headset cannot be connected
        if (connected && mac != null && !mac.startsWith("usb:")) {
            val btEnabled = try {
                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                mgr?.adapter?.isEnabled ?: true
            } catch (_: Throwable) {
                true
            }
            if (!btEnabled) {
                connected = false
            }
        }

        val isSingle = prefs.getBoolean(KEY_SINGLE_BATTERY, model.isSingleBatteryHeadset || model.isGenericBluetooth)
        val supportsAnc = prefs.getBoolean(KEY_SUPPORTS_ANC, model.supportsAnc)

        return WidgetSnapshot(
            deviceName = prefs.getString(KEY_DEVICE_NAME, null),
            deviceMac = mac,
            model = model,
            connected = connected,
            leftLevel = if (connected) prefs.getInt(KEY_LEFT_LVL, -1) else -1,
            leftCharging = connected && prefs.getBoolean(KEY_LEFT_CHG, false),
            rightLevel = if (connected) prefs.getInt(KEY_RIGHT_LVL, -1) else -1,
            rightCharging = connected && prefs.getBoolean(KEY_RIGHT_CHG, false),
            caseLevel = if (connected && !isSingle) prefs.getInt(KEY_CASE_LVL, -1) else -1,
            caseCharging = connected && !isSingle && prefs.getBoolean(KEY_CASE_CHG, false),
            lastCaseLevel = if (isSingle) null else lastCase?.first,
            lastCaseTimeMs = if (isSingle) null else lastCase?.second,
            ancMode = if (connected && supportsAnc) ancMode else null,
            supportsAnc = supportsAnc,
            isSingleBattery = isSingle
        )
    }

    fun formatRelativeTime(context: Context, timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60L -> context.getString(R.string.time_just_now)
            diffSec < 3600L -> context.getString(R.string.time_min_ago, (diffSec / 60L).toInt())
            diffSec < 86_400L -> context.getString(R.string.time_hours_ago, (diffSec / 3600L).toInt())
            else -> context.getString(R.string.time_days_ago, (diffSec / 86_400L).toInt())
        }
    }

    fun formatShortRelativeTime(context: Context, timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60L -> context.getString(R.string.time_short_now)
            diffSec < 3600L -> context.getString(R.string.time_short_min, (diffSec / 60L).toInt())
            diffSec < 86_400L -> context.getString(R.string.time_short_hours, (diffSec / 3600L).toInt())
            else -> context.getString(R.string.time_short_days, (diffSec / 86_400L).toInt())
        }
    }
}
