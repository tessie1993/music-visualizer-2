package dev.geode.render.scene

import dev.geode.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The particle family's fluid style: tracers that live inside the shared
 * FlowField's Navier-Stokes velocity field, and push back into it.
 *
 * The other four fluid-adjacent styles in the app run the fluid ON the GPU and
 * draw its own point layer, which means the whole Customize particle surface -
 * shape, per-particle energy, the palette mapping the CPU styles use - does not
 * reach them. This one comes at it from the other side: an ordinary
 * [ParticleSceneBase] population, so every particle control applies as usual,
 * with the field supplying the motion instead of a hand-written force law.
 *
 * Three things make it a fluid style rather than "a particle style with drift":
 *
 *  1. [requiresFlowField] - the field is run and read back for this style
 *     whether or not `flowEnabled` is on. The style IS the field; a style that
 *     shows nothing until you find a checkbox in another tab is not a style.
 *  2. Inertia, not teleportation. Particles keep their own momentum and are
 *     pulled toward the local flow (v += (flow - v) * k, frame-rate
 *     independent), the same inertial model the GPU layer uses. Pure advection
 *     puts every tracer exactly on a streamline and the result looks like a
 *     texture; inertia is what makes them overshoot, cross and braid.
 *  3. Two-way coupling. Every frame a handful of the fastest particles inject a
 *     velocity kick back into the field through [flowKicks]. The field then
 *     carries a trace of where the population has been, which feeds back into
 *     where it goes next - the difference between ink IN water and ink ON it.
 *
 * A slow curl of its own runs underneath so the style still breathes when the
 * field is quiet (start of a track, near-silence), and never presents a frozen
 * screen.
 *
 * Music mapping: bass sets how hard the particles are pulled into the flow,
 * mids the injection strength (so louder passages stir the field harder),
 * treble the fine scatter, and a beat both widens the injected kicks and lifts
 * the population's energy. Speed scales the underlying curl, Turbulence its
 * spatial frequency, Flow strength (Motion tab) the field coupling itself.
 */
class InkflowScene(
    shaders: ShaderSources,
    count: Int = 3000,
) : ParticleSceneBase(SceneIds.INKFLOW, count, shaders) {
    private companion object {
        /** Per-1/60 s blend of local flow into particle velocity. */
        const val DRAG = 0.32f

        /**
         * Clock wrap: 200 * pi seconds (CymaticsScene TIME_WRAP convention).
         * Every read of [t] is sin/cos at a two-decimal rate (0.7, 0.5), and
         * k * 200pi is k * 100 whole turns - exact at the wrap.
         */
        const val TIME_WRAP_SECONDS = 628.31853f

        /** Kicks injected per frame; the field only needs a few strong ones. */
        const val KICKS_PER_FRAME = 6

        /** Clip-space radius of an injected kick. */
        const val KICK_RADIUS = 0.09f
    }

    private val random = Random(71)
    private val px = FloatArray(count) { random.nextFloat() * 2f - 1f }
    private val py = FloatArray(count) { random.nextFloat() * 2f - 1f }
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val seed = FloatArray(count) { random.nextFloat() }

    /** Smoothed speed per particle - the energy and hue source. */
    private val speedEnv = FloatArray(count)
    private var t = 0f
    private var beatEnv = 0f
    private var prevBeat = false
    private var kickCursor = 0
    private var reseedCursor = 0

    override val requiresFlowField: Boolean get() = true

    override fun simulate(
        features: AudioFeatures,
        dt: Float,
    ) {
        val p = sceneParams
        t = (t + dt * p.speed.coerceIn(0.1f, 3f)) % TIME_WRAP_SECONDS
        val drive = p.audioDrive.coerceIn(0f, 2f)
        val bass = (features.bass * drive).coerceIn(0f, 1.5f)
        val mid = (features.mid * drive).coerceIn(0f, 1.5f)
        val treble = (features.treble * drive).coerceIn(0f, 1.5f)
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge) beatEnv = p.beatResponse.coerceIn(0f, 2f) * features.beatImpulse
        beatEnv = (beatEnv - dt * 1.4f).coerceAtLeast(0f)

        // Frame-rate-independent inertia, the same shape the GPU layer uses:
        // DRAG is the per-1/60 s blend, so 120 Hz does not double the pull.
        val pull = 1f - (1f - DRAG * (0.5f + bass)).coerceIn(0f, 1f).pow(dt * 60f)
        val freq = 2.2f + p.turbulence.coerceIn(0f, 2f) * 3.4f
        // Amplitude of the fallback curl: enough to stay alive, small enough
        // that the real field dominates the moment it has anything to say.
        val calm = 0.16f + treble * 0.10f
        // Baseline scatter, not just the audio-driven part: inertia pulls
        // tracers onto the field's attracting streamlines and they collapse
        // into a few hard threads within seconds without it.
        val scatter = 0.07f + treble * 0.25f + p.turbulence * 0.20f

        flowKicks.clear()
        var fastest = 0f
        var fastestIdx = 0

        // Stochastic reseeding, the same trick the GPU curl layer uses. Drag
        // pulls every tracer onto the field's attracting streamlines, so left
        // alone the population collapses into a handful of hard threads with
        // dead space between them within seconds - "ink" that has finished
        // mixing. Recycling a sliver per frame (a full sweep takes ~8 s), and
        // preferentially the ones that have stalled, keeps the field seeded
        // everywhere without any visible popping: a reborn particle starts at
        // zero speed, which is also its dimmest.
        val sweep = (count / 480).coerceAtLeast(1)
        repeat(sweep) {
            val i = reseedCursor
            reseedCursor = (reseedCursor + 1) % count
            if (speedEnv[i] < 0.02f || random.nextFloat() < 0.25f) {
                px[i] = random.nextFloat() * 2.2f - 1.1f
                py[i] = random.nextFloat() * 2.2f - 1.1f
                vx[i] = 0f
                vy[i] = 0f
                speedEnv[i] = 0f
            }
        }

        for (i in 0 until count) {
            var x = px[i]
            var y = py[i]
            // Analytic curl of a scalar potential: divergence-free by
            // construction (Bridson, SIGGRAPH 2007), so the fallback motion
            // swirls instead of piling particles into sinks.
            val cx = -curlDy(x, y, freq) * calm
            val cy = curlDx(x, y, freq) * calm
            vx[i] += (cx - vx[i]) * pull
            vy[i] += (cy - vy[i]) * pull
            if (scatter > 0f) {
                vx[i] += (random.nextFloat() - 0.5f) * scatter * dt * 4f
                vy[i] += (random.nextFloat() - 0.5f) * scatter * dt * 4f
            }
            if (p.endlessZoom) {
                val flow = p.endlessZoomSpeed * 1.4f * dt
                x += x * flow
                y += y * flow
            }
            x += vx[i] * dt * p.speed
            y += vy[i] * dt * p.speed
            // Wrap at the edges rather than expire: a lifetime would punch
            // holes in the field on a fixed clock and read as flicker. The
            // only recycling is the stalled-tracer sweep above.
            if (x < -1.15f) x += 2.3f
            if (x > 1.15f) x -= 2.3f
            if (y < -1.15f) y += 2.3f
            if (y > 1.15f) y -= 2.3f
            px[i] = x
            py[i] = y

            val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            speedEnv[i] += (sp - speedEnv[i]) * (5f * dt).coerceAtMost(1f)
            if (sp > fastest) {
                fastest = sp
                fastestIdx = i
            }

            val energy = (0.12f + speedEnv[i] * 1.5f + beatEnv * 0.3f + bass * 0.2f).coerceIn(0f, 1f)
            val o = i * FLOATS_PER_PARTICLE
            vertexData[o] = x
            vertexData[o + 1] = y
            vertexData[o + 2] = 2.2f + energy * 12f + bass * 4f
            // Hue by local speed: slow eddies and fast filaments separate into
            // different colours, which is what makes the structure legible.
            vertexData[o + 3] = (speedEnv[i] * 1.1f + seed[i] * 0.16f).coerceIn(0f, 1f)
            vertexData[o + 4] = energy
            vertexData[o + VELOCITY_OFFSET] = vx[i]
            vertexData[o + VELOCITY_OFFSET + 1] = vy[i]
        }

        // The return leg: stride through the population so the injection sites
        // migrate instead of parking on whichever particles happen to be fast,
        // and always include the current fastest so a real jet is never missed.
        val strength = (0.35f + mid * 0.9f + beatEnv * 0.8f) * p.flowStrength.coerceIn(0f, 1f).coerceAtLeast(0.2f)
        val radius = KICK_RADIUS * (1f + beatEnv * 0.8f)
        val stride = (count / KICKS_PER_FRAME).coerceAtLeast(1)
        for (n in 0 until KICKS_PER_FRAME) {
            val i = if (n == 0) fastestIdx else (kickCursor + n * stride) % count
            flowKicks.add(px[i], py[i], vx[i] * strength, vy[i] * strength, radius)
        }
        kickCursor = (kickCursor + 17) % count
    }

    /**
     * Partial derivatives of the scalar potential the fallback curl is taken
     * from: the velocity is (-dPsi/dy, dPsi/dx), which is divergence-free by
     * construction. Split out rather than inlined so the headless test can pin
     * that property instead of taking the comment's word for it.
     */
    internal fun curlDx(
        x: Float,
        y: Float,
        freq: Float,
    ): Float = freq * cos(x * freq + t * 0.7f) * sin(y * freq * 0.8f - t * 0.5f)

    internal fun curlDy(
        x: Float,
        y: Float,
        freq: Float,
    ): Float = sin(x * freq + t * 0.7f) * freq * 0.8f * cos(y * freq * 0.8f - t * 0.5f)

    /** Exposed for the headless coupling test. */
    internal fun kickCount(): Int = flowKicks.size

    internal fun meanSpeed(): Float {
        var s = 0f
        for (i in 0 until count) s += abs(vx[i]) + abs(vy[i])
        return s / count
    }

    init {
        // Seed on a loose ring so the very first frame already has structure
        // for the field to pull apart.
        for (i in 0 until count) {
            val a = random.nextFloat() * 2f * PI.toFloat()
            val r = 0.25f + sqrt(random.nextFloat()) * 0.8f
            px[i] = cos(a) * r
            py[i] = sin(a) * r
        }
    }
}
