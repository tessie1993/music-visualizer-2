package dev.geode.render

import android.content.Context
import android.os.SystemClock
import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FlowField
import dev.geode.render.fluid.RippleOverlayDrops
import dev.geode.render.fluid.RippleSim
import dev.geode.render.fluid.WaterScene
import dev.geode.render.scene.Scene
import dev.geode.render.scene.SceneParams
import java.util.concurrent.ConcurrentLinkedQueue

internal class OverlayEffects(
    private val context: Context,
) {
    private companion object {
        const val TOUCH_RADIUS = 0.11f

        const val MAX_TOUCH_BACKLOG = 24

        const val TOUCH_LINGER_MS = 2_500L

        const val RIPPLE_OVERLAY_RES = 256
    }

    private var flowField: FlowField? = null
    private var rippleOverlay: RippleSim? = null
    private val rippleDrops = RippleOverlayDrops()

    private class TouchStroke(
        val nx: Float,
        val ny: Float,
        val ndx: Float,
        val ndy: Float,
        val dt: Float,
        val strength: Float,
    )

    private val touchStrokes = ConcurrentLinkedQueue<TouchStroke>()

    @Volatile
    private var lastTouchMs = 0L

    val flow: FlowField? get() = flowField

    val ripple: RippleSim? get() = rippleOverlay

    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) {
        if (strength <= 0f) return
        if (touchStrokes.size >= MAX_TOUCH_BACKLOG) return
        lastTouchMs = SystemClock.elapsedRealtime()
        touchStrokes.add(TouchStroke(nx, ny, ndx, ndy, dt, strength))
    }

    fun smearing(now: Long): Boolean = now - lastTouchMs < TOUCH_LINGER_MS

    fun recreate() {
        flowField?.release()
        flowField = FlowField(context).also { it.create() }
        rippleOverlay?.release()
        rippleOverlay =
            RippleSim(context).also {
                it.create()
                it.applyResolution(RIPPLE_OVERLAY_RES)
            }
        rippleDrops.reset()
    }

    fun resize(
        width: Int,
        height: Int,
    ) {
        flowField?.resize(width, height)
        rippleOverlay?.resize(width, height)
    }

    fun wantsFlow(
        p: SceneParams,
        fluidActive: Boolean,
    ): Boolean {
        val ff = flowField
        return p.flowEnabled && !fluidActive && ff != null && ff.available
    }

    fun stepFlow(
        features: AudioFeatures,
        dt: Float,
        p: SceneParams,
    ) {
        flowField?.step(features, dt, p)
    }

    fun rippleOverlayActive(
        p: SceneParams,
        smearing: Boolean,
        waterActive: Boolean,
    ): Boolean {
        val r = rippleOverlay
        return (p.rippleOverlayEnabled || smearing) && r != null && r.available && !waterActive
    }

    fun stepRippleOverlay(
        features: AudioFeatures,
        p: SceneParams,
        dt: Float,
    ) {
        val r = rippleOverlay ?: return
        r.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
        r.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
        rippleDrops.tick(features, r.aspect) { x, y, radius, amp ->
            r.queueDrop(x, y, radius, amp)
        }
        r.step(dt)
    }

    fun drainTouchStrokes(scene: Scene) {
        val water = scene as? WaterScene
        val r = rippleOverlay
        val aspect = r?.aspect ?: 1f
        while (true) {
            val st = touchStrokes.poll() ?: return
            if (water != null) {
                water.queueTouchStroke(
                    st.nx * 2f - 1f,
                    1f - st.ny * 2f,
                    st.ndx * 2f,
                    -st.ndy * 2f,
                    st.dt,
                    st.strength,
                )
            } else if (r != null && r.available) {
                r.queueStroke(
                    (st.nx * 2f - 1f) * aspect,
                    1f - st.ny * 2f,
                    st.ndx * 2f * aspect,
                    -st.ndy * 2f,
                    st.dt,
                    TOUCH_RADIUS,
                    st.strength,
                )
            }
        }
    }
}
