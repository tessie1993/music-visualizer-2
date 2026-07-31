package dev.musicviz.render

import dev.musicviz.render.scene.SceneParams

/**
 * Photosensitivity safety: one clamp every visual path passes through.
 *
 * This app draws full-screen brightness changes on purpose - that is what a
 * music visualizer is - and several of those paths sit squarely in the range
 * that provokes photosensitive seizures. The three that matter, all reachable
 * without the user doing anything unusual:
 *
 *  - `strobe` runs a **9 Hz** square wave over the whole frame at up to 85%
 *    depth (`composite_frag.glsl`: `step(0.5, fract(uTime * 9.0))`). The rate
 *    was hard-coded, so no user control could reach it.
 *  - `flash` adds a beat-triggered white wash, so its rate is the track's -
 *    a 200 BPM track flashes at 3.3 Hz, faster on double-time material.
 *  - An LFO can target `BRIGHTNESS` or `INTENSITY` at up to **30 Hz**
 *    ([Lfo] clamps `rate` to `0.01f..30f`), and `ParamRandomizer` can roll
 *    one. That is a full-screen luminance oscillation deep in the hazardous
 *    band, produced by pressing the randomize button.
 *
 * The thresholds follow WCAG 2.3.1 (and the Harding/ITU guidance it derives
 * from): **three flashes per second** is the general limit, and the risk peaks
 * around 15-20 Hz, so capping RATE matters more than capping depth. Both are
 * capped here anyway, because a large-area luminance swing is the other half
 * of the criterion.
 *
 * ## Why the clamp lives here and not in the sliders
 *
 * Clamping a slider would not work: the value a scene actually sees is the
 * stored param after preset morphing, then after [LfoEngine.apply], then after
 * [AdsrEngine.apply]. A modulator can push a safe stored value anywhere. So
 * [apply] runs LAST, on the final params, at the single point both the live
 * renderer and the exporter already share - and [limitLfoRate] additionally
 * caps the modulator's own rate, because clamping the endpoints of a 30 Hz
 * oscillation still leaves a 30 Hz oscillation.
 *
 * ## Disabled is an exact no-op
 *
 * With [SafetyConfig.enabled] false and [SafetyConfig.reducedMotion] false,
 * [apply] returns the input instance unchanged - not an equal copy - so every
 * saved preset, the default experience and the export byte-parity tests are
 * untouched. That is the same contract `milkdropPaletteTint` and the other
 * opt-in additions in this codebase hold to.
 */
object VisualSafety {
    /**
     * WCAG 2.3.1's general flash threshold: content should not flash more than
     * three times per second. Used as the default rate cap and as the ceiling
     * the strobe and any luminance-targeting LFO are held to.
     */
    const val WCAG_FLASHES_PER_SECOND = 3f

    /**
     * The strobe rate baked into `composite_frag.glsl` before this existed,
     * and still the value uploaded when safety is off, so nothing changes for
     * users who do not turn it on.
     */
    const val DEFAULT_STROBE_HZ = 9f

    /** Strobe depth coefficient in the shader (`uStrobe * 0.85 * ...`). */
    const val STROBE_SHADER_DEPTH = 0.85f

    /** Flash depth coefficient in the shader (`uPostFlash * uBeat * 0.6`). */
    const val FLASH_SHADER_DEPTH = 0.6f

    /** Motion params are scaled by this in reduced-motion mode. */
    const val REDUCED_MOTION_SCALE = 0.4f

    /**
     * User-facing safety settings. Persisted in `GuiPrefs`; passed to both the
     * renderer and the exporter so a clip matches the screen.
     *
     * [enabled] defaults to FALSE deliberately. Every optional visual addition
     * in this codebase ships as an exact no-op so saved presets keep looking
     * the way the user left them, and a safety mode that silently retunes
     * everyone's work would be a surprising change of behaviour on upgrade.
     * Whether it should instead default ON, or be asked about during
     * first-run onboarding (which does not exist yet), is a product decision
     * recorded in todo.md - not one to make silently inside a clamp function.
     */
    data class SafetyConfig(
        /** Master "Safe visuals" switch: caps flash rate and depth. */
        val enabled: Boolean = false,
        /** Flashes per second ceiling. Only consulted when [enabled]. */
        val maxFlashHz: Float = WCAG_FLASHES_PER_SECOND,
        /**
         * Largest full-screen luminance swing a flash may produce, 0..1, as a
         * fraction of the frame. 0 removes flashing entirely.
         */
        val maxFlashDepth: Float = 0.25f,
        /** Allow full-frame inversion and solarize (hard contrast reversals). */
        val allowInversion: Boolean = false,
        /** Independent of [enabled]: scales speed/shake/drift-style motion. */
        val reducedMotion: Boolean = false,
    ) {
        /** True when neither switch would change anything. */
        val isNeutral: Boolean get() = !enabled && !reducedMotion

        companion object {
            /** Everything off - the shipped default and an exact no-op. */
            val OFF = SafetyConfig()
        }
    }

    /**
     * Clamps [p] to [config]. MUST be called after [LfoEngine.apply] and
     * [AdsrEngine.apply], on the params a scene is about to be handed, and
     * identically on the export path.
     *
     * Returns the receiver unchanged when [config] is neutral.
     */
    fun apply(
        p: SceneParams,
        config: SafetyConfig,
    ): SceneParams {
        if (config.isNeutral) return p
        var out = p
        if (config.enabled) {
            val depth = config.maxFlashDepth.coerceIn(0f, 1f)
            out =
                out.copy(
                    // Both are direct full-screen luminance swings; convert the
                    // depth budget into each one's own slider units by dividing
                    // out the coefficient the shader applies.
                    strobe = out.strobe.coerceIn(0f, depth / STROBE_SHADER_DEPTH),
                    flash = out.flash.coerceIn(0f, depth / FLASH_SHADER_DEPTH),
                    // Glitch cuts and displaces blocks frame to frame, which
                    // reads as high-frequency change over a large area.
                    glitch = out.glitch.coerceAtMost(depth),
                    // A hard reversal of the whole frame is the largest
                    // possible contrast change; solarize folds the curve and
                    // is nearly as abrupt.
                    invert = out.invert && config.allowInversion,
                    solarize = out.solarize && config.allowInversion,
                    // Keep the overall level and contrast near neutral so a
                    // beat-driven swell cannot land as a white-out.
                    brightness = out.brightness.coerceIn(0f, 1f + depth),
                    intensity = out.intensity.coerceIn(0f, 1f + depth),
                    contrast = out.contrast.coerceIn(0f, 1f + depth),
                )
        }
        if (config.reducedMotion) {
            out =
                out.copy(
                    speed = out.speed * REDUCED_MOTION_SCALE,
                    shake = out.shake * REDUCED_MOTION_SCALE,
                    sway = out.sway * REDUCED_MOTION_SCALE,
                    driftX = out.driftX * REDUCED_MOTION_SCALE,
                    driftY = out.driftY * REDUCED_MOTION_SCALE,
                    rotation = out.rotation * REDUCED_MOTION_SCALE,
                    turbulence = out.turbulence * REDUCED_MOTION_SCALE,
                    pulse = out.pulse * REDUCED_MOTION_SCALE,
                    cycleSpeed = out.cycleSpeed * REDUCED_MOTION_SCALE,
                )
        }
        return out
    }

    /**
     * The value to upload as `uStrobeHz`. The strobe's rate used to be a
     * literal in the shader, so this is the whole reason the uniform exists:
     * clamping the strobe AMOUNT only dims a 9 Hz flicker, it does not slow it
     * down, and rate is the part the guidance is actually about.
     */
    fun strobeHz(config: SafetyConfig): Float =
        if (config.enabled) {
            config.maxFlashHz.coerceIn(0.1f, DEFAULT_STROBE_HZ)
        } else {
            DEFAULT_STROBE_HZ
        }

    /**
     * Caps an LFO's rate when it drives a large-area luminance parameter.
     *
     * Clamping [apply]'s output bounds the ENDPOINTS of the oscillation but
     * not its frequency, and frequency is what the 3 Hz limit governs - an LFO
     * swinging brightness between two perfectly safe values thirty times a
     * second is exactly the hazard. Targets that move geometry rather than
     * luminance are left alone; they are covered by reduced motion, which is a
     * comfort setting with a different purpose.
     */
    fun limitLfoRate(
        rateHz: Float,
        target: LfoTarget,
        config: SafetyConfig,
    ): Float =
        if (config.enabled && target.isLuminance) {
            rateHz.coerceAtMost(config.maxFlashHz.coerceAtLeast(0.1f))
        } else {
            rateHz
        }

    /**
     * Floors the analysis gate's minimum gap between beats so the BEAT-driven
     * flash cannot outrun [SafetyConfig.maxFlashHz].
     *
     * `flash` fires once per detected beat, so its rate is the track's, not a
     * slider's - at the shipped `INTERVAL_MS_MIN` of 200 ms a dense track can
     * flash five times a second. This is the only lever that reaches that,
     * because the rate is set upstream in the analyzer rather than in any
     * visual param. Returns [requestedMs] untouched when safety is off, and
     * only ever RAISES the gap: a user who has already chosen a calmer
     * setting keeps it.
     */
    fun beatMinIntervalMs(
        requestedMs: Float,
        config: SafetyConfig,
    ): Float =
        if (config.enabled) {
            maxOf(requestedMs, 1000f / config.maxFlashHz.coerceAtLeast(0.1f))
        } else {
            requestedMs
        }

    /**
     * Replaces a hard CUT with a crossfade while safety is on.
     *
     * A cut swaps the entire frame between two consecutive frames, which is a
     * single full-screen change of arbitrary magnitude - the same event the
     * flash limits exist to bound, just triggered by a scene change instead of
     * a beat. Every other transition style already ramps.
     */
    fun transitionStyle(
        requested: TransitionStyle,
        config: SafetyConfig,
    ): TransitionStyle =
        if (config.enabled && requested == TransitionStyle.CUT) TransitionStyle.FADE else requested

    /**
     * Whether an LFO target drives overall frame brightness/contrast, i.e.
     * whether oscillating it fast is a flash rather than motion.
     *
     * Deliberately a property of the target rather than a set held here, so a
     * new [LfoTarget] has to state which side it is on.
     */
    private val LfoTarget.isLuminance: Boolean
        get() =
            when (this) {
                LfoTarget.BRIGHTNESS,
                LfoTarget.INTENSITY,
                LfoTarget.SATURATION,
                LfoTarget.BLOOM,
                LfoTarget.GLITCH,
                -> true
                else -> false
            }
}
