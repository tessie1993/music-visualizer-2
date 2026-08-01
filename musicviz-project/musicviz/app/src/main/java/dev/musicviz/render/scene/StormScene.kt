package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Driven rain: a falling sheet with wind shear, gusts on the beat, and drops
 * that break into splashes when they hit the floor.
 *
 * The point of this one is the streak. Every other style in the family draws
 * particles whose velocity is incidental to their look; here the velocity IS
 * the look - the billboards stretch along their fall, so wind shear reads as
 * the whole sheet leaning, and a gust reads as the leaning changing. Which is
 * also why it needs the second population: a sheet of parallel streaks with no
 * event at the bottom looks like a texture, not weather. Drops that reach the
 * floor hand their impact point to a splash particle, so the storm has a
 * surface it is hitting.
 *
 * Music mapping: bass drives fall speed and drop size, mids the wind (signed,
 * so it swings), treble the fine scatter, and a beat fires a squall - a burst
 * of wind and brightness that decays over about a second, scaled by Beat
 * response. Turbulence stirs individual drops off the sheet; Speed scales the
 * whole thing; Density thins the rain out through the shared draw-count path.
 */
class StormScene(
    shaders: ShaderSources,
    count: Int = 3600,
) : ParticleSceneBase(SceneIds.STORM, count, shaders) {
    private companion object {
        /** Fraction of the population reserved for floor splashes. */
        const val SPLASH_SHARE = 0.18f

        /** Floor height in clip space; drops below this land. */
        const val FLOOR = -0.92f
    }

    // Rain is the one style whose subject IS the fall, so it asks the shared
    // billboard for a real streak instead of the default lean.
    override val stretchScale: Float get() = 2.4f
    override val stretchMax: Float get() = 5f

    private val random = Random(53)
    private val splashStart = ((1f - SPLASH_SHARE) * count).toInt().coerceIn(1, count - 1)

    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)

    /** Fall-speed multiplier per drop: the depth cue that gives the sheet layers. */
    private val depth = FloatArray(count) { 0.35f + random.nextFloat() * 1.05f }
    private val life = FloatArray(count)
    private var splashCursor = 0
    private var wind = 0f
    private var squall = 0f
    private var prevBeat = false

    init {
        // Staggered down the whole column so the first frame is rain in
        // flight, not a bar of drops released together.
        for (i in 0 until splashStart) respawnDrop(i, 1.15f - random.nextFloat() * 2.2f)
        for (i in splashStart until count) {
            life[i] = 0f
            vertexData[i * FLOATS_PER_PARTICLE + 2] = 0f
        }
    }

    private fun respawnDrop(
        i: Int,
        y: Float,
    ) {
        px[i] = random.nextFloat() * 2.6f - 1.3f
        py[i] = y
        vx[i] = 0f
        vy[i] = 0f
        life[i] = 1f
    }

    private fun spawnSplash(
        x: Float,
        power: Float,
    ) {
        // Two shards per impact, thrown apart and up: one drop landing should
        // read as an event, and a single shard reads as a stray drop.
        repeat(2) {
            val i = splashStart + splashCursor
            splashCursor = (splashCursor + 1) % (count - splashStart)
            px[i] = x
            py[i] = FLOOR
            val dir = if (random.nextBoolean()) 1f else -1f
            vx[i] = dir * (0.15f + random.nextFloat() * 0.45f) * power
            vy[i] = (0.35f + random.nextFloat() * 0.55f) * power
            life[i] = 0.30f + random.nextFloat() * 0.35f
        }
    }

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        val drive = p.audioDrive.coerceIn(0f, 2f)
        val bass = (features.bass * drive).coerceIn(0f, 1.5f)
        val mid = (features.mid * drive).coerceIn(0f, 1.5f)
        val treble = (features.treble * drive).coerceIn(0f, 1.5f)

        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge) {
            squall = p.beatResponse.coerceIn(0f, 2f) * features.beatImpulse
        }
        squall = (squall - dt * 1.1f).coerceAtLeast(0f)

        // Wind is smoothed, signed and swings on its own clock, so the sheet
        // leans one way and then the other instead of jittering.
        val windTarget = sin(features.centroid * 6.283f + mid * 2.2f) * (0.25f + mid * 0.75f) + squall * 0.7f
        wind += (windTarget - wind) * (2.4f * dt).coerceAtMost(1f)

        val fall = (1.15f + bass * 1.5f + squall * 0.8f) * p.speed
        val scatter = (treble * 0.5f + p.turbulence.coerceIn(0f, 2f) * 0.7f)

        for (i in 0 until splashStart) {
            if (p.endlessZoom) {
                val flow = p.endlessZoomSpeed * 1.2f * dt
                px[i] += px[i] * flow
            }
            val d = depth[i]
            vx[i] = wind * d + (random.nextFloat() - 0.5f) * scatter
            vy[i] = -fall * d
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            if (py[i] <= FLOOR) {
                // Only the nearer, faster drops are worth a splash; splashing
                // every drop turns the floor into a solid bar of light.
                if (d > 0.9f && random.nextFloat() < 0.35f) spawnSplash(px[i], (0.4f + d * 0.6f) * (0.6f + bass))
                respawnDrop(i, 1.15f)
            }
            if (px[i] < -1.35f) px[i] += 2.7f
            if (px[i] > 1.35f) px[i] -= 2.7f

            val energy = (0.14f + d * 0.35f + squall * 0.35f + bass * 0.2f).coerceIn(0f, 1f)
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = px[i]
            vertexData[o + 1] = py[i]
            vertexData[o + 2] = 1.6f + d * 4.2f + bass * 3f
            // Near drops are brighter AND further along the palette, which is
            // what separates the layers of the sheet by colour as well as size.
            vertexData[o + 3] = (0.08f + d * 0.30f + squall * 0.14f).coerceIn(0f, 1f)
            vertexData[o + 4] = energy
            vertexData[o + VELOCITY_OFFSET] = vx[i]
            vertexData[o + VELOCITY_OFFSET + 1] = vy[i]
        }

        for (i in splashStart until count) {
            val alive = life[i] > 0f
            if (alive) {
                life[i] -= dt
                vy[i] -= 3.2f * dt
                px[i] += vx[i] * dt * p.speed
                py[i] += vy[i] * dt * p.speed
            }
            val live = life[i] > 0f
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = px[i]
            vertexData[o + 1] = py[i]
            vertexData[o + 2] = if (live) 1.4f + life[i] * 7f else 0f
            vertexData[o + 3] = (0.42f + life[i] * 0.3f).coerceIn(0f, 1f)
            vertexData[o + 4] = if (live) (0.35f + life[i] * 1.4f).coerceIn(0f, 1f) else 0f
            vertexData[o + VELOCITY_OFFSET] = if (live) vx[i] * p.speed else 0f
            vertexData[o + VELOCITY_OFFSET + 1] = if (live) vy[i] * p.speed else 0f
        }
    }

    /** Exposed for the headless test of the wind smoothing. */
    internal fun windValue(): Float = wind

    internal fun splashRange(): IntRange = splashStart until count

    /** Sanity helper for tests: the sheet must never leave the visible band. */
    internal fun maxDropAbscissa(): Float {
        var m = 0f
        for (i in 0 until splashStart) m = maxOf(m, abs(px[i]))
        return m
    }
}
