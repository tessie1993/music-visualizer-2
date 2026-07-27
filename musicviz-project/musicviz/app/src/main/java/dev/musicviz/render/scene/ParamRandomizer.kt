package dev.musicviz.render.scene

import kotlin.random.Random

/**
 * One-tap randomization of the Customize parameters, with per-category locks
 * so users can freeze the parts they've dialed in (e.g. keep Color, roll new
 * Motion + FX). Ranges are curated rather than full-span so a random roll is
 * always watchable: rare/extreme effects (strobe, invert, glitch) appear with
 * low probability and modest amounts instead of uniformly.
 */
object ParamRandomizer {
    enum class Category { MOTION, SHAPE, BEHAVIOR, COLOR, FX }

    fun randomize(
        current: SceneParams,
        locked: Set<Category>,
        rng: Random = Random.Default,
    ): SceneParams {
        fun f(
            lo: Float,
            hi: Float,
        ) = lo + rng.nextFloat() * (hi - lo)

        fun chance(p: Float) = rng.nextFloat() < p

        /** [amount] with probability [p], else 0 - keeps rare FX rare. */
        fun sometimes(
            p: Float,
            lo: Float,
            hi: Float,
        ) = if (chance(p)) f(lo, hi) else 0f

        var r = current
        if (Category.MOTION !in locked) {
            val endless = chance(0.2f)
            r =
                r.copy(
                    speed = f(0.4f, 1.5f),
                    zoom = f(0.85f, 1.3f),
                    rotation = f(-0.4f, 0.4f),
                    endlessZoom = endless,
                    endlessZoomSpeed = if (endless) f(0.2f, 0.6f) else r.endlessZoomSpeed,
                    sway = sometimes(0.4f, 0.1f, 0.5f),
                    pulse = sometimes(0.5f, 0.2f, 0.6f),
                    driftX = sometimes(0.25f, -0.2f, 0.2f),
                    driftY = sometimes(0.25f, -0.2f, 0.2f),
                    shake = sometimes(0.2f, 0.1f, 0.3f),
                )
        }
        if (Category.SHAPE !in locked) {
            val kaleido = chance(0.25f)
            r =
                r.copy(
                    warp = sometimes(0.5f, 0.1f, 0.5f),
                    ripple = sometimes(0.4f, 0.1f, 0.5f),
                    kaleidoscope = kaleido,
                    symmetry = if (kaleido) listOf(4, 6, 8).random(rng) else r.symmetry,
                    morph = sometimes(0.5f, 0.1f, 0.6f),
                    pixelate = sometimes(0.15f, 0.2f, 0.5f),
                    tile = if (chance(0.25f)) f(2f, 3f) else 1f,
                    twist = sometimes(0.4f, -0.5f, 0.5f),
                    particleShape = rng.nextInt(0, 5),
                    particleSize = f(0.7f, 1.4f),
                )
        }
        if (Category.BEHAVIOR !in locked) {
            val trails = chance(0.4f)
            r =
                r.copy(
                    audioDrive = f(0.8f, 1.6f),
                    beatResponse = f(0.5f, 1.8f),
                    turbulence = sometimes(0.5f, 0.1f, 0.6f),
                    density = f(0.6f, 1.3f),
                    trails = trails,
                    trailLength = if (trails) f(0.4f, 0.85f) else r.trailLength,
                    mirror = chance(0.15f),
                    bassGain = f(0.9f, 1.3f),
                    midGain = f(0.9f, 1.3f),
                    trebGain = f(0.9f, 1.3f),
                )
        }
        if (Category.COLOR !in locked) {
            val paletteCount = SceneParams.PALETTES.size
            val cycle = chance(0.3f)
            r =
                r.copy(
                    palette = rng.nextInt(paletteCount),
                    palette2 = rng.nextInt(paletteCount),
                    paletteMix = sometimes(0.5f, 0.3f, 0.7f),
                    colorShift = f(0f, 1f),
                    hueRange = f(0.8f, 1.3f),
                    saturation = f(0.8f, 1.3f),
                    brightness = f(0.9f, 1.15f),
                    contrast = f(0.95f, 1.25f),
                    gamma = f(0.9f, 1.15f),
                    colorCycle = cycle,
                    cycleSpeed = if (cycle) f(0.02f, 0.08f) else r.cycleSpeed,
                    intensity = f(0.9f, 1.25f),
                    duotone = chance(0.1f),
                    temperature = sometimes(0.4f, -0.3f, 0.3f),
                    solarize = chance(0.05f),
                    invert = chance(0.03f),
                    bloom = sometimes(0.5f, 0.1f, 0.5f),
                )
        }
        if (Category.FX !in locked) {
            r =
                r.copy(
                    flash = sometimes(0.5f, 0.1f, 0.5f),
                    chromaAb = sometimes(0.4f, 0.1f, 0.4f),
                    vignette = sometimes(0.5f, 0.1f, 0.5f),
                    scanlines = sometimes(0.25f, 0.2f, 0.5f),
                    grain = sometimes(0.3f, 0.1f, 0.35f),
                    glitch = sometimes(0.2f, 0.1f, 0.4f),
                    fisheye = sometimes(0.25f, 0.2f, 0.5f),
                    strobe = sometimes(0.1f, 0.2f, 0.4f),
                    posterize = sometimes(0.15f, 0.2f, 0.5f),
                )
        }
        return r
    }
}
