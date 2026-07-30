package dev.musicviz.render.fluid

import kotlin.math.pow

/**
 * Pure-Kotlin mirror of [CurlFlowScene]'s Customize arithmetic, kept GL-free so
 * the headless gate can pin it (same pattern as [FluidMath] and [FluidHue]).
 *
 * Two shipped bugs it guards, both reported as "customizations don't work on
 * the fluid styles":
 *
 * 1. The renderer forced canvas persistence ON for Curl Flow regardless of the
 *    Trails toggle, and floored Trail length at 0.85 on top - so on that style
 *    Trails was inert and most of the Trail length slider did nothing either.
 * 2. The scene multiplied its own brightness by `intensity`, and the composite
 *    grading pass (which now grades every style that does not grade itself,
 *    Curl Flow included) multiplies by `brightness * intensity` again - so the
 *    Intensity slider responded quadratically.
 *
 * Curl Flow does need MORE persistence than a particle scene to read well: it
 * draws bare `GL_POINTS`, which on a hard-cleared canvas strobe as dots instead
 * of streaming as ribbons. So instead of ignoring the controls, the style keeps
 * a scene-specific FLOOR that the user can still override - Trails off wipes
 * the canvas like any other style, and while Trails is on the whole Trail
 * length slider stays live, remapped onto the band [MIN_RETENTION, 1] where the
 * points still join into streams.
 */
internal object CurlFlowMath {
    /**
     * Shortest retention Curl Flow renders at while Trails is on. Below this
     * the streams break up into flickering dots; the remap in [retention]
     * keeps the slider monotonic rather than clamping its lower half flat.
     */
    const val MIN_RETENTION = 0.6f

    /** Baseline point brightness with no beat in flight. */
    const val BASE_BRIGHTNESS = 0.85f

    /** How much a fresh beat lifts the points above [BASE_BRIGHTNESS]. */
    const val BEAT_BRIGHTNESS = 0.35f

    /**
     * Frame retention for a given "Trail length" slider value. Strictly
     * increasing over the slider's whole 0.05..0.98 range, so no part of the
     * control is dead.
     */
    fun retention(trailLength: Float): Float {
        val t = trailLength.coerceIn(0f, 1f)
        return MIN_RETENTION + (1f - MIN_RETENTION) * t
    }

    /**
     * Per-frame fade alpha the renderer blends over the canvas, mirroring its
     * `1 - (keep * 0.97)^(dt * 60)` framerate-independent decay. Trails OFF
     * returns 1 - a full wipe, which is exactly what the renderer's `glClear`
     * branch does.
     */
    fun fadeAlpha(
        trails: Boolean,
        trailLength: Float,
        dt: Float,
    ): Float {
        if (!trails) return 1f
        return 1f - (retention(trailLength) * 0.97f).pow(dt * 60f)
    }

    /**
     * Brightness the scene hands its particle shader: the beat pulse ONLY.
     * Exposure (`brightness * intensity`) belongs to the composite grading
     * pass now, so folding it in here as well made Intensity quadratic. The
     * uniform is still uploaded - an unset GL uniform reads 0 and would render
     * the streams black - just at a neutral, exposure-free value.
     */
    fun particleBrightness(beatEnvelope: Float): Float = BASE_BRIGHTNESS + beatEnvelope.coerceIn(0f, 1f) * BEAT_BRIGHTNESS
}
