package dev.musicviz.engine.audio

/**
 * Single-writer store of planar PCM addressed by absolute frame index.
 *
 * Holds samples and nothing else: no windowing, no cursor, no mid/side. A
 * reader's position lives in [RingReader], so two of them cannot corrupt each
 * other the way one shared `lastCopyEndIndex` does today.
 *
 * Planar rather than mid/side because §5.1 asks for stereo preserved *to* the
 * analysis boundary; which pair of axes a feature wants is the feature's
 * business.
 *
 * Frames, not interleaved samples. `sampleIndex` here counts frames - one per
 * channel - because at a given rate that is what maps to time, and a store
 * that means "sample" two ways is a bug waiting for a multichannel source.
 */
class SampleRing(
    val capacityFrames: Int,
    val channelCount: Int,
    val maxWriteFrames: Int = capacityFrames / DEFAULT_RUNWAY_DIVISOR,
) : PcmSink {
    init {
        require(capacityFrames > 0 && capacityFrames and (capacityFrames - 1) == 0) {
            "capacityFrames must be a power of two, was $capacityFrames"
        }
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(maxWriteFrames in 1..capacityFrames) {
            "maxWriteFrames must be between 1 and $capacityFrames, was $maxWriteFrames"
        }
    }

    private val channels: Array<FloatArray> = Array(channelCount) { FloatArray(capacityFrames) }
    private val mask: Long = (capacityFrames - 1).toLong()

    @Volatile
    private var written: Long = 0

    @Volatile
    private var epochValue: Int = 0

    /** Frames written since the current epoch began. */
    val writtenFrames: Long get() = written

    /** Increments whenever sample numbering restarts. */
    val epoch: Int get() = epochValue

    /**
     * Oldest frame a reader can still trust.
     *
     * Not `written - capacityFrames`, and the difference is the bug that
     * version had. [written] is published *after* the slot stores of a write,
     * so between the two a reader sees a frame count that understates how far
     * the writer has already reached into the ring - by up to [maxWriteFrames].
     * A reader whose window starts inside that span reads slots the writer is
     * overwriting and gets audio that passes every check: the frame count says
     * it is intact, and the samples are real samples, from a lap later.
     *
     * Found by `SampleRingConcurrencyTest`, which returned frame 46400 where it
     * asked for 45376 - exactly one lap of a 1024-frame ring. Costing the
     * writer's runway is what makes the answer sound rather than usually right.
     */
    val oldestAvailable: Long get() = maxOf(0L, written + maxWriteFrames - capacityFrames)

    /**
     * Ends the current numbering and starts a new one at frame 0.
     *
     * Called for seek, source change and any format change the ring cannot
     * absorb. Not called from the audio callback: §5.1 puts reformat and
     * resize off that thread, and this only touches two fields precisely so a
     * caller that must do it there is not forced to allocate.
     */
    fun beginEpoch() {
        written = 0
        epochValue += 1
    }

    /**
     * Writes [frameCount] interleaved frames. Audio-thread path: no
     * allocation, no lock, no branch on reader state.
     *
     * Extra channels beyond [channelCount] are dropped rather than folded in.
     * For a surround source the first two are the front pair, which is where
     * the image a listener perceives lives; folding the surrounds in would
     * report width no two-speaker playback produces.
     */
    override fun write(
        interleaved: FloatArray,
        frameCount: Int,
        sourceChannelCount: Int,
    ) {
        require(sourceChannelCount > 0) { "sourceChannelCount must be positive" }
        require(frameCount.toLong() * sourceChannelCount <= interleaved.size) {
            "$frameCount frames x $sourceChannelCount channels exceeds ${interleaved.size}"
        }
        // The bound readers reserve as the writer's runway. A write longer than
        // this reaches past what any reader believes is safe, which is the one
        // way [oldestAvailable] can be wrong.
        require(frameCount <= maxWriteFrames) {
            "$frameCount frames exceeds maxWriteFrames of $maxWriteFrames"
        }
        var w = written
        var read = 0
        repeat(frameCount) {
            val slot = (w and mask).toInt()
            for (c in 0 until channelCount) {
                channels[c][slot] = if (c < sourceChannelCount) interleaved[read + c] else 0f
            }
            read += sourceChannelCount
            w++
        }
        written = w
    }

    /** Copies [count] frames from [firstSample] into [out], one array per channel. */
    companion object {
        /**
         * A quarter of the ring is the writer's runway by default: enough for
         * any decoder buffer at the capacities this is used at, and the same
         * proportional headroom `PcmRingBuffer` reserved for the same reason.
         */
        const val DEFAULT_RUNWAY_DIVISOR = 4
    }

    internal fun copyInto(
        firstSample: Long,
        count: Int,
        out: Array<FloatArray>,
    ) {
        for (c in out.indices.take(channelCount)) {
            val src = channels[c]
            val dst = out[c]
            var r = firstSample
            for (i in 0 until count) {
                dst[i] = src[(r and mask).toInt()]
                r++
            }
        }
    }
}
