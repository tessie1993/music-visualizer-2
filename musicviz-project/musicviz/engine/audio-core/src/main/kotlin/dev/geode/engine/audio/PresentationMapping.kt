package dev.geode.engine.audio

/**
 * Where an input frame lands on the presentation timeline.
 *
 * MASTER_PLAN §5.2 requires unmappable gaps to be surfaced rather than
 * interpolated across, so the three ways a frame can fail to have a
 * presentation time are three cases and not one null.
 */
sealed interface PresentationTime {
    /** The frame is heard at [us] on the output timeline. */
    data class At(
        val us: Long,
    ) : PresentationTime

    /**
     * The frame fell inside a span silence skipping removed, so it is never
     * heard at all. Interpolating across this would give a time at which
     * different audio is playing.
     */
    data class Skipped(
        val fromInputSample: Long,
        val toInputSample: Long,
    ) : PresentationTime

    /**
     * The frame belongs to a numbering that has ended — a seek, a source
     * change, a format change the ring could not absorb. §5.1's whole reason
     * for the epoch field: without this the answer would be plausible and wrong.
     */
    data class StaleEpoch(
        val asked: Int,
        val current: Int,
    ) : PresentationTime

    /** Older than the oldest segment still held, or nothing recorded yet. */
    data object Unknown : PresentationTime
}

/** Which input frame is being heard at a given presentation time. */
sealed interface InputPosition {
    /**
     * [inputSample] in [epoch]. The epoch travels with the answer because a
     * frame index without one is not a position.
     */
    data class At(
        val inputSample: Long,
        val epoch: Int,
    ) : InputPosition

    /** Before the oldest segment still held, or nothing recorded yet. */
    data object Unknown : InputPosition
}
