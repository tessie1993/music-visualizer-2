package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Orbital rings: particles circle the center on per-band radii; bass swells
 * the whole system, each particle's band energy drives its speed and glow.
 */
class OrbitScene(
    shaders: ShaderSources,
    count: Int = 2200,
) : ParticleSceneBase(SceneIds.ORBITS, count, shaders) {
    /** Square units - rings are rings. */
    override val aspectCorrected: Boolean get() = true

    private val random = Random(7)
    private val angle = FloatArray(count) { random.nextFloat() * 2f * PI.toFloat() }
    private val radius = FloatArray(count) { 0.12f + random.nextFloat() * 0.95f }
    private val speed =
        FloatArray(count) { (0.2f + random.nextFloat() * 0.6f) * (if (random.nextBoolean()) 1f else -1f) }
    private val band = IntArray(count) { random.nextInt(64) }
    private val wobble = FloatArray(count) { random.nextFloat() * 2f * PI.toFloat() }

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        val bands = features.bands
        val n = bands.size
        val swell = 1f + features.bass * p.audioDrive * 0.15f
        for (i in 0 until count) {
            if (p.endlessZoom) {
                // Orbits have no free px/py; endless zoom = radii drifting
                // outward, respawning small so rings flow past the camera.
                radius[i] *= 1f + p.endlessZoomSpeed * 1.5f * dt
                if (radius[i] > 1.6f) radius[i] = 0.08f
            }
            val e = (bands[band[i] % n] * p.audioDrive).coerceIn(0f, 1.5f)
            val angularRate = speed[i] * p.speed * (0.4f + e)
            angle[i] += angularRate * dt
            val wob = 1f + 0.07f * p.turbulence * sin(angle[i] * 3f + wobble[i])
            val r = radius[i] * swell * (1f + e * 0.08f) * wob
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = cos(angle[i]) * r
            vertexData[o + 1] = sin(angle[i]) * r
            vertexData[o + 2] = 2.5f + e * 16f
            vertexData[o + 3] = radius[i].coerceIn(0f, 1f)
            vertexData[o + 4] = e.coerceIn(0f, 1f)
            // Tangent of the circle the particle is riding: d/dt of the two
            // lines above. Orbits are the one scene with no stored velocity,
            // and without this its billboards would never lean into the arc.
            vertexData[o + VELOCITY_OFFSET] = -sin(angle[i]) * r * angularRate
            vertexData[o + VELOCITY_OFFSET + 1] = cos(angle[i]) * r * angularRate
        }
    }
}
