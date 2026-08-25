package dev.synesthesia.core.audio

/**
 * Single-writer lock-free PCM ring (blueprint decision #1).
 * Producer: audio/tap thread via write() — never blocks, drop-oldest on overflow.
 * Consumer: any thread via snapshotLatest() — seqlock-style versioned read.
 * Epochs: beginEpoch() invalidates in-flight snapshots at source switches;
 * snapshots carry the epoch so stale data is detectable (AAA clock-domain law).
 */
class SampleRing(
    private val capacityFrames: Int,
    private val channels: Int = 1,
) {
    init {
        require(capacityFrames > 0 && channels > 0)
    }

    private val data = FloatArray(capacityFrames * channels)
    private var writePos = 0L // total frames ever written
    private var version = 0L // bumped per write; odd = mid-write
    private var epoch = 0L

    val totalFramesWritten: Long get() = writePos
    val currentEpoch: Long get() = epoch

    /** Producer only. Drops oldest frames when pcm exceeds capacity. */
    fun write(pcm: FloatArray, frames: Int) {
        require(pcm.size >= frames * channels)
        version++ // odd
        val usable = minOf(frames, capacityFrames)
        if (frames > capacityFrames) {
            // keep the newest tail
            val skip = (frames - capacityFrames) * channels
            System.arraycopy(pcm, skip, data, 0, usable * channels)
        } else {
            val pos = (writePos % capacityFrames).toInt()
            val first = minOf(usable, capacityFrames - pos.toInt())
            System.arraycopy(pcm, 0, data, pos.toInt() * channels, first * channels)
            System.arraycopy(pcm, first * channels, data, 0, (usable - first) * channels)
        }
        writePos += usable
        version++ // even: stable again
    }

    /**
     * Consistent snapshot of the newest [maxFrames] frames (older if fewer written).
     * Returns null when no complete frame is available or epoch changed mid-read.
     */
    fun snapshotLatest(maxFrames: Int, into: FloatArray? = null): Snapshot? {
        var v: Long
        var w: Long
        var e: Long
        do {
            v = version
            if (v % 2L == 1L) continue // producer mid-write: retry
            w = writePos
            e = epoch
        } while (v != version)
        val available = minOf(w.toInt(), maxFrames, capacityFrames)
        if (available == 0) return null
        val out = into ?: FloatArray(available * channels)
        require(out.size >= available * channels)
        val endPos = (w % capacityFrames).toInt()
        val start = endPos - available
        for (i in 0 until available) {
            val src = ((start + i).mod(capacityFrames)) * channels
            System.arraycopy(data, src, out, i * channels, channels)
        }
        return Snapshot(out, available, channels, e, w)
    }

    fun beginEpoch(): Long = ++epoch

    data class Snapshot(
        val pcm: FloatArray,
        val frames: Int,
        val channels: Int,
        val epoch: Long,
        val upToFrame: Long,
    )
}
