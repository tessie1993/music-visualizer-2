package dev.musicviz.engine.audio

/**
 * Half-wave rectified change between successive magnitude spectra — the
 * evidence an onset detector runs on.
 *
 * Rectified because only *rises* are onsets: a note ending is as large a
 * change as a note starting, and counting both makes every offset look like a
 * hit. §5.3 lists this as the input to SuperFlux-style onset evidence.
 *
 * Stateful by nature — it compares against the previous frame — so it holds
 * one buffer and allocates nothing per frame. One instance per branch, per
 * worker, like [Spectrum].
 *
 * ## Provenance
 *
 * Written from the published definition. Meyda (REIMPLEMENT tier) was read for
 * cross-checking and is **not** the reference here: its `spectralFlux` iterates
 * from a negative index, reads an undeclared variable, and carries its own
 * `@ts-nocheck` with a comment saying the file has major issues. The corpus
 * oracle recomputes this definition independently in numpy instead.
 */
class SpectralFlux(
    binCount: Int,
) {
    init {
        require(binCount > 0) { "binCount must be positive, was $binCount" }
    }

    private val previous = FloatArray(binCount)
    private var primed = false

    /**
     * Flux between [magnitudes] and the previous call, normalised by bin count
     * so branches of different window lengths are comparable.
     *
     * The first call after construction or [reset] returns 0: there is no
     * previous frame, and reporting the whole spectrum as a rise would put a
     * phantom onset at the start of every track and every seek.
     */
    fun next(magnitudes: FloatArray): Double {
        require(magnitudes.size == previous.size) {
            "expected ${previous.size} bins, got ${magnitudes.size}"
        }
        var rise = 0.0
        if (primed) {
            for (k in magnitudes.indices) {
                val delta = magnitudes[k] - previous[k]
                if (delta > 0f) rise += delta.toDouble()
            }
        }
        magnitudes.copyInto(previous)
        primed = true
        return rise / previous.size
    }

    /** Forgets the previous frame; call on a seek or a source change. */
    fun reset() {
        primed = false
        previous.fill(0f)
    }
}
