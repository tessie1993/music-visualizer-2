package dev.musicviz.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-Kotlin mirror of the composite pass' universal grading + geometry math
 * (composite_frag.glsl's `geo()` zoom/rotation block and the gated colour-grade
 * block), plus the per-texture [Gate] that decides which scenes those blocks
 * run for. Kept in lockstep with the shader so the headless gate can pin the
 * formulas (FluidMath/RippleMath convention: if a formula changes in the
 * shader, change it here too).
 *
 * The chain deliberately reproduces what the self-grading scenes already do -
 * plasma_frag's `grade()`, particle_frag and pm_post_frag - so one slider
 * value looks the same on a fluid style as on a shader or particle style.
 *
 * The "Palette tint" block at the bottom is the one stage that does NOT ride
 * the composite: it is MilkDrop's, and lives in pm_post_frag because that is
 * the pass ProjectMScene owns (it is the reason MilkDrop is excluded from
 * `uPostGrade` in the first place). It is mirrored here anyway rather than in
 * a fourth mirror object, because it is the same kind of colour math the rest
 * of this file pins and it shares [luma] with the grade above it.
 */
internal object CompositeGrade {
    /**
     * The scene families the composite pass distinguishes. Which `uPost*`
     * groups it must apply is a property of the SCENE, not of the frame, so
     * the gate is derived from this alone - see [gateFor].
     */
    enum class SceneFamily {
        /** `ShaderScene`: `view()` + `grade()` do everything in-shader. */
        SHADER,

        /** `ParticleSceneBase`: `particle_vert` / `particle_frag` (instanced
         *  billboards; grades and tone-maps in its own fragment stage). */
        PARTICLE,

        /** `ProjectMScene`: `pm_post_frag` grades and zooms but never pulses. */
        MILKDROP,

        /**
         * Fluid, Curl Flow, Water, Cymatics: no grading pass of their own, so
         * the composite owns every group for them. Named for the family it
         * started as; membership is "grades nothing itself", which is why the
         * Chladni plate joined it rather than getting a gate of its own.
         */
        FLUID,
    }

    /**
     * Which `uPost*` groups the composite pass owns for ONE texture, uploaded
     * as `uGateA` (the incoming scene) / `uGateB` (the outgoing one).
     *
     * Per-texture, not per-frame: `composite_frag`'s `main()` routes BOTH
     * textures through `postFx`, and during a cross-family transition they
     * belong to different [SceneFamily]s. A single gate taken from the
     * incoming scene graded the outgoing frame under the wrong rule - a
     * julia -> fluid fade graded the already-graded julia frame a second time
     * (16x brightness, 4x zoom, contrast squared: a white flash for the whole
     * fade) and fluid -> julia dropped the outgoing grade entirely.
     */
    data class Gate(
        /** Geometry/stylize group: warp, ripple, kaleido, tile, bloom, ... */
        val geo: Boolean,
        /** Mirror + invert. */
        val mirrorInvert: Boolean,
        /** Colour grade + zoom/rotation (the `uPostZoom`..`uPostHue` block). */
        val grade: Boolean,
        /** Beat pulse (`uPostPulse`), gated apart from [grade] on purpose. */
        val pulse: Boolean,
    ) {
        /** The uniform as the shader reads it: `vec4(geo, mirror, grade, pulse)`. */
        fun toVec4(): FloatArray =
            floatArrayOf(
                if (geo) 1f else 0f,
                if (mirrorInvert) 1f else 0f,
                if (grade) 1f else 0f,
                if (pulse) 1f else 0f,
            )
    }

    /**
     * The gate for a [family], identical for the live renderer and the export
     * compositor so a rendered clip matches the screen.
     *
     * Each group is owned by the composite exactly when the family applies it
     * nowhere else: shader scenes apply everything themselves; particle scenes
     * grade and pulse but need mirror/invert and the screen-space geometry;
     * milkdrop grades and zooms in `pm_post_frag` but never pulses; the fluid
     * family applies nothing at all.
     */
    fun gateFor(family: SceneFamily): Gate =
        Gate(
            geo = family != SceneFamily.SHADER,
            mirrorInvert = family == SceneFamily.PARTICLE || family == SceneFamily.FLUID,
            grade = family == SceneFamily.FLUID,
            pulse = family == SceneFamily.MILKDROP || family == SceneFamily.FLUID,
        )

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
     * `ParticleSceneBase`'s 0.8, which swells a sprite SIZE - 0.8 of the whole
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
     * Beat envelope for the composite pass: snaps to the beat's graded
     * impulse ([dev.musicviz.analysis.AudioFeatures.beatImpulse] - how hard
     * the hit actually was, up to 1) and decays linearly at [BEAT_DECAY] per
     * second, the same envelope `ParticleSceneBase.update` keeps. The
     * composite pass has no BPM phase clock (that lives in `ShaderScene`),
     * so this is what drives its pulse.
     */
    fun integrateBeatPulse(
        envelope: Float,
        impulse: Float,
        dt: Float,
    ): Float = maxOf(impulse, (envelope - dt * BEAT_DECAY)).coerceAtLeast(0f)

    /** Boolean convenience for callers without a graded impulse: a beat is a
     *  full-strength kick, exactly the pre-[PulseTracker] behavior. */
    fun integrateBeatPulse(
        envelope: Float,
        beat: Boolean,
        dt: Float,
    ): Float = integrateBeatPulse(envelope, if (beat) 1f else 0f, dt)

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
     *
     * The rotation matches `mat2(cos, -sin, sin, cos) * c` in `geo()`. GLSL
     * `mat2` is COLUMN-major, so that literal is the rotation by MINUS [angle]
     * of the sampling coordinate - which is what turns the image by plus
     * [angle] on screen. The mirror used to spell the transpose (a +[angle]
     * coordinate rotation), so it and its tests agreed with each other while
     * both disagreed with the shader.
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
            val rx = cs * cx + sn * cy
            val ry = -sn * cx + cs * cy
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

    // ---------------------------------------------------------- Palette tint

    /**
     * Blend amounts at or below this leave the frame untouched, mirroring
     * pm_post_frag's `if (uPalTint > 0.001)` gate. The default
     * (`SceneParams.milkdropPaletteTint` = 0) therefore costs nothing and
     * changes nothing.
     */
    const val TINT_EPSILON: Float = 0.001f

    /** `TINT_CHROMA_KNEE` in pm_post_frag: below it, hue is noise. */
    const val TINT_CHROMA_KNEE: Float = 0.15f

    /** `TINT_SAT_LIFT` in pm_post_frag: chroma a fully tinted grey gains. */
    const val TINT_SAT_LIFT: Float = 0.35f

    /**
     * Slice of the hue wheel the palette covers, as `uPalSpan`.
     *
     * This is the SHADER/PARTICLE form (`t * uPalRange * uHueRange` in every
     * scene fragment shader, `paletteRange * hueRange` in
     * `ParticleSceneBase`), NOT `FluidHue.span`: the fluid family clamps
     * `hueRange` to 0.1..1 because a zero span kills its emission look, while
     * here a zero span legitimately means "one hue", exactly as it does on a
     * shader scene. pm_post_frag is ShaderScene's sibling grade path, so it
     * follows the same side of that documented divergence.
     */
    fun paletteSpan(
        hueRange: Float,
        paletteRange: Float,
    ): Float = hueRange.coerceAtLeast(0f) * paletteRange.coerceIn(0f, 1f)

    /** The uploaded `uPalTint`: the slider, clamped to its 0..1 range. */
    fun paletteTintAmount(tint: Float): Float = tint.coerceIn(0f, 1f)

    /**
     * Mirror of pm_post_frag's `paletteTint`: steers a colour toward the
     * palette band ([base], [span]) by [amount], in HSV so VALUE survives
     * untouched.
     *
     * The two halves are deliberate. A pixel that HAS chroma keeps its hue
     * relationships - they are compressed into the palette's band - so two
     * .milk presets that differ in colour still differ after the tint; a pixel
     * with no chroma has no hue to steer, so it takes the palette entry its
     * own luma selects (a gradient map), which is both the only way a white
     * core can show the palette at all and smooth across the flat areas where
     * a steered hue would be quantization noise. [TINT_SAT_LIFT] is what makes
     * that second half visible; without it the control does nothing on the
     * desaturated presets, which is the bug this whole stage exists to fix.
     * The lift is weighted by the same chroma knee, so a pixel that already
     * has a hue keeps its saturation exactly and the tint never doubles as a
     * saturation boost.
     *
     * [amount] <= [TINT_EPSILON] returns the input unchanged, bit for bit.
     */
    fun paletteTint(
        rgb: FloatArray,
        base: Float,
        span: Float,
        amount: Float,
    ): FloatArray {
        if (amount <= TINT_EPSILON) return rgb.copyOf()
        val hsv = rgbToHsv(rgb)
        val chroma = smoothstep(0f, TINT_CHROMA_KNEE, hsv[1])
        val t = lerp(luma(rgb), hsv[0], chroma)
        val target = base + t * span
        // Shortest way round the wheel: `fract(x + 0.5) - 0.5` in the shader.
        val delta = fract(target - hsv[0] + 0.5f) - 0.5f
        return hsvToRgb(
            fract(hsv[0] + delta * amount),
            lerp(hsv[1], hsv[1] + (1f - hsv[1]) * TINT_SAT_LIFT, amount * (1f - chroma)),
            hsv[2],
        )
    }

    /** GLSL `fract`. */
    private fun fract(x: Float): Float = x - floor(x)

    private fun lerp(
        a: Float,
        b: Float,
        k: Float,
    ): Float = a + (b - a) * k

    /** GLSL `smoothstep`. */
    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * RGB -> (hue, saturation, value), hue in [0,1). The textbook max/min form
     * is used here rather than the shader's branchless `k`-vector trick; the
     * two agree everywhere except on an exact grey, where the shader's hue
     * falls out as 0 or 1 and this returns 0 - unobservable, because a
     * saturation of 0 makes [hsvToRgb] ignore the hue entirely.
     */
    private fun rgbToHsv(rgb: FloatArray): FloatArray {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        val d = mx - mn
        val h =
            when {
                d <= 0f -> 0f
                mx == r -> fract((g - b) / d / 6f)
                mx == g -> (2f + (b - r) / d) / 6f
                else -> (4f + (r - g) / d) / 6f
            }
        return floatArrayOf(h, if (mx <= 0f) 0f else d / mx, mx)
    }

    /** (hue, saturation, value) -> RGB; mirror of pm_post_frag's `hsv2rgb`. */
    private fun hsvToRgb(
        h: Float,
        s: Float,
        v: Float,
    ): FloatArray {
        val k = floatArrayOf(1f, 2f / 3f, 1f / 3f)
        return FloatArray(3) {
            val p = abs(fract(h + k[it]) * 6f - 3f)
            v * lerp(1f, (p - 1f).coerceIn(0f, 1f), s)
        }
    }
}
