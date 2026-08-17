package dev.geode.engine.audio

import kotlin.math.exp
import kotlin.math.max

/**
 * Re-decides a whole track's rhythm from a stored onset curve.
 *
 * This is why an analysis cache stores the raw flux rather than the decided
 * beats: changing "Beat sensitivity" or "Minimum gap between beats" then
 * applies to already-analysed tracks immediately, without decoding them again.
 *
 * The guarantee that matters is that it is the *same* decision. [decide] drives
 * the same [OnsetPeakPicker], [TempoTracker] and [BeatGrid] the live path
 * drives, in the same order, from the same cold start — so live playback, a
 * cached timeline and an exported video cannot disagree about where the beats
 * are.
 */
object PulseReplay {
    /** Per-frame rhythm curves, all the same length as the input. */
    class Result(
        val beat: BooleanArray,
        val strength: FloatArray,
        val transient: FloatArray,
        val phase: FloatArray,
        val confidence: FloatArray,
        val energy: FloatArray,
    )

    /**
     * @param flux the per-frame onset evidence, in order, as produced by
     *   [ReactiveAnalyzer.fluxValue].
     * @param rms the per-frame level curve, used only to regrade [Result.energy];
     *   may be empty, in which case that curve is all zero.
     * @param hopRateHz the rate the curves were produced at — the refractory,
     *   the resonator periods and the beat grid are all measured in frames.
     */
    fun decide(
        flux: FloatArray,
        rms: FloatArray,
        hopRateHz: Float,
        sensitivity: Float,
        refractoryMs: Float,
    ): Result {
        val picker = OnsetPeakPicker(hopRateHz, sensitivity = sensitivity, refractorySeconds = refractoryMs / 1000f)
        val tempo = TempoTracker(hopRateHz)
        val grid = BeatGrid()

        val beat = BooleanArray(flux.size)
        val strength = FloatArray(flux.size)
        val transient = FloatArray(flux.size)
        val phase = FloatArray(flux.size)
        val confidence = FloatArray(flux.size)
        val energy = FloatArray(flux.size)

        val decay = exp(-1f / (hopRateHz * MACRO_PEAK_SECONDS))
        var peak = 0f

        for (i in flux.indices) {
            val isOnset = picker.accept(flux[i])
            transient[i] = picker.strength
            tempo.step(flux[i])
            beat[i] = grid.step(tempo.periodFrames, tempo.confidence, isOnset)
            strength[i] = if (beat[i]) picker.strength else 0f
            phase[i] = grid.phase
            confidence[i] = tempo.confidence

            val level = if (i < rms.size) rms[i] else 0f
            peak = max(level, peak * decay)
            energy[i] = if (peak <= 1e-6f) 0f else (level / peak).coerceIn(0f, 1f)
        }
        return Result(beat, strength, transient, phase, confidence, energy)
    }

    /** Must match [ReactiveAnalyzer]'s macro-energy memory, or export drifts. */
    private const val MACRO_PEAK_SECONDS = 20f
}
