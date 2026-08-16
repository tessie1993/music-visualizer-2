package dev.musicviz.engine.audio

/**
 * Where a branch's analysis windows sit on the input timeline, addressed by
 * their **centre** sample.
 *
 * This is the whole of MASTER_PLAN §5.3's alignment requirement, and it is one
 * line of arithmetic: frame *k* is centred at `k * hop`, and its window spans
 * half a window either side. Every branch therefore has a frame centred at
 * every multiple of its hop, and branches whose hops divide one another share
 * those centres exactly.
 *
 * ## Why not the right edge
 *
 * The obvious implementation stamps a frame with the last sample it saw, which
 * is what a streaming analyzer does naturally. Then frame *k* of a window *W*
 * ends at `k * hop + W`, so its centre is `k * hop + W / 2` — and that offset
 * is **different for every branch**. Running §5.3's stack that way puts the
 * 8192-sample branch 3,584 samples behind the 1024-sample one: 74.7 ms at
 * 48 kHz, which turns one kick into four apparent times spread across a beat.
 * `FrameGridTest` measures exactly that, so the number is evidence rather than
 * a quotation.
 *
 * Frames near the start have a negative [firstSample]; that is deliberate and
 * is the same convention librosa's `center=True` uses. A reader either pads or
 * waits for [firstCompleteFrame], and this type refuses to hide the choice.
 */
class FrameGrid(
    val branch: AnalysisBranch,
) {
    private val half = branch.windowFrames / 2

    /** The instant frame [index] describes. */
    fun centerSample(index: Long): Long = index * branch.hopFrames

    /** First sample of frame [index]'s window; negative for the first frames. */
    fun firstSample(index: Long): Long = centerSample(index) - half

    /** One past the last sample of frame [index]'s window. */
    fun endSample(index: Long): Long = firstSample(index) + branch.windowFrames

    /** The newest frame centred at or before [sample]. Negative before the first. */
    fun frameAtOrBefore(sample: Long): Long = Math.floorDiv(sample, branch.hopFrames.toLong())

    /** True when this branch has a window centred exactly on [sample]. */
    fun hasFrameCenteredAt(sample: Long): Boolean = sample >= 0 && sample % branch.hopFrames == 0L

    /** The first frame whose whole window lies at or after sample 0. */
    val firstCompleteFrame: Long
        get() = (half + branch.hopFrames - 1L) / branch.hopFrames

    /**
     * The newest frame whose entire window has been written, given
     * [writtenFrames] samples of input, or null when none has.
     */
    fun latestCompleteFrame(writtenFrames: Long): Long? {
        val candidate = Math.floorDiv(writtenFrames - half, branch.hopFrames.toLong())
        return if (candidate >= firstCompleteFrame) candidate else null
    }

    /** Centre of frame [index] as a time, for humans and for cross-rate checks. */
    fun centerMicros(
        index: Long,
        sampleRateHz: Int,
    ): Long {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        return centerSample(index) * MICROS_PER_SECOND / sampleRateHz
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
