package dev.musicviz.render.scene

/**
 * The constants of the shared particle look, in one place because two
 * unrelated families upload them: the CPU styles through [ParticleSceneBase],
 * and the fluid styles' GPU lifecycle layer through `FluidParticles.draw`.
 * Both feed the same `lib_particle_shade.glsl`, so a value that lived in only one
 * of them would silently make the same slider mean two different things
 * depending on which style was on screen.
 *
 * [dpiScale] is the exception: the fluid scenes compute the same factor
 * inline, and they are left alone deliberately: duplicating two working
 * lines would buy nothing.
 *
 * Pure Kotlin, no GL: the headless tests pin it directly.
 */
object ParticleLook {
    /**
     * Seconds of travel folded into the streak length: a particle crossing
     * 400 px/s stretches to about 1.4x. Deliberately small - the streak is a
     * motion cue, not a smear - and hard-capped again in the shader.
     */
    const val STRETCH_SECONDS: Float = 0.0025f

    /** Aura weight at Bloom 0, and how far the Bloom slider lifts it. */
    private const val GLOW_BASE: Float = 0.85f
    private const val GLOW_PER_BLOOM: Float = 1.2f

    /** Sprite sizes are authored against a 1080p-tall target. */
    private const val REFERENCE_HEIGHT_PX: Float = 1080f

    /**
     * Aura weight for the Bloom slider. The composite bloom pass blurs what
     * the scene already drew, so tying the per-sprite glow to the same slider
     * keeps the two moving together instead of fighting: turning Bloom up
     * gives brighter sprites AND a wider bleed, which is what people expect
     * the control to do.
     */
    fun glow(bloom: Float): Float = GLOW_BASE + bloom.coerceIn(0f, 1f) * GLOW_PER_BLOOM

    /**
     * Size compensation for the render target's height. Without it the whole
     * system shrank to specks on a 1440p panel and bloated on a downscaled
     * render target, because sizes are px values authored at 1080p. Clamped so
     * neither extreme runs away.
     */
    fun dpiScale(viewportHeightPx: Int): Float = (viewportHeightPx.coerceAtLeast(1) / REFERENCE_HEIGHT_PX).coerceIn(0.75f, 2.5f)
}
