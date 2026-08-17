package dev.geode.engine.audio

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

    @Volatile
    private var sourceChannels: Int = 0

    /**
     * Channels the source actually had, which is not [channelCount].
     *
     * A mono source fills channel 0 and leaves channel 1 silent, so a reader
     * deriving a mono downmix as `(ch0 + ch1) / 2` would halve it. The count is
     * constant within an epoch - a format change ends the numbering - so the
     * last write's value describes the whole span. Zero before the first write.
     */
    val sourceChannelCount: Int get() = sourceChannels

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
        sourceChannels = sourceChannelCount
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

    /**
     * Copies the newest `out[0].size` frames into [out], planar. False when
     * fewer than that many frames exist, or when the window is wider than the
     * ring - a window past the write head would wrap and come back scrambled,
     * and there is no coherent answer to give.
     *
     * The legacy shape, kept for the bridge that serves today's analyzer. It
     * carries the tearing the old buffer always accepted: the writer may
     * advance mid-copy, which at a 2,048-frame window changes no statistic
     * computed over it. Readers that need to know what they missed use
     * [RingReader] instead - this one cannot tell.
     */
    fun snapshotLatest(out: Array<FloatArray>): Boolean {
        val frames = out.minOf { it.size }
        if (frames > capacityFrames) return false
        val w = written
        if (w < frames) return false
        for (c in out.indices.take(channelCount)) {
            val src = channels[c]
            val dst = out[c]
            var r = w - frames
            for (i in 0 until frames) {
                dst[i] = src[(r and mask).toInt()]
                r++
            }
        }
        return true
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
