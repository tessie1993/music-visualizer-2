package dev.musicviz.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Why a playback-capture start attempt failed, in words a user can act on.
 *
 * Top level rather than nested in [PlaybackCapture]: the state that carries it
 * and the screen that renders it both run on every API level this app
 * supports, and a member of an API-29 class cannot be named from there.
 */
enum class CaptureFailure {
    /** The device is older than Android 10, which introduced the API. */
    UNSUPPORTED,

    /** RECORD_AUDIO has not been granted. */
    PERMISSION,

    /** The screen-capture consent dialog was dismissed or denied. */
    CONSENT,

    /**
     * The device would not open a capture recorder at any format, or refused
     * to start the one it opened.
     */
    UNAVAILABLE,
}

/** True on devices new enough to have the playback-capture API. */
val playbackCaptureSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/**
 * Other apps' audio as a source for the visuals: Spotify, YouTube, a podcast,
 * a game - whatever is coming out of the speaker.
 *
 * Uses Android 10's playback-capture API, which is the ONLY supported way for
 * an ordinary app to see another app's audio. Like [MicCapture] it writes into
 * the SAME [PcmRingBuffer] the playback tap feeds, so the FFT, the beat
 * tracker and every scene downstream are unchanged and unaware of where the
 * samples came from.
 *
 * ## What can and cannot be captured
 *
 * A playing app declares whether it may be captured. The system honours that
 * declaration, and there is no way around it that does not involve rooting the
 * device or a system signature - so this class does not try. Concretely:
 *
 *  - Apps that allow capture (the default, and what YouTube, most podcast
 *    players and most games do) arrive here as real audio.
 *  - Apps that call `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_SYSTEM)` -
 *    Spotify is the well-known one - are not refused at open time. The
 *    recorder starts happily and reads **silence**. That silent-success is
 *    the single most confusing failure mode in this whole feature, so it is
 *    detected explicitly ([blockedLikely]) and reported as what it is, rather
 *    than left looking like a broken visualizer.
 *
 * Nothing is recorded, buffered to disk or sent anywhere: samples go into the
 * ring buffer, which is overwritten continuously and lives only in memory.
 *
 * Constructible on any API level - only [start] needs Android 10, so the
 * ViewModel can hold one unconditionally and the version gate lives at the one
 * place that actually touches the new API.
 */
class PlaybackCapture(
    private val ring: PcmRingBuffer,
) {
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

    /** True while the capture is open and feeding the ring buffer. */
    val active: Boolean get() = running

    /**
     * Rate the capture actually opened at, for the analyzer's `sampleRateHz`.
     * Meaningless while stopped.
     */
    @Volatile
    var sampleRateHz: Int = DEFAULT_RATE
        private set

    /**
     * True once the capture has run for [SILENCE_GRACE_MS] without a single
     * non-zero sample.
     *
     * Read as "the app being played almost certainly forbids capture" - which
     * is the only ordinary way to get a healthy recorder producing perfect
     * digital silence. It is a heuristic and says so: genuinely silent audio,
     * or a paused player, looks identical from here, which is why the UI pairs
     * it with "is anything actually playing" from the media-session bridge
     * before it accuses anyone.
     */
    @Volatile
    var blockedLikely: Boolean = false
        private set

    /** Elapsed-time stamp of the last non-zero sample; 0 when never. */
    @Volatile
    private var lastAudibleAtMs: Long = 0L

    /**
     * Opens the capture and starts feeding [ring]. Returns null once the
     * recorder is actually recording, or the [CaptureFailure] that stopped it;
     * already-running is a success no-op.
     *
     * [onSampleRate] fires on the caller's thread with the rate the device
     * granted, which is not always the one requested.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(
        projection: MediaProjection,
        onSampleRate: (Int) -> Unit = {},
    ): CaptureFailure? {
        if (running) return null
        val rec = openRecord(projection) ?: return CaptureFailure.UNAVAILABLE
        // startRecording(), not the builder, is where the system actually
        // refuses a capture, so it runs here, before the caller is told this
        // worked. Started on the worker instead, its failure could only be
        // logged - and the foreground service and its "reading the audio
        // playing on this device" notification stayed up over a capture that
        // never began.
        val recording =
            runCatching { rec.startRecording() }.isSuccess &&
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!recording) {
            android.util.Log.w("PlaybackCapture", "startRecording refused")
            runCatching { rec.stop() }
            runCatching { rec.release() }
            return CaptureFailure.UNAVAILABLE
        }
        record = rec
        // Generation first, then the flag: a stale worker re-checks both, and
        // the other order has a moment where it sees the new run's `running`
        // while its own generation is still current.
        val generation = ++runGeneration
        running = true
        blockedLikely = false
        lastAudibleAtMs = 0L
        onSampleRate(sampleRateHz)
        val channels = channelCount
        worker =
            thread(name = "musicviz-playback-capture", isDaemon = true) {
                val floats = FloatArray(READ_FRAMES * channels)
                val shorts = ShortArray(READ_FRAMES * channels)
                val asFloat = rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
                val startedAt = android.os.SystemClock.elapsedRealtime()
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
                        val frames = n / channels
                        if (frames > 0) {
                            ring.writeInterleaved(floats, frames, channels)
                            noteLevel(floats, n, startedAt)
                        }
                    } else if (n < 0) {
                        // A negative result is an error code, not a short read:
                        // spinning on it would burn a core for nothing.
                        android.util.Log.w("PlaybackCapture", "AudioRecord.read error $n")
                        break
                    }
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                // Every way out of the loop ends with a released recorder, so
                // `active` must stop reporting a running capture: after a read
                // error it stayed true forever, and the card kept claiming to
                // be listening to a stream nothing was reading. Only the run
                // that is still current may clear it.
                if (runGeneration == generation) running = false
            }
        return null
    }

    /**
     * Updates [blockedLikely] from this buffer.
     *
     * The bar is "any sample at all above the noise floor", not a loudness
     * threshold: a capture that is working produces dither and rounding even
     * during quiet passages, and one that is being refused produces exact
     * zeroes. The grace period exists because the first buffers after
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (peak > SILENCE_EPSILON) {
            lastAudibleAtMs = now
            blockedLikely = false
        } else if (lastAudibleAtMs == 0L && now - startedAtMs > SILENCE_GRACE_MS) {
            blockedLikely = true
        }
    }

    /** Closes the capture. Safe to call when already stopped. */
    fun stop() {
        running = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
        // The worker owns stop()/release(); dropping the reference here keeps
        // a second stop() from racing it.
        record = null
        blockedLikely = false
    }

    @Volatile
    private var channelCount: Int = 1

    /**
     * Builds a capture AudioRecord, preferring the tap's own float format so
     * the ring buffer sees the same numbers playback would produce, and
     * falling back through 16-bit and lower rates. Returns null when the
     * device grants none of them.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openRecord(projection: MediaProjection): AudioRecord? {
        val config =
            AudioPlaybackCaptureConfiguration
                .Builder(projection)
                // The three usages the platform allows capturing. Everything
                // else (a call, an alarm, an accessibility prompt) is excluded
                // by the system regardless of what we ask for.
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        for (rate in intArrayOf(DEFAULT_RATE, 44_100, 22_050)) {
            for (mask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
                for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                    val min = AudioRecord.getMinBufferSize(rate, mask, encoding)
                    if (min <= 0) continue
                    val channels = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                    val rec =
                        runCatching {
                            @Suppress("MissingPermission")
                            AudioRecord
                                .Builder()
                                .setAudioPlaybackCaptureConfig(config)
                                .setAudioFormat(
                                    AudioFormat
                                        .Builder()
                                        .setEncoding(encoding)
                                        .setSampleRate(rate)
                                        .setChannelMask(mask)
                                        .build(),
                                ).setBufferSizeInBytes(
                                    (min * BUFFER_MULTIPLIER).coerceAtLeast(READ_FRAMES * channels * 4),
                                ).build()
                        }.getOrNull()
                    if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                        sampleRateHz = rate
                        channelCount = channels
                        return rec
                    }
                    runCatching { rec?.release() }
                }
            }
        }
        return null
    }

    private companion object {
        const val DEFAULT_RATE = 48_000

        /** Frames per read: ~21 ms at 48 kHz, well under the analyzer's hop. */
        const val READ_FRAMES = 1024

        /** Headroom over the device minimum so a slow frame never drops audio. */
        const val BUFFER_MULTIPLIER = 4

        /**
         * Anything at or below this counts as digital silence. Not zero: a
         * 16-bit source converted to float lands on exact multiples of 1/32768,
         * and the smallest of those must still read as sound.
         */
        const val SILENCE_EPSILON = 1e-6f

        /** How long silence must last before it means "refused" and not "starting". */
        const val SILENCE_GRACE_MS = 4_000L
    }
}
