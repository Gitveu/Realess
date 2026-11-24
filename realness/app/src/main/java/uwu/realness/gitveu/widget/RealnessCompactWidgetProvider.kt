package uwu.realness.gitveu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uwu.realness.gitveu.R
import uwu.realness.gitveu.RealnessApp
import uwu.realness.gitveu.protocol.T300Protocol
import uwu.realness.gitveu.ui.MainActivity

class RealnessCompactWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CYCLE_ANC = "uwu.realness.gitveu.widget.ACTION_CYCLE_ANC"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RealnessCompactWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        fun buildViews(context: Context): RemoteViews {
            val snap = WidgetStateStore.loadSnapshot(context)
            val rv = RemoteViews(context.packageName, R.layout.widget_realness_compact)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 200, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_compact_root, openPi)

            val title = snap.deviceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
            rv.setTextViewText(R.id.widget_compact_name, title)

            val batLine = if (snap.isSingleBattery) {
                if (snap.leftLevel in 0..100) "${snap.leftLevel}%" else context.getString(R.string.status_disconnected)
            } else {
                val parts = ArrayList<String>(3)
                if (snap.leftLevel in 0..100) parts.add("L ${snap.leftLevel}%")
                if (snap.rightLevel in 0..100) parts.add("R ${snap.rightLevel}%")
                if (snap.caseLevel in 0..100) {
                    parts.add("C ${snap.caseLevel}%")
                } else if (snap.lastCaseLevel != null && snap.lastCaseTimeMs != null) {
                    val ago = WidgetStateStore.formatRelativeTime(context, snap.lastCaseTimeMs)
                    parts.add("C ${snap.lastCaseLevel}% ($ago)")
                }
                if (parts.isEmpty()) context.getString(R.string.status_disconnected) else parts.joinToString(" · ")
            }
            rv.setTextViewText(R.id.widget_compact_battery, batLine)

            if (snap.supportsAnc) {
                rv.setViewVisibility(R.id.widget_compact_anc_btn, View.VISIBLE)
                val ancLabel = when (snap.ancMode) {
                    T300Protocol.AncMode.NOISE_REDUCTION -> context.getString(R.string.widget_anc_short)
                    T300Protocol.AncMode.TRANSPARENCY -> context.getString(R.string.widget_trans_short)
                    T300Protocol.AncMode.OFF -> context.getString(R.string.anc_off)
                    null -> context.getString(R.string.widget_anc_short)
                }
                rv.setTextViewText(R.id.widget_compact_anc_btn, ancLabel)
                val cycleIntent = Intent(context, RealnessCompactWidgetProvider::class.java).apply {
                    action = ACTION_CYCLE_ANC
                }
                val cyclePi = PendingIntent.getBroadcast(
                    context, 201, cycleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(R.id.widget_compact_anc_btn, cyclePi)
            } else {
                rv.setViewVisibility(R.id.widget_compact_anc_btn, View.GONE)
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
        if (intent.action != ACTION_CYCLE_ANC) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RealnessApp.from(context).executeWidgetAction(targetAnc = null, cycleAnc = true)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
