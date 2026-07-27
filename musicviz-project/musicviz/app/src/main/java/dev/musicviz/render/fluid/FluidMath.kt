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
     * (fluid_particle_update_frag): v += (flow - v) * drag.
     */
    fun dragStep(
        v: Float,
        flow: Float,
        drag: Float,
    ): Float = v + (flow - v) * drag
}
