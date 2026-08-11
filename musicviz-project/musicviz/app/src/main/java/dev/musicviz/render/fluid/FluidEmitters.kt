package dev.musicviz.render.fluid

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rebuilt audio emitter system: converts audio features into capsule splat
 * requests each frame, anchored to the [FluidChoreography] spawn/catch
 * points instead of fixed screen patterns - so WHERE the dye is injected
 * progresses through the track along with the particles.
 *
 * - Stirrers orbit the moving spawn anchors (dye trails follow the journey).
 * - Beat splats fire from the spawn anchors in the selected pattern.
 * - Catch points emit inward suction splats, so the dye visibly drains
 *   toward the same attractors that capture particles.
 * - Sparkle/pump keep their audio triggers, relocated onto the anchors.
 *
 * Pure Kotlin (no GL) so the scheduler is unit-testable headless;
 * [FluidScene] queues the result into [FluidSim]. All positions/radii are
 * sim space (y in [-1,1], x in [-aspect, aspect]).
 *
 * Envelopes are self-contained: [beatEnv] is a beat-triggered
 * attack/release, [bassEnv] tracks bass with fast attack and slow release -
 * the same shape as the app's ADSR spec, local so the scene works without
 * user routing.
 */
internal class FluidEmitters(
    private val random: Random = Random.Default,
) {
    companion object {
        const val PATTERN_CENTER = 0
        const val PATTERN_RING = 1
        const val PATTERN_RANDOM = 2
        const val PATTERN_SPECTRUM_ARC = 3

        /** Base target speed in grid-velocity units for a full-strength splat. */
        const val BASE_SPEED = 6f
        private const val MAX_SPLATS_PER_FRAME = 16

        /**
         * Suction-phase wrap: 200 * pi (CymaticsScene TIME_WRAP convention).
         * Both readers are cos/sin at two-decimal rates, so k * 200pi is
         * k * 100 whole turns - exactly periodic at the wrap.
         */
        private const val PHASE_WRAP_SECONDS = 628.31853f

        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /** "Beat response" slider domain (CustomizeTabs), neutral at 1. */
        const val MIN_BEAT_RESPONSE = 0f
        const val MAX_BEAT_RESPONSE = 2f

        /** Below this the beat pattern stops firing (BurstScene's gate). */
        const val BEAT_RESPONSE_GATE = 0.05f

        /**
         * Ceiling on the beat radius swell. Unreachable at the neutral beat
         * response (envelope <= 1 and radius pulse <= 1 give exactly 2), so it
         * only bites when the slider is pushed above 1 - where an unbounded
         * 3x splat radius would stamp most of the screen in one capsule.
         */
        private const val MAX_RADIUS_SWELL = 2f
    }

    /**
     * The spawn/catch anchor source. When null (FlowField's headless use)
     * emitters fall back to a single virtual orbit around the center.
     */
    var choreography: FluidChoreography? = null

    // ---- config (surfaced through SceneParams by FluidScene) ----
    var beatPattern = PATTERN_RING
    var beatSplats = 3
    var stirrers = 2
    var stirrerSpeed = 1.0f
    var bassPump = false
    var sparkle = true
    var splatRadius = 0.12f
    var radiusPulse = 0.4f
    var paletteCycleSpeed = 0.5f

    /** Emitter momentum multiplier (Customize "Splat force", 0..3). */
    var forceScale = 1f

    /** Suction splat strength at catch points, 0 disables. */
    var catchSuction = 1f

    /**
     * Customize "Beat response" (Behavior tab), 0..2, neutral at 1: the DEPTH
     * of the beat envelope every beat-driven emitter term rides - splat
     * radius pulse, splat momentum, beat-splat dye gain. The slider had no
     * reader at all on FLUID/WATER before; multiplying the envelope (rather
     * than any one term) is the same shape shader and particle scenes use,
     * and it is an exact no-op at 1.
     *
     * At (near) zero the beat pattern stops firing entirely, matching
     * `BurstScene`'s `beatResponse > 0.05f` gate, so "no beat response"
     * really means no beat events rather than very small ones.
     *
     * [FlowField]'s shared emitters deliberately leave this neutral: the
     * particle scenes that ride that field already apply the slider
     * themselves, and setting it here as well would apply it twice.
     */
    var beatResponse = 1f

    // ---- envelopes (read after tick) ----

    /**
     * Beat envelope AFTER [beatResponse]: what the emitters actually ride.
     * The raw attack/release lives in [beatEnvRaw] so the release curve stays
     * independent of the slider (scaling the state would make a lowered
     * slider also shorten the tail, and raising it back would not restore it).
     */
    var beatEnv = 0f
        private set

    private var beatEnvRaw = 0f

    var bassEnv = 0f
        private set

    private val stirrerAngle = FloatArray(4) { it * 1.7f }
    private val stirrerPrevX = FloatArray(4) { Float.NaN }
    private val stirrerPrevY = FloatArray(4) { Float.NaN }
    private var activeStirrers = 0
    private var trebleMean = 0.05f
    private var palettePhase = 0f
    private var suctionPhase = 0f
    private var suctionIndex = 0
    private var prevBeat = false

    /**
     * Per-frame scratch, all of it consumed inside one [tick] before anything
     * else can look at it and none of it escaping this object: the band
     * energies the stirrers index, the anchor position [anchor] reports, and
     * the dye colour each splat is built from. Fields rather than locals
     * because this whole class runs once per frame forever, and these were
     * a `FloatArray`, a boxed `Pair` per stirrer and per beat splat, and a
     * boxed `Triple` per splat respectively.
     */
    private val bands = FloatArray(4)
    private var anchorX = 0f
    private var anchorY = 0f
    private val splatRgb = FloatArray(3)

    /**
     * Advances envelopes + emitters; returns this frame's splat requests.
     *
     * Allocates the list it returns, so the returned value is a snapshot the
     * caller may keep. The scenes use the [tick] overload below, which fills
     * a list they own, instead.
     */
    fun tick(
        f: AudioFeatures,
        dt: Float,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
    ): List<FluidSim.Splat> {
        val out = ArrayList<FluidSim.Splat>(MAX_SPLATS_PER_FRAME)
        tick(f, dt, aspect, baseHue, hueSpan, out)
        return out
    }

    /**
     * [tick] appending into [out], which is cleared first: the scenes call
     * this every frame and immediately drain the result into their sim, so
     * they keep one list for the life of the scene rather than allocating an
     * `ArrayList` per frame in the draw path.
     *
     * The list is only safe to reuse because of that drain-immediately
     * contract - the splats are read before the next tick can clear them.
     */
    fun tick(
        f: AudioFeatures,
        dt: Float,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
        out: MutableList<FluidSim.Splat>,
    ) {
        out.clear()
        // Envelopes: beat -> instant attack to the beat's graded impulse
        // (plus budgeted off-grid transient texture), ~0.3 s release; bass
        // follower. The raw envelope carries the timing, "Beat response" the
        // depth.
        beatEnvRaw = max(f.motionImpulse, beatEnvRaw * exp(-dt / 0.3f))
        beatEnv = beatEnvRaw * beatResponse.coerceIn(MIN_BEAT_RESPONSE, MAX_BEAT_RESPONSE)
        val bassTarget = (f.bass * 1.2f).coerceIn(0f, 1f)
        bassEnv +=
            (bassTarget - bassEnv) *
            (if (bassTarget > bassEnv) (dt / 0.03f) else (dt / 0.4f)).coerceAtMost(1f)
        // dt-scaled EMA (~0.32 s time constant) so the sparkle trigger
        // threshold doesn't depend on frame rate.
        trebleMean += (f.treble - trebleMean) * (dt / 0.32f).coerceAtMost(1f)
        palettePhase = (palettePhase + dt * paletteCycleSpeed * 0.05f) % 1f
        // Wrapped at 200 * pi (CymaticsScene TIME_WRAP convention): both
        // consumers are cos/sin of suctionPhase at two-decimal rates (0.4,
        // 2.7), and k * 200pi is k * 100 whole turns - exact at the wrap.
        suctionPhase = (suctionPhase + dt) % PHASE_WRAP_SECONDS

        val radius = splatRadius * (1f + radiusPulse * beatEnv).coerceAtMost(MAX_RADIUS_SWELL)
        val speed = BASE_SPEED * forceScale * (0.4f + 1.6f * f.bass) * (0.3f + 0.7f * beatEnv)

        // Edge-detect the beat flag: the ~62.5 Hz analysis snapshot can be
        // consumed by several display frames, and level-triggered firing
        // doubled the splats per beat on 120 Hz screens.
        val beatEdge = f.beat && !prevBeat
        prevBeat = f.beat

        // Priority order fills the frame budget most-important first: beats
        // define the rhythm, stirrers the continuity, suction the drain,
        // sparkle/pump are garnish.
        if (beatEdge && beatSplats > 0 && beatResponse > BEAT_RESPONSE_GATE) {
            beatSplats(out, f, aspect, baseHue, hueSpan, radius, speed)
        }
        stirrerSplats(out, f, dt, aspect, baseHue, hueSpan, radius)
        suctionSplats(out, radius)
        if (sparkle && f.treble > trebleMean * 1.6f && f.treble > 0.08f) {
            sparkleSplats(out, aspect, baseHue, hueSpan, radius)
        }
        if (bassPump && bassEnv > 0.15f) pumpSplats(out, baseHue, hueSpan, radius)

        // Frame budget. Dropped from the tail in place rather than returned
        // as a subList view, which is another object on every over-budget
        // frame; the kept splats are the same first [MAX_SPLATS_PER_FRAME].
        while (out.size > MAX_SPLATS_PER_FRAME) out.removeAt(out.size - 1)
    }

    /**
     * Spawn-anchor position for slot [i]; virtual center orbit without one.
     * Reports through [anchorX]/[anchorY]: it is called once per stirrer and
     * once per beat splat, and a `Pair<Float, Float>` boxes both floats.
     */
    private fun anchor(
        i: Int,
        aspect: Float,
    ) {
        val c = choreography
        if (c != null) {
            val n = c.spawnCount.coerceIn(1, FluidChoreography.MAX_SPAWN)
            val a = c.spawns[i % n]
            anchorX = a.x
            anchorY = a.y
            return
        }
        val ang = suctionPhase * 0.4f + i * 2.1f
        anchorX = cos(ang) * 0.45f * aspect.coerceAtMost(1.4f)
        anchorY = sin(ang) * 0.45f
    }

    /**
     * Stirrers orbit the MOVING spawn anchors: each stirrer circles its
     * anchor at a small radius, so continuous dye trails travel with the
     * choreography instead of pinning to fixed screen circles.
     */
    private fun stirrerSplats(
        out: MutableList<FluidSim.Splat>,
        f: AudioFeatures,
        dt: Float,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
    ) {
        val n = stirrers.coerceIn(0, 4)
        // Re-enabled stirrers must not fire a splat from their stale previous
        // position (one giant velocity kick); reset history on count change.
        if (n != activeStirrers) {
            for (i in 0 until 4) {
                stirrerPrevX[i] = Float.NaN
                stirrerPrevY[i] = Float.NaN
            }
            activeStirrers = n
        }
        // With the stirrers off there is nothing below to read the bands for.
        if (n == 0) return
        bands[0] = f.bass
        bands[1] = f.mid
        bands[2] = f.treble
        bands[3] = f.rms
        for (i in 0 until n) {
            val band = bands[i % bands.size]
            anchor(i, aspect)
            val cxA = anchorX
            val cyA = anchorY
            val orbitR = 0.14f + 0.10f * (i % 3)
            // Wrapped to one turn: only ever read through cos/sin. Kotlin's
            // % keeps the sign, which trig is indifferent to.
            stirrerAngle[i] =
                (stirrerAngle[i] + dt * stirrerSpeed * (0.3f + band * 1.7f) * (if (i % 2 == 0) 1f else -1f)) % TWO_PI
            val x = cxA + cos(stirrerAngle[i]) * orbitR
            val y = cyA + sin(stirrerAngle[i]) * orbitR
            val px = stirrerPrevX[i]
            val py = stirrerPrevY[i]
            if (!px.isNaN()) {
                val invDt = 1f / dt.coerceAtLeast(1e-3f)
                hsv((baseHue + palettePhase + i * hueSpan / 4f) % 1f, 0.85f, 1f, splatRgb)
                val cr = splatRgb[0]
                val cg = splatRgb[1]
                val cb = splatRgb[2]
                val amp = 0.1f + 0.55f * band
                out +=
                    FluidSim.Splat(
                        prevX = px,
                        prevY = py,
                        curX = x,
                        curY = y,
                        radius = radius,
                        velX = (x - px) * invDt * 0.22f * forceScale,
                        velY = (y - py) * invDt * 0.22f * forceScale,
                        r = cr * amp,
                        g = cg * amp,
                        b = cb * amp,
                    )
            }
            stirrerPrevX[i] = x
            stirrerPrevY[i] = y
        }
    }

    /** Beat splats fire FROM the spawn anchors in the selected pattern. */
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
            hsv(hue, 0.9f, 1f, splatRgb)
            val cr = splatRgb[0]
            val cg = splatRgb[1]
            val cb = splatRgb[2]
            anchor(i, aspect)
            val ax = anchorX
            val ay = anchorY
            when (beatPattern) {
                PATTERN_CENTER -> {
                    // Radial burst out of each anchor.
                    val a = frac * 2f * PI.toFloat() + palettePhase * 6f
                    out +=
                        capsule(
                            ax,
                            ay,
                            ax + cos(a) * 0.06f,
                            ay + sin(a) * 0.06f,
                            radius,
                            cos(a) * speed,
                            sin(a) * speed,
                            cr,
                            cg,
                            cb,
                            dyeGain,
                        )
                }
                PATTERN_RANDOM -> {
                    val x = ax + (random.nextFloat() * 2f - 1f) * 0.25f
                    val y = ay + (random.nextFloat() * 2f - 1f) * 0.25f
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    out +=
                        capsule(
                            x,
                            y,
                            x + cos(a) * 0.05f,
                            y + sin(a) * 0.05f,
                            radius,
                            cos(a) * speed,
                            sin(a) * speed,
                            cr,
                            cg,
                            cb,
                            dyeGain,
                        )
                }
                PATTERN_SPECTRUM_ARC -> {
                    // Full-width spectrum readout; the baseline drifts with
                    // the anchor height but stays in the lower band so it
                    // always reads as a spectrum floor.
                    val bandE =
                        if (f.bands.isEmpty()) {
                            0.5f
                        } else {
                            val bandIdx = (frac * (f.bands.size - 1)).toInt().coerceIn(0, f.bands.size - 1)
                            f.bands[bandIdx].coerceIn(0f, 1.5f)
                        }
                    val x = (frac * 2f - 1f) * 0.7f * aspect
                    val y = (ay * 0.35f - 0.6f).coerceIn(-0.9f, -0.35f)
                    val v = speed * (0.4f + 1.6f * bandE) / (0.4f + 1.6f * f.bass).coerceAtLeast(0.4f)
                    out += capsule(x, y, x, y + 0.06f, radius, 0f, v, cr, cg, cb, dyeGain * (0.4f + bandE))
                }
                else -> { // PATTERN_RING: tangential kick around each anchor.
                    val a = frac * 2f * PI.toFloat() + palettePhase * 6f
                    val ringR = 0.16f
                    val x = ax + cos(a) * ringR
                    val y = ay + sin(a) * ringR
                    val tx = -sin(a)
                    val ty = cos(a)
                    out +=
                        capsule(
                            x - tx * 0.04f,
                            y - ty * 0.04f,
                            x + tx * 0.04f,
                            y + ty * 0.04f,
                            radius,
                            tx * speed,
                            ty * speed,
                            cr,
                            cg,
                            cb,
                            dyeGain,
                        )
                }
            }
        }
    }

    /**
     * Suction splats: one catch point per frame (round-robin) receives an
     * inward velocity capsule, its strength riding the bass envelope - the
     * dye drains toward the attractors that also capture particles. Dye
     * contribution is nearly zero (slight darkening reads as a shadow well).
     */
    private fun suctionSplats(
        out: MutableList<FluidSim.Splat>,
        radius: Float,
    ) {
        val c = choreography ?: return
        if (catchSuction <= 0f) return
        val n = c.catchCount.coerceIn(0, FluidChoreography.MAX_CATCH)
        if (n == 0) return
        suctionIndex = (suctionIndex + 1) % n
        val a = c.catches[suctionIndex]
        val strength = BASE_SPEED * 0.7f * catchSuction * (0.35f + 0.65f * bassEnv)
        // Capsule from just outside the well toward its center, angle
        // precessing so successive frames pull from all sides.
        val ang = suctionPhase * 2.7f + suctionIndex * 2.1f
        val ox = cos(ang) * 0.18f
        val oy = sin(ang) * 0.18f
        val len = sqrt(ox * ox + oy * oy).coerceAtLeast(1e-4f)
        out +=
            FluidSim.Splat(
                prevX = a.x + ox,
                prevY = a.y + oy,
                curX = a.x,
                curY = a.y,
                radius = radius * 0.8f,
                velX = -ox / len * strength,
                velY = -oy / len * strength,
                r = 0f,
                g = 0f,
                b = 0f,
            )
    }

    private fun sparkleSplats(
        out: MutableList<FluidSim.Splat>,
        aspect: Float,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
    ) {
        repeat(1 + random.nextInt(2)) {
            anchor(random.nextInt(8), aspect)
            val ax = anchorX
            val ay = anchorY
            val x = ax + (random.nextFloat() * 2f - 1f) * 0.3f
            val y = ay + (random.nextFloat() * 2f - 1f) * 0.3f
            val a = random.nextFloat() * 2f * PI.toFloat()
            hsv((baseHue + palettePhase + hueSpan * 0.5f) % 1f, 0.35f, 1f, splatRgb)
            val cr = splatRgb[0]
            val cg = splatRgb[1]
            val cb = splatRgb[2]
            out +=
                capsule(
                    x,
                    y,
                    x + cos(a) * 0.02f,
                    y + sin(a) * 0.02f,
                    radius * 0.35f,
                    cos(a) * BASE_SPEED * 0.5f,
                    sin(a) * BASE_SPEED * 0.5f,
                    cr,
                    cg,
                    cb,
                    0.9f,
                )
        }
    }

    /** Bass pump: spokes burst out of the FIRST spawn anchor (the "heart"). */
    private fun pumpSplats(
        out: MutableList<FluidSim.Splat>,
        baseHue: Float,
        hueSpan: Float,
        radius: Float,
    ) {
        val v = BASE_SPEED * forceScale * bassEnv
        anchor(0, 1f)
        val ax = anchorX
        val ay = anchorY
        for (i in 0 until 6) {
            val a = i / 6f * 2f * PI.toFloat()
            // Spokes sweep the user's hue span like the beat splats do; a
            // fixed base hue ignored the Hue span control on this emitter.
            hsv((baseHue + palettePhase + i * hueSpan / 6f) % 1f, 0.95f, 1f, splatRgb)
            val cr = splatRgb[0]
            val cg = splatRgb[1]
            val cb = splatRgb[2]
            out +=
                capsule(
                    ax + cos(a) * 0.06f,
                    ay + sin(a) * 0.06f,
                    ax + cos(a) * 0.14f,
                    ay + sin(a) * 0.14f,
                    radius,
                    cos(a) * v,
                    sin(a) * v,
                    cr,
                    cg,
                    cb,
                    0.3f + 0.9f * bassEnv,
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

    /**
     * Delegates to [FluidHue.hsv]. A splat's dye is how WaterScene tells a
     * drain from a splash (`WaterMath.isCatchWell`), so the family keeps ONE
     * conversion rather than a copy per caller.
     */
    internal fun hsv(
        h: Float,
        s: Float,
        v: Float,
    ): Triple<Float, Float, Float> = FluidHue.hsv(h, s, v)

    /** [hsv] into [out]; the form the per-splat callers use. Same values. */
    private fun hsv(
        h: Float,
        s: Float,
        v: Float,
        out: FloatArray,
    ) = FluidHue.hsv(h, s, v, out)
}
