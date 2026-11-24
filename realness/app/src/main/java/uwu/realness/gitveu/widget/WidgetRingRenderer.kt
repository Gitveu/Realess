package uwu.realness.gitveu.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import uwu.realness.gitveu.R
import uwu.realness.gitveu.protocol.T300Protocol
import kotlin.math.roundToInt

/**
 * Renders QuickBuds-inspired anti-aliased circular battery rings with centered monochrome
 * earbud / case / headset / ANC glyphs using Material You dynamic color tokens.
 */
object WidgetRingRenderer {

    @DrawableRes
    fun ancIconRes(mode: T300Protocol.AncMode?): Int = when (mode) {
        T300Protocol.AncMode.NOISE_REDUCTION -> R.drawable.ic_widget_anc
        T300Protocol.AncMode.TRANSPARENCY -> R.drawable.ic_widget_anc_trans
        T300Protocol.AncMode.OFF -> R.drawable.ic_widget_anc_off
        null -> R.drawable.ic_widget_anc
    }

    fun renderBatteryRing(
        context: Context,
        level: Int,
        charging: Boolean,
        dimmed: Boolean,
        @DrawableRes glyphRes: Int,
        sizeDp: Float
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).roundToInt().coerceIn(28, 192)
        val size = px.toFloat()
        val stroke = (size * 0.095f).coerceAtLeast(2.2f * density)

        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val trackColor = ContextCompat.getColor(context, R.color.widget_ring_track)
        val accentRaw = ContextCompat.getColor(context, R.color.widget_primary)
        val accentColor = if (dimmed) ColorUtils.setAlphaComponent(accentRaw, 130) else accentRaw
        val fgColor = ContextCompat.getColor(
            context,
            if (dimmed || level < 0) R.color.widget_on_surface_variant else R.color.widget_on_surface
        )
        val glyphColor = if (level < 0 && !dimmed) ColorUtils.setAlphaComponent(fgColor, 120) else fgColor

        val half = stroke / 2f + 0.5f
        val oval = RectF(half, half, size - half, size - half)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

        // Background track
        paint.color = trackColor
        c.drawOval(oval, paint)

        // Progress arc from 12 o'clock
        if (level in 1..100) {
            paint.color = accentColor
            val sweep = (360f * level / 100f).coerceIn(4f, 360f)
            c.drawArc(oval, -90f, sweep, false, paint)
        }

        // Centered silhouette glyph
        ContextCompat.getDrawable(context, glyphRes)?.mutate()?.let { d ->
            val glyphSize = (size * 0.50f).roundToInt().coerceAtLeast(8)
            val left = ((px - glyphSize) / 2f).roundToInt()
            val top = ((px - glyphSize) / 2f).roundToInt()
            d.setTint(glyphColor)
            d.bounds = Rect(left, top, left + glyphSize, top + glyphSize)
            d.draw(c)
        }

        // Subtle charging bolt badge at bottom-right when actively charging
        if (charging && level in 0..100) {
            val badgeR = (size * 0.16f).coerceAtLeast(4f * density)
            val cx = size - badgeR - 1f
            val cy = size - badgeR - 1f
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = ContextCompat.getColor(context, R.color.widget_primary_container)
            }
            c.drawCircle(cx, cy, badgeR, bgPaint)
            ContextCompat.getDrawable(context, R.drawable.ic_widget_bolt)?.mutate()?.let { bolt ->
                val bs = (badgeR * 1.35f).roundToInt().coerceAtLeast(6)
                val bl = (cx - bs / 2f).roundToInt()
                val bt = (cy - bs / 2f).roundToInt()
                bolt.setTint(ContextCompat.getColor(context, R.color.widget_on_primary_container))
                bolt.bounds = Rect(bl, bt, bl + bs, bt + bs)
                bolt.draw(c)
            }
        }

        return bmp
    }

    /**
     * Renders the 1x1 Mini tile's circular battery ring with a tonal inner badge holding the
     * current ANC mode icon (or headset icon when ANC is unsupported).
     */
    fun renderMiniAncRing(
        context: Context,
        level: Int,
        ancMode: T300Protocol.AncMode?,
        supportsAnc: Boolean,
        dimmed: Boolean,
        sizeDp: Float = 44f
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).roundToInt().coerceIn(36, 176)
        val size = px.toFloat()
        val stroke = (size * 0.095f).coerceAtLeast(2.5f * density)

        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val trackColor = ContextCompat.getColor(context, R.color.widget_ring_track)
        val accentRaw = ContextCompat.getColor(context, R.color.widget_primary)
        val accentColor = if (dimmed) ColorUtils.setAlphaComponent(accentRaw, 130) else accentRaw

        val half = stroke / 2f + 0.5f
        val oval = RectF(half, half, size - half, size - half)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

        ringPaint.color = trackColor
        c.drawOval(oval, ringPaint)
        if (level in 1..100) {
            ringPaint.color = accentColor
            c.drawArc(oval, -90f, (360f * level / 100f).coerceIn(4f, 360f), false, ringPaint)
        }

        val isActive = supportsAnc && (
            ancMode == T300Protocol.AncMode.NOISE_REDUCTION ||
                ancMode == T300Protocol.AncMode.TRANSPARENCY
            )
        val innerRadius = (size / 2f) - stroke - (size * 0.06f)
        if (innerRadius > 4f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = ContextCompat.getColor(
                    context,
                    if (isActive) R.color.widget_primary_container else R.color.widget_surface_card
                )
            }
            c.drawCircle(size / 2f, size / 2f, innerRadius, fillPaint)
        }

        val iconRes = if (supportsAnc) ancIconRes(ancMode) else R.drawable.ic_widget_headset
        val iconTint = ContextCompat.getColor(
            context,
            if (isActive) R.color.widget_on_primary_container else R.color.widget_on_surface
        )
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.let { d ->
            val iconSize = (size * 0.42f).roundToInt().coerceAtLeast(10)
            val left = ((px - iconSize) / 2f).roundToInt()
            val top = ((px - iconSize) / 2f).roundToInt()
            d.setTint(iconTint)
            d.bounds = Rect(left, top, left + iconSize, top + iconSize)
            d.draw(c)
        }

        return bmp
    }

    /**
     * Renders a small tinted icon bitmap for RemoteViews ImageViews (works across all API levels
     * without tint compatibility quirks).
     */
    fun renderTintedIcon(
        context: Context,
        @DrawableRes iconRes: Int,
        color: Int,
        sizeDp: Float = 22f
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).roundToInt().coerceIn(18, 96)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.let { d ->
            d.setTint(color)
            d.bounds = Rect(0, 0, px, px)
            d.draw(c)
        }
        return bmp
    }
}
