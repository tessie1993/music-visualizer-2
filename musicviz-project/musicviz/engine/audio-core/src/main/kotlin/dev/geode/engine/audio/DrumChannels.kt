package dev.geode.engine.audio

/**
 * Three band-limited onset channels, named after what usually dominates their
 * range.
 *
 * They are **not** a drum classifier and must not be described as one: a bass
 * synth stab fires [kick], a snare's rattle and a piano chord both fire
 * [snare], and any bright transient fires [hat]. What they give a scene is
 * "something happened, and it happened down here / in the middle / up top",
 * which is enough to move different visual elements on different instruments
 * without pretending to know which instrument it was.
 *
 * Runs the same [SuperFlux] and [OnsetPeakPicker] the full-band detector runs,
 * over a slice of the spectrum. Allocates nothing per [step].
 */
class DrumChannels(
    private val bandCount: Int,
    hopRateHz: Float,
    sampleRateHz: Int,
    minHz: Float = LogBands.DEFAULT_MIN_HZ,
    maxHz: Float = LogBands.DEFAULT_MAX_HZ,
) {
    private val pickers = Array(CHANNELS) { OnsetPeakPicker(hopRateHz, refractorySeconds = 0.05f) }
    private val fluxes = Array(CHANNELS) { SuperFlux(bandCount) }
    private val slices = Array(CHANNELS) { FloatArray(bandCount) }
    private val from = IntArray(CHANNELS)
    private val to = IntArray(CHANNELS)

    /** Low-band onset impulse this step, 0..1; 0 when nothing fired. */
    var kick: Float = 0f
        private set

    /** Mid-band onset impulse; see [kick]. */
    var snare: Float = 0f
        private set

    /** High-band onset impulse; see [kick]. */
    var hat: Float = 0f
        private set

    /** Onset sensitivity, shared by all three channels. */
    var sensitivity: Float
        get() = pickers[0].sensitivity
        set(value) {
            for (p in pickers) p.sensitivity = value
        }

    init {
        for (c in 0 until CHANNELS) {
            from[c] = LogBands.bandForHz(EDGES[c * 2], bandCount, sampleRateHz, minHz, maxHz)
            to[c] = LogBands.bandForHz(EDGES[c * 2 + 1], bandCount, sampleRateHz, minHz, maxHz)
        }
    }

    /** Feeds one frame of per-band values; read [kick], [snare] and [hat] after. */
    fun step(bands: FloatArray) {
        require(bands.size == bandCount) { "expected $bandCount bands, got ${bands.size}" }
        for (c in 0 until CHANNELS) {
            val slice = slices[c]
            // Zeroed outside the channel's range so the shared-width flux sees
            // only this channel's bands.
            for (b in 0 until bandCount) slice[b] = if (b >= from[c] && b <= to[c]) bands[b] else 0f
            val fired = pickers[c].accept(fluxes[c].next(slice))
            val impulse = if (fired) pickers[c].strength else 0f
            when (c) {
                0 -> kick = impulse
                1 -> snare = impulse
                else -> hat = impulse
            }
        }
    }

    /** Forgets every channel's history; call on a track change or a seek. */
    fun reset() {
        for (p in pickers) p.reset()
        for (f in fluxes) f.reset()
        kick = 0f
        snare = 0f
        hat = 0f
    }

    private companion object {
        const val CHANNELS = 3

        /** Low, mid and high band edges in Hz, as from/to pairs. */
        val EDGES = floatArrayOf(30f, 120f, 120f, 900f, 4_000f, 16_000f)
    }
}
