package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.scene.AttractorScene
import dev.musicviz.render.scene.GalaxyScene
import dev.musicviz.render.scene.InkflowScene
import dev.musicviz.render.scene.ParticleSceneBase
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.render.scene.StormScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The four styles added on top of the original five, driven headlessly.
 *
 * `ParticleSceneBase.update` is pure CPU - simulate, optional flow advection,
 * then the palette/mirror/density pass - so a scene can be stepped for seconds
 * of simulated time with no GL context at all. That is worth using: the
 * failure modes of these particular styles (a chaotic map latching NaN, a
 * rain sheet drifting out of frame, a differential-rotation disc that isn't
 * actually differential) are all invisible in a screenshot and all trivially
 * detectable here.
 */
class NewParticleStylesTest {
    private val shaders = ParticleSceneBase.ShaderSources("", "")

    private fun features(
        bass: Float = 0.4f,
        mid: Float = 0.3f,
        treble: Float = 0.2f,
        beat: Boolean = false,
    ) = AudioFeatures(
        bands = FloatArray(64) { 0.15f + 0.3f * ((it % 7) / 7f) },
        waveform = FloatArray(64),
        rms = 0.3f,
        bass = bass,
        mid = mid,
        treble = treble,
        beat = beat,
        centroid = 0.35f,
    )

    /** Steps a scene for [seconds] at 60 Hz, beating every 8th frame. */
    private fun run(
        scene: ParticleSceneBase,
        seconds: Float,
        params: SceneParams = SceneParams.DEFAULT,
    ) {
        scene.setParams(params)
        val dt = 1f / 60f
        var t = 0f
        var i = 0
        while (t < seconds) {
            scene.update(features(beat = i % 8 == 0), dt)
            t += dt
            i++
        }
    }

    /** Every particle record, as (x, y, size, hue, energy, vx, vy) rows. */
    private fun rows(scene: ParticleSceneBase): List<FloatArray> {
        val data = scene.particleRecords()
        val stride = ParticleSceneBase.FLOATS_PER_PARTICLE
        return (0 until data.size / stride).map { i -> data.copyOfRange(i * stride, i * stride + stride) }
    }

    private fun assertFiniteAndOnScreen(
        scene: ParticleSceneBase,
        bound: Float = 1.6f,
    ) {
        rows(scene).forEachIndexed { i, r ->
            r.forEachIndexed { c, v ->
                assertTrue("particle $i component $c is not finite: $v", v.isFinite())
            }
            assertTrue("particle $i ran away to (${r[0]}, ${r[1]})", abs(r[0]) <= bound && abs(r[1]) <= bound)
            assertTrue("particle $i hue out of range: ${r[3]}", r[3] in 0f..1f)
        }
    }

    @Test
    fun allFourAreRegisteredAsParticleStyles() {
        listOf(SceneIds.GALAXY, SceneIds.ATTRACTOR, SceneIds.STORM, SceneIds.INKFLOW).forEach {
            assertTrue("$it missing from PARTICLE_SCENES", it in VisualizerRenderer.PARTICLE_SCENES)
        }
        // Ids are persisted in presets and shared links; duplicates would make
        // one style silently load as another.
        assertEquals(
            VisualizerRenderer.PARTICLE_SCENES.size,
            VisualizerRenderer.PARTICLE_SCENES.toSet().size,
        )
    }

    @Test
    fun galaxyRotationCurveShears() {
        val scene = GalaxyScene(shaders, count = 400)
        // The winding dilemma is the whole point: the inner disc MUST lap the
        // outer disc, or the arms are just rigid spokes with extra maths.
        val inner = scene.angularRate(0.15f, 1f)
        val outer = scene.angularRate(1.0f, 1f)
        assertTrue("rotation curve is not differential: $inner vs $outer", inner > outer * 1.8f)
        run(scene, 6f)
        assertFiniteAndOnScreen(scene)
    }

    @Test
    fun galaxyKeepsItsDiscAndItsArms() {
        val scene = GalaxyScene(shaders, count = 600)
        run(scene, 8f)
        val r = rows(scene)
        // Sizes vary: a disc where every star is the same brightness has no
        // bulge and no arms, which is what the density wave is there to avoid.
        val sizes = r.map { it[2] }
        assertTrue("no size structure in the disc", sizes.max() - sizes.min() > 4f)
        // Something has to be near the centre (the bulge) and something far out.
        assertTrue("no bulge", r.any { abs(it[0]) < 0.2f && abs(it[1]) < 0.2f })
        assertTrue("no outer disc", r.any { abs(it[0]) > 0.6f || abs(it[1]) > 0.6f })
    }

    @Test
    fun attractorStaysBoundedAndKeepsMoving() {
        val scene = AttractorScene(shaders, count = 500)
        run(scene, 10f)
        // de Jong is bounded by construction, but a NaN would latch forever and
        // the scene has to scrub it rather than paint a hole.
        assertFiniteAndOnScreen(scene, bound = 1.2f)
        val before = rows(scene).map { it[0] }
        run(scene, 2f)
        val after = rows(scene).map { it[0] }
        assertNotEquals("the attractor froze", before, after)
    }

    @Test
    fun attractorCoefficientsTrackTheAudioWithoutRunningAway() {
        val scene = AttractorScene(shaders, count = 200)
        val calm = scene.coefficients().copyOf()
        run(scene, 4f, SceneParams.DEFAULT.copy(audioDrive = 1.6f))
        val loud = scene.coefficients()
        assertTrue("coefficients never moved", loud.indices.any { abs(loud[it] - calm[it]) > 0.05f })
        // The map is chaotic in its parameters too; unbounded coefficients turn
        // it into noise. de Jong's interesting band is roughly +-3.
        loud.forEach { assertTrue("coefficient escaped its band: $it", abs(it) < 3.5f) }
    }

    @Test
    fun stormRainsDownwardAndSplashes() {
        val scene = StormScene(shaders, count = 900)
        run(scene, 5f)
        assertFiniteAndOnScreen(scene)
        val r = rows(scene)
        val splash = scene.splashRange()
        val drops = r.filterIndexed { i, _ -> i !in splash }
        // Rain falls: the sheet's velocity is overwhelmingly downward.
        val falling = drops.count { it[6] < 0f }
        assertTrue("rain is not falling ($falling of ${drops.size})", falling > drops.size * 0.9f)
        // ...and the floor is doing something about it.
        val liveSplashes = splash.count { r[it][2] > 0f }
        assertTrue("no splashes after 5 s of rain", liveSplashes > 0)
        assertTrue("the sheet drifted out of frame", scene.maxDropAbscissa() < 1.4f)
    }

    @Test
    fun stormWindIsSmoothedNotJittered() {
        val scene = StormScene(shaders, count = 400)
        run(scene, 1f)
        val a = scene.windValue()
        scene.update(features(mid = 0.9f), 1f / 60f)
        val b = scene.windValue()
        // One frame must not be able to swing the whole sheet: the smoothing
        // constant is what stops a gust from reading as a glitch.
        assertTrue("wind slewed too far in one frame: $a -> $b", abs(b - a) < 0.25f)
    }

    @Test
    fun inkflowDemandsTheFieldAndPushesBackIntoIt() {
        val scene = InkflowScene(shaders, count = 600)
        assertTrue("Inkflow must force the FlowField on", scene.requiresFlowField)
        run(scene, 3f)
        assertFiniteAndOnScreen(scene, bound = 1.3f)
        // The return leg of the coupling: without kicks this is just a particle
        // style that happens to drift.
        assertTrue("no kicks were queued for the field", scene.kickCount() > 0)
        assertTrue("the ink is not moving", scene.meanSpeed() > 1e-3f)
    }

    @Test
    fun inkflowFallbackCurlIsDivergenceFree() {
        // The fallback motion has to swirl, not converge: a field with
        // divergence piles every tracer into sinks within seconds, which is
        // exactly the clumping the curl construction exists to avoid.
        val scene = InkflowScene(shaders, count = 16)
        val h = 1e-3f
        val freq = 2.6f
        var divSum = 0.0
        var magSum = 0.0
        var samples = 0
        for (gy in -4..4) {
            for (gx in -4..4) {
                val x = gx * 0.21f
                val y = gy * 0.19f
                // v = (-dPsi/dy, dPsi/dx)
                val vxr = -scene.curlDy(x + h, y, freq)
                val vxl = -scene.curlDy(x - h, y, freq)
                val vyt = scene.curlDx(x, y + h, freq)
                val vyb = scene.curlDx(x, y - h, freq)
                divSum += abs((vxr - vxl) / (2 * h) + (vyt - vyb) / (2 * h)).toDouble()
                magSum += (abs(scene.curlDx(x, y, freq)) + abs(scene.curlDy(x, y, freq))).toDouble()
                samples++
            }
        }
        val meanDiv = divSum / samples
        val meanMag = magSum / samples
        assertTrue("field is dead (mean magnitude $meanMag)", meanMag > 0.1)
        assertTrue("field is not divergence-free: $meanDiv vs $meanMag", meanDiv < meanMag * 0.05)
    }

    @Test
    fun everyNewStyleSurvivesTheExtremesOfItsControls() {
        // Randomize can roll any of these together; a scene that only holds up
        // at the defaults is a crash waiting for one button press.
        val extreme =
            SceneParams.DEFAULT.copy(
                speed = 4f,
                turbulence = 2f,
                audioDrive = 2f,
                beatResponse = 2f,
                density = 1f,
                endlessZoom = true,
                endlessZoomSpeed = 1.2f,
                mirror = true,
                flowStrength = 1f,
            )
        listOf(
            GalaxyScene(shaders, count = 300),
            AttractorScene(shaders, count = 300),
            StormScene(shaders, count = 300),
            InkflowScene(shaders, count = 300),
        ).forEach { scene ->
            run(scene, 4f, extreme)
            rows(scene).forEachIndexed { i, r ->
                r.forEachIndexed { c, v ->
                    assertTrue("${scene.id}: particle $i component $c is not finite: $v", v.isFinite())
                }
            }
        }
    }
}
