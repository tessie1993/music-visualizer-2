package dev.geode.engine.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Spectral flux with a maximum filter across frequency — the onset evidence
 * `docs/quality/bar-visualizer.md` §2.4 names as the state of the art.
 *
 * ## What the max filter is for
 *
 * Plain flux differences a band against its own previous value, so anything
 * that moves energy sideways between neighbouring bands reads as a rise. That
 * is exactly what vibrato, tremolo and portamento do, several times a second,
 * on strings, vocals and any synth lead with an LFO — and the legacy path
 * counted every one of them. Comparing instead against the *maximum over a
 * small neighbourhood* of the earlier frame means a band only counts as rising
 * if it out-rose where its neighbours already were, which a wobble cannot do
 * and a real attack can.
 *
 * ## Provenance
 *
 * Böck & Widmer, *Maximum Filter Vibrato Suppression for Onset Detection*
 * (DAFx 2013). Written from the published definition:
 *
 * ```text
 * SF(n) = Σ_k max( 0, X(n,k) − maxfilt(X(n−μ, ·))[k] )
 * ```
 *
 * Fed whitened band energy ([AdaptiveWhitening]) rather than raw magnitudes,
 * which is the filterbank form the paper uses and the reason no hand-tuned
 * per-band weight table is needed.
 *
 * Holds `lagFrames` band-sized buffers. Allocates nothing per frame.
 */
class SuperFlux(
    val bandCount: Int,
    /**
     * Width of the frequency maximum filter, in bands; must be odd. 1 disables
     * it and leaves plain rectified flux, which is useful for showing what the
     * filter buys but is not a setting to ship.
     */
    maxFilterBands: Int = 3,
    /**
     * How many frames back to compare against (μ in the paper). At short hops
     * a slow attack spread over several frames reads as a run of small rises
     * against the immediately previous frame and as one clear rise against an
     * older one.
     */
    private val lagFrames: Int = 1,
) {
    init {
        require(bandCount > 0) { "bandCount must be positive, was $bandCount" }
        require(maxFilterBands >= 1 && maxFilterBands % 2 == 1) {
            "maxFilterBands must be odd and at least 1, was $maxFilterBands"
        }
        require(lagFrames >= 1) { "lagFrames must be at least 1, was $lagFrames" }
    }

    private val radius = (maxFilterBands - 1) / 2

    /** Ring of the last [lagFrames] max-filtered frames. */
    private val history = Array(lagFrames) { FloatArray(bandCount) }
    private val filtered = FloatArray(bandCount)
    private var cursor = 0
    private var filled = 0

    /**
     * Returns the onset evidence for [bands] against the frame [lagFrames]
     * earlier, normalized by [bandCount] so configurations of different band
     * counts are comparable.
     *
     * The first [lagFrames] calls after construction or [reset] return 0:
     * there is no earlier frame, and treating the whole spectrum as a rise
     * would put a phantom onset at the start of every track and every seek.
     */
    fun next(bands: FloatArray): Float {
        require(bands.size == bandCount) { "expected $bandCount bands, got ${bands.size}" }

        for (k in 0 until bandCount) {
            var peak = bands[k]
            val from = max(0, k - radius)
            val to = min(bandCount - 1, k + radius)
            for (j in from..to) if (bands[j] > peak) peak = bands[j]
            filtered[k] = peak
        }

        var rise = 0f
        if (filled >= lagFrames) {
            // The slot about to be overwritten is the oldest one held, which
            // is the frame exactly `lagFrames` back.
            val earlier = history[cursor]
            for (k in 0 until bandCount) {
                val delta = bands[k] - earlier[k]
                if (delta > 0f) rise += delta
            }
        }

        filtered.copyInto(history[cursor])
        cursor = (cursor + 1) % lagFrames
        if (filled < lagFrames) filled++
        return rise / bandCount
    }

    /** Forgets the history; call on a track change or a seek. */
    fun reset() {
        for (frame in history) frame.fill(0f)
        cursor = 0
        filled = 0
    }
}
