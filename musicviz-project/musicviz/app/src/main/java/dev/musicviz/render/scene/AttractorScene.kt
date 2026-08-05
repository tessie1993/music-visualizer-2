package dev.musicviz.render.scene

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * Strange attractor: every particle is an ITERATE of the same chaotic map, so
 * the picture is the attractor's own filament structure rather than a cloud of
 * independently simulated objects.
 *
 * This is the one style in the family whose shape is not produced by a force
 * law at all. Peter de Jong's map,
 *
 *     x' = sin(a*y) - cos(b*x)
 *     y' = sin(c*x) - cos(d*y)
 *
 * has no closed form and no equilibrium; iterating it from any starting point
 * converges onto a set whose folded, thread-like structure changes completely
 * for small changes in (a, b, c, d). Driving those four coefficients from the
 * audio therefore does something no amount of force-field tuning can: it
 * remakes the STRUCTURE of the image on the music rather than pushing existing
 * structure around. Between coefficient changes the population re-converges
 * within a handful of iterations, which is what makes it morph rather than
 * jump.
 *
 * Two details keep it stable as a visual. Coefficients are smoothed toward
 * their audio targets instead of tracking them per frame, because the map is
 * chaotic in its parameters as well as its state and untamed coefficients
 * strobe. And a small share of the population is re-seeded every frame from a
 * fresh random point, so any particle that wandered into a low-density basin
 * (or landed exactly on a fixed point) rejoins the attractor.
 *
 * Music mapping: bass and mids bend two coefficients each, treble adds fine
 * jitter, a beat kicks the coefficients (Beat response scales the kick) so the
 * whole structure snaps to a new shape and settles. Speed sets the iteration
 * rate, Turbulence the jitter, Audio drive how far the coefficients travel.
 */
class AttractorScene(
    shaders: ShaderSources,
    count: Int = 4200,
) : ParticleSceneBase(SceneIds.ATTRACTOR, count, shaders) {
    /** Square units - the attractor's own proportions are the subject. */
    override val aspectCorrected: Boolean get() = true

    private companion object {
        /** de Jong's attractor lives inside about +-2; this fits it to clip. */
        const val FIT = 0.46f

        /** Coefficient smoothing per second - the anti-strobe term. */
        const val TRACK = 2.2f

        /** Iterations per second at Speed 1. Not per frame: rate must be dt-based. */
        const val ITER_HZ = 26f
    }

    private val random = Random(31)
    private val px = FloatArray(count) { random.nextFloat() * 2f - 1f }
    private val py = FloatArray(count) { random.nextFloat() * 2f - 1f }

    /** Last step length, the "how fast is this iterate moving" energy source. */
    private val step = FloatArray(count)
    private val seed = FloatArray(count) { random.nextFloat() }

    private var a = -2.0f
    private var b = -2.34f
    private var c = 1.18f
    private var d = 2.1f
    private var t = 0f
    private var iterAcc = 0f
    private var beatKick = 0f
    private var prevBeat = false
    private var reseedCursor = 0

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        t += dt * p.speed
        val drive = p.audioDrive.coerceIn(0f, 2f)
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge) beatKick = p.beatResponse.coerceIn(0f, 2f) * features.beatImpulse
        beatKick = (beatKick - dt * 1.6f).coerceAtLeast(0f)

        // Targets: slow autonomous drift plus the band energies, so the shape
        // keeps evolving through quiet passages instead of freezing.
        val bass = (features.bass * drive).coerceIn(0f, 1.5f)
        val mid = (features.mid * drive).coerceIn(0f, 1.5f)
        val treble = (features.treble * drive).coerceIn(0f, 1.5f)
        val ta = -2.0f + 0.55f * sin(t * 0.13f) + bass * 0.45f + beatKick * 0.30f
        val tb = -2.34f + 0.50f * cos(t * 0.11f) - mid * 0.40f - beatKick * 0.22f
        val tc = 1.18f + 0.45f * sin(t * 0.17f + 1.3f) + mid * 0.35f
        val td = 2.10f + 0.40f * cos(t * 0.09f + 2.1f) + treble * 0.30f
        val k = min(1f, TRACK * dt)
        a += (ta - a) * k
        b += (tb - b) * k
        c += (tc - c) * k
        d += (td - d) * k

        // Fixed iteration RATE: at 120 Hz the map must not run twice as fast.
        iterAcc += ITER_HZ * p.speed.coerceIn(0.1f, 3f) * dt
        val iterations = iterAcc.toInt().coerceIn(0, 4)
        iterAcc -= iterations
        val jitter = p.turbulence.coerceIn(0f, 2f) * 0.02f + treble * 0.012f

        // Re-seed a sliver of the population every frame (a full sweep takes
        // ~2 s at 60 Hz), so orphaned iterates always find their way back.
        val reseed = (count / 128).coerceAtLeast(1)
        repeat(reseed) {
            val i = reseedCursor
            reseedCursor = (reseedCursor + 1) % count
            px[i] = random.nextFloat() * 2f - 1f
            py[i] = random.nextFloat() * 2f - 1f
        }

        for (i in 0 until count) {
            var x = px[i]
            var y = py[i]
            var moved = 0f
            repeat(iterations) {
                val nx = sin(a * y) - cos(b * x)
                val ny = sin(c * x) - cos(d * y)
                moved = abs(nx - x) + abs(ny - y)
                x = nx
                y = ny
            }
            if (jitter > 0f) {
                x += (random.nextFloat() - 0.5f) * jitter
                y += (random.nextFloat() - 0.5f) * jitter
            }
            // The map is bounded, but a NaN from a denormal would latch
            // forever; a cheap finite check costs nothing next to the sines.
            if (!x.isFinite() || !y.isFinite()) {
                x = random.nextFloat() * 2f - 1f
                y = random.nextFloat() * 2f - 1f
                moved = 0f
            }
            px[i] = x
            py[i] = y
            step[i] += (moved - step[i]) * min(1f, 6f * dt)

            val energy = (0.12f + step[i] * 0.55f + bass * 0.25f).coerceIn(0f, 1f)
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = x * FIT
            vertexData[o + 1] = y * FIT
            vertexData[o + 2] = 2f + energy * 9f
            // Hue by how far this iterate travels: the dense, slow filaments
            // and the fast sparse sheets separate into different colours.
            vertexData[o + 3] = (step[i] * 0.32f + seed[i] * 0.12f).coerceIn(0f, 1f)
            vertexData[o + 4] = energy
            // An iterate teleports across the attractor, so the true delta is
            // meaningless as a streak. A small share of the SMOOTHED step
            // gives the filaments a directionless shimmer without smearing.
            vertexData[o + VELOCITY_OFFSET] = 0f
            vertexData[o + VELOCITY_OFFSET + 1] = 0f
        }
    }

    /** Exposed for the headless test: the map itself, no state, no audio. */
    internal fun mapPoint(
        x: Float,
        y: Float,
        out: FloatArray,
    ) {
        out[0] = sin(a * y) - cos(b * x)
        out[1] = sin(c * x) - cos(d * y)
    }

    internal fun coefficients(): FloatArray = floatArrayOf(a, b, c, d)

    init {
        // Warm the population onto the attractor so the first frame is the
        // shape, not the uniform noise it was seeded from.
        val out = FloatArray(2)
        repeat(24) {
            for (i in 0 until count) {
                mapPoint(px[i], py[i], out)
                px[i] = out[0]
                py[i] = out[1]
            }
        }
        reseedCursor = random.nextInt(0..count - 1)
    }
}
