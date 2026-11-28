package uwu.realness.gitveu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.os.Build

/**
 * Snapshot of the active A2DP Bluetooth audio codec for the connected headset.
 */
data class ActiveCodecInfo(
    val codecName: String,
    val sampleRateLabel: String? = null,
    val bitsPerSample: Int? = null,
    val channelModeLabel: String? = null,
    val bitrateLabel: String? = null,
    val isHiRes: Boolean = false
)

/**
 * Reads active A2DP codec, sample rate, bit depth, and estimated/negotiated bitrate via
 * BluetoothA2dp.getCodecStatus() (API 26+) and filters out non-headset BT devices such as
 * smartwatches, keyboards, and gamepads.
 */
object BluetoothCodecHelper {

    const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

    private val NON_HEADSET_NAME_KEYWORDS = listOf(
        "watch", "band", "fitbit", "garmin", "amazfit", "polar", "suunto",
        "whoop", "oura", "smart ring", "ring ", "keyboard", "mouse", "trackpad",
        "gamepad", "controller", "xbox", "dualsense", "dualshock", "joy-con",
        "printer", "scale", "mi smart band", "miband", "galaxy fit", "huawei band",
        "honor band", "bip ", "gtr ", "gts ", "t-rex", "smartwatch"
    )

    /**
     * Returns true if this BluetoothDevice is clearly NOT a headset/earbuds (e.g. Galaxy Watch,
     * fitness band, keyboard, gamepad, phone, PC).
     */
    @SuppressLint("MissingPermission")
    fun isNonHeadsetDevice(device: BluetoothDevice): Boolean {
        val major = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        }
        when (major) {
            BluetoothClass.Device.Major.WEARABLE,
            BluetoothClass.Device.Major.PHONE,
            BluetoothClass.Device.Major.COMPUTER,
            BluetoothClass.Device.Major.PERIPHERAL,
            BluetoothClass.Device.Major.HEALTH,
            BluetoothClass.Device.Major.IMAGING,
            BluetoothClass.Device.Major.TOY,
            BluetoothClass.Device.Major.NETWORKING -> return true
        }
        val name = try {
            device.name?.lowercase()?.trim()
        } catch (_: SecurityException) {
            null
        } ?: return false
        return NON_HEADSET_NAME_KEYWORDS.any { name.contains(it) }
    }

    @SuppressLint("MissingPermission")
    fun readActiveCodec(a2dp: BluetoothA2dp?, device: BluetoothDevice?): ActiveCodecInfo? {
        if (a2dp == null || device == null) return null
        val status: BluetoothCodecStatus = try {
            val method = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java)
            method.invoke(a2dp, device) as? BluetoothCodecStatus
        } catch (_: Throwable) {
            try {
                // Legacy no-arg overload on some Android 8/9 builds
                val method = BluetoothA2dp::class.java.getMethod("getCodecStatus")
                method.invoke(a2dp) as? BluetoothCodecStatus
            } catch (_: Throwable) {
                null
            }
        } ?: return null

        val config = try {
            status.codecConfig
        } catch (_: Throwable) {
            null
        } ?: return null

        return parseCodecConfig(config)
    }

    fun parseCodecConfig(config: BluetoothCodecConfig): ActiveCodecInfo? {
        val codecType = try {
            @Suppress("DEPRECATION")
            config.codecType
        } catch (_: Throwable) {
            return null
        }
        if (codecType < 0 || codecType == 1_000_000) return null

        val reflectedName = try {
            val m = BluetoothCodecConfig::class.java.getMethod("getCodecName", Int::class.javaPrimitiveType)
            (m.invoke(null, codecType) as? String)?.takeIf {
                it.isNotBlank() && !it.contains("INVALID", ignoreCase = true) && !it.contains("UNKNOWN", ignoreCase = true)
            }
        } catch (_: Throwable) {
            null
        }

        val codecName = when (codecType) {
            0 -> "SBC"
            1 -> "AAC"
            2 -> "aptX"
            3 -> "aptX HD"
            4 -> "LDAC"
            5 -> reflectedName ?: "Opus"
            6 -> reflectedName ?: "LC3"
            7 -> reflectedName ?: "LHDC V3"
            8 -> reflectedName ?: "LHDC V5"
            9 -> reflectedName ?: "LHDC"
            10 -> reflectedName ?: "aptX Adaptive"
            11 -> reflectedName ?: "aptX TWS+"
            12 -> reflectedName ?: "SSC"
            else -> reflectedName ?: "Codec #$codecType"
        }

        val sampleRateMask = try { config.sampleRate } catch (_: Throwable) { 0 }
        val sampleRateLabel = when {
            (sampleRateMask and 0x20) != 0 -> "192 kHz"
            (sampleRateMask and 0x10) != 0 -> "176.4 kHz"
            (sampleRateMask and 0x08) != 0 -> "96 kHz"
            (sampleRateMask and 0x04) != 0 -> "88.2 kHz"
            (sampleRateMask and 0x02) != 0 -> "48 kHz"
            (sampleRateMask and 0x01) != 0 -> "44.1 kHz"
            else -> null
        }

        val bitsMask = try { config.bitsPerSample } catch (_: Throwable) { 0 }
        val bitsPerSample = when {
            (bitsMask and 0x04) != 0 -> 32
            (bitsMask and 0x02) != 0 -> 24
            (bitsMask and 0x01) != 0 -> 16
            else -> null
        }

        val channelMask = try { config.channelMode } catch (_: Throwable) { 0 }
        val channelModeLabel = when {
            (channelMask and 0x02) != 0 -> "Stereo"
            (channelMask and 0x01) != 0 -> "Mono"
            else -> null
        }

        val specific1 = try { config.codecSpecific1 } catch (_: Throwable) { 0L }
        val upper = codecName.uppercase()
        val bitrateLabel = when {
            upper.contains("LDAC") -> when (specific1.toInt()) {
                1000 -> "990 kbps"
                1001 -> "660 kbps"
                1002 -> "330 kbps"
                1003 -> "330–990 kbps"
                else -> "≤990 kbps"
            }
            upper.contains("LHDC") -> when {
                (specific1 and 0x0F).toInt() == 4 -> "900 kbps"
                (specific1 and 0x0F).toInt() == 3 -> "560 kbps"
                (specific1 and 0x0F).toInt() == 2 -> "400 kbps"
                else -> "≤900 kbps"
            }
            upper.contains("APTX HD") -> "576 kbps"
            upper.contains("APTX ADAPTIVE") -> "279–420 kbps"
            upper.contains("APTX") -> "352 kbps"
            upper.contains("AAC") -> "~256 kbps"
            upper.contains("SBC") -> "~328 kbps"
            upper.contains("SSC") -> "≤512 kbps"
            else -> null
        }

        val isHiRes = upper.contains("LDAC") ||
            upper.contains("LHDC") ||
            upper.contains("APTX HD") ||
            upper.contains("APTX ADAPTIVE") ||
            upper.contains("SSC") ||
            (bitsPerSample != null && bitsPerSample >= 24) ||
            (sampleRateLabel == "96 kHz" || sampleRateLabel == "88.2 kHz" || sampleRateLabel == "192 kHz")

        return ActiveCodecInfo(
            codecName = codecName,
            sampleRateLabel = sampleRateLabel,
            bitsPerSample = bitsPerSample,
            channelModeLabel = channelModeLabel,
            bitrateLabel = bitrateLabel,
            isHiRes = isHiRes
        )
    }
}
