package uwu.realness.gitveu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uwu.realness.gitveu.R
import uwu.realness.gitveu.RealnessApp
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

            val (batText, ringLevel, isCachedOnly) = when {
                snap.isSingleBattery -> {
                    val lvl = snap.leftLevel
                    if (lvl in 0..100) Triple("${lvl}%", lvl, !snap.connected) else Triple("—", -1, true)
                }
                snap.leftLevel in 0..100 && snap.rightLevel in 0..100 -> {
                    val minLvl = minOf(snap.leftLevel, snap.rightLevel)
                    val txt = if (snap.leftLevel == snap.rightLevel) {
                        "${snap.leftLevel}%"
                    } else {
                        "${snap.leftLevel}/${snap.rightLevel}"
                    }
                    Triple(txt, minLvl, !snap.connected)
                }
                snap.leftLevel in 0..100 -> Triple("${snap.leftLevel}%", snap.leftLevel, !snap.connected)
                snap.rightLevel in 0..100 -> Triple("${snap.rightLevel}%", snap.rightLevel, !snap.connected)
                snap.caseLevel in 0..100 -> Triple("${snap.caseLevel}%", snap.caseLevel, false)
                snap.lastCaseLevel != null -> Triple("${snap.lastCaseLevel}%", snap.lastCaseLevel, true)
                else -> Triple("—", -1, true)
            }

            rv.setTextViewText(R.id.widget_mini_battery, batText)
            rv.setImageViewBitmap(
                R.id.widget_mini_ring,
                WidgetRingRenderer.renderMiniAncRing(
                    context = context,
                    level = ringLevel,
                    ancMode = snap.ancMode,
                    supportsAnc = snap.supportsAnc,
                    dimmed = isCachedOnly,
                    sizeDp = 40f
                )
            )

            if (snap.supportsAnc) {
                val cycleIntent = Intent(context, RealnessMiniWidgetProvider::class.java).apply {
                    action = ACTION_MINI_CYCLE_ANC
                }
                val cyclePi = PendingIntent.getBroadcast(
                    context, 501, cycleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                rv.setOnClickPendingIntent(R.id.widget_mini_ring, cyclePi)
            } else {
                rv.setOnClickPendingIntent(R.id.widget_mini_ring, openPi)
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
