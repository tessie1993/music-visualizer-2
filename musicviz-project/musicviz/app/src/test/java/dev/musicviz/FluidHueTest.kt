package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.CompositeGrade
import dev.musicviz.render.fluid.FluidEmitters
import dev.musicviz.render.fluid.FluidHue
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Headless gate for the fluid scenes' colour wiring, and for the division of
 * labour that keeps every colour control applied EXACTLY ONCE:
 *
 * - the SCENE owns palette identity (base hue + span) because dye colour is
 *   decided at emission time. The span used to be dropped - FluidScene passed
 *   the raw Hue range slider where WaterScene passes `hueRange * paletteRange`
 *   - so Fire (0.14) and Aurora (0.7) both spanned the whole wheel and every
 *   palette produced the same look in a different tint;
 * - the COMPOSITE owns hue rotation. `VisualizerRenderer` uploads
 *   `uPostHue = colorShift + cyclePhase` for every scene that doesn't grade
 *   itself, which is this whole family. The fluid scenes briefly folded
 *   `colorShift` into their base hue as well, and the emitters were fed
 *   `cycleSpeed * 20` on top of the composite's integrated phase; each of
 *   those turned the wheel twice per slider unit ("Hue shift overshoots").
 *
 * The emitter assertions close the loop: they run the real, GL-free splat
 * scheduler, push its dye through the composite's own hue-rotation mirror and
 * measure how far round the wheel the finished pixel actually travelled.
 */
class FluidHueTest {
    private val dt = 1f / 60f

    /** A mid-wheel palette identity, for the drift measurements. */
    private val refBase = 0.45f
    private val refSpan = 0.7f

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

    /** Beat-only emitter, so the dye under test is the beat splats' alone. */
    private fun emitters(cycle: Float) =
        FluidEmitters(Random(11)).apply {
            stirrers = 0
            sparkle = false
            bassPump = false
            beatSplats = 4
            beatPattern = FluidEmitters.PATTERN_RING
            paletteCycleSpeed = cycle
        }

    /** Dye colours of one beat's splats, from the real emitter scheduler. */
    private fun beatColors(
        base: Float,
        span: Float,
    ): List<Triple<Float, Float, Float>> {
        val e = emitters(0f)
        e.tick(features(), dt, 1.6f, base, span)
        return e.tick(features(beat = true), dt, 1.6f, base, span).map { Triple(it.r, it.g, it.b) }
    }

    /** The composite pass' hue rotation (`uPostHue`), on one dye colour. */
    private fun composite(
        c: Triple<Float, Float, Float>,
        hue: Float,
    ): Triple<Float, Float, Float> {
        val out = CompositeGrade.hueRotate(floatArrayOf(c.first, c.second, c.third), hue)
        return Triple(out[0], out[1], out[2])
    }

    /**
     * The whole colour path of one fluid frame: the scene emits dye from
     * palette identity only ([FluidHue.base] / [FluidHue.span], as FluidScene
     * calls them), then the composite rotates the frame by
     * `colorShift + cyclePhase` - VisualizerRenderer's `uPostHue`.
     */
    private fun pipelineColors(
        p: SceneParams,
        cyclePhase: Float = 0f,
    ): List<Triple<Float, Float, Float>> =
        beatColors(FluidHue.base(p.paletteBase), FluidHue.span(p.hueRange, p.paletteRange))
            .map { composite(it, p.colorShift + cyclePhase) }

    /**
     * Hue angle in turns, measured in the plane orthogonal to the grey axis -
     * the exact plane `CompositeGrade.hueRotate` spins in, so rotating by `x`
     * turns moves this by `x`. Scale-invariant, so the emitters' dye gain and
     * the beat envelope don't disturb it.
     */
    private fun hueTurns(c: Triple<Float, Float, Float>): Float {
        val angle = atan2(sqrt(3f) * (c.second - c.third), 2f * c.first - c.second - c.third)
        return FluidHue.wrap01(angle / (2f * PI.toFloat()))
    }

    /** How far round the wheel [to] sits from [from], in turns of [0,1). */
    private fun turnsBetween(
        from: Triple<Float, Float, Float>,
        to: Triple<Float, Float, Float>,
    ): Float = FluidHue.wrap01(hueTurns(to) - hueTurns(from))

    /** Turns the emitter dye drifts in one second at a palette-cycle speed. */
    private fun driftTurns(paletteCycleSpeed: Float): Float {
        val e = emitters(paletteCycleSpeed)
        val first = e.tick(features(beat = true), dt, 1.6f, refBase, refSpan)
        repeat(59) { e.tick(features(), dt, 1.6f, refBase, refSpan) }
        val later = e.tick(features(beat = true), dt, 1.6f, refBase, refSpan)
        assertTrue("both beats must emit splats", first.isNotEmpty() && later.size == first.size)
        return turnsBetween(
            Triple(first[0].r, first[0].g, first[0].b),
            Triple(later[0].r, later[0].g, later[0].b),
        )
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
            for (range in listOf(0f, 0.05f, 0.3f, 1f, 1.25f, 1.5f)) {
                val sp = SceneParams(palette = p, hueRange = range)
                assertEquals(
                    sp.hueRange.coerceIn(FluidHue.MIN_HUE_RANGE, FluidHue.MAX_HUE_RANGE) * sp.paletteRange,
                    FluidHue.span(sp.hueRange, sp.paletteRange),
                    1e-6f,
                )
            }
        }
    }

    @Test
    fun theWholeHueRangeSliderIsLiveOnTheFluidFamily() {
        // The bug: span() clamped the slider at 1 while the slider itself runs
        // to 1.5 (and the randomizer rolls 0.5..1.5), so the top THIRD of its
        // travel did nothing on Fluid/Curl Flow/Water while the shader and
        // particle families - which pass hueRange through unclamped - kept
        // moving. One slider value has to mean one thing on every family.
        assertEquals(1.5f, FluidHue.MAX_HUE_RANGE, 0f)
        val palRange = SceneParams(palette = 7).paletteRange
        var previous = FluidHue.span(1f, palRange)
        for (range in listOf(1.1f, 1.25f, 1.4f, 1.5f)) {
            val span = FluidHue.span(range, palRange)
            assertEquals(range * palRange, span, 1e-6f)
            assertTrue("Hue range $range must still widen the span", span > previous)
            previous = span
        }
        // ParticleSceneBase's form (`paletteRange * hueRange`, unclamped) is
        // what the family is being aligned to across the slider's own travel.
        for (range in listOf(0.1f, 0.5f, 1f, 1.5f)) {
            assertEquals(palRange * range, FluidHue.span(range, palRange), 1e-6f)
        }
        // Past the slider's top it clamps rather than running away.
        assertEquals(FluidHue.MAX_HUE_RANGE * palRange, FluidHue.span(9f, palRange), 1e-6f)
        // A span over one turn reaches the emitters as a real colour change,
        // not a wrapped no-op: the dye walks more than once round the wheel.
        val oneTurn = beatColors(refBase, FluidHue.span(1f, 1f))
        val overTurn = beatColors(refBase, FluidHue.span(1.5f, 1f))
        assertTrue(
            "a span past one turn must repaint the splats",
            maxChannelDelta(oneTurn, overTurn) > 0.05f,
        )
    }

    @Test
    fun theHueRangeFloorKeepsTheEmittersOffOneFlatColour() {
        // The floor is load-bearing, unlike the ceiling: the emitters colour
        // each splat at `base + frac * span`, so a span of 0 paints every
        // splat the same and the style collapses to one flat tint.
        assertEquals(FluidHue.MIN_HUE_RANGE, FluidHue.range(0f), 0f)
        assertEquals(FluidHue.MIN_HUE_RANGE, FluidHue.range(-4f), 0f)
        val palRange = SceneParams(palette = 7).paletteRange
        val floored = beatColors(refBase, FluidHue.span(0f, palRange))
        val collapsed = beatColors(refBase, 0f)
        assertTrue("the floor must keep the splats off a single flat colour", maxChannelDelta(floored, collapsed) > 0.01f)
        // ...and it stays a TIGHT band: much narrower than the default slider.
        val wide = beatColors(refBase, FluidHue.span(1f, palRange))
        assertTrue(maxChannelDelta(floored, wide) > maxChannelDelta(floored, collapsed))
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
        // The palette's own width is DATA, a fraction of the wheel: never
        // negative, never over 1. (The slider's 0..1.5 travel is the only
        // thing that may take the product past a full turn - see
        // theWholeHueRangeSliderIsLiveOnTheFluidFamily.)
        assertEquals(0f, FluidHue.span(1f, -1f), 1e-6f)
        assertEquals(1f, FluidHue.span(1f, 5f), 1e-6f)
    }

    @Test
    fun baseIsThePaletteBaseAloneAndWraps() {
        // Palette identity only: the Hue shift slider is the composite's job,
        // so it must not appear in the emission hue at all.
        assertEquals(0.45f, FluidHue.base(0.45f), 1e-6f)
        assertEquals(0.9f, FluidHue.base(0.9f), 1e-6f)
        // A user-made palette can arrive with a base outside [0,1): wrap it,
        // never clamp, so the wheel stays continuous.
        for (b in listOf(-2.5f, -0.4f, 0f, 0.33f, 1f, 4.75f)) {
            val h = FluidHue.base(b)
            assertTrue("hue $h out of [0,1)", h >= 0f && h < 1f)
        }
        assertEquals(0f, FluidHue.base(1f), 1e-6f)
        assertEquals(0.75f, FluidHue.base(-0.25f), 1e-6f)
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
        // The Hue shift slider rides the composite, so a custom palette's
        // emission hue is its own base, untouched.
        assertEquals(0.42f, FluidHue.base(p.paletteBase), 1e-6f)
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
    fun theHueShiftSliderStillMovesTheFluid() {
        // The slider must stay ALIVE on the fluid family - the whole point of
        // the composite grading pass - it just has to arrive there once.
        val p = SceneParams(palette = 7)
        val off = pipelineColors(p)
        val shifted = pipelineColors(p.copy(colorShift = 0.33f))
        assertTrue(
            "Hue shift must repaint the fluid frame",
            maxChannelDelta(off, shifted) > 0.05f,
        )
        // A full turn of the slider is a round trip, not a jump to an edge.
        val full = pipelineColors(p.copy(colorShift = 1f))
        assertTrue(maxChannelDelta(off, full) < 2e-3f)
    }

    @Test
    fun emissionIgnoresTheHueShiftSlider() {
        // FluidScene's call: base() sees the palette, never colorShift, so two
        // params that differ only in the slider emit identical dye and the
        // whole shift is delivered by the composite.
        val p = SceneParams(palette = 7)
        val span = FluidHue.span(p.hueRange, p.paletteRange)
        val plain = beatColors(FluidHue.base(p.paletteBase), span)
        for (shift in listOf(0.1f, 0.33f, 0.75f)) {
            val q = p.copy(colorShift = shift)
            val emitted = beatColors(FluidHue.base(q.paletteBase), FluidHue.span(q.hueRange, q.paletteRange))
            assertEquals(0f, maxChannelDelta(plain, emitted), 1e-6f)
        }
    }

    @Test
    fun oneHueShiftUnitIsOneRotationNotTwo() {
        val p = SceneParams(palette = 7)
        val ref = pipelineColors(p)
        for (shift in listOf(0.1f, 0.25f, 0.4f)) {
            val moved = pipelineColors(p.copy(colorShift = shift))
            for (i in ref.indices) {
                val turns = turnsBetween(ref[i], moved[i])
                assertEquals("one slider unit must be exactly one rotation", shift, turns, 3e-3f)
                assertTrue("splat $i advanced $turns turns for a $shift shift", abs(turns - 2f * shift) > 0.05f)
            }
        }
    }

    @Test
    fun theOldSceneSideFoldTurnedTheWheelTwice() {
        // Regression witness for the arrangement this replaced: the scene
        // folded colorShift into its base hue AND the composite rotated by it.
        val p = SceneParams(palette = 7, colorShift = 0.25f)
        val span = FluidHue.span(p.hueRange, p.paletteRange)
        val ref = beatColors(FluidHue.base(p.paletteBase), span)
        val doubled =
            beatColors(FluidHue.wrap01(p.paletteBase + p.colorShift), span)
                .map { composite(it, p.colorShift) }
        assertEquals("the double-apply really did turn twice", 0.5f, turnsBetween(ref[0], doubled[0]), 0.02f)
        assertEquals("the shipped path turns once", 0.25f, turnsBetween(ref[0], pipelineColors(p)[0]), 3e-3f)
    }

    @Test
    fun colourCycleIsDeliveredOnlyByTheComposite() {
        val cycleSpeed = 0.5f
        // Composite side: the phase VisualizerRenderer integrates and uploads
        // as part of uPostHue - one turn per unit of Cycle speed per second.
        var phase = 0f
        repeat(60) { phase = CompositeGrade.integrateCyclePhase(phase, cycleSpeed, dt, enabled = true) }
        assertEquals(cycleSpeed, phase, 1e-3f)
        // Emission side: the removed `+ cycleSpeed * 20` term drifted the dye
        // at exactly that same rate, so the fluid cycled twice as fast as
        // every other style.
        assertEquals("the old emission term matched the composite's rate", cycleSpeed, driftTurns(cycleSpeed * 20f), 0.02f)
        // What the scene feeds the emitters now: the fluid-only Palette cycle
        // slider alone, so Colour cycle reaches the frame exactly once.
        assertEquals(0f, FluidHue.paletteCycleSpeed(0f), 1e-6f)
        assertEquals(0f, driftTurns(FluidHue.paletteCycleSpeed(0f)), 1e-4f)
        // The fluid-only slider still drifts the dye, and still clamps.
        assertTrue("Palette cycle must still drift the dye", driftTurns(FluidHue.paletteCycleSpeed(2f)) > 0.05f)
        assertEquals(FluidHue.MAX_PALETTE_CYCLE, FluidHue.paletteCycleSpeed(5f), 1e-6f)
        assertEquals(0f, FluidHue.paletteCycleSpeed(-1f), 1e-6f)
    }
}
