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
