package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time-addressed feature ring: continuous values interpolated at a
 * sample, events OR/peak-held over a span, and the explicit outcomes the
 * §5.1 contract requires instead of silent clamps.
 */
class FeatureRingTest {
    private fun ring(capacity: Int = 64) = FeatureRing(continuousSlots = 2, eventSlots = 2, capacityFrames = capacity)

    private fun frame() = FeatureFrame(continuousSlots = 2, eventSlots = 2)

    /** Publishes frames at samples 0, 100, 200, ... with slot0 = index. */
    private fun publishRamp(
        ring: FeatureRing,
        frames: Int,
        eventAt: Int = -1,
    ) {
        val continuous = FloatArray(2)
        val events = FloatArray(2)
        repeat(frames) { i ->
            continuous[0] = i.toFloat()
            continuous[1] = 1f - i / frames.toFloat()
            events[0] = if (i == eventAt) 1f else 0f
            events[1] = if (i == eventAt) 0.7f else 0f
            ring.publish(i * 100L, continuous, events)
        }
    }

    @Test
    fun `continuous slots interpolate linearly between frames`() {
        val ring = ring()
        publishRamp(ring, 10)
        val out = frame()
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(250L, 0L, out))
        assertEquals(2.5f, out.continuous[0], 1e-5f)
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(300L, 0L, out))
        assertEquals(3f, out.continuous[0], 1e-5f)
    }

    @Test
    fun `an event inside the span is seen and one outside is not`() {
        val ring = ring()
        publishRamp(ring, 10, eventAt = 4)
        val out = frame()
        // Base at sample 200, span to 500: frames 2..4 - the event at 400 is in.
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(200L, 300L, out))
        assertEquals(1f, out.events[0], 0f)
        assertEquals(0.7f, out.events[1], 0f)
        // Span that ends before it: not seen.
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(200L, 100L, out))
        assertEquals(0f, out.events[0], 0f)
    }

    @Test
    fun `asking ahead of the newest frame is not yet available`() {
        val ring = ring()
        publishRamp(ring, 5)
        assertEquals(FeatureRing.Acquire.NOT_YET_AVAILABLE, ring.acquireAt(1000L, 0L, frame()))
    }

    @Test
    fun `a reader fallen off the back gets a gap, not stale numbers`() {
        val ring = ring(capacity = 8)
        publishRamp(ring, 64)
        assertEquals(FeatureRing.Acquire.GAP, ring.acquireAt(100L, 0L, frame()))
    }

    @Test
    fun `an empty ring says so`() {
        assertEquals(FeatureRing.Acquire.EMPTY, ring().acquireAt(0L, 0L, frame()))
    }

    @Test
    fun `an epoch change invalidates old addressing`() {
        val ring = ring()
        publishRamp(ring, 10)
        val epochBefore = ring.epoch
        ring.beginEpoch()
        assertTrue(ring.epoch != epochBefore)
        assertEquals(FeatureRing.Acquire.EMPTY, ring.acquireAt(500L, 0L, frame()))
        publishRamp(ring, 3)
        val out = frame()
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(100L, 0L, out))
        assertEquals(ring.epoch, out.epoch)
    }

    @Test
    fun `wrapping the ring keeps the newest frames addressable`() {
        val ring = ring(capacity = 8)
        publishRamp(ring, 64)
        val out = frame()
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(6250L, 0L, out))
        assertEquals(62.5f, out.continuous[0], 1e-4f)
    }

    @Test
    fun `acquisition allocates nothing`() {
        val ring = ring()
        publishRamp(ring, 32)
        val out = frame()
        val perRun = JvmAllocationMeter.perRun(10_000) { ring.acquireAt(1500L, 400L, out) }
        assertTrue("acquire allocated $perRun bytes per call", perRun < 1.0)
    }
}
