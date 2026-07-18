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

    private val extractor = FeatureExtractor(processor.bandCount)
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
