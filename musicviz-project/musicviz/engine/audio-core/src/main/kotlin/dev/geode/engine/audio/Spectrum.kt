package dev.geode.engine.audio

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.sqrt

/**
 * Magnitude spectrum of one windowed frame, computed in place.
 *
 * Holds its own scratch and output, so a caller in the analysis loop allocates
 * nothing per frame. Not thread-safe: one instance per branch, per worker.
 *
 * ## Bins, including the ones the legacy path drops
 *
 * [magnitudes] has `fftSize / 2 + 1` entries — DC through Nyquist inclusive,
 * the same layout every STFT reference uses. `:app`'s `FftProcessor` zeroes DC
 * and stops one short of Nyquist, which is fine for its log bands (neither
 * falls in one) but wrong for anything that integrates across the spectrum:
 * flatness and rolloff over a truncated axis are simply different numbers.
 *
 * Magnitudes are unscaled. Dividing by the window length or bin count is a
 * presentation choice, and baking one in here would mean every consumer that
 * wanted another had to undo it first.
 */
class Spectrum(
    val fftSize: Int,
) {
    init {
        require(fftSize >= 2 && fftSize and (fftSize - 1) == 0) {
            "fftSize must be a power of two of at least 2, was $fftSize"
        }
    }

    private val fft = FloatFFT_1D(fftSize.toLong())
    private val work = FloatArray(fftSize)

    /** DC..Nyquist inclusive; valid after [compute]. */
    val magnitudes = FloatArray(fftSize / 2 + 1)

    /** Hz per bin at [sampleRateHz]. */
    fun binHz(sampleRateHz: Int): Double = sampleRateHz.toDouble() / fftSize

    /**
     * Fills [magnitudes] from [windowed], which must already have the window
     * applied and be at least [fftSize] long.
     */
    fun compute(windowed: FloatArray) {
        require(windowed.size >= fftSize) { "need $fftSize samples, got ${windowed.size}" }
        windowed.copyInto(work, 0, 0, fftSize)
        fft.realForward(work)
        // JTransforms packs a real transform as [re0, reNyquist, re1, im1, ...]
        // - the two purely real bins share the first slot pair. Unpacked here
        // rather than skipped, so the spectrum spans the whole axis.
        magnitudes[0] = kotlin.math.abs(work[0])
        magnitudes[fftSize / 2] = kotlin.math.abs(work[1])
        for (k in 1 until fftSize / 2) {
            val re = work[2 * k]
            val im = work[2 * k + 1]
            magnitudes[k] = sqrt(re * re + im * im)
        }
    }

    /** Index of the loudest bin, ignoring DC. */
    fun peakBin(): Int {
        var best = 1
        for (k in 2 until magnitudes.size) if (magnitudes[k] > magnitudes[best]) best = k
        return best
    }
}
