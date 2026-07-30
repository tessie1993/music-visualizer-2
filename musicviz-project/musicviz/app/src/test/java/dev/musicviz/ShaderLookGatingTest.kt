package dev.musicviz

import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.isJourneySceneId
import dev.musicviz.ui.isShaderLookSceneId
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
 * Customize -> Shape / Color gating, the sibling of [FluidTabGatingTest].
 *
 * "Not all customizations work on all styles" was mostly fixed by moving the
 * Shape/Color effects into the COMPOSITE pass, which bends particles,
 * MilkDrop and the fluid family alike. Four params were left behind because
 * only `ShaderScene` uploads them - `morph` (uMorph), `paletteMix`
 * (uPaletteMix), `duotone` (uDuotone) and the second palette slot
 * (uPal2Base/uPal2Range) - and `composite_frag.glsl` declares no counterpart
 * for any of them. Rather than leave four dead controls on every other style,
 * Shape and Color hide them there: if you can see it, it works.
 *
 * [exactlyTheShaderOnlyControlsAreGated] reads the gating straight out of
 * `CustomizeDialog.kt`, so gating a control that DOES work everywhere (or
 * un-gating one of these four) fails the build instead of silently shipping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShaderLookGatingTest {
    /** Labels of the controls that must only appear on shader styles. */
    private val shaderOnlyLabels = setOf("Morph", "Palette blend", "Palette 2", "Duotone")

    @Test
    fun everyShaderStyleReadsTheShaderOnlyLookParams() {
        assertTrue("no shader scenes registered", VisualizerRenderer.SHADER_SCENES.isNotEmpty())
        VisualizerRenderer.SHADER_SCENES.keys.forEach { assertTrue(it, isShaderLookSceneId(it)) }
    }

    @Test
    fun noOtherStyleReadsThem() {
        val others =
            VisualizerRenderer.PARTICLE_SCENES +
                listOf(SceneIds.MILKDROP, SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER)
        others.forEach { assertFalse(it, isShaderLookSceneId(it)) }
    }

    @Test
    fun theShaderGateNeverOverlapsTheFluidGates() {
        // A style is either a shader scene or a fluid-family one, never both,
        // so the two gates can be reasoned about independently.
        listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER).forEach {
            assertTrue(it, isJourneySceneId(it))
            assertFalse(it, isShaderLookSceneId(it))
        }
        VisualizerRenderer.SHADER_SCENES.keys.forEach { assertFalse(it, isJourneySceneId(it)) }
    }

    @Test
    fun exactlyTheShaderOnlyControlsAreGated() {
        assertEquals(
            "controls wrapped in `if (isShaderLookScene)` inside CustomizeDialog.kt",
            shaderOnlyLabels,
            gatedLabels(),
        )
    }

    @Test
    fun theGatedControlsAreStillRandomizableAndLockable() {
        // Hiding a control must not orphan its randomizer key: the labels stay
        // in the source verbatim, they are only conditionally composed.
        val missing = shaderOnlyLabels.filterNot { it in ParamRandomizer.KEYS }
        assertEquals("gated labels the randomizer no longer knows", emptyList<String>(), missing)
    }

    /**
     * Labels rendered inside an `if (isShaderLookScene) { ... }` block.
     *
     * Brace depth is tracked line by line; Kotlin string templates (`${...}`)
     * balance within a line, so plain counting is enough here.
     */
    private fun gatedLabels(): Set<String> {
        val labelRegex = Regex("(?:LabeledSlider|LabeledIntSlider|CheckRow|LockableChipLabel)\\(\\s*\"([^\"]+)\"")
        val gateRegex = Regex("\\bif \\(isShaderLookScene\\)\\s*\\{")
        val gated = mutableSetOf<String>()
        val gateDepths = mutableListOf<Int>()
        var depth = 0
        for (line in customizeDialogSource().lines()) {
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
        assertEquals("unbalanced braces while parsing CustomizeDialog.kt", 0, depth)
        return gated
    }

    private fun customizeDialogSource(): String {
        val relatives =
            listOf(
                "src/main/java/dev/musicviz/ui/CustomizeDialog.kt",
                "app/src/main/java/dev/musicviz/ui/CustomizeDialog.kt",
            )
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (rel in relatives) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("CustomizeDialog.kt not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
