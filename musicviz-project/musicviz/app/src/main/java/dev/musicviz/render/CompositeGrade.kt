package dev.musicviz.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-Kotlin mirror of the composite pass' universal grading + geometry math
 * (composite_frag.glsl's `geo()` zoom/rotation block and the `uPostGrade`
 * colour-grade block), kept in lockstep with the shader so the headless gate
 * can pin the formulas (FluidMath/RippleMath convention: if a formula changes
 * in the shader, change it here too).
 *
 * The chain deliberately reproduces what the self-grading scenes already do -
 * plasma_frag's `grade()`, particle_frag and pm_post_frag - so one slider
 * value looks the same on a fluid style as on a shader or particle style.
 */
internal object CompositeGrade {
    /** Zoom divisor floor, matching `max(z, 0.05)` in every scene shader. */
    const val MIN_ZOOM: Float = 0.05f

    /** Gamma floor, matching `max(uGamma, 0.05)` in every scene shader. */
    const val MIN_GAMMA: Float = 0.05f

    /**
     * Decay rate of the beat envelope, per second: exactly the
     * `beatPulse = if (beat) 1f else (beatPulse - dt * 3f)` that
     * `ShaderScene`/`ParticleSceneBase` run, so the composite pass' pulse
     * falls off at the same speed as the readers that already exist.
     */
    const val BEAT_DECAY: Float = 3f

    /**
     * Magnification the beat pulse adds at the peak of a beat, per unit of
     * the slider: `1.0 + uPulse * 0.22 * bump` in every shader scene's
     * `view()`. Deliberately the geometric (zoom) magnitude and NOT
     * `ParticleSceneBase`'s 0.8, which swells a point SIZE - 0.8 of the whole
     * screen would be a lurch, and the composite pulse is a zoom.
     */
    const val PULSE_GAIN: Float = 0.22f

    /** Rec.601 luma weights - the same vec3 every grading shader dots with. */
    private const val LUMA_R: Float = 0.299f
    private const val LUMA_G: Float = 0.587f
    private const val LUMA_B: Float = 0.114f

    /** Full turn in radians, as spelled in pm_post_frag's `hueRotate`. */
    private const val TAU_GLSL: Float = 6.2831f

    /** True 2*pi, used only to wrap the integrated rotation angle. */
    private const val TAU: Float = 6.2831855f

    /** 1/sqrt(3), the `cross(vec3(0.57735), c)` axis of the hue rotation. */
    private const val AXIS: Float = 0.57735f

    /**
     * Rotation is a SPEED in every scene (`rotationAngle += p.rotation * dt`),
     * so the composite pass integrates its own angle instead of treating the
     * slider as a static offset. Wrapped into +-2*pi so a long session never
     * loses angular precision in a 32-bit float.
     */
    fun integrateRotation(
        angle: Float,
        rotation: Float,
        dt: Float,
    ): Float = (angle + rotation * dt) % TAU

    /**
     * Colour-cycle phase, integrated exactly like ShaderScene/ProjectMScene:
     * it advances only while the toggle is on and holds its value otherwise
     * (so switching the cycle off parks the hue instead of snapping it back).
     */
    fun integrateCyclePhase(
        phase: Float,
        cycleSpeed: Float,
        dt: Float,
        enabled: Boolean,
    ): Float = if (enabled) (phase + cycleSpeed * dt) % 1f else phase

    /**
     * Beat envelope for the composite pass: snaps to 1 on a detected beat and
     * decays linearly at [BEAT_DECAY] per second, the same envelope
     * `ParticleSceneBase.update` keeps. The composite pass has no BPM phase
     * clock (that lives in `ShaderScene`), so this is what drives its pulse.
     */
    fun integrateBeatPulse(
        envelope: Float,
        beat: Boolean,
        dt: Float,
    ): Float = if (beat) 1f else (envelope - dt * BEAT_DECAY).coerceAtLeast(0f)

    /**
     * The value uploaded as `uPostPulse`: the slider scaled by the SQUARED
     * beat envelope, mirroring the `pow(0.5 + 0.5 * cos(...), 2.0)` shaping
     * the shader scenes apply to their beat bump - a sharp attack that falls
     * away quickly instead of riding at half strength between beats.
     *
     * Neutral is 0 (no pulse), which is also GL's default for an uploaded-
     * nowhere uniform, so the shader needs no enable flag for this one.
     */
    fun pulseAmount(
        pulse: Float,
        envelope: Float,
    ): Float {
        val e = envelope.coerceIn(0f, 1f)
        return pulse.coerceIn(0f, 1f) * e * e
    }

    /**
     * The scale factor `uPostPulse` produces: `1 + amount * 0.22`, the divisor
     * `geo()` applies to the centered uv (so >1 magnifies, exactly like zoom).
     */
    fun pulseScale(amount: Float): Float = 1f + amount.coerceAtLeast(0f) * PULSE_GAIN

    /** Sway shares the rotation angle, exactly like plasma_frag's `view()`. */
    fun swayAngle(
        rotationAngle: Float,
        sway: Float,
        timeSeconds: Float,
    ): Float = if (abs(sway) > 1e-3f) rotationAngle + sway * 0.35f * sin(timeSeconds * 0.7f) else rotationAngle

    /** Brightness and intensity multiply into one factor, as in every scene. */
    fun brightness(
        brightness: Float,
        intensity: Float,
    ): Float = brightness * intensity

    /**
     * The `geo()` zoom/rotation transform on a [0,1] uv: rotate about the
     * centre, then divide by the zoom (>1 magnifies). Returns the sampling uv.
     *
     * [pulseAmount] is the beat-pulse value uploaded as `uPostPulse` (see
     * [pulseAmount]); it divides by a further [pulseScale] after the zoom, in
     * the same slot and order as the shader. It defaults to 0 because the two
     * are gated on DIFFERENT scene sets - milkdrop is zoomed by its own pass
     * but pulsed by this one.
     */
    fun geometry(
        u: Float,
        v: Float,
        angle: Float,
        zoom: Float,
        pulseAmount: Float = 0f,
    ): Pair<Float, Float> {
        var cx = u - 0.5f
        var cy = v - 0.5f
        if (abs(angle) > 1e-4f) {
            val cs = cos(angle)
            val sn = sin(angle)
            val rx = cs * cx - sn * cy
            val ry = sn * cx + cs * cy
            cx = rx
            cy = ry
        }
        if (abs(zoom - 1f) > 1e-4f) {
            val z = maxOf(zoom, MIN_ZOOM)
            cx /= z
            cy /= z
        }
        if (pulseAmount > 1e-4f) {
            val s = pulseScale(pulseAmount)
            cx /= s
            cy /= s
        }
        return (cx + 0.5f) to (cy + 0.5f)
    }

    /** `dot(col, vec3(0.299, 0.587, 0.114))` - the shaders' greyscale weight. */
    fun luma(rgb: FloatArray): Float = rgb[0] * LUMA_R + rgb[1] * LUMA_G + rgb[2] * LUMA_B

    /**
     * Hue rotation about the grey axis; mirror of pm_post_frag's `hueRotate`.
     * Greys are a fixed point and a full turn is a round trip, but the axis
     * is 1/sqrt(3) rather than the Rec.601 luma vector, so brightness is only
     * approximately preserved - the same approximation the scene shaders use.
     */
    fun hueRotate(
        rgb: FloatArray,
        amount: Float,
    ): FloatArray {
        if (abs(amount) <= 1e-4f) return rgb.copyOf()
        val angle = amount * TAU_GLSL
        val cs = cos(angle)
        val sn = sin(angle)
        val g = luma(rgb)
        // cross(vec3(0.57735), c)
        val kx = AXIS * rgb[2] - AXIS * rgb[1]
        val ky = AXIS * rgb[0] - AXIS * rgb[2]
        val kz = AXIS * rgb[1] - AXIS * rgb[0]
        return floatArrayOf(
            g + (rgb[0] - g) * cs + kx * sn,
            g + (rgb[1] - g) * cs + ky * sn,
            g + (rgb[2] - g) * cs + kz * sn,
        )
    }

    /**
     * The full colour grade: hue -> saturation -> contrast -> gamma, with
     * brightness applied last (composite_frag applies it after the screen FX,
     * immediately before invert - the same slot plasma_frag's `grade()` uses).
     * Neutral arguments (hue 0, the rest 1) return the input untouched.
     */
    fun grade(
        rgb: FloatArray,
        hue: Float,
        saturation: Float,
        contrast: Float,
        gamma: Float,
        brightness: Float,
    ): FloatArray {
        var col = hueRotate(rgb, hue)
        if (abs(saturation - 1f) > 1e-4f) {
            val src = col
            val lum = luma(src)
            col = FloatArray(3) { lum + (src[it] - lum) * saturation }
        }
        if (abs(contrast - 1f) > 1e-4f) {
            val src = col
            col = FloatArray(3) { (src[it] - 0.5f) * contrast + 0.5f }
        }
        if (abs(gamma - 1f) > 1e-4f) {
            val src = col
            val inv = 1f / maxOf(gamma, MIN_GAMMA)
            col = FloatArray(3) { maxOf(src[it], 0f).pow(inv) }
        }
        // Unconditional, exactly like the shader's `col *= uPostBright`.
        val graded = col
        return FloatArray(3) { graded[it] * brightness }
    }
}
