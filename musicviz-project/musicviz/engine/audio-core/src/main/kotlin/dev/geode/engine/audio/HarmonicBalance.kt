package dev.geode.engine.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Harmonic against percussive, 0..1 — how much of what is playing is
 * sustained pitch versus struck transient.
 *
 * The HPSS intuition (Fitzgerald's median-filter formulation, DAFx 2010)
 * made causal: harmonic content is HORIZONTAL in a spectrogram — energy
 * that persists in time — and percussive content is VERTICAL, energy that
 * just appeared across the spectrum. Offline HPSS median-filters both axes;
 * a causal engine cannot see forward in time, so this keeps a per-bin
 * exponential history and splits each frame into the part that was already
 * there, `min(now, history)`, and the part that was not,
 * `max(now - history, 0)`. The balance of the two, smoothed, is the output.
 *
 * A held tone reads near 1, a click train near 0, broadband noise between —
 * the RANKING is the contract, validated on constructed material; the
 * absolute value is an approximation and consumers should treat it as a
 * mix, not a measurement.
 *
 * Deterministic and ordered; allocates nothing per frame.
 */
class HarmonicBalance(
    private val binCount: Int,
    hopRateHz: Float,
    historySeconds: Float = 0.2f,
    smoothingSeconds: Float = 0.25f,
) {
    init {
        require(binCount > 0) { "binCount must be positive, was $binCount" }
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
    }

    private val historyPole = 1f - exp(-1f / (historySeconds * hopRateHz))
    private val smoothingPole = 1f - exp(-1f / (smoothingSeconds * hopRateHz))
    private val history = FloatArray(binCount)

    /** The smoothed balance, 0 percussive to 1 harmonic; 0.5 undecided. */
    var balance: Float = UNDECIDED
        private set

    /** Feeds one magnitude spectrum in [Spectrum]'s layout. */
    fun step(magnitudes: FloatArray) {
        require(magnitudes.size == binCount) { "expected $binCount bins, got ${magnitudes.size}" }
        var harmonic = 0.0
        var percussive = 0.0
        for (k in 0 until binCount) {
            val m = magnitudes[k]
            val h = history[k]
            harmonic += min(m, h).toDouble()
            percussive += max(m - h, 0f).toDouble()
            history[k] = h + (m - h) * historyPole
        }
        val total = harmonic + percussive
        // A silent frame has no vote either way: hold, exactly as the other
        // continuous channels hold, rather than drift toward "undecided".
        if (total <= SILENCE) return
        val instantaneous = (harmonic / total).toFloat()
        balance += (instantaneous - balance) * smoothingPole
    }

    /** Forgets the spectral history; call on a track change or a seek. */
    fun reset() {
        history.fill(0f)
        balance = UNDECIDED
    }

    companion object {
        /** The reading before any evidence: neither harmonic nor percussive. */
        const val UNDECIDED = 0.5f

        /** Total magnitude below which a frame carries no timbre evidence. */
        private const val SILENCE = 1e-7
    }
}
