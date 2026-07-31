package dev.musicviz.analysis

import kotlin.math.abs
import kotlin.math.max

/**
 * Tempo-phase-locked pulse tracking: the layer between raw onset detection and
 * what the visuals actually ride.
 *
 * The plain [FeatureExtractor.BeatGate] is REACTIVE - any flux spike past the
 * sigma threshold fires, and every firing looks the same to a scene. On real
 * material that means syncopated hits, fills, vocal consonants and anything
 * else that survives the band weighting all trigger full-strength visual
 * events, which reads as flicker rather than rhythm. The fix, standard in the
 * beat-tracking literature (Scheirer's comb-filter tracker, Ellis' dynamic-
 * programming beat tracking: both model a tempo PERIOD and a beat PHASE, then
 * treat onsets as evidence for that grid rather than as beats themselves), is
 * to predict where beats should land and hold everything else back:
 *
 *  - The gate still produces CANDIDATES, under the user's sigma/refractory
 *    settings - those sliders keep their meaning.
 *  - The onset autocorrelation (the BPM estimate the app already computes)
 *    provides the period; accepted candidates anchor the phase, and a small
 *    phase-locked-loop correction keeps the grid on the music.
 *  - Once [confidence] is high enough to call the grid LOCKED, candidates far
 *    from a predicted beat are suppressed - that single rule removes most of
 *    the "way too many triggers" complaint - unless they are so far above the
 *    threshold ([ACCENT_EXTRA_SIGMA]) that they are clearly a deliberate
 *    accent (a fill, a drop hit) rather than texture.
 *  - A predicted beat with no candidate coasts: the grid advances silently,
 *    so a breakdown stays calm instead of re-normalising and strobing.
 *  - Tracks with no stable pulse never lock (the autocorrelation clarity gate
 *    keeps [confidence] down), and the tracker degrades to the plain gate at
 *    reduced strength - ambient material breathes instead of flashing.
 *
 * Beats also stop being all-or-nothing: [strength] grades each accepted beat
 * by how far its flux rose past the threshold, scaled by the track-relative
 * [energy] envelope, so a soft verse hit nudges the visuals where a chorus
 * hit slams them. [phase] is a continuous 0..1 saw over the beat grid for
 * scenes that want motion BETWEEN beats, not just at them.
 *
 * Deterministic and ordered, exactly like the gate it wraps: [step] must see
 * every frame in order, and [decidePulse] replays the same code over a stored
 * flux/rms curve so live playback, cached analysis and video export cannot
 * disagree. The beat DECISION depends only on the flux curve and the two gate
 * settings ([energy] shapes strength, never acceptance), which is what keeps
 * [FeatureExtractor.decideBeats]'s flux-only replay exact.
 */
class PulseTracker(
    private val hopRateHz: Float,
    historySeconds: Float = FeatureExtractor.HISTORY_SECONDS,
) {
    /** Candidate gate; the sigma/interval settings live on it. */
    val gate = FeatureExtractor.BeatGate(hopRateHz, historySeconds)

    // ---- outputs, valid after each [step] ----

    /** Whether this frame is an accepted beat. */
    var beat: Boolean = false
        private set

    /** Graded weight of an accepted beat, [STRENGTH_FLOOR]..1; 0 off-beat. */
    var strength: Float = 0f
        private set

    /** Position within the current beat interval, 0 (on the beat) rising to 1. */
    var phase: Float = 0f
        private set

    /** How sure the tracker is that its beat grid matches the music, 0..1. */
    var confidence: Float = 0f
        private set

    /** Track-relative macro-dynamics envelope, 0..1 (see [EnergyFollower]). */
    var energy: Float = 0f
        private set

    /** Latest raw autocorrelation BPM, 0 until the history half-fills. */
    var bpmEstimate: Float = 0f
        private set

    private val energyFollower = EnergyFollower(hopRateHz)
    private var frame = 0
    private var periodFrames = 0f
    private var predictedFrame = 0f
    private var locked = false
    private var tempoClarity = 0f
    private var divergentFrames = 0
    private var predictionStreak = 0
    private var anchorZ = 0f

    /** Feeds one frame's flux and rms; read the outputs afterwards. */
    fun step(
        flux: Float,
        rms: Float,
    ) {
        frame++
        energy = energyFollower.step(rms)
        val candidate = gate.accept(flux)
        val std = gate.fluxStd
        val z = if (std > 1e-6f) (flux - gate.fluxMean) / std else 0f
        if (gate.filled >= gate.size / 2) updateTempo()

        beat = false
        strength = 0f
        anchorZ *= ANCHOR_DECAY
        val period = periodFrames
        if (candidate) {
            // Distance to the NEAREST grid point, so a candidate one whole
            // period late still reads as on-grid rather than off by 100%.
            var err = 0f
            var onGrid = false
            if (period > 0f) {
                err = frame - predictedFrame
                err -= Math.round(err / period) * period
                onGrid = abs(err) <= period * GRID_TOLERANCE
            }
            when {
                period <= 0f -> {
                    // No tempo estimate yet: the reactive gate, softened.
                    beat = true
                    strength = grade(z) * UNLOCKED_SCALE
                }
                onGrid -> {
                    beat = true
                    strength = grade(z) * if (locked) 1f else UNLOCKED_SCALE
                    // Confidence needs a run of CONSECUTIVELY confirmed
                    // predictions, not a hit tally: random transients land
                    // inside the window ~40% of the time, so counting lone
                    // hits would let aperiodic material lock by chance, while
                    // P(three in a row) is what separates a real pulse.
                    predictionStreak++
                    if (predictionStreak >= CONF_STREAK) confidence += CONF_HIT * (1f - confidence)
                    // PLL: late beats stretch the period, early ones shrink it.
                    periodFrames = (period + PLL_GAIN * err).coerceIn(minPeriod(), maxPeriod())
                    predictedFrame = frame + periodFrames
                    anchorZ = max(anchorZ, z)
                }
                !locked -> {
                    // Unlocked off-grid: still fires (no lock means no right
                    // to suppress), and a hit clearly stronger than the
                    // current anchor re-seats the grid on itself - which is
                    // what keeps the phase on the kick rather than on
                    // whatever transient happened to arrive first.
                    beat = true
                    strength = grade(z) * UNLOCKED_SCALE
                    confidence *= CONF_OFF_GRID
                    if (z >= anchorZ * ANCHOR_TAKEOVER) {
                        predictedFrame = frame + period
                        anchorZ = z
                        predictionStreak = 0
                    }
                }
                z >= gate.beatThresholdSigma + ACCENT_EXTRA_SIGMA -> {
                    // A deliberate accent, not texture: pass it through but
                    // keep the grid anchored where the music actually is.
                    beat = true
                    strength = grade(z)
                    confidence *= CONF_OFF_GRID
                }
                else -> confidence *= CONF_OFF_GRID
            }
        } else if (period > 0f && frame > predictedFrame + period * GRID_TOLERANCE) {
            // The predicted beat came and went with nothing there: coast.
            predictedFrame += period
            predictionStreak = 0
            confidence *= CONF_COAST
        }
        // Confidence may only persist while the onset envelope is actually
        // periodic; without this, random transients that happen to land near
        // predictions would lock onto silence-adjacent noise.
        if (tempoClarity < CLARITY_MIN) confidence *= CLARITY_DECAY
        locked = confidence >= if (locked) LOCK_EXIT else LOCK_ENTER
        phase =
            if (periodFrames > 0f) {
                ((frame - (predictedFrame - periodFrames)) / periodFrames).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    /** [strength] before scene envelopes: 0 between beats, graded on them. */
    private fun grade(z: Float): Float {
        val t = ((z - gate.beatThresholdSigma) / STRENGTH_SPAN_SIGMA).coerceIn(0f, 1f)
        val raw = STRENGTH_FLOOR + (1f - STRENGTH_FLOOR) * t
        return raw * (ENERGY_BASE + (1f - ENERGY_BASE) * energy)
    }

    private fun minPeriod(): Float = hopRateHz * 60f / BPM_MAX

    private fun maxPeriod(): Float = hopRateHz * 60f / BPM_MIN

    /**
     * Autocorrelation of the onset envelope over lags covering
     * [BPM_MIN]..[BPM_MAX] - the same estimate [FeatureExtractor] always
     * published as `bpm`, now also yielding the grid period and a clarity
     * score (how much the best lag stands out from the rest; near zero for
     * aperiodic material, which is what keeps ambient tracks unlocked).
     */
    private fun updateTempo() {
        val filled = gate.filled
        val minLag = minPeriod().toInt().coerceAtLeast(2)
        val maxLag = maxPeriod().toInt().coerceAtMost(filled / 2)
        if (maxLag <= minLag) return
        var bestLag = 0
        var bestScore = 0f
        var scoreSum = 0f
        for (lag in minLag..maxLag) {
            var score = 0f
            val overlap = filled - lag
            for (i in 0 until overlap) {
                score += gate.chronological(i) * gate.chronological(i + lag)
            }
            // Normalize by overlap length: raw sums have more terms at small
            // lags, which biased the estimate toward doubled BPM.
            score /= overlap.coerceAtLeast(1)
            scoreSum += score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return
        bpmEstimate = hopRateHz * 60f / bestLag
        val meanScore = scoreSum / (maxLag - minLag + 1)
        tempoClarity = if (bestScore > 1e-9f) (1f - meanScore / bestScore).coerceIn(0f, 1f) else 0f
        val target = bestLag.toFloat()
        when {
            periodFrames <= 0f -> {
                periodFrames = target
                predictedFrame = frame + target
            }
            abs(target - periodFrames) / periodFrames > PERIOD_SNAP_RATIO -> {
                // A harmonic flip or real tempo change - but only re-seat the
                // grid once the new estimate has PERSISTED, or a single noisy
                // autocorrelation frame would break an otherwise solid lock.
                divergentFrames++
                if (divergentFrames >= (hopRateHz * PERIOD_SNAP_SECONDS).toInt()) {
                    periodFrames = target
                    predictedFrame = frame + target
                    divergentFrames = 0
                    confidence *= 0.5f
                }
            }
            else -> {
                divergentFrames = 0
                periodFrames += PERIOD_TRACK_GAIN * (target - periodFrames)
            }
        }
    }

    /**
     * Track-relative macro dynamics: a fast-attack/slow-release follower of
     * rms, normalized by a slowly decaying rolling peak - "how loud is this
     * moment relative to this song", 0..1. A whole-track normalization would
     * need two passes; the rolling peak converges within a few seconds and
     * keeps live analysis, cached replay and export identical.
     */
    class EnergyFollower(
        hopRateHz: Float,
    ) {
        private val dt = 1f / hopRateHz.coerceAtLeast(1f)
        private var follower = 0f
        private var peak = 0f

        /** Envelope after the latest frame, 0..1. */
        var value = 0f
            private set

        fun step(rms: Float): Float {
            val tau = if (rms > follower) ATTACK_SECONDS else RELEASE_SECONDS
            follower += (rms - follower) * (dt / tau).coerceAtMost(1f)
            peak = max(follower, peak - peak * dt / PEAK_DECAY_SECONDS)
            value = if (peak > SILENCE_FLOOR) (follower / peak).coerceIn(0f, 1f) else 0f
            return value
        }

        private companion object {
            const val ATTACK_SECONDS = 0.15f
            const val RELEASE_SECONDS = 1.2f

            /** Rolling-peak decay; long enough that a post-chorus verse still
             *  reads visibly quieter, short enough to re-adapt within about a
             *  minute after a loud intro on an otherwise soft track. */
            const val PEAK_DECAY_SECONDS = 30f

            /** Below this peak the track is silent; report 0, not garbage. */
            const val SILENCE_FLOOR = 1e-3f
        }
    }

    /** Replayed pulse decisions for a whole stored curve, index-aligned. */
    class PulseCurves(
        val beat: BooleanArray,
        val strength: FloatArray,
        val phase: FloatArray,
        val confidence: FloatArray,
        val energy: FloatArray,
    )

    companion object {
        /** Beat-grid window as a fraction of the period, each side. At 120 BPM
         *  (500 ms period) this accepts +-100 ms - generous against onset
         *  smear, while an off-beat eighth (250 ms away) stays well outside. */
        const val GRID_TOLERANCE = 0.2f

        /** PLL correction gain from a beat's timing error into the period. */
        const val PLL_GAIN = 0.15f

        /** Sigmas ABOVE the beat threshold at which an off-grid candidate is
         *  a deliberate accent that may bypass the grid. */
        const val ACCENT_EXTRA_SIGMA = 1.5f

        /** Confidence pull toward 1 on an on-grid hit (once streaking). */
        const val CONF_HIT = 0.22f

        /** Consecutive confirmed predictions before confidence may grow. */
        const val CONF_STREAK = 3

        /** Per-frame decay of the anchor-strength memory (~halves in 6 s). */
        const val ANCHOR_DECAY = 0.998f

        /** Fraction of the anchor strength an unlocked off-grid hit must
         *  reach to re-seat the grid phase on itself. */
        const val ANCHOR_TAKEOVER = 0.8f

        /** Confidence factor per coasted (missed) prediction. */
        const val CONF_COAST = 0.85f

        /** Confidence factor per off-grid candidate. */
        const val CONF_OFF_GRID = 0.97f

        /** Confidence factor per frame while the tempo estimate is unclear. */
        const val CLARITY_DECAY = 0.99f

        /** Autocorrelation clarity below which confidence cannot persist. */
        const val CLARITY_MIN = 0.3f

        /** Lock hysteresis: enter at/above, leave only below the exit level. */
        const val LOCK_ENTER = 0.5f
        const val LOCK_EXIT = 0.25f

        /** Strength multiplier while unlocked - the reactive fallback should
         *  read softer than a confirmed rhythmic pulse. */
        const val UNLOCKED_SCALE = 0.8f

        /** Weakest accepted beat's strength; a beat must still be visible. */
        const val STRENGTH_FLOOR = 0.35f

        /** Sigmas past the threshold at which strength saturates at 1. */
        const val STRENGTH_SPAN_SIGMA = 3f

        /** Portion of strength independent of the macro-energy envelope. */
        const val ENERGY_BASE = 0.6f

        /** Tempo search range, matching the historical BPM estimate. */
        const val BPM_MIN = 60f
        const val BPM_MAX = 200f

        /** Period divergence treated as a tempo change rather than jitter. */
        const val PERIOD_SNAP_RATIO = 0.25f

        /** How long a divergent tempo must persist before the grid re-seats. */
        const val PERIOD_SNAP_SECONDS = 1.5f

        /** Tracking gain from the autocorrelation period into the grid's. */
        const val PERIOD_TRACK_GAIN = 0.02f

        /**
         * Replays the tracker over a stored flux/rms curve - the offline
         * counterpart of per-frame [step], used when a cached timeline is
         * read back at the user's current sensitivity. Settings are clamped
         * exactly as [AnalysisEngine] clamps them, so live and offline cannot
         * drift apart at the extremes. Pass an all-zero [rms] when only the
         * beat flags matter: energy shapes strength, never acceptance.
         */
        fun decidePulse(
            flux: FloatArray,
            rms: FloatArray,
            hopRateHz: Float,
            beatThresholdSigma: Float,
            beatMinIntervalMs: Float,
            historySeconds: Float = FeatureExtractor.HISTORY_SECONDS,
        ): PulseCurves {
            val tracker = PulseTracker(hopRateHz, historySeconds)
            tracker.gate.beatThresholdSigma =
                beatThresholdSigma.coerceIn(FeatureExtractor.SIGMA_MIN, FeatureExtractor.SIGMA_MAX)
            tracker.gate.beatMinIntervalMs =
                beatMinIntervalMs.coerceIn(FeatureExtractor.INTERVAL_MS_MIN, FeatureExtractor.INTERVAL_MS_MAX)
            val beat = BooleanArray(flux.size)
            val strength = FloatArray(flux.size)
            val phase = FloatArray(flux.size)
            val confidence = FloatArray(flux.size)
            val energy = FloatArray(flux.size)
            for (i in flux.indices) {
                tracker.step(flux[i], if (i < rms.size) rms[i] else 0f)
                beat[i] = tracker.beat
                strength[i] = tracker.strength
                phase[i] = tracker.phase
                confidence[i] = tracker.confidence
                energy[i] = tracker.energy
            }
            return PulseCurves(beat, strength, phase, confidence, energy)
        }
    }
}
