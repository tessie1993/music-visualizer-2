package dev.musicviz

import dev.musicviz.render.scene.Bloom
import dev.musicviz.render.scene.BloomBank
import dev.musicviz.render.scene.HyperspaceCamera
import dev.musicviz.render.scene.HyperspaceJourney
import dev.musicviz.render.scene.HyperspaceLook
import dev.musicviz.render.scene.HyperspaceMath
import dev.musicviz.render.scene.MarchBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pins the HYPERSPACE style's maths: the five-act story law, the bodies'
 * independent lifecycles, and the rotation algebra the shader consumes.
 *
 * These are the parts that cannot be checked by looking at the screen. A
 * rotation matrix that is subtly not the inverse still draws *something*; a
 * journey that never reaches the last act just looks like a style with fewer
 * looks than it has; a life envelope that steps instead of easing reads as
 * "the bodies flicker" rather than as a bug in one formula. So each of them
 * is asserted here instead.
 */
class HyperspaceMathTest {
    private val eps = 1e-4f

    // ---- the act table ---------------------------------------------------

    @Test
    fun act_profile_at_whole_numbers_is_the_table_entry() {
        for (i in HyperspaceMath.ACT_PROFILES.indices) {
            assertEquals(HyperspaceMath.ACT_PROFILES[i], HyperspaceMath.profileAt(i.toFloat()))
        }
    }

    @Test
    fun act_profile_interpolates_between_neighbours() {
        val a = HyperspaceMath.ACT_PROFILES[2]
        val b = HyperspaceMath.ACT_PROFILES[3]
        val mid = HyperspaceMath.profileAt(2.5f)
        assertEquals((a.camera + b.camera) * 0.5f, mid.camera, eps)
        assertEquals((a.field + b.field) * 0.5f, mid.field, eps)
        assertEquals((a.hueSpread + b.hueSpread) * 0.5f, mid.hueSpread, eps)
    }

    @Test
    fun act_profile_clamps_outside_the_table() {
        assertEquals(HyperspaceMath.ACT_PROFILES.first(), HyperspaceMath.profileAt(-9f))
        assertEquals(HyperspaceMath.ACT_PROFILES.last(), HyperspaceMath.profileAt(99f))
    }

    @Test
    fun every_act_has_a_name_and_a_profile() {
        assertEquals(HyperspaceMath.ACTS.size, HyperspaceMath.ACT_NAMES.size)
        assertEquals(HyperspaceMath.ACTS.size, HyperspaceMath.ACT_PROFILES.size)
    }

    /**
     * The mirror is a GATE in the shader (`uMirror >= 0.5`), not a blend -
     * interpolating a kaleidoscope's POSITIONS folds the plane over itself and
     * smears the frame. The table must therefore only ever hold 0 or 1, or an
     * act would sit permanently on the wrong side of a threshold it was never
     * meant to straddle.
     */
    @Test
    fun mirror_is_only_ever_fully_on_or_fully_off() {
        for (p in HyperspaceMath.ACT_PROFILES) {
            assertTrue("mirror ${p.mirror} is neither 0 nor 1", p.mirror == 0f || p.mirror == 1f)
        }
    }

    // ---- the journey -----------------------------------------------------

    @Test
    fun loud_music_walks_the_journey_all_the_way_in() {
        val j = HyperspaceJourney()
        repeat(secondsAsFrames(HyperspaceMath.RISE_SECONDS + 12f)) {
            j.advance(FRAME, energy = 1f, mode = HyperspaceMath.JOURNEY_MUSIC, holdAct = 0, cycleSeconds = 30f, pace = 1f)
        }
        assertEquals(1f, j.immersion, 1e-3f)
        assertEquals(HyperspaceMath.ACTS.size - 1, j.act)
    }

    @Test
    fun silence_walks_the_journey_back_out() {
        val j = HyperspaceJourney()
        repeat(secondsAsFrames(HyperspaceMath.RISE_SECONDS + 12f)) {
            j.advance(FRAME, 1f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f)
        }
        repeat(secondsAsFrames(HyperspaceMath.FALL_SECONDS + 12f)) {
            j.advance(FRAME, 0f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f)
        }
        assertEquals(0f, j.immersion, 1e-3f)
        assertEquals(0, j.act)
    }

    /** Coming back is slower than going in - the asymmetry is the point. */
    @Test
    fun the_way_back_is_slower_than_the_way_in() {
        assertTrue(HyperspaceMath.FALL_SECONDS > HyperspaceMath.RISE_SECONDS)
        val inward = HyperspaceJourney()
        repeat(secondsAsFrames(10f)) { inward.advance(FRAME, 1f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f) }
        val outward = HyperspaceJourney()
        repeat(secondsAsFrames(30f)) { outward.advance(FRAME, 1f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f) }
        val startedAt = outward.immersion
        repeat(secondsAsFrames(10f)) { outward.advance(FRAME, 0f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f) }
        assertTrue(
            "ten seconds of quiet undid more than ten seconds of loud built",
            startedAt - outward.immersion < inward.immersion,
        )
    }

    /**
     * A track hovering at the pivot must not re-target the body count several
     * times a second: a body's whole life is a spawn and a despawn.
     */
    @Test
    fun the_committed_act_never_changes_faster_than_the_dwell() {
        val j = HyperspaceJourney()
        var last = j.act
        var sinceChange = 0f
        var frames = 0
        // A square wave straddling the pivot, at 2 Hz - far faster than the
        // act is allowed to follow.
        while (frames < secondsAsFrames(80f)) {
            val energy = if ((frames / 30) % 2 == 0) 1f else 0f
            j.advance(FRAME, energy, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f)
            sinceChange += FRAME
            if (j.act != last) {
                assertTrue(
                    "act changed after ${sinceChange}s, dwell is ${HyperspaceMath.MIN_ACT_SECONDS}s",
                    sinceChange >= HyperspaceMath.MIN_ACT_SECONDS - FRAME,
                )
                last = j.act
                sinceChange = 0f
            }
            frames++
        }
    }

    @Test
    fun hold_mode_pins_the_chosen_act_whatever_is_playing() {
        val j = HyperspaceJourney()
        repeat(secondsAsFrames(40f)) {
            j.advance(FRAME, energy = 1f, mode = HyperspaceMath.JOURNEY_HOLD, holdAct = 1, cycleSeconds = 30f, pace = 1f)
        }
        assertEquals(1, j.act)
        assertEquals(1f, j.actPosition, 1e-2f)
    }

    @Test
    fun cycle_mode_visits_every_act_and_returns() {
        val j = HyperspaceJourney()
        val seen = HashSet<Int>()
        // Five acts at ten seconds each, twice around.
        repeat(secondsAsFrames(10f * HyperspaceMath.ACTS.size * 2)) {
            j.advance(FRAME, energy = 0f, mode = HyperspaceMath.JOURNEY_CYCLE, holdAct = 0, cycleSeconds = 10f, pace = 1f)
            seen.add(j.act)
        }
        assertEquals(HyperspaceMath.ACTS.indices.toSet(), seen)
    }

    @Test
    fun a_paused_pace_freezes_the_journey() {
        val j = HyperspaceJourney()
        repeat(secondsAsFrames(60f)) { j.advance(FRAME, 1f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, pace = 0f) }
        assertEquals(0f, j.immersion, eps)
        assertEquals(0, j.act)
    }

    // ---- rotation algebra ------------------------------------------------

    @Test
    fun world_to_local_rotation_is_orthonormal() {
        val rng = Random(7)
        val m = FloatArray(9)
        repeat(20) {
            val a = unit(rng)
            val b = unit(rng)
            HyperspaceMath.worldToLocalRotation(a, rng.nextFloat() * 6f, b, rng.nextFloat() * 6f, m, 0)
            for (c in 0 until 3) {
                val col = floatArrayOf(m[c * 3], m[c * 3 + 1], m[c * 3 + 2])
                assertEquals("column $c is not unit length", 1f, len(col), 1e-3f)
            }
            // Columns pairwise perpendicular.
            for (c in 0 until 3) {
                val d = 0 until 3
                for (c2 in d) {
                    if (c2 <= c) continue
                    var dot = 0f
                    for (r in 0 until 3) dot += m[c * 3 + r] * m[c2 * 3 + r]
                    assertEquals("columns $c,$c2 not perpendicular", 0f, dot, 1e-3f)
                }
            }
        }
    }

    /**
     * The matrix handed to the shader must undo the body's own orientation:
     * rotating a point by A then B and then applying the matrix has to give
     * the point back. This is the assertion that would have caught a
     * transposed or wrongly-ordered product, which draws a plausible-looking
     * but wrong scene.
     */
    @Test
    fun world_to_local_rotation_undoes_the_forward_orientation() {
        val rng = Random(11)
        val m = FloatArray(9)
        val a = FloatArray(9)
        val b = FloatArray(9)
        val out = FloatArray(3)
        repeat(20) {
            val axisA = unit(rng)
            val axisB = unit(rng)
            val angA = rng.nextFloat() * 6f
            val angB = rng.nextFloat() * 6f
            HyperspaceMath.axisAngle(axisA, angA, a)
            HyperspaceMath.axisAngle(axisB, angB, b)
            val v = floatArrayOf(rng.nextFloat() * 2f - 1f, rng.nextFloat() * 2f - 1f, rng.nextFloat() * 2f - 1f)
            // Forward: apply A, then B (row-major multiply).
            val afterA = rowMajorMul(a, v)
            val world = rowMajorMul(b, afterA)
            HyperspaceMath.worldToLocalRotation(axisA, angA, axisB, angB, m, 0)
            HyperspaceMath.transform(m, 0, world, out)
            for (i in 0 until 3) assertEquals(v[i], out[i], 1e-3f)
        }
    }

    @Test
    fun rotation_writes_only_its_own_slice() {
        val m = FloatArray(HyperspaceMath.FLOATS_PER_MAT3 * 3) { -7f }
        HyperspaceMath.worldToLocalRotation(floatArrayOf(0f, 1f, 0f), 1f, floatArrayOf(1f, 0f, 0f), 0.5f, m, 9)
        for (i in 0 until 9) assertEquals(-7f, m[i], 0f)
        for (i in 18 until 27) assertEquals(-7f, m[i], 0f)
        assertNotEquals(-7f, m[9])
    }

    @Test
    fun a_degenerate_axis_yields_identity_rather_than_nan() {
        val m = FloatArray(9)
        HyperspaceMath.axisAngle(floatArrayOf(0f, 0f, 0f), 1.3f, m)
        assertEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).toList(), m.toList())
    }

    @Test
    fun random_unit_vectors_are_unit_length() {
        val rng = Random(3)
        val v = FloatArray(3)
        repeat(200) {
            HyperspaceMath.randomUnitVector(rng, v)
            assertEquals(1f, len(v), 1e-3f)
        }
    }

    @Test
    fun random_planes_are_orthonormal() {
        val rng = Random(5)
        val u = FloatArray(3)
        val v = FloatArray(3)
        repeat(200) {
            HyperspaceMath.randomPlane(rng, u, v)
            assertEquals(1f, len(u), 1e-3f)
            assertEquals(1f, len(v), 1e-3f)
            assertEquals(0f, u[0] * v[0] + u[1] * v[1] + u[2] * v[2], 1e-3f)
        }
    }

    // ---- lives -----------------------------------------------------------

    @Test
    fun the_life_envelope_starts_and_ends_at_nothing() {
        assertEquals(0f, HyperspaceMath.lifeEnvelope(0f, 10f, 1f, 2f), 0f)
        assertEquals(0f, HyperspaceMath.lifeEnvelope(10f, 10f, 1f, 2f), 0f)
        assertEquals(0f, HyperspaceMath.lifeEnvelope(-1f, 10f, 1f, 2f), 0f)
        assertEquals(0f, HyperspaceMath.lifeEnvelope(11f, 10f, 1f, 2f), 0f)
        assertEquals(1f, HyperspaceMath.lifeEnvelope(5f, 10f, 1f, 2f), 1e-3f)
    }

    @Test
    fun the_life_envelope_rises_and_falls_monotonically() {
        var prev = 0f
        var t = 0f
        while (t <= 1f) {
            val v = HyperspaceMath.lifeEnvelope(t, 10f, 1f, 2f)
            assertTrue("rise is not monotone at $t", v >= prev - 1e-5f)
            prev = v
            t += 0.05f
        }
        prev = 1f
        t = 8f
        while (t <= 10f) {
            val v = HyperspaceMath.lifeEnvelope(t, 10f, 1f, 2f)
            assertTrue("fall is not monotone at $t", v <= prev + 1e-5f)
            prev = v
            t += 0.05f
        }
    }

    /**
     * Retiring a body early must not step its envelope down. Before the wither
     * was carried as its own state, shortening the life re-derived it as a
     * fifth of the NEW life, which put the start of the dissolve in the past.
     */
    @Test
    fun retiring_a_body_does_not_make_its_fade_jump() {
        val b = Bloom()
        b.spawn(Random(1), HyperspaceMath.Species.GASKET, lifetime = 20f, spread = 2f, sizeScale = 0.5f)
        repeat(secondsAsFrames(9f)) { b.advance(FRAME, 1f, 1f) }
        val before = b.fade
        assertTrue("body should be fully grown by nine seconds of a twenty second life", before > 0.99f)
        b.retire(1.6f)
        b.advance(FRAME, 1f, 1f)
        assertTrue("fade stepped from $before to ${b.fade}", before - b.fade < 0.05f)
        // And it does still die.
        repeat(secondsAsFrames(2f)) { b.advance(FRAME, 1f, 1f) }
        assertTrue(!b.alive || b.fade < 0.05f)
    }

    /**
     * The whole premise of the style: no two bodies share a clock. Two bodies
     * spawned back to back must differ in where they are, which way they face
     * and what they are.
     */
    @Test
    fun two_bodies_do_not_share_a_clock() {
        val rng = Random(21)
        val a = Bloom()
        val b = Bloom()
        a.spawn(rng, HyperspaceMath.Species.CORAL, 20f, 2.5f, 0.6f)
        b.spawn(rng, HyperspaceMath.Species.CORAL, 20f, 2.5f, 0.6f)
        repeat(secondsAsFrames(4f)) {
            a.advance(FRAME, 1f, 1f)
            b.advance(FRAME, 1f, 1f)
        }
        val ra = FloatArray(9)
        val rb = FloatArray(9)
        a.writeRotation(ra, 0)
        b.writeRotation(rb, 0)
        assertTrue("the two bodies are in the same place", dist(a.centre, b.centre) > 1e-3f)
        var diff = 0f
        for (i in 0 until 9) diff += abs(ra[i] - rb[i])
        assertTrue("the two bodies face the same way", diff > 1e-3f)
    }

    // ---- the bank --------------------------------------------------------

    @Test
    fun bodies_arrive_on_hits_not_on_a_timer() {
        val bank = BloomBank(Random(31))
        // Well under SILENT_SPAWN_SECONDS of quiet, with no transient at all.
        repeat(secondsAsFrames(2f)) {
            bank.advance(
                FRAME,
                target = 6,
                impulse = 0f,
                species = null,
                lifetime = 20f,
                spread = 2f,
                sizeScale = 0.5f,
                motion = 1f,
                orbitScale = 1f,
            )
        }
        assertEquals(0, bank.aliveCount)
        // Now play something.
        repeat(20) {
            repeat(secondsAsFrames(0.6f)) {
                bank.advance(
                    FRAME,
                    6,
                    impulse = 0f,
                    species = null,
                    lifetime = 20f,
                    spread = 2f,
                    sizeScale = 0.5f,
                    motion = 1f,
                    orbitScale = 1f,
                )
            }
            bank.advance(
                FRAME,
                6,
                impulse = 1f,
                species = null,
                lifetime = 20f,
                spread = 2f,
                sizeScale = 0.5f,
                motion = 1f,
                orbitScale = 1f,
            )
        }
        assertEquals(6, bank.aliveCount)
    }

    /** Silence must not mean an empty screen: after a while it seeds anyway. */
    @Test
    fun a_long_silence_still_populates_the_room() {
        val bank = BloomBank(Random(33))
        repeat(secondsAsFrames(40f)) {
            bank.advance(
                FRAME,
                target = 4,
                impulse = 0f,
                species = null,
                lifetime = 30f,
                spread = 2f,
                sizeScale = 0.5f,
                motion = 1f,
                orbitScale = 1f,
            )
        }
        assertEquals(4, bank.aliveCount)
    }

    @Test
    fun the_bank_thins_out_when_the_act_asks_for_fewer() {
        val bank = BloomBank(Random(37))
        repeat(secondsAsFrames(40f)) {
            bank.advance(FRAME, 8, 0f, null, 300f, 2f, 0.5f, 1f, 1f)
        }
        assertEquals(8, bank.aliveCount)
        repeat(secondsAsFrames(12f)) {
            bank.advance(FRAME, 2, 0f, null, 300f, 2f, 0.5f, 1f, 1f)
        }
        assertEquals(2, bank.aliveCount)
    }

    @Test
    fun the_bank_never_exceeds_the_uniform_arrays() {
        val bank = BloomBank(Random(41))
        repeat(secondsAsFrames(60f)) {
            bank.advance(
                FRAME,
                target = 99,
                impulse = 1f,
                species = null,
                lifetime = 300f,
                spread = 2f,
                sizeScale = 0.5f,
                motion = 1f,
                orbitScale = 1f,
            )
        }
        assertTrue(bank.aliveCount <= HyperspaceMath.MAX_BLOOMS)
        assertEquals(HyperspaceMath.MAX_BLOOMS, bank.aliveCount)
    }

    @Test
    fun a_forced_species_is_the_only_one_that_spawns() {
        val bank = BloomBank(Random(43))
        repeat(secondsAsFrames(40f)) {
            bank.advance(FRAME, 8, 1f, HyperspaceMath.Species.JEWEL, 300f, 2f, 0.5f, 1f, 1f)
        }
        assertTrue(bank.aliveCount > 0)
        for (b in bank.blooms) {
            if (b.alive) assertEquals(HyperspaceMath.Species.JEWEL, b.species)
        }
    }

    @Test
    fun snapshot_packs_what_the_shader_expects() {
        val bank = BloomBank(Random(47))
        repeat(secondsAsFrames(40f)) {
            bank.advance(FRAME, 5, 1f, null, 300f, 2.5f, 0.55f, 1f, 1f)
        }
        val pos = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val shape = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val look = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val rot = FloatArray(HyperspaceMath.MAX_BLOOMS * 9)
        val n = bank.snapshot(fold = 0.5f, pos = pos, shape = shape, look = look, rot = rot)
        assertEquals(5, n)
        for (i in 0 until n) {
            val species = shape[i * 4].toInt()
            assertTrue("species ordinal $species out of range", species in HyperspaceMath.SPECIES.indices)
            val scale = shape[i * 4 + 1]
            assertTrue("world scale $scale is not positive", scale > 0f)
            // The bounding radius must actually bound the fractal, or the
            // raymarcher will skip the body it was supposed to enter.
            assertEquals(
                HyperspaceMath.localRadius(HyperspaceMath.SPECIES[species]) * scale,
                pos[i * 4 + 3],
                1e-4f,
            )
            val fade = shape[i * 4 + 3]
            assertTrue("life envelope $fade out of range", fade in 0f..1f)
            assertTrue("hue ${look[i * 4]} out of range", look[i * 4] in 0f..1f)
            // Rotation slices are all written, none left as zeros.
            var mag = 0f
            for (k in 0 until 9) mag += abs(rot[i * 9 + k])
            assertTrue("rotation slice $i was never written", mag > 0.5f)
        }
    }

    @Test
    fun a_body_grows_rather_than_popping_into_existence() {
        val bank = BloomBank(Random(53))
        val pos = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val shape = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val look = FloatArray(HyperspaceMath.MAX_BLOOMS * 4)
        val rot = FloatArray(HyperspaceMath.MAX_BLOOMS * 9)
        var smallest = Float.MAX_VALUE
        var largest = 0f
        repeat(secondsAsFrames(12f)) {
            bank.advance(FRAME, 1, 1f, HyperspaceMath.Species.BULB, 20f, 2f, 0.6f, 1f, 1f)
            if (bank.snapshot(0.5f, pos, shape, look, rot) > 0) {
                smallest = minOf(smallest, pos[3])
                largest = maxOf(largest, pos[3])
            }
        }
        assertTrue("no body was ever drawn", largest > 0f)
        assertTrue("the body never started small: $smallest vs $largest", smallest < largest * 0.6f)
    }

    // ---- framing ---------------------------------------------------------

    /**
     * A raymarcher started INSIDE a folded distance estimator draws stripes,
     * not an interior - the estimate is only valid outside the set. So the eye
     * has to stay outside every body, at every act, at every density.
     */
    @Test
    fun the_camera_never_ends_up_inside_a_body() {
        for (profile in HyperspaceMath.ACT_PROFILES) {
            for (density in listOf(0.2f, 1f, 2f)) {
                val target = HyperspaceLook.bodyTarget(profile.bodies, density)
                val spread = HyperspaceLook.spread(target)
                val maxRadius = HyperspaceLook.maxBodyRadius(target)
                val d = HyperspaceLook.cameraDistance(profile.camera, spread, maxRadius)
                assertTrue(
                    "camera at $d is inside a body reaching ${spread + maxRadius}",
                    d > spread + maxRadius,
                )
                assertTrue("far plane clips the far side", HyperspaceLook.farPlane(d, spread) > d + spread)
            }
        }
    }

    @Test
    fun the_camera_looks_at_the_origin_from_the_distance_it_was_given() {
        val cam = HyperspaceCamera()
        var t = 0f
        while (t < 400f) {
            cam.advance(FRAME, distance = 5.5f, drift = 1f, roll = 0.3f)
            t += FRAME
            assertEquals("eye drifted off its sphere", 5.5f, len(cam.position), 1e-2f)
            // Forward is the third column and must point back at the origin.
            val f = floatArrayOf(cam.basis[6], cam.basis[7], cam.basis[8])
            assertEquals(1f, len(f), 1e-3f)
            val inv = 1f / len(cam.position)
            for (i in 0 until 3) assertEquals(-cam.position[i] * inv, f[i], 1e-3f)
            // Basis stays orthonormal, including through the roll.
            val r = floatArrayOf(cam.basis[0], cam.basis[1], cam.basis[2])
            val u = floatArrayOf(cam.basis[3], cam.basis[4], cam.basis[5])
            assertEquals(1f, len(r), 1e-3f)
            assertEquals(1f, len(u), 1e-3f)
            assertEquals(0f, r[0] * u[0] + r[1] * u[1] + r[2] * u[2], 1e-3f)
        }
    }

    @Test
    fun the_camera_path_does_not_close_on_itself() {
        val cam = HyperspaceCamera()
        cam.advance(FRAME, 5f, 1f, 0f)
        val start = cam.position.copyOf()
        var closest = Float.MAX_VALUE
        // Skip the first few seconds, where it has not yet moved away.
        repeat(secondsAsFrames(20f)) { cam.advance(FRAME, 5f, 1f, 0f) }
        repeat(secondsAsFrames(600f)) {
            cam.advance(FRAME, 5f, 1f, 0f)
            closest = minOf(closest, dist(cam.position, start))
        }
        assertTrue("the camera returned to its starting point after ten minutes", closest > 0.05f)
    }

    // ---- budgets and bands -----------------------------------------------

    @Test
    fun the_march_budget_stays_inside_the_shaders_compile_time_bounds() {
        var d = 0.1f
        while (d <= 2f) {
            val b = MarchBudget.forDetail(d)
            assertTrue(b.steps in 1..MarchBudget.MAX_STEPS)
            assertTrue(b.iterations in 1..MarchBudget.MAX_ITERS)
            assertTrue(b.bulbIterations in 1..10)
            // The bulb is several times the cost of the others, so it must
            // never be asked for more than they are.
            assertTrue(b.bulbIterations <= b.iterations)
            d += 0.05f
        }
    }

    @Test
    fun more_detail_is_never_less_work() {
        val low = MarchBudget.forDetail(0.3f)
        val high = MarchBudget.forDetail(1.5f)
        assertTrue(high.steps > low.steps)
        assertTrue(high.iterations > low.iterations)
    }

    @Test
    fun every_species_has_a_usable_fold_band() {
        for (s in HyperspaceMath.SPECIES) {
            val lo = HyperspaceMath.foldFor(s, 0f, 0f)
            val hi = HyperspaceMath.foldFor(s, 1f, 0f)
            assertTrue("$s fold band is empty", hi > lo)
            assertTrue("$s fold is not finite", lo.isFinite() && hi.isFinite())
            // Jitter must stay inside the band's own neighbourhood rather than
            // walking a body out of the range its species is drawable in.
            val jittered = HyperspaceMath.foldFor(s, 0.5f, 1f)
            assertTrue("$s jitter left the band", jittered in minOf(lo, hi)..maxOf(lo, hi))
        }
        // The bulb's power is the one that must bracket 8, the classic.
        assertTrue(HyperspaceMath.foldFor(HyperspaceMath.Species.BULB, 0f, 0f) < 8f)
        assertTrue(HyperspaceMath.foldFor(HyperspaceMath.Species.BULB, 1f, 0f) > 8f)
    }

    @Test
    fun the_body_target_is_never_zero_and_never_overflows() {
        for (profile in HyperspaceMath.ACT_PROFILES) {
            for (density in listOf(0f, 0.01f, 0.5f, 1f, 2f, 99f)) {
                val n = HyperspaceLook.bodyTarget(profile.bodies, density)
                assertTrue("target $n out of range", n in 1..HyperspaceMath.MAX_BLOOMS)
            }
        }
    }

    @Test
    fun the_species_selector_lists_mixed_plus_every_species() {
        assertEquals(
            HyperspaceMath.SPECIES.size + 1,
            dev.musicviz.render.scene.SceneParams.HYPERSPACE_SPECIES.size,
        )
        assertEquals("Mixed", dev.musicviz.render.scene.SceneParams.HYPERSPACE_SPECIES.first())
    }

    // ---- helpers ---------------------------------------------------------

    private companion object {
        const val FRAME = 1f / 60f
    }

    private fun secondsAsFrames(seconds: Float): Int = (seconds / FRAME).toInt()

    private fun len(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dist(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun unit(rng: Random): FloatArray {
        val v = FloatArray(3)
        HyperspaceMath.randomUnitVector(rng, v)
        return v
    }

    /** `m` row-major times `v`, i.e. what [HyperspaceMath.axisAngle] produces. */
    private fun rowMajorMul(
        m: FloatArray,
        v: FloatArray,
    ): FloatArray =
        floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        )
}
