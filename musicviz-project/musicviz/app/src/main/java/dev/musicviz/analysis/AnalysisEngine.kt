package dev.musicviz.analysis

import dev.musicviz.audio.PcmRingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pulls the newest PCM window from [ring] at ~60 Hz, runs FFT + smoothing +
 * feature extraction on Dispatchers.Default (never the main or audio thread)
 * and publishes [AudioFeatures].
 */
class AnalysisEngine(
    private val ring: PcmRingBuffer,
    private val processor: FftProcessor = FftProcessor(),
    val smoother: BandSmoother = BandSmoother(processor.bandCount),
) {
    @Volatile
    var sampleRateHz: Int = 44100

    // delay(16) yields ~62.5 iterations/sec; the extractor's beat/BPM math
    // must use the real hop rate or live BPM reads ~4% high.
    private val extractor = FeatureExtractor(processor.bandCount, hopRateHz = 1000f / 16f)

    /** Beat sensitivity in sigmas; higher = fewer, surer beats (less flicker). */
    var beatThresholdSigma: Float
        get() = extractor.beatThresholdSigma
        set(value) {
            extractor.beatThresholdSigma = value.coerceIn(1.5f, 4f)
        }
    private val _features = MutableStateFlow(AudioFeatures.empty(processor.bandCount))
    val features: StateFlow<AudioFeatures> = _features

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.Default) {
                val windowBuf = FloatArray(processor.fftSize)
                val raw = FloatArray(processor.bandCount)
                val smoothed = FloatArray(processor.bandCount)
                val waveform = FloatArray(128)
                while (true) {
                    if (ring.snapshotLatest(windowBuf)) {
                        processor.process(windowBuf, sampleRateHz, raw)
                        smoother.apply(raw, smoothed)
                        val step = processor.fftSize / waveform.size
                        for (i in waveform.indices) waveform[i] = windowBuf[i * step]
                        _features.value = extractor.extract(smoothed, waveform, sampleRateHz)
                    }
                    delay(16)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
