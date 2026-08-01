package dev.musicviz.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes a whole audio file (no playback) via MediaExtractor/MediaCodec and
 * runs the same FFT/feature pipeline as live analysis at a fixed hop.
 *
 * Streaming: decoded PCM is analyzed chunk-by-chunk and discarded, so memory
 * stays constant regardless of track length.
 *
 * The beat gate is driven by the caller's sensitivity settings, exactly as
 * [AnalysisEngine] drives the live one - otherwise every export and every
 * section-driven decision would silently run at the shipped defaults. The
 * timeline also carries the raw onset curve, so [AnalysisCache] can re-decide
 * the beats later without a second decode (see
 * [FeatureTimeline.withBeatSensitivity]).
 */
class OfflineAnalyzer(
    private val context: Context,
) {
    suspend fun analyze(
        uri: Uri,
        beatThresholdSigma: Float = FeatureExtractor.SIGMA_DEFAULT,
        beatMinIntervalMs: Float = FeatureExtractor.INTERVAL_MS_DEFAULT,
        onProgress: (Float) -> Unit = {},
    ): FeatureTimeline =
        withContext(Dispatchers.Default) {
            analyzeBlocking(uri, beatThresholdSigma, beatMinIntervalMs, onProgress)
        }

    fun analyzeBlocking(
        uri: Uri,
        beatThresholdSigma: Float = FeatureExtractor.SIGMA_DEFAULT,
        beatMinIntervalMs: Float = FeatureExtractor.INTERVAL_MS_DEFAULT,
        onProgress: (Float) -> Unit = {},
    ): FeatureTimeline {
        // AIFF first: the platform extractor/codec stack can't read it, but
        // it's plain PCM - stream it straight into the pipeline.
        dev.musicviz.audio.AiffPcm.open(context, uri)?.let { aiff ->
            try {
                val pipeline = StreamingPipeline(beatThresholdSigma, beatMinIntervalMs)
                val buf = ShortArray(16384)
                var last = 0f
                while (true) {
                    val n = aiff.read(buf)
                    if (n <= 0) break
                    pipeline.feed(java.nio.ShortBuffer.wrap(buf, 0, n), aiff.channels, aiff.sampleRate)
                    if (aiff.progress - last > 0.01f) {
                        last = aiff.progress
                        onProgress(last)
                    }
                }
                return pipeline.finish()
            } finally {
                aiff.close()
            }
        }
        val extractor = MediaExtractor()
        var codecRef: MediaCodec? = null
        val pipeline = StreamingPipeline(beatThresholdSigma, beatMinIntervalMs)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = 0f
        // Setup runs inside the try as well: setDataSource on a truncated or
        // DRM file, and createDecoderByType on a codec this device does not
        // have, throw as readily as the decode loop does, and used to leave
        // the extractor holding an open descriptor and the decoder a native
        // instance - which a batch of files accumulates.
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("No audio track in file")
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime).also { codecRef = it }
            codec.configure(format, null, null, 0)
            codec.start()
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) {
                                val p = (extractor.sampleTime / durationUs.toFloat()).coerceIn(0f, 1f)
                                if (p - lastProgress > 0.01f) {
                                    lastProgress = p
                                    onProgress(p)
                                }
                            }
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outFormat = codec.outputFormat
                        val sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val pcmEncoding =
                            if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                android.media.AudioFormat.ENCODING_PCM_16BIT
                            }
                        if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                            pipeline.feedFloat(buf.order(ByteOrder.nativeOrder()).asFloatBuffer(), channels, sampleRate)
                        } else {
                            pipeline.feed(buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), channels, sampleRate)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codecRef?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
        onProgress(1f)
        return pipeline.finish()
    }

    /** Windows a mono stream at a fixed hop, running the shared FFT/feature pipeline. */
    private class StreamingPipeline(
        sigma: Float,
        minIntervalMs: Float,
    ) {
        private val processor = FftProcessor()
        private val keyDetector = KeyDetector()
        private val smoother = BandSmoother(processor.bandCount)
        private val extractor =
            FeatureExtractor(processor.bandCount, hopRateHz = HOP_RATE_HZ).also {
                // Clamped to the same bounds AnalysisEngine uses for the live
                // extractor, so the two paths cannot diverge at the extremes.
                it.beatThresholdSigma = sigma.coerceIn(FeatureExtractor.SIGMA_MIN, FeatureExtractor.SIGMA_MAX)
                it.beatMinIntervalMs =
                    minIntervalMs.coerceIn(FeatureExtractor.INTERVAL_MS_MIN, FeatureExtractor.INTERVAL_MS_MAX)
            }
        private val window = FloatArray(processor.fftSize)
        private val raw = FloatArray(processor.bandCount)
        private val smoothed = FloatArray(processor.bandCount)
        private val waveform = FloatArray(128)
        private val frames = ArrayList<TimelineFrame>(16_384)
        private var buffer = FloatArray(processor.fftSize * 4)
        private var buffered = 0
        private var sampleRate = 44100
        private var hopSamples = sampleRate / 60

        /** Absolute mono-sample index of the current window start; timestamps
         *  derive from this so they never drift (1000/60 truncates to 16 ms). */
        private var absSample = 0L

        fun feed(
            pcm: java.nio.ShortBuffer,
            channels: Int,
            sampleRateHz: Int,
        ) {
            if (sampleRateHz != sampleRate) {
                sampleRate = sampleRateHz
                hopSamples = (sampleRate / 60).coerceAtLeast(1)
            }
            val frameCount = pcm.remaining() / channels
            if (buffered + frameCount > buffer.size) {
                buffer = buffer.copyOf((buffered + frameCount).coerceAtLeast(buffer.size * 2))
            }
            var s = 0
            for (f in 0 until frameCount) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += pcm.get(s) / 32768f
                    s++
                }
                buffer[buffered + f] = acc / channels
            }
            buffered += frameCount
            drain()
        }

        /** Same as [feed] for decoders that output float PCM. */
        fun feedFloat(
            pcm: java.nio.FloatBuffer,
            channels: Int,
            sampleRateHz: Int,
        ) {
            if (sampleRateHz != sampleRate) {
                sampleRate = sampleRateHz
                hopSamples = (sampleRate / 60).coerceAtLeast(1)
            }
            val frameCount = pcm.remaining() / channels
            if (buffered + frameCount > buffer.size) {
                buffer = buffer.copyOf((buffered + frameCount).coerceAtLeast(buffer.size * 2))
            }
            var s = 0
            for (f in 0 until frameCount) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += pcm.get(s)
                    s++
                }
                buffer[buffered + f] = acc / channels
            }
            buffered += frameCount
            drain()
        }

        private fun drain() {
            var start = 0
            while (start + processor.fftSize <= buffered) {
                System.arraycopy(buffer, start, window, 0, processor.fftSize)
                processor.process(window, sampleRate, raw)
                processor.accumulateChroma(keyDetector, sampleRate)
                smoother.apply(raw, smoothed)
                val step = processor.fftSize / waveform.size
                for (i in waveform.indices) waveform[i] = window[i * step]
                val timeMs = absSample * 1000L / sampleRate
                frames += TimelineFrame(timeMs, extractor.extract(smoothed, waveform, sampleRate))
                absSample += hopSamples
                start += hopSamples
            }
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, buffered - start)
                buffered -= start
            }
        }

        fun finish(): FeatureTimeline =
            FeatureTimeline(
                frames,
                hopMs = 1000L / 60,
                key = keyDetector.finish(),
                hopRateHz = HOP_RATE_HZ,
            )

        private companion object {
            /** Offline hop rate. Note hopMs above truncates it to 16 ms, which
             *  is why the timeline carries the rate separately. */
            const val HOP_RATE_HZ = 60f
        }
    }
}
