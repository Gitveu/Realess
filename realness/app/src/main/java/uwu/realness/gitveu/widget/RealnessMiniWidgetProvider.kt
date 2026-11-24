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

class RealnessMiniWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_MINI_CYCLE_ANC = "uwu.realness.gitveu.widget.ACTION_MINI_CYCLE_ANC"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RealnessMiniWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        fun buildViews(context: Context): RemoteViews {
            val snap = WidgetStateStore.loadSnapshot(context)
            val rv = RemoteViews(context.packageName, R.layout.widget_realness_mini)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 500, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_mini_root, openPi)

            val (batText, batProgress) = when {
                snap.isSingleBattery -> {
                    val lvl = snap.leftLevel
                    if (lvl in 0..100) ("${lvl}%" to lvl) else ("—" to 0)
                }
                snap.leftLevel in 0..100 && snap.rightLevel in 0..100 -> {
                    val minLvl = minOf(snap.leftLevel, snap.rightLevel)
                    val txt = if (snap.leftLevel == snap.rightLevel) {
                        "${snap.leftLevel}%"
                    } else {
                        "${snap.leftLevel}/${snap.rightLevel}"
                    }
                    txt to minLvl
                }
                snap.leftLevel in 0..100 -> "${snap.leftLevel}%" to snap.leftLevel
                snap.rightLevel in 0..100 -> "${snap.rightLevel}%" to snap.rightLevel
                snap.caseLevel in 0..100 -> "C${snap.caseLevel}%" to snap.caseLevel
                snap.lastCaseLevel != null -> "C${snap.lastCaseLevel}%" to snap.lastCaseLevel
                else -> "—" to 0
            }
            rv.setTextViewText(R.id.widget_mini_battery, batText)

            if (snap.supportsAnc) {
                rv.setViewVisibility(R.id.widget_mini_anc_btn, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_mini_bar, View.GONE)
                val isActive = snap.ancMode == T300Protocol.AncMode.NOISE_REDUCTION ||
                    snap.ancMode == T300Protocol.AncMode.TRANSPARENCY
                val ancLabel = when (snap.ancMode) {
                    T300Protocol.AncMode.NOISE_REDUCTION -> context.getString(R.string.widget_anc_mini)
                    T300Protocol.AncMode.TRANSPARENCY -> context.getString(R.string.widget_trans_mini)
                    T300Protocol.AncMode.OFF -> context.getString(R.string.widget_off_mini)
                    null -> context.getString(R.string.widget_anc_mini)
                }
                rv.setTextViewText(R.id.widget_mini_anc_btn, ancLabel)
                rv.setInt(
                    R.id.widget_mini_anc_btn,
                    "setBackgroundResource",
                    if (isActive) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
                )
                rv.setTextColor(
                    R.id.widget_mini_anc_btn,
                    ContextCompat.getColor(
                        context,
                        if (isActive) R.color.widget_on_primary_container else R.color.widget_on_surface
                    )
                )
                val cycleIntent = Intent(context, RealnessMiniWidgetProvider::class.java).apply {
                    action = ACTION_MINI_CYCLE_ANC
                }
                val cyclePi = PendingIntent.getBroadcast(
                    context, 501, cycleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(R.id.widget_mini_anc_btn, cyclePi)
            } else {
                rv.setViewVisibility(R.id.widget_mini_anc_btn, View.GONE)
                rv.setViewVisibility(R.id.widget_mini_bar, View.VISIBLE)
                rv.setProgressBar(R.id.widget_mini_bar, 100, batProgress.coerceIn(0, 100), false)
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
        if (intent.action != ACTION_MINI_CYCLE_ANC) return
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
