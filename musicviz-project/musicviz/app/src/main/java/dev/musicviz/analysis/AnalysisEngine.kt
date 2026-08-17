package dev.musicviz.analysis

import dev.musicviz.engine.audio.MidSideWindow
import dev.musicviz.engine.audio.ReactiveAnalyzer
import dev.musicviz.engine.audio.SampleRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pulls the newest PCM window from [ring] at ~62.5 Hz, runs the reactive
 * analysis graph on Dispatchers.Default (never the main or audio thread) and
 * publishes [AudioFeatures].
 *
 * Reads the V2 [SampleRing] through [MidSideWindow], which derives the mono
 * downmix and the side channel on read rather than at capture, so mid and side
 * come from a single snapshot: taken separately, the write head could advance
 * between them and the correlation was computed across two slightly different
 * windows.
 *
 * The V2 ring restarts its numbering at a seek or a format change, so for one
 * window after each (~43 ms at 48 kHz) there is nothing to read and this
 * publishes nothing — the visuals hold their last frame, which is better than
 * serving a window that still contains pre-seek audio.
 *
 * ## What changed under it
 *
 * The DSP is now [ReactiveAnalyzer] rather than `FftProcessor` +
 * `BandSmoother` + `FeatureExtractor` + `PulseTracker`. Everything this class
 * does is the same — same cadence, same ring, same published type — but the
 * values in that type are on a scale a scene can use. See `ReactiveAnalyzer`
 * for what was wrong with the old ones.
 */
class AnalysisEngine(
    private val ring: SampleRing,
    val bandCount: Int = DEFAULT_BAND_COUNT,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
) {
    private val analyzer =
        ReactiveAnalyzer(
            bandCount = bandCount,
            fftSize = fftSize,
            hopRateHz = HOP_RATE_HZ,
        )

    @Volatile
    var sampleRateHz: Int = 44100
        set(value) {
            field = value
            analyzer.sampleRateHz = value
        }

    /**
     * Band-smoothing attack, as the per-tick mix fraction presets store. See
     * [BeatTuning.envelopeSeconds] for why the stored unit did not change.
     */
    var attack: Float = DEFAULT_ATTACK
        set(value) {
            field = value
            analyzer.attackSeconds = BeatTuning.envelopeSeconds(value)
        }

    /** Band-smoothing decay, in the same stored unit as [attack]. */
    var decay: Float = DEFAULT_DECAY
        set(value) {
            field = value
            analyzer.releaseSeconds = BeatTuning.envelopeSeconds(value)
        }

    /** Onset sensitivity in robust deviations; higher = fewer, surer beats. */
    var beatSensitivity: Float = BeatTuning.SENSITIVITY_DEFAULT
        set(value) {
            field = BeatTuning.clampSensitivity(value)
            analyzer.sensitivity = field
        }

    /** Minimum gap between onsets, in ms; the rate cap for slow tracks. */
    var beatMinIntervalMs: Float = BeatTuning.INTERVAL_MS_DEFAULT
        set(value) {
            field = BeatTuning.clampIntervalMs(value)
            analyzer.refractoryMs = field
        }

    private val _features = MutableStateFlow(AudioFeatures.empty(bandCount))
    val features: StateFlow<AudioFeatures> = _features

    /**
     * Set by [reset] and consumed by the worker loop. The analyzer is
     * single-threaded state owned by that loop, so clearing it from the
     * caller's thread (a player callback, i.e. the main thread) would race a
     * live analysis and hand the visuals a frame built from half-cleared
     * history. The flag costs one volatile read per tick and moves the work to
     * the thread that owns it.
     */
    @Volatile
    private var resetPending = false

    init {
        // Push the constructor defaults through the setters so the analyzer
        // starts configured rather than on its own defaults.
        attack = DEFAULT_ATTACK
        decay = DEFAULT_DECAY
    }

    /**
     * Drops the per-track analysis state (learned band ranges, peak profiles,
     * flux history, tempo bank, beat grid) so the next frames are judged on
     * their own audio. Sensitivity settings survive.
     *
     * Call on every track change and every seek: everything the analyzer holds
     * models one continuous piece of music, and a previous track's tempo grid
     * suppresses the new track's real beats as off-grid. It also restores
     * export parity, since the offline replay always starts from a cold graph.
     *
     * Safe to call from any thread and while stopped.
     */
    fun reset() {
        resetPending = true
        _features.value = AudioFeatures.empty(bandCount)
    }

    /**
     * One hop's worth of work, and the buffers it reuses.
     *
     * Separate from the worker loop so it can be driven a tick at a time. The
     * loop runs behind a wall-clock deadline no test can step; left inline,
     * everything this does would be unreachable, and fault injection proved
     * that mattered — hard-wiring the stereo field to `MONO`, or building the
     * waveform from the side channel, used to break nothing.
     *
     * Confined to one thread, like the analyzer it drives.
     */
    internal inner class Pass {
        // Owns both windows and fills them from one snapshot. Full length: the
        // stereo measurements are taken over the side window, not over the
        // decimated `waveform` below.
        private val window = MidSideWindow(ring, fftSize)
        private val waveform = FloatArray(WAVEFORM_POINTS)
        private val chroma = Chromagram(hopRateHz = HOP_RATE_HZ)
        private val chromaMagnitudes = FloatArray(fftSize / 2)

        /** Drops the per-track state this pass owns. */
        fun reset() {
            analyzer.reset()
            chroma.reset()
        }

        /** Publishes one frame, or returns false when the ring has no window yet. */
        fun tick(): Boolean {
            if (!window.refresh()) return false
            analyzer.analyze(window.mid, DT_SECONDS)

            // Box-average each span rather than point-sampling it: one sample
            // in ~16 aliases hi-hats into shimmer on the scope scene; the mean
            // over the span does not.
            val step = fftSize / waveform.size
            for (i in waveform.indices) {
                var acc = 0f
                val base = i * step
                for (j in 0 until step) acc += window.mid[base + j]
                waveform[i] = acc / step
            }

            chroma.step(analyzer.spectrumInto(chromaMagnitudes), sampleRateHz, fftSize)
            val stereo = StereoField.of(window.mid, window.side)

            _features.value =
                AudioFeatures(
                    bands = analyzer.bands.copyOf(),
                    waveform = waveform.copyOf(),
                    rms = analyzer.rms,
                    bass = analyzer.bass,
                    mid = analyzer.mid,
                    treble = analyzer.treble,
                    onset = analyzer.onset,
                    beat = analyzer.beat,
                    bpm = analyzer.bpm,
                    centroid = analyzer.centroid,
                    flux = analyzer.fluxValue,
                    beatStrength = analyzer.beatStrength,
                    transient = analyzer.transient,
                    beatPhase = analyzer.beatPhase,
                    pulseConfidence = analyzer.pulseConfidence,
                    macroEnergy = analyzer.macroEnergy,
                    kick = analyzer.kick,
                    snare = analyzer.snare,
                    hat = analyzer.hat,
                    chroma = chroma.bins.copyOf(),
                    chromaConfidence = chroma.confidence,
                    stereoWidth = stereo.width,
                    stereoCorrelation = stereo.correlation,
                )
            return true
        }
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job =
            scope.launch(Dispatchers.Default) {
                val pass = Pass()
                var deadlineNs = System.nanoTime()
                while (true) {
                    if (resetPending) {
                        resetPending = false
                        pass.reset()
                    }
                    pass.tick()
                    // Drift-corrected: a plain delay(16) sleeps 16 ms AFTER
                    // each tick's work, so the real hop rate sags under load
                    // and the rhythm nodes' frame-based math skews with it.
                    // Advancing a deadline keeps the average at HOP_RATE_HZ.
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
        /** One tick per 16 ms deadline = 62.5 Hz, shared by the analyzer and the chroma. */
        private const val TICK_NS = 16_000_000L

        /** Points in the waveform a scene is handed, decimated from the analysis window. */
        internal const val WAVEFORM_POINTS = 128

        internal const val HOP_RATE_HZ = 1000f / 16f
        internal const val DT_SECONDS = 16f / 1000f

        /** Bands a scene is handed; unchanged, so no consumer's indexing moves. */
        const val DEFAULT_BAND_COUNT = 64

        /** ~43 ms at 48 kHz: enough resolution at the bottom of the spectrum. */
        const val DEFAULT_FFT_SIZE = 2048

        /** Per-tick mix fractions, the unit presets store. See [BeatTuning]. */
        const val DEFAULT_ATTACK = 0.6f
        const val DEFAULT_DECAY = 0.12f
    }
}
