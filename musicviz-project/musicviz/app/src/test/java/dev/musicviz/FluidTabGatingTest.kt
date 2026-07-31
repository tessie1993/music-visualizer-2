package dev.musicviz

import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.isEmitterSceneId
import dev.musicviz.ui.isFluidSceneId
import dev.musicviz.ui.isJourneySceneId
import dev.musicviz.ui.isParticleLayerSceneId
import dev.musicviz.ui.isWaterSceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Customize -> Fluid tab gating. Each section is scoped to what the ACTIVE
 * style actually reads: WaterScene reuses FluidEmitters' splat schedule and
 * the FluidQuality tiers, so those controls must reach WATER, while the
 * Navier-Stokes solver / dye / look controls only FluidScene reads must not.
 *
 * The label-set tests below read the gating back out of `CustomizeDialog.kt`
 * (the technique [ParticleGatingTest] / [ShaderLookGatingTest] use), so a
 * control that drifts into a section whose styles do not read it - or out of
 * one whose styles do - fails the build instead of shipping as a dead or
 * missing slider.
 */
class FluidTabGatingTest {
    private val allStyles =
        listOf(
            SceneIds.FLUID,
            SceneIds.CURLFLOW,
            SceneIds.WATER,
            SceneIds.NEBULA,
            SceneIds.MILKDROP,
            SceneIds.PLASMA,
        )

    @Test
    fun emitterAndQualityControlsReachWater() {
        assertTrue(isEmitterSceneId(SceneIds.WATER))
        assertTrue(isEmitterSceneId(SceneIds.FLUID))
    }

    @Test
    fun solverAndDyeControlsStayOnFluidOnly() {
        assertTrue(isFluidSceneId(SceneIds.FLUID))
        assertFalse(isFluidSceneId(SceneIds.WATER))
        assertFalse(isFluidSceneId(SceneIds.CURLFLOW))
    }

    @Test
    fun emitterGateIsSupersetOfFluidGate() {
        // The tab nests the FLUID-only rows (solver iterations, palette
        // cycle) inside the emitter sections, so the wider gate must be
        // true wherever the narrow one is.
        allStyles.filter { isFluidSceneId(it) }.forEach { assertTrue(it, isEmitterSceneId(it)) }
    }

    @Test
    fun emitterGateIsSubsetOfJourneyGate() {
        // Every emitter style also runs the spawn/catch progression that
        // places the splats, so the Journey section always precedes them.
        allStyles.filter { isEmitterSceneId(it) }.forEach { assertTrue(it, isJourneySceneId(it)) }
    }

    @Test
    fun journeyGateCoversAllThreeFluidStyles() {
        assertTrue(isJourneySceneId(SceneIds.FLUID))
        assertTrue(isJourneySceneId(SceneIds.CURLFLOW))
        assertTrue(isJourneySceneId(SceneIds.WATER))
        assertFalse(isJourneySceneId(SceneIds.NEBULA))
    }

    @Test
    fun surfaceControlsStayOnWaterOnly() {
        assertTrue(isWaterSceneId(SceneIds.WATER))
        assertFalse(isWaterSceneId(SceneIds.FLUID))
        assertFalse(isWaterSceneId(SceneIds.CURLFLOW))
    }

    @Test
    fun nonFluidStylesSeeOnlyTheSharedSections() {
        listOf(SceneIds.NEBULA, SceneIds.MILKDROP, SceneIds.PLASMA).forEach { id ->
            assertFalse(id, isFluidSceneId(id))
            assertFalse(id, isEmitterSceneId(id))
            assertFalse(id, isJourneySceneId(id))
            assertFalse(id, isWaterSceneId(id))
        }
    }

    @Test
    fun everyJourneyControlHasAWaterReader() {
        // The journey gate includes WATER, so nothing may live in it that
        // WaterScene ignores. `fluidParticleLife` did (Water has no particle
        // layer), which put a dead "Particle life (s)" slider on that style.
        assertEquals(
            "controls wrapped in `if (isJourneyScene)` inside CustomizeDialog.kt",
            setOf("Path", "Spawn points", "Progression", "Catch points", "Catch pull", "Catch radius"),
            gatedLabels("isJourneyScene"),
        )
    }

    @Test
    fun particleLifeRidesTheParticleLayerGateWithDrag() {
        // FluidScene and CurlFlowScene set `particles.drag` and
        // `particles.life` on consecutive lines; WATER has neither.
        assertEquals(
            "controls wrapped in `if (isParticleLayerScene)` inside CustomizeDialog.kt",
            setOf("Particle layer", "Particle drag", "Particle life (s)", "Particle brightness"),
            gatedLabels("isParticleLayerScene"),
        )
        assertTrue(isParticleLayerSceneId(SceneIds.FLUID))
        assertTrue(isParticleLayerSceneId(SceneIds.CURLFLOW))
        assertFalse("WATER ages no particles", isParticleLayerSceneId(SceneIds.WATER))
    }

    @Test
    fun waveSpeedAndDampingAreReachableOnEveryStyle() {
        // They are the WATER surface's physics AND the ripple overlay's
        // (VisualizerRenderer's `ripple.waveSpeed/damping`, mirrored by
        // VideoExporter), so WATER shows them in its own section and every
        // style that CAN show the overlay gets them there. Living only in the
        // WATER block left the overlay's rings uncontrollable everywhere else
        // while the randomizer kept rolling both.
        val physics = setOf("Wave speed", "Damping")
        assertTrue("the Water section must keep its own wave physics", gatedLabels("isWaterScene").containsAll(physics))
        assertTrue("the overlay must carry its own wave physics", gatedLabels("!isWaterScene").containsAll(physics))
        // Both are still rolled, so both still have to be lockable by label.
        physics.forEach { assertTrue("\"$it\" is no longer a randomizer key", it in ParamRandomizer.KEYS) }
    }

    @Test
    fun theRippleOverlaySectionIsHiddenOnWater() {
        // The renderer hard-disables the overlay while WaterScene is active
        // (`&& !waterActive`, mirrored by VideoExporter's
        // `exportWaterScene == null`), so on WATER the enable checkbox and both
        // overlay sliders used to be live controls driving nothing. The whole
        // section is gated off WATER now.
        assertEquals(
            "controls wrapped in `if (!isWaterScene)` inside CustomizeDialog.kt",
            setOf("Water ripples enabled", "Wave speed", "Damping", "Ripple overlay strength", "Ripple glint"),
            gatedLabels("!isWaterScene"),
        )
    }

    @Test
    fun theWaterSectionStaysTheHeightfieldSurface() {
        assertEquals(
            "controls wrapped in `if (isWaterScene)` inside CustomizeDialog.kt",
            setOf("Wave speed", "Damping", "Ripple strength", "Depth", "Specular", "Flow drift"),
            gatedLabels("isWaterScene"),
        )
    }

    /**
     * Labels rendered inside an `if (<gate>) { ... }` block, brace-tracked line
     * by line - the same reader [ParticleGatingTest] uses (Kotlin string
     * templates balance within a line, so plain counting is enough).
     */
    private fun gatedLabels(gate: String): Set<String> {
        val labelRegex = Regex("(?:LabeledSlider|LabeledIntSlider|CheckRow|LockableChipLabel)\\(\\s*\"([^\"]+)\"")
        val gateRegex = Regex("\\bif \\(${Regex.escape(gate)}\\)\\s*\\{")
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
        assertTrue("no `if ($gate)` block found in CustomizeDialog.kt", gated.isNotEmpty())
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
