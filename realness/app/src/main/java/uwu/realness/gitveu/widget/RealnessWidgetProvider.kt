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

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 100, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, openPi)
            rv.setOnClickPendingIntent(R.id.widget_bat_case_card, actionPendingIntent(context, ACTION_REFRESH, 104))

            if (snap.isSingleBattery) {
                rv.setViewVisibility(R.id.widget_tws_row, View.GONE)
                rv.setViewVisibility(R.id.widget_single_card, View.VISIBLE)
                val lvl = snap.leftLevel
                val title = snap.deviceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
                rv.setTextViewText(R.id.widget_single_name, title)
                rv.setTextViewText(R.id.widget_single_value, if (lvl in 0..100) "${lvl}%" else "—")
                rv.setImageViewBitmap(
                    R.id.widget_single_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = lvl,
                        charging = snap.leftCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_headset,
                        sizeDp = 54f
                    )
                )
            } else {
                rv.setViewVisibility(R.id.widget_tws_row, View.VISIBLE)
                rv.setViewVisibility(R.id.widget_single_card, View.GONE)

                rv.setImageViewBitmap(
                    R.id.widget_left_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.leftLevel,
                        charging = snap.leftCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_bud_left,
                        sizeDp = 46f
                    )
                )
                rv.setTextViewText(
                    R.id.widget_left_value,
                    if (snap.leftLevel in 0..100) "${snap.leftLevel}%" else "—"
                )

                rv.setImageViewBitmap(
                    R.id.widget_right_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = snap.rightLevel,
                        charging = snap.rightCharging,
                        dimmed = !snap.connected,
                        glyphRes = R.drawable.ic_widget_bud_right,
                        sizeDp = 46f
                    )
                )
                rv.setTextViewText(
                    R.id.widget_right_value,
                    if (snap.rightLevel in 0..100) "${snap.rightLevel}%" else "—"
                )

                val liveCase = snap.caseLevel in 0..100
                val displayCaseLvl = when {
                    liveCase -> snap.caseLevel
                    snap.lastCaseLevel != null -> snap.lastCaseLevel
                    else -> -1
                }
                rv.setImageViewBitmap(
                    R.id.widget_case_ring,
                    WidgetRingRenderer.renderBatteryRing(
                        context = context,
                        level = displayCaseLvl,
                        charging = liveCase && snap.caseCharging,
                        dimmed = !liveCase,
                        glyphRes = R.drawable.ic_widget_case,
                        sizeDp = 46f
                    )
                )
                if (liveCase) {
                    rv.setTextViewText(R.id.widget_case_value, "${snap.caseLevel}%")
                    rv.setViewVisibility(R.id.widget_case_sub, View.GONE)
                } else if (snap.lastCaseLevel != null && snap.lastCaseTimeMs != null) {
                    rv.setTextViewText(R.id.widget_case_value, "${snap.lastCaseLevel}%")
                    rv.setTextViewText(
                        R.id.widget_case_sub,
                        WidgetStateStore.formatRelativeTime(context, snap.lastCaseTimeMs)
                    )
                    rv.setViewVisibility(R.id.widget_case_sub, View.VISIBLE)
                } else {
                    rv.setTextViewText(R.id.widget_case_value, "—")
                    rv.setViewVisibility(R.id.widget_case_sub, View.GONE)
                }
            }

            if (snap.supportsAnc) {
                rv.setViewVisibility(R.id.widget_anc_row, View.VISIBLE)
                val selColor = ContextCompat.getColor(context, R.color.widget_on_primary_container)
                val normColor = ContextCompat.getColor(context, R.color.widget_on_surface_variant)

                fun styleTile(btnId: Int, iconId: Int, labelId: Int, iconRes: Int, selected: Boolean) {
                    val fg = if (selected) selColor else normColor
                    rv.setInt(
                        btnId,
                        "setBackgroundResource",
                        if (selected) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
                    )
                    rv.setImageViewBitmap(iconId, WidgetRingRenderer.renderTintedIcon(context, iconRes, fg, 18f))
                    rv.setTextColor(labelId, fg)
                }

                styleTile(
                    R.id.widget_anc_btn_nr,
                    R.id.widget_anc_icon_nr,
                    R.id.widget_anc_label_nr,
                    R.drawable.ic_widget_anc,
                    snap.ancMode == T300Protocol.AncMode.NOISE_REDUCTION
                )
                styleTile(
                    R.id.widget_anc_btn_off,
                    R.id.widget_anc_icon_off,
                    R.id.widget_anc_label_off,
                    R.drawable.ic_widget_anc_off,
                    snap.ancMode == T300Protocol.AncMode.OFF
                )
                styleTile(
                    R.id.widget_anc_btn_tr,
                    R.id.widget_anc_icon_tr,
                    R.id.widget_anc_label_tr,
                    R.drawable.ic_widget_anc_trans,
                    snap.ancMode == T300Protocol.AncMode.TRANSPARENCY
                )

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
