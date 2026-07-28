package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.TimelineFrame
import dev.musicviz.render.fluid.FluidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless gate for the rebuilt particle lifecycle math (CPU mirrors in
 * lockstep with fluid_particle_update_frag) and the progression enrichment
 * that feeds the choreography in live playback and export.
 */
class FluidLifecycleMathTest {
    @Test
    fun attractorForceIsBoundedAndMonotonic() {
        // Soft cap: no pull/distance combination may exceed 6 sim-units/s^2 -
        // the "close pass must swing, never explode" contract.
        for (pull in floatArrayOf(0.1f, 1f, 3f, 100f)) {
            var prev = Float.MAX_VALUE
            for (d2 in floatArrayOf(0f, 1e-4f, 0.01f, 0.1f, 1f, 4f)) {
                val f = FluidMath.attractorForce(pull, d2)
                assertTrue("force $f above cap", f < 6f)
                assertTrue("force must be non-negative", f >= 0f)
                assertTrue("force must weaken with distance", f <= prev + 1e-6f)
                prev = f
            }
        }
        // Far away the softened inverse-square shape survives the cap.
        val near = FluidMath.attractorForce(1f, 0.25f)
        val far = FluidMath.attractorForce(1f, 1f)
        assertTrue("expected ~inverse-square falloff", near > far * 2f)
    }

    @Test
    fun captureFiresExactlyInsideTheRadius() {
        assertTrue(FluidMath.isCaptured(0.1f, 0f, 0f, 0f, 0.15f))
        assertFalse(FluidMath.isCaptured(0.2f, 0f, 0f, 0f, 0.15f))
        // Boundary is exclusive - matches the shader's strict <.
        assertFalse(FluidMath.isCaptured(0.15f, 0f, 0f, 0f, 0.15f))
        // Degenerate zero radius can never capture.
        assertFalse(FluidMath.isCaptured(0f, 0f, 1e-8f, 0f, 0f))
    }

    private fun timeline(frames: Int): FeatureTimeline {
        val hop = 100L
        return FeatureTimeline(
            frames = (0 until frames).map { TimelineFrame(it * hop, AudioFeatures.empty(8, 8)) },
            hopMs = hop,
        )
    }

    @Test
    fun progressionAtEnrichesFeaturesDeterministically() {
        val t = timeline(101) // duration 10_000 ms
        val sections = listOf(3000L, 7000L)
        val early = t.progressionAt(0L, sections)
        val mid = t.progressionAt(5000L, sections)
        val end = t.progressionAt(10_000L, sections)
        assertEquals(0f, early.progress, 1e-6f)
        assertEquals(0, early.sectionIndex)
        assertEquals(0.5f, mid.progress, 1e-6f)
        assertEquals(1, mid.sectionIndex)
        assertEquals(1f, end.progress, 1e-6f)
        assertEquals(2, end.sectionIndex)
        assertEquals(3, end.sectionCount)
        // Deterministic: same inputs, same outputs (export parity contract).
        assertEquals(mid, t.progressionAt(5000L, sections))
        // Section boundary is inclusive on arrival.
        assertEquals(1, t.progressionAt(3000L, sections).sectionIndex)
    }

    @Test
    fun progressionAtPassesThroughWhenDurationUnknown() {
        val empty = FeatureTimeline(frames = emptyList(), hopMs = 100L)
        val f = empty.progressionAt(5000L, emptyList())
        assertEquals(0f, f.progress, 0f)
        assertEquals(0, f.sectionIndex)
        assertEquals(0, f.sectionCount)
    }
}
