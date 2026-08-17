package dev.geode.engine.audio

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shape measurements over one magnitude spectrum, from §5.3's descriptor list.
 *
 * Stateless and allocation-free: every one is a single pass with `double`
 * accumulators. The inputs are the [Spectrum] node's magnitudes, DC through
 * Nyquist, so the axis is complete rather than truncated — which matters here
 * more than anywhere, because [flatness] and [rolloffHz] integrate across the
 * whole spectrum and a missing bin at either end simply changes the answer.
 *
 * ## Provenance
 *
 * Written from the published definitions and validated against librosa
 * (ORACLE tier) before being committed: centroid and bandwidth agree to
 * float precision, rolloff and flatness exactly. Meyda (REIMPLEMENT tier) was
 * read for cross-checking the formulas and no code, naming or layout is taken
 * from it — its centroid and spread are in bin-index units where these are in
 * Hz, and its flatness is magnitude-based where librosa's, and this, is
 * power-based with a floor.
 */
object SpectralDescriptors {
    /**
     * Below this total magnitude a frame has no spectral shape to describe,
     * and every descriptor returns 0 rather than a ratio of two zeros.
     */
    const val SILENCE_TOTAL = 1e-12

    /** librosa's floor on the power spectrum, so a zero bin cannot make [flatness] collapse. */
    private const val POWER_FLOOR = 1e-10

    /** Energy-weighted mean frequency: where the spectrum sits. */
    fun centroidHz(
        magnitudes: FloatArray,
        binHz: Double,
    ): Double {
        var weighted = 0.0
        var total = 0.0
        for (k in magnitudes.indices) {
            val m = magnitudes[k].toDouble()
            weighted += k * binHz * m
            total += m
        }
        return if (total > SILENCE_TOTAL) weighted / total else 0.0
    }

    /**
     * Energy-weighted spread about [centroidHz]: how wide the spectrum is.
     *
     * Takes the centroid rather than recomputing it, because a caller almost
     * always wants both and computing it twice is two passes for one answer.
     */
    fun bandwidthHz(
        magnitudes: FloatArray,
        binHz: Double,
        centroidHz: Double,
    ): Double {
        var deviation = 0.0
        var total = 0.0
        for (k in magnitudes.indices) {
            val m = magnitudes[k].toDouble()
            val offset = k * binHz - centroidHz
            deviation += m * offset * offset
            total += m
        }
        return if (total > SILENCE_TOTAL) sqrt(deviation / total) else 0.0
    }

    /**
     * The frequency below which [fraction] of the magnitude lies — a brightness
     * measure that survives a loud low end better than the centroid does.
     *
     * Returns the first bin at or above the threshold, so the answer is a bin
     * centre and lands exactly on the oracle's.
     */
    fun rolloffHz(
        magnitudes: FloatArray,
        binHz: Double,
        fraction: Double = DEFAULT_ROLLOFF,
    ): Double {
        require(fraction > 0.0 && fraction <= 1.0) { "fraction must be in (0, 1], was $fraction" }
        var total = 0.0
        for (m in magnitudes) total += m.toDouble()
        if (total <= SILENCE_TOTAL) return 0.0

        val threshold = fraction * total
        var running = 0.0
        for (k in magnitudes.indices) {
            running += magnitudes[k].toDouble()
            if (running >= threshold) return k * binHz
        }
        return (magnitudes.size - 1) * binHz
    }

    /**
     * Geometric over arithmetic mean of the power spectrum: 1 for noise, near
     * 0 for a tone. The measure that separates a cymbal from a bass note.
     *
     * Power, not magnitude, and floored — a single exactly-zero bin would
     * otherwise send the geometric mean to zero and report every frame
     * containing silence anywhere as perfectly tonal.
     */
    fun flatness(magnitudes: FloatArray): Double {
        if (magnitudes.isEmpty()) return 0.0
        var logSum = 0.0
        var sum = 0.0
        for (m in magnitudes) {
            val power = max(m.toDouble() * m, POWER_FLOOR)
            logSum += ln(power)
            sum += power
        }
        val n = magnitudes.size
        return kotlin.math.exp(logSum / n) / (sum / n)
    }

    /** librosa's `spectral_rolloff` default, and the one the corpus is generated with. */
    const val DEFAULT_ROLLOFF = 0.85
}
