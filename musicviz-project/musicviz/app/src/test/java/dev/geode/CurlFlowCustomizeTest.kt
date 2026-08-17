package dev.geode

import dev.geode.render.CompositeGrade
import dev.geode.render.fluid.CurlFlowMath
import dev.geode.render.fluid.FluidHue
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams
import dev.geode.ui.isFluidSceneId
import dev.geode.ui.isJourneySceneId
import dev.geode.ui.isParticleLayerSceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Headless gate for the Curl Flow style's Customize wiring. Four shipped bugs
 * it guards, all reported as "customizations don't work on the fluid styles":
 *
 * 1. The Trails toggle was inert: the renderer forced canvas persistence on
 *    for this scene regardless of the setting, and floored Trail length at
 *    0.85 on top, so most of that slider did nothing either. Honouring the
 *    toggle then over-corrected into a HARD CLEAR, because `trails` defaults
 *    to false - selecting the style from the Styles list strobed. The toggle
 *    now picks a band (a short echo off, the whole slider on), never a wipe.
 * 2. The palette's SPAN multiplier was dropped - the scene passed the raw Hue
 *    range slider where every other family passes `hueRange * paletteRange` -
 *    so switching palette only retinted the streams instead of changing how
 *    much of the wheel they cover.
 * 3. "Particle drag" was unreachable: the scene reads `fluidParticleDrag`
 *    every frame, but the slider sat in the FLUID-only Particles section,
 *    nested behind `fluidParticlesEnabled`, a param Curl Flow never reads.
 * 4. Intensity was quadratic: the scene multiplied its own point brightness by
 *    `intensity` while the composite grading pass - which grades this style -
 *    multiplies by `brightness * intensity` again.
 * 5. The feedback-trail WARP path (Trail zoom / Trail warp non-zero) read the
 *    raw Trail length slider for its decay while the plain fade path read the
 *    remapped retention, so turning either knob on collapsed the streams back
 *    into strobing dots at settings the fade path kept smooth.
 */
class CurlFlowCustomizeTest {
    private val dt = 1f / 60f

    /** The slider's real UI range (CustomizeTabs "Trail length"). */
    private val sliderRange = listOf(0.05f, 0.2f, 0.4f, 0.5f, 0.75f, 0.9f, 0.98f)

    /**
     * Mirror of the Fluid tab's visibility rule for the "Particle drag" row:
     * shown wherever the lifecycle particle layer actually runs, which on
     * FLUID means behind its layer toggle and on CURLFLOW means always.
     */
    private fun dragRowVisible(
        sceneId: String,
        particlesEnabled: Boolean,
    ): Boolean = isParticleLayerSceneId(sceneId) && (!isFluidSceneId(sceneId) || particlesEnabled)

    @Test
    fun trailsToggleActuallyChangesCurlFlowPersistence() {
        // The bug: persistence was forced on, so both of these were equal.
        val off = CurlFlowMath.fadeAlpha(trails = false, trailLength = 0.5f, dt = dt)
        val on = CurlFlowMath.fadeAlpha(trails = true, trailLength = 0.5f, dt = dt)
        assertTrue("Trails off must fade fast", off > 0.5f)
        assertTrue("Trails on must keep most of the previous frame", on < 0.5f)
        assertTrue("the toggle must change the frame", off - on > 0.2f)
        // Even at the extremes of the slider the toggle still separates them.
        for (len in sliderRange) {
            assertTrue(
                "trailLength=$len",
                CurlFlowMath.fadeAlpha(trails = true, trailLength = len, dt = dt) <
                    CurlFlowMath.fadeAlpha(trails = false, trailLength = len, dt = dt),
            )
        }
    }

    @Test
    fun theDefaultParamsDoNotHardClearCurlFlow() {
        // The regression: the renderer's trails gate became
        // `p.trails && (isParticle || isCurl)`, but SceneParams.trails
        // defaults to FALSE and selectScene() only writes sceneId - so
        // Visuals -> Styles -> "Curl Flow" landed on the glClear branch and
        // the scene drew bare GL_POINTS on a wiped canvas, i.e. strobing dots.
        // Only the one built-in `curlflow · Streams` preset sets trails = true.
        val defaults = SceneParams.DEFAULT
        assertFalse("this test is about the default, which must still be off", defaults.trails)
        val alpha = CurlFlowMath.fadeAlpha(defaults.trails, defaults.trailLength, dt)
        assertTrue("selecting the style must never wipe the canvas outright", alpha < 1f)
        assertTrue("the echo must survive several frames, not one", CurlFlowMath.retention(defaults.trailLength, false) > 0.3f)
        // Still a floor, not the trails-on band: the toggle stays meaningful.
        assertEquals(CurlFlowMath.OFF_RETENTION, CurlFlowMath.retention(defaults.trailLength, false), 1e-6f)
        assertTrue("Trails off must stay below the streaming band", CurlFlowMath.OFF_RETENTION < CurlFlowMath.MIN_RETENTION)
        // Whatever the slider says, Trails off is the same short echo - the
        // slider belongs to the toggle - and always shorter than Trails on.
        for (len in sliderRange) {
            assertEquals(CurlFlowMath.OFF_RETENTION, CurlFlowMath.retention(len, false), 1e-6f)
            assertTrue("trailLength=$len", CurlFlowMath.retention(len, false) < CurlFlowMath.retention(len, true))
        }
    }

    @Test
    fun trailLengthStaysLiveOverTheWholeSlider() {
        // The bug: coerceAtLeast(0.85f) flattened everything below 0.85.
        var prev = -1f
        for (len in sliderRange) {
            val keep = CurlFlowMath.retention(len)
            assertTrue("retention must rise with the slider at $len", keep > prev)
            prev = keep
            assertTrue("retention out of band at $len", keep >= CurlFlowMath.MIN_RETENTION && keep <= 1f)
        }
        // Longer trails must mean a gentler fade, monotonically.
        var prevAlpha = Float.MAX_VALUE
        for (len in sliderRange) {
            val a = CurlFlowMath.fadeAlpha(trails = true, trailLength = len, dt = dt)
            assertTrue("fade must soften as the slider rises at $len", a < prevAlpha)
            prevAlpha = a
        }
    }

    @Test
    fun retentionKeepsTheStreamFloorAndClampsOutOfRangeInput() {
        // The floor is why the style still reads as streams rather than
        // strobing dots; the user overrides it by switching Trails OFF.
        assertEquals(CurlFlowMath.MIN_RETENTION, CurlFlowMath.retention(0f), 1e-6f)
        assertEquals(CurlFlowMath.MIN_RETENTION, CurlFlowMath.retention(-2f), 1e-6f)
        assertEquals(1f, CurlFlowMath.retention(1f), 1e-6f)
        assertEquals(1f, CurlFlowMath.retention(4f), 1e-6f)
    }

    @Test
    fun trailWarpPathKeepsTheSameFrameAsThePlainFade() {
        // The bug: drawTrailWarp accepted the REMAPPED retention but computed
        // uDecay from the raw Trail length slider, so turning Trail zoom or
        // Trail warp on dropped Curl Flow below its stream floor while the
        // plain fade path stayed above it - same slider, two decay rates.
        for (len in sliderRange) {
            val keep = CurlFlowMath.retention(len)
            val warpKept = CurlFlowMath.warpDecay(keep, dt)
            val fadeKept = 1f - CurlFlowMath.fadeAlpha(trails = true, trailLength = len, dt = dt)
            assertEquals("warp and fade must retain alike at $len", fadeKept, warpKept, 0.03f)
            assertTrue(
                "the raw slider decays faster than the remapped band at $len",
                warpKept >= CurlFlowMath.warpDecay(len, dt),
            )
            assertTrue("uDecay must stay a usable retention at $len", warpKept > 0f && warpKept < 1f)
        }
    }

    @Test
    fun fadeIsFramerateIndependent() {
        // Retention^(dt*60): one second of decay is the same on a 60 Hz and a
        // 120 Hz panel, so trail length does not halve on fast displays.
        fun keptAfterOneSecond(frames: Int): Float {
            val step = 1f / frames
            val perFrame = 1f - CurlFlowMath.fadeAlpha(trails = true, trailLength = 0.98f, dt = step)
            return perFrame.pow(frames)
        }
        assertEquals(keptAfterOneSecond(60), keptAfterOneSecond(120), 1e-4f)
        // A meaningful amount must survive the second, or the check is vacuous.
        assertTrue(keptAfterOneSecond(60) > 0.02f)
    }

    @Test
    fun paletteSpanSurvivesOnCurlFlow() {
        // The bug: the scene passed hueRange alone, so these were identical.
        val fire = SceneParams(palette = 2)
        val aurora = SceneParams(palette = 7)
        val fireSpan = FluidHue.span(fire.hueRange, fire.paletteRange)
        val auroraSpan = FluidHue.span(aurora.hueRange, aurora.paletteRange)
        assertTrue("a narrow and a wide palette must not span the same wheel", fireSpan != auroraSpan)
        assertTrue(fireSpan < auroraSpan)
        for (i in SceneParams.PALETTES.indices) {
            val sp = SceneParams(palette = i)
            assertEquals(
                sp.hueRange.coerceIn(FluidHue.MIN_HUE_RANGE, 1f) * sp.paletteRange,
                FluidHue.span(sp.hueRange, sp.paletteRange),
                1e-6f,
            )
        }
        // Hue range still scales the span, and never collapses it to a point.
        val wide = SceneParams(palette = 7, hueRange = 1f)
        val narrow = SceneParams(palette = 7, hueRange = 0.3f)
        assertTrue(FluidHue.span(narrow.hueRange, narrow.paletteRange) < FluidHue.span(wide.hueRange, wide.paletteRange))
        assertTrue(FluidHue.span(0f, wide.paletteRange) > 0f)
    }

    @Test
    fun hueRotationBelongsToTheCompositeNotTheScene() {
        // The split the fluid family standardised on: the SCENE owns palette
        // identity (base hue + span, fixed at emission time), the COMPOSITE
        // owns rotation (colorShift + cycle phase). Folding colorShift into
        // the scene's base too would advance the hue twice per slider unit.
        val p = SceneParams(palette = 7, colorShift = 0.3f)
        // FluidHue.base takes no colorShift at all: the scene's emission hue
        // is the palette's own base, so the same palette resolves to the same
        // base whatever the slider says, and only the composite rotates it.
        val unshifted = SceneParams(palette = 7, colorShift = 0f)
        assertEquals(
            "the scene's base must not move with the shift",
            FluidHue.base(unshifted.paletteBase),
            FluidHue.base(p.paletteBase),
            1e-6f,
        )
        assertEquals("an unrotated base is the palette's own", p.paletteBase, FluidHue.base(p.paletteBase), 1e-6f)
        // The half of the wiring the scene DOES own is untouched by the shift.
        assertEquals(
            FluidHue.span(SceneParams(palette = 7, colorShift = 0f).hueRange, p.paletteRange),
            FluidHue.span(p.hueRange, p.paletteRange),
            1e-6f,
        )
    }

    @Test
    fun intensityResponseIsLinearNotQuadratic() {
        // Exposure is the composite pass's job. The scene contributes only its
        // beat pulse, so doubling Intensity must double the screen brightness.
        fun onScreen(intensity: Float) = CurlFlowMath.particleBrightness(0f) * CompositeGrade.brightness(1f, intensity)

        // The old wiring - scene intensity AND composite intensity - squared it.
        fun doubleApplied(intensity: Float) = onScreen(intensity) * intensity

        assertEquals(2f, onScreen(2f) / onScreen(1f), 1e-4f)
        assertEquals(0.5f, onScreen(0.5f) / onScreen(1f), 1e-4f)
        assertEquals(4f, doubleApplied(2f) / doubleApplied(1f), 1e-4f)
        // The uniform is still uploaded at a live, non-zero value: an unset GL
        // uniform reads 0 and would render the streams black.
        assertTrue(CurlFlowMath.particleBrightness(0f) > 0f)
        assertTrue("a beat must still lift the points", CurlFlowMath.particleBrightness(1f) > CurlFlowMath.particleBrightness(0f))
        assertEquals(CurlFlowMath.BASE_BRIGHTNESS, CurlFlowMath.particleBrightness(-1f), 1e-6f)
        assertEquals(CurlFlowMath.BASE_BRIGHTNESS + CurlFlowMath.BEAT_BRIGHTNESS, CurlFlowMath.particleBrightness(3f), 1e-6f)
    }

    @Test
    fun particleDragIsReachableOnCurlFlow() {
        // The bug: the row was FLUID-only and nested behind a param CurlFlow
        // never reads, so the style consumed a slider the user could not see.
        assertTrue(isParticleLayerSceneId(SceneIds.CURLFLOW))
        assertTrue(isParticleLayerSceneId(SceneIds.FLUID))
        assertFalse("WATER has no particle layer", isParticleLayerSceneId(SceneIds.WATER))
        assertFalse(isParticleLayerSceneId(SceneIds.NEBULA))
        assertTrue("CurlFlow reads drag unconditionally", dragRowVisible(SceneIds.CURLFLOW, particlesEnabled = false))
        assertTrue(dragRowVisible(SceneIds.CURLFLOW, particlesEnabled = true))
        assertTrue(dragRowVisible(SceneIds.FLUID, particlesEnabled = true))
        assertFalse("FLUID stops stepping particles when the layer is off", dragRowVisible(SceneIds.FLUID, particlesEnabled = false))
        assertFalse(dragRowVisible(SceneIds.WATER, particlesEnabled = true))
    }

    @Test
    fun particleLifeIsReachableOnCurlFlowToo() {
        // The other particle param the scene reads sits next to Drag in the
        // particle-layer section: CurlFlowScene sets `particles.life` on the
        // line after `particles.drag`, so the two share a visibility rule.
        // It used to live in the Journey section, which also covers WATER -
        // a style with no particle layer at all, so the slider read nothing
        // there while being one row away from controls that do.
        assertTrue(isParticleLayerSceneId(SceneIds.CURLFLOW))
        assertTrue(dragRowVisible(SceneIds.CURLFLOW, particlesEnabled = false))
        assertFalse("WATER ages no particles", dragRowVisible(SceneIds.WATER, particlesEnabled = true))
        // The rest of the Journey section still reaches all three styles.
        assertTrue(isJourneySceneId(SceneIds.CURLFLOW))
        assertTrue(isJourneySceneId(SceneIds.WATER))
    }
}
