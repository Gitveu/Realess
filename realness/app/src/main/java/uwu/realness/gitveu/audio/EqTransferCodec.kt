package uwu.realness.gitveu.audio

import android.util.Base64
import uwu.realness.gitveu.protocol.BudsProtocol
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Codec for sharing custom EQ profiles as a compact 1-line string (RNQ1:...)
 * and importing AutoEQ / Wavelet (.txt) curves with automatic log-frequency resampling
 * to any headset's native band count (3 / 5 / 6 / 8 / 10 bands) and dB range.
 */
object EqTransferCodec {

    private const val SHARE_PREFIX = "RNQ1:"
    private const val SAMPLE_RATE_HZ = 48_000.0

    data class ImportedEq(
        val gains: List<Int>,
        val profileName: String?,
        val sourceFormat: String,
        val sourcePointCount: Int
    )

    private data class ParametricFilter(
        val type: String, // PK, LSC, HSC
        val fcHz: Double,
        val gainDb: Double,
        val q: Double
    )

    /**
     * Parses human band labels like "31", "62", "1k", "2.4k", "Bass", "Mid", "Treble" to Hz.
     */
    fun bandLabelsToHz(labels: List<String>): List<Double> {
        return labels.mapIndexed { idx, raw ->
            val s = raw.trim().lowercase(Locale.US)
            when {
                s == "bass" || s == "бас" -> 100.0
                s == "mid" || s == "середина" -> 1000.0
                s == "treble" || s == "верха" -> 6000.0
                s.endsWith("k") || s.endsWith("khz") -> {
                    val num = s.removeSuffix("khz").removeSuffix("k").replace(",", ".").toDoubleOrNull()
                    if (num != null && num > 0.0) num * 1000.0 else fallbackFreq(idx, labels.size)
                }
                else -> {
                    val num = s.removeSuffix("hz").replace(",", ".").toDoubleOrNull()
                    if (num != null && num > 0.0) num else fallbackFreq(idx, labels.size)
                }
            }
        }
    }

    private fun fallbackFreq(index: Int, count: Int): Double {
        if (count <= 1) return 1000.0
        val logMin = log10(31.0)
        val logMax = log10(16000.0)
        val t = index.toDouble() / (count - 1).toDouble()
        return 10.0.pow(logMin + t * (logMax - logMin))
    }

    /**
     * Encodes the current EQ curve into a compact single-line share code:
     * RNQ1:<base64url(name|f1:g1,f2:g2,...)>
     */
    fun encodeShareCode(
        spec: BudsProtocol.CustomEqSpec,
        gains: List<Int>,
        profileName: String? = null
    ): String {
        val freqs = bandLabelsToHz(spec.bandLabels)
        val cleanName = (profileName ?: "")
            .replace("|", " ")
            .replace("\n", " ")
            .trim()
            .take(20)
        val pairs = freqs.indices.joinToString(",") { i ->
            val f = freqs[i].roundToInt()
            val g = gains.getOrElse(i) { 0 }.coerceIn(spec.minDb, spec.maxDb)
            "$f:$g"
        }
        val raw = "$cleanName|$pairs"
        val encoded = Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$SHARE_PREFIX$encoded"
    }

    /**
     * Universal importer supporting:
     * 1. realness 1-line share code (`RNQ1:...`)
     * 2. Wavelet GraphicEQ (`GraphicEQ: 20 -1.2; 25 -0.8; ...`)
     * 3. AutoEQ / EqualizerAPO ParametricEQ (`Preamp: ...`, `Filter 1: ON PK Fc ... Gain ... Q ...`)
     * 4. Plain comma/space-separated dB list (`+3, +2, 0, -1, +1, +2`)
     */
    fun importFromText(
        rawText: String,
        targetSpec: BudsProtocol.CustomEqSpec,
        fallbackName: String? = null
    ): ImportedEq? {
        val text = rawText.trim()
        if (text.isEmpty()) return null

        // 1. realness RNQ1 share code (may appear inside a chat message)
        val shareRegex = Regex("""RNQ1:([A-Za-z0-9_\-]+)""")
        shareRegex.find(text)?.let { match ->
            parseShareCode(match.groupValues[1], targetSpec)?.let { return it }
        }

        // 2. Wavelet GraphicEQ format
        if (text.contains("GraphicEQ:", ignoreCase = true)) {
            parseWaveletGraphicEq(text, targetSpec, fallbackName)?.let { return it }
        }

        // 3. AutoEQ / EqualizerAPO ParametricEQ format
        if (text.contains("Filter", ignoreCase = true) &&
            text.contains("Fc", ignoreCase = true) &&
            text.contains("Gain", ignoreCase = true)
        ) {
            parseAutoEqParametric(text, targetSpec, fallbackName)?.let { return it }
        }

        // 4. Two-column frequency/gain table (e.g. FixedBandEQ / CSV)
        parseFrequencyTable(text, targetSpec, fallbackName)?.let { return it }

        // 5. Simple list of band gains (e.g. "+3, +2, 0, -1, +2, +3")
        parsePlainGainList(text, targetSpec, fallbackName)?.let { return it }

        return null
    }

    private fun parseShareCode(b64: String, targetSpec: BudsProtocol.CustomEqSpec): ImportedEq? {
        return try {
            val decoded = String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
            val pipe = decoded.indexOf('|')
            if (pipe < 0) return null
            val name = decoded.substring(0, pipe).trim().ifEmpty { null }
            val body = decoded.substring(pipe + 1)
            val points = body.split(",").mapNotNull { token ->
                val kv = token.trim().split(":")
                if (kv.size != 2) return@mapNotNull null
                val f = kv[0].toDoubleOrNull() ?: return@mapNotNull null
                val g = kv[1].toDoubleOrNull() ?: return@mapNotNull null
                if (f <= 0.0) return@mapNotNull null
                f to g
            }.sortedBy { it.first }
            if (points.isEmpty()) return null
            val resampled = resampleCurveToSpec(points, targetSpec, normalizeOffset = false)
            ImportedEq(
                gains = resampled,
                profileName = name,
                sourceFormat = "realness",
                sourcePointCount = points.size
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseWaveletGraphicEq(
        text: String,
        targetSpec: BudsProtocol.CustomEqSpec,
        fallbackName: String?
    ): ImportedEq? {
        val idx = text.indexOf("GraphicEQ:", ignoreCase = true)
        if (idx < 0) return null
        val payload = text.substring(idx + "GraphicEQ:".length).lineSequence().firstOrNull()?.trim() ?: return null
        val points = payload.split(";").mapNotNull { entry ->
            val parts = entry.trim().split(Regex("""\s+"""))
            if (parts.size < 2) return@mapNotNull null
            val f = parts[0].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            val g = parts[1].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            if (f <= 0.0) return@mapNotNull null
            f to g
        }.sortedBy { it.first }
        if (points.size < 2) return null

        val resampled = resampleCurveToSpec(points, targetSpec, normalizeOffset = true)
        return ImportedEq(
            gains = resampled,
            profileName = fallbackName?.take(20),
            sourceFormat = "Wavelet",
            sourcePointCount = points.size
        )
    }

    private fun parseAutoEqParametric(
        text: String,
        targetSpec: BudsProtocol.CustomEqSpec,
        fallbackName: String?
    ): ImportedEq? {
        var preamp = 0.0
        val preampRegex = Regex("""Preamp:\s*([+-]?\d+(?:[.,]\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        preampRegex.find(text)?.let { m ->
            preamp = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
        }

        val filterRegex = Regex(
            """Filter\s+\d+:\s*ON\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+(\d+(?:[.,]\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:[.,]\d+)?)\s*dB(?:\s+Q\s+(\d+(?:[.,]\d+)?))?""",
            RegexOption.IGNORE_CASE
        )
        val filters = filterRegex.findAll(text).mapNotNull { m ->
            val type = when (m.groupValues[1].uppercase(Locale.US)) {
                "LS", "LSC" -> "LSC"
                "HS", "HSC" -> "HSC"
                else -> "PK"
            }
            val fc = m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            val gain = m.groupValues[3].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            val q = m.groupValues[4].replace(",", ".").toDoubleOrNull()?.coerceIn(0.1, 20.0) ?: 0.707
            if (fc <= 0.0) return@mapNotNull null
            ParametricFilter(type, fc, gain, q)
        }.toList()

        if (filters.isEmpty()) return null

        val targetFreqs = bandLabelsToHz(targetSpec.bandLabels)
        val rawGains = targetFreqs.map { f ->
            var totalDb = preamp
            for (flt in filters) {
                totalDb += evaluateBiquadMagnitudeDb(flt, f)
            }
            totalDb
        }
        val fitted = fitGainsToRange(rawGains, targetSpec.minDb, targetSpec.maxDb, normalizeOffset = true)
        return ImportedEq(
            gains = fitted,
            profileName = fallbackName?.take(20),
            sourceFormat = "AutoEQ",
            sourcePointCount = filters.size
        )
    }

    private fun parseFrequencyTable(
        text: String,
        targetSpec: BudsProtocol.CustomEqSpec,
        fallbackName: String?
    ): ImportedEq? {
        val lineRegex = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(?:Hz)?[\s,;:\t]+([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?\s*$""", RegexOption.IGNORE_CASE)
        val points = text.lineSequence().mapNotNull { line ->
            val m = lineRegex.matchEntire(line) ?: return@mapNotNull null
            val f = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            val g = m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            if (f < 10.0 || f > 24000.0) return@mapNotNull null
            f to g
        }.toList().sortedBy { it.first }
        if (points.size < 3) return null

        val resampled = resampleCurveToSpec(points, targetSpec, normalizeOffset = true)
        return ImportedEq(
            gains = resampled,
            profileName = fallbackName?.take(20),
            sourceFormat = "AutoEQ",
            sourcePointCount = points.size
        )
    }

    private fun parsePlainGainList(
        text: String,
        targetSpec: BudsProtocol.CustomEqSpec,
        fallbackName: String?
    ): ImportedEq? {
        val tokens = text.split(Regex("""[\s,;]+""")).filter { it.isNotBlank() }
        if (tokens.size < 3 || tokens.size > 16) return null
        val values = tokens.map { it.replace(",", ".").removeSuffix("dB").removeSuffix("db").toDoubleOrNull() ?: return null }
        if (values.any { it < -24.0 || it > 24.0 }) return null

        val targetCount = targetSpec.bandLabels.size
        val mapped = if (values.size == targetCount) {
            values
        } else {
            val srcFreqs = values.indices.map { fallbackFreq(it, values.size) }
            val pts = srcFreqs.zip(values)
            return ImportedEq(
                gains = resampleCurveToSpec(pts, targetSpec, normalizeOffset = false),
                profileName = fallbackName?.take(20),
                sourceFormat = "Manual",
                sourcePointCount = values.size
            )
        }
        return ImportedEq(
            gains = mapped.map { it.roundToInt().coerceIn(targetSpec.minDb, targetSpec.maxDb) },
            profileName = fallbackName?.take(20),
            sourceFormat = "Manual",
            sourcePointCount = values.size
        )
    }

    /**
     * Resamples a frequency->dB curve onto targetSpec's bands using log-frequency interpolation
     * (averaging points within +/- 1/3 octave around each target band when dense data is present).
     */
    private fun resampleCurveToSpec(
        points: List<Pair<Double, Double>>,
        targetSpec: BudsProtocol.CustomEqSpec,
        normalizeOffset: Boolean
    ): List<Int> {
        val targetFreqs = bandLabelsToHz(targetSpec.bandLabels)
        val raw = targetFreqs.map { f ->
            if (points.size >= 20) {
                // Smooth dense Wavelet/AutoEQ curves over +/- 1/3 octave to avoid single-bin spikes
                val lo = f * 0.7937
                val hi = f * 1.2599
                val inWindow = points.filter { it.first in lo..hi }
                if (inWindow.isNotEmpty()) {
                    inWindow.map { it.second }.average()
                } else {
                    interpolateLog(points, f)
                }
            } else {
                interpolateLog(points, f)
            }
        }
        return fitGainsToRange(raw, targetSpec.minDb, targetSpec.maxDb, normalizeOffset)
    }

    private fun interpolateLog(points: List<Pair<Double, Double>>, freqHz: Double): Double {
        if (points.size == 1) return points[0].second
        if (freqHz <= points.first().first) return points.first().second
        if (freqHz >= points.last().first) return points.last().second

        val logF = log10(freqHz)
        for (i in 0 until points.lastIndex) {
            val (f0, g0) = points[i]
            val (f1, g1) = points[i + 1]
            if (freqHz in f0..f1) {
                val denom = log10(f1) - log10(f0)
                if (denom <= 1e-9) return (g0 + g1) / 2.0
                val t = (logF - log10(f0)) / denom
                return g0 + t * (g1 - g0)
            }
        }
        return points.last().second
    }

    private fun fitGainsToRange(
        raw: List<Double>,
        minDb: Int,
        maxDb: Int,
        normalizeOffset: Boolean
    ): List<Int> {
        if (raw.isEmpty()) return emptyList()
        var shifted = raw
        if (normalizeOffset) {
            val minV = raw.minOrNull() ?: 0.0
            val maxV = raw.maxOrNull() ?: 0.0
            // Shift curve into headset's usable dB window without distorting relative shape
            val midCurve = (minV + maxV) / 2.0
            val midRange = (minDb + maxDb) / 2.0
            if (minV < minDb || maxV > maxDb) {
                val delta = midRange - midCurve
                shifted = raw.map { it + delta }
            }
            // If peak-to-peak span exceeds hardware dynamic range, compress proportionally
            val span = (shifted.maxOrNull() ?: 0.0) - (shifted.minOrNull() ?: 0.0)
            val maxSpan = (maxDb - minDb).toDouble()
            if (span > maxSpan && span > 1e-6) {
                val scale = maxSpan / span
                shifted = shifted.map { midRange + (it - midRange) * scale }
            }
        }
        return shifted.map { it.roundToInt().coerceIn(minDb, maxDb) }
    }

    /**
     * Computes exact biquad magnitude response in dB at frequency fHz (RBJ Audio EQ Cookbook).
     */
    private fun evaluateBiquadMagnitudeDb(filter: ParametricFilter, fHz: Double): Double {
        if (filter.gainDb == 0.0) return 0.0
        val w0 = 2.0 * PI * filter.fcHz / SAMPLE_RATE_HZ
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = 10.0.pow(filter.gainDb / 40.0)
        val alpha = sinW0 / (2.0 * filter.q)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (filter.type) {
            "LSC" -> {
                val twoSqrtAAlpha = 2.0 * kotlin.math.sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            "HSC" -> {
                val twoSqrtAAlpha = 2.0 * kotlin.math.sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
            else -> { // PK
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / a
            }
        }

        val w = 2.0 * PI * fHz / SAMPLE_RATE_HZ
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = (b0 / a0) + (b1 / a0) * cosW + (b2 / a0) * cos2W
        val numIm = -(b1 / a0) * sinW - (b2 / a0) * sin2W
        val denRe = 1.0 + (a1 / a0) * cosW + (a2 / a0) * cos2W
        val denIm = -(a1 / a0) * sinW - (a2 / a0) * sin2W

        val magSq = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm).coerceAtLeast(1e-18)
        return 10.0 * log10(magSq.coerceAtLeast(1e-18))
    }
}
