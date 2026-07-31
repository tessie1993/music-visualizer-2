package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.CurlFlowMath
import dev.musicviz.render.fluid.FluidAudioDrive
import dev.musicviz.render.fluid.FluidEmitters
import dev.musicviz.render.fluid.FluidMath
import dev.musicviz.render.fluid.FluidSim
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Headless gate for the two MASTER reactivity sliders on the fluid family.
 *
 * The shipped bug, the same class as the original "customizations don't work
 * on all styles" report: "Audio drive" and "Beat response" (Behavior tab) had
 * no reader at all on FLUID and WATER, and Curl Flow read only "Audio drive" -
 * those scenes take `AudioFeatures` straight from the renderer's band-gain
 * stage and feed them to the sim, the choreography and the emitters, so the
 * per-band faders worked while the two sliders above them did nothing.
 *
 * The fix is deliberately NOT a central multiply in `applyBandGains` /
 * `gainAdjusted`: `ShaderScene` and every `ParticleSceneBase` subclass apply
 * `audioDrive` themselves, so a central gain would apply TWICE there - the
 * quadratic-response regression Water and Curl Flow already had to be rescued
 * from on brightness. Each fluid scene consumes the sliders itself, reusing
 * the shader scenes' scaling so one slider value feels the same everywhere.
 *
 * What this pins:
 *  - both sliders are an EXACT no-op at their neutral default (1), so saved
 *    presets that never touched them render exactly as before;
 *  - both actually change the scene's response away from the default;
 *  - neither is applied twice, and audio drive cannot amplify a feature past
 *    the ceiling `ShaderScene` already clamps to.
 */
class FluidAudioDriveTest {
    private val dt = 1f / 60f

    private fun features(
        beat: Boolean = false,
        bass: Float = 0.4f,
        mid: Float = 0.3f,
        treble: Float = 0.05f,
        rms: Float = 0.4f,
    ) = AudioFeatures(
        bands = FloatArray(16) { it / 16f },
        waveform = FloatArray(64),
        rms = rms,
        bass = bass,
        mid = mid,
        treble = treble,
        beat = beat,
    )

    private fun emitter(response: Float? = null) =
        FluidEmitters(Random(7)).apply {
            stirrers = 0
            sparkle = false
            bassPump = false
            beatSplats = 1
            beatPattern = FluidEmitters.PATTERN_CENTER
            splatRadius = 0.12f
            radiusPulse = 0.4f
            response?.let { beatResponse = it }
        }

    private fun speedOf(s: FluidSim.Splat) = sqrt(s.velX * s.velX + s.velY * s.velY)

    // ---- SceneParams defaults are the neutral values this file assumes ----

    @Test
    fun neutralDefaultsAreOne() {
        assertEquals(1f, SceneParams.DEFAULT.audioDrive, 0f)
        assertEquals(1f, SceneParams.DEFAULT.beatResponse, 0f)
    }

    // ---- Audio drive ----

    @Test
    fun audioDriveIsExactIdentityAtTheDefault() {
        // Every value, including ones already hotter than the ShaderScene
        // ceiling: a slider the user never moved must not clip them.
        for (v in listOf(0f, 0.01f, 0.37f, 1f, 1.5f, 2.4f)) {
            assertEquals("driven($v, 1) must be identity", v, FluidMath.driven(v, 1f), 0f)
        }
        val f = features(bass = 1.9f, rms = 1.7f)
        // The snapshot helper returns the SAME object - no copy, no arithmetic.
        assertSame(f, FluidAudioDrive().scaled(f, 1f))
    }

    @Test
    fun audioDriveScalesEveryFeatureTheFluidScenesRead() {
        val f = features(bass = 0.4f, mid = 0.3f, treble = 0.05f, rms = 0.4f)
        val hot = FluidAudioDrive().scaled(f, 2f)
        assertEquals(0.8f, hot.bass, 1e-6f)
        assertEquals(0.6f, hot.mid, 1e-6f)
        assertEquals(0.1f, hot.treble, 1e-6f)
        assertEquals(0.8f, hot.rms, 1e-6f)
        // Bands feed the spectrum-arc beat pattern and the per-anchor energy.
        for (i in f.bands.indices) assertEquals(f.bands[i] * 2f, hot.bands[i], 1e-6f)
        // ...and the untouched fields survive the copy.
        assertSame(f.waveform, hot.waveform)

        val quiet = FluidAudioDrive().scaled(f, 0.5f)
        assertEquals(0.2f, quiet.bass, 1e-6f)
        assertEquals(0.2f, quiet.rms, 1e-6f)
        assertTrue("audio drive must be monotonic", quiet.bass < f.bass && f.bass < hot.bass)
    }

    @Test
    fun audioDriveCannotAmplifyPastTheShaderSceneCeiling() {
        // ShaderScene clamps `features.x * audioDrive` to 1.5; the fluid
        // family must not blow past it at 2.5x and destabilise the sim.
        assertEquals(FluidMath.DRIVE_CEILING, FluidMath.driven(1f, FluidMath.MAX_AUDIO_DRIVE), 1e-6f)
        assertEquals(FluidMath.DRIVE_CEILING, FluidMath.driven(0.9f, 2.5f), 1e-6f)
        // A feature that ALREADY exceeds the ceiling keeps its own headroom
        // (band gains can hand us one) but still is not amplified further.
        assertEquals(1.8f, FluidMath.driven(1.8f, 2.5f), 1e-6f)
        // Out-of-domain slider values are clamped to the slider's own range.
        assertEquals(FluidMath.driven(0.4f, FluidMath.MIN_AUDIO_DRIVE), FluidMath.driven(0.4f, -3f), 1e-6f)
        assertEquals(FluidMath.driven(0.4f, FluidMath.MAX_AUDIO_DRIVE), FluidMath.driven(0.4f, 99f), 1e-6f)
    }

    @Test
    fun audioDriveChangesTheEmitterScheduleFluidAndWaterFeed() {
        // End-to-end for the scene wiring: the driven snapshot is what
        // FluidScene/WaterScene hand the emitters, and it must move them.
        val raw = features(beat = true)
        val hot = FluidAudioDrive().scaled(raw, 2f)
        val quiet = FluidAudioDrive().scaled(raw, 0.5f)
        val base = emitter().tick(raw, dt, 1.6f, 0.2f, 0.5f).single()
        val loud = emitter().tick(hot, dt, 1.6f, 0.2f, 0.5f).single()
        val soft = emitter().tick(quiet, dt, 1.6f, 0.2f, 0.5f).single()
        assertTrue("audio drive did not reach the emitters", speedOf(loud) > speedOf(base) * 1.1f)
        assertTrue("audio drive did not reach the emitters", speedOf(soft) < speedOf(base) * 0.9f)
    }

    // ---- Beat response ----

    @Test
    fun beatResponseIsExactNoOpAtTheDefault() {
        // Pins the PRE-CHANGE arithmetic: radius = splatRadius * (1 + pulse *
        // env) and speed = BASE_SPEED * force * (0.4 + 1.6*bass) * (0.3 +
        // 0.7*env), with env = 1 on the beat frame.
        val s = emitter().tick(features(beat = true), dt, 1.6f, 0.2f, 0.5f).single()
        assertEquals(0.12f * 1.4f, s.radius, 1e-6f)
        assertEquals(FluidEmitters.BASE_SPEED * (0.4f + 1.6f * 0.4f) * 1f, speedOf(s), 1e-4f)
        // Explicitly setting the neutral value changes nothing either.
        val pinned = emitter(1f).tick(features(beat = true), dt, 1.6f, 0.2f, 0.5f).single()
        assertEquals(s.radius, pinned.radius, 0f)
        assertEquals(speedOf(s), speedOf(pinned), 0f)
        assertEquals(s.r, pinned.r, 0f)
    }

    @Test
    fun beatResponseScalesTheBeatEnvelopeEveryEmitterTermRides() {
        val onBeat = features(beat = true)
        val neutral = emitter(1f).also { it.tick(onBeat, dt, 1.6f, 0.2f, 0.5f) }
        val hot = emitter(2f).also { it.tick(onBeat, dt, 1.6f, 0.2f, 0.5f) }
        val cold = emitter(0.25f).also { it.tick(onBeat, dt, 1.6f, 0.2f, 0.5f) }
        assertEquals(1f, neutral.beatEnv, 1e-6f)
        assertEquals(2f, hot.beatEnv, 1e-6f)
        assertEquals(0.25f, cold.beatEnv, 1e-6f)

        val a = emitter(1f).tick(onBeat, dt, 1.6f, 0.2f, 0.5f).single()
        val b = emitter(2f).tick(onBeat, dt, 1.6f, 0.2f, 0.5f).single()
        val c = emitter(0.25f).tick(onBeat, dt, 1.6f, 0.2f, 0.5f).single()
        assertTrue("radius swell ignored the slider", b.radius > a.radius && a.radius > c.radius)
        assertTrue("momentum ignored the slider", speedOf(b) > speedOf(a) && speedOf(a) > speedOf(c))
        assertTrue("dye gain ignored the slider", b.r + b.g + b.b > a.r + a.g + a.b)
        // The swell stays bounded: an unclamped 2x envelope with a full radius
        // pulse would stamp a 3x capsule over most of the screen.
        assertTrue("radius swell unbounded: ${b.radius}", b.radius <= 0.12f * 2f + 1e-6f)
    }

    @Test
    fun beatResponseAtZeroStopsTheBeatPatternFiring() {
        val e = emitter(0f)
        assertTrue("beats still fired at zero response", e.tick(features(beat = true), dt, 1.6f, 0.2f, 0.5f).isEmpty())
        // ...and the release curve is untouched, so raising the slider again
        // restores the response immediately rather than fading it back in.
        val live = emitter(0f)
        live.tick(features(beat = true), dt, 1.6f, 0.2f, 0.5f)
        live.beatResponse = 1f
        live.tick(features(), dt, 1.6f, 0.2f, 0.5f)
        assertTrue("envelope state was scaled, not the depth: ${live.beatEnv}", live.beatEnv > 0.9f)
    }

    // ---- Curl Flow: audio drive was wired, beat response was not ----

    @Test
    fun curlFlowBeatResponseIsNeutralAtOneAndScalesOtherwise() {
        assertEquals(0.62f, CurlFlowMath.beatDrive(0.62f, 1f), 0f)
        assertEquals(0f, CurlFlowMath.beatDrive(0f, 2f), 0f)
        assertEquals(1.24f, CurlFlowMath.beatDrive(0.62f, 2f), 1e-6f)
        assertEquals(0f, CurlFlowMath.beatDrive(1f, 0f), 0f)

        // Both beat-driven terms move with it: the field kick and the points.
        val neutral = CurlFlowMath.beatDrive(1f, 1f)
        val hot = CurlFlowMath.beatDrive(1f, 2f)
        assertTrue(CurlFlowMath.fieldAmp(1f, hot) > CurlFlowMath.fieldAmp(1f, neutral))
        assertTrue(
            CurlFlowMath.particleBrightness(hot) >= CurlFlowMath.particleBrightness(neutral),
        )
        // Zero response leaves the field at its unkicked baseline.
        assertEquals(CurlFlowMath.BASE_AMP, CurlFlowMath.fieldAmp(1f, CurlFlowMath.beatDrive(1f, 0f)), 1e-6f)
    }

    @Test
    fun curlFlowFieldAmpKeepsItsShippedCurveAndReachesTheWholeSlider() {
        // Pre-change formula at the defaults: 0.55 * drive * (1 + env * 0.9).
        assertEquals(0.55f, CurlFlowMath.fieldAmp(1f, 0f), 1e-6f)
        assertEquals(0.55f * 1.9f, CurlFlowMath.fieldAmp(1f, 1f), 1e-6f)
        assertEquals(0.55f * 1.4f * 1.9f, CurlFlowMath.fieldAmp(1.4f, 1f), 1e-6f)
        // The scene used to clamp audio drive to 2.0 while the slider runs to
        // 2.5, so its top fifth was flat on this style.
        assertTrue(
            "the top of the audio drive slider is still flat",
            CurlFlowMath.fieldAmp(2.5f, 1f) > CurlFlowMath.fieldAmp(2f, 1f),
        )
        assertEquals(0.55f * FluidMath.MAX_AUDIO_DRIVE * 1.9f, CurlFlowMath.fieldAmp(2.5f, 1f), 1e-6f)
        assertEquals(0.55f * FluidMath.MIN_AUDIO_DRIVE * 1.9f, CurlFlowMath.fieldAmp(0f, 1f), 1e-6f)
    }
}
