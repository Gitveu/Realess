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

class RealnessSquareWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_SQUARE_CYCLE_ANC = "uwu.realness.gitveu.widget.ACTION_SQUARE_CYCLE_ANC"
        const val ACTION_SQUARE_REFRESH = "uwu.realness.gitveu.widget.ACTION_SQUARE_REFRESH"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RealnessSquareWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        private fun actionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, RealnessSquareWidgetProvider::class.java).apply {
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
            val rv = RemoteViews(context.packageName, R.layout.widget_realness_square)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 300, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_square_root, openPi)

            val title = snap.deviceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
            rv.setTextViewText(R.id.widget_square_name, title)
            rv.setOnClickPendingIntent(
                R.id.widget_square_refresh_btn,
                actionPendingIntent(context, ACTION_SQUARE_REFRESH, 302)
            )

            if (snap.isSingleBattery) {
                rv.setViewVisibility(R.id.widget_square_tws_group, View.GONE)
                rv.setViewVisibility(R.id.widget_square_single_group, View.VISIBLE)
                val lvl = snap.leftLevel
                val txt = if (lvl in 0..100) "${lvl}%${if (snap.leftCharging) "+" else ""}" else "—"
                rv.setTextViewText(R.id.widget_square_single_value, txt)
                rv.setProgressBar(R.id.widget_square_single_bar, 100, lvl.coerceIn(0, 100), false)
            } else {
                rv.setViewVisibility(R.id.widget_square_tws_group, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_square_single_group, View.GONE)
                val lTxt = if (snap.leftLevel in 0..100) "${snap.leftLevel}%${if (snap.leftCharging) "+" else ""}" else "—"
                val rTxt = if (snap.rightLevel in 0..100) "${snap.rightLevel}%${if (snap.rightCharging) "+" else ""}" else "—"
                rv.setTextViewText(R.id.widget_square_left, lTxt)
                rv.setTextViewText(R.id.widget_square_right, rTxt)

                if (snap.caseLevel in 0..100) {
                    rv.setTextViewText(
                        R.id.widget_square_case,
                        "${snap.caseLevel}%${if (snap.caseCharging) "+" else ""}"
                    )
                    rv.setViewVisibility(R.id.widget_square_case_time, View.GONE)
                } else if (snap.lastCaseLevel != null && snap.lastCaseTimeMs != null) {
                    rv.setTextViewText(R.id.widget_square_case, "${snap.lastCaseLevel}%")
                    val shortAgo = WidgetStateStore.formatShortRelativeTime(context, snap.lastCaseTimeMs)
                    rv.setTextViewText(R.id.widget_square_case_time, "· $shortAgo")
                    rv.setViewVisibility(R.id.widget_square_case_time, View.VISIBLE)
                } else {
                    rv.setTextViewText(R.id.widget_square_case, "—")
                    rv.setViewVisibility(R.id.widget_square_case_time, View.GONE)
                }
            }

            if (snap.supportsAnc) {
                rv.setViewVisibility(R.id.widget_square_anc_btn, View.VISIBLE)
                val isActive = snap.ancMode == T300Protocol.AncMode.NOISE_REDUCTION ||
                    snap.ancMode == T300Protocol.AncMode.TRANSPARENCY
                val ancLabel = when (snap.ancMode) {
                    T300Protocol.AncMode.NOISE_REDUCTION -> context.getString(R.string.widget_anc_short)
                    T300Protocol.AncMode.TRANSPARENCY -> context.getString(R.string.widget_trans_short)
                    T300Protocol.AncMode.OFF -> context.getString(R.string.anc_off)
                    null -> context.getString(R.string.widget_anc_short)
                }
                rv.setTextViewText(R.id.widget_square_anc_btn, ancLabel)
                rv.setInt(
                    R.id.widget_square_anc_btn,
                    "setBackgroundResource",
                    if (isActive) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
                )
                rv.setTextColor(
                    R.id.widget_square_anc_btn,
                    ContextCompat.getColor(
                        context,
                        if (isActive) R.color.widget_on_primary_container else R.color.widget_on_surface
                    )
                )
                rv.setOnClickPendingIntent(
                    R.id.widget_square_anc_btn,
                    actionPendingIntent(context, ACTION_SQUARE_CYCLE_ANC, 301)
                )
            } else {
                rv.setViewVisibility(R.id.widget_square_anc_btn, View.GONE)
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
        val cycleAnc = when (intent.action) {
            ACTION_SQUARE_CYCLE_ANC -> true
            ACTION_SQUARE_REFRESH -> false
            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RealnessApp.from(context).executeWidgetAction(targetAnc = null, cycleAnc = cycleAnc)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
