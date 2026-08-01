package dev.musicviz

import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.isParticleLayerSceneId
import dev.musicviz.ui.isParticleShapeSceneId
import dev.musicviz.ui.isPointSpriteSceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Customize -> Shape, "Particles" section. The third sibling of
 * [FluidTabGatingTest] / [ShaderLookGatingTest], and the one case where a
 * single section needs TWO gates because its controls have different readers:
 *
 *  - `particleShape` is uploaded only by `ParticleSceneBase.draw` (`uShape`,
 *    consumed by `particle_frag.glsl`'s shapeMask), whose five subclasses are
 *    exactly [VisualizerRenderer.PARTICLE_SCENES]. The fluid point layer has
 *    no shape uniform, so the chips are dead on FLUID/CURLFLOW as well.
 *  - `particleSize` is read by those five (`ParticleSceneBase.kt:152`) AND by
 *    `FluidScene.kt:333` / `CurlFlowScene.kt:212`, which scale the
 *    FluidParticles sprites with it.
 *
 * So the size gate is strictly wider than the shape gate, the header rides the
 * wider one (an empty "Particles" heading on a shader style would read as a
 * bug of its own), and both are still dead on MilkDrop and Water.
 *
 * [exactlyTheParticleControlsAreGated] reads the gating back out of
 * `CustomizeTabs.kt`, so over-gating - sweeping a control that DOES work
 * everywhere into either block - fails the build just like un-gating does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParticleGatingTest {
    private val allStyles: List<String> =
        VisualizerRenderer.PARTICLE_SCENES +
            VisualizerRenderer.SHADER_SCENES.keys +
            listOf(SceneIds.MILKDROP, SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER)

    @Test
    fun particleShapeGateIsExactlyTheParticleFamily() {
        assertTrue("no particle scenes registered", VisualizerRenderer.PARTICLE_SCENES.isNotEmpty())
        assertEquals(
            VisualizerRenderer.PARTICLE_SCENES.toSet(),
            allStyles.filter { isParticleShapeSceneId(it) }.toSet(),
        )
    }

    @Test
    fun particleSizeGateIsTheParticleFamilyPlusTheFluidPointLayer() {
        assertEquals(
            VisualizerRenderer.PARTICLE_SCENES.toSet() + setOf(SceneIds.FLUID, SceneIds.CURLFLOW),
            allStyles.filter { isPointSpriteSceneId(it) }.toSet(),
        )
    }

    @Test
    fun neitherControlReachesMilkDropWaterOrTheShaderStyles() {
        val dead = VisualizerRenderer.SHADER_SCENES.keys + listOf(SceneIds.MILKDROP, SceneIds.WATER)
        dead.forEach {
            assertFalse(it, isParticleShapeSceneId(it))
            assertFalse(it, isPointSpriteSceneId(it))
        }
    }

    @Test
    fun theShapeGateIsASubsetOfTheSizeGate() {
        // The section header is drawn on the size gate, so any style that can
        // show the shape chips must also be inside the header's gate.
        allStyles.filter { isParticleShapeSceneId(it) }.forEach { assertTrue(it, isPointSpriteSceneId(it)) }
        // ...and strictly wider: FLUID/CURLFLOW size sprites they cannot shape.
        assertTrue(isPointSpriteSceneId(SceneIds.FLUID) && !isParticleShapeSceneId(SceneIds.FLUID))
        assertTrue(isPointSpriteSceneId(SceneIds.CURLFLOW) && !isParticleShapeSceneId(SceneIds.CURLFLOW))
    }

    @Test
    fun theSizeGateReusesTheExistingParticleLayerGate() {
        // `fluidParticleDrag` (Fluid tab) and `particleSize` share the same
        // FluidParticles layer; composing rather than restating FLUID/CURLFLOW
        // keeps them from drifting if that layer ever gains a style.
        allStyles.forEach {
            assertEquals(
                it,
                isParticleShapeSceneId(it) || isParticleLayerSceneId(it),
                isPointSpriteSceneId(it),
            )
        }
    }

    @Test
    fun exactlyTheParticleControlsAreGated() {
        assertEquals(
            "controls wrapped in `if (isParticleShapeScene)` inside CustomizeTabs.kt",
            setOf("Particle shape"),
            gatedLabels("isParticleShapeScene"),
        )
        assertEquals(
            "controls wrapped in `if (isPointSpriteScene)` inside CustomizeTabs.kt",
            setOf("Particle shape", "Particle size"),
            gatedLabels("isPointSpriteScene"),
        )
    }

    @Test
    fun theGatedControlsAreStillRandomizableAndLockable() {
        // Conditional composition only: the labels stay in the source verbatim
        // so `ParamRandomizer`'s label-keyed locks keep working.
        val labels = listOf("Particle shape", "Particle size")
        assertEquals(
            "gated labels the randomizer no longer knows",
            emptyList<String>(),
            labels.filterNot { it in ParamRandomizer.KEYS },
        )
    }

    /**
     * Labels rendered inside an `if (<gate>) { ... }` block.
     *
     * Brace depth is tracked line by line; Kotlin string templates (`${...}`)
     * balance within a line, so plain counting is enough here.
     */
    private fun gatedLabels(gate: String): Set<String> {
        val labelRegex = Regex("(?:LabeledSlider|LabeledIntSlider|CheckRow|LockableChipLabel)\\(\\s*\"([^\"]+)\"")
        val gateRegex = Regex("\\bif \\($gate\\)\\s*\\{")
        val gated = mutableSetOf<String>()
        val gateDepths = mutableListOf<Int>()
        var depth = 0
        for (line in customizeTabsSource().lines()) {
            if (gateDepths.isNotEmpty()) {
                labelRegex.findAll(line).forEach { gated += it.groupValues[1] }
            }
            val gateBrace = gateRegex.find(line)?.let { line.indexOf('{', it.range.first) } ?: -1
            line.forEachIndexed { idx, c ->
                when (c) {
                    '{' -> {
                        depth++
                        if (idx == gateBrace) gateDepths.add(depth)
                    }
                    '}' -> {
                        if (gateDepths.isNotEmpty() && gateDepths.last() == depth) {
                            gateDepths.removeAt(gateDepths.size - 1)
                        }
                        depth--
                    }
                    else -> Unit
                }
            }
        }
        assertEquals("unbalanced braces while parsing CustomizeTabs.kt", 0, depth)
        assertTrue("no `if ($gate)` block found in CustomizeTabs.kt", gated.isNotEmpty())
        return gated
    }

    private fun customizeTabsSource(): String {
        val relatives =
            listOf(
                "src/main/java/dev/musicviz/ui/CustomizeTabs.kt",
                "app/src/main/java/dev/musicviz/ui/CustomizeTabs.kt",
            )
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (rel in relatives) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("CustomizeTabs.kt not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
