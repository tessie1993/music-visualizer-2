package dev.musicviz

import dev.musicviz.export.ExportGradeState
import dev.musicviz.render.CompositeGrade
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export parity gate for the composite pass' universal grading.
 *
 * `FxCompositor` builds its own GL program from the same `composite_frag`
 * source as the live renderer, so every grading uniform the renderer uploads
 * has to be uploaded there too - otherwise an exported fluid clip comes out
 * ungraded while the live view is graded. These tests drive the export-side
 * state ([ExportGradeState]) frame by frame and compare it against a CPU
 * mirror of the live renderer's per-frame integration, then push both through
 * the shared [CompositeGrade] maths to prove the pixels land in the same
 * place.
 */
class ExportCompositeGradeTest {
    private val eps = 1e-4f

    /** 2*pi - the wrap CompositeGrade.integrateRotation applies. */
    private val tau = 6.2831855f

    /** The grading sliders the fluid family had no delivery path for. */
    private val graded =
        SceneParams.DEFAULT.copy(
            zoom = 1.8f,
            rotation = 0.7f,
            saturation = 1.4f,
            brightness = 0.8f,
            intensity = 1.25f,
            contrast = 1.3f,
            gamma = 0.85f,
            colorShift = 0.2f,
            colorCycle = true,
            cycleSpeed = 0.35f,
        )

    /**
     * CPU mirror of `VisualizerRenderer`'s per-frame integration: it advances
     * the same two accumulators through `CompositeGrade` once per displayed
     * frame. Returns (rotationAngle, cyclePhase) after [seconds] of playback.
     */
    private fun livePlayback(
        params: SceneParams,
        seconds: Float,
        fps: Int,
    ): Pair<Float, Float> {
        var angle = 0f
        var phase = 0f
        val dt = 1f / fps
        repeat((seconds * fps).toInt()) {
            angle = CompositeGrade.integrateRotation(angle, params.rotation, dt)
            phase = CompositeGrade.integrateCyclePhase(phase, params.cycleSpeed, dt, params.colorCycle)
        }
        return angle to phase
    }

    private fun exportRun(
        params: SceneParams,
        seconds: Float,
        fps: Int,
    ): ExportGradeState {
        val state = ExportGradeState()
        val dt = 1f / fps
        repeat((seconds * fps).toInt()) { state.advance(params, dt) }
        return state
    }

    @Test
    fun aTenSecondExportSpinsAndCyclesLikeTenSecondsOfPlayback() {
        // The export renders on its own clock: at 24, 30 or 60 fps the same
        // ten seconds must land on the same angle and phase as live playback.
        val (liveAngle, livePhase) = livePlayback(graded, seconds = 10f, fps = 60)
        for (fps in listOf(24, 30, 60)) {
            val state = exportRun(graded, seconds = 10f, fps = fps)
            assertEquals("rotation at $fps fps", liveAngle, state.rotationAngle, 1e-3f)
            assertEquals("cycle phase at $fps fps", livePhase, state.cyclePhase, 1e-3f)
        }
        // Sanity: the slider really is a SPEED - 0.7 rad/s over 10 s is 7 rad,
        // wrapped into +-2*pi, not the raw 0.7 a static offset would give.
        assertEquals(7f % tau, liveAngle, 1e-2f)
    }

    @Test
    fun exportedFluidFrameIsGradedIdenticallyToTheLiveFrame() {
        // Fluid family: grades nothing itself, so the composite IS the
        // delivery path. Same params + same elapsed time => same pixel.
        val state = exportRun(graded, seconds = 4f, fps = 30)
        val u = state.uniforms(graded, gradesItself = false)
        assertTrue("grading must be switched ON for the fluid family", u.enabled)

        val (liveAngle, livePhase) = livePlayback(graded, seconds = 4f, fps = 60)
        assertEquals(liveAngle, u.rotation, 1e-3f)

        val src = floatArrayOf(0.31f, 0.66f, 0.18f)
        val live =
            CompositeGrade.grade(
                src,
                graded.colorShift + livePhase,
                graded.saturation,
                graded.contrast,
                graded.gamma,
                CompositeGrade.brightness(graded.brightness, graded.intensity),
            )
        val export = CompositeGrade.grade(src, u.hue, u.saturation, u.contrast, u.gamma, u.brightness)
        for (i in 0..2) assertEquals("channel $i", live[i], export[i], 1e-3f)

        // Geometry too: zoom about the centre plus the integrated angle.
        val (lx, ly) = CompositeGrade.geometry(0.8f, 0.65f, liveAngle, graded.zoom)
        val (ex, ey) = CompositeGrade.geometry(0.8f, 0.65f, u.rotation, u.zoom)
        assertEquals(lx, ex, 1e-3f)
        assertEquals(ly, ey, 1e-3f)
    }

    @Test
    fun brightnessCarriesIntensityAndHueCarriesTheCyclePhase() {
        val state = exportRun(graded, seconds = 2f, fps = 30)
        val u = state.uniforms(graded, gradesItself = false)
        assertEquals(graded.brightness * graded.intensity, u.brightness, eps)
        assertEquals(graded.colorShift + state.cyclePhase, u.hue, eps)
        assertTrue("the cycle must have advanced", state.cyclePhase > 0f)
    }

    @Test
    fun selfGradingScenesGetTheNeutralIdentityAndTheDisableFlag() {
        // Shader, particle and milkdrop scenes grade in their own pass; if the
        // export graded them again they would be zoomed twice and brightened
        // twice. The neutral values are 1.0, NOT 0.0 - a zero here is black at
        // 20x zoom, which is exactly why the enable flag exists.
        val state = exportRun(graded, seconds = 3f, fps = 30)
        val u = state.uniforms(graded, gradesItself = true)
        assertFalse("uPostGrade must be off for self-grading scenes", u.enabled)
        assertEquals(1f, u.zoom, 0f)
        assertEquals(1f, u.saturation, 0f)
        assertEquals(1f, u.brightness, 0f)
        assertEquals(1f, u.contrast, 0f)
        assertEquals(1f, u.gamma, 0f)
        assertEquals(0f, u.rotation, 0f)
        assertEquals(0f, u.hue, 0f)

        // ...and those values are an exact identity on both maths paths.
        val src = floatArrayOf(0.2f, 0.55f, 0.91f)
        val out = CompositeGrade.grade(src, u.hue, u.saturation, u.contrast, u.gamma, u.brightness)
        for (i in 0..2) assertEquals("channel $i", src[i], out[i], 0f)
        val (x, y) = CompositeGrade.geometry(0.77f, 0.12f, u.rotation, u.zoom)
        assertEquals(0.77f, x, 0f)
        assertEquals(0.12f, y, 0f)
    }

    @Test
    fun defaultParamsAreANoOpOnEverySceneType() {
        // A user who touched nothing must get a byte-identical export.
        val state = exportRun(SceneParams.DEFAULT, seconds = 5f, fps = 30)
        for (gradesItself in listOf(false, true)) {
            val u = state.uniforms(SceneParams.DEFAULT, gradesItself)
            val src = floatArrayOf(0.44f, 0.09f, 0.72f)
            val out = CompositeGrade.grade(src, u.hue, u.saturation, u.contrast, u.gamma, u.brightness)
            for (i in 0..2) assertEquals("gradesItself=$gradesItself channel $i", src[i], out[i], eps)
            val (x, y) = CompositeGrade.geometry(0.31f, 0.87f, u.rotation, u.zoom)
            assertEquals(0.31f, x, eps)
            assertEquals(0.87f, y, eps)
        }
    }
}
