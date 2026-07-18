package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Cosmic drift: band-tinted glow particles, bass swells, gentle curl. */
class NebulaScene(shaders: ShaderSources, count: Int = 2500) :
    ParticleSceneBase(SceneIds.NEBULA, count, shaders) {
    private val random = Random(42)
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val life = FloatArray(count)
    private val band = IntArray(count)

    init {
        for (i in 0 until count) respawn(i, calm = true)
    }

    private fun respawn(
        i: Int,
        calm: Boolean,
    ) {
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val center = sceneParams.endlessZoom
        val radius =
            when {
                center -> random.nextFloat() * 0.1f
                calm -> random.nextFloat() * 0.9f
                else -> random.nextFloat() * 0.15f
            }
        px[i] = cos(angle) * radius
        py[i] = sin(angle) * radius
        val speed = if (calm && !center) 0.02f else 0.25f + random.nextFloat() * 0.35f
        vx[i] = cos(angle) * speed
        vy[i] = sin(angle) * speed
        life[i] = 0.5f + random.nextFloat() * 1.5f
        band[i] = random.nextInt(64)
    }

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        val bands = features.bands
        val bandCount = bands.size
        val burst = features.bass * p.audioDrive > 0.55f || (features.beat && p.beatResponse > 0.2f)
        for (i in 0 until count) {
            val e = (bands[band[i] % bandCount] * p.audioDrive).coerceIn(0f, 1.5f)
            life[i] -= dt
            if (life[i] <= 0f) respawn(i, calm = !burst)
            val drive = (0.15f + e * 1.2f) * p.speed
            px[i] += vx[i] * drive * dt
            py[i] += vy[i] * drive * dt
            if (p.endlessZoom) {
                val flow = p.endlessZoomSpeed * 1.5f * dt
                px[i] += px[i] * flow
                py[i] += py[i] * flow
            }
            val curl = (0.4f + p.turbulence * 1.6f) * dt * p.speed
            val cx = -py[i] * curl
            val cy = px[i] * curl
            px[i] += cx
            py[i] += cy
            if (px[i] < -1.3f || px[i] > 1.3f || py[i] < -1.3f || py[i] > 1.3f) respawn(i, calm = true)
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = px[i]
            vertexData[o + 1] = py[i]
            vertexData[o + 2] = 3f + e * 22f + features.bass * p.audioDrive * 10f
            vertexData[o + 3] = 0.55f + (band[i] % bandCount) / bandCount.toFloat() * 0.8f
            vertexData[o + 4] = e.coerceIn(0f, 1f)
        }
    }
}
