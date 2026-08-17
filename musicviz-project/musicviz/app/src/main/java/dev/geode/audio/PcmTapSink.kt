package dev.geode.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Superseded by `dev.geode.engine.audioandroid.PcmTap`, which is what
 * playback actually uses. Nothing in production constructs this any more.
 *
 * It survives one slice as the oracle `PcmTapParityTest` compares the
 * replacement against - MASTER_PLAN §2.1 rule 7 forbids deleting a legacy seam
 * in the slice that introduces its replacement, and §12 names waveform
 * fixtures as the migration proof. **Do not extend it**; V2-2-03b deletes it.
 *
 * Receives raw PCM from ExoPlayer's audio pipeline via [TeeAudioProcessor].
 * Runs on the playback thread: copy out fast, never block - which it does
 * imperfectly, allocating 120 bytes per callback. That is the defect the
 * replacement exists to fix.
 */
@OptIn(UnstableApi::class)
class PcmTapSink(
    private val ring: PcmRingBuffer,
    private val onFormat: (sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit,
) : TeeAudioProcessor.AudioBufferSink {
    private var channelCount: Int = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT
    private var scratch: FloatArray = FloatArray(0)

    override fun flush(
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        this.channelCount = channelCount
        this.encoding = encoding
        onFormat(sampleRateHz, channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = b.asShortBuffer()
                val n = sb.remaining()
                if (n == 0 || channelCount <= 0) return
                if (scratch.size < n) scratch = FloatArray(n)
                for (i in 0 until n) scratch[i] = sb.get(i) / 32768f
                ring.writeInterleaved(scratch, n / channelCount, channelCount)
            }
            C.ENCODING_PCM_FLOAT -> {
                val fb = b.asFloatBuffer()
                val n = fb.remaining()
                if (n == 0 || channelCount <= 0) return
                if (scratch.size < n) scratch = FloatArray(n)
                fb.get(scratch, 0, n)
                ring.writeInterleaved(scratch, n / channelCount, channelCount)
            }
            else -> Unit
        }
    }
}
