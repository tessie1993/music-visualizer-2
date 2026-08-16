package dev.musicviz.engine.audio

/**
 * The legacy mid/side view of a [SampleRing]'s newest window.
 *
 * `PcmRingBuffer` derives mid and side at *capture* time and stores them.
 * MASTER_PLAN §5.1 puts that the other way round — stereo is preserved to the
 * analysis boundary and downmixing happens per feature — so the ring keeps
 * planar channels and this derives the pair on read. Same numbers, computed
 * one stage later, which is what lets the analyzer move without its features
 * changing.
 *
 * Buffers are allocated once. [refresh] runs on the analysis worker every hop
 * and must not add garbage to a 62 Hz loop.
 *
 * ## Parity, exactly
 *
 * `mid` is bit-identical to `PcmRingBuffer`'s mono downmix for **mono and
 * stereo** sources: the same sum in the same order divided by the same count.
 * For more than two source channels it is not, and cannot be — the ring keeps
 * the front pair only, by the design §5.1 asks for, so the surrounds are gone
 * before this sees them. See `adr/0003`.
 */
class MidSideWindow(
    private val ring: SampleRing,
    windowFrames: Int,
) {
    init {
        require(windowFrames > 0) { "windowFrames must be positive, was $windowFrames" }
        require(ring.channelCount >= 2) { "a mid/side view needs two ring channels" }
    }

    private val planar = Array(ring.channelCount) { FloatArray(windowFrames) }

    /** The mono downmix, valid after a [refresh] that returned true. */
    val mid: FloatArray = FloatArray(windowFrames)

    /** (L - R) / 2 over the same window; zero throughout for a mono source. */
    val side: FloatArray = FloatArray(windowFrames)

    /**
     * Re-reads the newest window and recomputes [mid] and [side]. False when
     * the ring holds fewer frames than the window, leaving both untouched.
     */
    fun refresh(): Boolean {
        if (!ring.snapshotLatest(planar)) return false
        val left = planar[0]
        val right = planar[1]
        // The source's channel count, not the ring's. A mono source leaves
        // channel 1 silent, and averaging that in would halve every sample -
        // silently, since the shape of the output is identical.
        val sources = ring.sourceChannelCount
        if (sources >= 2) {
            for (i in mid.indices) {
                mid[i] = (left[i] + right[i]) / 2f
                side[i] = (left[i] - right[i]) * 0.5f
            }
        } else {
            left.copyInto(mid)
            side.fill(0f)
        }
        return true
    }
}
