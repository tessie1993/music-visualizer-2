package dev.geode.engine.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Log-spaced bands carrying band **energy**, equalized for the spectral tilt of
 * real music.
 *
 * `docs/quality/bar-visualizer.md` §2.3 asks for three things: log or mel band
 * spacing, each band integrating the bins it covers, and "per-band
 * normalization/tilt [to compensate] the natural 1/f spectral slope … so highs
 * aren't permanently dead". The legacy `FftProcessor` did the first only, and
 * the doc predicted the consequence in the same paragraph: "without spectral
 * tilt correction, treble bars barely move on real music". They did not.
 *
 * ## Energy, not the loudest bin
 *
 * The legacy path took `max` over each band's bins. A broadband sound spreads
 * its power over many bins, so its loudest single bin is a small number however
 * loud the sound is — which is why the old band values were tiny in absolute
 * terms as well as tilted. Here a band is the mean tilt-weighted power density
 * across its bins, so a hi-hat covering two hundred bins reads like a hi-hat.
 *
 * ## The tilt is applied per bin, not per band
 *
 * Weighting a band by its centre frequency is close but not exact, and at the
 * bottom of the spectrum it is not even close: at 2048/48 kHz the bins are
 * 23 Hz apart, so the lowest bands are one bin wide and that bin sits wherever
 * it sits inside the band. Weighting each bin by its own frequency makes the
 * correction exact everywhere — pink noise reads flat to within a rounding
 * error rather than to within the band quantization.
 *
 * Rebuilds its tables when [sampleRateHz] changes and not otherwise. Allocates
 * nothing per frame.
 */
class LogBands(
    val bandCount: Int,
    val fftSize: Int,
    sampleRateHz: Int,
    private val minHz: Float = DEFAULT_MIN_HZ,
    private val maxHz: Float = DEFAULT_MAX_HZ,
    /** dB per octave added to the spectrum; see [PINK_TILT_DB_PER_OCTAVE]. */
    private val tiltDbPerOctave: Float = PINK_TILT_DB_PER_OCTAVE,
) {
    init {
        require(bandCount > 0) { "bandCount must be positive, was $bandCount" }
        require(fftSize >= 2 && fftSize and (fftSize - 1) == 0) {
            "fftSize must be a power of two of at least 2, was $fftSize"
        }
        require(minHz > 0f && maxHz > minHz) { "need 0 < minHz < maxHz, got $minHz..$maxHz" }
    }

    /** Rebuilds the band edges and tilt weights when set to a new rate. */
    var sampleRateHz: Int = sampleRateHz
        set(value) {
            if (value != field) {
                field = value
                rebuild()
            }
        }

    private val binCount = fftSize / 2 + 1

    /** Inclusive bin range per band, and the exact Hz edges they approximate. */
    private val firstBin = IntArray(bandCount)
    private val lastBin = IntArray(bandCount)
    private val lowerEdgeHz = FloatArray(bandCount)
    private val upperEdgeHz = FloatArray(bandCount)

    /** Per-bin tilt weight applied to power; 1.0 everywhere when the tilt is 0. */
    private val tiltWeight = FloatArray(binCount)

    /**
     * Magnitude scale. [Spectrum] leaves magnitudes unscaled, so a full-scale
     * sine peaks near `fftSize / 2` times the window's coherent gain. Dividing
     * by `fftSize / 2` puts the result on a dBFS-like scale where a full-scale
     * tone reads near 0 dB, which is the scale [AdaptiveRange.SILENCE_DB] and
     * every threshold in this package are written against.
     */
    private val magnitudeScale = 2f / fftSize

    init {
        rebuild()
    }

    /** Lower frequency edge of [band], in Hz. */
    fun lowerHz(band: Int): Float = lowerEdgeHz[band]

    /** Upper frequency edge of [band], in Hz. */
    fun upperHz(band: Int): Float = upperEdgeHz[band]

    /**
     * Writes the tilt-corrected level of each band into [out], in dB on the
     * scale described by [magnitudeScale].
     *
     * [magnitudes] must be a full [Spectrum] frame — DC through Nyquist
     * inclusive. Bands with no energy read [AdaptiveRange.SILENCE_DB], which is
     * a floor rather than negative infinity so downstream arithmetic stays
     * finite.
     */
    fun energyDb(
        magnitudes: FloatArray,
        out: FloatArray,
    ) {
        energy(magnitudes, out)
        for (b in 0 until bandCount) {
            val mean = out[b]
            out[b] = if (mean <= 0f) AdaptiveRange.SILENCE_DB else max(10f * log10(mean), AdaptiveRange.SILENCE_DB)
        }
    }

    /**
     * Writes the mean tilt-weighted **power** of each band into [out], on a
     * linear scale where a full-scale tone reads near 1.
     *
     * The linear form is what [AdaptiveWhitening] wants: normalizing a band by
     * its own recent peak is a ratio, and taking it in the log domain would
     * make a rise out of near-silence — inaudible, and mostly noise — look
     * like the largest onset in the track.
     */
    fun energy(
        magnitudes: FloatArray,
        out: FloatArray,
    ) {
        require(magnitudes.size == binCount) { "expected $binCount bins, got ${magnitudes.size}" }
        require(out.size == bandCount) { "expected $bandCount bands, got ${out.size}" }
        for (b in 0 until bandCount) {
            var power = 0.0
            val from = firstBin[b]
            val to = lastBin[b]
            for (k in from..to) {
                val m = magnitudes[k] * magnitudeScale
                power += m.toDouble() * m * tiltWeight[k]
            }
            out[b] = (power / (to - from + 1)).toFloat()
        }
    }

    private fun rebuild() {
        val nyquist = sampleRateHz / 2f
        val top = min(maxHz, nyquist)
        val bottom = min(minHz, top * 0.5f)
        val binHz = sampleRateHz.toFloat() / fftSize

        val logBottom = ln(bottom.toDouble())
        val logTop = ln(top.toDouble())
        var cursor = 1 // never DC: it carries offset, not music
        for (b in 0 until bandCount) {
            val lo = exp(logBottom + (logTop - logBottom) * b / bandCount).toFloat()
            val hi = exp(logBottom + (logTop - logBottom) * (b + 1) / bandCount).toFloat()
            lowerEdgeHz[b] = lo
            upperEdgeHz[b] = hi

            // At least one bin per band, and never a bin twice: at the bottom
            // of the spectrum the bands are narrower than a bin, so without a
            // walking cursor the lowest several bands would all read bin 1 and
            // move together as one fat band.
            val wantFirst = max(cursor, (lo / binHz).toInt())
            val first = min(wantFirst, binCount - 1)
            val last = min(max(first, (hi / binHz).toInt()), binCount - 1)
            firstBin[b] = first
            lastBin[b] = last
            cursor = min(last + 1, binCount - 1)
        }

        // Power weight for a +[tiltDbPerOctave] dB/octave correction: an
        // exponent of 1 multiplies power by f, which exactly flattens the 1/f
        // density of pink noise.
        val exponent = tiltDbPerOctave / PINK_TILT_DB_PER_OCTAVE
        for (k in 0 until binCount) {
            val hz = k * binHz
            tiltWeight[k] =
                if (exponent == 0f || hz <= 0f) 1f else (hz / TILT_REFERENCE_HZ).toDouble().pow(exponent.toDouble()).toFloat()
        }
    }

    companion object {
        /** Bottom of the band span; below this is room rumble and DC offset. */
        const val DEFAULT_MIN_HZ: Float = 30f

        /** Top of the band span, bounded by Nyquist at low sample rates. */
        const val DEFAULT_MAX_HZ: Float = 16_000f

        /**
         * The slope of pink noise, and so of most music: power density falls by
         * `10 * log10(2)` dB per octave. Adding exactly this back makes a pink
         * spectrum read flat, which is the condition under which every band
         * gets the same share of the visual range.
         */
        const val PINK_TILT_DB_PER_OCTAVE: Float = 3.0103f

        /** Pivot of the tilt: bands here are unchanged, below cut, above lifted. */
        const val TILT_REFERENCE_HZ: Float = 1_000f

        /**
         * Index of the band containing [hz], for a bank of [bandCount] bands
         * over the same span an instance would use.
         *
         * Static because the answer depends only on the log spacing and the
         * Nyquist clamp, never on the FFT size — which lets a consumer that has
         * band values but no spectrum (a cache replay) map frequencies onto
         * them without inventing an FFT configuration to do it.
         */
        fun bandForHz(
            hz: Float,
            bandCount: Int,
            sampleRateHz: Int,
            minHz: Float = DEFAULT_MIN_HZ,
            maxHz: Float = DEFAULT_MAX_HZ,
        ): Int {
            val top = min(maxHz, sampleRateHz / 2f)
            val bottom = min(minHz, top * 0.5f)
            if (hz <= bottom) return 0
            if (hz >= top) return bandCount - 1
            val fraction = ln(hz / bottom) / ln(top / bottom)
            return (fraction * bandCount).toInt().coerceIn(0, bandCount - 1)
        }
    }
}
