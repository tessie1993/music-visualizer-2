package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.TimelineFrame
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
        // Geometry skips both branches, so the uv only makes the centre-space
        // round trip (-0.5 then +0.5), which costs at most an ulp.
        val (x, y) = CompositeGrade.geometry(0.77f, 0.12f, u.rotation, u.zoom)
        assertEquals(0.77f, x, eps)
        assertEquals(0.12f, y, eps)
    }

    @Test
    fun exportedBeatPulseMatchesTheLiveEnvelopeAndUsesItsOwnGate() {
        // The pulse gate is deliberately NOT the grading gate: milkdrop grades
        // itself (gradesItself = true) but nothing in its pipeline reads the
        // pulse slider, so it must still be pulsed by the composite.
        val pulsed = graded.copy(pulse = 0.75f)
        val state = ExportGradeState()
        val dt = 1f / 30f
        // One beat, then a tenth of a second of decay on the export's clock.
        state.advance(pulsed, dt, beat = true)
        var live = CompositeGrade.integrateBeatPulse(0f, beat = true, dt = dt)
        repeat(3) {
            state.advance(pulsed, dt, beat = false)
            live = CompositeGrade.integrateBeatPulse(live, beat = false, dt = dt)
        }
        assertEquals("envelope must decay on the export clock", live, state.beatPulse, 1e-4f)
        assertEquals(
            CompositeGrade.pulseAmount(pulsed.pulse, live),
            state.pulseAmount(pulsed, pulsesItself = false),
            1e-4f,
        )
        // Scenes that pulse themselves (shader, particle) get the neutral 0.
        assertEquals(0f, state.pulseAmount(pulsed, pulsesItself = true), 0f)
        // A parked slider is neutral even mid-beat, on every scene type.
        assertEquals(0f, state.pulseAmount(SceneParams.DEFAULT, pulsesItself = false), 0f)
        assertTrue("the beat envelope must be live for that to be meaningful", state.beatPulse > 0f)
    }

    /**
     * A 60 Hz analysis timeline whose beat flag is ONE frame wide - the shape
     * the offline analyzer really produces, since `BeatGate.accept` is true
     * for a single frame per onset. Beats fall on odd and even indices alike.
     */
    private fun beatTimeline(
        count: Int = 600,
        everyN: Int = 11,
    ): FeatureTimeline {
        val frames =
            (0 until count).map { i ->
                TimelineFrame(
                    i * 1000L / 60L,
                    AudioFeatures(
                        bands = FloatArray(64),
                        waveform = FloatArray(128),
                        beat = i % everyN == 0,
                    ),
                )
            }
        return FeatureTimeline(frames, hopMs = 16L, hopRateHz = 60f)
    }

    /**
     * Beat pulses an export at [fps] fires over the whole timeline, driving
     * [ExportGradeState] frame by frame from timeline features sampled the way
     * `VideoExporter` samples them. A hit is a frame whose envelope is armed
     * at 1 (`pulseAmount` = the slider itself); it decays immediately after,
     * so this counts beats observed, not frames spent decaying.
     *
     * Drives `advance` with [AudioFeatures.motionImpulse] - the field
     * `FxCompositor` passes in production - and NOT the `beat: Boolean`
     * convenience overload. Feeding the Boolean here would hard-code a
     * full-strength 1 and quietly take a branch nothing ships, so a
     * regression in the graded impulse would leave this test green. The
     * fixture's beats carry no `beatStrength`, so `motionImpulse` is 1 on
     * them by the documented legacy rule and the counts are unchanged.
     */
    private fun pulseHits(
        timeline: FeatureTimeline,
        fps: Int,
        params: SceneParams,
        spanned: Boolean = true,
    ): Int {
        val state = ExportGradeState()
        val dt = 1f / fps
        val total = (timeline.durationMs * fps / 1000L).toInt() + 1
        var hits = 0
        for (frame in 0 until total) {
            val timeMs = frame * 1000L / fps
            val nextMs = (frame + 1) * 1000L / fps
            val features = timeline.progressionAt(timeMs, emptyList(), if (spanned) nextMs - timeMs else 0L)
            state.advance(params, dt, features.motionImpulse)
            if (state.pulseAmount(params, pulsesItself = false) >= params.pulse) hits++
        }
        return hits
    }

    @Test
    fun aThirtyFpsExportPulsesOnEveryBeatASixtyFpsExportDoes() {
        // The defect this guards: the beat flag is exactly ONE 60 Hz timeline
        // frame wide, and an exported frame used to sample the single NEAREST
        // frame - so a 30 fps render looked at every other timeline frame and
        // never saw about half the track's beats. advance(beat = false) then
        // never armed the envelope and the video pulsed on half the beats
        // (worse at 24 fps), while the live view pulsed on all of them.
        val pulsed = graded.copy(pulse = 0.75f)
        val timeline = beatTimeline()
        val expected = timeline.frames.count { it.features.beat }
        assertEquals("a 60 fps export pulses on every beat", expected, pulseHits(timeline, 60, pulsed))
        for (fps in listOf(24, 30, 48, 50)) {
            assertEquals("an export at $fps fps must pulse on every beat too", expected, pulseHits(timeline, fps, pulsed))
        }
        // Witness: the nearest-frame sampling this replaced, at the rate the
        // encoder falls back to when 60 fps will not configure.
        val dropped = pulseHits(timeline, 30, pulsed, spanned = false)
        assertTrue("nearest-frame sampling should drop about half, got $dropped of $expected", dropped < expected * 3 / 4)
        assertEquals("...while 60 fps was always correct", expected, pulseHits(timeline, 60, pulsed, spanned = false))
    }

    @Test
    fun theExportPulseFollowsTheBeatsOwnStrength() {
        // ExportGradeState exists so a headless render matches the screen, and
        // since v0.14 the thing being matched is GRADED: a soft hit must swell
        // less than a hard one. Nothing else in this file would notice if the
        // export path went back to a full-strength kick per beat - the parity
        // fixture's beats carry no strength, so they ride the legacy 1.0 rule.
        val pulsed = graded.copy(pulse = 1f)
        val dt = 1f / 60f

        fun peakFor(strength: Float): Float {
            val state = ExportGradeState()
            state.advance(pulsed, dt, AudioFeatures.empty().copy(beat = true, beatStrength = strength).motionImpulse)
            return state.pulseAmount(pulsed, pulsesItself = false)
        }

        val hard = peakFor(1f)
        val soft = peakFor(0.4f)
        // The weakest beat the pulse path can emit: a quiet hit, graded against a
        // recent peak roughly six times its size.
        val faint = peakFor(0.168f)
        assertTrue("a full-strength beat should reach the slider, got $hard", hard >= 0.99f)
        // pulseAmount SQUARES the envelope, so the ordering is preserved but
        // the spread is wider than the strengths themselves - which is the
        // property a "why is my soft beat invisible" bug would break.
        assertEquals("a soft beat swells by strength squared", 0.16f, soft, 1e-4f)
        assertTrue("the weakest real beat must still be non-zero, got $faint", faint > 0f)
        assertTrue("...and clearly smaller than a soft one, got $faint vs $soft", faint < soft / 2f)
        // beatStrength = 0 with beat = true is the LEGACY case (a synthesised
        // or pre-tracker frame) and reads as a full kick by design, so the
        // no-pulse case has to be an actual absence of a beat.
        val noBeat = ExportGradeState()
        noBeat.advance(pulsed, dt, AudioFeatures.empty().motionImpulse)
        assertEquals("no beat, no pulse", 0f, noBeat.pulseAmount(pulsed, pulsesItself = false), 0f)
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
