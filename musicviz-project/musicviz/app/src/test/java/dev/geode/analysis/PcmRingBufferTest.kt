package dev.geode.analysis

import dev.geode.audio.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun `snapshot fails until enough data written`() {
        val ring = PcmRingBuffer(capacity = 1024)
        val out = FloatArray(256)
        assertFalse(ring.snapshotLatest(out))
        ring.writeInterleaved(FloatArray(512), frameCount = 256, channelCount = 2)
        assertTrue(ring.snapshotLatest(out))
    }

    @Test
    fun `stereo frames are downmixed to mono`() {
        val ring = PcmRingBuffer(capacity = 1024)
        val interleaved = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f, 0.25f, 0.75f)
        ring.writeInterleaved(interleaved, frameCount = 4, channelCount = 2)
        val out = FloatArray(4)
        assertTrue(ring.snapshotLatest(out))
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(0.5f, out[3], 1e-6f)
    }

    @Test
    fun `snapshot returns most recent window after wraparound`() {
        val ring = PcmRingBuffer(capacity = 256)
        repeat(10) { round ->
            val chunk = FloatArray(100) { round.toFloat() }
            ring.writeInterleaved(chunk, frameCount = 100, channelCount = 1)
        }
        val out = FloatArray(50)
        assertTrue(ring.snapshotLatest(out))
        for (v in out) assertEquals(9f, v, 1e-6f)
    }

    /**
     * A reader a whole lap behind must not be handed the full ring: the
     * oldest samples of that window sit AT the write head, where the audio
     * thread overwrites them while the copy runs - a stale-vs-fresh seam,
     * not the benign single-sample tearing the class signs up for. A quarter
     * of the capacity stays clear as the writer's runway, and what the
     * lagging reader gets is the NEWEST window of the reduced size.
     */
    @Test
    fun `a lagging reader is kept clear of the write head`() {
        val capacity = 256
        val ring = PcmRingBuffer(capacity = capacity)
        var value = 0f
        repeat(10) {
            val chunk = FloatArray(100) { value++ }
            ring.writeInterleaved(chunk, frameCount = 100, channelCount = 1)
        }
        // 1000 samples written, the reader still at 0, an out array as large
        // as the whole ring: the pre-clamp answer was all 256 slots.
        val out = FloatArray(capacity)
        val n = ring.copyNewSince(0L, out)
        assertEquals(capacity - capacity / 4, n)
        for (i in 0 until n) assertEquals("sample $i", (1000 - n + i).toFloat(), out[i], 0f)
    }
}
