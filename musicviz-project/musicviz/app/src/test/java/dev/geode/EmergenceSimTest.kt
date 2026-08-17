package dev.geode

import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FlowField
import dev.geode.render.scene.EmergenceField
import dev.geode.render.scene.EmergenceScene
import dev.geode.render.scene.EmergenceSim
import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The particle simulation, stepped headlessly - the only way to catch the
 * failures a screenshot cannot: a chaotic field latching NaN, a population
 * collapsing to a point or starving to mass extinction, audio that changes
 * nothing.
 */
class EmergenceSimTest {
    private companion object {
        const val FRAME = 1f / 60f
    }

    private fun features(
        bass: Float = 0.3f,
        mid: Float = 0.3f,
        treble: Float = 0.2f,
        impulse: Float = 0f,
    ) = AudioFeatures(
        bands = FloatArray(64) { 0.2f },
        waveform = FloatArray(64),
        rms = 0.3f,
        bass = bass,
        mid = mid,
        treble = treble,
        beatStrength = impulse,
        beatPhase = 0f,
        transient = impulse,
    )

    private fun sim(
        field: Int = EmergenceField.AUTO,
        seed: Long = 3L,
    ) = EmergenceSim(count = 400, seed = seed).also { it.field = field }

    private fun rows(sim: EmergenceSim): List<FloatArray> {
        val stride = EmergenceSim.FLOATS_PER_PARTICLE
        return (0 until sim.count).map { i ->
            sim.records.copyOfRange(i * stride, i * stride + stride)
        }
    }

    private fun meanSpeed(sim: EmergenceSim): Float =
        rows(sim)
            .map { r ->
                val vx = r[EmergenceSim.VELOCITY_OFFSET]
                val vy = r[EmergenceSim.VELOCITY_OFFSET + 1]
                sqrt(vx * vx + vy * vy)
            }.average()
            .toFloat()

    @Test
    fun `a minute of simulation stays finite and inside the world`() {
        val s = sim()
        repeat(60 * 60) { s.step(features(), FRAME) }
        rows(s).forEachIndexed { i, r ->
            assertTrue("particle $i has a non-finite record", r.all { it.isFinite() })
            assertTrue(
                "particle $i escaped to ${r[0]}, ${r[1]}",
                abs(r[0]) <= EmergenceSim.RESPAWN_EDGE && abs(r[1]) <= EmergenceSim.RESPAWN_EDGE,
            )
        }
    }

    @Test
    fun `every concrete field keeps a live, spread population`() {
        for (field in EmergenceField.CONCRETE_FIELDS) {
            val s = sim(field = field)
            repeat(600) { s.step(features(), FRAME) }
            val xs = rows(s).map { it[0] }
            val mean = xs.average().toFloat()
            val variance = xs.map { (it - mean) * (it - mean) }.average().toFloat()
            assertTrue("field $field collapsed the population to a point", variance > 1e-4f)
            assertTrue("field $field froze the population", meanSpeed(s) > 1e-3f)
        }
    }

    @Test
    fun `bass drives the population harder`() {
        val quiet = sim(field = EmergenceField.THOMAS, seed = 5L)
        val loud = sim(field = EmergenceField.THOMAS, seed = 5L)
        repeat(300) {
            quiet.step(features(bass = 0.05f), FRAME)
            loud.step(features(bass = 1.2f), FRAME)
        }
        assertTrue(
            "bass ${meanSpeed(loud)} vs quiet ${meanSpeed(quiet)}: the music does not move the particles",
            meanSpeed(loud) > meanSpeed(quiet) * 1.3f,
        )
    }

    @Test
    fun `a beat scatters the population outward`() {
        val calm = sim(field = EmergenceField.BLOOM, seed = 9L)
        val hit = sim(field = EmergenceField.BLOOM, seed = 9L)
        repeat(240) {
            calm.step(features(), FRAME)
            hit.step(features(), FRAME)
        }
        repeat(12) {
            calm.step(features(), FRAME)
            hit.step(features(impulse = 1f), FRAME)
        }
        assertTrue(
            "a full-strength impulse did not add motion",
            meanSpeed(hit) > meanSpeed(calm) * 1.15f,
        )
    }

    @Test
    fun `silence does not cause mass extinction`() {
        val s = sim()
        repeat(60 * 30) { s.step(features(bass = 0f, mid = 0f, treble = 0f), FRAME) }
        val alive = rows(s).count { it[4] > EmergenceSim.DEATH_ENERGY }
        assertTrue(
            "only $alive of ${s.count} particles survive silence - the idle look is a respawn flicker",
            alive > s.count * 8 / 10,
        )
    }

    @Test
    fun `identical seeds and input replay identically`() {
        val a = sim(seed = 21L)
        val b = sim(seed = 21L)
        repeat(200) {
            a.step(features(bass = 0.6f, impulse = if (it % 30 == 0) 0.8f else 0f), FRAME)
            b.step(features(bass = 0.6f, impulse = if (it % 30 == 0) 0.8f else 0f), FRAME)
        }
        assertTrue("the sim is nondeterministic under a fixed seed", a.records.contentEquals(b.records))
    }

    @Test
    fun `an absurd dt is clamped instead of exploding`() {
        val s = sim(field = EmergenceField.DEJONG)
        repeat(20) { s.step(features(bass = 1.4f), 10f) }
        rows(s).forEachIndexed { i, r ->
            assertTrue("particle $i went non-finite under a huge dt", r.all { it.isFinite() })
        }
    }

    @Test
    fun `the growth bell rewards the tuned density and punishes the extremes`() {
        val mu = 0.3f
        assertEquals(1f, EmergenceField.growth(mu, mu), 1e-4f)
        assertTrue("far-too-sparse must starve", EmergenceField.growth(0f, mu) < -0.5f)
        assertTrue("far-too-dense must starve", EmergenceField.growth(1f, mu) < -0.5f)
        assertTrue(
            "growth must fall monotonically away from mu",
            EmergenceField.growth(mu + 0.05f, mu) > EmergenceField.growth(mu + 0.2f, mu),
        )
    }

    @Test
    fun `the kernel is a ring, not a blob`() {
        assertEquals(1f, EmergenceField.kernel(0.5f), 1e-4f)
        assertTrue("self-distance must not count as neighborhood", EmergenceField.kernel(0f) < 0.05f)
        assertTrue("the rim must not count as neighborhood", EmergenceField.kernel(1f) < 0.05f)
    }

    /**
     * The wiring the review caught dead: the renderer writes the FlowField
     * grid onto the SCENE, the sim reads its own field, and nothing forwarded
     * one to the other - so "Particles ride the field" shipped as a no-op
     * while every sim-level test stayed green. This one goes through the
     * scene's own update, the seam that was broken.
     */
    @Test
    fun `the scene forwards the flow grid to the simulation`() {
        val params =
            SceneParams.DEFAULT.copy(
                trails = false,
                flowEnabled = true,
                flowAdvectParticles = true,
                flowStrength = 1f,
                emergenceField = EmergenceField.THOMAS,
            )
        val still = EmergenceScene(EmergenceScene.Shaders("", "", "", ""), EmergenceSim(count = 200, seed = 13L))
        val carried = EmergenceScene(EmergenceScene.Shaders("", "", "", ""), EmergenceSim(count = 200, seed = 13L))
        carried.flowGrid = uniformField(vx = 0.6f, vy = 0f)
        still.setParams(params)
        carried.setParams(params)
        repeat(120) {
            still.update(features(), FRAME)
            carried.update(features(), FRAME)
        }

        fun meanX(scene: EmergenceScene): Float {
            val stride = EmergenceSim.FLOATS_PER_PARTICLE
            val r = scene.records()
            return (0 until r.size / stride).map { r[it * stride] }.average().toFloat()
        }
        assertTrue(
            "the grid on the scene never reached the sim - the ride-the-field toggle is a no-op again",
            meanX(carried) - meanX(still) > 0.08f,
        )
    }

    private fun uniformField(
        vx: Float,
        vy: Float,
    ): FlowField.CpuGrid =
        FlowField.CpuGrid().apply {
            scale = 1f
            aspect = 1f
            for (cell in 0 until FlowField.CPU_GRID * FlowField.CPU_GRID) {
                data[cell * 4] = vx
                data[cell * 4 + 1] = vy
            }
        }

    @Test
    fun `records carry the layout the scene uploads`() {
        val s = sim()
        s.step(features(), FRAME)
        assertEquals(7, EmergenceSim.FLOATS_PER_PARTICLE)
        assertEquals(5, EmergenceSim.VELOCITY_OFFSET)
        assertEquals(s.count * EmergenceSim.FLOATS_PER_PARTICLE, s.records.size)
        rows(s).forEach { r ->
            assertTrue("size must be positive px", r[2] > 0f)
            assertTrue("energy must be published in 0..1", r[4] in 0f..1f)
        }
    }
}
