package uwu.realness.gitveu.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import uwu.realness.gitveu.R
import uwu.realness.gitveu.RealnessApp
import uwu.realness.gitveu.ble.BleConnectionState
import uwu.realness.gitveu.protocol.BudsProtocol
import uwu.realness.gitveu.ui.MainActivity
import uwu.realness.gitveu.widget.WidgetRingRenderer
import uwu.realness.gitveu.widget.WidgetStateStore
import kotlin.math.abs

/**
 * ColorOS / FreeBuds-inspired bottom-sheet connection popup rendered via WindowManager overlay.
 * Appears automatically on unlocked screen when earbuds connect, updates live as battery & ANC
 * packets arrive, supports swipe-down / outside-tap dismissal, and auto-dismisses cleanly.
 */
class ConnectionPopupManager(private val app: RealnessApp) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val windowManager: WindowManager? by lazy {
        app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }
    private val powerManager: PowerManager? by lazy {
        app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    private val keyguardManager: KeyguardManager? by lazy {
        app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    private var rootView: View? = null
    private var cardView: View? = null
    private var observerJob: Job? = null
    private var activeMac: String? = null
    private var activeDeviceName: String = ""
    private var activeModel: BudsProtocol.BudsModel = BudsProtocol.BudsModel.UNKNOWN
    private var isPreviewMode: Boolean = false
    private var previewAncMode: BudsProtocol.AncMode = BudsProtocol.AncMode.NOISE_REDUCTION
    private var isDismissing: Boolean = false
    private var shownAtElapsedMs: Long = 0L

    @Volatile private var lastPopupMac: String? = null
    @Volatile private var lastPopupTimeMs: Long = 0L

    private val autoDismissRunnable = Runnable {
        dismiss(animate = true)
    }

    fun isEnabled(): Boolean =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_POPUP_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_POPUP_ENABLED, enabled)
            .apply()
    }

    fun showAncControls(): Boolean =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_POPUP_SHOW_ANC, true)

    fun setShowAncControls(show: Boolean) {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_POPUP_SHOW_ANC, show)
            .apply()
    }

    fun isCompactMode(): Boolean =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_POPUP_COMPACT, false)

    fun setCompactMode(compact: Boolean) {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_POPUP_COMPACT, compact)
            .apply()
    }

    fun autoDismissSeconds(): Int =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_POPUP_DISMISS_SEC, DEFAULT_DISMISS_SECONDS)
            .coerceIn(3, 15)

    fun setAutoDismissSeconds(seconds: Int) {
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POPUP_DISMISS_SEC, seconds.coerceIn(3, 15))
            .apply()
    }

    fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(app)
    } catch (_: Throwable) {
        false
    }

    fun isEnabledAndPermitted(): Boolean = isEnabled() && canDrawOverlays()

    private fun isScreenUnlocked(): Boolean {
        val interactive = try { powerManager?.isInteractive == true } catch (_: Throwable) { false }
        val locked = try { keyguardManager?.isKeyguardLocked == true } catch (_: Throwable) { true }
        return interactive && !locked
    }

    /**
     * Shows the live connection popup when a Bluetooth headset connects in the background
     * while the device screen is awake and unlocked.
     */
    fun showForBluetoothDevice(
        mac: String,
        deviceName: String,
        model: BudsProtocol.BudsModel
    ) {
        if (!isEnabledAndPermitted()) return
        if (!isScreenUnlocked()) return
        if (app.isAppInForeground()) return

        val now = System.currentTimeMillis()
        val sameMac = lastPopupMac != null && lastPopupMac.equals(mac, ignoreCase = true)
        if (sameMac && now - lastPopupTimeMs < DEBOUNCE_MS) {
            // If already showing for this device, keep it live without restarting enter animation
            if (rootView != null && activeMac.equals(mac, ignoreCase = true)) {
                mainHandler.post {
                    activeDeviceName = deviceName
                    activeModel = model
                    renderCurrentState()
                }
            }
            return
        }
        lastPopupMac = mac
        lastPopupTimeMs = now

        mainHandler.post {
            showInternal(
                mac = mac,
                deviceName = deviceName,
                model = model,
                preview = false
            )
        }
    }

    /**
     * Shows an interactive test popup from the Settings drawer so the user can preview the card.
     */
    fun showPreview(
        deviceName: String?,
        model: BudsProtocol.BudsModel?,
        currentState: BudsProtocol.BudsState?,
        connected: Boolean
    ): Boolean {
        if (!canDrawOverlays()) return false
        val effectiveModel = model?.takeIf { it.isSupported } ?: BudsProtocol.BudsModel.BUDS_T300
        val effectiveName = deviceName?.takeIf { it.isNotBlank() } ?: app.getString(R.string.widget_preview_device)
        previewAncMode = currentState?.ancMode ?: BudsProtocol.AncMode.NOISE_REDUCTION

        mainHandler.post {
            showInternal(
                mac = null,
                deviceName = effectiveName,
                model = effectiveModel,
                preview = true,
                previewLiveState = if (connected) currentState else null
            )
        }
        return true
    }

    fun isShowingPreview(): Boolean = rootView != null && isPreviewMode

    fun onDeviceDisconnected(mac: String?, force: Boolean = false) {
        val current = activeMac ?: return
        if (mac == null || current.equals(mac, ignoreCase = true)) {
            val elapsedSinceShow = SystemClock.elapsedRealtime() - shownAtElapsedMs
            // Ignore transient HFP/A2DP/LE profile bounce right after connect unless forced (BT off)
            if (!force && elapsedSinceShow < DISCONNECT_GRACE_MS) {
                return
            }
            // Allow immediate re-show on next real reconnect after a real disconnect
            if (System.currentTimeMillis() - lastPopupTimeMs > 3_500L) {
                lastPopupTimeMs = 0L
            }
            mainHandler.post {
                if (!isPreviewMode && rootView != null) {
                    dismiss(animate = true)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal(
        mac: String?,
        deviceName: String,
        model: BudsProtocol.BudsModel,
        preview: Boolean,
        previewLiveState: BudsProtocol.BudsState? = null
    ) {
        val wm = windowManager ?: return
        if (!canDrawOverlays()) return

        // Replace any existing popup cleanly
        removeViewImmediateLocked()

        activeMac = mac
        activeDeviceName = deviceName
        activeModel = model
        isPreviewMode = preview
        isDismissing = false
        shownAtElapsedMs = SystemClock.elapsedRealtime()

        val view = LayoutInflater.from(app).inflate(R.layout.overlay_connection_popup, null)
        val card = view.findViewById<View>(R.id.popup_card)
        rootView = view
        cardView = card

        // Respect bottom navigation bar / gesture pill inset
        val navBottomPx = resolveNavBarInsetPx(wm)
        val density = app.resources.displayMetrics.density
        val baseBottomPx = (18f * density).toInt()
        val sidePx = (14f * density).toInt()
        val topPx = (8f * density).toInt()
        view.setPadding(sidePx, topPx, sidePx, baseBottomPx + navBottomPx)

        // Close icon
        val closeBtn = view.findViewById<ImageView>(R.id.popup_btn_close)
        closeBtn.setImageBitmap(
            WidgetRingRenderer.renderTintedIcon(
                app,
                R.drawable.ic_popup_close,
                ContextCompat.getColor(app, R.color.widget_on_surface_variant),
                18f
            )
        )
        closeBtn.setOnClickListener { dismiss(animate = true) }

        // Open app button (and tapping compact battery row in compact mode)
        val openAppAction: (View) -> Unit = {
            dismiss(animate = true)
            if (!isPreviewMode || !app.isAppInForeground()) {
                val launch = Intent(app, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                try {
                    app.startActivity(launch)
                } catch (_: Throwable) {}
            }
        }
        view.findViewById<TextView>(R.id.popup_btn_open_app).setOnClickListener(openAppAction)
        view.findViewById<View>(R.id.popup_compact_row).setOnClickListener(openAppAction)

        // Quick ANC buttons
        view.findViewById<View>(R.id.popup_btn_anc_nr).setOnClickListener {
            onAncClicked(BudsProtocol.AncMode.NOISE_REDUCTION)
        }
        view.findViewById<View>(R.id.popup_btn_anc_off).setOnClickListener {
            onAncClicked(BudsProtocol.AncMode.OFF)
        }
        view.findViewById<View>(R.id.popup_btn_anc_tr).setOnClickListener {
            onAncClicked(BudsProtocol.AncMode.TRANSPARENCY)
        }

        // Tap outside popup -> dismiss (ignore residual touches during entrance animation)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (SystemClock.elapsedRealtime() - shownAtElapsedMs >= OUTSIDE_TOUCH_GRACE_MS) {
                    dismiss(animate = true)
                }
                true
            } else {
                false
            }
        }

        // Swipe down on card -> dismiss
        var dragStartY = 0f
        var dragging = false
        val dismissThresholdPx = 56f * density
        card.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragging = false
                    rescheduleAutoDismiss()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - dragStartY
                    if (dy > 10f * density) {
                        dragging = true
                        rescheduleAutoDismiss()
                    }
                    if (dragging) {
                        val clamped = dy.coerceAtLeast(0f)
                        v.translationY = clamped
                        v.alpha = (1f - clamped / (220f * density)).coerceIn(0.35f, 1f)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val dy = (event.rawY - dragStartY).coerceAtLeast(0f)
                        dragging = false
                        if (dy >= dismissThresholdPx) {
                            dismiss(animate = true)
                        } else {
                            v.animate()
                                .translationY(0f)
                                .alpha(1f)
                                .setDuration(180L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Constrain max width on tablets/landscape
            val maxW = (440f * density).toInt()
            val screenW = app.resources.displayMetrics.widthPixels
            if (screenW > maxW) {
                width = maxW
            }
        }

        try {
            wm.addView(view, params)
        } catch (_: Throwable) {
            rootView = null
            cardView = null
            return
        }

        // Initial render
        if (preview) {
            val state = previewLiveState ?: buildDemoPreviewState(model)
            renderState(
                connState = BleConnectionState.CONNECTED,
                state = state,
                cachedCase = null
            )
        } else {
            renderCurrentState()
            startLiveObserver()
        }

        // Smooth expressive slide-up entrance
        card.alpha = 0f
        card.translationY = 120f * density
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300L)
            .setInterpolator(OvershootInterpolator(0.55f))
            .start()

        rescheduleAutoDismiss()
    }

    private fun startLiveObserver() {
        observerJob?.cancel()
        observerJob = uiScope.launch {
            combine(
                app.bleManager.connectionState,
                app.bleManager.budsState
            ) { conn, st -> conn to st }.collect { (conn, st) ->
                if (rootView == null || isPreviewMode) return@collect
                val activeDevMac = app.bleManager.activeDevice?.address
                if (activeMac != null && activeDevMac != null && !activeMac.equals(activeDevMac, ignoreCase = true)) {
                    return@collect
                }
                val liveModel = app.bleManager.currentModel.takeIf { it.isSupported } ?: activeModel
                activeModel = liveModel
                val cachedCase = WidgetStateStore.getLastCaseBattery(app, activeMac)
                renderState(conn, st, cachedCase)
            }
        }
    }

    private fun renderCurrentState() {
        val conn = app.bleManager.connectionState.value
        val st = app.bleManager.budsState.value
        val cachedCase = WidgetStateStore.getLastCaseBattery(app, activeMac)
        renderState(conn, st, cachedCase)
    }

    private fun buildDemoPreviewState(model: BudsProtocol.BudsModel): BudsProtocol.BudsState {
        return if (model.isSingleBatteryHeadset || model.isGenericBluetooth) {
            BudsProtocol.BudsState(
                leftBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.LEFT_EAR, 84, false),
                rightBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.RIGHT_EAR, 84, false),
                caseBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.CASE, -1, false),
                ancMode = if (model.supportsAnc) previewAncMode else null,
                isStandardBluetoothFallback = model.isGenericBluetooth
            )
        } else {
            BudsProtocol.BudsState(
                leftBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.LEFT_EAR, 95, false),
                rightBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.RIGHT_EAR, 90, false),
                caseBattery = BudsProtocol.BatteryInfo(BudsProtocol.ComponentRole.CASE, 70, true),
                ancMode = if (model.supportsAnc) previewAncMode else null
            )
        }
    }

    private fun renderState(
        connState: BleConnectionState,
        state: BudsProtocol.BudsState,
        cachedCase: Pair<Int, Long>?
    ) {
        val view = rootView ?: return
        val nameTv = view.findViewById<TextView>(R.id.popup_device_name)
        val statusTv = view.findViewById<TextView>(R.id.popup_status_text)
        val statusDot = view.findViewById<View>(R.id.popup_status_dot)

        nameTv.text = activeDeviceName.ifBlank { activeModel.displayName }

        val isConnected = connState == BleConnectionState.CONNECTED
        val isConnecting = connState == BleConnectionState.CONNECTING
        val dotColor = ContextCompat.getColor(
            app,
            if (isConnected) R.color.widget_status_online else R.color.widget_status_offline
        )
        (statusDot.background?.mutate() as? GradientDrawable)?.setColor(dotColor)

        statusTv.text = when {
            isPreviewMode -> app.getString(R.string.popup_status_preview)
            isConnected -> app.getString(R.string.popup_status_connected)
            isConnecting -> app.getString(R.string.popup_status_connecting)
            else -> app.getString(R.string.popup_status_connecting)
        }

        val isSingleBattery = activeModel.isSingleBatteryHeadset ||
            activeModel.isGenericBluetooth ||
            state.isStandardBluetoothFallback
        val compact = isCompactMode()

        val twsRow = view.findViewById<LinearLayout>(R.id.popup_tws_row)
        val singleRow = view.findViewById<LinearLayout>(R.id.popup_single_row)
        val compactRow = view.findViewById<LinearLayout>(R.id.popup_compact_row)
        val ancRow = view.findViewById<LinearLayout>(R.id.popup_anc_row)
        val openBtn = view.findViewById<TextView>(R.id.popup_btn_open_app)

        if (compact) {
            twsRow.visibility = View.GONE
            singleRow.visibility = View.GONE
            ancRow.visibility = View.GONE
            openBtn.visibility = View.GONE
            compactRow.visibility = View.VISIBLE

            val compactTws = view.findViewById<LinearLayout>(R.id.popup_compact_tws)
            val compactSingle = view.findViewById<LinearLayout>(R.id.popup_compact_single)

            if (isSingleBattery) {
                compactTws.visibility = View.GONE
                compactSingle.visibility = View.VISIBLE
                val lvl = state.leftBattery.level.takeIf { it in 0..100 }
                    ?: state.rightBattery.level.takeIf { it in 0..100 }
                    ?: -1
                val charging = state.leftBattery.isCharging || state.rightBattery.isCharging
                view.findViewById<ImageView>(R.id.popup_compact_ring_single).setImageBitmap(
                    WidgetRingRenderer.renderBatteryRing(
                        context = app,
                        level = lvl,
                        charging = charging,
                        dimmed = !isConnected,
                        glyphRes = R.drawable.ic_widget_headset,
                        sizeDp = 34f
                    )
                )
                view.findViewById<TextView>(R.id.popup_compact_bat_single).text =
                    if (lvl in 0..100) "$lvl%" else "—"
                val labelRes = if (activeModel.isGenericBluetooth || state.isStandardBluetoothFallback) {
                    R.string.battery_overall
                } else {
                    R.string.battery_headset
                }
                view.findViewById<TextView>(R.id.popup_compact_label_single).text = app.getString(labelRes)
            } else {
                compactTws.visibility = View.VISIBLE
                compactSingle.visibility = View.GONE

                val leftLvl = state.leftBattery.level
                val rightLvl = state.rightBattery.level
                val liveCaseLvl = state.caseBattery.level
                val showCachedCase = liveCaseLvl !in 0..100 && cachedCase != null
                val effectiveCaseLvl = if (liveCaseLvl in 0..100) liveCaseLvl else (cachedCase?.first ?: -1)

                view.findViewById<ImageView>(R.id.popup_compact_ring_left).setImageBitmap(
                    WidgetRingRenderer.renderBatteryRing(
                        context = app,
                        level = leftLvl,
                        charging = state.leftBattery.isCharging,
                        dimmed = !isConnected,
                        glyphRes = R.drawable.ic_widget_bud_left,
                        sizeDp = 32f
                    )
                )
                view.findViewById<TextView>(R.id.popup_compact_bat_left).text =
                    if (leftLvl in 0..100) "$leftLvl%" else "—"

                view.findViewById<ImageView>(R.id.popup_compact_ring_case).setImageBitmap(
                    WidgetRingRenderer.renderBatteryRing(
                        context = app,
                        level = effectiveCaseLvl,
                        charging = state.caseBattery.isCharging && !showCachedCase,
                        dimmed = showCachedCase || !isConnected,
                        glyphRes = R.drawable.ic_widget_case,
                        sizeDp = 32f
                    )
                )
                view.findViewById<TextView>(R.id.popup_compact_bat_case).text =
                    if (effectiveCaseLvl in 0..100) "$effectiveCaseLvl%" else "—"
                val caseAgeTv = view.findViewById<TextView>(R.id.popup_compact_case_age)
                if (showCachedCase) {
                    caseAgeTv.text = WidgetStateStore.formatShortRelativeTime(app, cachedCase!!.second)
                    caseAgeTv.visibility = View.VISIBLE
                } else {
                    caseAgeTv.visibility = View.GONE
                }

                view.findViewById<ImageView>(R.id.popup_compact_ring_right).setImageBitmap(
                    WidgetRingRenderer.renderBatteryRing(
                        context = app,
                        level = rightLvl,
                        charging = state.rightBattery.isCharging,
                        dimmed = !isConnected,
                        glyphRes = R.drawable.ic_widget_bud_right,
                        sizeDp = 32f
                    )
                )
                view.findViewById<TextView>(R.id.popup_compact_bat_right).text =
                    if (rightLvl in 0..100) "$rightLvl%" else "—"
            }
            return
        }

        compactRow.visibility = View.GONE
        openBtn.visibility = View.VISIBLE

        if (isSingleBattery) {
            twsRow.visibility = View.GONE
            singleRow.visibility = View.VISIBLE
            val lvl = state.leftBattery.level.takeIf { it in 0..100 }
                ?: state.rightBattery.level.takeIf { it in 0..100 }
                ?: -1
            val charging = state.leftBattery.isCharging || state.rightBattery.isCharging
            view.findViewById<ImageView>(R.id.popup_ring_single).setImageBitmap(
                WidgetRingRenderer.renderBatteryRing(
                    context = app,
                    level = lvl,
                    charging = charging,
                    dimmed = !isConnected,
                    glyphRes = R.drawable.ic_widget_headset,
                    sizeDp = 56f
                )
            )
            view.findViewById<TextView>(R.id.popup_bat_single).text =
                if (lvl in 0..100) "$lvl%" else "—"
            val labelRes = if (activeModel.isGenericBluetooth || state.isStandardBluetoothFallback) {
                R.string.battery_overall
            } else {
                R.string.battery_headset
            }
            view.findViewById<TextView>(R.id.popup_label_single).text = app.getString(labelRes)
        } else {
            twsRow.visibility = View.VISIBLE
            singleRow.visibility = View.GONE

            val leftLvl = state.leftBattery.level
            val rightLvl = state.rightBattery.level
            val liveCaseLvl = state.caseBattery.level
            val showCachedCase = liveCaseLvl !in 0..100 && cachedCase != null
            val effectiveCaseLvl = if (liveCaseLvl in 0..100) liveCaseLvl else (cachedCase?.first ?: -1)

            view.findViewById<ImageView>(R.id.popup_ring_left).setImageBitmap(
                WidgetRingRenderer.renderBatteryRing(
                    context = app,
                    level = leftLvl,
                    charging = state.leftBattery.isCharging,
                    dimmed = !isConnected,
                    glyphRes = R.drawable.ic_widget_bud_left,
                    sizeDp = 50f
                )
            )
            view.findViewById<TextView>(R.id.popup_bat_left).text =
                if (leftLvl in 0..100) "$leftLvl%" else "—"

            view.findViewById<ImageView>(R.id.popup_ring_case).setImageBitmap(
                WidgetRingRenderer.renderBatteryRing(
                    context = app,
                    level = effectiveCaseLvl,
                    charging = state.caseBattery.isCharging && !showCachedCase,
                    dimmed = showCachedCase || !isConnected,
                    glyphRes = R.drawable.ic_widget_case,
                    sizeDp = 50f
                )
            )
            view.findViewById<TextView>(R.id.popup_bat_case).text =
                if (effectiveCaseLvl in 0..100) "$effectiveCaseLvl%" else "—"
            view.findViewById<TextView>(R.id.popup_label_case).text = if (showCachedCase) {
                WidgetStateStore.formatShortRelativeTime(app, cachedCase!!.second)
            } else {
                app.getString(R.string.battery_case)
            }

            view.findViewById<ImageView>(R.id.popup_ring_right).setImageBitmap(
                WidgetRingRenderer.renderBatteryRing(
                    context = app,
                    level = rightLvl,
                    charging = state.rightBattery.isCharging,
                    dimmed = !isConnected,
                    glyphRes = R.drawable.ic_widget_bud_right,
                    sizeDp = 50f
                )
            )
            view.findViewById<TextView>(R.id.popup_bat_right).text =
                if (rightLvl in 0..100) "$rightLvl%" else "—"
        }

        // Quick ANC row
        val supportsAnc = showAncControls() &&
            activeModel.supportsAnc &&
            !activeModel.isGenericBluetooth &&
            !state.isStandardBluetoothFallback

        if (supportsAnc) {
            ancRow.visibility = View.VISIBLE
            val activeAnc = if (isPreviewMode) previewAncMode else state.ancMode
            bindAncPill(
                pill = view.findViewById(R.id.popup_btn_anc_nr),
                icon = view.findViewById(R.id.popup_icon_anc_nr),
                label = view.findViewById(R.id.popup_text_anc_nr),
                iconRes = R.drawable.ic_widget_anc,
                selected = activeAnc == BudsProtocol.AncMode.NOISE_REDUCTION
            )
            bindAncPill(
                pill = view.findViewById(R.id.popup_btn_anc_off),
                icon = view.findViewById(R.id.popup_icon_anc_off),
                label = view.findViewById(R.id.popup_text_anc_off),
                iconRes = R.drawable.ic_widget_anc_off,
                selected = activeAnc == BudsProtocol.AncMode.OFF
            )
            bindAncPill(
                pill = view.findViewById(R.id.popup_btn_anc_tr),
                icon = view.findViewById(R.id.popup_icon_anc_tr),
                label = view.findViewById(R.id.popup_text_anc_tr),
                iconRes = R.drawable.ic_widget_anc_trans,
                selected = activeAnc == BudsProtocol.AncMode.TRANSPARENCY
            )
        } else {
            ancRow.visibility = View.GONE
        }
    }

    private fun bindAncPill(
        pill: View,
        icon: ImageView,
        label: TextView,
        iconRes: Int,
        selected: Boolean
    ) {
        pill.setBackgroundResource(
            if (selected) R.drawable.widget_pill_selected else R.drawable.widget_pill_normal
        )
        val fgColor = ContextCompat.getColor(
            app,
            if (selected) R.color.widget_on_primary_container else R.color.widget_on_surface_variant
        )
        label.setTextColor(fgColor)
        icon.setImageBitmap(WidgetRingRenderer.renderTintedIcon(app, iconRes, fgColor, 18f))
    }

    private fun onAncClicked(mode: BudsProtocol.AncMode) {
        rescheduleAutoDismiss()
        if (isPreviewMode) {
            previewAncMode = mode
            renderState(
                connState = BleConnectionState.CONNECTED,
                state = buildDemoPreviewState(activeModel),
                cachedCase = null
            )
            return
        }
        // Optimistic highlight + send command over live BLE/SPP link
        val current = app.bleManager.budsState.value
        renderState(
            connState = app.bleManager.connectionState.value,
            state = current.copy(ancMode = mode),
            cachedCase = WidgetStateStore.getLastCaseBattery(app, activeMac)
        )
        if (app.bleManager.connectionState.value == BleConnectionState.CONNECTED) {
            app.bleManager.setAncMode(mode)
        }
    }

    private fun rescheduleAutoDismiss() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, autoDismissSeconds() * 1_000L)
    }

    fun dismiss(animate: Boolean = true) {
        mainHandler.post {
            val view = rootView ?: return@post
            val card = cardView
            if (isDismissing) return@post
            isDismissing = true
            mainHandler.removeCallbacks(autoDismissRunnable)
            observerJob?.cancel()
            observerJob = null

            if (!animate || card == null) {
                removeViewImmediateLocked()
                return@post
            }

            val density = app.resources.displayMetrics.density
            val targetY = maxOf(140f * density, abs(card.height.toFloat()) + 40f * density)
            card.animate().cancel()
            card.animate()
                .translationY(targetY)
                .alpha(0f)
                .setDuration(200L)
                .setInterpolator(AccelerateInterpolator(1.4f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeViewImmediateLocked()
                    }
                    override fun onAnimationCancel(animation: Animator) {
                        removeViewImmediateLocked()
                    }
                })
                .start()
        }
    }

    private fun removeViewImmediateLocked() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        observerJob?.cancel()
        observerJob = null
        val view = rootView
        rootView = null
        cardView = null
        isDismissing = false
        if (view != null) {
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Throwable) {
                try {
                    windowManager?.removeView(view)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun resolveNavBarInsetPx(wm: WindowManager): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                    .bottom
            } else {
                val resId = app.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (resId > 0) app.resources.getDimensionPixelSize(resId) else 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    companion object {
        private const val PREFS_NAME = "realness_prefs"
        private const val KEY_POPUP_ENABLED = "connection_popup_enabled"
        private const val KEY_POPUP_SHOW_ANC = "connection_popup_show_anc"
        private const val KEY_POPUP_COMPACT = "connection_popup_compact"
        private const val KEY_POPUP_DISMISS_SEC = "connection_popup_dismiss_sec"
        private const val DEFAULT_DISMISS_SECONDS = 6
        private const val DEBOUNCE_MS = 15_000L
        private const val OUTSIDE_TOUCH_GRACE_MS = 900L
        private const val DISCONNECT_GRACE_MS = 2_000L
    }
}
