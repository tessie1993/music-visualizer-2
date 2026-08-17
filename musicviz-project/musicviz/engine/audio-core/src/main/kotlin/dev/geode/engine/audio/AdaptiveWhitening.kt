package dev.geode.engine.audio

import kotlin.math.exp
import kotlin.math.max

/**
 * Per-band adaptive whitening: divides each band by a decaying record of its
 * own recent peak, so every band gets the same dynamic range.
 *
 * ## Why the onset branch needs its own normalizer
 *
 * [AdaptiveRange] answers "where does this moment sit in the music's
 * dynamics", which is the question a *visual* asks. An onset detector asks a
 * different one — "did this band just rise" — and it wants the bands
 * commensurate before they are summed, or the loudest band decides every onset
 * on its own. A kick drum's band is 40 dB above a hi-hat's on most masters, so
 * an unweighted sum of rises is a kick detector wearing an onset detector's
 * name. That is what the legacy `FeatureExtractor` worked around with a
 * hand-tuned `fluxWeights` table; whitening removes the need for the table.
 *
 * ## Provenance
 *
 * Stowell & Plumbley, *Adaptive whitening for improved real-time audio onset
 * detection* (ICMC 2007). Implemented from the published definition:
 *
 * ```text
 * psp[k] = max( |X[k]|, floor, decay * psp_prev[k] )
 * out[k] = |X[k]| / psp[k]
 * ```
 *
 * Two deliberate departures. The memory coefficient is derived from a time
 * constant and the elapsed time rather than being a per-frame constant, for
 * the reason [Envelope] gives. And the input is band power from [LogBands]
 * rather than raw FFT bins: the paper whitens bins because its detector sums
 * bins, and summing a whitened bin spectrum would hand a hundred bins of
 * cymbal wash the same weight as one bin of kick fundamental.
 *
 * Holds one float per band. Allocates nothing per frame.
 */
class AdaptiveWhitening(
    val bandCount: Int,
    /**
     * Time constant of the peak profile's decay. Long enough that a bar
     * without a hit does not re-normalize the band up into its noise, short
     * enough that a loud section does not deafen the band for the next one.
     */
    private val peakDecaySeconds: Float = 2f,
    /**
     * Smallest peak the profile will divide by. Without it, a band carrying
     * only a noise floor normalizes to full scale and the detector fires on
     * dither — the classic failure of per-band normalizers on quiet material.
     */
    private val floor: Float = DEFAULT_FLOOR,
) {
    init {
        require(bandCount > 0) { "bandCount must be positive, was $bandCount" }
        require(peakDecaySeconds > 0f) { "peakDecaySeconds must be positive, was $peakDecaySeconds" }
        require(floor > 0f) { "floor must be positive, was $floor" }
    }

    /** The peak spectral profile — one decaying maximum per band. */
    private val profile = FloatArray(bandCount)

    /**
     * Whitens [input] into [out], both of length [bandCount].
     *
     * [input] is linear band power ([LogBands.energy]), not dB: the whitening
     * is a ratio, and a ratio of logarithms is not the same quantity.
     */
    fun whiten(
        input: FloatArray,
        dtSeconds: Float,
        out: FloatArray,
    ) {
        require(input.size == bandCount) { "expected $bandCount bands, got ${input.size}" }
        require(out.size == bandCount) { "expected $bandCount outputs, got ${out.size}" }
        val decay = if (dtSeconds <= 0f) 1f else exp(-dtSeconds / peakDecaySeconds)
        for (b in 0 until bandCount) {
            val x = input[b]
            val peak = max(max(x, floor), profile[b] * decay)
            profile[b] = peak
            out[b] = (x / peak).coerceIn(0f, 1f)
        }
    }

    /** Forgets every peak; call on a track change or a seek. */
    fun reset() {
        profile.fill(0f)
    }

    companion object {
        /**
         * About -80 dB in power on [LogBands]' scale — below the noise floor of
         * any real master, above the dither of a digital silence.
         */
        const val DEFAULT_FLOOR: Float = 1e-8f
    }
}
