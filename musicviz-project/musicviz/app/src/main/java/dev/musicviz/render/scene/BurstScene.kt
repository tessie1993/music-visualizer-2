package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Beat-driven fireworks: each detected beat launches a radial burst. */
class BurstScene(
    shaders: ShaderSources,
    count: Int = 3000,
) : ParticleSceneBase(SceneIds.BURSTS, count, shaders) {
    /** Square units - a detonation throws debris equally in every direction. */
    override val aspectCorrected: Boolean get() = true

    private val random = Random(7)
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val life = FloatArray(count)
    private val hue = FloatArray(count)
    private var nextIndex = 0

    // Beat snapshots outlive one display frame (analysis ~62.5 Hz vs up to
    // 120 Hz draws); edge-detect like FluidEmitters so a beat fires once.
    private var prevBeat = false

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge && p.beatResponse > 0.05f) {
            val cx = if (p.endlessZoom) 0f else random.nextFloat() * 1.2f - 0.6f
            val cy = if (p.endlessZoom) 0f else random.nextFloat() * 1.2f - 0.6f
            val burstHue = random.nextFloat()
            // Firework size rides the graded impulse: soft beats launch small
            // shells, only real accents fill the sky.
            val n =
                ((180 + (features.bass * p.audioDrive * 220).toInt()) * p.beatResponse * features.beatImpulse)
                    .toInt()
                    .coerceIn(20, 900)
            repeat(n) {
                val i = nextIndex
                nextIndex = (nextIndex + 1) % count
                val angle = random.nextFloat() * 2f * PI.toFloat()
                val speed = 0.4f + random.nextFloat() * 1.1f * (0.5f + features.rms * p.audioDrive)
                px[i] = cx
                py[i] = cy
                vx[i] = cos(angle) * speed
                vy[i] = sin(angle) * speed
                life[i] = 0.8f + random.nextFloat() * 0.8f
                hue[i] = burstHue
            }
        }
        for (i in 0 until count) {
            if (life[i] > 0f) {
                life[i] -= dt
                vy[i] -= 0.35f * dt * (1f - if (p.endlessZoom) 1f else 0f)
                if (p.turbulence > 0f) {
                    vx[i] += (random.nextFloat() - 0.5f) * p.turbulence * 2f * dt
                    vy[i] += (random.nextFloat() - 0.5f) * p.turbulence * 2f * dt
                }
                if (p.endlessZoom) {
                    val flow = p.endlessZoomSpeed * 1.5f * dt
                    px[i] += px[i] * flow
                    py[i] += py[i] * flow
                }
                vx[i] *= 1f - 0.9f * dt
                vy[i] *= 1f - 0.4f * dt
                px[i] += vx[i] * dt * p.speed
                py[i] += vy[i] * dt * p.speed
            }
            val alive = life[i] > 0f
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = px[i]
            vertexData[o + 1] = py[i]
            vertexData[o + 2] = if (alive) 4f + life[i] * 10f else 0f
            vertexData[o + 3] = hue[i]
            vertexData[o + 4] = if (alive) life[i].coerceIn(0f, 1f) else 0f
            // Shell velocity, in the same units the position step above uses:
            // the billboard leans into it, so a burst reads as ejecta trails.
            vertexData[o + VELOCITY_OFFSET] = if (alive) vx[i] * p.speed else 0f
            vertexData[o + VELOCITY_OFFSET + 1] = if (alive) vy[i] * p.speed else 0f
        }
    }
}
