package dev.musicviz.render.fluid

import kotlin.math.floor

/**
 * Pure-Kotlin hue arithmetic for the fluid scenes' Customize colour wiring,
 * kept out of the GL classes so the headless gate can pin it (same pattern
 * as [FluidMath]).
 *
 * Three Customize controls decide a fluid scene's colours and all three have
 * to be folded in here:
 * - the palette's BASE hue (`paletteBase`), where on the wheel the style sits;
 * - the palette's SPAN multiplier (`paletteRange`), how much of the wheel it
 *   covers - the difference between Fire (0.14, one hot ember band) and
 *   Aurora (0.7, a wide sweep). Dropping it made every palette read as the
 *   same look in a different tint, which is exactly the "colours don't
 *   change on fluid" report;
 * - the "Hue shift" slider (`colorShift`), which the fluid scenes were not
 *   reading at all.
 */
internal object FluidHue {
    /**
     * Lower clamp on the Hue range slider, shared with the other scene
     * families: a span of 0 collapses the style to one flat colour.
     */
    const val MIN_HUE_RANGE = 0.1f

    /**
     * Wraps a hue into [0,1), mirroring GLSL `fract()` (the fluid particle
     * shader's own wrap) rather than `%`, which keeps the sign of the
     * dividend. The upper guard catches the one input `x - floor(x)` can
     * round to exactly 1f: a hue a hair below zero.
     */
    fun wrap01(hue: Float): Float {
        val h = hue - floor(hue)
        return if (h >= 1f) 0f else h
    }

    /**
     * Base hue for a fluid scene: the palette's base plus the Hue shift
     * slider, wrapped - the same `paletteBase + colorShift` convention the
     * particle and shader families use.
     */
    fun base(
        paletteBase: Float,
        colorShift: Float,
    ): Float = wrap01(paletteBase + colorShift)

    /**
     * Slice of the hue wheel a fluid scene spans: the Hue range slider scaled
     * by the palette's own span multiplier (WaterScene's form).
     */
    fun span(
        hueRange: Float,
        paletteRange: Float,
    ): Float = hueRange.coerceIn(MIN_HUE_RANGE, 1f) * paletteRange.coerceIn(0f, 1f)
}
