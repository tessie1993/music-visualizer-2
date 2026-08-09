package dev.musicviz.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
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
 * Nothing is recorded, buffered to disk or sent anywhere: samples go into the
 * ring buffer, which is overwritten continuously and lives only in memory.
 */
class MicCapture(
    private val context: Context,
    private val ring: PcmRingBuffer,
    /** Injectable for tests; production uses the monotonic elapsed clock. */
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    /** Why a start attempt failed, for a message the user can act on. */
    enum class Failure {
        /** RECORD_AUDIO has not been granted. */
        PERMISSION,

        /** The device or another app would not give us the microphone. */
        UNAVAILABLE,
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /**
     * Bumped on every [start], so a worker that outlived its own [stop] - a
     * read still blocked when the join timed out - cannot clear [running] out
     * from under the run that replaced it, and exits instead of re-entering
     * its loop when that run flips [running] back on. Without the loop fence
     * a stop-then-start pair left the old worker alive, holding its recorder
     * and double-feeding the ring alongside the new one.
     */
    @Volatile
    private var runGeneration = 0

    /** True while the microphone is open and feeding the ring buffer. */
    val active: Boolean get() = running

    /**
     * Sample rate the capture actually opened at, for the analyzer's
     * `sampleRateHz`. Meaningless while stopped.
     */
    @Volatile
    var sampleRateHz: Int = DEFAULT_RATE
        private set

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

    /** Stamp of the last sample above the noise floor; 0 when never. */
    @Volatile
    private var lastAudibleAtMs: Long = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens the microphone and starts feeding [ring]. Returns null once the
     * recorder is actually recording, or the [Failure] that stopped it;
     * already-running is a success no-op.
     *
     * [onSampleRate] fires on the caller's thread with the rate the device
     * granted, which is not always the one requested.
     */
    fun start(onSampleRate: (Int) -> Unit = {}): Failure? {
        if (running) return null
        if (!hasPermission()) return Failure.PERMISSION
        val rec = openRecord() ?: return Failure.UNAVAILABLE
        // startRecording(), not the constructor, is where a microphone held by
        // a call or by another app is refused - the constructor succeeds
        // either way - so it runs here, before the caller is told this worked.
        // Started on the worker instead, its failure could only be logged: the
        // switch latched on over a recorder that never ran and the visuals sat
        // flat with nothing to explain it.
        val recording =
            runCatching { rec.startRecording() }.isSuccess &&
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!recording) {
            android.util.Log.w("MicCapture", "startRecording refused")
            runCatching { rec.stop() }
            runCatching { rec.release() }
            return Failure.UNAVAILABLE
        }
        record = rec
        // Generation first, then the flag: a stale worker re-checks both, and
        // the other order has a moment where it sees the new run's `running`
        // while its own generation is still current.
        val generation = ++runGeneration
        running = true
        peakLevel = 0f
        silenceLikely = false
        lastAudibleAtMs = 0L
        onSampleRate(sampleRateHz)
        worker =
            thread(name = "musicviz-mic", isDaemon = true) {
                val floats = FloatArray(READ_FRAMES)
                val shorts = ShortArray(READ_FRAMES)
                val asFloat = rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
                val startedAt = nowMs()
                while (running && runGeneration == generation) {
                    val n =
                        if (asFloat) {
                            rec.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
                        } else {
                            val read = rec.read(shorts, 0, shorts.size)
                            if (read > 0) {
                                for (i in 0 until read) floats[i] = shorts[i] / 32768f
                            }
                            read
                        }
                    // The read may have straddled a stop-then-start pair. If
                    // so, these samples belong to the run that was stopped and
                    // the ring already has a new worker feeding it - writing
                    // them would double-feed it.
                    if (runGeneration != generation) break
                    if (n > 0) {
                        ring.writeInterleaved(floats, n, 1)
                        noteLevel(floats, n, startedAt)
                    } else if (n < 0) {
                        // A negative result is an error code, not a short read:
                        // spinning on it would burn a core for nothing.
                        android.util.Log.w("MicCapture", "AudioRecord.read error $n")
                        break
                    }
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                // Every way out of the loop ends with a released recorder, so
                // `active` must stop reporting an open microphone: after a
                // read error it stayed true forever, leaving the switch on and
                // turning the next start() into a silent no-op. Only the run
                // that is still current may clear it.
                if (runGeneration == generation) running = false
            }
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
    private fun noteLevel(
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
            // Unlike the capture's flag this one can also flip mid-run: a
            // hardware mute switch thrown while listening is exactly the case
            // the hint exists for, so the clock restarts from the last audible
            // sample, not only from the start of the run.
            val quietSince = if (lastAudibleAtMs == 0L) startedAtMs else lastAudibleAtMs
            if (now - quietSince > SILENCE_GRACE_MS) silenceLikely = true
        }
    }

    /** Closes the microphone. Safe to call when already stopped. */
    fun stop() {
        running = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
        // The worker owns stop()/release(); dropping the reference here keeps
        // a second stop() from racing it.
        record = null
        peakLevel = 0f
        silenceLikely = false
    }

    /**
     * Builds an AudioRecord, trying float PCM first (the tap's own format, so
     * the ring buffer sees the same numbers playback would produce) and
     * falling back to 16-bit, then to a smaller rate. Returns null when the
     * device grants none of them.
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

        /** Frames per read: ~23 ms at 44.1 kHz, well under the analyzer's hop. */
        const val READ_FRAMES = 1024

        /** Headroom over the device minimum so a slow frame never drops audio. */
        const val BUFFER_MULTIPLIER = 4

        /**
         * Anything at or below this counts as digital silence. Not zero: a
         * 16-bit source converted to float lands on exact multiples of
         * 1/32768, and the smallest of those must still read as sound.
         */
        const val SILENCE_EPSILON = 1e-6f

        /** How long silence must last before it means "muted" and not "quiet". */
        const val SILENCE_GRACE_MS = 4_000L
    }
}
