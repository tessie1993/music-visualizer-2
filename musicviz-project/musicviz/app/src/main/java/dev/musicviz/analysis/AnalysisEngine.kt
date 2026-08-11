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

    // The worker ticks on a 16 ms deadline schedule; the extractor's beat/BPM
    // math must use the real hop rate or live BPM reads ~4% high.
    private val extractor = FeatureExtractor(processor.bandCount, hopRateHz = HOP_RATE_HZ)

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
                // Same hop rate as the extractor: the chroma's decay math ran
                // ~4% fast at an assumed 60 Hz while the loop ticks at 62.5.
                val chroma = Chromagram(hopRateHz = HOP_RATE_HZ)
                var deadlineNs = System.nanoTime()
                while (true) {
                    if (resetPending) {
                        resetPending = false
                        extractor.reset()
                        smoother.reset()
                        chroma.reset()
                    }
                    if (ring.snapshotLatest(windowBuf)) {
                        processor.process(windowBuf, sampleRateHz, raw)
                        processor.updateChroma(chroma, sampleRateHz)
                        smoother.apply(raw, smoothed)
                        // Box-average each span rather than point-sampling it:
                        // one sample in ~16 aliases hi-hats into shimmer on
                        // the scope scene; the mean over the span does not.
                        val step = processor.fftSize / waveform.size
                        for (i in waveform.indices) {
                            var acc = 0f
                            val base = i * step
                            for (j in 0 until step) acc += windowBuf[base + j]
                            waveform[i] = acc / step
                        }
                        val stereo =
                            if (ring.snapshotLatestSide(sideBuf)) {
                                StereoField.of(windowBuf, sideBuf)
                            } else {
                                StereoField.MONO
                            }
                        _features.value = extractor.extract(smoothed, waveform, sampleRateHz, stereo, chroma)
                    }
                    // Drift-corrected: a plain delay(16) sleeps 16 ms AFTER
                    // each tick's work, so the real hop rate sagged under
                    // load and the extractor's fixed-rate beat/BPM math
                    // skewed with it. Advancing a deadline keeps the average
                    // rate at HOP_RATE_HZ regardless of per-tick cost.
                    deadlineNs += TICK_NS
                    val now = System.nanoTime()
                    if (deadlineNs < now) deadlineNs = now // stalled: no catch-up burst
                    delay((deadlineNs - now) / 1_000_000)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** One tick per 16 ms deadline = 62.5 Hz, shared by the extractor and the chroma. */
        private const val TICK_NS = 16_000_000L
        internal const val HOP_RATE_HZ = 1000f / 16f
    }
}
