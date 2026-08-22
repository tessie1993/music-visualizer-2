package dev.geode.render

import dev.geode.render.scene.SceneParams

enum class VisualSafetyChoice {
    UNKNOWN,

    SAFE,

    REDUCED_MOTION,

    CUSTOM,
}

object VisualSafety {
    const val WCAG_FLASHES_PER_SECOND = 3f

    const val DEFAULT_STROBE_HZ = 9f

    const val STROBE_SHADER_DEPTH = 0.85f

    const val FLASH_SHADER_DEPTH = 0.6f

    const val REDUCED_MOTION_SCALE = 0.4f

    data class SafetyConfig(
        val enabled: Boolean = false,
        val maxFlashHz: Float = WCAG_FLASHES_PER_SECOND,
        val maxFlashDepth: Float = 0.25f,
        val allowInversion: Boolean = false,
        val reducedMotion: Boolean = false,
    ) {
        val isNeutral: Boolean get() = !enabled && !reducedMotion

        companion object {
            val OFF = SafetyConfig()

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

    fun resolve(
        choice: VisualSafetyChoice,
        custom: SafetyConfig,
    ): SafetyConfig =
        when (choice) {
            VisualSafetyChoice.UNKNOWN, VisualSafetyChoice.SAFE -> SafetyConfig.SAFE_DEFAULTS
            VisualSafetyChoice.REDUCED_MOTION -> SafetyConfig.SAFE_DEFAULTS.copy(reducedMotion = true)
            VisualSafetyChoice.CUSTOM -> custom
        }

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
                    strobe = out.strobe.coerceIn(0f, depth / STROBE_SHADER_DEPTH),
                    flash = out.flash.coerceIn(0f, depth / FLASH_SHADER_DEPTH),
                    glitch = out.glitch.coerceAtMost(depth),
                    bloom = out.bloom.coerceAtMost(depth),
                    invert = out.invert && config.allowInversion,
                    solarize = out.solarize && config.allowInversion,
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
                    endlessZoomSpeed = out.endlessZoomSpeed * REDUCED_MOTION_SCALE,
                )
        }
        return out
    }

    fun flashImpulse(
        flash: Float,
        beatImpulse: Float,
    ): Float = flash * beatImpulse * FLASH_SHADER_DEPTH

    fun strobeHz(config: SafetyConfig): Float =
        if (config.enabled) {
            config.maxFlashHz.coerceIn(0.1f, DEFAULT_STROBE_HZ)
        } else {
            DEFAULT_STROBE_HZ
        }

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

    fun beatMinIntervalMs(
        requestedMs: Float,
        config: SafetyConfig,
    ): Float =
        if (config.enabled) {
            maxOf(requestedMs, 1000f / config.maxFlashHz.coerceAtLeast(0.1f))
        } else {
            requestedMs
        }

    fun transitionStyle(
        requested: TransitionStyle,
        config: SafetyConfig,
    ): TransitionStyle = if (config.enabled && requested == TransitionStyle.CUT) TransitionStyle.FADE else requested

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
            else -> mix
        }
    }

    fun transitionId(
        requested: String,
        config: SafetyConfig,
    ): String =
        if (config.enabled && requested == TransitionStyle.CUT.name.lowercase()) {
            TransitionStyle.FADE.name.lowercase()
        } else {
            requested
        }

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
