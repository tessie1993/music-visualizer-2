package dev.geode.render

import dev.geode.render.scene.SceneParams

/**
 * What the user has said about flashing, which is not the same question as
 * how the limiter is configured.
 *
 * A single "Safe visuals" boolean defaulting to false answered both at once,
 * and the two answers differ: "this person wants the strobe" is a preference,
 * "nobody has ever been asked" is the absence of one. The paths this governs -
 * a 9 Hz full-frame strobe, a beat flash running at the track's rate, a
 * randomizer that can roll a 30 Hz luminance LFO - are the kind where reading
 * silence as consent is the wrong default.
 *
 * [UNKNOWN] is therefore a real state rather than a null, and it resolves to
 * safe behaviour. Persisted alongside a schema version, so when the set of
 * behaviours a choice covers changes, the choice is asked for again instead
 * of being carried forward to something the user never saw.
 */
enum class VisualSafetyChoice {
    /** Never asked, or asked under an older schema. Runs safe. */
    UNKNOWN,

    /** Flash rate, depth, inversion and hard cuts all limited. */
    SAFE,

    /** [SAFE] plus vestibular comfort: speed, drift, shake and zoom scaled down. */
    REDUCED_MOTION,

    /** The stored sliders, verbatim - the only choice that can turn limiting off. */
    CUSTOM,
}

/**
 * Photosensitivity safety: one clamp every visual path passes through.
 *
 * This app draws full-screen brightness changes on purpose - that is what a
 * music visualizer is - and several of those paths sit squarely in the range
 * that provokes photosensitive seizures. The three that matter, all reachable
 * without the user doing anything unusual:
 *
 *  - `strobe` runs a **9 Hz** square wave over the whole frame at up to 85%
 *    depth (`composite_frag.glsl`: `step(0.5, fract(uTime * max(uStrobeHz, 0.1)))`,
 *    with 9.0 the default upload). The rate used to be a hard-coded 9.0,
 *    so no user control could reach it.
 *  - `flash` adds a beat-triggered white wash, so its rate is the track's -
 *    a 200 BPM track flashes at 3.3 Hz, faster on double-time material.
 *  - An LFO can target `BRIGHTNESS` or `INTENSITY` at up to **30 Hz**
 *    ([LfoEngine] clamps `rate` to `0.01f..30f`), and `ParamRandomizer` can roll
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
     * This is the RESOLVED configuration, not a preference. It is produced by
     * [resolve] from a [VisualSafetyChoice], and [enabled] false is reachable
     * only through [VisualSafetyChoice.CUSTOM] - an explicit decision by
     * someone who saw what they were turning off.
     *
     * The constructor defaults stay off so that [OFF] is an exact no-op, which
     * is what makes the export byte-parity tests and every saved preset
     * unaffected by a user who has chosen CUSTOM. Do not read those defaults
     * as the app's behaviour: an install that has made no choice resolves to
     * [SAFE_DEFAULTS].
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
            /** Everything off - an exact no-op, reachable only through [VisualSafetyChoice.CUSTOM]. */
            val OFF = SafetyConfig()

            /**
             * What [VisualSafetyChoice.UNKNOWN] and [VisualSafetyChoice.SAFE]
             * resolve to. Fixed rather than read from the stored sliders: the
             * sliders are CUSTOM's parameters, and a user who has not chosen
             * has not chosen those either, so honouring a permissive stored
             * value here would be the same silent inference the choice exists
             * to remove.
             */
            val SAFE_DEFAULTS =
                SafetyConfig(
                    enabled = true,
                    maxFlashHz = WCAG_FLASHES_PER_SECOND,
                    maxFlashDepth = 0.25f,
                    allowInversion = false,
                    reducedMotion = false,
                )
        }
    }

    /**
     * The one place a [VisualSafetyChoice] becomes engine settings.
     *
     * Every output already reads `GuiPrefs.safety` - the live renderer, the
     * transition picker, the exporter and the wallpaper - so resolving here
     * means all four agree by construction, and a fifth cannot be added that
     * consults the raw switch instead.
     *
     * [custom] is only consulted for [VisualSafetyChoice.CUSTOM]; for every
     * other choice the answer does not depend on it, which is what makes
     * "unknown runs safe" true regardless of what is stored.
     */
    fun resolve(
        choice: VisualSafetyChoice,
        custom: SafetyConfig,
    ): SafetyConfig =
        when (choice) {
            VisualSafetyChoice.UNKNOWN, VisualSafetyChoice.SAFE -> SafetyConfig.SAFE_DEFAULTS
            VisualSafetyChoice.REDUCED_MOTION -> SafetyConfig.SAFE_DEFAULTS.copy(reducedMotion = true)
            VisualSafetyChoice.CUSTOM -> custom
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
                    // Bloom is `col += uPostBloom * col * col` - a superlinear
                    // luminance ADD over the whole frame, so a beat that lands
                    // on a bright frame can nearly double it.
                    bloom = out.bloom.coerceAtMost(depth),
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
                    // A continuous zoom toward or away from the viewer is one
                    // of the strongest vection triggers there is - stronger
                    // than the lateral drift above - so it belongs here more
                    // than most of this list.
                    endlessZoomSpeed = out.endlessZoomSpeed * REDUCED_MOTION_SCALE,
                )
        }
        return out
    }

    /**
     * The full-frame luminance swing `uPostFlash` will produce this frame.
     *
     * The shader adds `uPostFlash * uBeat * FLASH_SHADER_DEPTH`, so this is
     * that product and not the slider value: a flash of 1.0 on a frame with no
     * beat under it changes nothing, and [FlashBudget] must not spend its
     * budget on it.
     */
    fun flashImpulse(
        flash: Float,
        beatImpulse: Float,
    ): Float = flash * beatImpulse * FLASH_SHADER_DEPTH

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
     * slider's. This is the only lever that reaches it, because the rate is
     * set upstream in the analyzer rather than in any visual param.
     *
     * Worth knowing how little this does at the DEFAULT settings, so nobody
     * reads more into the switch than it delivers:
     * `BeatTuning.INTERVAL_MS_DEFAULT` is 1000/3 ms, i.e. already
     * exactly the 3 Hz limit, so a user who has not touched "Minimum gap
     * between beats" sees no change here at all. It bites only for someone who
     * dragged that slider down toward its 200 ms minimum (5 Hz), which is
     * precisely the person it should protect.
     *
     * Returns [requestedMs] untouched when safety is off, and only ever RAISES
     * the gap: a user who has already chosen a calmer setting keeps it.
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
    ): TransitionStyle = if (config.enabled && requested == TransitionStyle.CUT) TransitionStyle.FADE else requested

    /**
     * Bounds how much a Layers blend may change the frame.
     *
     * Layers reaches the screen without passing any limit in [apply]: the
     * blend happens in the composite, AFTER every clamped parameter, and its
     * magnitude is set by the mix and the mode rather than by any of the
     * params [apply] sees. Two of the eight modes are genuine full-frame
     * luminance events at high mix, which is the exact hazard the rest of this
     * file exists to bound:
     *
     * - [BlendMode.ADD] can nearly double frame brightness, and does so
     *   whenever the two layers happen to peak together - which for two
     *   beat-driven scenes is ON THE BEAT, at the track's own rate.
     * - [BlendMode.DIFFERENCE] takes the frame to black wherever the layers
     *   agree, so two scenes drifting in and out of agreement swing the whole
     *   frame between lit and dark with nothing setting the rate.
     *
     * The mix is clamped rather than the mode substituted, because the mix is
     * already the magnitude control and [apply] bounds full-frame events by
     * magnitude ([SafetyConfig.maxFlashDepth]) rather than by forbidding them.
     * A substitution would also change the look for a reason the user could
     * not see, which [transitionStyle] gets away with only because a cut and a
     * fast fade read the same.
     *
     * DIFFERENCE is additionally a contrast REVERSAL - identical layers
     * produce black - so it is gated on [SafetyConfig.allowInversion], the
     * same switch that governs Invert and Solarize.
     */
    fun layerMix(
        requested: Float,
        mode: BlendMode,
        config: SafetyConfig,
    ): Float {
        val mix = requested.coerceIn(0f, 1f)
        if (!config.enabled) return mix
        return when (mode) {
            BlendMode.DIFFERENCE ->
                if (config.allowInversion) minOf(mix, config.maxFlashDepth) else 0f
            BlendMode.ADD -> minOf(mix, config.maxFlashDepth)
            // SCREEN, MULTIPLY, LIGHTEN, DARKEN, OVERLAY and NORMAL are all
            // bounded by the two layers themselves - none can produce a value
            // outside the range its inputs already occupied - so a mix of 1 is
            // no brighter than the brighter layer already was.
            else -> mix
        }
    }

    /**
     * The same substitution over transition IDs, which is what the renderer
     * takes now that the library sits alongside the five built-in styles.
     * Only a cut is replaced: every gl-transition ramps by construction, so
     * none of the 122 is a single-frame full-screen change.
     */
    fun transitionId(
        requested: String,
        config: SafetyConfig,
    ): String =
        if (config.enabled && requested == TransitionStyle.CUT.name.lowercase()) {
            TransitionStyle.FADE.name.lowercase()
        } else {
            requested
        }

    /**
     * Whether oscillating this target quickly reads as the SCREEN FLASHING
     * rather than as something moving.
     *
     * Note this set is deliberately NOT the same as the set [apply] clamps,
     * and the difference is not an oversight - the two bound different things.
     * [apply] bounds the peak magnitude of a full-frame luminance event, which
     * only matters for params that are large and bright at rest (brightness,
     * contrast, bloom, the flash controls). This bounds the FREQUENCY of any
     * modulation a viewer would perceive as flashing, which includes params
     * that are perfectly safe at any fixed value and only become a hazard when
     * swung fast: a vignette pumping at 30 Hz is a large-area luminance
     * oscillation, and hue/palette at 30 Hz is the saturated-colour flashing
     * WCAG 2.3.1 calls out separately from luminance. Geometry targets are
     * excluded because fast geometry is motion, which is what reduced motion
     * is for.
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
                LfoTarget.VIGNETTE,
                LfoTarget.COLOR_SHIFT,
                LfoTarget.PALETTE_MIX,
                LfoTarget.TEMPERATURE,
                -> true
                else -> false
            }
}
