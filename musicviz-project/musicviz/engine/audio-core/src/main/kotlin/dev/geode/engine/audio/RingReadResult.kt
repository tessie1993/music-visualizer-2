package dev.geode.engine.audio

/**
 * What a reader got, stated rather than inferred from a count.
 *
 * `PcmRingBuffer.copyNewSince` returns an Int and clamps twice on the way -
 * once to the caller's buffer, once to keep clear of the write head - so
 * "your buffer was full" and "you fell behind and audio is gone" arrive as
 * the same value. MASTER_PLAN §5.1 requires the difference to be visible,
 * because only one of them means the feature timeline now has a hole in it.
 *
 * These allocate. That is deliberate and safe: reads happen on the analysis
 * thread, and it is the WRITE path that §5.1 holds to zero allocation.
 */
sealed interface RingReadResult {
    /** [sampleCount] frames starting at absolute frame [firstSample], in [epoch]. */
    data class Ok(
        val firstSample: Long,
        val sampleCount: Int,
        val epoch: Int,
    ) : RingReadResult

    /** The reader fell behind: everything before [oldestAvailable] is overwritten. */
    data class Gap(
        val requested: Long,
        val oldestAvailable: Long,
    ) : RingReadResult

    /**
     * The cursor belongs to a numbering that ended - a seek, a source change,
     * a format change the ring could not absorb.
     *
     * Distinct from [Gap] because the samples are not merely old: they are
     * from a different timeline, and interpolating across the boundary would
     * produce features for audio that was never played.
     */
    data class Discontinuity(
        val cursorEpoch: Int,
        val currentEpoch: Int,
    ) : RingReadResult

    /** Nothing new since the cursor. Not an error. */
    data object NotYetAvailable : RingReadResult
}
