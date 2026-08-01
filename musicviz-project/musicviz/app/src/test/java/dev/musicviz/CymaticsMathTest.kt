package dev.musicviz

import dev.musicviz.analysis.FftProcessor
import dev.musicviz.render.scene.CymaticsMath
import dev.musicviz.render.scene.CymaticsPlate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The CYMATICS style's gate: the plate has to be the sound, not a shape that
 * happens to move with it.
 *
 * Three families of claim are pinned here.
 *  1. The plate formula and its analytic gradient - the maths the vertex
 *     shader runs per vertex, which cannot be checked on a device without
 *     looking at it. The gradient is checked against finite differences of
 *     the height it is supposed to be the derivative of.
 *  2. The pitch -> figure mapping: the analyzer's log-spaced bands land on the
 *     frequencies they actually cover, and an octave moves the figure by the
 *     factor Chladni's law says it should.
 *  3. The resonator bank: a tone puts its OWN mode on the plate, the ring
 *     decays at the time constant the slider asks for, a loud mix cannot tear
 *     the surface apart, and silence leaves nothing ringing.
 */
class CymaticsMathTest {
    @Test
    fun no_mode_is_a_flat_plate() {
        // n == m makes the formula identically zero, and a mode that renders
        // nothing is silence wearing a mode's name.
        assertTrue("MODES must not contain n == m", CymaticsMath.MODES.none { it.n == it.m })
        assertTrue("MODES must not contain n < m", CymaticsMath.MODES.none { it.n < it.m })
        for (mode in CymaticsMath.MODES) {
            // Somewhere off the diagonals every mode has real displacement.
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

    @Test
    fun the_shader_gradient_matches_the_surface_it_shades() {
        // The vertex shader lights the plate from this gradient rather than
        // from a finite difference, so if the two disagree the surface is lit
        // as if it were a different surface.
        val modes = floatArrayOf(5f, 3f, 0.4f, 8f, 1f, 0.25f, 2f, 0f, 0.2f)
        val out = FloatArray(2)
        val h = 1e-3f
        for (i in -4..4) {
            for (j in -4..4) {
                val x = i / 5f
                val y = j / 5f
                CymaticsMath.surfaceGradient(modes, 3, x, y, out)
                val dx =
                    (
                        CymaticsMath.surfaceHeight(modes, 3, x + h, y) -
                            CymaticsMath.surfaceHeight(modes, 3, x - h, y)
                    ) / (2f * h)
                val dy =
                    (
                        CymaticsMath.surfaceHeight(modes, 3, x, y + h) -
                            CymaticsMath.surfaceHeight(modes, 3, x, y - h)
                    ) / (2f * h)
                assertEquals("d/dx at ($x, $y)", dx, out[0], 0.02f)
                assertEquals("d/dy at ($x, $y)", dy, out[1], 0.02f)
            }
        }
    }

    @Test
    fun band_centres_sit_inside_the_bands_the_analyzer_measures() {
        // CymaticsMath re-derives the band -> frequency map instead of calling
        // the analyzer, so this is the pin that keeps the two the same map -
        // including the bin quantization that makes the bottom of the range
        // linear rather than logarithmic (REFERENCE_FFT_BINS).
        val bandCount = 64
        val rate = 44_100
        val processor = FftProcessor(bandCount = bandCount)
        val edges = processor.bandEdges(rate)
        val mirrored = CymaticsMath.bandEdgeBins(bandCount)
        assertEquals("band edges drifted from FftProcessor", edges.toList(), mirrored.toList())
        val binHz = (rate / 2f) / (processor.fftSize / 2f)
        for (band in 0 until bandCount) {
            val center = CymaticsMath.bandCenterHz(band, bandCount)
            val low = edges[band] * binHz
            val high = (edges[band + 1] + 1) * binHz
            assertTrue("band $band centre $center Hz outside [$low, $high]", center in low..high)
        }
        // The bass really is where the analyzer says it is, not where the
        // logarithm alone would put it.
        assertTrue("band 12 sits below its measured range", CymaticsMath.bandCenterHz(12, bandCount) > 200f)
    }

    @Test
    fun an_octave_moves_the_figure_by_the_square_root_of_two() {
        // Chladni's law for a stiff plate: frequency goes as the SQUARE of the
        // order, so doubling the pitch makes the figure sqrt(2) finer - the
        // whole reason a nine-octave spectrum fits on one plate.
        val low = CymaticsMath.wavenumberFor(110f, 110f)
        val high = CymaticsMath.wavenumberFor(220f, 110f)
        assertEquals(1f, low, 1e-4f)
        assertEquals(sqrt(2f), high / low, 1e-4f)
        // The fundamental IS the mode-(1,0) pitch, whatever it is set to.
        assertEquals(1f, CymaticsMath.wavenumberFor(300f, 300f), 1e-4f)
    }

    @Test
    fun the_whole_spectrum_lands_on_the_plate() {
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
    fun a_tone_puts_its_own_figure_on_the_plate() {
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
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
        val count = plate.snapshot(4, 1f, out)
        assertTrue("a sustained tone left the plate silent", count > 0)
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
            val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
            plate.snapshot(1, 1f, out)
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
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
        plate.snapshot(1, 1f, out)
        val struck = out[2]
        assertTrue("the plate never rang", struck > 0.05f)
        // Silence for exactly one time constant: a one-pole decay leaves 1/e.
        val silence = FloatArray(64)
        val steps = (ring * 60f).toInt()
        repeat(steps) { plate.excite(silence, 1f / 60f, 110f, 1f, ring, 0f) }
        plate.snapshot(1, 1f, out)
        assertEquals("decay is not the requested time constant", struck / E.toFloat(), out[2], struck * 0.06f)
    }

    @Test
    fun silence_leaves_nothing_ringing() {
        val plate = CymaticsPlate()
        val bands = FloatArray(64) { 0.7f }
        repeat(30) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.2f, 0f) }
        val silence = FloatArray(64)
        repeat(600) { plate.excite(silence, 1f / 60f, 110f, 1f, 0.2f, 0f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
        assertEquals("a silent plate still has modes to draw", 0, plate.snapshot(8, 1f, out))
        assertTrue(!plate.ringing)
    }

    @Test
    fun a_loud_mix_cannot_tear_the_surface_apart() {
        // Every band at once: without normalization eight modes of full
        // displacement stack, and the plate leaves the screen.
        val plate = CymaticsPlate()
        val bands = FloatArray(64) { 1f }
        repeat(120) { plate.excite(bands, 1f / 60f, 110f, 4f, 0.5f, 0f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
        val count = plate.snapshot(CymaticsMath.MAX_RENDERED_MODES, 1f, out)
        assertEquals(CymaticsMath.MAX_RENDERED_MODES, count)
        var total = 0f
        for (i in 0 until count) total += out[i * 3 + 2]
        assertTrue("summed amplitude $total exceeds the relief budget", total <= 1.0001f)
        // ... and the figure is still the superposition, not one flat mode.
        assertTrue("normalization collapsed the quiet modes", out[(count - 1) * 3 + 2] > 0f)
    }

    @Test
    fun the_snapshot_is_ordered_loudest_first_and_bounded() {
        val plate = CymaticsPlate()
        val bands = FloatArray(64) { i -> if (i % 7 == 0) 0.9f else 0.15f }
        repeat(90) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.4f, 0.5f) }
        val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
        val count = plate.snapshot(99, 1f, out)
        assertTrue("snapshot wrote more modes than the shader can read", count <= CymaticsMath.MAX_RENDERED_MODES)
        for (i in 1 until count) {
            assertTrue("snapshot is not ordered loudest first", out[i * 3 + 2] <= out[(i - 1) * 3 + 2] + 1e-6f)
        }
    }

    @Test
    fun tonal_focus_hands_the_plate_to_the_peaks() {
        // A bass-heavy bed with a quiet high tone on top: raw energy answers
        // the bed, focus answers the note. This is the control that lets a
        // melody put a fine figure on the plate at all.
        val bands = FloatArray(64)
        for (i in 0 until 12) bands[i] = 0.75f
        bands[46] = 0.55f

        fun dominantWavenumberAt(focus: Float): Float {
            val plate = CymaticsPlate()
            repeat(90) { plate.excite(bands, 1f / 60f, 110f, 1f, 0.3f, focus) }
            val out = FloatArray(CymaticsMath.MAX_RENDERED_MODES * 3)
            plate.snapshot(1, 1f, out)
            return sqrt(out[0] * out[0] + out[1] * out[1])
        }
        assertTrue("tonal focus did not move the plate onto the peak", dominantWavenumberAt(1f) > dominantWavenumberAt(0f))
    }

    @Test
    fun the_surface_oscillates_without_ever_moving_the_figure() {
        // The physical claim the whole style rests on: a driven plate's modes
        // move in phase, so the vibration scales the height and leaves the
        // nodal lines - where the height is zero - exactly where they were.
        val modes = floatArrayOf(5f, 3f, 0.5f, 4f, 1f, 0.3f)
        for (i in 0..12) {
            val phase = i * 0.5f
            val factor = CymaticsMath.vibrationFactor(phase, 1f)
            for (j in -6..6) {
                val t = j / 6f
                // On a nodal line the surface stays put whatever the phase.
                assertEquals(0f, CymaticsMath.surfaceHeight(modes, 2, t, t) * factor, 1e-5f)
            }
        }
        // Depth 0 freezes the plate at full relief; nothing ever amplifies it.
        for (i in 0..20) {
            val phase = i * 0.31f
            assertEquals(1f, CymaticsMath.vibrationFactor(phase, 0f), 1e-6f)
            assertTrue(abs(CymaticsMath.vibrationFactor(phase, 1f)) <= 1f + 1e-6f)
        }
    }

    @Test
    fun the_visible_vibration_stays_under_the_flashing_band() {
        // Visual safety: a real plate vibrates in the hundreds of hertz. This
        // is that motion strobed down, and it must stay well under the WCAG
        // three-flashes-per-second threshold at every mode order.
        assertTrue(CymaticsMath.MAX_VIBRATION_HZ < 3f)
        for (order in 0..40) {
            val hz = CymaticsMath.vibrationHz(order.toFloat())
            assertTrue("vibration at order $order is $hz Hz", hz in CymaticsMath.MIN_VIBRATION_HZ..CymaticsMath.MAX_VIBRATION_HZ)
        }
        // Finer figures move faster than coarse ones, inside that band.
        assertTrue(CymaticsMath.vibrationHz(10f) > CymaticsMath.vibrationHz(2f))
    }

    @Test
    fun the_ring_slider_spans_its_documented_range() {
        assertEquals(CymaticsMath.MIN_RING_SECONDS, CymaticsMath.ringSeconds(0f), 1e-6f)
        assertEquals(CymaticsMath.MAX_RING_SECONDS, CymaticsMath.ringSeconds(1f), 1e-6f)
        assertEquals(CymaticsMath.MAX_RING_SECONDS, CymaticsMath.ringSeconds(9f), 1e-6f)
    }
}
