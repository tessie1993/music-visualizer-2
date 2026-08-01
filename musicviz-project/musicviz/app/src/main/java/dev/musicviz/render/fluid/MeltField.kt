package dev.musicviz.render.fluid

import android.content.Context
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.SceneParams
import kotlin.math.abs
import kotlin.math.max

/**
 * The fluid the HYPERSPACE fractals are suspended in - and made of.
 *
 * A full [FluidSim] (velocity AND dye, unlike the velocity-only
 * [FlowField] service), owned by `HyperspaceScene` and world-anchored rather
 * than screen-anchored: sim space IS the world's xy plane, divided by
 * [MeltMath.DEFAULT_SCALE]. That one decision is what lets the loop close.
 *
 * ### The loop
 *
 * 1. The **bodies stir the fluid**. Every living fractal drops a capsule splat
 *    at its own world position each frame, carrying its own colour and its own
 *    motion ([queueBodySplat]). A body drifting across the room leaves a wake;
 *    a body being born blooms ink outward.
 * 2. The **music stirs the fluid**, through the same [FluidEmitters] schedule
 *    the whole fluid family runs, so a beat lands here exactly as it lands on
 *    the FLUID style.
 * 3. The **finger stirs the fluid** ([queueTouchStroke]).
 * 4. The **fluid molds the bodies**. `hyperspace_frag.glsl` samples the
 *    velocity field at two orthogonal projections of every raymarch sample and
 *    displaces the point before evaluating any fractal, so the whole scene is
 *    stirred as one continuous medium - the geometry stretches, smears and
 *    pulls like taffy instead of turning as a rigid object.
 * 5. The **dye stains what it touches**: it lights the surfaces it has run
 *    over and glows in the space between them.
 *
 * Because it is world-anchored, all five agree with each other. A screen-space
 * field would have looked right until the camera moved, at which point the ink
 * a body had shed would slide off it.
 */
internal class MeltField(
    context: Context,
) {
    private companion object {
        /** Velocity grid. Small on purpose: it is sampled twice per march step. */
        const val SIM_RES = 96

        /** Dye grid. Larger - this one is looked at directly. */
        const val DYE_RES = 256

        /** Jacobi iterations. Fewer than the FLUID style: this is a garnish. */
        const val PRESSURE_ITERATIONS = 14
    }

    private val sim = FluidSim(context)

    /**
     * Beat/stirrer splats, configured for a medium rather than for a picture:
     * a ring of gentle beat splats and two stirrers keep it always moving,
     * with no sparkle - bright points would read as dirt on the lens once the
     * fractals are the subject.
     */
    private val emitters =
        FluidEmitters().apply {
            beatPattern = FluidEmitters.PATTERN_RING
            beatSplats = 2
            stirrers = 2
            sparkle = false
            splatRadius = 0.16f
        }

    /**
     * This frame's splat requests, reused across frames: the emitters fill it
     * and the loop that drains it into the sim runs in the same call, so no
     * one is still reading last frame's contents when it is cleared. Stays on
     * the GL thread - unlike [touchStrokes], which crosses from the UI thread
     * and therefore has to be a queue.
     */
    private val splats = ArrayList<FluidSim.Splat>()

    private val touchStrokes = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()

    val available: Boolean get() = sim.available
    val velocityTex: Int get() = sim.velocityTex
    val dyeTex: Int get() = sim.dyeTex
    val aspect: Float get() = sim.aspect

    /**
     * Grid velocity -> sim units per second for this field. The shader needs
     * it to turn a raw texel into a world displacement; see `uMeltGain`.
     */
    val flowScale: Float get() = sim.flowScale

    var onShaderError: (String?) -> Unit = {}

    fun create() {
        sim.onShaderError = { onShaderError(it) }
        sim.simRes = SIM_RES
        sim.dyeRes = DYE_RES
        sim.pressureIterations = PRESSURE_ITERATIONS
        sim.create()
    }

    fun resize(
        w: Int,
        h: Int,
    ) {
        sim.resize(w, h)
    }

    /** Thread-safe: a drag from the UI thread, drained on the GL thread. */
    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        strength: Float,
    ) {
        if (touchStrokes.size >= 64) return
        touchStrokes.add(floatArrayOf(nx, ny, ndx, ndy, strength))
    }

    /**
     * One body's contribution to the medium: a capsule from where it was to
     * where it is, in the body's own colour.
     *
     * Coordinates are WORLD, converted here - the caller should not have to
     * know what sim space is. [radius] is the body's world radius, [life] its
     * envelope; a body being born or dissolving pushes harder, which is what
     * makes a spawn visible as a bloom of ink rather than only as a shape.
     */
    fun queueBodySplat(
        prevWorldX: Float,
        prevWorldY: Float,
        worldX: Float,
        worldY: Float,
        radius: Float,
        life: Float,
        scale: Float,
        r: Float,
        g: Float,
        b: Float,
        strength: Float,
    ) {
        if (!sim.available || strength <= 0f) return
        val px = MeltMath.simFromWorld(prevWorldX, scale)
        val py = MeltMath.simFromWorld(prevWorldY, scale)
        val cx = MeltMath.simFromWorld(worldX, scale)
        val cy = MeltMath.simFromWorld(worldY, scale)
        // Off the grid entirely: a splat there would wrap into the far edge of
        // the field and leave ink where no body is.
        if (!MeltMath.insideSim(cx, cy, sim.aspect)) return
        val edge = MeltMath.birthBoost(life)
        val push = MeltMath.BODY_FORCE * strength * edge
        sim.queueSplat(
            FluidSim.Splat(
                prevX = px,
                prevY = py,
                curX = cx,
                curY = cy,
                radius = MeltMath.splatRadius(radius, scale),
                velX = (cx - px) * push,
                velY = (cy - py) * push,
                r = r * strength * edge,
                g = g * strength * edge,
                b = b * strength * edge,
            ),
        )
    }

    /**
     * Advances the medium one frame.
     *
     * Binds its own FBOs, so callers must snapshot and restore the framebuffer
     * and viewport around it - the renderer has the scene target bound by the
     * time a scene's `draw` runs.
     */
    fun step(
        features: AudioFeatures,
        dt: Float,
        p: SceneParams,
        hueBase: Float,
        hueSpan: Float,
    ) {
        if (!sim.available) return
        val simDt = dt.coerceIn(0f, 1f / 30f)
        sim.curlStrength = p.hyperSwirl.coerceIn(0f, 50f) * (1f + 0.5f * features.mid)
        sim.velocityDissipation = p.hyperFlowFade.coerceIn(0f, 4f)
        // The dye has to outlive the velocity that carried it or the stain
        // never has time to be seen ON anything: it would appear and clear
        // within one pass of a body.
        sim.densityDissipation = (p.hyperFlowFade * 0.45f).coerceIn(0f, 4f)
        sim.audioBass = features.bass
        sim.audioMid = features.mid
        sim.audioTreble = features.treble
        sim.audioEnergy = features.rms.coerceIn(0f, 1f)
        sim.audioBeat = features.motionImpulse
        emitters.forceScale = p.hyperStir.coerceIn(0f, 3f)
        emitters.stirrerSpeed = p.speed.coerceIn(0.1f, 2f)
        emitters.beatResponse = p.beatResponse
        emitters.tick(features, simDt, sim.aspect, hueBase, hueSpan, splats)
        for (i in splats.indices) sim.queueSplat(splats[i])
        drainTouchStrokes(hueBase, hueSpan)
        sim.step(simDt)
    }

    /**
     * Turns queued drags into capsule splats: a finger pushes the medium and
     * paints into it at once, so the fractals it is dragged across are pulled
     * out of shape and stained in the same gesture. This is the "moldable"
     * part that is literally hands-on.
     */
    private fun drainTouchStrokes(
        hueBase: Float,
        hueSpan: Float,
    ) {
        while (true) {
            val st = touchStrokes.poll() ?: return
            val cx = st[0] * sim.aspect
            val cy = st[1]
            val dx = st[2] * sim.aspect
            val dy = st[3]
            val strength = st[4].coerceIn(0f, 4f)
            val (r, g, b) = FluidHue.rgb(hueBase + 0.35f * hueSpan, 1f)
            sim.queueSplat(
                FluidSim.Splat(
                    prevX = cx - dx,
                    prevY = cy - dy,
                    curX = cx,
                    curY = cy,
                    radius = MeltMath.TOUCH_RADIUS,
                    velX = dx * MeltMath.TOUCH_FORCE * strength,
                    velY = dy * MeltMath.TOUCH_FORCE * strength,
                    r = r * strength,
                    g = g * strength,
                    b = b * strength,
                ),
            )
        }
    }

    fun release() {
        sim.release()
        touchStrokes.clear()
    }
}

/**
 * The world <-> sim arithmetic behind [MeltField], and the two safety numbers
 * the raymarcher needs when the medium is allowed to move its geometry.
 *
 * Pure and allocation-free so `HyperspaceMeltTest` can pin it: the two safety
 * numbers in particular are the difference between a molten fractal and a
 * screen of artefacts, and neither is visible in a code review.
 */
internal object MeltMath {
    /**
     * World units per sim unit. The bodies orbit inside roughly two to three
     * world units, and sim y runs -1..1, so this frames the room in the field
     * rather than putting it all in one texel or spreading it past the edges.
     */
    const val DEFAULT_SCALE: Float = 2.6f

    /** Grid-velocity gain for a body's own wake. */
    const val BODY_FORCE: Float = 220f

    /** Grid-velocity gain for a finger. */
    const val TOUCH_FORCE: Float = 320f

    /** Sim-space radius of a finger's capsule. */
    const val TOUCH_RADIUS: Float = 0.13f

    /**
     * How much harder a body pushes while being born or while dissolving.
     * A body at full life is a steady presence in the medium; the two ends of
     * its life are events, and they should look like events.
     */
    const val BIRTH_BOOST: Float = 1.8f

    /**
     * Seconds of the medium's motion a full "Melt" is worth: the displacement
     * is "where would this point be carried in this long". Expressing it as a
     * time rather than as a distance is what makes one Melt setting behave the
     * same in a still passage and a violent one - the field is slower in the
     * first, so it moves things less, which is what a fluid does.
     */
    const val MELT_SECONDS: Float = 0.09f

    fun simFromWorld(
        world: Float,
        scale: Float,
    ): Float = world / max(scale, 0.05f)

    /** True when a sim-space point is on the grid at this [aspect]. */
    fun insideSim(
        x: Float,
        y: Float,
        aspect: Float,
    ): Boolean = abs(x) <= max(aspect, 0.05f) + 0.25f && abs(y) <= 1.25f

    /** A body's capsule radius, floored so a small body still marks the field. */
    fun splatRadius(
        worldRadius: Float,
        scale: Float,
    ): Float = (worldRadius / max(scale, 0.05f)).coerceIn(0.05f, 0.5f)

    /** [BIRTH_BOOST] at the ends of a life, 1 in the middle. */
    fun birthBoost(life: Float): Float = 1f + (BIRTH_BOOST - 1f) * (1f - life.coerceIn(0f, 1f))

    /**
     * How far, in world units, the medium can displace a raymarch sample at a
     * given "Melt". Two things depend on it and neither is optional.
     *
     * The first is the bounding spheres. A body's sphere is what lets the
     * raymarcher skip seven of the eight fractals on most rays, and a body
     * whose geometry has been pushed [reach] units sideways no longer fits in
     * the sphere it was skipped by - so the sphere is inflated by exactly this
     * before it is uploaded. Without it, melting a body cuts its own edges off.
     */
    fun reach(
        melt: Float,
        scale: Float,
    ): Float = melt.coerceIn(0f, 2f) * max(scale, 0.05f) * 0.25f

    /**
     * The second is the march itself. A distance estimate is only valid
     * because the map is 1-Lipschitz; warping the domain by a field with
     * gradient g makes the true distance as much as `1/(1+g)` of the estimate,
     * and a ray stepping the full estimate then walks straight through thin
     * geometry - which shows up as holes and shimmering rather than as
     * anything recognisable as an overshoot.
     *
     * So the step is relaxed by this factor. It is a bound, not a measurement:
     * the exact gradient varies per sample, and computing it would cost more
     * than the steps it would save.
     */
    fun stepRelaxation(melt: Float): Float = 1f / (1f + 1.6f * melt.coerceIn(0f, 2f))
}
