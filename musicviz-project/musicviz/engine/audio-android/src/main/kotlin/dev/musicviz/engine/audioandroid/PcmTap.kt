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
     */
    @Volatile
    var format: PcmTapFormat? = null
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
        val next =
            PcmTapFormat(
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                encoding = encoding,
                generation = (format?.generation ?: 0) + 1,
            )
        if (channelCount > 0) {
            val floats = STAGING_FRAMES * channelCount
            if (staging.size != floats) staging = FloatArray(floats)
        }
        framesWritten = 0L
        format = next
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
