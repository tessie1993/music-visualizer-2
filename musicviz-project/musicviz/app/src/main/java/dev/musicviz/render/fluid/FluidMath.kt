package dev.musicviz.render.fluid

import kotlin.math.sqrt

/**
 * Pure-Kotlin mirrors of the fluid GLSL math, kept in lockstep with the
 * shaders so the headless gate can validate them (FLUID_SIM v2 section 15).
 * If a formula changes in a shader, change it here too.
 */
internal object FluidMath {
    /** Particle state texture side: smallest square holding [count] texels. */
    fun stateSide(count: Int): Int = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt().coerceAtLeast(2)

    /**
     * Point-to-segment distance with fractional projection, mirroring
     * fluid_splat_frag.glsl's segDist(). Degenerate segments (length below
     * epsilon) return the point distance with fp = 0 - never a normalize
     * of a zero-length vector.
     */
    fun segDist(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        px: Float,
        py: Float,
    ): Pair<Float, Float> {
        val abx = bx - ax
        val aby = by - ay
        val len2 = abx * abx + aby * aby
        if (len2 < 1e-8f) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy) to 0f
        }
        val fp = (((px - ax) * abx + (py - ay) * aby) / len2).coerceIn(0f, 1f)
        val cx = ax + abx * fp
        val cy = ay + aby * fp
        val dx = px - cx
        val dy = py - cy
        return sqrt(dx * dx + dy * dy) to fp
    }

    /**
     * CPU mirror of the particle update kernel's inertia term
     * (fluid_particle_update_frag): frame-rate-independent blend where
     * [drag] is the per-1/60s factor: k = 1-(1-drag)^(dt*60).
     */
    fun dragStep(
        v: Float,
        flow: Float,
        drag: Float,
        dt: Float = 1f / 60f,
    ): Float {
        val k = 1f - Math.pow((1f - drag).toDouble(), (dt * 60f).toDouble()).toFloat()
        return v + (flow - v) * k
    }

    /**
     * Soft-knee bloom prefilter curve, mirroring
     * fluid_bloom_prefilter_frag.glsl: knee = T*K + 1e-4,
     * curve = (T - knee, 2*knee, 0.25/knee).
     */
    fun bloomCurve(
        threshold: Float,
        softKnee: Float,
    ): Triple<Float, Float, Float> {
        val knee = threshold * softKnee + 1e-4f
        return Triple(threshold - knee, knee * 2f, 0.25f / knee)
    }

    /** CPU mirror of the prefilter's brightness rescale for a given max-channel. */
    fun bloomPrefilterScale(
        br: Float,
        threshold: Float,
        softKnee: Float,
    ): Float {
        val (cx, cy, cz) = bloomCurve(threshold, softKnee)
        var rq = (br - cx).coerceIn(0f, cy)
        rq = cz * rq * rq
        return maxOf(rq, br - threshold) / maxOf(br, 1e-4f)
    }
}
