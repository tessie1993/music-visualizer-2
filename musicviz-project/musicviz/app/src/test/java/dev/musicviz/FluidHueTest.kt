package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidEmitters
import dev.musicviz.render.fluid.FluidHue
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Headless gate for the fluid scenes' colour wiring. Two shipped bugs it
 * guards, both reported as "customizations don't work on the fluid styles":
 *
 * 1. The palette's SPAN multiplier was dropped - FluidScene passed the raw
 *    Hue range slider where WaterScene passes `hueRange * paletteRange`. With
 *    the span gone, Fire (0.14) and Aurora (0.7) both spanned the whole wheel
 *    and every palette produced the same look in a different tint.
 * 2. `colorShift` (the "Hue shift" slider) was read by no fluid scene at all,
 *    so the slider was inert on FLUID while it worked everywhere else.
 *
 * The emitter assertions close the loop: they run the real, GL-free splat
 * scheduler and check the dye colours actually move.
 */
class FluidHueTest {
    private val dt = 1f / 60f

    private fun features(beat: Boolean = false) =
        AudioFeatures(
            bands = FloatArray(16) { it / 16f },
            waveform = FloatArray(64),
            rms = 0.4f,
            bass = 0.4f,
            mid = 0.3f,
            treble = 0.05f,
            beat = beat,
        )

    /** Dye colours of one beat's splats, from the real emitter scheduler. */
    private fun beatColors(
        base: Float,
        span: Float,
    ): List<Triple<Float, Float, Float>> {
        val e =
            FluidEmitters(Random(11)).apply {
                stirrers = 0
                sparkle = false
                bassPump = false
                beatSplats = 4
                beatPattern = FluidEmitters.PATTERN_RING
                paletteCycleSpeed = 0f
            }
        e.tick(features(), dt, 1.6f, base, span)
        return e.tick(features(beat = true), dt, 1.6f, base, span).map { Triple(it.r, it.g, it.b) }
    }

    private fun maxChannelDelta(
        a: List<Triple<Float, Float, Float>>,
        b: List<Triple<Float, Float, Float>>,
    ): Float {
        assertEquals(a.size, b.size)
        var d = 0f
        for (i in a.indices) {
            d = maxOf(d, abs(a[i].first - b[i].first), abs(a[i].second - b[i].second), abs(a[i].third - b[i].third))
        }
        return d
    }

    @Test
    fun spanFoldsInThePalettesOwnWidth() {
        // The bug: span() returning hueRange alone made these two identical.
        val fire = SceneParams(palette = 2)
        val aurora = SceneParams(palette = 7)
        assertEquals(0.14f, fire.paletteRange, 1e-6f)
        assertEquals(0.7f, aurora.paletteRange, 1e-6f)
        val fireSpan = FluidHue.span(fire.hueRange, fire.paletteRange)
        val auroraSpan = FluidHue.span(aurora.hueRange, aurora.paletteRange)
        assertEquals(0.14f, fireSpan, 1e-6f)
        assertEquals(0.7f, auroraSpan, 1e-6f)
        assertTrue("the palette span must survive the wiring", fireSpan != auroraSpan)
        // WaterScene's form, which the fluid scenes must now match exactly.
        for (p in SceneParams.PALETTES.indices) {
            for (range in listOf(0f, 0.05f, 0.3f, 1f)) {
                val sp = SceneParams(palette = p, hueRange = range)
                assertEquals(
                    sp.hueRange.coerceIn(0.1f, 1f) * sp.paletteRange,
                    FluidHue.span(sp.hueRange, sp.paletteRange),
                    1e-6f,
                )
            }
        }
    }

    @Test
    fun hueRangeSliderStillScalesTheSpan() {
        val palRange = SceneParams(palette = 7).paletteRange
        assertEquals(0.7f, FluidHue.span(1f, palRange), 1e-6f)
        assertEquals(0.35f, FluidHue.span(0.5f, palRange), 1e-6f)
        // Below the shared 0.1 floor the span clamps instead of collapsing to
        // a single flat colour.
        assertEquals(FluidHue.MIN_HUE_RANGE * palRange, FluidHue.span(0f, palRange), 1e-6f)
        assertEquals(FluidHue.MIN_HUE_RANGE * palRange, FluidHue.span(-3f, palRange), 1e-6f)
        // A span is a fraction of the wheel: never negative, never over 1.
        assertEquals(0f, FluidHue.span(1f, -1f), 1e-6f)
        assertEquals(1f, FluidHue.span(1f, 5f), 1e-6f)
    }

    @Test
    fun baseFoldsInTheHueShiftSliderAndWraps() {
        // Same convention as ParticleSceneBase/ShaderScene: base + colorShift.
        assertEquals(0.45f, FluidHue.base(0.45f, 0f), 1e-6f)
        assertEquals(0.7f, FluidHue.base(0.45f, 0.25f), 1e-6f)
        // The slider is 0..1 and LFOs can push past it: wrap, never clamp,
        // so a full sweep travels the wheel once and lands back where it was.
        assertEquals(0.2f, FluidHue.base(0.9f, 0.3f), 1e-6f)
        assertEquals(0.45f, FluidHue.base(0.45f, 1f), 1e-6f)
        assertEquals(0.35f, FluidHue.base(0.45f, -0.1f), 1e-6f)
        assertEquals(0.45f, FluidHue.base(0.45f, -3f), 1e-5f)
        for (shift in listOf(-2.5f, -0.4f, 0f, 0.33f, 1f, 4.75f)) {
            val h = FluidHue.base(0.45f, shift)
            assertTrue("hue $h out of [0,1)", h >= 0f && h < 1f)
        }
    }

    @Test
    fun wrapKeepsHuesInTheUnitInterval() {
        for (h in listOf(-7.3f, -1f, -0.001f, 0f, 0.5f, 0.999f, 1f, 12.25f)) {
            val w = FluidHue.wrap01(h)
            assertTrue("wrap01($h) = $w out of [0,1)", w >= 0f && w < 1f)
        }
        assertEquals(0.25f, FluidHue.wrap01(12.25f), 1e-4f)
        assertEquals(0.75f, FluidHue.wrap01(-0.25f), 1e-6f)
    }

    @Test
    fun customPaletteOverridesFlowThroughUnchanged() {
        // The custom-palette hook is transparent: no special-casing needed in
        // the fluid scenes, a user palette just arrives as base/range.
        val p =
            SceneParams(
                palette = 2,
                paletteBaseOverride = 0.42f,
                paletteRangeOverride = 0.6f,
                colorShift = 0.1f,
            )
        assertEquals(0.52f, FluidHue.base(p.paletteBase, p.colorShift), 1e-6f)
        assertEquals(0.6f, FluidHue.span(p.hueRange, p.paletteRange), 1e-6f)
    }

    @Test
    fun palettesWithDifferentSpansEmitDifferentDyeColours() {
        val fire = SceneParams(palette = 2)
        val aurora = SceneParams(palette = 7)
        // Isolate the span: same base hue, only the palette's width differs.
        val base = 0.45f
        val a = beatColors(base, FluidHue.span(fire.hueRange, fire.paletteRange))
        val b = beatColors(base, FluidHue.span(aurora.hueRange, aurora.paletteRange))
        assertTrue("beat must emit splats to compare", a.isNotEmpty())
        assertTrue(
            "narrow and wide palettes must not paint the same dye",
            maxChannelDelta(a, b) > 0.05f,
        )
    }

    @Test
    fun theHueShiftSliderMovesTheDye() {
        val p = SceneParams(palette = 7)
        val span = FluidHue.span(p.hueRange, p.paletteRange)
        val off = beatColors(FluidHue.base(p.paletteBase, 0f), span)
        val shifted = beatColors(FluidHue.base(p.paletteBase, 0.33f), span)
        assertTrue(
            "Hue shift must repaint the fluid dye",
            maxChannelDelta(off, shifted) > 0.05f,
        )
        // A full turn of the slider is a no-op, not a jump to a wrapped edge.
        val full = beatColors(FluidHue.base(p.paletteBase, 1f), span)
        assertTrue(maxChannelDelta(off, full) < 1e-4f)
    }
}
