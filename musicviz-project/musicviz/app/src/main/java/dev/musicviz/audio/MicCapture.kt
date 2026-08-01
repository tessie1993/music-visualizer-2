package dev.musicviz.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

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
     * from under the run that replaced it.
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
        running = true
        val generation = ++runGeneration
        onSampleRate(sampleRateHz)
        worker =
            thread(name = "musicviz-mic", isDaemon = true) {
                val floats = FloatArray(READ_FRAMES)
                val shorts = ShortArray(READ_FRAMES)
                val asFloat = rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
                while (running) {
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
                    if (n > 0) {
                        ring.writeInterleaved(floats, n, 1)
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

    /** Closes the microphone. Safe to call when already stopped. */
    fun stop() {
        running = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
        // The worker owns stop()/release(); dropping the reference here keeps
        // a second stop() from racing it.
        record = null
    }

    /**
     * Builds an AudioRecord, trying float PCM first (the tap's own format, so
     * the ring buffer sees the same numbers playback would produce) and
     * falling back to 16-bit, then to a smaller rate. Returns null when the
     * device grants none of them.
     */
    private fun openRecord(): AudioRecord? {
        for (rate in intArrayOf(DEFAULT_RATE, 48_000, 22_050)) {
            for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
                if (min <= 0) continue
                val rec =
                    runCatching {
                        @Suppress("MissingPermission")
                        AudioRecord(
                            // UNPROCESSED asks the device to skip the AGC and
                            // noise suppression the voice sources apply -
                            // those flatten exactly the dynamics the beat
                            // tracker keys off. Not every device honours it,
                            // and the constructor falls back on its own.
                            MediaRecorder.AudioSource.UNPROCESSED,
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

    private companion object {
        const val DEFAULT_RATE = 44_100

        /** Frames per read: ~23 ms at 44.1 kHz, well under the analyzer's hop. */
        const val READ_FRAMES = 1024

        /** Headroom over the device minimum so a slow frame never drops audio. */
        const val BUFFER_MULTIPLIER = 4
    }
}
