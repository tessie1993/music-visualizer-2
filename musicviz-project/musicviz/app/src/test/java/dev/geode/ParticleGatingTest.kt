package dev.geode

import dev.geode.render.VisualizerRenderer
import dev.geode.render.scene.ParamRandomizer
import dev.geode.render.scene.SceneIds
import dev.geode.ui.isParticleLayerSceneId
import dev.geode.ui.isPointSpriteSceneId
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
 * [FluidTabGatingTest] / [ShaderLookGatingTest].
 *
 * `particleShape` and `particleSize` share one reader set, and this pins what
 * it is. Both are uploaded by `ParticleSceneBase.draw` for the CPU styles
 * ([VisualizerRenderer.PARTICLE_SCENES]) and by `FluidParticles.draw` for the
 * GPU lifecycle layer FLUID and CURLFLOW run - two families, one
 * `lib_particle_shade.glsl`. Every other style - MilkDrop, Water, the shader
 * family, and whatever lands next - draws no sprite at all, so both controls
 * stay hidden there.
 *
 * That was two gates until the two families were unified: the fluid layer had
 * no shape uniform, so its sprites were always round and the chip row was dead
 * on FLUID/CURLFLOW while the size slider worked. If the two ever diverge
 * again, this file is where it has to be said out loud.
 *
 * [exactlyTheParticleControlsAreGated] reads the gating back out of
 * `CustomizeTabs.kt`, so over-gating - sweeping a control that DOES work
 * everywhere into the block - fails the build just like un-gating does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParticleGatingTest {
    /**
     * EVERY style the app declares, read straight off [SceneIds] by
     * reflection rather than listed here.
     *
     * A hand-written list would be wrong the day someone adds a style, and
     * wrong in the silent direction: the new style would simply not be
     * checked, while this file kept passing and kept conflicting with every
     * branch that added one. Reflection makes the coverage automatic and the
     * file inert to other people's work.
     */
    private val allStyles: List<String> =
        SceneIds::class
            .java
            .declaredFields
            .filter { it.type == String::class.java }
            .map {
                it.isAccessible = true
                it.get(SceneIds) as String
            }

    @Test
    fun theSpriteGateIsTheParticleFamilyPlusTheFluidPointLayer() {
        assertTrue("no particle scenes registered", VisualizerRenderer.PARTICLE_SCENES.isNotEmpty())
        assertEquals(
            VisualizerRenderer.PARTICLE_SCENES.toSet() + setOf(SceneIds.FLUID, SceneIds.CURLFLOW),
            allStyles.filter { isPointSpriteSceneId(it) }.toSet(),
        )
    }

    @Test
    fun theControlsDoNotReachMilkDropWaterOrTheShaderStyles() {
        val sprite = VisualizerRenderer.PARTICLE_SCENES.toSet() + setOf(SceneIds.FLUID, SceneIds.CURLFLOW)
        val dead = allStyles.filterNot { it in sprite }
        assertTrue("no styles left to check - is the reflection over SceneIds still working?", dead.size > 5)
        dead.forEach { assertFalse(it, isPointSpriteSceneId(it)) }
    }

    @Test
    fun theGateReusesTheExistingParticleLayerGate() {
        // `fluidParticleDrag` (Fluid tab) and these two share the same
        // FluidParticles layer; composing rather than restating FLUID/CURLFLOW
        // keeps them from drifting if that layer ever gains a style.
        allStyles.forEach {
            assertEquals(
                it,
                it in VisualizerRenderer.PARTICLE_SCENES || isParticleLayerSceneId(it),
                isPointSpriteSceneId(it),
            )
        }
    }

    @Test
    fun exactlyTheParticleControlsAreGated() {
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
                "src/main/java/dev/geode/ui/CustomizeTabs.kt",
                "app/src/main/java/dev/geode/ui/CustomizeTabs.kt",
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
