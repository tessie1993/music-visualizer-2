package dev.musicviz.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Microphone source for "no music, visuals react to the room".
 *
 * Writes into the SAME [PcmRingBuffer] the playback tap feeds, so every
 * consumer downstream - the FFT, the beat tracker, the fluid emitters, the
 * water drops - is unchanged and unaware of where the samples came from. That
 * is the whole design: a second producer for one buffer, not a second
 * analysis path that would drift from the first.
 *
 * The worker thread, the generation fence and the recorder's release live in
 * [AudioCapturePump], shared with [PlaybackCapture]; this class supplies the
 * microphone recorder and the "is it actually hearing anything" metering.
 *
 * Nothing is recorded, buffered to disk or sent anywhere: samples go into the
 * ring buffer, which is overwritten continuously and lives only in memory.
 */
class MicCapture(
    private val context: Context,
    ring: PcmRingBuffer,
    /** Injectable for tests; production uses the monotonic elapsed clock. */
    nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : AudioCapturePump(ring, DEFAULT_RATE, nowMs) {
    /** Why a start attempt failed, for a message the user can act on. */
    enum class Failure {
        /** RECORD_AUDIO has not been granted. */
        PERMISSION,

        /** The device or another app would not give us the microphone. */
        UNAVAILABLE,
    }

    /**
     * Peak absolute sample of the most recent read, 0..1. Overwritten every
     * ~23 ms while running (so it decays to the room by itself, never
     * accumulates) and reset to 0 on start and stop. This is the "is it
     * actually hearing anything" number the UI never had: a muted or
     * hardware-dead microphone that still reads zeros looked identical to a
     * working one.
     */
    @Volatile
    var peakLevel: Float = 0f
        private set

    /**
     * True once the capture has run for [SILENCE_GRACE_MS] without a single
     * sample above the noise floor.
     *
     * The mirror of [PlaybackCapture.blockedLikely], and the same heuristic
     * honesty applies: a genuinely silent room and a muted microphone look
     * identical from here, so this reads as "hearing nothing", never as an
     * accusation. A real microphone in a real room produces thermal noise
     * well above [SILENCE_EPSILON]; long runs of exact zeros mean the samples
     * are synthetic - a mute switch, a privacy toggle, dead hardware.
     */
    @Volatile
    var silenceLikely: Boolean = false
        private set

    override val threadName = "musicviz-mic"

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens the microphone and starts feeding the ring. Returns null once the
     * recorder is actually recording, or the [Failure] that stopped it;
     * already-running is a success no-op.
     *
     * [onSampleRate] fires on the caller's thread with the rate the device
     * granted, which is not always the one requested.
     */
    @Synchronized
    fun start(onSampleRate: (Int) -> Unit = {}): Failure? {
        if (active) return null
        if (!hasPermission()) return Failure.PERMISSION
        val rec = openRecord() ?: return Failure.UNAVAILABLE
        if (!startPump(rec, 1, onSampleRate)) return Failure.UNAVAILABLE
        return null
    }

    /**
     * Updates [peakLevel] and [silenceLikely] from this buffer.
     *
     * The bar is "any sample at all above the noise floor", not a loudness
     * threshold - the same rule as [PlaybackCapture]: a working microphone
     * produces thermal noise even in a quiet room, and a muted one produces
     * exact zeroes. The grace period exists because the first buffers after
     * `startRecording` legitimately arrive empty.
     */
    override fun noteLevel(
        buffer: FloatArray,
        count: Int,
        startedAtMs: Long,
    ) {
        var peak = 0f
        for (i in 0 until count) {
            val v = abs(buffer[i])
            if (v > peak) peak = v
        }
        peakLevel = peak
        val now = nowMs()
        if (peak > SILENCE_EPSILON) {
            lastAudibleAtMs = now
            silenceLikely = false
        } else {
            // Unlike the playback capture's flag this one can also flip
            // mid-run: a hardware mute switch thrown while listening is
            // exactly the case the hint exists for, so the clock restarts
            // from the last audible sample, not only from the start of the
            // run.
            val quietSince = if (lastAudibleAtMs == 0L) startedAtMs else lastAudibleAtMs
            if (now - quietSince > SILENCE_GRACE_MS) silenceLikely = true
        }
    }

    /** Cleared on start and stop: the meter must not freeze its last value. */
    override fun resetLevel() {
        peakLevel = 0f
        silenceLikely = false
    }

    /**
     * Builds an AudioRecord, trying float PCM first (the tap's own format, so
     * the ring buffer sees the same numbers playback would produce) and
     * falling back to 16-bit, then to the other rates (48 kHz, then
     * 22.05 kHz). Returns null when the device grants none of them.
     */
    private fun openRecord(): AudioRecord? {
        val source = preferredSource()
        for (rate in intArrayOf(DEFAULT_RATE, 48_000, 22_050)) {
            for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
                if (min <= 0) continue
                val rec =
                    runCatching {
                        @Suppress("MissingPermission")
                        AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            encoding,
                            (min * BUFFER_MULTIPLIER).coerceAtLeast(READ_FRAMES * 4),
                        )
                    }.getOrNull()
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    sampleRateHz = rate
                    return rec
                }
                runCatching { rec?.release() }
            }
        }
        return null
    }

    /**
     * UNPROCESSED asks the device to skip the AGC and noise suppression the
     * voice sources apply - those flatten exactly the dynamics the beat
     * tracker keys off. But the platform contract is that an app MUST check
     * [AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED] first: on a
     * device that does not support it, UNPROCESSED does not fail - it quietly
     * behaves like an arbitrary voice source, processing included. Where it is
     * not supported, VOICE_RECOGNITION is the documented nearest thing: tuned
     * flat, no AGC.
     */
    private fun preferredSource(): Int {
        val supported =
            runCatching {
                (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            }.getOrNull()
        return if (supported == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private companion object {
        const val DEFAULT_RATE = 44_100
    }
}
