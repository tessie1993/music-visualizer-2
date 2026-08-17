package dev.geode.audio

import android.media.AudioFormat
import android.media.AudioRecord
import androidx.annotation.AnyThread
import kotlin.concurrent.thread

/**
 * The lifecycle and pump loop shared by [MicCapture] and [PlaybackCapture]:
 * one AudioRecord read on one dedicated worker thread, feeding the one
 * [PcmRingBuffer] every consumer downstream reads. A plain thread, not a
 * coroutine, on purpose - the loop lives inside a blocking AudioRecord.read
 * and owns the recorder's release.
 *
 * Subclasses supply the recorder (their own `openRecord`), the worker's name
 * and the signal metering ([noteLevel]/[resetLevel]); the start handshake,
 * the generation fence, the short/float read branch, the error exit and the
 * release epilogue live here exactly once, so the two captures cannot drift
 * apart.
 */
abstract class AudioCapturePump(
    private val sink: dev.geode.engine.audio.PcmSink,
    defaultRateHz: Int,
    /** Injectable for tests; production uses the monotonic elapsed clock. */
    protected val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /**
     * Bumped on every start, so a worker that outlived its own [stop] - a
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
    var sampleRateHz: Int = defaultRateHz
        protected set

    /** Stamp of the last sample above the noise floor; 0 when never. */
    @Volatile
    protected var lastAudibleAtMs: Long = 0L

    /** Name for the worker thread, so a stuck capture is findable in a trace. */
    protected abstract val threadName: String

    /** Updates the subclass's signal meter from a buffer the pump just wrote. */
    protected abstract fun noteLevel(
        buffer: FloatArray,
        count: Int,
        startedAtMs: Long,
    )

    /** Clears the subclass's signal meter; runs on every start and stop. */
    protected abstract fun resetLevel()

    /**
     * Starts [rec] and, on success, spawns the worker that pumps it into
     * [ring]. Returns false - with the recorder already released - when the
     * device refuses to record: startRecording(), not construction, is where
     * a source held by a call, another app or the system is actually refused,
     * so it runs here, before the caller is told this worked. Started on the
     * worker instead, its failure could only be logged, and the caller
     * latched "on" over a recorder that never ran.
     *
     * Synchronized on the pump (as are [stop] and the subclasses' `start`):
     * callers are main-thread by convention, but the convention was the only
     * thing keeping two racing starts from opening two recorders, and the
     * generation fence guards the ring, not the recorder handle.
     */
    @AnyThread
    @Synchronized
    protected fun startPump(
        rec: AudioRecord,
        channels: Int,
        onSampleRate: (Int) -> Unit,
    ): Boolean {
        val recording =
            runCatching { rec.startRecording() }.isSuccess &&
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!recording) {
            android.util.Log.w(javaClass.simpleName, "startRecording refused")
            runCatching { rec.stop() }
            runCatching { rec.release() }
            return false
        }
        record = rec
        // Generation first, then the flag: a stale worker re-checks both, and
        // the other order has a moment where it sees the new run's `running`
        // while its own generation is still current.
        val generation = ++runGeneration
        running = true
        lastAudibleAtMs = 0L
        resetLevel()
        onSampleRate(sampleRateHz)
        worker =
            thread(name = threadName, isDaemon = true) {
                val floats = FloatArray(READ_FRAMES * channels)
                val shorts = ShortArray(READ_FRAMES * channels)
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
                        val frames = n / channels
                        if (frames > 0) {
                            sink.write(floats, frames, channels)
                            noteLevel(floats, n, startedAt)
                        }
                    } else if (n < 0) {
                        // A negative result is an error code, not a short read:
                        // spinning on it would burn a core for nothing.
                        android.util.Log.w(javaClass.simpleName, "AudioRecord.read error $n")
                        break
                    }
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                // Every way out of the loop ends with a released recorder, so
                // `active` must stop reporting an open capture: after a read
                // error it stayed true forever, leaving the caller's switch on
                // and turning the next start into a silent no-op. Only the run
                // that is still current may clear it.
                if (runGeneration == generation) running = false
            }
        return true
    }

    /** Closes the capture. Safe to call when already stopped. */
    @AnyThread
    @Synchronized
    fun stop() {
        running = false
        // The worker's read() is blocking (READ_BLOCKING / the default
        // blocking read) and only re-checks `running` between reads, so
        // clearing the flag alone does nothing while a read is in progress -
        // a HAL stall or a source handoff can delay the next buffer well past
        // the join below. AudioRecord.stop() is what actually unblocks a
        // pending read; called here, before the join, instead of waiting for
        // the worker's own loop-exit stop() to run. Without this a slow
        // device left the worker thread and the native recorder alive after
        // stop() returned, so the source stayed open and a second start raced
        // it for the same device.
        record?.let { runCatching { it.stop() } }
        worker?.let { runCatching { it.join(500) } }
        worker = null
        // The worker owns release(); dropping the reference here keeps a
        // second stop() from racing it.
        record = null
        resetLevel()
    }

    protected companion object {
        /** Frames per read: ~21-23 ms at either default rate, well under the analyzer's hop. */
        const val READ_FRAMES = 1024

        /** Headroom over the device minimum so a slow frame never drops audio. */
        const val BUFFER_MULTIPLIER = 4

        /**
         * Anything at or below this counts as digital silence. Not zero: a
         * 16-bit source converted to float lands on exact multiples of
         * 1/32768, and the smallest of those must still read as sound.
         */
        const val SILENCE_EPSILON = 1e-6f

        /** How long silence must last before it means more than "quiet". */
        const val SILENCE_GRACE_MS = 4_000L
    }
}
