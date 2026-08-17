package dev.musicviz.engine.audio

import java.util.Arrays
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tempo from a bank of comb-filter resonators driven by the onset envelope.
 *
 * Each resonator is `y[n] = a * y[n - T] + (1 - a) * x[n]`. Fed an onset train
 * whose spacing matches `T`, it adds constructively and rings; fed anything
 * else, it does not. The bank therefore reports two things at once — which
 * period the music is on, and how much more clearly that period than any other,
 * which is the [confidence] a visual needs to decide whether to trust a beat
 * grid at all.
 *
 * ## Provenance
 *
 * Scheirer, *Tempo and beat analysis of acoustic musical signals* (JASA 1998).
 * Written from the published formulation, with two things the paper leaves to
 * the implementer:
 *
 * **Gain.** The feedback coefficient is set per resonator so that every one has
 * the same half-life in *seconds* rather than in periods — a fast resonator
 * would otherwise forget in a fraction of the time a slow one does. No further
 * normalization is applied, and none is correct: scaling the input by `1 - a`
 * already makes the resonant steady state equal to the drive amplitude for
 * every period, so a resonator at `T` and one at `2T` fed a `T` train settle at
 * exactly the same mean square. Dividing by the gain a second time tilts the
 * bank toward slow periods, and the whole bank then reports half the true tempo
 * — which is what it did before this was removed.
 *
 * **The octave trap.** A pattern with a hit on every eighth note resonates just
 * as well at the eighth-note period as at the quarter-note one, and no amount
 * of signal analysis distinguishes them — which one is "the tempo" is a fact
 * about human perception, not about the waveform. Scores are weighted by a
 * log-Gaussian preference around [preferredBpm], following the resonance curve
 * Parncutt and Moelants measured.
 *
 * Deterministic and ordered: [step] must see every frame, in order. Allocates
 * nothing per frame.
 */
class TempoTracker(
    private val hopRateHz: Float,
    private val minBpm: Float = 60f,
    private val maxBpm: Float = 200f,
    resonatorCount: Int = 64,
    /** Centre of the tempo preference curve; see the class doc. */
    private val preferredBpm: Float = 120f,
    /** Width of the preference curve, in octaves. */
    private val preferenceOctaves: Float = 1.1f,
    /** How long a resonator remembers, in seconds. */
    halfLifeSeconds: Float = 4f,
    /** Time constant of the energy measurement. */
    energyAverageSeconds: Float = 2f,
) {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(minBpm > 0f && maxBpm > minBpm) { "need 0 < minBpm < maxBpm, got $minBpm..$maxBpm" }
        require(resonatorCount > 1) { "resonatorCount must exceed 1, was $resonatorCount" }
    }

    /** Distinct integer frame periods, ascending; one resonator each. */
    private val periods: IntArray = buildPeriods(resonatorCount)

    private val delayLines: Array<FloatArray> = Array(periods.size) { FloatArray(periods[it]) }
    private val delayIndex = IntArray(periods.size)

    /** Feedback coefficient per resonator, equal half-life in seconds. */
    private val feedback =
        FloatArray(periods.size) { i ->
            0.5f.pow(periods[i] / (halfLifeSeconds * hopRateHz))
        }

    /** Log-Gaussian tempo preference per resonator. */
    private val preference =
        FloatArray(periods.size) { i ->
            val octaves = ln(bpmOf(periods[i]) / preferredBpm) / LN_2
            exp(-0.5 * (octaves / preferenceOctaves) * (octaves / preferenceOctaves)).toFloat()
        }

    private val energy = FloatArray(periods.size)
    private val scores = FloatArray(periods.size)
    private val scoreScratch = FloatArray(periods.size)
    private val energyPole = 1f - exp(-1f / (energyAverageSeconds * hopRateHz))

    /** Winning period in frames; 0 until something has resonated. */
    var periodFrames: Float = 0f
        private set

    /** Winning tempo in BPM; 0 until something has resonated. */
    var bpm: Float = 0f
        private set

    /**
     * How much more clearly the winner resonates than the field, 0..1.
     *
     * Low on ambient and rubato material, which have no stable pulse to find.
     * A scene wanting tempo-synced choreography should fall back to
     * energy-driven motion below about 0.4 rather than follow a grid that is
     * guessing.
     */
    var confidence: Float = 0f
        private set

    /** Feeds one frame of onset evidence; read the outputs afterwards. */
    fun step(onset: Float) {
        var best = -1
        var bestScore = 0f
        for (i in periods.indices) {
            val line = delayLines[i]
            val at = delayIndex[i]
            val a = feedback[i]
            val y = a * line[at] + (1f - a) * onset
            line[at] = y
            delayIndex[i] = if (at + 1 == line.size) 0 else at + 1

            energy[i] += (y * y - energy[i]) * energyPole
            val score = energy[i] * preference[i]
            scores[i] = score
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }

        if (best < 0 || bestScore <= 0f) {
            periodFrames = 0f
            bpm = 0f
            confidence = 0f
            return
        }

        periodFrames = periods[best].toFloat()
        bpm = bpmOf(periods[best])
        confidence = clarity(bestScore)
    }

    /** Forgets every resonator; call on a track change or a seek. */
    fun reset() {
        for (line in delayLines) line.fill(0f)
        delayIndex.fill(0)
        energy.fill(0f)
        scores.fill(0f)
        periodFrames = 0f
        bpm = 0f
        confidence = 0f
    }

    /**
     * How far the winner stands above the typical resonator, as a fraction of
     * its own score. A median rather than a mean: half the bank resonating at
     * harmonics of the true period is normal, and a mean would read that as
     * ambiguity when it is in fact agreement.
     */
    private fun clarity(bestScore: Float): Float {
        System.arraycopy(scores, 0, scoreScratch, 0, scores.size)
        Arrays.sort(scoreScratch)
        val median = scoreScratch[scoreScratch.size / 2]
        return ((bestScore - median) / bestScore).coerceIn(0f, 1f)
    }

    private fun bpmOf(periodFrames: Int): Float = 60f * hopRateHz / periodFrames

    /**
     * Integer frame periods, log-spaced in tempo so the bank's resolution is
     * even in the units a listener hears. Deduped: at the fast end several
     * requested tempos round to the same frame count, and duplicate resonators
     * would each hold a vote.
     */
    private fun buildPeriods(count: Int): IntArray {
        val logLow = ln(minBpm.toDouble())
        val logHigh = ln(maxBpm.toDouble())
        val seen = sortedSetOf<Int>()
        for (i in 0 until count) {
            val candidateBpm = exp(logLow + (logHigh - logLow) * i / (count - 1))
            val period = (60.0 * hopRateHz / candidateBpm).roundToInt()
            if (period >= 2) seen.add(period)
        }
        require(seen.size > 1) {
            "hopRateHz $hopRateHz is too low to resolve $minBpm..$maxBpm BPM"
        }
        return seen.toIntArray()
    }

    companion object {
        private val LN_2 = ln(2.0).toFloat()
    }
}
