package dev.musicviz.engine.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The onset strength signal: spectral flux smoothed into something a tempo
 * estimator can autocorrelate, with a delay that is an exact whole number of
 * frames.
 *
 * Raw [SpectralFlux] is too spiky to autocorrelate usefully — a hi-hat and a
 * kick land as narrow impulses whose overlap at the right lag depends on
 * single-frame alignment, so the correlation peak that should mark the tempo
 * is buried in noise. The standard fix, and the one the compact C tracker
 * (BTT, ORACLE tier) uses, is a low-pass over the flux stream.
 *
 * ## Why an odd number of taps
 *
 * BTT runs 16 taps, so its group delay is **7.5 frames**: onsets come out
 * half a frame away from any whole-frame correction, which is why it carries
 * an empirically-measured `analysis_latency_onset_adjustment` of 857 audio
 * samples that its own header admits has no closed form.
 *
 * This sizes the kernel to `2 delayFrames + 1`, always odd, so the group delay
 * is exactly [delayFrames] and an onset's sample index is
 * `centreSample - delayFrames * hop` with nothing left over. §5.7 asks feature
 * timestamps to agree within one analysis hop; a half-frame delay spends that
 * whole budget before any other stage has had a turn.
 *
 * ## Why the length is in seconds
 *
 * [delaySeconds], not a tap count, because a tap count means different
 * smoothing at every hop rate — the mistake `BandSmoother`'s per-hop attack
 * and decay coefficients already make, where the same slider is a 25 ms
 * response at one FFT size and 12 ms at another. Sized from the hop rate, the
 * kernel covers the same span of music on every branch: at BTT's own 344.5 Hz
 * it comes out at 17 taps, matching its 16 closely enough to compare against.
 *
 * The coefficients are a Hamming-windowed sinc **normalised to unit gain at
 * DC**, which BTT's are not — its sum to 0.451, so its onset strength is
 * scaled by an arbitrary factor its raw threshold minimum then has to absorb.
 * Normalised, this signal stays in the flux's own units and a threshold can be
 * stated in them.
 *
 * A windowed sinc this short has no negative taps, so the output of a
 * non-negative flux stream is itself non-negative: the onset strength is a
 * weighted moving average of the flux and cannot ring below zero.
 */
class OnsetStrength(
    hopRateHz: Float,
    delaySeconds: Float = DELAY_SECONDS,
    private val cutoffHz: Float = CUTOFF_HZ,
) {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(delaySeconds > 0f) { "delaySeconds must be positive, was $delaySeconds" }
        require(cutoffHz > 0f && cutoffHz < hopRateHz / 2f) {
            "cutoffHz must be inside 0..${hopRateHz / 2f}, was $cutoffHz"
        }
    }

    /**
     * Frames between a flux impulse and the peak it produces here — the group
     * delay, exact rather than rounded, because the kernel is symmetric and
     * odd. Subtract `delayFrames * hopFrames` from a frame's centre sample to
     * place the onset in the audio.
     */
    val delayFrames: Int = (delaySeconds * hopRateHz).roundToInt().coerceAtLeast(1)

    /** How long [delayFrames] is at this hop rate, after rounding to whole frames. */
    val delaySecondsActual: Float = delayFrames / hopRateHz

    /** Kernel length. Always odd, which is what makes [delayFrames] a whole number. */
    val taps: Int = 2 * delayFrames + 1

    private val kernel = FloatArray(taps)
    private val history = FloatArray(taps)
    private var next = 0

    init {
        val ft = cutoffHz / hopRateHz
        val centre = delayFrames.toDouble()
        val exact = DoubleArray(taps)
        var sum = 0.0
        for (i in 0 until taps) {
            val offset = i - centre
            val sinc = if (offset == 0.0) 2.0 * ft else sin(2.0 * PI * ft * offset) / (PI * offset)
            val window = 0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1))
            exact[i] = sinc * window
            sum += exact[i]
        }
        // Unit gain at DC, normalised in double before it is rounded once.
        // The sum is positive for any cutoff inside Nyquist — every tap is a
        // non-negative sinc lobe times a non-negative window — so this cannot
        // divide by zero or flip the kernel's sign.
        for (i in 0 until taps) kernel[i] = (exact[i] / sum).toFloat()
    }

    /** Coefficient [index] of the kernel, for tests and diagnostics. */
    fun coefficientAt(index: Int): Float = kernel[index]

    /**
     * Feeds one frame's flux and returns the onset strength for the frame
     * [delayFrames] before it.
     */
    fun next(flux: Float): Float {
        history[next] = flux
        next = if (next + 1 == taps) 0 else next + 1
        var acc = 0f
        // history[next] is the oldest sample, which pairs with kernel[0] — the
        // ring's write cursor lands on the oldest entry once it has wrapped.
        var read = next
        for (k in 0 until taps) {
            acc += history[read] * kernel[k]
            read = if (read + 1 == taps) 0 else read + 1
        }
        return acc
    }

    /** Forgets the stream, as at a track change or a seek. */
    fun reset() {
        history.fill(0f)
        next = 0
    }

    companion object {
        /**
         * 23 ms, which is BTT's 7.5 frames at its own 344.5 Hz onset rate
         * rounded to the nearest whole frame there. Short enough that a beat
         * still lands inside its own 16th note at any tempo the tracker
         * accepts, long enough to fill the gap between a flam's two hits.
         */
        const val DELAY_SECONDS = 0.023f

        /**
         * Design cutoff, not the −3 dB point: a Hamming-windowed sinc rolls
         * off gently, so at BTT's rate the half-power frequency lands near
         * 14 Hz. 10 Hz keeps 300 BPM's 5 Hz beat rate and its first two
         * harmonics essentially untouched.
         */
        const val CUTOFF_HZ = 10f
    }
}
