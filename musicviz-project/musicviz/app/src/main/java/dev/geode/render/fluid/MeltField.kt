package dev.geode.render.fluid

import android.content.Context
import dev.geode.analysis.AudioFeatures
import dev.geode.render.scene.SceneParams
import kotlin.math.abs
import kotlin.math.max

internal class MeltField(
    context: Context,
) {
    private companion object {
        const val SIM_RES = 96

        const val DYE_RES = 256

        const val PRESSURE_ITERATIONS = 14
    }

    private val sim = FluidSim(context)

    private val emitters =
        FluidEmitters().apply {
            beatPattern = FluidEmitters.PATTERN_RING
            beatSplats = 2
            stirrers = 2
            sparkle = false
            splatRadius = 0.16f
        }

    private val splats = ArrayList<FluidSim.Splat>()

    private val touchStrokes = java.util.concurrent.ConcurrentLinkedQueue<FloatArray>()

    val available: Boolean get() = sim.available
    val velocityTex: Int get() = sim.velocityTex
    val dyeTex: Int get() = sim.dyeTex
    val aspect: Float get() = sim.aspect

    val flowScale: Float get() = sim.flowScale

    var onShaderError: (String?) -> Unit = {}

    fun create() {
        sim.onShaderError = { onShaderError(it) }
        sim.simRes = SIM_RES
        sim.dyeRes = DYE_RES
        sim.pressureIterations = PRESSURE_ITERATIONS
        sim.dyeCeiling = MeltMath.DYE_CEILING
        sim.create()
    }

    fun resize(
        w: Int,
        h: Int,
    ) {
        sim.resize(w, h)
    }

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
        sim.densityDissipation = MeltMath.dyeDissipation(p.hyperFlowFade)
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

internal object MeltMath {
    const val DEFAULT_SCALE: Float = 2.6f

    const val BODY_FORCE: Float = 220f

    const val TOUCH_FORCE: Float = 320f

    const val TOUCH_RADIUS: Float = 0.13f

    const val DYE_CEILING: Float = 1f

    const val DYE_FADE_RATIO: Float = 0.45f

    const val MIN_DYE_DISSIPATION: Float = 0.08f

    fun dyeDissipation(flowFade: Float): Float = (flowFade.coerceIn(0f, 4f) * DYE_FADE_RATIO).coerceIn(MIN_DYE_DISSIPATION, 4f)

    const val BIRTH_BOOST: Float = 1.8f

    const val MELT_SECONDS: Float = 0.09f

    fun simFromWorld(
        world: Float,
        scale: Float,
    ): Float = world / max(scale, 0.05f)

    fun insideSim(
        x: Float,
        y: Float,
        aspect: Float,
    ): Boolean = abs(x) <= max(aspect, 0.05f) + 0.25f && abs(y) <= 1.25f

    fun splatRadius(
        worldRadius: Float,
        scale: Float,
    ): Float = (worldRadius / max(scale, 0.05f)).coerceIn(0.05f, 0.5f)

    fun birthBoost(life: Float): Float = 1f + (BIRTH_BOOST - 1f) * (1f - life.coerceIn(0f, 1f))

    fun reach(
        melt: Float,
        scale: Float,
    ): Float = melt.coerceIn(0f, 2f) * max(scale, 0.05f) * 0.25f

    fun stepRelaxation(melt: Float): Float = 1f / (1f + 1.6f * melt.coerceIn(0f, 2f))
}
