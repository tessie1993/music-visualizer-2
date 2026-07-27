package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Firefly swarm: particles chase a wandering attractor; treble scatters
 * them, beats relocate the attractor. Boids-lite with O(n) cost.
 */
class SwarmScene(shaders: ShaderSources, count: Int = 2200) :
    ParticleSceneBase(SceneIds.SWARM, count, shaders) {
    private val random = Random(11)
    private val px = FloatArray(count) { random.nextFloat() * 2f - 1f }
    private val py = FloatArray(count) { random.nextFloat() * 2f - 1f }
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val phase = FloatArray(count) { random.nextFloat() }
    private var ax = 0f
    private var ay = 0f
    private var t = 0f

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        t += dt * p.speed
        if (features.beat && p.beatResponse > 0.2f) {
            ax = random.nextFloat() * 1.2f - 0.6f
            ay = random.nextFloat() * 1.2f - 0.6f
        } else {
            ax = sin(t * 0.4f) * 0.5f
            ay = cos(t * 0.31f) * 0.5f
        }
        val pull = (0.8f + features.bass * p.audioDrive * 2f) * p.speed
        val scatter = features.treble * p.audioDrive * (0.5f + p.turbulence)
        for (i in 0 until count) {
            if (p.endlessZoom) {
                val flow = p.endlessZoomSpeed * 1.5f * dt
                px[i] += px[i] * flow
                py[i] += py[i] * flow
            }
            var dx = ax - px[i]
            var dy = ay - py[i]
            vx[i] += dx * pull * dt
            vy[i] += dy * pull * dt
            vx[i] += (random.nextFloat() - 0.5f) * scatter * dt * 8f
            vy[i] += (random.nextFloat() - 0.5f) * scatter * dt * 8f
            vx[i] *= 1f - 1.2f * dt
            vy[i] *= 1f - 1.2f * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            val flicker = 0.5f + 0.5f * sin((t + phase[i]) * 12f)
            val e = (features.mid * p.audioDrive * flicker).coerceIn(0.05f, 1f)
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = px[i]
            vertexData[o + 1] = py[i]
            vertexData[o + 2] = 2.5f + e * 12f
            vertexData[o + 3] = phase[i]
            vertexData[o + 4] = e
        }
    }
}
