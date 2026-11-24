package uwu.realness.gitveu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uwu.realness.gitveu.R
import uwu.realness.gitveu.RealnessApp
import uwu.realness.gitveu.protocol.T300Protocol
import uwu.realness.gitveu.ui.MainActivity

class RealnessWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ANC_NR = "uwu.realness.gitveu.widget.ACTION_ANC_NR"
        const val ACTION_ANC_OFF = "uwu.realness.gitveu.widget.ACTION_ANC_OFF"
        const val ACTION_ANC_TR = "uwu.realness.gitveu.widget.ACTION_ANC_TR"
        const val ACTION_REFRESH = "uwu.realness.gitveu.widget.ACTION_REFRESH"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RealnessWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        private fun actionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, RealnessWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun buildViews(context: Context): RemoteViews {
            val snap = WidgetStateStore.loadSnapshot(context)
            val rv = RemoteViews(context.packageName, R.layout.widget_realness)

            // Tap root opens MainActivity
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 100, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, openPi)

            // Header
            val title = snap.deviceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
            val status = if (snap.connected) {
                context.getString(R.string.status_connected)
            } else if (snap.deviceMac != null) {
                context.getString(R.string.status_disconnected)
            } else {
                context.getString(R.string.widget_no_device)
            }
            rv.setTextViewText(R.id.widget_device_name, title)
            rv.setTextViewText(R.id.widget_status_text, status)
            rv.setOnClickPendingIntent(R.id.widget_refresh_btn, actionPendingIntent(context, ACTION_REFRESH, 104))

            // Battery cards
            if (snap.isSingleBattery) {
                rv.setTextViewText(R.id.widget_left_label, context.getString(R.string.battery_headset))
                val lvl = snap.leftLevel
                val txt = if (lvl in 0..100) "${lvl}%${if (snap.leftCharging) " +" else ""}" else "—"
                rv.setTextViewText(R.id.widget_left_value, txt)
                rv.setProgressBar(R.id.widget_left_bar, 100, lvl.coerceIn(0, 100), false)
                rv.setViewVisibility(R.id.widget_bat_case_card, View.GONE)
                rv.setViewVisibility(R.id.widget_bat_right_card, View.GONE)
            } else {
                rv.setViewVisibility(R.id.widget_bat_case_card, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_bat_right_card, View.VISIBLE)
                rv.setTextViewText(R.id.widget_left_label, context.getString(R.string.battery_left))
                rv.setTextViewText(R.id.widget_right_label, context.getString(R.string.battery_right))
                rv.setTextViewText(R.id.widget_case_label, context.getString(R.string.battery_case))

                val lTxt = if (snap.leftLevel in 0..100) "${snap.leftLevel}%${if (snap.leftCharging) " +" else ""}" else "—"
                val rTxt = if (snap.rightLevel in 0..100) "${snap.rightLevel}%${if (snap.rightCharging) " +" else ""}" else "—"
                rv.setTextViewText(R.id.widget_left_value, lTxt)
                rv.setProgressBar(R.id.widget_left_bar, 100, snap.leftLevel.coerceIn(0, 100), false)
                rv.setTextViewText(R.id.widget_right_value, rTxt)
                rv.setProgressBar(R.id.widget_right_bar, 100, snap.rightLevel.coerceIn(0, 100), false)

                // Case: live battery OR last known battery + relative timestamp (e.g. "5h ago")
                if (snap.caseLevel in 0..100) {
                    rv.setTextViewText(R.id.widget_case_value, "${snap.caseLevel}%${if (snap.caseCharging) " +" else ""}")
                    rv.setViewVisibility(R.id.widget_case_sub, View.GONE)
                    rv.setProgressBar(R.id.widget_case_bar, 100, snap.caseLevel, false)
                } else if (snap.lastCaseLevel != null && snap.lastCaseTimeMs != null) {
                    rv.setTextViewText(R.id.widget_case_value, "${snap.lastCaseLevel}%")
                    rv.setTextViewText(R.id.widget_case_sub, WidgetStateStore.formatRelativeTime(context, snap.lastCaseTimeMs))
                    rv.setViewVisibility(R.id.widget_case_sub, View.VISIBLE)
                    rv.setProgressBar(R.id.widget_case_bar, 100, snap.lastCaseLevel, false)
                } else {
                    rv.setTextViewText(R.id.widget_case_value, "—")
                    rv.setViewVisibility(R.id.widget_case_sub, View.GONE)
                    rv.setProgressBar(R.id.widget_case_bar, 100, 0, false)
                }
            }

            // ANC Quick Toggle row
            if (snap.supportsAnc) {
                rv.setViewVisibility(R.id.widget_anc_row, View.VISIBLE)
                val selColor = ContextCompat.getColor(context, R.color.widget_on_primary_container)
                val normColor = ContextCompat.getColor(context, R.color.widget_on_surface)

                fun stylePill(viewId: Int, selected: Boolean) {
                    rv.setInt(
                        viewId,
                        "setBackgroundResource",
                        if (selected) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
                    )
                    rv.setTextColor(viewId, if (selected) selColor else normColor)
                }

                stylePill(R.id.widget_anc_btn_nr, snap.ancMode == T300Protocol.AncMode.NOISE_REDUCTION)
                stylePill(R.id.widget_anc_btn_off, snap.ancMode == T300Protocol.AncMode.OFF)
                stylePill(R.id.widget_anc_btn_tr, snap.ancMode == T300Protocol.AncMode.TRANSPARENCY)

                rv.setOnClickPendingIntent(R.id.widget_anc_btn_nr, actionPendingIntent(context, ACTION_ANC_NR, 101))
                rv.setOnClickPendingIntent(R.id.widget_anc_btn_off, actionPendingIntent(context, ACTION_ANC_OFF, 102))
                rv.setOnClickPendingIntent(R.id.widget_anc_btn_tr, actionPendingIntent(context, ACTION_ANC_TR, 103))
            } else {
                rv.setViewVisibility(R.id.widget_anc_row, View.GONE)
            }

            return rv
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val targetAnc = when (intent.action) {
            ACTION_ANC_NR -> T300Protocol.AncMode.NOISE_REDUCTION
            ACTION_ANC_OFF -> T300Protocol.AncMode.OFF
            ACTION_ANC_TR -> T300Protocol.AncMode.TRANSPARENCY
            ACTION_REFRESH -> null
            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RealnessApp.from(context).executeWidgetAction(targetAnc = targetAnc, cycleAnc = false)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
