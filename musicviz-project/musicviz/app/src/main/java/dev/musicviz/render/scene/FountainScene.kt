package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.random.Random

/** Bottom-center fountain: bass drives launch power, gravity pulls back. */
class FountainScene(
    shaders: ShaderSources,
    count: Int = 2800,
) : ParticleSceneBase(SceneIds.FOUNTAIN, count, shaders) {
    private val random = Random(23)
    private val px = FloatArray(count)
    private val py = FloatArray(count) { -2f }
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val life = FloatArray(count)
    private val hue = FloatArray(count)
    private var nextIndex = 0

    // Fractional-emission carry so the rate is per-second, not per-frame
    // (per-frame doubled density and halved lifetimes on 120 Hz panels).
    private var emitAcc = 0f

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        val power = (features.bass * p.audioDrive).coerceIn(0f, 1.5f)
        emitAcc += (6 + power * 60f * (1f + p.beatResponse * 2f * features.motionImpulse)) * dt * 60f
        val emitCount = emitAcc.toInt().coerceAtMost(count)
        emitAcc -= emitCount
        repeat(emitCount) {
            val i = nextIndex
            nextIndex = (nextIndex + 1) % count
            px[i] = (random.nextFloat() - 0.5f) * 0.15f
            py[i] = -0.95f
            vx[i] = (random.nextFloat() - 0.5f) * (0.5f + p.turbulence)
            vy[i] = 1.1f + power * 1.6f + random.nextFloat() * 0.4f
            life[i] = 1.4f + random.nextFloat()
            hue[i] = random.nextFloat() * 0.4f + features.centroid * 0.5f
        }
        for (i in 0 until count) {
            if (p.endlessZoom) {
                val flow = p.endlessZoomSpeed * 1.5f * dt
                px[i] += px[i] * flow
                py[i] += py[i] * flow
            }
            if (life[i] > 0f) {
                life[i] -= dt
                vy[i] -= 1.5f * dt
                px[i] += vx[i] * dt * p.speed
                py[i] += vy[i] * dt * p.speed
            }
            val alive = life[i] > 0f && py[i] > -1.1f
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = px[i]
            vertexData[o + 1] = py[i]
            vertexData[o + 2] = if (alive) 3f + life[i] * 6f else 0f
            vertexData[o + 3] = hue[i]
            vertexData[o + 4] = if (alive) (life[i] * 0.7f).coerceIn(0f, 1f) else 0f
        }
    }
}
