package dev.musicviz

import dev.musicviz.render.fluid.MeltMath
import dev.musicviz.render.scene.BloomBank
import dev.musicviz.render.scene.HyperspaceMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pins the arithmetic that lets a fluid simulation move a raymarched fractal
 * without the raymarcher falling apart.
 *
 * Warping the domain of a distance estimate is the kind of change that looks
 * fine in a still frame and is wrong everywhere else, so the two numbers that
 * keep it honest are asserted here rather than eyeballed: the displacement is
 * bounded by exactly the amount every bounding sphere was inflated by, and the
 * march step is relaxed by enough to survive the broken Lipschitz bound.
 */
class HyperspaceMeltTest {
    /**
     * The contract between the shader's `uMeltReach` clamp and the CPU's
     * sphere inflation. If the two ever disagree, geometry gets displaced out
     * of the sphere the ray culled it with and the body is sliced along a
     * perfect circle - which reads as a rendering glitch, not as a bug in an
     * arithmetic identity.
     */
    @Test
    fun the_bound_inflation_is_exactly_the_displacement_ceiling() {
        val bank = BloomBank(Random(5))
        repeat(600) {
            bank.advance(
                1f / 60f,
                target = 5,
                impulse = 1f,
                species = null,
                lifetime = 300f,
                spread = 2.5f,
                sizeScale = 0.55f,
                motion = 1f,
                orbitScale = 1f,
            )
        }
        val pos = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val shape = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val look = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val rot = FloatArray(HyperspaceMath.MAX_BLOOMS * 9)

        for (melt in listOf(0f, 0.3f, 0.55f, 1f, 2f)) {
            val reach = MeltMath.reach(melt, MeltMath.DEFAULT_SCALE)
            val plain = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
            bank.snapshot(0.5f, plain, shape, look, rot, boundInflate = 0f)
            val n = bank.snapshot(0.5f, pos, shape, look, rot, boundInflate = reach)
            assertTrue(n > 0)
            for (i in 0 until n) {
                assertEquals(
                    "melt $melt: sphere not inflated by exactly the reach",
                    plain[i * 4 + 3] + reach,
                    pos[i * 4 + 3],
                    1e-5f,
                )
            }
        }
    }

    @Test
    fun no_melt_is_an_exact_no_op() {
        assertEquals(0f, MeltMath.reach(0f, MeltMath.DEFAULT_SCALE), 0f)
        assertEquals(1f, MeltMath.stepRelaxation(0f), 0f)
    }

    @Test
    fun the_reach_grows_with_melt_and_stays_bounded() {
        var prev = -1f
        var m = 0f
        while (m <= 2f) {
            val r = MeltMath.reach(m, MeltMath.DEFAULT_SCALE)
            assertTrue("reach is not monotone at $m", r >= prev)
            prev = r
            m += 0.1f
        }
        // Even at the top of the slider the medium must not be able to throw a
        // body clean out of the room the camera was placed around.
        assertTrue(MeltMath.reach(2f, MeltMath.DEFAULT_SCALE) < HyperspaceLookSpreadFloor)
        // And beyond the slider it still clamps rather than growing without end.
        assertEquals(
            MeltMath.reach(2f, MeltMath.DEFAULT_SCALE),
            MeltMath.reach(99f, MeltMath.DEFAULT_SCALE),
            0f,
        )
    }

    /**
     * The march has to slow down as the warp gets stronger. A ray still taking
     * the full estimate through a warped domain walks through thin geometry -
     * holes and shimmer, not something recognisable as an overshoot.
     */
    @Test
    fun the_march_relaxes_as_the_melt_deepens() {
        var prev = 2f
        var m = 0f
        while (m <= 2f) {
            val r = MeltMath.stepRelaxation(m)
            assertTrue("relaxation is not monotone at $m", r <= prev)
            assertTrue("relaxation left (0,1] at $m", r > 0f && r <= 1f)
            prev = r
            m += 0.1f
        }
        // Never so slow that the ray cannot cross the room within its budget.
        assertTrue(MeltMath.stepRelaxation(2f) > 0.2f)
    }

    @Test
    fun world_and_sim_coordinates_round_trip_through_the_scale() {
        for (w in listOf(-3f, -0.5f, 0f, 1.3f, 4f)) {
            assertEquals(w / MeltMath.DEFAULT_SCALE, MeltMath.simFromWorld(w, MeltMath.DEFAULT_SCALE), 1e-5f)
        }
        // A degenerate scale must not divide by zero.
        assertTrue(MeltMath.simFromWorld(1f, 0f).isFinite())
    }

    @Test
    fun the_room_lands_inside_the_field_rather_than_in_one_texel() {
        // The bodies orbit within `spread` of the origin at every act; the
        // scale exists to frame that in a grid whose y runs -1..1. Too large a
        // scale puts the whole room in a few texels, too small pushes it off
        // the grid, and both look like the fluid simply is not there.
        for (profile in HyperspaceMath.ACT_PROFILES) {
            val target = profile.bodies
            val spread = dev.musicviz.render.scene.HyperspaceLook.spread(target)
            val sim = MeltMath.simFromWorld(spread, MeltMath.DEFAULT_SCALE)
            assertTrue("act spread $spread maps to $sim, off the grid", sim <= 1.3f)
            assertTrue("act spread $spread maps to $sim, too small to resolve", sim >= 0.35f)
        }
    }

    @Test
    fun splat_radius_is_clamped_to_something_the_grid_can_draw() {
        for (r in listOf(0.001f, 0.1f, 0.8f, 5f)) {
            val s = MeltMath.splatRadius(r, MeltMath.DEFAULT_SCALE)
            assertTrue("radius $r -> $s out of range", s in 0.05f..0.5f)
        }
    }

    @Test
    fun a_body_pushes_hardest_at_the_ends_of_its_life() {
        assertEquals(MeltMath.BIRTH_BOOST, MeltMath.birthBoost(0f), 1e-5f)
        assertEquals(1f, MeltMath.birthBoost(1f), 1e-5f)
        assertTrue(MeltMath.birthBoost(0.5f) > 1f)
        // Out-of-range life must not produce a negative or runaway push.
        assertTrue(MeltMath.birthBoost(-1f) <= MeltMath.BIRTH_BOOST)
        assertTrue(MeltMath.birthBoost(9f) >= 1f)
    }

    @Test
    fun splats_outside_the_grid_are_rejected() {
        assertTrue(MeltMath.insideSim(0f, 0f, 0.5f))
        assertTrue(MeltMath.insideSim(0.5f, 1f, 0.5f))
        assertTrue(!MeltMath.insideSim(0f, 4f, 0.5f))
        assertTrue(!MeltMath.insideSim(9f, 0f, 0.5f))
    }

    private companion object {
        /**
         * The smallest orbit spread any act uses. The reach must stay well
         * under it: a displacement comparable to the whole room would not read
         * as melting, it would read as the geometry teleporting.
         */
        val HyperspaceLookSpreadFloor: Float =
            HyperspaceMath.ACT_PROFILES.minOf { dev.musicviz.render.scene.HyperspaceLook.spread(it.bodies) }
    }
}
