package dev.musicviz

import dev.musicviz.render.scene.CymaticsDrops
import dev.musicviz.render.scene.CymaticsMath
import dev.musicviz.render.scene.CymaticsPlate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pure maths added by the CYMATICS substyle rework: the ferrofluid's hex
 * lattice, the Standing Chamber's room-mode recomposition, the pitch-class
 * hue coupling, the Faraday droplet bank - and the family-wide claim that a
 * change of dominant mode SLIDES through superposition rather than popping.
 *
 * Same split as [CymaticsMathTest]: the formulas the shader runs per pixel
 * are pinned against their CPU twins here, headless, because no device test
 * can check them by looking.
 */
class CymaticsReworkMathTest {
    private val dt = 1f / 60f

    // ------------------------------------------------------------ hex lattice

    @Test
    fun the_hex_lattice_peaks_at_the_spike_sites_and_is_six_fold_symmetric() {
        // 1.0 exactly where three cosines align - a spike site.
        assertEquals(1f, CymaticsMath.hexLattice(0f, 0f), 1e-6f)
        // Rotating by 60 degrees permutes the three wave vectors (up to
        // sign), so the lattice must be invariant - that symmetry is what
        // makes it read as ferrofluid rather than as stripes.
        val c = cos(PI.toFloat() / 3f)
        val s = sin(PI.toFloat() / 3f)
        for (i in 0 until 24) {
            val x = -3f + i * 0.26f
            val y = 1.7f - i * 0.13f
            val rx = c * x - s * y
            val ry = s * x + c * y
            assertEquals("hex lattice broke its 60-degree symmetry", CymaticsMath.hexLattice(x, y), CymaticsMath.hexLattice(rx, ry), 1e-4f)
        }
        // Smooth and bounded: the spike remap raises it to a power, so a
        // value outside [-1, 1] would explode the spike heights.
        var min = 1f
        for (i in 0..60) {
            for (j in 0..60) {
                val v = CymaticsMath.hexLattice(-6f + i * 0.2f, -6f + j * 0.2f)
                assertTrue(v <= 1f + 1e-5f)
                if (v < min) min = v
            }
        }
        assertTrue("hex lattice floor $min drifted", min >= -0.6f)
    }

    // ------------------------------------------------------------- room modes

    @Test
    fun room_modes_are_product_cosines_and_exactly_periodic() {
        val modes =
            floatArrayOf(
                3f, 2f, 0.6f, 0.3f,
                2f, 0f, 0.4f, 1.0f,
                5f, 1f, 0.2f, 0f,
                4f, 3f, 0.1f, 0.5f,
                9f, 2f, 0.9f, 0f,
            )
        val pi = PI.toFloat()
        // The formula itself: PRODUCT cosines (rectangular pressure cells),
        // not the plate's antisymmetric difference.
        val x = 0.31f
        val y = -0.57f
        var expected = 0f
        for (i in 0 until 4) {
            val b = i * 4
            expected += modes[b + 2] * cos(modes[b] * pi * x) * cos(modes[b + 1] * pi * y) * cos(modes[b + 3])
        }
        assertEquals(expected, CymaticsMath.roomModeHeight(modes, 5, x, y), 1e-5f)
        // Only ROOM_MODES are drawn: the fifth (loud!) quad must not leak in.
        assertEquals(
            CymaticsMath.roomModeHeight(modes, 4, x, y),
            CymaticsMath.roomModeHeight(modes, 5, x, y),
            0f,
        )
        // 2-periodic in both axes and in the drift - the exact fact that lets
        // the scene wrap its scroll accumulator at 2.0 with no visible pop.
        for (i in 0..12) {
            val px = -1f + i * 0.17f
            val py = 1f - i * 0.15f
            val here = CymaticsMath.roomModeHeight(modes, 4, px, py)
            assertEquals(here, CymaticsMath.roomModeHeight(modes, 4, px + 2f, py), 1e-4f)
            assertEquals(here, CymaticsMath.roomModeHeight(modes, 4, px, py + 2f), 1e-4f)
            assertEquals(
                CymaticsMath.roomModeHeight(modes, 4, px, py, drift = 0f),
                CymaticsMath.roomModeHeight(modes, 4, px, py, drift = 2f),
                1e-4f,
            )
        }
    }

    // ------------------------------------------------------- pitch-class hue

    @Test
    fun the_chroma_hue_goes_the_short_way_round_the_wheel() {
        // B -> C is one semitone, and must nudge the palette one step - not
        // sweep it through eleven.
        assertEquals(0.05f, CymaticsMath.approachHue(0.95f, 0.05f, 1f), 1e-5f)
        assertEquals(0.95f, CymaticsMath.approachHue(0.05f, 0.95f, 1f), 1e-5f)
        assertEquals(0.15f, CymaticsMath.approachHue(0.1f, 0.2f, 0.5f), 1e-5f)
        // Always lands back inside [0, 1).
        for (i in 0..20) {
            val v = CymaticsMath.approachHue(i / 21f, ((i * 7) % 21) / 21f, 0.37f)
            assertTrue("hue $v escaped the wheel", v >= 0f && v < 1f)
        }
        // Half-way smoothing across the wrap stays on the short arc.
        val half = CymaticsMath.approachHue(0.95f, 0.05f, 0.5f)
        assertTrue("smoothing crossed the long way: $half", half >= 0.99f || half <= 0.01f)
    }

    // ------------------------------------------------------------ droplet bank

    @Test
    fun drops_spawn_on_strong_beats_and_decay_to_silence() {
        val drops = CymaticsDrops()
        repeat(30) { drops.update(dt, 0f) }
        assertTrue("drops rang with no beat", !drops.ringing)
        // A weak transient spawns nothing (texture stays texture)...
        drops.update(dt, CymaticsDrops.SPAWN_THRESHOLD * 0.5f)
        assertTrue(!drops.ringing)
        // ...a real beat spawns exactly one drop, and the refractory period
        // keeps the next frame's beat from machine-gunning the pool.
        drops.update(dt, 1f)
        assertEquals(1, activeCount(drops))
        drops.update(dt, 1f)
        assertEquals("cooldown did not hold", 1, activeCount(drops))
        // After the cooldown a second beat is a second drop.
        repeat((CymaticsDrops.COOLDOWN_SECONDS / dt).toInt() + 1) { drops.update(dt, 0f) }
        drops.update(dt, 1f)
        assertEquals(2, activeCount(drops))
        // Amplitudes decay to a true zero: the accumulator is bounded.
        repeat((20f / dt).toInt()) { drops.update(dt, 0f) }
        assertTrue("drops never fell silent", !drops.ringing)
        assertTrue(drops.packed.all { it == 0f || it.isFinite() })
    }

    @Test
    fun the_pool_recycles_its_oldest_slot_and_keeps_phases_wrapped() {
        val drops = CymaticsDrops()
        val tau = (2.0 * PI).toFloat()
        // Ten spawns through the cooldown: never more than SLOTS drops.
        repeat(10) {
            drops.update(dt, 1.2f)
            repeat((CymaticsDrops.COOLDOWN_SECONDS / dt).toInt() + 1) { drops.update(dt, 0f) }
            assertTrue(activeCount(drops) <= CymaticsDrops.SLOTS)
        }
        assertEquals(CymaticsDrops.SLOTS, activeCount(drops))
        for (i in 0 until CymaticsDrops.SLOTS) {
            val base = i * 4
            val phase = drops.packed[base + 2]
            assertTrue("drop $i phase $phase escaped [0, 2pi)", phase >= 0f && phase < tau)
            assertTrue("drop $i amplitude out of range", drops.packed[base + 3] in 0f..0.72f)
            assertTrue("drop $i landed off the field", abs(drops.packed[base]) <= CymaticsDrops.SPREAD)
        }
        drops.reset()
        assertTrue(!drops.ringing)
        assertTrue(drops.packed.all { it == 0f })
    }

    private fun activeCount(drops: CymaticsDrops): Int = (0 until CymaticsDrops.SLOTS).count { drops.packed[it * 4 + 3] > 0f }

    // ------------------------------------------------------------- mode morph

    @Test
    fun a_change_of_dominant_mode_slides_through_superposition() {
        // The nodal lines must SLIDE when the music moves to a new figure:
        // the old mode releases at the ring time constant while the new one
        // attacks, so intermediate frames render their superposition. A frame
        // where the old amplitude teleports would pop the whole picture.
        // Driven below the snapshot's sum-to-one budget, so the amplitudes
        // observed here are the resonators' own dynamics, not the loudness
        // normalization moving on top of them.
        val plate = CymaticsPlate()
        val bandCount = 64
        val low = FloatArray(bandCount).also { it[18] = 0.9f }
        val high = FloatArray(bandCount).also { it[46] = 0.9f }
        val ring = 0.6f
        val drive = 0.5f
        repeat(120) { plate.excite(low, dt, 110f, drive, ring, 0f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        assertTrue(plate.snapshot(CymaticsMath.MAX_RENDERED_MODES, out) > 0)
        val oldN = out[0]
        val oldM = out[1]
        val newMode =
            CymaticsMath.MODES[
                CymaticsMath.modeIndexFor(
                    CymaticsMath.wavenumberFor(CymaticsMath.bandCenterHz(46, bandCount), 110f),
                ),
            ]
        assertTrue("the test tones must land on different modes", newMode.n.toFloat() != oldN || newMode.m.toFloat() != oldM)

        var sawSuperposition = false
        var previousOld = amplitudeOf(plate, out, oldN, oldM)
        repeat(200) {
            plate.excite(high, dt, 110f, drive, ring, 0f)
            val oldAmp = amplitudeOf(plate, out, oldN, oldM)
            val newAmp = amplitudeOf(plate, out, newMode.n.toFloat(), newMode.m.toFloat())
            if (oldAmp > 0.12f && newAmp > 0.12f) sawSuperposition = true
            assertTrue(
                "the old figure dropped ${previousOld - oldAmp} in one frame - that is a pop, not a slide",
                previousOld - oldAmp < 0.1f,
            )
            previousOld = oldAmp
        }
        assertTrue("the old and new figures never coexisted - the change pops instead of morphing", sawSuperposition)
        assertTrue("the old figure never released", previousOld < 0.1f)
        assertTrue(
            "the new figure never stood up",
            amplitudeOf(plate, out, newMode.n.toFloat(), newMode.m.toFloat()) > 0.3f,
        )
    }

    private fun amplitudeOf(
        plate: CymaticsPlate,
        scratch: FloatArray,
        n: Float,
        m: Float,
    ): Float {
        val count = plate.snapshot(CymaticsMath.MAX_RENDERED_MODES, scratch)
        for (i in 0 until count) {
            if (scratch[i * 4] == n && scratch[i * 4 + 1] == m) return scratch[i * 4 + 2]
        }
        return 0f
    }
}
