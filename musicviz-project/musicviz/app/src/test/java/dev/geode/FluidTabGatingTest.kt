package dev.geode

import dev.geode.render.scene.ParamRandomizer
import dev.geode.render.scene.SceneIds
import dev.geode.ui.isEmitterSceneId
import dev.geode.ui.isFluidSceneId
import dev.geode.ui.isJourneySceneId
import dev.geode.ui.isParticleLayerSceneId
import dev.geode.ui.isWaterSceneId
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
 * The label-set tests below read the gating back out of `CustomizeTabs.kt`
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
            "controls wrapped in `if (isJourneyScene)` inside CustomizeTabs.kt",
            setOf("Path", "Spawn points", "Progression", "Catch points", "Catch pull", "Catch radius"),
            gatedLabels("isJourneyScene"),
        )
    }

    @Test
    fun particleLifeRidesTheParticleLayerGateWithDrag() {
        // FluidScene and CurlFlowScene set `particles.drag` and
        // `particles.life` on consecutive lines; WATER has neither.
        assertEquals(
            "controls wrapped in `if (isParticleLayerScene)` inside CustomizeTabs.kt",
            setOf("Particle layer", "Particle drag", "Particle life (s)", "Particle brightness"),
            gatedLabels("isParticleLayerScene"),
        )
        assertTrue(isParticleLayerSceneId(SceneIds.FLUID))
        assertTrue(isParticleLayerSceneId(SceneIds.CURLFLOW))
        assertFalse("WATER ages no particles", isParticleLayerSceneId(SceneIds.WATER))
    }

    @Test
    fun waveSpeedAndDampingAreReachableOnEveryStyle() {
        // They are the WATER surface's physics AND the all-styles ripple
        // overlay's (VisualizerRenderer's `ripple.waveSpeed/damping`, mirrored
        // by VideoExporter), so WATER shows them in its own section and every
        // other style gets them in the overlay section. Living only in the
        // WATER block left the overlay's rings uncontrollable everywhere else
        // while the randomizer kept rolling both.
        val physics = setOf("Wave speed", "Damping")
        assertTrue("the Water section must keep its own wave physics", gatedLabels("isWaterScene").containsAll(physics))
        assertEquals(
            "controls wrapped in `if (!isWaterScene)` inside CustomizeTabs.kt",
            physics,
            gatedLabels("!isWaterScene"),
        )
        // Both are still rolled, so both still have to be lockable by label.
        physics.forEach { assertTrue("\"$it\" is no longer a randomizer key", it in ParamRandomizer.KEYS) }
    }

    @Test
    fun theWaterSectionStaysTheHeightfieldSurface() {
        assertEquals(
            "controls wrapped in `if (isWaterScene)` inside CustomizeTabs.kt",
            setOf(
                "Wave speed",
                "Damping",
                "Ripple strength",
                "Depth",
                "Specular",
                "Flow drift",
                // The liquid ink film: only WaterScene allocates and steps it
                // (RippleSim.inkEnabled), so it is WATER-only like the rest of
                // the surface. The renderer's shared ripple overlay leaves the
                // layer off, because there the scene underneath IS the image.
                "Liquid",
                "Liquid flow",
                "Liquid fade",
            ),
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
