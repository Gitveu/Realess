package uwu.realness.gitveu.widget

import android.content.Context
import uwu.realness.gitveu.R
import uwu.realness.gitveu.protocol.T300Protocol

/**
 * Persistent snapshot store shared between BudsViewModel, RealnessApp, and Home Screen Widgets.
 * Also persists per-MAC last known case battery + timestamp whenever the case reports a live charge.
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
        val model: T300Protocol.BudsModel,
        val connected: Boolean,
        val leftLevel: Int,
        val leftCharging: Boolean,
        val rightLevel: Int,
        val rightCharging: Boolean,
        val caseLevel: Int,
        val caseCharging: Boolean,
        val lastCaseLevel: Int?,
        val lastCaseTimeMs: Long?,
        val ancMode: T300Protocol.AncMode?,
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
        model: T300Protocol.BudsModel,
        connected: Boolean,
        state: T300Protocol.T300State
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_DEVICE_MAC, deviceMac)
            .putString(KEY_MODEL_NAME, model.name)
            .putBoolean(KEY_CONNECTED, connected)
            .putInt(KEY_LEFT_LVL, state.leftBattery.level)
            .putBoolean(KEY_LEFT_CHG, state.leftBattery.isCharging)
            .putInt(KEY_RIGHT_LVL, state.rightBattery.level)
            .putBoolean(KEY_RIGHT_CHG, state.rightBattery.isCharging)
            .putInt(KEY_CASE_LVL, state.caseBattery.level)
            .putBoolean(KEY_CASE_CHG, state.caseBattery.isCharging)
            .putString(KEY_ANC_MODE, state.ancMode?.name)
            .putBoolean(KEY_SUPPORTS_ANC, model.supportsAnc)
            .putBoolean(KEY_SINGLE_BATTERY, model.isSingleBatteryHeadset)

        if (!deviceMac.isNullOrBlank() && state.caseBattery.level in 0..100) {
            editor.putInt(KEY_LAST_CASE_LVL_PREFIX + deviceMac, state.caseBattery.level)
            editor.putLong(KEY_LAST_CASE_TIME_PREFIX + deviceMac, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun loadSnapshot(context: Context): WidgetSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_DEVICE_MAC, null)
        val model = T300Protocol.BudsModel.fromNameOrNull(prefs.getString(KEY_MODEL_NAME, null))
            ?: T300Protocol.BudsModel.UNKNOWN
        val ancName = prefs.getString(KEY_ANC_MODE, null)
        val ancMode = ancName?.let { n -> T300Protocol.AncMode.entries.firstOrNull { it.name == n } }
        val lastCase = getLastCaseBattery(context, mac)

        return WidgetSnapshot(
            deviceName = prefs.getString(KEY_DEVICE_NAME, null),
            deviceMac = mac,
            model = model,
            connected = prefs.getBoolean(KEY_CONNECTED, false),
            leftLevel = prefs.getInt(KEY_LEFT_LVL, -1),
            leftCharging = prefs.getBoolean(KEY_LEFT_CHG, false),
            rightLevel = prefs.getInt(KEY_RIGHT_LVL, -1),
            rightCharging = prefs.getBoolean(KEY_RIGHT_CHG, false),
            caseLevel = prefs.getInt(KEY_CASE_LVL, -1),
            caseCharging = prefs.getBoolean(KEY_CASE_CHG, false),
            lastCaseLevel = lastCase?.first,
            lastCaseTimeMs = lastCase?.second,
            ancMode = ancMode,
            supportsAnc = prefs.getBoolean(KEY_SUPPORTS_ANC, model.supportsAnc),
            isSingleBattery = prefs.getBoolean(KEY_SINGLE_BATTERY, model.isSingleBatteryHeadset)
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
}
