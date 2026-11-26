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

class RealnessCompactWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CYCLE_ANC = "uwu.realness.gitveu.widget.ACTION_CYCLE_ANC"
        const val ACTION_COMPACT_REFRESH = "uwu.realness.gitveu.widget.ACTION_COMPACT_REFRESH"

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

            val refreshIntent = Intent(context, RealnessCompactWidgetProvider::class.java).apply {
                action = ACTION_COMPACT_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(
                context, 202, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_compact_case_card, refreshPi)

            if (snap.isSingleBattery) {
                rv.setViewVisibility(R.id.widget_compact_tws_group, View.GONE)
                rv.setViewVisibility(R.id.widget_compact_single_group, View.VISIBLE)
                val title = snap.deviceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
                rv.setTextViewText(R.id.widget_compact_single_name, title)
                rv.setTextViewText(
                    R.id.widget_compact_single_val,
                    if (snap.leftLevel in 0..100) "${snap.leftLevel}%" else "—"
                )
                rv.setImageViewBitmap(
                    R.id.widget_compact_single_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.leftLevel,
                        charging = snap.leftCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_headset,
                        sizeDp = 34f
                    )
                )
            } else {
                rv.setViewVisibility(R.id.widget_compact_tws_group, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_compact_single_group, View.GONE)

                rv.setImageViewBitmap(
                    R.id.widget_compact_left_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.leftLevel,
                        charging = snap.leftCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_bud_left,
                        sizeDp = 30f
                    )
                )
                rv.setTextViewText(
                    R.id.widget_compact_left_val,
                    if (snap.leftLevel in 0..100) "${snap.leftLevel}%" else "—"
                )

                rv.setImageViewBitmap(
                    R.id.widget_compact_right_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.rightLevel,
                        charging = snap.rightCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_bud_right,
                        sizeDp = 30f
                    )
                )
                rv.setTextViewText(
                    R.id.widget_compact_right_val,
                    if (snap.rightLevel in 0..100) "${snap.rightLevel}%" else "—"
                )

                val liveCase = snap.caseLevel in 0..100
                val displayCaseLvl = when {
                    liveCase -> snap.caseLevel
                    snap.lastCaseLevel != null -> snap.lastCaseLevel
                    else -> -1
                }
                rv.setImageViewBitmap(
                    R.id.widget_compact_case_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = displayCaseLvl,
                        charging = liveCase && snap.caseCharging,
                        dimmed = !liveCase,
                        glyphRes = R.drawable.ic_widget_case,
                        sizeDp = 30f
                    )
                )
                if (liveCase) {
                    rv.setTextViewText(R.id.widget_compact_case_val, "${snap.caseLevel}%")
                    rv.setViewVisibility(R.id.widget_compact_case_time, View.GONE)
                } else if (snap.lastCaseLevel != null && snap.lastCaseTimeMs != null) {
                    rv.setTextViewText(R.id.widget_compact_case_val, "${snap.lastCaseLevel}%")
                    rv.setTextViewText(
                        R.id.widget_compact_case_time,
                        WidgetStateStore.formatShortRelativeTime(context, snap.lastCaseTimeMs)
                    )
                    rv.setViewVisibility(R.id.widget_compact_case_time, View.VISIBLE)
                } else {
                    rv.setTextViewText(R.id.widget_compact_case_val, "—")
                    rv.setViewVisibility(R.id.widget_compact_case_time, View.GONE)
                }
            }

            if (snap.supportsAnc) {
                rv.setViewVisibility(R.id.widget_compact_anc_btn, View.VISIBLE)
                val isActive = snap.ancMode == T300Protocol.AncMode.NOISE_REDUCTION ||
                    snap.ancMode == T300Protocol.AncMode.TRANSPARENCY
                val fg = ContextCompat.getColor(
                    context,
                    if (isActive) R.color.widget_on_primary_container else R.color.widget_on_surface_variant
                )
                rv.setInt(
                    R.id.widget_compact_anc_btn,
                    "setBackgroundResource",
                    if (isActive) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
                )
                rv.setImageViewBitmap(
                    R.id.widget_compact_anc_icon,
                    WidgetRingRenderer.renderTintedIcon(context, WidgetRingRenderer.ancIconRes(snap.ancMode), fg, 20f)
                )
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
        val app = RealnessApp.from(context)
        app.runBroadcastSync(goAsync()) {
            app.syncWidgetFromActiveDevice()
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val app = RealnessApp.from(context)
        if (!app.hasAnyWidgets()) app.cancelPeriodicWidgetSync()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val cycleAnc = when (intent.action) {
            ACTION_CYCLE_ANC -> true
            ACTION_COMPACT_REFRESH -> false
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
