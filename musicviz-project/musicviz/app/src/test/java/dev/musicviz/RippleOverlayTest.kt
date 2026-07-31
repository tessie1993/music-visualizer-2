package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.RippleMath
import dev.musicviz.render.fluid.RippleOverlayDrops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless gate for the ripple overlay's deterministic drop placement (F2):
 * the golden-angle position sequence must stay inside the sim domain, be
 * reproducible (live view and export land identical drops - no RNG), and
 * scatter instead of clustering; the beat/treble schedule must drop rings on
 * beats, sparkles only above the treble threshold at the capped rate, and
 * replay identically for the same feature stream.
 */
class RippleOverlayTest {
    private fun features(
        beat: Boolean = false,
        bass: Float = 0f,
        treble: Float = 0f,
    ): AudioFeatures =
        AudioFeatures(
            bands = FloatArray(16),
            waveform = FloatArray(64),
            bass = bass,
            treble = treble,
            beat = beat,
        )

    @Test
    fun dropPositionsStayInsideSimDomain() {
        for (aspect in floatArrayOf(0.5f, 1f, 1.78f, 2.4f)) {
            for (n in 0 until 500) {
                val (x, y) = RippleMath.overlayDropPosition(n, aspect)
                assertTrue("x=$x out of range at n=$n aspect=$aspect", abs(x) <= 0.85f * aspect + 1e-4f)
                assertTrue("y=$y out of range at n=$n", abs(y) <= 0.85f + 1e-4f)
            }
        }
    }

    @Test
    fun dropPositionsAreDeterministicAndScattered() {
        // Determinism: same index -> same position, always.
        for (n in 0 until 64) {
            val a = RippleMath.overlayDropPosition(n, 1.5f)
            val b = RippleMath.overlayDropPosition(n, 1.5f)
            assertEquals(a.first, b.first, 0f)
            assertEquals(a.second, b.second, 0f)
        }
        // Scatter: consecutive drops must not cluster (golden-angle spiral
        // property) - every consecutive pair lands a visible distance apart.
        for (n in 1 until 200) {
            val (x0, y0) = RippleMath.overlayDropPosition(n - 1, 1f)
            val (x1, y1) = RippleMath.overlayDropPosition(n, 1f)
            val d = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            assertTrue("consecutive drops cluster at n=$n (d=$d)", d > 0.05f)
        }
    }

    @Test
    fun beatDropsRingsAndQuietFramesDropNothing() {
        val drops = RippleOverlayDrops()
        var count = 0
        drops.tick(features(beat = true, bass = 1f), 1f, DT_60) { _, _, _, amp ->
            count++
            assertTrue("beat drop must have positive amplitude", amp > 0f)
        }
        assertEquals(RippleOverlayDrops.BEAT_DROPS, count)
        // No beat, treble under threshold: silence stays glassy.
        repeat(120) {
            drops.tick(features(treble = RippleOverlayDrops.SPARKLE_THRESHOLD * 0.5f), 1f, DT_60) { _, _, _, _ ->
                throw AssertionError("quiet frame must not drop")
            }
        }
    }

    /** Sparkle count over [seconds] of solid treble, ticked at [fps]. */
    private fun sparklesOver(
        seconds: Float,
        fps: Int,
    ): Int {
        val drops = RippleOverlayDrops()
        var count = 0
        repeat((seconds * fps).toInt()) {
            drops.tick(features(treble = 1.5f), 1f, 1f / fps) { _, _, _, _ -> count++ }
        }
        return count
    }

    @Test
    fun sparkleDropsAreRateCapped() {
        // Solid treble for 2 s at a 0.1 s cap: ~20 sparkles, give or take the
        // one that depends on whether a tick lands exactly on the boundary.
        val expected = (2f / RippleOverlayDrops.SPARKLE_INTERVAL_SEC).toInt()
        val actual = sparklesOver(2f, 60)
        assertTrue("expected ~$expected sparkles in 2 s, got $actual", abs(actual - expected) <= 1)
    }

    @Test
    fun theSparkleRateDoesNotDependOnTheFrameRate() {
        // The regression this guards: the cap used to be "every 6th frame", so
        // playback at 120 Hz sprinkled twice as fast as a 60 fps export of the
        // same track - and since sparkles and beat rings share one drop
        // counter, the extra sparkles also shifted every subsequent RING.
        val at24 = sparklesOver(4f, 24)
        val at60 = sparklesOver(4f, 60)
        val at120 = sparklesOver(4f, 120)
        assertTrue("24 fps gave $at24, 60 fps gave $at60", abs(at24 - at60) <= 1)
        assertTrue("120 fps gave $at120, 60 fps gave $at60", abs(at120 - at60) <= 1)
    }

    @Test
    fun scheduleReplaysIdenticallyForTheSameFeatureStream() {
        val stream =
            (0 until 90).map {
                features(beat = it % 30 == 0, bass = 0.8f, treble = if (it % 5 == 0) 1f else 0.2f)
            }
        val a = ArrayList<List<Float>>()
        val b = ArrayList<List<Float>>()
        val dropsA = RippleOverlayDrops()
        val dropsB = RippleOverlayDrops()
        for (f in stream) dropsA.tick(f, 1.78f, DT_60) { x, y, r, amp -> a.add(listOf(x, y, r, amp)) }
        for (f in stream) dropsB.tick(f, 1.78f, DT_60) { x, y, r, amp -> b.add(listOf(x, y, r, amp)) }
        assertTrue("schedule must produce drops", a.isNotEmpty())
        assertEquals(a, b)
    }

    private companion object {
        /** One frame at 60 fps, the rate the old frame-counted cap assumed. */
        const val DT_60 = 1f / 60f
    }
}
