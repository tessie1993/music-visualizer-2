package dev.musicviz.engine.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns [OnsetStrength] into onsets: one event per excursion above a threshold
 * that follows the music's own recent statistics.
 *
 * ## One onset per excursion, timed at its peak
 *
 * The compact C tracker (BTT, ORACLE tier) contributes the first half of the
 * rule: an onset is one **excursion** above a threshold, not every frame that
 * happens to be above one. A held chord or a long crash sits above the mean for
 * a second at a time, and a level test reports it eighty-six times.
 *
 * BTT then reports the excursion at the frame it *began*. That cannot be
 * sample-aligned, and it is why BTT carries an empirically measured latency
 * constant whose own header blames "complex interactions between filters,
 * buffering, adaptive thresholds, and other things": where in the rise the
 * signal crosses the threshold depends on the threshold, so the offset between
 * the reported frame and the audio is not a constant at all.
 *
 * This reports the excursion's **peak** — the first frame that failed to rise
 * — which is fixed by the transient, not by the threshold. [OnsetStrength]'s
 * kernel is symmetric, so it is linear phase and delays every frequency by the
 * same [OnsetStrength.delayFrames]; a peak therefore comes out exactly that far
 * late and nowhere else. Locating the peak rather than the edge is what turns
 * that property into an exact sample index:
 *
 * ```
 * onsetSample = grid.centerSample(frame - PEAK_FRAMES_BACK) - strength.delayFrames * branch.hopFrames
 * ```
 *
 * with no empirical term anywhere. `OnsetDetectorTest` runs that composition
 * against a known transient and requires it to land on the sample exactly.
 *
 * The cost is one frame of lookahead: the peak is only known once the signal
 * has failed to beat it. That frame was already spent — the strength signal's
 * own delay is longer — and it buys an exact time instead of an approximate one.
 *
 * This is a different mechanism from the millisecond refractory the app's
 * `FeatureExtractor.BeatGate` applies, and does not replace it. The excursion
 * rule stops one long event being reported many times; the refractory stops
 * many short events being reported too close together. §5.3's graph wants both,
 * and this supplies the half that is missing.
 *
 * ## Why the threshold is a small offset and not a rarity
 *
 * [DEVIATIONS] is a tenth of a standard deviation, which reads as far too low
 * for a detector until the excursion rule is taken into account: with it, the
 * pair says "report each peak the strength signal makes above its recent
 * average", not "report every frame that is unusual". It is deliberately not the
 * 1.5-to-6 sigma the app's beat gate runs at — that gate is picking *beats* out
 * of onsets, and this is producing the onsets it picks from. Setting this to a
 * beat-like sigma would throw away the evidence the tracker needs before the
 * tracker ever sees it.
 *
 * [minimum] is what keeps a passage with almost no flux variance — a sustained
 * pad, room tone above the silence floor — from crossing its own flattened
 * average every few frames. [SilenceGate] handles actual silence; this handles
 * the quiet material that is not silent.
 *
 * ## Statistics
 *
 * Sliding mean and population standard deviation over [historySeconds] of
 * sounding frames, maintained incrementally in double. Measured against a
 * recomputation over two million frames — six and a half hours at an 86 Hz hop
 * — the incremental form drifts by 2e-13 relative, so it is used rather than
 * recomputing the window every frame.
 *
 * Silent frames neither fire nor train, the same rule [AdaptiveRange] follows
 * and for the same reason: a rest that dragged the mean down would make the
 * first frame of the next phrase an onset whatever its size.
 */
class OnsetDetector(
    hopRateHz: Float,
    /**
     * Smallest excursion above the running mean that can count, in the onset
     * strength's own units — which are the flux's, since [OnsetStrength]
     * normalises to unit gain.
     *
     * Required rather than defaulted for the reason [AdaptiveRange.minimumSpan]
     * is: flux scale depends on FFT size and on how magnitudes are scaled, so
     * no constant here can be right for every caller. BTT's own 5.0 is in its
     * unnormalised units and does not transfer.
     */
    val minimum: Float,
    historySeconds: Float = HISTORY_SECONDS,
    private val deviations: Float = DEVIATIONS,
    warmupSeconds: Float = WARMUP_SECONDS,
) {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(minimum > 0f && minimum.isFinite()) { "minimum must be positive, was $minimum" }
        require(historySeconds > 0f) { "historySeconds must be positive, was $historySeconds" }
        require(deviations > 0f) { "deviations must be positive, was $deviations" }
        require(warmupSeconds > 0f) { "warmupSeconds must be positive, was $warmupSeconds" }
    }

    private val windowFrames = (historySeconds * hopRateHz).roundToInt().coerceAtLeast(2)
    private val warmupFrames = (warmupSeconds * hopRateHz).roundToInt().coerceAtLeast(2)

    private val history = DoubleArray(windowFrames)
    private var cursor = 0
    private var filled = 0
    private var mean = 0.0

    /** Sum of squared deviations over the frames currently held. */
    private var squares = 0.0
    private var wasAbove = false
    private var previous = 0f
    private var reportedThisExcursion = false

    /** How much the last [next] is worth. [FeatureValidity.Warmup] until the statistics mean something. */
    var validity: FeatureValidity = FeatureValidity.Warmup
        private set

    /** The level the strength signal had to exceed on the last sounding frame. */
    var threshold: Float = 0f
        private set

    /** The running mean of the strength signal over the history window. */
    val runningMean: Float get() = mean.toFloat()

    /**
     * Feeds one frame's onset strength and returns whether an onset peaked
     * [PEAK_FRAMES_BACK] frames ago.
     *
     * A silent frame is never an onset and never trains.
     */
    fun next(
        strength: Float,
        activity: FrameActivity,
    ): Boolean {
        when (activity) {
            FrameActivity.Silent -> {
                validity = FeatureValidity.Silent
                // The excursion is abandoned rather than left open, so the
                // first phrase after a rest gets its own onset.
                wasAbove = false
                reportedThisExcursion = false
                previous = 0f
                return false
            }
            FrameActivity.Sounding -> Unit
        }
        train(strength.toDouble())
        val deviation = sqrt(maxOf(squares / filled, 0.0))
        threshold = maxOf(deviations * deviation.toFloat(), minimum)
        val above = strength - mean > threshold
        // The peak is the first frame that failed to beat the one before it,
        // so a plateau reports where the rise ended rather than waiting for the
        // fall — a held crash would otherwise be timed at its release.
        val turned = above && wasAbove && strength <= previous
        val fired = turned && !reportedThisExcursion && filled >= warmupFrames
        reportedThisExcursion = if (above) reportedThisExcursion || fired else false
        wasAbove = above
        previous = strength
        validity = if (filled >= warmupFrames) FeatureValidity.Valid else FeatureValidity.Warmup
        return fired
    }

    private fun train(x: Double) {
        val oldest = history[cursor]
        history[cursor] = x
        cursor = if (cursor + 1 == windowFrames) 0 else cursor + 1
        if (filled < windowFrames) {
            // Welford while the window fills, so [squares] is the exact sum of
            // squared deviations by the time the sliding form takes over.
            filled++
            val delta = x - mean
            mean += delta / filled
            squares += delta * (x - mean)
        } else {
            val next = mean + (x - oldest) / windowFrames
            squares += (x + oldest - mean - next) * (x - oldest)
            mean = next
        }
    }

    /** Forgets the stream, as at a track change or a seek. */
    fun reset() {
        history.fill(0.0)
        cursor = 0
        filled = 0
        mean = 0.0
        squares = 0.0
        wasAbove = false
        previous = 0f
        reportedThisExcursion = false
        threshold = 0f
        validity = FeatureValidity.Warmup
    }

    companion object {
        /**
         * Frames between the frame [next] just saw and the peak it reported.
         * One, because a peak is only known once something has failed to beat
         * it — the lookahead the exactness above is bought with.
         */
        const val PEAK_FRAMES_BACK = 1

        /**
         * How far back the mean and deviation look. Long enough to span a bar
         * at any tempo the tracker admits, so one loud fill does not raise the
         * bar for the phrase that follows it.
         */
        const val HISTORY_SECONDS = 3f

        /** See the class note: an offset above the mean, not a rarity threshold. */
        const val DEVIATIONS = 0.1f

        /** Long enough for a deviation to mean something, short enough not to miss a track's first bar. */
        const val WARMUP_SECONDS = 0.25f
    }
}
