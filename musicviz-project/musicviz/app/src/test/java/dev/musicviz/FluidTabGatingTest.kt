package dev.musicviz

import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.isEmitterSceneId
import dev.musicviz.ui.isFluidSceneId
import dev.musicviz.ui.isJourneySceneId
import dev.musicviz.ui.isWaterSceneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Customize -> Fluid tab gating. Each section is scoped to what the ACTIVE
 * style actually reads: WaterScene reuses FluidEmitters' splat schedule and
 * the FluidQuality tiers, so those controls must reach WATER, while the
 * Navier-Stokes solver / dye / look controls only FluidScene reads must not.
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
}
