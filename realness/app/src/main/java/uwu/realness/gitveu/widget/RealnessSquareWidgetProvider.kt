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

            if (snap.isSingleBattery) {
                rv.setViewVisibility(R.id.widget_square_tws_group, View.GONE)
                rv.setViewVisibility(R.id.widget_square_single_group, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_square_bottom_row, if (snap.supportsAnc) View.VISIBLE else View.GONE)
                rv.setViewVisibility(R.id.widget_square_case_pill, View.GONE)

                val title = snap.deviceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
                rv.setTextViewText(R.id.widget_square_single_name, title)
                rv.setTextViewText(
                    R.id.widget_square_single_value,
                    if (snap.leftLevel in 0..100) "${snap.leftLevel}%" else "—"
                )
                rv.setImageViewBitmap(
                    R.id.widget_square_single_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.leftLevel,
                        charging = snap.leftCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_headset,
                        sizeDp = 48f
                    )
                )
            } else {
                rv.setViewVisibility(R.id.widget_square_tws_group, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_square_single_group, View.GONE)
                rv.setViewVisibility(R.id.widget_square_bottom_row, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_square_case_pill, View.VISIBLE)

                rv.setImageViewBitmap(
                    R.id.widget_square_left_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.leftLevel,
                        charging = snap.leftCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_bud_left,
                        sizeDp = 38f
                    )
                )
                rv.setTextViewText(
                    R.id.widget_square_left,
                    if (snap.leftLevel in 0..100) "${snap.leftLevel}%" else "—"
                )

                rv.setImageViewBitmap(
                    R.id.widget_square_right_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.rightLevel,
                        charging = snap.rightCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_bud_right,
                        sizeDp = 38f
                    )
                )
                rv.setTextViewText(
                    R.id.widget_square_right,
                    if (snap.rightLevel in 0..100) "${snap.rightLevel}%" else "—"
                )

                val liveCase = snap.caseLevel in 0..100
                val displayCaseLvl = when {
                    liveCase -> snap.caseLevel
                    snap.lastCaseLevel != null -> snap.lastCaseLevel
                    else -> -1
                }
                rv.setImageViewBitmap(
                    R.id.widget_square_case_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = displayCaseLvl,
                        charging = liveCase && snap.caseCharging,
                        dimmed = !liveCase,
                        glyphRes = R.drawable.ic_widget_case,
                        sizeDp = 24f
                    )
                )
                if (liveCase) {
                    rv.setTextViewText(R.id.widget_square_case, "${snap.caseLevel}%")
                    rv.setViewVisibility(R.id.widget_square_case_time, View.GONE)
                } else if (snap.lastCaseLevel != null && snap.lastCaseTimeMs != null) {
                    rv.setTextViewText(R.id.widget_square_case, "${snap.lastCaseLevel}%")
                    rv.setTextViewText(
                        R.id.widget_square_case_time,
                        WidgetStateStore.formatShortRelativeTime(context, snap.lastCaseTimeMs)
                    )
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
                val fg = ContextCompat.getColor(
                    context,
                    if (isActive) R.color.widget_on_primary_container else R.color.widget_on_surface_variant
                )
                rv.setInt(
                    R.id.widget_square_anc_btn,
                    "setBackgroundResource",
                    if (isActive) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
                )
                rv.setImageViewBitmap(
                    R.id.widget_square_anc_icon,
                    WidgetRingRenderer.renderTintedIcon(context, WidgetRingRenderer.ancIconRes(snap.ancMode), fg, 20f)
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
