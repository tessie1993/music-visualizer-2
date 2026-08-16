package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class CenteredRangeTest {
    private val hopRateHz = 86f

    private fun range(minimumDeviation: Float = 1e-4f) = CenteredRange(hopRateHz, minimumDeviation = minimumDeviation)

    private fun CenteredRange.feed(samples: FloatArray): Float {
        var last = 0f
        for (sample in samples) last = normalize(sample, FrameActivity.Sounding)
        return last
    }

    private fun gaussian(
        seed: Long,
        count: Int,
        mean: Float,
        sigma: Float,
    ): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { mean + sigma * random.nextGaussian().toFloat() }
    }

    @Test
    fun `the first frame has nothing to be above or below`() {
        assertEquals(0f, range().normalize(0.7f, FrameActivity.Sounding), 0f)
    }

    @Test
    fun `above the running average reads positive and below reads negative`() {
        val centered = range()
        centered.feed(gaussian(seed = 1, count = 5_000, mean = 0.5f, sigma = 0.1f))
        assertTrue("a loud frame did not read positive", centered.normalize(0.8f, FrameActivity.Sounding) > 0f)
        assertTrue("a quiet frame did not read negative", centered.normalize(0.2f, FrameActivity.Sounding) < 0f)
    }

    @Test
    fun `the tracked mean and deviation are the material's own`() {
        // The scale is the feature's spread, so it has a right answer: for
        // gaussian material the mean absolute deviation is sigma * sqrt(2/pi),
        // about 0.798 sigma. Anything else means the standardization is not
        // standardizing.
        val centered = range()
        centered.feed(gaussian(seed = 2, count = 60_000, mean = 0.4f, sigma = 0.2f))
        assertEquals("mean", 0.4f, centered.trackedMean, 0.03f)
        assertEquals("deviation", 0.798f * 0.2f, centered.trackedDeviation, 0.02f)
    }

    @Test
    fun `two deviations reach full scale, and most frames stay inside`() {
        // What DEVIATIONS buys, measured rather than asserted: two mean
        // absolute deviations is about 1.6 sigma, so roughly a ninth of
        // gaussian frames clip and the rest keep their shape.
        val centered = range()
        val material = gaussian(seed = 3, count = 60_000, mean = 0.4f, sigma = 0.2f)
        centered.feed(material)
        var clipped = 0
        val probe = gaussian(seed = 4, count = 20_000, mean = 0.4f, sigma = 0.2f)
        for (sample in probe) {
            if (abs(centered.normalize(sample, FrameActivity.Sounding)) >= 1f) clipped++
        }
        val fraction = clipped.toDouble() / probe.size
        assertTrue("$fraction of frames clipped, which is not 'the tails'", fraction > 0.05 && fraction < 0.20)
    }

    @Test
    fun `the error is measured against the average as it stood, not as it ends up`() {
        // One minimum deviation above a settled average reads exactly half of
        // full scale. Updating the mean before taking the error would shrink
        // it by the smoothing coefficient — invisible at the default three
        // seconds, which is why this uses a fast one and pins the number.
        val centered = CenteredRange(hopRateHz = 100f, minimumDeviation = 0.01f, adaptSeconds = 0.02f)
        repeat(2_000) { centered.normalize(0.5f, FrameActivity.Sounding) }
        assertEquals(0.5f, centered.normalize(0.51f, FrameActivity.Sounding), 1e-4f)
    }

    @Test
    fun `an extreme frame saturates rather than leaving the range`() {
        val centered = range()
        centered.feed(gaussian(seed = 5, count = 5_000, mean = 0.5f, sigma = 0.05f))
        assertEquals(1f, centered.normalize(1e6f, FrameActivity.Sounding), 0f)
        assertEquals(-1f, centered.normalize(-1e6f, FrameActivity.Sounding), 0f)
    }

    @Test
    fun `material with no spread reads exactly zero`() {
        // Nothing deviates from its own average, so there is nothing to
        // modulate with, and the honest answer is the centre.
        val centered = range()
        repeat(10_000) { centered.normalize(0.42f, FrameActivity.Sounding) }
        repeat(100) { assertEquals(0f, centered.normalize(0.42f, FrameActivity.Sounding), 1e-6f) }
    }

    @Test
    fun `a silent frame changes nothing at all`() {
        val centered = range()
        centered.feed(gaussian(seed = 6, count = 5_000, mean = 0.5f, sigma = 0.1f))
        val mean = centered.trackedMean
        val deviation = centered.trackedDeviation
        repeat(4_000) {
            assertEquals(0f, centered.normalize(0f, FrameActivity.Silent), 0f)
            assertSame(FeatureValidity.Silent, centered.validity)
        }
        assertEquals("the mean moved during silence", mean, centered.trackedMean, 0f)
        assertEquals("the deviation moved during silence", deviation, centered.trackedDeviation, 0f)
    }

    @Test
    fun `so a rest does not turn the next frame into an event`() {
        // The same reading before and after a rest. Had silence trained the
        // mean toward zero, this frame would come back saturated at +1 and
        // every modulation bound to it would fire on the downbeat after every
        // gap in the music.
        val material = gaussian(seed = 6, count = 5_000, mean = 0.5f, sigma = 0.1f)
        val straight = range()
        straight.feed(material)
        val before = straight.normalize(0.55f, FrameActivity.Sounding)

        val rested = range()
        rested.feed(material)
        repeat(4_000) { rested.normalize(0f, FrameActivity.Silent) }
        val after = rested.normalize(0.55f, FrameActivity.Sounding)

        assertTrue("the probe saturated anyway, so this proved little", abs(before) < 0.9f)
        assertEquals(before, after, 1e-6f)
    }

    @Test
    fun `the average follows the music rather than the whole session`() {
        // Centered is a comparison against "lately", not against the track:
        // after a sustained change of level the output returns toward zero,
        // which is what keeps a long loud section from pinning it at +1.
        val centered = range()
        centered.feed(gaussian(seed = 7, count = 5_000, mean = 0.2f, sigma = 0.02f))
        val onArrival = centered.normalize(0.8f, FrameActivity.Sounding)
        centered.feed(gaussian(seed = 8, count = 5_000, mean = 0.8f, sigma = 0.02f))
        assertEquals("the jump should have read full scale", 1f, onArrival, 0f)
        // Averaged, because single frames of noisy material still touch the
        // ends of the range once it has settled — that is the mode working,
        // not failing, and asserting on one frame would only be flaky.
        var total = 0f
        val probe = gaussian(seed = 9, count = 500, mean = 0.8f, sigma = 0.02f)
        for (sample in probe) total += abs(centered.normalize(sample, FrameActivity.Sounding))
        val average = total / probe.size
        assertTrue("it sits at $average instead of settling back around zero", average < 0.75f)
        assertEquals("the average never caught up", 0.8f, centered.trackedMean, 0.01f)
    }

    @Test
    fun `reset forgets the previous track's average`() {
        val centered = range()
        centered.feed(gaussian(seed = 9, count = 5_000, mean = 0.8f, sigma = 0.02f))
        centered.reset()
        assertSame(FeatureValidity.Warmup, centered.validity)
        assertEquals("the first frame of a new session cannot be an outlier", 0f, centered.normalize(0.1f, FrameActivity.Sounding), 0f)
        assertEquals(0.1f, centered.trackedMean, 1e-6f)
    }

    @Test
    fun `a malformed range is refused at construction`() {
        val bad =
            listOf(
                { CenteredRange(0f, 1e-4f) },
                { CenteredRange(hopRateHz, minimumDeviation = 0f) },
                { CenteredRange(hopRateHz, minimumDeviation = Float.NaN) },
                { CenteredRange(hopRateHz, 1e-4f, deviations = 0f) },
                { CenteredRange(hopRateHz, 1e-4f, adaptSeconds = 0f) },
                { CenteredRange(hopRateHz, 1e-4f, warmupSeconds = -1f) },
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
