package dev.geode.engine.audio

/**
 * An independent position in a [SampleRing].
 *
 * The reason this is a separate object: today's cursor is a field on the
 * buffer documented "single-reader only", so the analysis worker and anything
 * else that reads move the same pointer and each sees the other's progress as
 * its own. Every reader here owns its cursor, and one falling behind is that
 * reader's problem alone.
 */
class RingReader(
    private val ring: SampleRing,
) {
    /** Next frame this reader wants. */
    var nextSample: Long = 0
        private set

    private var cursorEpoch: Int = ring.epoch

    /** Restarts at the current epoch's beginning; use after a discontinuity. */
    fun rewindToEpoch() {
        cursorEpoch = ring.epoch
        nextSample = ring.oldestAvailable
    }

    /**
     * Reads up to `out[0].size` frames, advancing the cursor by what was read.
     *
     * Never clamps silently. A reader that has fallen off the back of the ring
     * gets [RingReadResult.Gap] naming what it missed, and its cursor stays
     * put so the caller decides whether to skip or to give up - the buffer
     * does not decide that on its behalf.
     */
    fun read(out: Array<FloatArray>): RingReadResult {
        val epoch = ring.epoch
        if (epoch != cursorEpoch) return RingReadResult.Discontinuity(cursorEpoch, epoch)

        val oldest = ring.oldestAvailable
        if (nextSample < oldest) return RingReadResult.Gap(nextSample, oldest)

        val available = ring.writtenFrames - nextSample
        if (available <= 0L) return RingReadResult.NotYetAvailable

        require(out.size == ring.channelCount) {
            "out has ${out.size} channels, ring has ${ring.channelCount}"
        }
        val count = minOf(available, out.minOf { it.size }.toLong()).toInt()
        val first = nextSample
        ring.copyInto(first, count, out)

        // The writer does not stop for this. A reader sitting at the tail can
        // have the frames it is copying overwritten underneath it, and the
        // copy would then look like ordinary audio. Re-checking afterwards
        // turns that into the Gap it actually is, at the cost of one wasted
        // copy on the rare occasion it happens.
        //
        // The re-check alone is NOT enough, and an earlier version of this
        // comment claimed it was: it argued the check beat reserving a
        // fraction of the ring. It does not, because the frame count it reads
        // is published after the slot stores it is meant to detect. Both are
        // needed - `oldestAvailable` reserves the writer's runway, and this
        // catches the writer advancing during the copy.
        val oldestNow = ring.oldestAvailable
        if (oldestNow > first) return RingReadResult.Gap(first, oldestNow)

        nextSample = first + count
        return RingReadResult.Ok(first, count, epoch)
    }

    /** Drops to the oldest readable frame after a [RingReadResult.Gap]. */
    fun skipToOldest() {
        nextSample = ring.oldestAvailable
    }
}
