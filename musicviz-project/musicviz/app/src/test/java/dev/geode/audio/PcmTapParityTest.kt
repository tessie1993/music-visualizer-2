package dev.geode.audio

import androidx.media3.common.C
import dev.geode.engine.audioandroid.PcmTap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * The new tap against the one it replaces, on the same audio.
 *
 * MASTER_PLAN §12 lists the PCM tap as "KEEP semantic order; MOVE
 * implementation", with "waveform fixtures" as the migration proof, and the
 * V2-2-03 slice as "preserve exact captured sample order and current
 * user-facing semantics". Exact is the word that matters: the tap is upstream
 * of every band, beat and scene, so a rounding difference is not a rounding
 * difference by the time it reaches a user - it is a different visual.
 *
 * So this compares the ring contents bit for bit rather than within a
 * tolerance. [PcmTapSink] stays in the tree for exactly one slice to be this
 * oracle; §2.1 rule 7 forbids deleting it in the slice that replaces it, and
 * its removal is V2-2-03b.
 */
class PcmTapParityTest {
    private fun buffer(
        bytes: Int,
        fill: ByteBuffer.() -> Unit,
    ): ByteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).apply(fill).apply { flip() }

    /** A stereo sweep: full-scale extremes, a phase-offset pair and a lot of ordinary values. */
    private fun sweep16(frames: Int): ByteBuffer =
        buffer(frames * 2 * Short.SIZE_BYTES) {
            for (i in 0 until frames) {
                putShort((sin(i * 0.01) * Short.MAX_VALUE).toInt().toShort())
                putShort((sin(i * 0.01 + 1.0) * Short.MIN_VALUE.toInt()).toInt().toShort())
            }
        }

    private fun sweepFloat(frames: Int): ByteBuffer =
        buffer(frames * 2 * Float.SIZE_BYTES) {
            for (i in 0 until frames) {
                putFloat(sin(i * 0.02).toFloat())
                putFloat(-sin(i * 0.02 + 0.5).toFloat())
            }
        }

    /** Mid and side over the newest [window] samples, the pair every consumer reads. */
    private fun readBack(
        ring: PcmRingBuffer,
        window: Int,
    ): Pair<FloatArray, FloatArray> {
        val mid = FloatArray(window)
        val side = FloatArray(window)
        assertTrue("the ring holds fewer than $window samples", ring.snapshotLatest(mid))
        assertTrue("the ring has no side channel", ring.snapshotLatestSide(side))
        return mid to side
    }

    private fun compare(
        channels: Int,
        encoding: Int,
        window: Int,
        pcm: () -> ByteBuffer,
    ) {
        val oldRing = PcmRingBuffer()
        val newRing = PcmRingBuffer()
        val old = PcmTapSink(oldRing) { _, _, _ -> }
        val new = PcmTap({ samples, frames, sourceChannels -> newRing.writeInterleaved(samples, frames, sourceChannels) })

        old.flush(48_000, channels, encoding)
        new.flush(48_000, channels, encoding)
        old.handleBuffer(pcm())
        new.handleBuffer(pcm())

        val (oldMid, oldSide) = readBack(oldRing, window)
        val (newMid, newSide) = readBack(newRing, window)
        assertArrayEquals("the mono downmix differs", oldMid, newMid, 0f)
        assertArrayEquals("the side channel differs", oldSide, newSide, 0f)
    }

    @Test
    fun `16-bit stereo is captured identically`() {
        compare(channels = 2, encoding = C.ENCODING_PCM_16BIT, window = 2048) { sweep16(4096) }
    }

    @Test
    fun `float stereo is captured identically`() {
        compare(channels = 2, encoding = C.ENCODING_PCM_FLOAT, window = 2048) { sweepFloat(4096) }
    }

    @Test
    fun `mono is captured identically`() {
        compare(channels = 1, encoding = C.ENCODING_PCM_16BIT, window = 1024) {
            buffer(2048 * Short.SIZE_BYTES) { repeat(2048) { putShort((it - 1024).toShort()) } }
        }
    }

    @Test
    fun `a buffer wider than the staging window is captured identically`() {
        // The one place the two implementations genuinely differ: the old tap
        // grew a scratch array to whatever arrived and wrote once; this one
        // writes fixed-size chunks. If the chunking dropped or reordered a
        // frame at a seam, this is where it shows.
        compare(channels = 2, encoding = C.ENCODING_PCM_16BIT, window = 8192) { sweep16(12_000) }
    }

    @Test
    fun `five-channel audio folds down identically`() {
        // The downmix is the mean over every source channel and the side
        // channel is the front pair, and both live below the tap - so a
        // surround source is the case most likely to expose a stride mistake.
        compare(channels = 5, encoding = C.ENCODING_PCM_16BIT, window = 1024) {
            buffer(2048 * 5 * Short.SIZE_BYTES) {
                repeat(2048 * 5) { putShort((it % 4096 - 2048).toShort()) }
            }
        }
    }
}
