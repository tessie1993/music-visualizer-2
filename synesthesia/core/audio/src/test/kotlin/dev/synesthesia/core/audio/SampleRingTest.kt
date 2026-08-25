package dev.synesthesia.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SampleRingTest {

    @Test
    fun `snapshot returns newest frames with wraparound`() {
        val ring = SampleRing(capacityFrames = 4, channels = 1)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6)
        val s = ring.snapshotLatest(3)!!
        assertEquals(listOf(4f, 5f, 6f), s.pcm.take(3).toList())
        assertEquals(6L, s.upToFrame)
    }

    @Test
    fun `snapshot respects maxFrames window`() {
        val ring = SampleRing(capacityFrames = 8, channels = 1)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f), 4)
        val s = ring.snapshotLatest(2)!!
        assertEquals(listOf(3f, 4f), s.pcm.take(2).toList())
    }

    @Test
    fun `empty ring yields null`() {
        assertNull(SampleRing(4).snapshotLatest(2))
    }

    @Test
    fun `epoch bump marks new snapshots`() {
        val ring = SampleRing(4)
        ring.write(floatArrayOf(1f, 2f), 2)
        val before = ring.snapshotLatest(2)!!.epoch
        ring.beginEpoch()
        ring.write(floatArrayOf(3f, 4f), 2)
        val after = ring.snapshotLatest(2)!!
        assertEquals(before + 1, after.epoch)
    }

    @Test
    fun `stereo interleaving preserved across wrap`() {
        val ring = SampleRing(capacityFrames = 3, channels = 2)
        ring.write(floatArrayOf(1f, 11f, 2f, 12f, 3f, 13f, 4f, 14f, 5f, 15f, 6f, 16f), 6)
        val s = assertNotNull(ring.snapshotLatest(3))
        assertEquals(listOf(4f, 14f, 5f, 15f, 6f, 16f), s.pcm.toList())
    }
}
