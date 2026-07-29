package dev.musicviz.render.fluid

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pure-Kotlin mirrors of the ripple (heightfield water) GLSL math, kept in
 * lockstep with ripple_splat_frag / ripple_update_frag / water_display_frag
 * so the headless gate can validate wave propagation, damping, CFL stability
 * and the refraction cap (FluidMath.kt convention: if a formula changes in a
 * shader, change it here too).
 */
internal object RippleMath {
    /**
     * Soft cap (UV units) on the display refraction offset - the same soft
     * limit idiom as composite_frag's fluidWarp (x * cap/(cap+|x|)).
     */
    const val REFRACTION_CAP = 0.08f

    /** Height clamp applied by the update shader (drop-spam safety rail). */
    const val MAX_HEIGHT = 8f

    /**
     * Lockstep CPU mirror of ripple_update_frag.glsl: one velocity-form wave
     * step over an entire [w] x [hgt] grid with clamped-edge boundary
     * (out-of-range neighbors read the edge cell, the texture's
     * CLAMP_TO_EDGE analogue). Per cell:
     *   v += c^2 * dt * laplacian(h) / dx^2  (laplacian = L+R+T+B - 4C)
     *   v *= damping
     *   h += v * dt   (then clamped to +-MAX_HEIGHT)
     * The v pass reads only h and the h pass reads only v, so updating v for
     * every cell first, then h, matches the shader's simultaneous update.
     */
    fun waveStep(
        h: FloatArray,
        v: FloatArray,
        w: Int,
        hgt: Int,
        c: Float,
        dt: Float,
        dx: Float,
        damping: Float,
    ) {
        val k = c * c * dt / (dx * dx)
        for (y in 0 until hgt) {
            for (x in 0 until w) {
                val i = y * w + x
                val l = h[y * w + (x - 1).coerceAtLeast(0)]
                val r = h[y * w + (x + 1).coerceAtMost(w - 1)]
                val b = h[(y - 1).coerceAtLeast(0) * w + x]
                val t = h[(y + 1).coerceAtMost(hgt - 1) * w + x]
                v[i] = (v[i] + k * (l + r + t + b - 4f * h[i])) * damping
            }
        }
        for (i in h.indices) {
            h[i] = (h[i] + v[i] * dt).coerceIn(-MAX_HEIGHT, MAX_HEIGHT)
        }
    }

    /**
     * Lockstep CPU mirror of ripple_splat_frag.glsl's drop kernel: a
     * Gaussian bump, amp * exp(-d^2 / r^2), added to the height channel.
     */
    fun dropProfile(
        dist: Float,
        radius: Float,
        amp: Float,
    ): Float {
        val r = maxOf(radius, 1e-4f)
        return amp * exp(-(dist * dist) / (r * r))
    }

    /**
     * CFL stability clamp for the explicit wave update (mirrored by
     * RippleSim's substep sizing): the scheme is stable only while
     * c*dt/dx <= ~0.7, so dt is clamped to 0.7*dx/c. Guarantees max|h|
     * stays bounded at any wave speed the params allow.
     */
    fun cflClampedDt(
        c: Float,
        dt: Float,
        dx: Float,
    ): Float {
        if (c <= 1e-6f) return dt
        return minOf(dt, 0.7f * dx / c)
    }

    /**
     * Lockstep CPU mirror of water_display_frag.glsl's refraction offset:
     * the height gradient scaled by [strength], then soft-capped with the
     * composite uFlow soft-limit idiom (offset *= cap/(cap+|offset|)) so the
     * background lookup can never displace by more than [REFRACTION_CAP] UV
     * regardless of ripple amplitude.
     */
    fun refractionOffset(
        hL: Float,
        hR: Float,
        hT: Float,
        hB: Float,
        strength: Float,
    ): Pair<Float, Float> {
        var ox = (hR - hL) * strength
        var oy = (hT - hB) * strength
        val len = sqrt(ox * ox + oy * oy)
        val k = REFRACTION_CAP / (REFRACTION_CAP + len)
        ox *= k
        oy *= k
        return ox to oy
    }
}
