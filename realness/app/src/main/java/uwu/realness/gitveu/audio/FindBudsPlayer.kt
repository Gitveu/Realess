package uwu.realness.gitveu.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class FindBudsChannel {
    LEFT, BOTH, RIGHT
}

/**
 * Universal escalating locator tone player for Find My Earbuds (works on ALL Bluetooth headsets).
 * Synthesizes a high-visibility triple-chirp locator tone via AudioTrack with stereo L/R panning
 * and smoothly escalates media volume from 15% to 100% over 20 seconds, restoring the user's
 * original media volume when stopped.
 */
class FindBudsPlayer(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var playJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var savedMediaVolume: Int? = null
    @Volatile private var currentChannel: FindBudsChannel = FindBudsChannel.BOTH

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _volumeProgress = MutableStateFlow(0f)
    val volumeProgress: StateFlow<Float> = _volumeProgress.asStateFlow()

    private val _channel = MutableStateFlow(FindBudsChannel.BOTH)
    val channel: StateFlow<FindBudsChannel> = _channel.asStateFlow()

    fun setChannel(channel: FindBudsChannel) {
        currentChannel = channel
        _channel.value = channel
        applyStereoPan()
    }

    fun start(channel: FindBudsChannel = currentChannel) {
        stop()
        currentChannel = channel
        _channel.value = channel
        _isPlaying.value = true
        _volumeProgress.value = 0.15f

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        savedMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        playJob = scope.launch {
            val sampleRate = 44100
            val patternPcm = buildLocatorPatternPcm(sampleRate)
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, patternPcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            val startTime = System.currentTimeMillis()
            val rampDurationMs = 20_000f

            try {
                applyStereoPan(0.25f)
                track.play()

                val chunkFrames = 2205 // 50ms chunks for responsive stop + smooth volume ramp
                val chunkShorts = chunkFrames * 2
                var cursor = 0

                while (isActive) {
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
                    val progress = (0.15f + 0.85f * (elapsed / rampDurationMs)).coerceIn(0.15f, 1.0f)
                    _volumeProgress.value = progress

                    // Escalate system STREAM_MUSIC smoothly alongside AudioTrack gain
                    val targetSysVol = (progress * maxVol).roundToInt().coerceIn(1, maxVol)
                    try {
                        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != targetSysVol) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVol, 0)
                        }
                    } catch (_: SecurityException) {}

                    applyStereoPan(0.35f + 0.65f * progress)

                    val remaining = patternPcm.size - cursor
                    val count = min(chunkShorts, remaining)
                    val written = track.write(patternPcm, cursor, count)
                    if (written < 0) break
                    cursor += count
                    if (cursor >= patternPcm.size) cursor = 0
                }
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        audioTrack?.let { t ->
            try { t.pause(); t.flush(); t.stop(); t.release() } catch (_: Exception) {}
        }
        audioTrack = null
        savedMediaVolume?.let { orig ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0)
            } catch (_: Exception) {}
        }
        savedMediaVolume = null
        _isPlaying.value = false
        _volumeProgress.value = 0f
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun applyStereoPan(gain: Float = 1f) {
        val track = audioTrack ?: return
        val g = gain.coerceIn(0.05f, 1.0f)
        val (l, r) = when (currentChannel) {
            FindBudsChannel.LEFT -> g to 0f
            FindBudsChannel.RIGHT -> 0f to g
            FindBudsChannel.BOTH -> g to g
        }
        try {
            track.setStereoVolume(l, r)
        } catch (_: Exception) {}
    }

    /**
     * Builds a 1.4-second stereo PCM loop: 3 crisp high-visibility dual-harmonic chirps
     * (2950 Hz + 3450 Hz) with smooth 8ms fade-in/out to prevent clicks, followed by a brief pause.
     */
    private fun buildLocatorPatternPcm(sampleRate: Int): ShortArray {
        val totalFrames = (sampleRate * 1.4f).toInt()
        val stereo = ShortArray(totalFrames * 2)

        val chirpFrames = (sampleRate * 0.14f).toInt()
        val gapFrames = (sampleRate * 0.09f).toInt()
        val fadeFrames = (sampleRate * 0.008f).toInt()

        for (chirpIdx in 0..2) {
            val baseFreq = if (chirpIdx == 2) 3450.0 else 2950.0
            val overtone = baseFreq * 1.18
            val start = chirpIdx * (chirpFrames + gapFrames)
            for (i in 0 until chirpFrames) {
                val frame = start + i
                if (frame >= totalFrames) break
                val env = when {
                    i < fadeFrames -> i.toFloat() / fadeFrames
                    i > chirpFrames - fadeFrames -> (chirpFrames - i).toFloat() / fadeFrames
                    else -> 1f
                }
                val t = i.toDouble() / sampleRate
                val wave = 0.72 * sin(2.0 * PI * baseFreq * t) + 0.28 * sin(2.0 * PI * overtone * t)
                val sample = (wave * env * Short.MAX_VALUE * 0.92).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                stereo[frame * 2] = sample
                stereo[frame * 2 + 1] = sample
            }
        }
        return stereo
    }
}
