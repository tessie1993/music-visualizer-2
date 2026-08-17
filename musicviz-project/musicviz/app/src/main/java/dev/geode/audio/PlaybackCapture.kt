package dev.geode.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
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
 * samples came from - and like [MicCapture] its worker thread, generation
 * fence and recorder release live in the shared [AudioCapturePump]; this
 * class supplies the capture recorder and the blocked-app metering.
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
    sink: dev.geode.engine.audio.PcmSink,
) : AudioCapturePump(sink, DEFAULT_RATE) {
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

    @Volatile
    private var channelCount: Int = 1

    override val threadName = "geode-playback-capture"

    /**
     * Opens the capture and starts feeding the ring. Returns null once the
     * recorder is actually recording, or the [CaptureFailure] that stopped it;
     * already-running is a success no-op.
     *
     * [onSampleRate] fires on the caller's thread with the rate the device
     * granted, which is not always the one requested.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @Synchronized
    fun start(
        projection: MediaProjection,
        onSampleRate: (Int) -> Unit = {},
    ): CaptureFailure? {
        if (active) return null
        val rec = openRecord(projection) ?: return CaptureFailure.UNAVAILABLE
        if (!startPump(rec, channelCount, onSampleRate)) return CaptureFailure.UNAVAILABLE
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
        val now = nowMs()
        if (peak > SILENCE_EPSILON) {
            lastAudibleAtMs = now
            blockedLikely = false
        } else {
            // Like MicCapture's flag, this one can flip mid-run: switching to
            // an app that forbids capture (Spotify after YouTube) is exactly
            // the case the hint exists for, so the clock restarts from the
            // last audible sample, not only from the start of the run.
            val quietSince = if (lastAudibleAtMs == 0L) startedAtMs else lastAudibleAtMs
            if (now - quietSince > SILENCE_GRACE_MS) blockedLikely = true
        }
    }

    /** Cleared on start and stop: the hint must not outlive its capture. */
    override fun resetLevel() {
        blockedLikely = false
    }

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
    }
}
