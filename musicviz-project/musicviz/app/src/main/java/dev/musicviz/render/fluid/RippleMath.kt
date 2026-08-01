package dev.musicviz.render.fluid

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
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
     * How much of the "Damping" slider's per-frame loss is also taken off the
     * HEIGHT channel, as opposed to the velocity channel alone.
     *
     * Velocity damping on its own cannot drain the surface. The discrete
     * Laplacian sums to zero across a Neumann grid, so the wave step conserves
     * the mean of h exactly - and every drop injects a strictly positive
     * Gaussian. Under a track that means monotone accumulation: the pool
     * climbs, pins against [MAX_HEIGHT], the gradient the display pass shades
     * from goes flat and the style freezes. That is the "water only ever adds
     * ripples, it never removes them" defect.
     *
     * A fraction rather than a second slider, because it is not an independent
     * idea: "Damping" already means "how quickly the pool calms down", and one
     * slider should not calm the ripples while the water level keeps rising.
     * Kept WELL below 1 so the surface still rings - at the default damping
     * (0.985) this drains the accumulated level with a ~2 s half-life while a
     * single ripple still crosses the pool and reflects.
     */
    const val HEIGHT_DECAY_RATIO = 0.35f

    /**
     * Per-substep height decay for a "per 1/60 s" [damping] slider value,
     * renormalized to [subDt] exactly the way the velocity damping is - so
     * ripple lifetime is the same at 60 Hz, 120 Hz and any substep count.
     */
    fun heightDecayPerSubstep(
        damping: Float,
        subDt: Float,
    ): Float {
        val per60 = 1f - (1f - damping.coerceIn(0f, 1f)) * HEIGHT_DECAY_RATIO
        return Math.pow(per60.toDouble(), (subDt * 60f).toDouble()).toFloat()
    }

    /**
     * Lockstep CPU mirror of ripple_update_frag.glsl: one velocity-form wave
     * step over an entire [w] x [hgt] grid with clamped-edge boundary
     * (out-of-range neighbors read the edge cell, the texture's
     * CLAMP_TO_EDGE analogue). Per cell:
     *   v += c^2 * dt * laplacian(h) / dx^2  (laplacian = L+R+T+B - 4C)
     *   v *= damping
     *   h = (h + v * dt) * heightDecay   (then clamped to +-MAX_HEIGHT)
     * The v pass reads only h and the h pass reads only v, so updating v for
     * every cell first, then h, matches the shader's simultaneous update.
     *
     * [heightDecay] defaults to 1 (the pre-fix behaviour) only so a caller can
     * demonstrate the unbounded-accumulation failure in a test; the sim always
     * passes [heightDecayPerSubstep].
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
        heightDecay: Float = 1f,
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
            h[i] = ((h[i] + v[i] * dt) * heightDecay).coerceIn(-MAX_HEIGHT, MAX_HEIGHT)
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

    /**
     * Per-frame survival factor for the liquid ink layer, from a "fade in N
     * seconds" style [dissipation] rate, renormalized to [dt] so the colour's
     * lifetime does not depend on frame rate. 0 leaves the ink forever, higher
     * values clear the pool faster.
     */
    fun inkDissipation(
        dissipation: Float,
        dt: Float,
    ): Float = (1f - dissipation.coerceIn(0f, 8f) * dt).coerceIn(0f, 1f)

    /**
     * A finger stroke, as the pair of drops it becomes.
     *
     * A smear is directional: pushing the surface piles water up AHEAD of the
     * finger and leaves a trough BEHIND it, and it is that asymmetric slope
     * that drags the refracted image along with the drag rather than just
     * ringing a symmetric drop under the fingertip. [strokeDrops] returns the
     * crest first, then the trough.
     */
    data class StrokeDrop(
        val x: Float,
        val y: Float,
        val radius: Float,
        val amplitude: Float,
    )

    /** Speed (sim units/s) at which a drag reaches full smear amplitude. */
    private const val STROKE_REFERENCE_SPEED = 1.6f

    /**
     * Converts a drag from ([x], [y]) by ([dx], [dy]) sim units in [dt]
     * seconds into the crest/trough pair above. [radius] is the fingertip
     * footprint and [strength] the Settings smear amount.
     *
     * A stationary finger still leaves a small dimple (the `0.25` floor) so
     * holding still reads as touching the water rather than as nothing.
     */
    fun strokeDrops(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        dt: Float,
        radius: Float,
        strength: Float,
    ): List<StrokeDrop> {
        val step = sqrt(dx * dx + dy * dy)
        val speed = if (dt > 1e-4f) step / dt else 0f
        val drive = (0.25f + (speed / STROKE_REFERENCE_SPEED).coerceIn(0f, 1.5f)) * strength.coerceIn(0f, 2f)
        if (drive <= 1e-4f) return emptyList()
        // Offset the pair along the drag, half a footprint apart, so the crest
        // leads the finger and the trough trails it. A stationary finger has no
        // direction to lean into, so both collapse onto the touch point and the
        // trough is dropped - a dimple, not a dipole.
        val ux = if (step > 1e-5f) dx / step else 0f
        val uy = if (step > 1e-5f) dy / step else 0f
        val lead = radius * 0.6f
        val crest = StrokeDrop(x + ux * lead, y + uy * lead, radius, drive)
        if (step <= 1e-5f) return listOf(crest)
        return listOf(crest, StrokeDrop(x - ux * lead, y - uy * lead, radius, -drive * 0.8f))
    }

    /** Golden angle (radians): successive turns never resonate into rows. */
    private const val GOLDEN_ANGLE = 2.3999631f

    /** Golden-ratio conjugate: low-discrepancy radius sequence. */
    private const val GOLDEN_FRACT = 0.6180339887f

    /**
     * Deterministic drop position [index] for the ripple overlay (F2): a
     * golden-angle spiral with a low-discrepancy sqrt-radius, so successive
     * beat/sparkle drops scatter evenly over the pool without ever calling a
     * random source (live view and export must land identical drops for the
     * same index sequence). Returns sim-space coords - y in [-0.85, 0.85],
     * x in [-0.85 * aspect, 0.85 * aspect] (the sim domain is y in [-1, 1],
     * x in [-aspect, aspect]; the 0.85 margin keeps rings on-screen).
     */
    fun overlayDropPosition(
        index: Int,
        aspect: Float,
    ): Pair<Float, Float> {
        val n = index.coerceAtLeast(0)
        val angle = n * GOLDEN_ANGLE
        val radius = 0.85f * sqrt(((n * GOLDEN_FRACT.toDouble()) % 1.0).toFloat())
        return (cos(angle) * radius * aspect) to (sin(angle) * radius)
    }
}
