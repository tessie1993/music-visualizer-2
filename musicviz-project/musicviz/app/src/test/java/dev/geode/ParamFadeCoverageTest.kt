package dev.geode

import dev.geode.render.VisualizerRenderer
import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Guards the param fade against silently dropping sliders. `lerpParams` used
 * to hand-copy 114 of SceneParams' 122 Floats, and the ones it missed snapped
 * mid-morph (Trail zoom, Trail warp, MilkDrop palette tint) while every
 * neighbouring slider glided. It is reflection-driven now, the way
 * [PresetRoundtripTest] covers the preset pipeline: a Float added later fades
 * automatically, or must be named in [VisualizerRenderer.NOT_FADED] with the
 * reason it snaps - and this test walks the whole constructor so neither list
 * can drift again.
 */
class ParamFadeCoverageTest {
    /** SceneParams' constructor Floats - the surface the fade must cover. */
    private val floatParams =
        SceneParams::class
            .primaryConstructor!!
            .parameters
            .filter { it.type.classifier == Float::class }

    private val propsByName = SceneParams::class.memberProperties.associateBy { it.name }

    @Test
    fun everyFloatGlidesUnlessDeclaredNotFaded() {
        val ctor = SceneParams::class.primaryConstructor!!
        val defaults = SceneParams()
        // Land every Float 8 units from its default so a quarter-lerp is
        // unambiguous: a glided field sits at default + 2, a snapped one at
        // default + 8, and nothing in between can satisfy both assertions.
        val target =
            ctor.callBy(
                floatParams.associateWith { param ->
                    propsByName.getValue(param.name!!).get(defaults) as Float + 8f
                },
            )
        val quarter = VisualizerRenderer.lerpParams(defaults, target, 0.25f)
        for (param in floatParams) {
            val name = param.name!!
            val begin = propsByName.getValue(name).get(defaults) as Float
            val mid = propsByName.getValue(name).get(quarter) as Float
            if (name in VisualizerRenderer.NOT_FADED) {
                assertEquals("\"$name\" is declared NOT_FADED, so it must snap straight to its target", begin + 8f, mid, 1e-4f)
            } else {
                assertEquals("\"$name\" snaps instead of gliding - the dropped-slider bug this walk exists for", begin + 2f, mid, 1e-4f)
            }
        }
    }

    @Test
    fun everyExclusionNamesARealFloatAndStatesItsReason() {
        val floatNames = floatParams.map { it.name }.toSet()
        for ((name, reason) in VisualizerRenderer.NOT_FADED) {
            assertTrue("NOT_FADED names \"$name\", which is not a SceneParams constructor Float", name in floatNames)
            assertTrue("NOT_FADED entry \"$name\" must state WHY it snaps", reason.isNotBlank())
        }
    }

    @Test
    fun theThreeForgottenSlidersFadeAgain() {
        // The audit's finding, pinned so a later "cleanup" cannot re-exclude
        // them: these are ordinary look sliders and there is no reason for
        // them to snap while everything around them glides.
        for (name in listOf("trailZoom", "trailWarp", "milkdropPaletteTint")) {
            assertFalse("\"$name\" fades now; it must not creep back into NOT_FADED", name in VisualizerRenderer.NOT_FADED)
        }
    }

    @Test
    fun togglesAndChoicesStillSnapToTarget() {
        val flipped = SceneParams.DEFAULT.copy(trails = true, palette = 5, symmetry = 9)
        val quarter = VisualizerRenderer.lerpParams(SceneParams.DEFAULT, flipped, 0.25f)
        assertTrue("toggles must snap to their target mid-fade", quarter.trails)
        assertEquals("choices must snap to their target mid-fade", 5, quarter.palette)
        assertEquals("choices must snap to their target mid-fade", 9, quarter.symmetry)
    }

    @Test
    fun lerpLeavesItsInputsUntouchedAndReturnsAFreshSnapshot() {
        // The implementation glides by writing through cached Fields into a
        // copy(). The one place those writes must never land is `from` or
        // `to` themselves: `to` IS the renderer's live sceneParams, and
        // HotPathReuseTest already pins that params handed onward are
        // snapshots, not views.
        val from = SceneParams(speed = 1f)
        val to = SceneParams(speed = 3f)
        val half = VisualizerRenderer.lerpParams(from, to, 0.5f)
        assertEquals("lerp mutated its `from` input", 1f, from.speed, 0f)
        assertEquals("lerp mutated its `to` input", 3f, to.speed, 0f)
        assertEquals(2f, half.speed, 1e-5f)
        assertNotSame("the result must be a fresh snapshot, not `to` itself", to, half)
        assertNotSame("the result must be a fresh snapshot, not `from` itself", from, half)
    }
}
