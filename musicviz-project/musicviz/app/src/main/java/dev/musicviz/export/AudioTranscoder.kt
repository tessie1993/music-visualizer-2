package dev.musicviz.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Transcodes any source audio track to AAC-LC so it can be muxed into MP4.
 *
 * MP4 containers cannot carry MP3/Vorbis/FLAC tracks, so passthrough muxing
 * fails for most user files; re-encoding is the universal path.
 */
class AudioTranscoder(private val context: Context) {
    class Result(
        val format: MediaFormat,
        val data: ByteArray,
        val sampleInfos: List<SampleInfo>,
    )

    class SampleInfo(val offset: Int, val size: Int, val presentationTimeUs: Long, val flags: Int)

    fun transcode(
        uri: Uri,
        maxDurationMs: Long,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex =
            (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track in source file")
        val srcFormat = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val mime = requireNotNull(srcFormat.getString(MediaFormat.KEY_MIME))
        val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtMost(2)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        val encFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val out = ByteArrayOutputStream()
        val infos = mutableListOf<SampleInfo>()
        var outFormat: MediaFormat? = null
        val maxUs = maxDurationMs * 1000
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        var pcmCarry: ByteBuffer? = null
        var carryTimeUs = 0L

        fun feedEncoder(): Boolean {
            val carry = pcmCarry ?: return true
            val inIndex = encoder.dequeueInputBuffer(10_000)
            if (inIndex < 0) return false
            val inBuf = encoder.getInputBuffer(inIndex)!!
            val toWrite = minOf(inBuf.remaining(), carry.remaining())
            val slice = carry.duplicate().apply { limit(position() + toWrite) }
            inBuf.put(slice)
            val bytesPerUs = sampleRate.toLong() * channels * 2 / 1_000_000.0
            encoder.queueInputBuffer(inIndex, 0, toWrite, carryTimeUs, 0)
            carryTimeUs += (toWrite / bytesPerUs).toLong()
            carry.position(carry.position() + toWrite)
            if (!carry.hasRemaining()) pcmCarry = null
            return pcmCarry == null
        }

        while (!encoderDone) {
            if (!extractorDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0 || (maxUs > 0 && extractor.sampleTime > maxUs)) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        if (maxUs > 0) onProgress((extractor.sampleTime / maxUs.toFloat()).coerceIn(0f, 1f))
                        extractor.advance()
                    }
                }
            }
            if (!decoderDone && pcmCarry == null) {
                val outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000)
                if (outIndex >= 0) {
                    if (decInfo.size > 0) {
                        val buf = decoder.getOutputBuffer(outIndex)!!
                        buf.position(decInfo.offset)
                        buf.limit(decInfo.offset + decInfo.size)
                        val copy = ByteBuffer.allocate(decInfo.size)
                        copy.put(buf)
                        copy.flip()
                        pcmCarry = copy
                        carryTimeUs = decInfo.presentationTimeUs
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                }
            }
            if (pcmCarry != null) {
                feedEncoder()
            } else if (decoderDone) {
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    encoder.queueInputBuffer(inIndex, 0, 0, carryTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    decoderDone = false // EOS sent once; stop re-sending
                }
            }
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(encInfo, 0)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = encoder.outputFormat
                } else if (outIndex >= 0) {
                    if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = encoder.getOutputBuffer(outIndex)!!
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        val bytes = ByteArray(encInfo.size)
                        buf.get(bytes)
                        infos += SampleInfo(out.size(), encInfo.size, encInfo.presentationTimeUs, encInfo.flags)
                        out.write(bytes)
                    }
                    val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (eos) {
                        encoderDone = true
                        break
                    }
                } else {
                    break
                }
            }
        }
        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()
        extractor.release()
        return Result(requireNotNull(outFormat) { "AAC encoder produced no format" }, out.toByteArray(), infos)
    }
}
