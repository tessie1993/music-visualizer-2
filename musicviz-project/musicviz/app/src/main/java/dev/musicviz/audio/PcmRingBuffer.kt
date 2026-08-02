package dev.musicviz.audio

/**
 * Single-writer circular buffer of mid/side float samples.
 *
 * The audio thread writes; the analysis worker snapshots the most recent
 * window. No locks are taken on the write path so the AudioProcessor is
 * never blocked; benign tearing at the write head is acceptable for
 * visualization purposes.
 *
 * ## Why there are two channels here
 *
 * This used to keep the mono downmix alone, which meant the stereo image was
 * destroyed at the very first stage of the pipeline: from here onwards the
 * FFT, the bands, the flux, the tempo and every scene saw one signal, and a
 * wide, carefully-placed mix drove the visuals exactly as its mono fold would.
 *
 * The fix is mid/side rather than left/right because mid IS the existing mono
 * downmix - byte for byte, so nothing downstream changes - and side is the
 * part that was being thrown away. Everything a visual wants (width,
 * correlation, which side a sound sits on) is recoverable from the pair, and
 * L = mid + side / R = mid - side reconstructs the originals exactly.
 *
 * The cost is one more float array of [capacity] - 256 KB at the default,
 * which is the price of the whole feature.
 */
class PcmRingBuffer(
    capacity: Int = 1 shl 16,
) {
    private val data: FloatArray = FloatArray(capacity)

    /**
     * (L - R) / 2, aligned index-for-index with [data]. Zero for every mono
     * source, which is the correct reading rather than a missing one: a mono
     * signal genuinely has no side content.
     */
    private val sideData: FloatArray = FloatArray(capacity)
    private val mask: Long = (capacity - 1).toLong()

    @Volatile
    private var writeIndex: Long = 0

    init {
        require(capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    /**
     * Called from the audio thread. Splits interleaved frames into mid (the
     * mono downmix, unchanged) and side.
     *
     * Side is taken from the FIRST TWO channels only. For stereo that is the
     * definition; for a surround source those two are the front pair, which is
     * where the stereo image a listener perceives actually lives, and folding
     * the surrounds into it would report width that no two-speaker playback
     * will produce. For mono it is zero.
     */
    fun writeInterleaved(
        samples: FloatArray,
        frameCount: Int,
        channelCount: Int,
    ) {
        var w = writeIndex
        var s = 0
        val stereo = channelCount >= 2
        repeat(frameCount) {
            var acc = 0f
            val base = s
            repeat(channelCount) {
                acc += samples[s]
                s++
            }
            val slot = (w and mask).toInt()
            data[slot] = acc / channelCount
            sideData[slot] = if (stereo) (samples[base] - samples[base + 1]) * 0.5f else 0f
            w++
        }
        writeIndex = w
    }

    /** Monotonic count of samples written so far; use with [copyNewSince]. */
    fun currentWriteIndex(): Long = writeIndex

    /**
     * Write index at the end of the window copied by the last [copyNewSince]
     * call. Pass this as the next `fromIndex` so no samples are skipped
     * (reading currentWriteIndex() afterwards races new writes and drops the
     * gap). Single-reader only.
     */
    var lastCopyEndIndex: Long = 0L
        private set

    /**
     * Copies samples written since [fromIndex] into [out] (newest window if
     * more arrived than fits). Returns the number of samples copied.
     */
    fun copyNewSince(
        fromIndex: Long,
        out: FloatArray,
    ): Int {
        val w = writeIndex
        lastCopyEndIndex = w
        var available = w - fromIndex
        if (available <= 0L) return 0
        if (available > out.size) available = out.size.toLong()
        if (available > data.size) available = data.size.toLong()
        val start = w - available
        for (i in 0 until available.toInt()) {
            out[i] = data[((start + i) and mask).toInt()]
        }
        return available.toInt()
    }

    /** Copies the most recent [out].size samples into [out]. Returns false if not enough data yet. */
    fun snapshotLatest(out: FloatArray): Boolean = snapshotFrom(data, out)

    /**
     * The side channel over the same window [snapshotLatest] would return.
     *
     * Call it immediately after [snapshotLatest] with an array of the same
     * size. The two are not atomic with respect to each other - the write head
     * may advance between them - which is the same benign tearing the mono
     * path has always accepted, and at a 2048-sample window a few samples of
     * skew changes no statistic computed over it.
     */
    fun snapshotLatestSide(out: FloatArray): Boolean = snapshotFrom(sideData, out)

    private fun snapshotFrom(
        src: FloatArray,
        out: FloatArray,
    ): Boolean {
        val w = writeIndex
        if (w < out.size) return false
        var r = w - out.size
        for (i in out.indices) {
            out[i] = src[(r and mask).toInt()]
            r++
        }
        return true
    }
}
