package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * What every §5.5 mode owes its callers, whichever mode it is.
 *
 * These are the promises a consumer can rely on without knowing which
 * normalizer it was handed — the reason the three sit behind one sealed type
 * at all.
 */
class NormalizerTest {
    private val hopRateHz = 86f

    private fun modes(): List<Normalizer> =
        listOf(
            FixedRange(0f, 1f),
            AdaptiveRange(hopRateHz, minimumSpan = 1e-4f),
            CenteredRange(hopRateHz, minimumDeviation = 1e-4f),
        )

    @Test
    fun `the sealed set is exactly the three modes the plan names`() {
        // Not a formality: this `when` has no else, so a fourth mode cannot be
        // added without a compile error here, and the output range of each is
        // stated in one place rather than three KDoc blocks.
        for (mode in modes()) {
            val range =
                when (mode) {
                    is FixedRange -> 0f..1f
                    is AdaptiveRange -> 0f..1f
                    is CenteredRange -> -1f..1f
                }
            val random = Random(11)
            repeat(5_000) {
                val raw = (random.nextDouble() * 4.0 - 2.0).toFloat()
                val out = mode.normalize(raw, FrameActivity.Sounding)
                assertTrue("${mode::class.simpleName} returned $out for $raw", out in range)
            }
        }
    }

    @Test
    fun `every mode rests at zero on a silent frame`() {
        for (mode in modes()) {
            repeat(200) { mode.normalize(0.6f, FrameActivity.Sounding) }
            assertEquals(mode::class.simpleName, 0f, mode.normalize(0.6f, FrameActivity.Silent), 0f)
            assertSame(mode::class.simpleName, FeatureValidity.Silent, mode.validity)
        }
    }

    @Test
    fun `every mode starts in warmup and returns to it on reset`() {
        for (mode in modes()) {
            assertSame(mode::class.simpleName, FeatureValidity.Warmup, mode.validity)
            repeat(1_000) { mode.normalize(0.6f, FrameActivity.Sounding) }
            assertSame(mode::class.simpleName, FeatureValidity.Valid, mode.validity)
            mode.reset()
            assertSame(mode::class.simpleName, FeatureValidity.Warmup, mode.validity)
        }
    }

    @Test
    fun `validity describes the frame that was just normalized`() {
        // The contract that makes two ABI slots safe to read as one value: the
        // pair can never be a Valid flag left over from an earlier frame.
        for (mode in modes()) {
            repeat(1_000) { mode.normalize(0.6f, FrameActivity.Sounding) }
            for (activity in listOf(FrameActivity.Silent, FrameActivity.Sounding, FrameActivity.Silent)) {
                mode.normalize(0.6f, activity)
                val expected = if (activity == FrameActivity.Silent) FeatureValidity.Silent else FeatureValidity.Valid
                assertSame(mode::class.simpleName, expected, mode.validity)
            }
        }
    }

    @Test
    fun `no mode returns a value a shader cannot use`() {
        // NaN and infinity are the two that reach the GPU and produce a black
        // frame rather than an error, so they are worth their own sweep over
        // the inputs a real feature can actually produce.
        val awkward = listOf(0f, -0f, 1e-30f, 1e30f, -1e30f, Float.MIN_VALUE, Float.MAX_VALUE)
        for (mode in modes()) {
            for (raw in awkward) {
                repeat(300) {
                    val out = mode.normalize(raw, FrameActivity.Sounding)
                    assertTrue("${mode::class.simpleName} returned $out for $raw", out.isFinite())
                }
            }
        }
    }

    @Test
    fun `normalizing a frame allocates nothing in any mode`() {
        for (mode in modes()) {
            var i = 0
            val perRun =
                JvmAllocationMeter.perRun(20_000) {
                    mode.normalize((i++ % 100) / 100f, if (i % 7 == 0) FrameActivity.Silent else FrameActivity.Sounding)
                }
            assertEquals("${mode::class.simpleName} allocated $perRun bytes per frame", 0.0, perRun, 1.0)
        }
    }
}
