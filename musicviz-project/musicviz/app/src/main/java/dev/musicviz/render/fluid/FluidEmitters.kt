package dev.musicviz.render.fluid

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * F2 audio emitter system per FLUID_SIM v2 section 7.3: converts audio
 * features into capsule splat requests each frame. Pure Kotlin (no GL) so
 * the scheduler is unit-testable headless; [FluidScene] queues the result
 * into [FluidSim]. All positions/radii are sim space (y in [-1,1],
 * x in [-aspect, aspect]).
 *
 * Envelopes are self-contained: [beatEnv] is a beat-triggered
 * attack/release, [bassEnv] tracks bass with fast attack and slow release -
 * the same shape as the app's ADSR spec, local so the scene works without
 * user routing. Config fields are internal defaults until the Customize tab
 * phase (F5) maps them from SceneParams.
 */
internal class FluidEmitters(private val random: Random = Random.Default) {
    companion object {
        const val PATTERN_CENTER = 0
        const val PATTERN_RING = 1
        const val PATTERN_RANDOM = 2
        const val PATTERN_SPECTRUM_ARC = 3

        /** Base target speed in grid-velocity units for a full-strength splat. */
        const val BASE_SPEED = 6f
        private const val MAX_SPLATS_PER_FRAME = 16
    }

    // ---- config (F5 will surface these) ----
    var beatPattern = PATTERN_RING
    var beatSplats = 3
    var stirrers = 2
    var stirrerSpeed = 1.0f
    var bassPump = false
    var sparkle = true
    var splatRadius = 0.12f
    var radiusPulse = 0.4f
    var paletteCycleSpeed = 0.5f

    // ---- envelopes (read after tick) ----
    var beatEnv = 0f
        private set
    var bassEnv = 0f
        private set

    private val stirrerAngle = FloatArray(4) { it * 1.7f }
    private val stirrerPrevX = FloatArray(4) { Float.NaN }
    private val stirrerPrevY = FloatArray(4) { Float.NaN }
    private var trebleMean = 0.05f
    private var palettePhase = 0f

    /** Advances envelopes + emitters; returns this frame's splat requests. */
    fun tick(
        f: AudioFeatures,
        dt: Float,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
    ): List<FluidSim.Splat> {
        // Envelopes: beat -> instant attack, ~0.3 s release; bass follower.
        beatEnv = if (f.beat) 1f else beatEnv * exp(-dt / 0.3f)
        val bassTarget = (f.bass * 1.2f).coerceIn(0f, 1f)
        bassEnv +=
            (bassTarget - bassEnv) *
            (if (bassTarget > bassEnv) (dt / 0.03f) else (dt / 0.4f)).coerceAtMost(1f)
        trebleMean += (f.treble - trebleMean) * 0.05f
        palettePhase = (palettePhase + dt * paletteCycleSpeed * 0.05f) % 1f

        val out = ArrayList<FluidSim.Splat>()
        val radius = splatRadius * (1f + radiusPulse * beatEnv)
        val speed = BASE_SPEED * (0.4f + 1.6f * f.bass) * (0.3f + 0.7f * beatEnv)

        stirrerSplats(out, f, dt, aspect, baseHue, hueSpan, radius)
        if (f.beat && beatSplats > 0) beatSplats(out, f, aspect, baseHue, hueSpan, radius, speed)
        if (sparkle && f.treble > trebleMean * 1.6f && f.treble > 0.08f) {
            sparkleSplats(out, aspect, baseHue, hueSpan, radius)
        }
        if (bassPump && bassEnv > 0.15f) pumpSplats(out, baseHue, hueSpan, radius)

        return if (out.size > MAX_SPLATS_PER_FRAME) out.subList(0, MAX_SPLATS_PER_FRAME) else out
    }

    private fun stirrerSplats(
        out: MutableList<FluidSim.Splat>,
        f: AudioFeatures,
        dt: Float,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
    ) {
        val bands = floatArrayOf(f.bass, f.mid, f.treble, f.rms)
        val n = stirrers.coerceIn(0, 4)
        for (i in 0 until n) {
            val band = bands[i % bands.size]
            val r = 0.35f + 0.4f * (if (n == 1) 0.5f else i / (n - 1f))
            stirrerAngle[i] += dt * stirrerSpeed * (0.3f + band * 1.7f) * (if (i % 2 == 0) 1f else -1f)
            val x = cos(stirrerAngle[i]) * r * aspect.coerceAtMost(1.4f)
            val y = sin(stirrerAngle[i]) * r
            val px = stirrerPrevX[i]
            val py = stirrerPrevY[i]
            if (!px.isNaN()) {
                val invDt = 1f / dt.coerceAtLeast(1e-3f)
                val (cr, cg, cb) = hsv((baseHue + palettePhase + i * hueSpan / 4f) % 1f, 0.85f, 1f)
                val amp = 0.1f + 0.55f * band
                out +=
                    FluidSim.Splat(
                        prevX = px, prevY = py, curX = x, curY = y,
                        radius = radius,
                        velX = (x - px) * invDt * 0.22f,
                        velY = (y - py) * invDt * 0.22f,
                        r = cr * amp, g = cg * amp, b = cb * amp,
                    )
            }
            stirrerPrevX[i] = x
            stirrerPrevY[i] = y
        }
    }

    private fun beatSplats(
        out: MutableList<FluidSim.Splat>,
        f: AudioFeatures,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
        speed: Float,
    ) {
        val n = beatSplats.coerceIn(1, 8)
        val dyeGain = 1.5f * (0.15f + 0.85f * beatEnv)
        for (i in 0 until n) {
            val frac = i / n.toFloat()
            val hue = (baseHue + palettePhase + frac * hueSpan) % 1f
            val (cr, cg, cb) = hsv(hue, 0.9f, 1f)
            when (beatPattern) {
                PATTERN_CENTER -> {
                    val a = frac * 2f * PI.toFloat()
                    out += capsule(0f, 0f, cos(a) * 0.06f, sin(a) * 0.06f, radius, cos(a) * speed, sin(a) * speed, cr, cg, cb, dyeGain)
                }
                PATTERN_RANDOM -> {
                    val x = (random.nextFloat() * 2f - 1f) * 0.8f * aspect
                    val y = (random.nextFloat() * 2f - 1f) * 0.8f
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    out +=
                        capsule(
                            x, y, x + cos(a) * 0.05f, y + sin(a) * 0.05f, radius,
                            cos(a) * speed, sin(a) * speed, cr, cg, cb, dyeGain,
                        )
                }
                PATTERN_SPECTRUM_ARC -> {
                    val bandIdx = (frac * (f.bands.size - 1)).toInt().coerceIn(0, f.bands.size - 1)
                    val bandE = f.bands[bandIdx].coerceIn(0f, 1.5f)
                    val x = (frac * 2f - 1f) * 0.7f * aspect
                    val y = -0.75f
                    val v = speed * (0.4f + 1.6f * bandE) / (0.4f + 1.6f * f.bass).coerceAtLeast(0.4f)
                    out += capsule(x, y, x, y + 0.06f, radius, 0f, v, cr, cg, cb, dyeGain * (0.4f + bandE))
                }
                else -> { // PATTERN_RING: tangential -> instant vortex
                    val a = frac * 2f * PI.toFloat() + palettePhase * 6f
                    val x = cos(a) * 0.55f
                    val y = sin(a) * 0.55f
                    val tx = -sin(a)
                    val ty = cos(a)
                    out +=
                        capsule(
                            x - tx * 0.04f, y - ty * 0.04f, x + tx * 0.04f, y + ty * 0.04f, radius,
                            tx * speed, ty * speed, cr, cg, cb, dyeGain,
                        )
                }
            }
        }
    }

    private fun sparkleSplats(
        out: MutableList<FluidSim.Splat>,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
    ) {
        repeat(1 + random.nextInt(2)) {
            val x = (random.nextFloat() * 2f - 1f) * 0.8f * aspect
            val y = 0.1f + random.nextFloat() * 0.75f
            val a = random.nextFloat() * 2f * PI.toFloat()
            val (cr, cg, cb) = hsv((baseHue + palettePhase + hueSpan * 0.5f) % 1f, 0.35f, 1f)
            out +=
                capsule(
                    x, y, x + cos(a) * 0.02f, y + sin(a) * 0.02f, radius * 0.35f,
                    cos(a) * BASE_SPEED * 0.5f, sin(a) * BASE_SPEED * 0.5f, cr, cg, cb, 0.9f,
                )
        }
    }

    private fun pumpSplats(
        out: MutableList<FluidSim.Splat>,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
    ) {
        val v = BASE_SPEED * bassEnv
        for (i in 0 until 6) {
            val a = i / 6f * 2f * PI.toFloat()
            val (cr, cg, cb) = hsv((baseHue + palettePhase) % 1f, 0.95f, 1f)
            out +=
                capsule(
                    cos(a) * 0.06f, sin(a) * 0.06f, cos(a) * 0.14f, sin(a) * 0.14f, radius,
                    cos(a) * v, sin(a) * v, cr, cg, cb, 0.3f + 0.9f * bassEnv,
                )
        }
    }

    private fun capsule(
        px: Float,
        py: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        vx: Float,
        vy: Float,
        r: Float,
        g: Float,
        b: Float,
        dyeGain: Float,
    ) = FluidSim.Splat(px, py, cx, cy, radius, vx, vy, r * dyeGain, g * dyeGain, b * dyeGain)

    internal fun hsv(
        h: Float,
        s: Float,
        v: Float,
    ): Triple<Float, Float, Float> {
        val hh = ((h % 1f) + 1f) % 1f
        val i = (hh * 6f).toInt() % 6
        val fr = hh * 6f - (hh * 6f).toInt()
        val p = v * (1f - s)
        val q = v * (1f - fr * s)
        val t = v * (1f - (1f - fr) * s)
        return when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
    }
}
