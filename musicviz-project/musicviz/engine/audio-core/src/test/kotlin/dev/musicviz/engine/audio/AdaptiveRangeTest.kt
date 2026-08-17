package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AdaptiveRangeTest {
    private val hopRateHz = 86f

    private fun range(
        minimumSpan: Float = 1e-4f,
        warmupSeconds: Float = AdaptiveRange.WARMUP_SECONDS,
    ) = AdaptiveRange(hopRateHz, minimumSpan = minimumSpan, warmupSeconds = warmupSeconds)

    /** A stationary signal with a known shape, so the tracked bounds have a right answer. */
    private fun noise(
        seed: Long,
        count: Int,
        low: Float = 0f,
        high: Float = 1f,
    ): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { low + (high - low) * random.nextFloat() }
    }

    private fun AdaptiveRange.feed(samples: FloatArray): Float {
        var last = 0f
        for (sample in samples) last = normalize(sample, FrameActivity.Sounding)
        return last
    }

    @Test
    fun `the bounds converge on the material's quantiles, not its extremes`() {
        // The claim that makes this "robust" rather than "a min and a max
        // follower". On uniform material the 5th and 95th percentiles are 0.05
        // and 0.95; the extremes are 0 and 1, which is what a follower would
        // find and what would leave every frame compressed toward the middle.
        val adaptive = range()
        adaptive.feed(noise(seed = 42, count = 40_000))
        assertEquals("floor", 0.05f, adaptive.trackedFloor, 0.03f)
        assertEquals("ceiling", 0.95f, adaptive.trackedCeiling, 0.03f)
    }

    @Test
    fun `one frame of any size moves a bound by at most one step`() {
        // Bounded influence, measured. The glitch here is about 1600 times the
        // material's whole range; a max-follower would put the ceiling at
        // 1000 and leave every later frame reading essentially zero until it
        // released, which is a visible dropout with no cause on screen.
        val material = noise(seed = 7, count = 2_000, low = 0.2f, high = 0.8f)

        val quiet = range()
        quiet.feed(material)
        val settled = quiet.trackedCeiling
        val step = maxOf(quiet.trackedCeiling - quiet.trackedFloor, 1e-4f) * (1f / hopRateHz) / AdaptiveRange.ADAPT_SECONDS

        val glitched = range()
        glitched.feed(material)
        glitched.normalize(1_000f, FrameActivity.Sounding)

        val moved = glitched.trackedCeiling - settled
        assertTrue("the ceiling did not move at all, so nothing was proved", moved > 0f)
        assertEquals("moved $moved, one step is ${step * 0.95f}", step * 0.95f, moved, step * 0.02f)
    }

    @Test
    fun `and it recovers from that frame within a second`() {
        val material = noise(seed = 7, count = 2_000, low = 0.2f, high = 0.8f)
        val glitched = range()
        glitched.feed(material)
        val settled = glitched.trackedCeiling
        glitched.normalize(1_000f, FrameActivity.Sounding)
        glitched.feed(noise(seed = 8, count = hopRateHz.toInt(), low = 0.2f, high = 0.8f))
        assertEquals("the ceiling did not come back", settled, glitched.trackedCeiling, 0.02f)
    }

    @Test
    fun `a silent frame changes nothing at all`() {
        // §5.5's silence semantics, at the level of the state rather than the
        // output: not "silence contributes little" but "silence contributes
        // nothing", so a rest of any length leaves the scale exactly as the
        // music left it.
        val adaptive = range()
        adaptive.feed(noise(seed = 7, count = 2_000, low = 0.2f, high = 0.8f))
        val floor = adaptive.trackedFloor
        val ceiling = adaptive.trackedCeiling
        repeat(4_000) {
            assertEquals(0f, adaptive.normalize(0f, FrameActivity.Silent), 0f)
            assertSame(FeatureValidity.Silent, adaptive.validity)
        }
        assertEquals("the floor moved during silence", floor, adaptive.trackedFloor, 0f)
        assertEquals("the ceiling moved during silence", ceiling, adaptive.trackedCeiling, 0f)
    }

    @Test
    fun `so the first sound after a rest reads the same as the last sound before it`() {
        // What the state test above buys, in the units a viewer sees. A scale
        // that decayed through the rest would make this frame read full scale.
        val material = noise(seed = 7, count = 2_000, low = 0.2f, high = 0.8f)
        val probe = 0.5f

        val straight = range()
        straight.feed(material)
        val before = straight.normalize(probe, FrameActivity.Sounding)

        val rested = range()
        rested.feed(material)
        repeat(4_000) { rested.normalize(0f, FrameActivity.Silent) }
        val after = rested.normalize(probe, FrameActivity.Sounding)

        assertTrue("the probe sat at an end of the range, so this proved little", before > 0.2f && before < 0.8f)
        assertEquals(before, after, 1e-6f)
    }

    @Test
    fun `a quiet passage and a loud one both use the whole range`() {
        // The reason the mode exists: the same shape at two levels 40 dB apart
        // reads the same, which no fixed range can do.
        val loud = range()
        val quiet = range()
        val shape = noise(seed = 3, count = 20_000)
        for (sample in shape) {
            loud.normalize(sample, FrameActivity.Sounding)
            quiet.normalize(sample * 0.01f, FrameActivity.Sounding)
        }
        val probeLoud = loud.normalize(0.5f, FrameActivity.Sounding)
        val probeQuiet = quiet.normalize(0.005f, FrameActivity.Sounding)
        assertEquals(probeLoud, probeQuiet, 0.05f)
        assertTrue("the quiet pass collapsed to $probeQuiet", probeQuiet > 0.3f && probeQuiet < 0.7f)
    }

    @Test
    fun `warmup takes the extremes so nothing clips before the range is known`() {
        val adaptive = range(warmupSeconds = 1f)
        val warmupFrames = 86
        val ramp = FloatArray(warmupFrames) { 0.1f + 0.8f * it / (warmupFrames - 1f) }
        for ((i, sample) in ramp.withIndex()) {
            val out = adaptive.normalize(sample, FrameActivity.Sounding)
            val expected = if (i < warmupFrames - 1) FeatureValidity.Warmup else FeatureValidity.Valid
            assertSame("frame $i", expected, adaptive.validity)
            assertTrue("frame $i read $out", out in 0f..1f)
        }
        assertEquals(0.1f, adaptive.trackedFloor, 1e-6f)
        assertEquals(0.9f, adaptive.trackedCeiling, 1e-6f)
    }

    @Test
    fun `material with no dynamics rests near zero instead of flickering`() {
        // The degenerate case minimumSpan exists for. Both quantiles collapse
        // onto the one value the signal ever takes; without the guard the
        // division would be by something near zero and the output would swing
        // the full range every frame on a signal that never changed.
        val adaptive = range(minimumSpan = 1e-3f)
        repeat(5_000) { adaptive.normalize(0.42f, FrameActivity.Sounding) }
        var worst = 0f
        repeat(1_000) { worst = maxOf(worst, adaptive.normalize(0.42f, FrameActivity.Sounding)) }
        assertTrue("a dead-constant feature swung to $worst", worst < 0.05f)
    }

    @Test
    fun `the tracked bounds never cross, whatever the material does`() {
        // Found by an adversarial sweep, not by reading the code. A frame that
        // lands between the bounds draws them together by 2·step·tailFraction,
        // and minimumSpan floors the step — so once the material's spread is
        // under that floor the two estimates step past each other and a reader
        // of the public pair sees a floor above its own ceiling.
        val cases =
            listOf(
                FloatArray(4_000) { 0.42f },
                FloatArray(4_000) { if (it < 500) it / 500f else 0.5f },
                FloatArray(4_000) { 0.5f + 1e-7f * (it % 3) },
            )
        for ((case, material) in cases.withIndex()) {
            val adaptive = range(minimumSpan = 1e-2f)
            for (sample in material) {
                adaptive.normalize(sample, FrameActivity.Sounding)
                assertTrue(
                    "case $case: floor ${adaptive.trackedFloor} above ceiling ${adaptive.trackedCeiling}",
                    adaptive.trackedFloor <= adaptive.trackedCeiling,
                )
            }
        }
    }

    @Test
    fun `reset forgets the loud track before the quiet one starts`() {
        val adaptive = range()
        adaptive.feed(noise(seed = 5, count = 5_000, low = 0.5f, high = 1f))
        val loudFloor = adaptive.trackedFloor
        adaptive.reset()
        assertSame(FeatureValidity.Warmup, adaptive.validity)
        adaptive.feed(noise(seed = 6, count = 5_000, low = 0f, high = 0.01f))
        assertTrue("the floor stayed with the loud track at $loudFloor", adaptive.trackedFloor < 0.01f)
    }

    @Test
    fun `a malformed range is refused at construction`() {
        val bad =
            listOf(
                { AdaptiveRange(0f, 1e-4f) },
                { AdaptiveRange(hopRateHz, minimumSpan = 0f) },
                { AdaptiveRange(hopRateHz, minimumSpan = Float.NaN) },
                { AdaptiveRange(hopRateHz, 1e-4f, tailFraction = 0.5f) },
                { AdaptiveRange(hopRateHz, 1e-4f, tailFraction = 0f) },
                { AdaptiveRange(hopRateHz, 1e-4f, adaptSeconds = 0f) },
                { AdaptiveRange(hopRateHz, 1e-4f, warmupSeconds = -1f) },
            )
        for (make in bad) {
            try {
                make()
                throw AssertionError("a malformed range was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue("the message says nothing useful", expected.message!!.isNotEmpty())
            }
        }
    }
}
