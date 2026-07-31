package dev.musicviz.render.fluid

import kotlin.math.floor

/**
 * Pure-Kotlin hue arithmetic for the fluid scenes' Customize colour wiring,
 * kept out of the GL classes so the headless gate can pin it (same pattern
 * as [FluidMath]).
 *
 * Ownership rule for the fluid family, so no control is applied twice:
 * - the SCENE owns palette IDENTITY, which is what this object computes: the
 *   palette's BASE hue (`paletteBase`), where on the wheel the style sits,
 *   and its SPAN multiplier (`paletteRange`), how much of the wheel it covers
 *   - the difference between Fire (0.14, one hot ember band) and Aurora (0.7,
 *   a wide sweep). Dropping the span made every palette read as the same look
 *   in a different tint, which is exactly the "colours don't change on fluid"
 *   report. Both decide the dye at emission time and cannot be recovered by a
 *   screen-space rotation, so they have to live here;
 * - the COMPOSITE owns hue ROTATION: the "Hue shift" slider (`colorShift`)
 *   and the colour-cycle phase are uploaded as `uPostHue` by
 *   `VisualizerRenderer` for every scene that doesn't grade itself, i.e. this
 *   whole family. Folding either of them in here as well made one slider unit
 *   turn the wheel twice.
 */
internal object FluidHue {
    /**
     * Lower clamp on the Hue range slider, shared with the other scene
     * families: a span of 0 collapses the style to one flat colour.
     */
    const val MIN_HUE_RANGE = 0.1f

    /** Upper clamp on the fluid-only "Palette cycle" slider. */
    const val MAX_PALETTE_CYCLE = 2f

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
     * Base hue for a fluid scene: the palette's own base, wrapped into the
     * unit interval (a user-made palette can arrive from the palette maker
     * with a base outside [0,1)).
     *
     * `colorShift` is deliberately not a parameter. The composite pass
     * rotates the whole fluid frame by `colorShift + cyclePhase`, so a scene
     * that also offset its emission hue would move twice per slider unit.
     */
    fun base(paletteBase: Float): Float = wrap01(paletteBase)

    /**
     * Emitter palette-drift speed: the fluid-only "Palette cycle" slider on
     * its own, clamped to its Customize range.
     *
     * The global Colour cycle toggle and Cycle speed slider used to be added
     * in here as `cycleSpeed * 20` (0.05 per unit per second inside the
     * emitters, i.e. exactly one turn per second per unit). The composite
     * pass now integrates that same phase and rotates the frame by it, so
     * keeping the emission-side term would cycle the fluid twice as fast as
     * every other style.
     */
    fun paletteCycleSpeed(fluidPaletteCycleSpeed: Float): Float = fluidPaletteCycleSpeed.coerceIn(0f, MAX_PALETTE_CYCLE)

    /**
     * Slice of the hue wheel a fluid scene spans: the Hue range slider scaled
     * by the palette's own span multiplier (WaterScene's form).
     */
    fun span(
        hueRange: Float,
        paletteRange: Float,
    ): Float = hueRange.coerceIn(MIN_HUE_RANGE, 1f) * paletteRange.coerceIn(0f, 1f)
}
