package dev.geode.engine.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Whether the tempo estimate has STAYED PUT, 0..1.
 *
 * [TempoTracker.confidence] is per-frame clarity — how far the winning
 * resonator stands above the field right now. A bank flapping between
 * octaves can be clear on every single frame and still be useless as a
 * grid, and a scene committing to bar-scale choreography needs to know the
 * difference. This tracks a leaky mean and mean absolute deviation of the
 * estimate in log2 space (the octave axis a listener hears), and reports
 * how small the deviation is against [SCALE_OCTAVES].
 *
 * Stability is earned, never assumed: the deviation is seeded at full
 * scale when a tempo first appears, so the value climbs from 0 as the
 * estimate proves itself, and silence decays it rather than freezing it —
 * the next track does not inherit the last one's certainty.
 *
 * Deterministic and ordered: [step] must see every frame, in order.
 * Allocates nothing.
 */
class TempoStability(
    hopRateHz: Float,
    /** Time constant of the tempo mean, in seconds. */
    meanSeconds: Float = 1f,
    /** Time constant of the deviation measure, in seconds. */
    deviationSeconds: Float = 2f,
    /** How fast silence forgets earned stability, in seconds. */
    silenceSeconds: Float = 1f,
) {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
    }

    private val meanPole = 1f - exp(-1f / (meanSeconds * hopRateHz))
    private val devPole = 1f - exp(-1f / (deviationSeconds * hopRateHz))
    private val silencePole = 1f - exp(-1f / (silenceSeconds * hopRateHz))

    private var mean = 0f
    private var dev = SCALE_OCTAVES
    private var seeded = false

    /** How steady the tempo has been, 0..1. */
    var value: Float = 0f
        private set

    /** Feeds one frame's [TempoTracker.bpm]; 0 or less means no tempo. */
    fun step(bpm: Float) {
        if (bpm <= 0f) {
            // No tempo to be stable ABOUT. Walk the deviation back to full
            // scale so certainty drains instead of freezing across the gap.
            dev += (SCALE_OCTAVES - dev) * silencePole
            value = if (seeded) (1f - dev / SCALE_OCTAVES).coerceIn(0f, 1f) else 0f
            return
        }
        val x = ln(bpm) / LN_2
        if (!seeded) {
            seeded = true
            mean = x
            dev = SCALE_OCTAVES
        }
        mean += (x - mean) * meanPole
        dev += (abs(x - mean) - dev) * devPole
        value = (1f - dev / SCALE_OCTAVES).coerceIn(0f, 1f)
    }

    /** Forgets everything; call on a track change or a seek. */
    fun reset() {
        mean = 0f
        dev = SCALE_OCTAVES
        seeded = false
        value = 0f
    }

    companion object {
        /**
         * The deviation, in octaves, at which stability reads zero. A bank
         * flapping between octaves sits at 0.5 and reads 0; the one-bin
         * quantization wobble of a real hold (~0.02–0.05) reads above 0.8.
         */
        const val SCALE_OCTAVES: Float = 0.25f

        private val LN_2 = ln(2.0).toFloat()
    }
}
