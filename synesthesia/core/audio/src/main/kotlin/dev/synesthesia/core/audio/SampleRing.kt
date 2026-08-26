package dev.synesthesia.core.audio

/**
 * Single-writer lock-free PCM ring (blueprint decision #1).
 * Producer: audio/tap thread via write() - never blocks, drop-oldest on overflow.
 * Consumer: any thread via snapshotLatest() - true seqlock (version validated
 * BEFORE and AFTER the copy; retry on torn read). Fields are @Volatile: JMM
 * gives no atomicity/visibility guarantees for plain Long across threads.
 * Epochs: beginEpoch() invalidates in-flight snapshots at source switches
 * (route changes, format changes); snapshots carry the epoch (clock-domain law).
 */
class SampleRing(
    private val capacityFrames: Int,
    private val channels: Int = 1,
) {
    init {
        require(capacityFrames > 0 && channels > 0)
    }

    private val data = FloatArray(capacityFrames * channels)
    @Volatile private var writePos = 0L // ABSOLUTE stream position incl. dropped frames
    @Volatile private var version = 0L // bumped per write; odd = mid-write
    @Volatile private var epoch = 0L

    val channelCount: Int get() = channels
    val writtenFrames: Long get() = writePos

    /** Absolute position of the OLDEST retained frame (drop-oldest aware).
     *  Frames below this have been dropped; readers detect gaps against it. */
    val oldestAvailable: Long get() = writePos - minOf(writePos, capacityFrames.toLong())

    val totalFramesWritten: Long get() = writePos
    val currentEpoch: Long get() = epoch

    /** Producer only. Drops oldest frames when pcm exceeds capacity. */
    fun write(pcm: FloatArray, frames: Int) {
        require(pcm.size >= frames * channels)
        version++ // odd: write in progress
        // Drop-oldest: when over capacity, skip the oldest head and wrap-write
        // the retained tail NORMALLY so data[p % cap] == frame p still holds.
        val skipFrames = if (frames > capacityFrames) frames - capacityFrames else 0
        val count = frames - skipFrames
        var pos = ((writePos + skipFrames) % capacityFrames).toInt()
        var offsetInSrc = skipFrames * channels
        var remaining = count
        while (remaining > 0) {
            val chunk = minOf(remaining, capacityFrames - pos)
            System.arraycopy(pcm, offsetInSrc, data, pos * channels, chunk * channels)
            pos = (pos + chunk) % capacityFrames
            offsetInSrc += chunk * channels
            remaining -= chunk
        }
        writePos += frames // absolute position advances even when frames were dropped
        version++ // even: stable again
    }

    /**
     * Consistent snapshot of the newest [maxFrames] frames (older if fewer written).
     * Seqlock discipline: validate version before AND after copying payload;
     * retry on torn read. Returns null when nothing is available yet.
     */
    fun snapshotLatest(maxFrames: Int, into: FloatArray? = null): Snapshot? {
        require(maxFrames > 0)
        while (true) {
            val v1 = version
            if (v1 % 2L == 1L) continue // producer mid-write: spin
            val w = writePos
            val e = epoch
            val available = minOf(w, maxFrames.toLong(), capacityFrames.toLong()).toInt()
            if (available == 0) return null
            val out = into ?: FloatArray(available * channels)
            require(out.size >= available * channels)
            val endPos = (w % capacityFrames).toInt()
            val start = endPos - available
            for (i in 0 until available) {
                val src = ((start + i).mod(capacityFrames)) * channels
                System.arraycopy(data, src, out, i * channels, channels)
            }
            val v2 = version
            if (v1 == v2 && v2 % 2L == 0L) {
                return Snapshot(out, available, channels, e, w)
            }
            // torn by concurrent write: discard copy, retry
        }
    }

    /** Atomic w.r.t. readers (AAA review B1): wrapped in the seqlock so no
     *  snapshot can straddle the switch carrying pre-switch samples labeled
     *  with the new epoch. */
    fun beginEpoch(): Long {
        version++
        val e = ++epoch
        version++
        return e
    }

    /**
     * Sequential planar read of ABSOLUTE frames [firstFrame, firstFrame+frameCount)
     * into per-channel arrays. Seqlock-guarded: retries until the copy is stable.
     * Caller re-checks [oldestAvailable] afterwards to detect mid-read drops
     * (RingReadResult.Gap). Requires firstFrame >= oldestAvailable at call time.
     */
    fun copyInto(firstFrame: Long, frameCount: Int, out: Array<FloatArray>) {
        require(frameCount > 0)
        require(out.size == channels) {
            "out has ${out.size} channels, ring has $channels"
        }
        val minSize = frameCount * channels
        out.forEach { require(it.size * channels >= minSize) { "channel buffer too small" } }
        while (true) {
            val v1 = version
            if (v1 % 2L == 1L) continue // producer mid-write: spin
            for (i in 0 until frameCount) {
                val src = ((firstFrame + i).mod(capacityFrames)) * channels
                for (c in 0 until channels) {
                    out[c][i] = data[src + c]
                }
            }
            val v2 = version
            if (v1 == v2 && v2 % 2L == 0L) return
            // torn by concurrent write: discard, retry
        }
    }

    /**
     * Planar snapshot of the newest exactly-[out[0].size] frames across ALL
     * channels. Returns false until the full window is available (never
     * partially fills - callers keep their previous view intact).
     */
    fun snapshotLatest(out: Array<FloatArray>): Boolean {
        val windowFrames = out[0].size
        require(windowFrames > 0)
        require(out.size == channels) {
            "out has ${out.size} channels, ring has $channels"
        }
        out.forEach { require(it.size == windowFrames) { "planar buffers must match" } }
        while (true) {
            val v1 = version
            if (v1 % 2L == 1L) continue // producer mid-write: spin
            val w = writePos
            if (w < windowFrames) return false // wait for the full window
            val endPos = (w % capacityFrames).toInt()
            val start = endPos - windowFrames
            for (i in 0 until windowFrames) {
                val src = ((start + i).mod(capacityFrames)) * channels
                for (c in 0 until channels) {
                    out[c][i] = data[src + c]
                }
            }
            val v2 = version
            if (v1 == v2 && v2 % 2L == 0L) return true
            // torn by concurrent write: discard copy, retry
        }
    }

    data class Snapshot(
        val pcm: FloatArray,
        val frames: Int,
        val channels: Int,
        val epoch: Long,
        val upToFrame: Long,
    )
}
