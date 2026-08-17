package dev.geode.render.fluid

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
 * a scene-specific FLOOR the user still moves with them: with Trails ON the
 * whole Trail length slider is live, remapped onto the band [MIN_RETENTION, 1]
 * where the points join into streams; with Trails OFF the canvas retains
 * [OFF_RETENTION] - a few frames of echo, read as motion blur.
 *
 * Trails OFF is deliberately NOT a hard clear here, which is the third shipped
 * bug this guards: `SceneParams.trails` defaults to FALSE and selecting a style
 * keeps the current params, so "Visuals -> Styles -> Curl Flow" landed on the
 * cleared branch and strobed exactly as described above. Only the single
 * built-in `curlflow · Streams` preset ever switched it on. The floor gives the
 * style its intended look out of the box while keeping the toggle a real,
 * visible control (a short blur versus long ribbons).
 */
internal object CurlFlowMath {
    /**
     * Shortest retention Curl Flow renders at while Trails is on. Below this
     * the streams break up into flickering dots; the remap in [retention]
     * keeps the slider monotonic rather than clamping its lower half flat.
     */
    const val MIN_RETENTION = 0.6f

    /**
     * Retention with Trails OFF: low, but never zero. At 60 Hz a point's echo
     * is spent inside ~4 frames, so the style reads as a motion-blurred spray
     * instead of the strobing dots a `glClear` gives it - and still visibly
     * shorter than anything the Trails-on band ([MIN_RETENTION]..1) produces.
     */
    const val OFF_RETENTION = 0.45f

    /** Baseline point brightness with no beat in flight. */
    const val BASE_BRIGHTNESS = 0.85f

    /** How much a fresh beat lifts the points above [BASE_BRIGHTNESS]. */
    const val BEAT_BRIGHTNESS = 0.35f

    /** Curl field amplitude at neutral audio drive with no beat in flight. */
    const val BASE_AMP = 0.55f

    /** How much a full beat envelope kicks the field above [BASE_AMP]. */
    const val BEAT_AMP = 0.9f

    /**
     * The beat envelope after the "Beat response" slider (0..2, neutral 1).
     * The scene's envelope is a local attack/release with no reader for that
     * slider at all before this - so on Curl Flow "Beat response" was inert
     * even though "Audio drive" was wired. Scaling the envelope (rather than
     * one term) makes the slider move BOTH beat-driven terms, the field kick
     * and the point brightness, and is an exact no-op at 1.
     */
    fun beatDrive(
        beatEnvelope: Float,
        beatResponse: Float,
    ): Float = beatEnvelope * beatResponse.coerceIn(0f, 2f)

    /**
     * Curl field amplitude uniform (`uAmp`). "Audio drive" spans the full
     * slider domain here: the scene used to clamp it to 2.0 while the slider
     * goes to 2.5, so its top fifth was flat on this style.
     */
    fun fieldAmp(
        audioDrive: Float,
        beatDrive: Float,
    ): Float =
        BASE_AMP *
            audioDrive.coerceIn(FluidMath.MIN_AUDIO_DRIVE, FluidMath.MAX_AUDIO_DRIVE) *
            (1f + beatDrive.coerceIn(0f, 2f) * BEAT_AMP)

    /**
     * Frame retention for a given "Trail length" slider value. Strictly
     * increasing over the slider's whole 0.05..0.98 range, so no part of the
     * control is dead.
     *
     * [trails] off returns the fixed [OFF_RETENTION] floor rather than 0: the
     * slider belongs to the toggle, so it goes inert there, but the canvas is
     * never hard-cleared on this style (see the class doc).
     */
    fun retention(
        trailLength: Float,
        trails: Boolean = true,
    ): Float {
        if (!trails) return OFF_RETENTION
        val t = trailLength.coerceIn(0f, 1f)
        return MIN_RETENTION + (1f - MIN_RETENTION) * t
    }

    /**
     * Per-frame fade alpha the renderer blends over the canvas, mirroring its
     * `1 - (keep * 0.97)^(dt * 60)` framerate-independent decay. Trails OFF
     * fades fast but never wipes: the renderer takes the fade branch on this
     * style either way, so the value is always < 1.
     */
    fun fadeAlpha(
        trails: Boolean,
        trailLength: Float,
        dt: Float,
    ): Float = 1f - (retention(trailLength, trails) * 0.97f).pow(dt * 60f)

    /**
     * Frame retention the feedback-trail WARP path writes as `uDecay`
     * (trail_warp_frag), given the same remapped [retention] the plain fade
     * path gets. Shared by every trailing style, but it lives here because
     * Curl Flow is the one whose retention is remapped: `drawTrailWarp` took
     * the remapped value as a parameter and then computed `uDecay` from the
     * RAW `trailLength` slider anyway, so switching Trail zoom or Trail warp
     * on dropped Curl Flow back below [MIN_RETENTION] and the streams broke up
     * into strobing dots at settings the fade path kept smooth.
     */
    fun warpDecay(
        retention: Float,
        dt: Float,
    ): Float = (retention * 0.97f + 0.02f).coerceIn(0f, 0.99f).pow(dt * 60f)

    /**
     * Brightness the scene hands its particle shader: the beat pulse ONLY.
     * Exposure (`brightness * intensity`) belongs to the composite grading
     * pass now, so folding it in here as well made Intensity quadratic. The
     * uniform is still uploaded - an unset GL uniform reads 0 and would render
     * the streams black - just at a neutral, exposure-free value.
     */
    fun particleBrightness(beatEnvelope: Float): Float = BASE_BRIGHTNESS + beatEnvelope.coerceIn(0f, 1f) * BEAT_BRIGHTNESS
}
