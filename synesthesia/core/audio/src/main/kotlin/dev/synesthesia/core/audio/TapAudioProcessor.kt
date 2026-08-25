package dev.synesthesia.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Pure s16-PCM -> f32 mono downmix core (polarity-safe averaging). Headless-testable. */
object TapConversion {
    class Result(val mono: FloatArray, val frames: Int)

    fun s16ToMonoF32(bytes: ByteArray, offset: Int, length: Int, channels: Int): Result {
        val frames = length / (2 * channels)
        val out = FloatArray(frames)
        var i = offset
        for (f in 0 until frames) {
            var acc = 0f
            repeat(channels) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                acc += ((hi shl 8) or lo) / 32768f
                i += 2
            }
            out[f] = acc / channels
        }
        return Result(out, frames)
    }
}

/**
 * Owned @UnstableApi wrapper (blueprint decision #3): the ONLY Media3
 * audio-processor type outside :feature:player. Tees decoded s16 PCM into a
 * [PcmSink] as f32 mono-analysis while passing playback through untouched.
 *
 * Contract (canonical ingest law): output is f32, mono (stereo downmixed,
 * polarity-safe averaging). A sample-rate change mid-stream fires
 * [onSampleRateChanged] so owners bump ring epoch + flush analyzers.
 */
@OptIn(UnstableApi::class)
class TapAudioProcessor(
    private val sink: PcmSink,
    private val onSampleRateChanged: (Int) -> Unit = {},
) : BaseAudioProcessor() {

    private var seenRate = -1
    private var seenChannels = -1
    private var monoScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        require(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            "TapAudioProcessor requires 16-bit PCM input, got ${inputAudioFormat.encoding}"
        }
        if (inputAudioFormat.sampleRate != seenRate || inputAudioFormat.channelCount != seenChannels) {
            seenRate = inputAudioFormat.sampleRate
            seenChannels = inputAudioFormat.channelCount
            onSampleRateChanged(seenRate)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val frames = remaining / (2 * channels)
        if (monoScratch.size < frames) monoScratch = FloatArray(frames)

        // Zero-allocation tee: convert into scratch + forward BEFORE passthrough.
        var i = inputBuffer.arrayOffset() + inputBuffer.position()
        val array = inputBuffer.array()
        for (f in 0 until frames) {
            var acc = 0f
            repeat(channels) {
                val lo = array[i].toInt() and 0xFF
                val hi = array[i + 1].toInt()
                acc += ((hi shl 8) or lo) / 32768f
                i += 2
            }
            monoScratch[f] = acc / channels
        }
        sink.write(monoScratch, frames, 1)

        // Passthrough: hand the same bytes downstream unchanged.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }
}
