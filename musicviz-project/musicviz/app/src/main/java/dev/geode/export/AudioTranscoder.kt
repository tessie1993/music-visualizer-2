package dev.geode.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Transcodes any source audio track to AAC-LC so it can be muxed into MP4.
 *
 * MP4 containers cannot carry MP3/Vorbis/FLAC tracks, so passthrough muxing
 * fails for most user files; re-encoding is the universal path.
 *
 * Encoded samples stream to a temp file in app cache (an hour of 192 kbps AAC
 * is ~86 MB - buffering it in RAM caused OOM on long-form exports). Callers
 * must invoke [Result.release] when done to delete the temp file.
 */
class AudioTranscoder(
    private val context: Context,
) {
    class Result(
        val format: MediaFormat,
        val file: File,
        val sampleInfos: List<SampleInfo>,
    ) {
        /**
         * Actual duration of the transcoded audio, measured from the last
         * encoded sample. This - not any metadata or analysis estimate - is
         * what the exported video length is matched against, so the export
         * always runs exactly as long as the music.
         */
        val durationUs: Long =
            sampleInfos.lastOrNull()?.let { last ->
                // One AAC frame is 1024 PCM samples; extend past the last PTS
                // so the final frame's own duration is included.
                last.presentationTimeUs + 24_000L
            } ?: 0L

        fun release() {
            runCatching { file.delete() }
        }
    }

    class SampleInfo(
        val offset: Long,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    /** Folds interleaved 16-bit PCM from [srcCh] channels down to [dstCh]. */
    private fun downmix(
        src: ByteBuffer,
        srcCh: Int,
        dstCh: Int,
    ): ByteBuffer {
        val sb = src.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val frames = sb.remaining() / srcCh
        val out = ByteBuffer.allocate(frames * dstCh * 2).order(java.nio.ByteOrder.nativeOrder())
        val ob = out.asShortBuffer()
        for (f in 0 until frames) {
            val base = f * srcCh
            if (dstCh == 1) {
                var acc = 0
                for (c in 0 until srcCh) acc += sb.get(base + c)
                ob.put((acc / srcCh).toShort())
            } else {
                var rest = 0
                for (c in 2 until srcCh) rest += sb.get(base + c)
                val fold = if (srcCh > 2) rest / (srcCh - 2) / 2 else 0
                ob.put((sb.get(base).toInt() + fold).coerceIn(-32768, 32767).toShort())
                ob.put((sb.get(base + 1).toInt() + fold).coerceIn(-32768, 32767).toShort())
            }
        }
        out.limit(frames * dstCh * 2)
        return out
    }

    /**
     * AIFF export path: the platform decoder stack can't read AIFF, so PCM
     * comes straight from [dev.geode.audio.AiffPcm] into the AAC encoder.
     * Multichannel sources are downmixed with the same fold as the decoder
     * path (L/R kept, remaining channels folded in at half weight).
     */
    private fun transcodeAiff(
        aiff: dev.geode.audio.AiffPcm,
        maxDurationMs: Long,
        startMs: Long,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Result {
        val channels = aiff.channels.coerceAtMost(2)
        val sampleRate = aiff.sampleRate
        val encFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
        val encoder: MediaCodec
        val outFile: File
        val out: BufferedOutputStream
        var encoderRef: MediaCodec? = null
        var outFileRef: File? = null
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { encoderRef = it }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            outFile = File.createTempFile("geode_aac_", ".bin", context.cacheDir).also { outFileRef = it }
            out = BufferedOutputStream(FileOutputStream(outFile))
        } catch (t: Throwable) {
            runCatching { encoderRef?.release() }
            runCatching { outFileRef?.delete() }
            runCatching { aiff.close() }
            throw t
        }
        var outBytes = 0L
        val infos = mutableListOf<SampleInfo>()
        var outFormat: MediaFormat? = null
        val maxUs = maxDurationMs * 1000
        val encInfo = MediaCodec.BufferInfo()
        val readBuf = ShortArray(16384 - (16384 % aiff.channels))
        var srcDone = false
        var eosSent = false
        var encoderDone = false
        var pcmCarry: ByteBuffer? = null
        var carryTimeUs = 0L
        var fedBytes = 0L
        var progressed = false
        var stallIterations = 0
        // AiffPcm reads forward only, so a start offset is reached by reading
        // and discarding. Uncompressed PCM has no sync frames, so this is exact
        // rather than the nearest-keyframe approximation the codec path needs.
        if (startMs > 0) {
            var toSkip = startMs * aiff.sampleRate / 1000 * aiff.channels
            while (toSkip > 0) {
                val want = minOf(toSkip, readBuf.size.toLong()).toInt()
                val n = aiff.read(if (want == readBuf.size) readBuf else ShortArray(want))
                if (n <= 0) break
                toSkip -= n
            }
        }
        try {
            while (!encoderDone) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("Export cancelled")
                progressed = false
                if (pcmCarry == null && !srcDone) {
                    val n = aiff.read(readBuf)
                    if (n <= 0 || (maxUs > 0 && carryTimeUs > maxUs)) {
                        srcDone = true
                    } else {
                        val frames = n / aiff.channels
                        val bb = ByteBuffer.allocate(frames * channels * 2).order(java.nio.ByteOrder.nativeOrder())
                        val sb = bb.asShortBuffer()
                        if (aiff.channels <= 2) {
                            sb.put(readBuf, 0, n)
                        } else {
                            for (f in 0 until frames) {
                                val base = f * aiff.channels
                                var rest = 0
                                for (c in 2 until aiff.channels) rest += readBuf[base + c]
                                val fold = rest / (aiff.channels - 2) / 2
                                sb.put((readBuf[base] + fold).coerceIn(-32768, 32767).toShort())
                                sb.put((readBuf[base + 1] + fold).coerceIn(-32768, 32767).toShort())
                            }
                        }
                        bb.limit(frames * channels * 2)
                        pcmCarry = bb
                        onProgress(aiff.progress)
                    }
                    progressed = true
                }
                if (pcmCarry != null) {
                    val carry = pcmCarry!!
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = checkNotNull(encoder.getInputBuffer(inIndex)) { "encoder input buffer null (codec error state)" }
                        val toWrite = minOf(inBuf.remaining(), carry.remaining())
                        val slice = carry.duplicate().apply { limit(position() + toWrite) }
                        inBuf.put(slice)
                        encoder.queueInputBuffer(inIndex, 0, toWrite, carryTimeUs, 0)
                        fedBytes += toWrite
                        carryTimeUs = fedBytes * 1_000_000L / (sampleRate.toLong() * channels * 2)
                        carry.position(carry.position() + toWrite)
                        if (!carry.hasRemaining()) pcmCarry = null
                        progressed = true
                    }
                } else if (srcDone && !eosSent) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(inIndex, 0, 0, carryTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                        progressed = true
                    }
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(encInfo, if (eosSent) 10_000 else 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = encoder.outputFormat
                        progressed = true
                        continue
                    }
                    if (outIndex >= 0) {
                        progressed = true
                        if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buf = checkNotNull(encoder.getOutputBuffer(outIndex)) { "encoder output buffer null (codec error state)" }
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            val bytes = ByteArray(encInfo.size)
                            buf.get(bytes)
                            infos += SampleInfo(outBytes, encInfo.size, encInfo.presentationTimeUs, encInfo.flags)
                            out.write(bytes)
                            outBytes += encInfo.size
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
                // Same wedge guard as the decoder path: fail loudly instead of
                // spinning at 10 ms waits until the user cancels.
                if (progressed) {
                    stallIterations = 0
                } else if (++stallIterations > STALL_LIMIT) {
                    throw IllegalStateException("Audio transcode stalled (codec made no progress)")
                }
            }
            out.flush()
            // Inside the try so the catch below deletes the temp file: the AAC
            // bytes are written whether or not the encoder ever reported its
            // output format, so failing this check after the finally orphaned
            // a file that can be the whole ~86 MB stream.
            return Result(requireNotNull(outFormat) { "AAC encoder produced no format" }, outFile, infos)
        } catch (t: Throwable) {
            runCatching { out.close() }
            runCatching { outFile.delete() }
            throw t
        } finally {
            runCatching { out.close() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { aiff.close() }
        }
    }

    /**
     * @param startMs where in the source to begin. The output is rebased to
     *   zero, so a clip from 1:30 starts at 0:00 in the file it lands in.
     * @param maxDurationMs how much to take from [startMs]; 0 means "to the end".
     */
    fun transcode(
        uri: Uri,
        maxDurationMs: Long,
        startMs: Long = 0L,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Result {
        dev.geode.audio.AiffPcm.open(context, uri)?.let { aiff ->
            return transcodeAiff(aiff, maxDurationMs, startMs, isCancelled, onProgress)
        }
        val extractor = MediaExtractor()
        val srcFormat: MediaFormat
        var sampleRate: Int
        var srcChannels: Int
        var channels: Int
        val decoder: MediaCodec
        var encoder: MediaCodec? = null
        val outFile: File
        val out: BufferedOutputStream
        var decoderRef: MediaCodec? = null
        var encoderRef: MediaCodec? = null
        var outFileRef: File? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("No audio track in source file")
            srcFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            // SEEK_TO_PREVIOUS_SYNC, not CLOSEST: a decoder needs a sync frame
            // to start from, so landing before the requested point and dropping
            // what comes early is the only way to get sample-accurate audio at
            // an arbitrary offset. Dropping happens on the DECODER's output
            // below, where timestamps are trustworthy.
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = requireNotNull(srcFormat.getString(MediaFormat.KEY_MIME))
            sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            srcChannels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            channels = srcChannels.coerceAtMost(2)

            decoder = MediaCodec.createDecoderByType(mime).also { decoderRef = it }
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            outFile = File.createTempFile("geode_aac_", ".bin", context.cacheDir).also { outFileRef = it }
            out = BufferedOutputStream(FileOutputStream(outFile))
        } catch (t: Throwable) {
            runCatching { decoderRef?.release() }
            runCatching { outFileRef?.delete() }
            runCatching { extractor.release() }
            throw t
        }

        // The encoder is NOT configured from the container format: for
        // HE-AAC v1/v2 the container understates the rate (SBR doubles it)
        // and Parametric Stereo understates the channels (1 declared, 2
        // decoded), so an encoder configured up front ran at the wrong rate -
        // chipmunk or garbled audio in the exported file, silently. It is
        // created here instead, on the first decoded buffer, from what the
        // DECODER says it is emitting; the container values above remain only
        // as the fallback for a stream that dies before producing anything.
        fun ensureEncoder(decoderFormat: MediaFormat?) {
            if (encoder != null) return
            if (decoderFormat != null) {
                if (decoderFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = decoderFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (decoderFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    srcChannels = decoderFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    channels = srcChannels.coerceAtMost(2)
                }
            }
            val encFormat =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                }
            encoder =
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    encoderRef = it
                    it.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                }
        }
        var outBytes = 0L
        val infos = mutableListOf<SampleInfo>()
        var outFormat: MediaFormat? = null
        val maxUs = maxDurationMs * 1000
        val startUs = startMs * 1000
        // The extractor is compared against an ABSOLUTE end; the encoder is fed
        // timestamps rebased to zero. Keeping the two apart is what stops a
        // ranged export from either stopping early or writing negative
        // timestamps into the muxer.
        val endUs = if (maxUs > 0) startUs + maxUs else 0L
        // For progress reporting when uncapped, estimate from container metadata.
        val estimatedUs =
            if (maxUs > 0) {
                maxUs
            } else if (srcFormat.containsKey(MediaFormat.KEY_DURATION)) {
                (srcFormat.getLong(MediaFormat.KEY_DURATION) - startUs).coerceAtLeast(0L)
            } else {
                0L
            }
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var eosSent = false
        var encoderDone = false
        var pcmCarry: ByteBuffer? = null
        var carryTimeUs = 0L
        var progressed = false
        var stallIterations = 0

        fun feedEncoder(): Boolean {
            val carry = pcmCarry ?: return true
            val enc = checkNotNull(encoder) { "PCM queued before the encoder existed" }
            val inIndex = enc.dequeueInputBuffer(10_000)
            if (inIndex < 0) return false
            val inBuf = checkNotNull(enc.getInputBuffer(inIndex)) { "encoder input buffer null (codec error state)" }
            val toWrite = minOf(inBuf.remaining(), carry.remaining())
            val slice = carry.duplicate().apply { limit(position() + toWrite) }
            inBuf.put(slice)
            val bytesPerUs = sampleRate.toLong() * channels * 2 / 1_000_000.0
            enc.queueInputBuffer(inIndex, 0, toWrite, carryTimeUs, 0)
            carryTimeUs += (toWrite / bytesPerUs).toLong()
            carry.position(carry.position() + toWrite)
            if (!carry.hasRemaining()) pcmCarry = null
            progressed = true
            return pcmCarry == null
        }

        try {
            while (!encoderDone) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("Export cancelled")
                progressed = false
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = checkNotNull(decoder.getInputBuffer(inIndex)) { "decoder input buffer null (codec error state)" }
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0 || (endUs > 0 && extractor.sampleTime > endUs)) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (estimatedUs > 0) onProgress((extractor.sampleTime / estimatedUs.toFloat()).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                if (!decoderDone && pcmCarry == null) {
                    val outIndex = decoder.dequeueOutputBuffer(decInfo, 10_000)
                    if (outIndex >= 0) {
                        progressed = true
                        if (decInfo.size > 0) {
                            val buf = checkNotNull(decoder.getOutputBuffer(outIndex)) { "decoder output buffer null (codec error state)" }
                            buf.position(decInfo.offset)
                            buf.limit(decInfo.offset + decInfo.size)
                            val outFmt = decoder.outputFormat
                            ensureEncoder(outFmt)
                            val pcmEnc =
                                if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    android.media.AudioFormat.ENCODING_PCM_16BIT
                                }
                            val copy: ByteBuffer
                            if (pcmEnc == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                // Some decoders emit float PCM; the AAC encoder (and
                                // the 2-bytes/sample timestamp math) expects 16-bit.
                                val fb = buf.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                                val n = fb.remaining()
                                copy = ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.nativeOrder())
                                val sb = copy.asShortBuffer()
                                for (i in 0 until n) {
                                    sb.put((fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                                }
                                copy.limit(n * 2)
                            } else {
                                copy = ByteBuffer.allocate(decInfo.size)
                                copy.put(buf)
                                copy.flip()
                            }
                            // Multichannel sources (5.1 etc.): the AAC encoder
                            // takes at most 2 channels, but the decoder emits
                            // ALL source channels interleaved. Feeding that
                            // stream unchanged garbles the audio and breaks
                            // the bytes-per-microsecond timestamp math, so
                            // fold the frames down to the encoder's channel
                            // count here. The stride comes from THIS buffer's
                            // format, not the container's - they disagree for
                            // Parametric Stereo and friends.
                            val bufChannels =
                                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                } else {
                                    srcChannels
                                }
                            val mixed =
                                if (bufChannels > channels) {
                                    downmix(copy, bufChannels, channels)
                                } else {
                                    copy
                                }
                            // Everything before the requested start decoded only
                            // so the decoder could reach a sync point; it is not
                            // part of the clip.
                            if (decInfo.presentationTimeUs + 1000 < startUs) {
                                decoder.releaseOutputBuffer(outIndex, false)
                                if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                                continue
                            }
                            pcmCarry = mixed
                            carryTimeUs = (decInfo.presentationTimeUs - startUs).coerceAtLeast(0L)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }
                if (pcmCarry != null) {
                    feedEncoder()
                } else if (decoderDone && !eosSent) {
                    // A stream that died before its first buffer still needs an
                    // encoder to carry the EOS; container values are all that
                    // is left to configure it from.
                    ensureEncoder(null)
                    val enc = checkNotNull(encoder)
                    val inIndex = enc.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        enc.queueInputBuffer(inIndex, 0, 0, carryTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        // Flag separately instead of clearing decoderDone: that
                        // hack made every remaining flush iteration block 10 ms
                        // on the finished decoder's dequeue, dragging out the
                        // export tail.
                        eosSent = true
                        progressed = true
                    }
                }
                while (true) {
                    val enc = encoder ?: break
                    val outIndex = enc.dequeueOutputBuffer(encInfo, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = enc.outputFormat
                        progressed = true
                    } else if (outIndex >= 0) {
                        progressed = true
                        if (encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buf = checkNotNull(enc.getOutputBuffer(outIndex)) { "encoder output buffer null (codec error state)" }
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            val bytes = ByteArray(encInfo.size)
                            buf.get(bytes)
                            infos += SampleInfo(outBytes, encInfo.size, encInfo.presentationTimeUs, encInfo.flags)
                            out.write(bytes)
                            outBytes += encInfo.size
                        }
                        val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(outIndex, false)
                        if (eos) {
                            encoderDone = true
                            break
                        }
                    } else {
                        break
                    }
                }
                // A wedged codec used to spin here at 10 ms waits until the
                // user cancelled; the video side's flush already had a bound,
                // this loop had none. Every productive iteration resets the
                // count, so only a codec making NO progress at all trips it.
                if (progressed) {
                    stallIterations = 0
                } else if (++stallIterations > STALL_LIMIT) {
                    throw IllegalStateException("Audio transcode stalled (codec made no progress)")
                }
            }
            out.flush()
            // Inside the try so the catch below deletes the temp file, exactly
            // as in the AIFF path: the AAC bytes are written whether or not
            // the encoder ever reported its output format.
            return Result(requireNotNull(outFormat) { "AAC encoder produced no format" }, outFile, infos)
        } catch (t: Throwable) {
            runCatching { out.close() }
            runCatching { outFile.delete() }
            throw t
        } finally {
            runCatching { out.close() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        /**
         * Consecutive loop iterations with no codec progress (each blocks at
         * least 10 ms on a dequeue) before the transcode is declared wedged
         * and fails loudly instead of spinning until the user cancels.
         */
        const val STALL_LIMIT = 1_000
    }
}
