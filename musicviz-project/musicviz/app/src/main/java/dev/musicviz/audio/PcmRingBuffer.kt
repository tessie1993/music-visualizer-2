package dev.musicviz.audio

/**
 * Single-writer circular buffer of mono float samples.
 *
 * The audio thread writes; the analysis worker snapshots the most recent
 * window. No locks are taken on the write path so the AudioProcessor is
 * never blocked; benign tearing at the write head is acceptable for
 * visualization purposes.
 */
class PcmRingBuffer(capacity: Int = 1 shl 16) {
    private val data: FloatArray = FloatArray(capacity)
    private val mask: Long = (capacity - 1).toLong()

    @Volatile
    private var writeIndex: Long = 0

    init {
        require(capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    /** Called from the audio thread. Downmixes interleaved frames to mono. */
    fun writeInterleaved(
        samples: FloatArray,
        frameCount: Int,
        channelCount: Int,
    ) {
        var w = writeIndex
        var s = 0
        repeat(frameCount) {
            var acc = 0f
            repeat(channelCount) {
                acc += samples[s]
                s++
            }
            data[(w and mask).toInt()] = acc / channelCount
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
    fun snapshotLatest(out: FloatArray): Boolean {
        val w = writeIndex
        if (w < out.size) return false
        var r = w - out.size
        for (i in out.indices) {
            out[i] = data[(r and mask).toInt()]
            r++
        }
        return true
    }
}
