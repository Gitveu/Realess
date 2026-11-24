package uwu.realness.gitveu.protocol

import kotlin.math.roundToInt

/**
 * Logitech G733 (HID++ 2.0) & Logitech G435 (Vendor FFC0 Report 6 bridge) USB-OTG protocols.
 *
 * References:
 * - Sapd/HeadsetControl (`logitech_g633_g933_935.hpp`, `hidpp_protocol.hpp`, `logitech_calibrations.hpp`)
 * - Spider19996/HeadsetControl-G733-Tray
 * - minchiki/g435-hub (`hub/src/hid.rs`, `hub/src/battery.rs`)
 */
object LogitechProtocol {

    const val VENDOR_LOGITECH = 0x046D

    // Logitech G733 (3 known USB Lightspeed dongle PIDs + compatible G633/G933/G935 family)
    val G733_PIDS = setOf(0x0AB5, 0x0AFE, 0x0B1F)

    // Logitech G435 Lightspeed USB dongle
    const val G435_PID = 0x0ACB

    fun modelFromUsbIds(vendorId: Int, productId: Int): T300Protocol.BudsModel? {
        if (vendorId != VENDOR_LOGITECH) return null
        return when (productId) {
            in G733_PIDS -> T300Protocol.BudsModel.LOGITECH_G733
            G435_PID -> T300Protocol.BudsModel.LOGITECH_G435
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Logitech G733 — HID++ 2.0 (20-byte long reports, 0x11 0xFF ...)
    // -------------------------------------------------------------------------

    const val HIDPP_LONG_MESSAGE: Byte = 0x11
    const val HIDPP_DEVICE_RECEIVER: Byte = 0xFF.toByte()
    const val HIDPP_LONG_LENGTH = 20
    const val G733_SPEC_HOURS = 29.0f // G733 Lightspeed rated up to 29h without lighting / ~20h with RGB

    // Calibrated G633 / G733 / G935 voltage curve from Sapd/HeadsetControl
    private val G733_CURVE = arrayOf(
        4100 to 100,
        3950 to 80,
        3850 to 60,
        3750 to 40,
        3650 to 20,
        3500 to 10,
        3300 to 5,
        3150 to 0
    )

    data class G733BatteryResult(
        val online: Boolean,
        val percent: Int,       // -1 when offline
        val charging: Boolean,
        val voltageMv: Int,
        val hoursLeft: Float?
    )

    fun buildG733LongPacket(vararg featureBytes: Int): ByteArray {
        val out = ByteArray(HIDPP_LONG_LENGTH)
        out[0] = HIDPP_LONG_MESSAGE
        out[1] = HIDPP_DEVICE_RECEIVER
        val n = minOf(featureBytes.size, HIDPP_LONG_LENGTH - 2)
        for (i in 0 until n) {
            out[2 + i] = (featureBytes[i] and 0xFF).toByte()
        }
        return out
    }

    fun buildG733BatteryRequest(): ByteArray = buildG733LongPacket(0x08, 0x0A)

    fun buildG733SidetoneSet(level: Int): ByteArray =
        buildG733LongPacket(0x07, 0x1A, level.coerceIn(0, 100))

    fun buildG733InactiveTimeSet(minutes: Int): ByteArray =
        buildG733LongPacket(0x07, 0x21, minutes.coerceIn(0, 90))

    /**
     * Returns 2 packets (side strips zone=1, logo zone=0) for G733 RGB lights on/off.
     * Matches `LogitechG633Family::setLights` (breathing effect when on, off when false).
     */
    fun buildG733LightsPackets(on: Boolean): List<ByteArray> {
        val mode = if (on) 0x02 else 0x00
        val strips = buildG733LongPacket(
            0x04, 0x3C, 0x01, mode,
            0x00, 0xB6, 0xFF, 0x0F, 0xA0, 0x00, 0x64, 0x00, 0x00, 0x00
        )
        val logo = buildG733LongPacket(
            0x04, 0x3C, 0x00, mode,
            0x00, 0xB6, 0xFF, 0x0F, 0xA0, 0x00, 0x64, 0x00, 0x00, 0x00
        )
        return listOf(strips, logo)
    }

    fun g733MillivoltsToPercent(mv: Int): Int {
        if (mv >= G733_CURVE.first().first) return 100
        if (mv <= G733_CURVE.last().first) return 0
        for (i in 0 until G733_CURVE.size - 1) {
            val (hiMv, hiP) = G733_CURVE[i]
            val (loMv, loP) = G733_CURVE[i + 1]
            if (mv >= loMv) {
                val t = (mv - loMv).toFloat() / (hiMv - loMv).toFloat()
                return (loP + t * (hiP - loP)).roundToInt().coerceIn(0, 100)
            }
        }
        return 0
    }

    fun parseG733BatteryResponse(resp: ByteArray): G733BatteryResult? {
        if (resp.size < 7) return null
        if ((resp[2].toInt() and 0xFF) == 0xFF) {
            return G733BatteryResult(online = false, percent = -1, charging = false, voltageMv = 0, hoursLeft = null)
        }
        val voltageMv = ((resp[4].toInt() and 0xFF) shl 8) or (resp[5].toInt() and 0xFF)
        val state = resp[6].toInt() and 0xFF
        val charging = state == 0x03
        if (voltageMv < G733_CURVE.last().first) {
            return G733BatteryResult(online = false, percent = -1, charging = false, voltageMv = voltageMv, hoursLeft = null)
        }
        val pct = g733MillivoltsToPercent(voltageMv)
        // Typical G733 wireless runtime: ~20h (matches HeadsetControl's 15h..20h conservative curve)
        val hours = if (!charging && pct > 0) (pct / 100f) * 20.0f else null
        return G733BatteryResult(
            online = true,
            percent = pct,
            charging = charging,
            voltageMv = voltageMv,
            hoursLeft = hours
        )
    }

    // -------------------------------------------------------------------------
    // Logitech G435 — USB Dongle 046D:0ACB Vendor FFC0 Report 6 Bridge Protocol
    // -------------------------------------------------------------------------

    const val G435_REPORT_ID: Byte = 0x06
    const val G435_FEATURE_LEN = 64
    const val G435_INPUT_LEN = 8
    const val G435_CAT_OTA: Byte = 0x06
    const val G435_CAT_WIRELESS: Byte = 0x0E
    const val G435_GET_MODE: Byte = 0xE0.toByte()
    const val G435_SET_MODE: Byte = 0xE1.toByte()
    const val G435_GET_CONNECT: Byte = 0xE3.toByte()

    val G435_OP_ENTER_BOOTLOADER = byteArrayOf(0x01, 0x03)
    val G435_OP_EXIT_BOOTLOADER = byteArrayOf(0x08, 0x03)
    val G435_OP_SEND_COMMAND = byteArrayOf(0x04, 0x03)

    // Exact 22-point calibrated Li-Po curve from minchiki/g435-hub (hub/src/hid.rs)
    private val G435_CURVE = arrayOf(
        4200 to 100,
        4150 to 95,
        4100 to 90,
        4050 to 85,
        4000 to 80,
        3950 to 75,
        3900 to 70,
        3850 to 62,
        3800 to 55,
        3750 to 41,
        3720 to 34,
        3705 to 29,
        3700 to 27,
        3690 to 17,
        3675 to 11,
        3650 to 9,
        3600 to 7,
        3550 to 5,
        3500 to 4,
        3450 to 2,
        3400 to 1,
        3300 to 0
    )

    fun buildG435BridgeReport(op: ByteArray, body: ByteArray = ByteArray(0)): ByteArray {
        val report = ByteArray(G435_FEATURE_LEN)
        report[0] = G435_REPORT_ID
        report[1] = op[0]
        report[2] = op[1]
        val copyLen = minOf(body.size, G435_FEATURE_LEN - 3)
        if (copyLen > 0) {
            System.arraycopy(body, 0, report, 3, copyLen)
        }
        return report
    }

    fun buildG435QueryBody(category: Byte, seq: Int, len: Int, payload: ByteArray): ByteArray {
        val cmdLen = 5 + payload.size
        val body = ByteArray(1 + cmdLen)
        body[0] = cmdLen.toByte()
        body[1] = 0x50
        body[2] = 0x41
        body[3] = category
        body[4] = (seq and 0xFF).toByte()
        body[5] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, body, 6, payload.size)
        return body
    }

    fun parseG435Remote(buf: ByteArray, cmd: Byte): Int? {
        for (i in 0..buf.size - 4) {
            if (buf[i] == 0x02.toByte() && buf[i + 1] == 0x00.toByte() && buf[i + 2] == cmd) {
                return buf[i + 3].toInt() and 0xFF
            }
        }
        return null
    }

    fun parseG435BatteryMv(buf: ByteArray): Int? {
        for (i in 0..buf.size - 7) {
            if (buf[i] == 0x07.toByte() &&
                buf[i + 1] == 0x00.toByte() &&
                buf[i + 2] == G435_CAT_OTA &&
                ((buf[i + 3].toInt() and 0x80) != 0) &&
                buf[i + 4] == 0x00.toByte()
            ) {
                val mv = (buf[i + 5].toInt() and 0xFF) or ((buf[i + 6].toInt() and 0xFF) shl 8)
                if (mv in 2500..4600) return mv
            }
        }
        return null
    }

    fun g435MillivoltsToPercent(mv: Int): Int {
        if (mv >= G435_CURVE.first().first) return 100
        for (i in 0 until G435_CURVE.size - 1) {
            val (hiMv, hiP) = G435_CURVE[i]
            val (loMv, loP) = G435_CURVE[i + 1]
            if (mv >= loMv) {
                val t = (mv - loMv).toFloat() / (hiMv - loMv).toFloat()
                return (loP + t * (hiP - loP)).roundToInt().coerceIn(0, 100)
            }
        }
        return 0
    }

    // -------------------------------------------------------------------------
    // G435 Battery Smoothing, Charging Inference & Remaining Hours Estimator
    // (Direct port of minchiki/g435-hub/hub/src/battery.rs)
    // -------------------------------------------------------------------------

    data class VoltageSample(val atMs: Long, val mv: Int)

    data class G435BatteryEstimate(
        val percent: Int,
        val charging: Boolean,
        val hoursLeft: Float?,
        val voltageMv: Int
    )

    class G435BatteryEstimator {
        companion object {
            private const val SPEC_HOURS = 18.0f
            private const val CHARGE_RISE_MV = 30
            private const val SMOOTH_WINDOW_MS = 45L * 60L * 1000L
            private const val GAP_RESET_MS = 2L * 3600L * 1000L
            private const val TREND_MIN_SPAN_MS = 40L * 60L * 1000L
            private const val FALLING_MV = 15
            private const val CHARGE_WINDOW_MS = 30L * 60L * 1000L
            private const val CHARGE_RISE_WINDOW_MV = 45
            private const val TREND_RECENT_MS = 2L * 3600L * 1000L
            private const val MAX_SAMPLES = 96
        }

        private val history = ArrayList<VoltageSample>()
        private var prevEstimate: G435BatteryEstimate? = null

        fun reset() {
            history.clear()
            prevEstimate = null
        }

        fun addSample(mv: Int, nowMs: Long = System.currentTimeMillis()): G435BatteryEstimate {
            history.add(VoltageSample(nowMs, mv))
            if (history.size > MAX_SAMPLES) {
                history.removeAt(0)
            }
            val est = computeEstimate()
            prevEstimate = est
            return est
        }

        private fun risingAt(index: Int): Boolean {
            val target = history[index]
            var minMv = Int.MAX_VALUE
            for (j in index - 1 downTo 0) {
                val s = history[j]
                if (target.atMs - s.atMs > CHARGE_WINDOW_MS) break
                if (s.mv < minMv) minMv = s.mv
            }
            return minMv != Int.MAX_VALUE && (target.mv - minMv >= CHARGE_RISE_WINDOW_MV)
        }

        private fun dischargeStart(): Int {
            for (i in history.size - 1 downTo 1) {
                val cur = history[i]
                val prev = history[i - 1]
                if (cur.mv - prev.mv >= CHARGE_RISE_MV ||
                    cur.atMs - prev.atMs > GAP_RESET_MS ||
                    risingAt(i)
                ) {
                    return i
                }
            }
            return 0
        }

        private fun fitRate(segment: List<VoltageSample>): Float? {
            if (segment.size < 3) return null
            val span = segment.last().atMs - segment.first().atMs
            if (span < TREND_MIN_SPAN_MS) return null
            val t0 = segment.first().atMs
            val n = segment.size.toFloat()
            var sx = 0f
            var sy = 0f
            val xs = FloatArray(segment.size)
            val ys = FloatArray(segment.size)
            for (i in segment.indices) {
                val x = (segment[i].atMs - t0) / 3_600_000f
                val y = g435MillivoltsToPercent(segment[i].mv).toFloat()
                xs[i] = x
                ys[i] = y
                sx += x
                sy += y
            }
            val mx = sx / n
            val my = sy / n
            var num = 0f
            var den = 0f
            for (i in segment.indices) {
                val dx = xs[i] - mx
                num += dx * (ys[i] - my)
                den += dx * dx
            }
            if (den <= 1e-6f) return null
            val slope = num / den
            return if (slope < -0.5f) -slope else null
        }

        private fun dischargeRate(segment: List<VoltageSample>): Float? {
            if (segment.isEmpty()) return null
            val lastMs = segment.last().atMs
            val recent = segment.filter { lastMs - it.atMs <= TREND_RECENT_MS }
            return fitRate(recent) ?: fitRate(segment)
        }

        private fun computeEstimate(): G435BatteryEstimate {
            val last = history.last()
            val before = if (history.size >= 2) history[history.size - 2] else null
            val delta = before?.let { last.mv - it.mv }
            val freshGap = before == null || (last.atMs - before.atMs > GAP_RESET_MS)
            val prev = prevEstimate

            val charging = when {
                delta != null && delta >= CHARGE_RISE_MV -> true
                delta != null && !freshGap && risingAt(history.size - 1) -> true
                delta != null && prev != null && prev.charging && !freshGap -> delta > -15
                else -> false
            }

            val start = dischargeStart()
            val segment = history.subList(start, history.size)
            val window = ArrayList<Int>(3)
            for (i in segment.size - 1 downTo 0) {
                val s = segment[i]
                if (last.atMs - s.atMs > SMOOTH_WINDOW_MS || window.size >= 3) break
                window.add(s.mv)
            }
            window.sort()
            val median = if (window.isEmpty()) last.mv else window[window.size / 2]
            val effectiveMv = if (last.mv <= median - FALLING_MV) last.mv else median
            val smoothed = g435MillivoltsToPercent(effectiveMv)
            val percent = if (prev != null && !charging && !prev.charging && !freshGap) {
                minOf(smoothed, prev.percent)
            } else {
                smoothed
            }

            val hoursLeft = if (charging) {
                null
            } else {
                val rate = dischargeRate(segment)
                if (rate != null) {
                    (percent / rate).coerceAtMost(40.0f)
                } else {
                    (percent / 100.0f) * SPEC_HOURS
                }
            }

            return G435BatteryEstimate(
                percent = percent,
                charging = charging,
                hoursLeft = hoursLeft,
                voltageMv = last.mv
            )
        }
    }
}
