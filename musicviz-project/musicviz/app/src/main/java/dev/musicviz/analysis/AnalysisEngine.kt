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
            extractor.beatThresholdSigma = value.coerceIn(FeatureExtractor.SIGMA_MIN, FeatureExtractor.SIGMA_MAX)
        }

    /** Minimum gap between beat flags, in ms; the rate cap for slow tracks. */
    var beatMinIntervalMs: Float
        get() = extractor.beatMinIntervalMs
        set(value) {
            extractor.beatMinIntervalMs = value.coerceIn(FeatureExtractor.INTERVAL_MS_MIN, FeatureExtractor.INTERVAL_MS_MAX)
        }
    private val _features = MutableStateFlow(AudioFeatures.empty(processor.bandCount))
    val features: StateFlow<AudioFeatures> = _features

    /**
     * Set by [reset] and consumed by the worker loop. The extractor and the
     * smoother are single-threaded state owned by that loop, so clearing them
     * from the caller's thread (a player callback, i.e. the main thread) would
     * race a live [FeatureExtractor.extract] and hand the visuals a frame
     * built from half-cleared history. The flag costs one volatile read per
     * ~16 ms tick and moves the work to the thread that owns it.
     */
    @Volatile
    private var resetPending = false

    /**
     * Drops the per-track analysis state (beat grid, energy envelope, rolling
     * flux window, band smoothing) so the next frames are judged on their own
     * audio. Sensitivity settings survive.
     *
     * The extractor lives for the whole session while the audio it sees does
     * not: call this on every track change and every seek. The state it holds
     * is a model of one continuous piece of music, and applying a previous
     * track's tempo grid to a new one suppresses that track's real beats as
     * off-grid. It also restores export parity - the offline replay always
     * starts from a cold tracker, so the live path has to as well.
     *
     * Safe to call from any thread, and safe to call while stopped: the
     * published features clear immediately, and the extractor clears on the
     * worker's next tick (or its next [start]).
     */
    fun reset() {
        resetPending = true
        _features.value = AudioFeatures.empty(processor.bandCount)
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.Default) {
                val windowBuf = FloatArray(processor.fftSize)
                val raw = FloatArray(processor.bandCount)
                val smoothed = FloatArray(processor.bandCount)
                val waveform = FloatArray(128)
                // The side window, snapshotted alongside the mid one. Full
                // length: the stereo measurements are taken over this, not
                // over the decimated `waveform` below.
                val sideBuf = FloatArray(processor.fftSize)
                while (true) {
                    if (resetPending) {
                        resetPending = false
                        extractor.reset()
                        smoother.reset()
                    }
                    if (ring.snapshotLatest(windowBuf)) {
                        processor.process(windowBuf, sampleRateHz, raw)
                        smoother.apply(raw, smoothed)
                        val step = processor.fftSize / waveform.size
                        for (i in waveform.indices) waveform[i] = windowBuf[i * step]
                        val stereo =
                            if (ring.snapshotLatestSide(sideBuf)) {
                                StereoField.of(windowBuf, sideBuf)
                            } else {
                                StereoField.MONO
                            }
                        _features.value = extractor.extract(smoothed, waveform, sampleRateHz, stereo)
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
