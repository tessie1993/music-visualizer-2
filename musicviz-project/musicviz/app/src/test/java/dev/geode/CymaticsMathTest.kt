package dev.geode

import dev.geode.engine.audio.LogBands
import dev.geode.render.scene.CymaticsMath
import dev.geode.render.scene.CymaticsPlate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The CYMATICS style's gate: the field on screen has to be the sound, not a
 * shape that happens to move with it.
 *
 * Four families of claim are pinned here.
 *  1. The two field formulas - the square plate's Chladni term and the round
 *     dish's Bessel modes - which is maths the fragment shader runs per pixel
 *     and nothing on a device can check by looking at it.
 *  2. The pitch -> figure mapping: the analyzer's log-spaced bands land on the
 *     frequencies they actually cover, and an octave moves the figure by the
 *     factor Chladni's law says it should.
 *  3. The resonator bank: a tone puts its OWN mode on the field, the ring
 *     decays at the time constant the slider asks for, a loud mix cannot blow
 *     the field out, and silence leaves nothing ringing.
 *  4. Visual safety: the phases that keep the field moving stay well under the
 *     flashing band whatever the music does.
 */
class CymaticsMathTest {
    /** Modes as the shader takes them: (n, m, amplitude, phase) quads. */
    private fun modes(vararg quads: Float) = quads

    @Test
    fun no_mode_is_a_flat_plate() {
        // n == m makes the plate formula identically zero, and a mode that
        // renders nothing is silence wearing a mode's name.
        assertTrue("MODES must not contain n == m", CymaticsMath.MODES.none { it.n == it.m })
        assertTrue("MODES must not contain n < m", CymaticsMath.MODES.none { it.n < it.m })
        for (mode in CymaticsMath.MODES) {
            val peak =
                (0..40).maxOf { i ->
                    val x = -1f + i / 20f
                    (0..40).maxOf { j -> abs(CymaticsMath.modeHeight(mode.n, mode.m, x, -0.97f + j / 20.5f)) }
                }
            assertTrue("mode (${mode.n}, ${mode.m}) is flat everywhere", peak > 0.05f)
        }
    }

    @Test
    fun modes_are_ordered_from_coarsest_to_finest() {
        // modeIndexFor walks the table and stops as soon as the gap grows, so
        // an unsorted table would silently return the wrong mode.
        val wavenumbers = CymaticsMath.MODES.map { it.wavenumber }
        assertEquals(wavenumbers.sorted(), wavenumbers)
    }

    @Test
    fun the_plate_is_antisymmetric_and_nodal_on_its_diagonals() {
        for (mode in listOf(CymaticsMath.Mode(5, 3), CymaticsMath.Mode(9, 2), CymaticsMath.Mode(2, 0))) {
            for (i in -8..8) {
                val t = i / 8f
                // Swapping (n, m) inverts the figure: this is why the table
                // enumerates one of each pair.
                assertEquals(
                    -CymaticsMath.modeHeight(mode.m, mode.n, t, 0.31f),
                    CymaticsMath.modeHeight(mode.n, mode.m, t, 0.31f),
                    1e-5f,
                )
                // Both diagonals are nodal lines for every (n, m) - the sand
                // has to sit somewhere even for a single mode.
                assertEquals(0f, CymaticsMath.modeHeight(mode.n, mode.m, t, t), 1e-5f)
                assertEquals(0f, CymaticsMath.modeHeight(mode.n, mode.m, t, -t), 1e-5f)
            }
        }
    }

    /** The real J_m, by its series, for checking the shader's cheap version. */
    private fun besselExact(
        m: Int,
        x: Double,
    ): Double {
        var term = 1.0
        for (k in 1..m) term *= (x / 2.0) / k
        var sum = term
        for (k in 1..60) {
            term *= -(x * x / 4.0) / (k.toDouble() * (k + m))
            sum += term
        }
        return sum
    }

    @Test
    fun the_dish_rings_where_the_real_bessel_function_does() {
        // The dish's rings ARE the zeros of J_m, so the cheap asymptotic form
        // is only allowed to be cheap about amplitude - never about where the
        // rings fall. Compared over the range a dish actually shows.
        for (m in 0..6) {
            val exactZeros = zerosOf(20.0) { x -> besselExact(m, x) }
            val approxZeros = zerosOf(20.0) { x -> CymaticsMath.besselApprox(m.toFloat(), x.toFloat()).toDouble() }
            val fromCore = m + 1.0
            val exact = exactZeros.filter { it > fromCore }
            val approx = approxZeros.filter { it > fromCore }
            assertTrue("J_$m: found ${approx.size} rings against ${exact.size}", approx.size >= exact.size - 1)
            for ((i, z) in exact.withIndex()) {
                if (i >= approx.size) break
                assertEquals("J_$m ring $i", z, approx[i], 0.12)
            }
        }
    }

    @Test
    fun the_centre_of_the_dish_belongs_to_the_lowest_mode_alone() {
        // J_0 peaks AT the centre and every higher order vanishes there. Get
        // this wrong in the shader's core factor and a black hole appears in
        // the middle of the dish (it did).
        //
        // The asymptotic form is about WHERE the rings fall, not about exact
        // amplitude at the origin - which is the one place the expansion is
        // formally invalid - so this asks for "strong", not for J_0(0) = 1.
        assertTrue("J_0 must be at its strongest in the centre", abs(CymaticsMath.besselApprox(0f, 0f)) > 0.7f)
        assertTrue(
            "J_0 must not be beaten at the centre by its own first ring",
            abs(CymaticsMath.besselApprox(0f, 0f)) > abs(CymaticsMath.besselApprox(0f, 4f)),
        )
        for (m in 1..8) {
            assertEquals("J_$m must vanish at the centre", 0f, CymaticsMath.besselApprox(m.toFloat(), 0f), 0.02f)
        }
    }

    @Test
    fun a_dish_mode_has_the_rotational_symmetry_of_its_angular_order() {
        // A single circular mode is invariant under a turn of 2*pi/m - that
        // symmetry is what makes the figure read as a cymatic pattern rather
        // than as noise, and it comes straight out of the cos(m*a) factor.
        for (mode in listOf(CymaticsMath.Mode(7, 3), CymaticsMath.Mode(9, 5), CymaticsMath.Mode(4, 2))) {
            val m = CymaticsMath.angularOrder(mode)
            val quad = modes(mode.n.toFloat(), mode.m.toFloat(), 0.6f, 0.4f)
            val turn = 2.0 * Math.PI / m
            for (i in 1..7) {
                val r = i / 8f
                for (j in 0..5) {
                    val a = j * 0.7
                    val here = CymaticsMath.dishHeight(quad, 1, (r * cos(a)).toFloat(), (r * sin(a)).toFloat())
                    val turned =
                        CymaticsMath.dishHeight(
                            quad,
                            1,
                            (r * cos(a + turn)).toFloat(),
                            (r * sin(a + turn)).toFloat(),
                        )
                    assertEquals("mode $mode is not $m-fold symmetric", here, turned, 1e-4f)
                }
            }
        }
    }

    @Test
    fun the_two_geometries_read_the_same_mode_table() {
        // One table, one resonator bank, one pitch -> figure law, whichever
        // geometry is being drawn: the dish just reads (n, m) as (radial,
        // angular) instead of as the plate's two orders.
        for (mode in CymaticsMath.MODES) {
            assertEquals(mode.m, CymaticsMath.angularOrder(mode))
            assertTrue("radial order must be at least 1", CymaticsMath.radialOrder(mode) >= 1)
            // McMahon's expansion, the same one the shader computes.
            val expected = Math.PI.toFloat() * (CymaticsMath.radialOrder(mode) + 0.5f * CymaticsMath.angularOrder(mode) - 0.25f)
            assertEquals(expected, CymaticsMath.dishBeta(mode), 1e-4f)
        }
        // Finer modes ring at a higher radial wavenumber, so a higher note
        // really does draw a finer figure on the dish as well as on the plate.
        assertTrue(CymaticsMath.dishBeta(CymaticsMath.Mode(9, 2)) > CymaticsMath.dishBeta(CymaticsMath.Mode(3, 1)))
    }

    @Test
    fun band_centres_sit_inside_the_bands_the_analyzer_measures() {
        // CymaticsMath re-derives the band -> frequency map instead of holding
        // an analyzer, so this is the pin that keeps the two the same map.
        val bandCount = 64
        val bander = LogBands(bandCount, fftSize = 2048, sampleRateHz = 44_100)
        for (band in 0 until bandCount) {
            val centre = CymaticsMath.bandCenterHz(band, bandCount)
            val low = bander.lowerHz(band)
            val high = bander.upperHz(band)
            assertTrue("band $band centre $centre Hz outside [$low, $high]", centre in low..high)
        }
    }

    @Test
    fun an_octave_moves_the_figure_by_the_square_root_of_two() {
        // Chladni's law for a stiff plate: frequency goes as the SQUARE of the
        // order, so doubling the pitch makes the figure sqrt(2) finer - the
        // whole reason a nine-octave spectrum fits on one field.
        val low = CymaticsMath.wavenumberFor(110f, 110f)
        val high = CymaticsMath.wavenumberFor(220f, 110f)
        assertEquals(1f, low, 1e-4f)
        assertEquals(sqrt(2f), high / low, 1e-4f)
        // The fundamental IS the mode-(1,0) pitch, whatever it is set to.
        assertEquals(1f, CymaticsMath.wavenumberFor(300f, 300f), 1e-4f)
    }

    @Test
    fun the_whole_spectrum_lands_on_the_field() {
        // Every band the analyzer produces has to map to a real mode at both
        // ends of the fundamental slider - otherwise the top of the spectrum
        // silently piles onto the finest mode and every bright sound looks
        // identical.
        for (f0 in listOf(CymaticsMath.MIN_FUNDAMENTAL_HZ, 110f, CymaticsMath.MAX_FUNDAMENTAL_HZ)) {
            val map = CymaticsMath.bandModeMap(64, f0)
            assertEquals(64, map.size)
            assertTrue("modes out of range for f0 = $f0", map.all { it in CymaticsMath.MODES.indices })
            // The map has to be monotone: higher band, no coarser a figure.
            val wavenumbers = map.map { CymaticsMath.MODES[it].wavenumber }
            assertEquals("band -> mode is not monotone at f0 = $f0", wavenumbers.sorted(), wavenumbers)
            assertNotEquals("f0 = $f0 collapses the spectrum onto one mode", map.first(), map.last())
        }
    }

    @Test
    fun a_tone_puts_its_own_figure_on_the_field() {
        val plate = CymaticsPlate()
        val bandCount = 64
        val bands = FloatArray(bandCount)
        val toneBand = 34
        // A single peak, as a sustained tone reaches the analyzer.
        for (i in bands.indices) {
            val d = (i - toneBand) / 1.5f
            bands[i] = 0.8f * exp(-d * d)
        }
        repeat(60) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.4f, 1f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        val count = plate.snapshot(4, out)
        assertTrue("a sustained tone left the field silent", count > 0)
        val expected = CymaticsMath.modeIndexFor(CymaticsMath.wavenumberFor(CymaticsMath.bandCenterHz(toneBand, bandCount), 110f))
        val loudest = CymaticsMath.MODES[expected]
        assertEquals("the loudest figure is not the tone's own", loudest.n.toFloat(), out[0], 0f)
        assertEquals(loudest.m.toFloat(), out[1], 0f)
    }

    @Test
    fun a_higher_tone_draws_a_finer_figure() {
        fun figureFor(band: Int): Float {
            val plate = CymaticsPlate()
            val bands = FloatArray(64)
            bands[band] = 0.9f
            repeat(60) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.4f, 0f) }
            val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
            plate.snapshot(1, out)
            return sqrt(out[0] * out[0] + out[1] * out[1])
        }
        assertTrue("a higher tone must not draw a coarser figure", figureFor(48) > figureFor(20))
    }

    @Test
    fun the_ring_decays_at_the_time_constant_the_slider_asks_for() {
        val plate = CymaticsPlate()
        val bands = FloatArray(64)
        bands[30] = 1f
        val ring = CymaticsMath.ringSeconds(0.5f)
        repeat(120) { plate.excite(bands, 1f / 60f, 110f, 1f, ring, 0f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        plate.snapshot(1, out)
        val struck = out[2]
        assertTrue("the field never rang", struck > 0.05f)
        // Silence for exactly one time constant: a one-pole decay leaves 1/e.
        val silence = FloatArray(64)
        val steps = (ring * 60f).toInt()
        repeat(steps) { plate.excite(silence, 1f / 60f, 110f, 1f, ring, 0f) }
        plate.snapshot(1, out)
        assertEquals("decay is not the requested time constant", struck / E.toFloat(), out[2], struck * 0.06f)
    }

    @Test
    fun silence_leaves_nothing_ringing() {
        val plate = CymaticsPlate()
        val bands = FloatArray(64) { 0.7f }
        repeat(30) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.2f, 0f) }
        val silence = FloatArray(64)
        repeat(600) { plate.excite(silence, 1f / 60f, 110f, 1f, 0.2f, 0f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        assertEquals("a silent field still has modes to draw", 0, plate.snapshot(8, out))
        assertTrue(!plate.ringing)
    }

    @Test
    fun a_loud_mix_cannot_blow_the_field_out() {
        // Every band at once: without normalization eight modes of full
        // displacement stack and the shading saturates to white.
        val plate = CymaticsPlate()
        val bands = FloatArray(64) { 1f }
        repeat(120) { plate.excite(bands, 1f / 60f, 110f, 4f, 0.5f, 0f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        val count = plate.snapshot(CymaticsMath.MAX_RENDERED_MODES, out)
        assertEquals(CymaticsMath.MAX_RENDERED_MODES, count)
        var total = 0f
        for (i in 0 until count) total += out[i * 4 + 2]
        assertTrue("summed amplitude $total exceeds the budget", total <= 1.0001f)
        // ... and the figure is still the superposition, not one flat mode.
        assertTrue("normalization collapsed the quiet modes", out[(count - 1) * 4 + 2] > 0f)
    }

    @Test
    fun the_snapshot_is_ordered_loudest_first_and_bounded() {
        val plate = CymaticsPlate()
        val bands = FloatArray(64) { i -> if (i % 7 == 0) 0.9f else 0.15f }
        repeat(90) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.4f, 0.5f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        val count = plate.snapshot(99, out)
        assertTrue("snapshot wrote more modes than the shader can read", count <= CymaticsMath.MAX_RENDERED_MODES)
        for (i in 1 until count) {
            assertTrue("snapshot is not ordered loudest first", out[i * 4 + 2] <= out[(i - 1) * 4 + 2] + 1e-6f)
        }
    }

    @Test
    fun tonal_focus_hands_the_field_to_the_peaks() {
        // A bass-heavy bed with a quiet high tone on top: raw energy answers
        // the bed, focus answers the note. This is the control that lets a
        // melody put a fine figure on the field at all.
        val bands = FloatArray(64)
        for (i in 0 until 12) bands[i] = 0.75f
        bands[46] = 0.55f

        fun dominantWavenumberAt(focus: Float): Float {
            val plate = CymaticsPlate()
            repeat(90) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.3f, focus) }
            val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
            plate.snapshot(1, out)
            return sqrt(out[0] * out[0] + out[1] * out[1])
        }
        assertTrue("tonal focus did not move the field onto the peak", dominantWavenumberAt(1f) > dominantWavenumberAt(0f))
    }

    @Test
    fun every_ringing_mode_keeps_its_own_phase() {
        // The phases are what keep the field flowing while a note is held, and
        // they belong to the MODE rather than to its slot in the rendered set:
        // a mode that drops out of the loudest handful and comes back has to
        // return where it would have been, or the figure jumps.
        val plate = CymaticsPlate()
        val bands = FloatArray(64)
        bands[20] = 0.9f
        bands[44] = 0.6f
        repeat(30) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.5f, 0f) }
        val before = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        val count = plate.snapshot(2, before)
        assertEquals(2, count)
        val dt = 0.5f
        plate.advancePhases(dt, 1f)
        val after = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 4)
        plate.snapshot(2, after)
        for (i in 0 until count) {
            val mode = CymaticsMath.Mode(after[i * 4].toInt(), after[i * 4 + 1].toInt())
            val expected = CymaticsMath.vibrationHz(mode.wavenumber) * 2f * Math.PI.toFloat() * dt
            val moved = after[i * 4 + 3] - before[i * 4 + 3]
            assertEquals("mode $mode did not advance at its own rate", expected, moved, 1e-3f)
        }
        // Finer figures move faster than coarse ones - that is the point.
        assertTrue(after[7] - before[7] != after[3] - before[3])
    }

    @Test
    fun the_field_moves_at_a_rate_that_stays_under_the_flashing_band() {
        // Visual safety: a real plate vibrates in the hundreds of hertz. The
        // phases are that motion strobed down, and they must stay well under
        // the WCAG three-flashes-per-second threshold at every mode order.
        assertTrue(CymaticsMath.MAX_VIBRATION_HZ < 3f)
        for (order in 0..40) {
            val hz = CymaticsMath.vibrationHz(order.toFloat())
            assertTrue("phase rate at order $order is $hz Hz", hz in CymaticsMath.MIN_VIBRATION_HZ..CymaticsMath.MAX_VIBRATION_HZ)
        }
        assertTrue(CymaticsMath.vibrationHz(10f) > CymaticsMath.vibrationHz(2f))
    }

    @Test
    fun the_ring_slider_spans_its_documented_range() {
        assertEquals(CymaticsMath.MIN_RING_SECONDS, CymaticsMath.ringSeconds(0f), 1e-6f)
        assertEquals(CymaticsMath.MAX_RING_SECONDS, CymaticsMath.ringSeconds(1f), 1e-6f)
        assertEquals(CymaticsMath.MAX_RING_SECONDS, CymaticsMath.ringSeconds(9f), 1e-6f)
    }

    /** Sign changes of [f] over (0, limit], by fine scan then bisection. */
    private fun zerosOf(
        limit: Double,
        f: (Double) -> Double,
    ): List<Double> {
        val zeros = mutableListOf<Double>()
        val step = 0.01
        var x = step
        var prev = f(x)
        while (x < limit) {
            val next = x + step
            val here = f(next)
            if (prev != 0.0 && here != 0.0 && (prev < 0) != (here < 0)) {
                var lo = x
                var hi = next
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if ((f(lo) < 0) != (f(mid) < 0)) hi = mid else lo = mid
                }
                zeros += (lo + hi) / 2
            }
            prev = here
            x = next
        }
        return zeros
    }
}
