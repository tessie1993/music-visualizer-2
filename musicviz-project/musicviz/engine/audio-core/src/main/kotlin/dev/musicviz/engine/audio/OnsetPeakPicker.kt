package dev.musicviz.engine.audio

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Causal peak picking over an onset envelope: adaptive threshold, local-maximum
 * test, refractory window.
 *
 * `docs/quality/bar-visualizer.md` §2.4 asks for exactly these three, and names
 * the two failures they exist to prevent: "Raw energy thresholds fire on
 * sustained loudness and miss soft transients", and "fixed thresholds break
 * across genres/volumes".
 *
 * ## Median, not mean — the reason this is a rewrite and not a retune
 *
 * The legacy `BeatGate` thresholded at `mean + sigma * stddev` over a six-second
 * flux window. Both statistics are pulled by the very peaks being detected, so
 * on dense material the threshold climbs toward the peaks themselves and the
 * detector loses exactly the hits that make a passage busy. Measured on a
 * 10 Hz onset train at the shipped sensitivity, swapping this median and mean
 * absolute deviation back for that mean and standard deviation drops detection
 * from 99 of 100 onsets to 85 — and no sensitivity setting recovers them,
 * because raising it only raises the threshold too. The statistic is the
 * problem, which is why this is a rewrite rather than a retune.
 *
 * The spread is a mean absolute deviation about the median, floored at a
 * fraction of the median so that a perfectly steady envelope cannot produce a
 * zero-width threshold. Both terms scale with the signal, which is what makes
 * the detector level-independent: the same music at any master level yields the
 * same onsets, frame for frame.
 *
 * Deterministic and ordered: [accept] must see every frame, in order.
 */
class OnsetPeakPicker(
    private val hopRateHz: Float,
    windowSeconds: Float = 1.5f,
    /**
     * How many robust deviations above the median an onset must reach. Higher
     * means fewer, surer onsets; this is the user-facing sensitivity control.
     */
    @Volatile var sensitivity: Float = 3f,
    /** No second onset may fire until this long after the last one. */
    @Volatile var refractorySeconds: Float = 0.06f,
    /**
     * How far back the local-maximum test looks. An onset must be strictly
     * greater than everything in this window, which is what distinguishes the
     * step at the start of a sustained note from the sustain that follows it.
     */
    localMaxSeconds: Float = 0.03f,
) {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(windowSeconds > 0f) { "windowSeconds must be positive, was $windowSeconds" }
    }

    private val windowSize = (hopRateHz * windowSeconds).roundToInt().coerceAtLeast(3)
    private val window = FloatArray(windowSize)
    private val scratch = FloatArray(windowSize)
    private var writeIndex = 0
    private var filled = 0

    private val localMaxSize = (hopRateHz * localMaxSeconds).roundToInt().coerceAtLeast(1)
    private val recent = FloatArray(localMaxSize)
    private var recentIndex = 0

    private var framesSinceOnset = Int.MAX_VALUE / 2

    /** The adaptive threshold as of the last [accept]. */
    var threshold: Float = 0f
        private set

    /**
     * How hard the last accepted onset hit, 0..1, measured against the track's
     * own recent peaks. 0 on frames that were not onsets.
     *
     * Graded this way and not by how far the onset cleared the threshold,
     * because that quantity does not grade: an isolated spike dominates the
     * deviation of its own window, so its z-score comes out at roughly the
     * window length whatever its height. What a visual wants is "how hard was
     * that *for this track*", which is a ratio against a decaying record of
     * recent peaks — the first hit after a reset is full strength, and a hit a
     * tenth the size of the last one reads a tenth as hard.
     */
    var strength: Float = 0f
        private set

    /** Decaying record of recent onset peaks; the denominator of [strength]. */
    private var peakEnvelope = 0f
    private val peakDecayPerFrame = exp(-1f / (hopRateHz * PEAK_MEMORY_SECONDS))

    /**
     * Feeds one frame of onset evidence and returns whether it is an onset.
     * Read [strength] afterwards.
     */
    fun accept(onset: Float): Boolean {
        val precedingMax = precedingMax()

        window[writeIndex] = onset
        writeIndex = (writeIndex + 1) % windowSize
        if (filled < windowSize) filled++

        val median = median()
        val spread = max(max(deviation(median), median * SPREAD_FLOOR_FRACTION), MIN_SPREAD)
        threshold = median + sensitivity * spread

        val refractoryFrames = (hopRateHz * refractorySeconds).roundToInt().coerceAtLeast(1)
        val isOnset =
            onset > threshold &&
                onset > NUMERIC_FLOOR &&
                onset > precedingMax &&
                framesSinceOnset > refractoryFrames

        // Read the peak as it stands BEFORE this onset joins it, or every hit
        // would be measured against itself and grade 1.
        val decayedPeak = peakEnvelope * peakDecayPerFrame
        strength = if (isOnset) (onset / max(decayedPeak, NUMERIC_FLOOR)).coerceIn(0f, 1f) else 0f
        peakEnvelope = max(onset, decayedPeak)

        // Saturating rather than free-running: an unbounded counter overflows
        // to negative on a long-running wallpaper and the gate then never fires
        // again. The ceiling is far above any reachable refractory.
        framesSinceOnset = if (isOnset) 0 else min(framesSinceOnset + 1, 1_000_000)

        recent[recentIndex] = onset
        recentIndex = (recentIndex + 1) % localMaxSize
        return isOnset
    }

    /**
     * Forgets the window, the local-maximum history and the refractory
     * countdown, keeping [sensitivity] and [refractorySeconds]. Leaves the
     * picker indistinguishable from a fresh one; call on a track change or a
     * seek.
     */
    fun reset() {
        window.fill(0f)
        recent.fill(0f)
        writeIndex = 0
        filled = 0
        recentIndex = 0
        framesSinceOnset = Int.MAX_VALUE / 2
        threshold = 0f
        strength = 0f
        peakEnvelope = 0f
    }

    private fun precedingMax(): Float {
        var peak = 0f
        for (i in 0 until localMaxSize) if (recent[i] > peak) peak = recent[i]
        return peak
    }

    private fun median(): Float {
        System.arraycopy(window, 0, scratch, 0, filled)
        Arrays.sort(scratch, 0, filled)
        val mid = filled / 2
        return if (filled % 2 == 1) scratch[mid] else (scratch[mid - 1] + scratch[mid]) * 0.5f
    }

    private fun deviation(median: Float): Float {
        var acc = 0f
        for (i in 0 until filled) acc += abs(window[i] - median)
        return acc / filled
    }

    companion object {
        /**
         * Floor on the spread, as a fraction of the median. Scales with the
         * signal, so it cannot break the detector's level-independence the way
         * an absolute floor would.
         */
        const val SPREAD_FLOOR_FRACTION: Float = 0.05f

        /** Absolute backstop against a division by zero on digital silence. */
        const val MIN_SPREAD: Float = 1e-6f

        /**
         * Below this an "onset" is float noise rather than audio. Deliberately
         * far under any musical level: the musical decision is the adaptive
         * threshold's job, not this one's.
         */
        const val NUMERIC_FLOOR: Float = 1e-6f

        /**
         * How long [strength]'s reference peak remembers. Long enough to span a
         * phrase, so a breakdown is graded against the chorus before it rather
         * than re-normalizing itself back to full strength.
         */
        const val PEAK_MEMORY_SECONDS: Float = 8f
    }
}
