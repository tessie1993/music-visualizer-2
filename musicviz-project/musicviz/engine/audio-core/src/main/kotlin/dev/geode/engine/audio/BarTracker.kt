package dev.geode.engine.audio

/**
 * Which beat is beat ONE — bar phase, beat-in-bar and the downbeat, over
 * the grid [BeatGrid] already tracks.
 *
 * The published downbeat intuition, implemented independently: the accented
 * position of a bar repeats, so evidence of how hard each position lands is
 * accumulated into a leaky four-slot histogram and the position that keeps
 * winning is the downbeat. One accented fill does not move it — a
 * challenger must beat the incumbent by [SWITCH_MARGIN] — and a track with
 * no repeating accent leaves [confidence] honestly near zero rather than
 * inventing a bar the music does not have.
 *
 * The bar assumes 4/4. That is an assumption, not a detection: a waltz gets
 * a musically wrong but stable bar, and the confidence stays low because
 * its accents do not repeat at four. Detecting the meter is a corpus-backed
 * slice of its own.
 *
 * Beat slots advance on the PHASE WRAP, not on accepted beats, so the bar
 * keeps flowing through a breakdown where [BeatGrid] rightly fires nothing
 * — bar-scale choreography should carry through the quiet, not stall at
 * position three until the drums return.
 *
 * Deterministic and ordered: [step] must see every frame, in order.
 * Allocates nothing.
 */
class BarTracker {
    /** Position within the bar, 0 on the downbeat rising to 1 before the next. */
    var barPhase: Float = 0f
        private set

    /** Which beat of the bar the grid is in, 0..3; 0 is the downbeat's. */
    var beatInBar: Int = 0
        private set

    /** Whether this frame's beat is the bar's first. */
    var downbeat: Boolean = false
        private set

    /** How clearly one position keeps winning, 0..1; 0 while the grid is unlocked. */
    var confidence: Float = 0f
        private set

    private var prevPhase = 0f
    private var beatIndex = 0
    private var downbeatPos = 0
    private var beatsSeen = 0
    private val scores = FloatArray(BEATS_PER_BAR)

    /**
     * Advances one frame.
     *
     * @param phase [BeatGrid.phase], the intra-beat ramp.
     * @param beat whether this frame is an accepted beat.
     * @param locked [BeatGrid.locked]; an unlocked grid's bar is a guess,
     *   so it earns no confidence.
     * @param accent how hard this beat landed (0 off beats) — the caller's
     *   mix of beat strength and low-band evidence, in any consistent unit.
     */
    fun step(
        phase: Float,
        beat: Boolean,
        locked: Boolean,
        accent: Float,
    ) {
        if (phase < prevPhase - WRAP_THRESHOLD) {
            beatIndex = (beatIndex + 1) % BEATS_PER_BAR
            for (i in scores.indices) scores[i] *= SCORE_LEAK
        }
        prevPhase = phase

        if (beat && accent > 0f) {
            // A beat accepted late in its slot (the grid tolerates ±0.12 of
            // a period) is the NEXT beat arriving early, not this one again.
            val slot = if (phase > 0.5f) (beatIndex + 1) % BEATS_PER_BAR else beatIndex
            scores[slot] += accent
            if (beatsSeen < BEATS_PER_BAR) beatsSeen++
            elect()
        }

        beatInBar = (beatIndex - downbeatPos + BEATS_PER_BAR) % BEATS_PER_BAR
        barPhase = (beatInBar + phase.coerceIn(0f, 1f)) / BEATS_PER_BAR
        // The very first beats a cold tracker hears would elect themselves;
        // a downbeat is a CLAIM, and it waits for a bar of evidence.
        downbeat = beat && beatInBar == 0 && beatsSeen >= BEATS_PER_BAR
        confidence = if (locked) clarity() else 0f
    }

    /** Forgets the learned bar; call on a track change or a seek. */
    fun reset() {
        prevPhase = 0f
        beatIndex = 0
        downbeatPos = 0
        beatsSeen = 0
        scores.fill(0f)
        barPhase = 0f
        beatInBar = 0
        downbeat = false
        confidence = 0f
    }

    private fun elect() {
        var best = downbeatPos
        for (i in scores.indices) {
            if (scores[i] > scores[best]) best = i
        }
        if (best != downbeatPos && scores[best] > scores[downbeatPos] * SWITCH_MARGIN) {
            downbeatPos = best
        }
    }

    /**
     * The winner's margin over the runner-up, as a fraction of the winner:
     * 0 when every position lands alike, toward 1 when one dominates.
     */
    private fun clarity(): Float {
        var best = 0f
        var second = 0f
        for (s in scores) {
            if (s > best) {
                second = best
                best = s
            } else if (s > second) {
                second = s
            }
        }
        return if (best <= 1e-6f) 0f else ((best - second) / best).coerceIn(0f, 1f)
    }

    companion object {
        const val BEATS_PER_BAR: Int = 4

        /**
         * How far [BeatGrid.phase] must fall in one frame to be a wrap
         * rather than the grid's own small phase correction, which pulls at
         * most [BeatGrid.ON_GRID_TOLERANCE] x [BeatGrid.PHASE_CORRECTION].
         */
        const val WRAP_THRESHOLD: Float = 0.5f

        /**
         * Per-beat decay of the accent histogram — a memory of roughly
         * eight bars, long enough to ride out a fill, short enough to
         * follow an arrangement that genuinely turns the bar around.
         */
        const val SCORE_LEAK: Float = 0.969f

        /** How decisively a challenger must beat the incumbent downbeat. */
        const val SWITCH_MARGIN: Float = 1.25f
    }
}
