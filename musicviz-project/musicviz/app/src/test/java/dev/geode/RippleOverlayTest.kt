package dev.geode

import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.RippleMath
import dev.geode.render.fluid.RippleOverlayDrops
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
        drops.tick(features(beat = true, bass = 1f), 1f) { _, _, _, amp ->
            count++
            assertTrue("beat drop must have positive amplitude", amp > 0f)
        }
        assertEquals(RippleOverlayDrops.BEAT_DROPS, count)
        // No beat, treble under threshold: silence stays glassy.
        repeat(120) {
            drops.tick(features(treble = RippleOverlayDrops.SPARKLE_THRESHOLD * 0.5f), 1f) { _, _, _, _ ->
                throw AssertionError("quiet frame must not drop")
            }
        }
    }

    @Test
    fun sparkleDropsAreRateCapped() {
        val drops = RippleOverlayDrops()
        var count = 0
        val frames = 120
        repeat(frames) {
            drops.tick(features(treble = 1.5f), 1f) { _, _, _, _ -> count++ }
        }
        assertEquals(frames / RippleOverlayDrops.SPARKLE_INTERVAL, count)
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
        for (f in stream) dropsA.tick(f, 1.78f) { x, y, r, amp -> a.add(listOf(x, y, r, amp)) }
        for (f in stream) dropsB.tick(f, 1.78f) { x, y, r, amp -> b.add(listOf(x, y, r, amp)) }
        assertTrue("schedule must produce drops", a.isNotEmpty())
        assertEquals(a, b)
    }
}
