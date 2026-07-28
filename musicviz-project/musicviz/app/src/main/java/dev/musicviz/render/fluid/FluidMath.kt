package dev.musicviz.render.fluid

import kotlin.math.sqrt

/**
 * Pure-Kotlin mirrors of the fluid GLSL math, kept in lockstep with the
 * shaders so the headless gate can validate them (FLUID_SIM v2 section 15).
 * If a formula changes in a shader, change it here too.
 */
internal object FluidMath {
    // ---- CPU mirror of curl_field_frag.glsl (kept in lockstep for tests) ----
    private fun fract(x: Float) = x - kotlin.math.floor(x)

    private fun hash3(
        x0: Float,
        y0: Float,
        z0: Float,
    ): Float {
        var x = fract(x0 * 0.3183099f + 0.1f) * 17f
        var y = fract(y0 * 0.3183099f + 0.2f) * 17f
        var z = fract(z0 * 0.3183099f + 0.3f) * 17f
        return fract(x * y * z * (x + y + z))
    }

    private fun vnoise3(
        px: Float,
        py: Float,
        pz: Float,
    ): Float {
        val ix = kotlin.math.floor(px)
        val iy = kotlin.math.floor(py)
        val iz = kotlin.math.floor(pz)
        var fx = px - ix
        var fy = py - iy
        var fz = pz - iz
        fx = fx * fx * (3f - 2f * fx)
        fy = fy * fy * (3f - 2f * fy)
        fz = fz * fz * (3f - 2f * fz)

        fun n(
            dx: Float,
            dy: Float,
            dz: Float,
        ) = hash3(ix + dx, iy + dy, iz + dz)

        fun mix(
            a: Float,
            b: Float,
            t: Float,
        ) = a + (b - a) * t
        return mix(
            mix(mix(n(0f, 0f, 0f), n(1f, 0f, 0f), fx), mix(n(0f, 1f, 0f), n(1f, 1f, 0f), fx), fy),
            mix(mix(n(0f, 0f, 1f), n(1f, 0f, 1f), fx), mix(n(0f, 1f, 1f), n(1f, 1f, 1f), fx), fy),
            fz,
        )
    }

    private fun psi(
        x: Float,
        y: Float,
        time: Float,
        freq: Float,
        detail: Float,
    ): Float {
        var v = vnoise3(x * freq, y * freq, time) * 0.625f
        v += vnoise3(x * freq * 2.02f + 11.3f, y * freq * 2.02f + 11.3f, time * 2.02f + 11.3f) * 0.25f
        v += vnoise3(x * freq * 4.05f + 29.7f, y * freq * 4.05f + 29.7f, time * 4.05f + 29.7f) * 0.125f * detail
        return v
    }

    /** Curl-noise velocity, mirroring the shader (central diff, e = 0.02). */
    fun curlVelocity(
        x: Float,
        y: Float,
        time: Float,
        freq: Float,
        detail: Float,
    ): Pair<Float, Float> {
        val e = 0.02f
        val dpdx = psi(x + e, y, time, freq, detail) - psi(x - e, y, time, freq, detail)
        val dpdy = psi(x, y + e, time, freq, detail) - psi(x, y - e, time, freq, detail)
        return (dpdy / (2f * e)) to (-dpdx / (2f * e))
    }

    /**
     * CPU mirror of fluid_vorticity_frag's confinement magnitude (GPU Gems
     * ch.38: f = eps * h * omega, with omega = halfRdx * velDiff from the
     * curl pass). The eps*h*omega form makes the per-frame velocity change
     * independent of grid resolution - the property the headless gate
     * asserts, because omitting the h (= dx) factor made the force ~1/dx
     * (64-142x) too strong and blew the sim up to NaN/black within frames.
     */
    fun confinementDeltaV(
        curlStrength: Float,
        dx: Float,
        velDiff: Float,
        dt: Float,
    ): Float {
        val omega = (0.5f / dx) * velDiff
        return curlStrength * dx * omega * dt
    }

    /**
     * CPU mirror of composite_frag's fluidWarp soft limit:
     * flow * 6/(6+|flow|) - bounded below 6 for any input, ~identity for
     * small fields, so the 0.015 UV scale can never displace by more than
     * ~0.09 UV regardless of emitter force.
     */
    fun softLimitFlow(
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val len = sqrt(x * x + y * y)
        val k = 6f / (6f + len)
        return (x * k) to (y * k)
    }

    /**
     * CPU mirror of fluid_gradient_frag's terminal-speed soft cap
     * (v *= 12/max(12,|v|)): confinement injects energy faster than
     * dissipation removes it, so uncapped speed grows without bound and the
     * dye advects off-grid faster than injection - numerically reproduced
     * as near-black by ~10s and fully black by ~30s in the reference run.
     */
    fun terminalSpeedCap(
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val sp = sqrt(x * x + y * y)
        val k = 12f / maxOf(12f, sp)
        return (x * k) to (y * k)
    }

    /** Particle state texture side: smallest square holding [count] texels. */
    fun stateSide(count: Int): Int = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt().coerceAtLeast(2)

    /**
     * CPU mirror of the particle update kernel's catch-point attraction
     * (fluid_particle_update_frag): inverse-square pull with softening
     * epsilon 0.05, then the soft cap f*6/(6+f) - bounded below 6 for ANY
     * pull/distance, so a close pass swings around the well instead of
     * exploding across the screen in one frame.
     */
    fun attractorForce(
        pull: Float,
        dist2: Float,
    ): Float {
        val f = pull / (dist2 + 0.05f)
        return f * 6f / (6f + f)
    }

    /**
     * CPU mirror of the capture predicate: a particle inside the capture
     * radius of a catch point is recycled to a spawn point.
     */
    fun isCaptured(
        px: Float,
        py: Float,
        cx: Float,
        cy: Float,
        captureRadius: Float,
    ): Boolean {
        val dx = cx - px
        val dy = cy - py
        return dx * dx + dy * dy < captureRadius * captureRadius
    }

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
