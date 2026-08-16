package dev.musicviz.engine.audioandroid

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import dev.musicviz.engine.audio.PcmSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts media3's PCM buffers to float frames and hands them to [sink].
 *
 * Runs on the playback thread inside the audio pipeline, which is why the
 * shape of this class is what it is: [handleBuffer] allocates nothing at all.
 * The version this replaced allocated three objects per buffer — a `duplicate`,
 * an `asShortBuffer` view, and a fresh `FloatArray` whenever a bigger buffer
 * arrived — measured at 120 bytes per callback, roughly forty times a second,
 * on the thread whose deadline is the audio device's.
 *
 * Everything that can be decided per format is decided in [flush] instead:
 * [PcmTapFormat.sampleWidth] resolves the encoding once, and the staging array
 * is sized once. [flush] is media3's reconfiguration point, not a per-buffer
 * one, so §5.1's "resize and reformat outside the callback" is satisfied by
 * construction rather than by care.
 *
 * A buffer longer than the staging window is written in order as several
 * chunks rather than growing the array, so the callback's allocation count
 * does not depend on what the decoder hands it.
 */
@OptIn(UnstableApi::class)
class PcmTap(
    private val sink: PcmSink,
    private val onFormat: (PcmTapFormat) -> Unit = {},
) : TeeAudioProcessor.AudioBufferSink {
    /**
     * The current format, or null before the first configuration.
     *
     * Published so a consumer that attaches mid-playback can ask, instead of
     * waiting for the next reconfiguration to be told.
     *
     * **This is the sequence field.** [flush] stores it last, so a reader
     * wanting a coherent (format, frame count) pair reads `format`, then
     * `framesWritten`, then `format` again and retries if it changed. Both are
     * volatile, so neither read may be reordered. Without that protocol a
     * reader can pair a new generation with the previous one's frame count,
     * and get a confident presentation time for audio that is minutes away.
     */
    @Volatile
    var format: PcmTapFormat? = null
        private set

    /**
     * Notified when one generation ends and the next begins.
     *
     * Called after both counters are published, so the listener cannot be the
     * thing that observes them disagreeing - but still inside [flush], which
     * matters: the silence-skipping stage sits after the tap in the chain and
     * zeroes its own per-generation counter when its turn comes, so this is
     * the last moment that number exists.
     */
    @Volatile
    var boundaryListener: TapBoundaryListener? = null

    /**
     * Boundaries whose listener threw. Nothing steers on it; it exists so a
     * silently-swallowed clock fault is still countable rather than invisible.
     */
    @Volatile
    var boundaryFailures: Long = 0L
        private set

    /** Frames delivered to [sink] since the current [PcmTapFormat.generation] began. */
    @Volatile
    var framesWritten: Long = 0L
        private set

    /**
     * Written and read only on the playback thread — media3 flushes and queues
     * from the same thread — so it needs no publication of its own. The two
     * fields above are volatile because other threads read them.
     */
    private var staging: FloatArray = FloatArray(0)

    override fun flush(
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        val ended = format
        val endedFrames = framesWritten
        val next =
            PcmTapFormat(
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                encoding = encoding,
                generation = (ended?.generation ?: 0) + 1,
            )
        if (channelCount > 0) {
            val floats = STAGING_FRAMES * channelCount
            if (staging.size != floats) staging = FloatArray(floats)
        }
        framesWritten = 0L
        format = next
        // Both counters are published before anyone is told, so no listener
        // and no concurrent reader can pair the new generation with the ended
        // one's frame count. The ended values travel as arguments instead.
        //
        // Guarded because this runs inside AudioProcessor.flush: an exception
        // escaping here would propagate into the renderer and stop playback,
        // and would take the format callback below with it - which is what
        // retunes the live analyzer.
        //
        // RuntimeException rather than runCatching, which catches Throwable:
        // that would swallow the AssertionError raised by a test asserting
        // inside the listener, and did - the ordering test above this one was
        // vacuous until the catch was narrowed.
        try {
            boundaryListener?.onTapBoundary(ended, endedFrames, next)
        } catch (survivable: RuntimeException) {
            boundaryFailures++
        }
        onFormat(next)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val active = format ?: return
        val width = active.sampleWidth ?: return
        val channels = active.channelCount
        val perChunk = if (channels > 0) staging.size / channels else 0
        if (perChunk == 0) return

        // The tap is first in the chain, so this is the decoder's own buffer
        // and its byte order is whatever the decoder left set — not reliably
        // the native one. Android PCM is little-endian on the wire regardless.
        // Media3 gives each sink a private read-only view of the buffer, so
        // this is not visible to the rest of the chain.
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val frameBytes = width.bytes * channels
        var at = buffer.position()
        val end = buffer.limit()
        var written = framesWritten
        while (end - at >= frameBytes) {
            val frames = minOf(perChunk, (end - at) / frameBytes)
            val samples = frames * channels
            when (width) {
                PcmSampleWidth.SIGNED_16 -> fill16(buffer, at, samples)
                PcmSampleWidth.FLOAT_32 -> fillFloat(buffer, at, samples)
            }
            sink.write(staging, frames, channels)
            at += samples * width.bytes
            written += frames
        }
        framesWritten = written
    }

    private fun fill16(
        buffer: ByteBuffer,
        from: Int,
        samples: Int,
    ) {
        for (i in 0 until samples) staging[i] = buffer.getShort(from + (i shl 1)) / SHORT_FULL_SCALE
    }

    private fun fillFloat(
        buffer: ByteBuffer,
        from: Int,
        samples: Int,
    ) {
        for (i in 0 until samples) staging[i] = buffer.getFloat(from + (i shl 2))
    }

    private companion object {
        /**
         * Frames the staging array holds. Long enough that one media3 buffer
         * is normally one chunk, small enough that an eight-channel source
         * costs 128 KB rather than sizing for a worst case that never arrives.
         */
        const val STAGING_FRAMES = 4096

        /** 2^15: the divisor that maps `Short.MIN_VALUE` to exactly -1.0. */
        const val SHORT_FULL_SCALE = 32768f
    }
}
