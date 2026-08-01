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
     * Lower clamp on the Hue range slider, fluid-only: the emitters pick each
     * splat's dye at `baseHue + frac * span`, so a span of 0 hands every splat
     * the same colour and the style collapses to one flat tint. The slider's
     * own floor is 0, so this floor is what keeps the bottom of its travel
     * meaningful (narrow band, never collapsed).
     */
    const val MIN_HUE_RANGE = 0.1f

    /**
     * Upper clamp on the Hue range slider: the slider's own top
     * (`CustomizeTabs`, 0..1.5) and the top of the randomizer's roll.
     *
     * It used to be 1, which killed the top THIRD of that slider on the whole
     * fluid family while 1..1.5 stayed live on the shader and particle
     * families (they pass `hueRange` through unclamped) - one slider value
     * meaning different things per family is exactly what this object exists
     * to stop. A span over 1 is not out of domain: every consumer wraps
     * (`% 1f` in the emitters, `fract()` in the shaders), so it just means the
     * palette walks more than one turn of the wheel, which is what the same
     * slider value already does on `ParticleSceneBase`.
     */
    const val MAX_HUE_RANGE = 1.5f

    /** Upper clamp on the fluid-only "Palette cycle" slider. */
    const val MAX_PALETTE_CYCLE = 2f

    /**
     * The Hue range slider clamped to the domain the fluid family supports:
     * the slider's own 0..1.5 travel, floored so the emitters can never
     * collapse to a single flat colour. Shared with [span] so the emitter-side
     * and shader-side spans of a scene cannot drift apart.
     */
    fun range(hueRange: Float): Float = hueRange.coerceIn(MIN_HUE_RANGE, MAX_HUE_RANGE)

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
     *
     * [paletteRange] stays clamped to 0..1 - it is palette DATA, a fraction of
     * the wheel, not a user control - so the slider is the only thing that can
     * push the product past one turn, exactly as on the particle family.
     */
    fun span(
        hueRange: Float,
        paletteRange: Float,
    ): Float = range(hueRange) * paletteRange.coerceIn(0f, 1f)

    /**
     * Full-value HSV -> RGB, the fluid family's one conversion. Lives here
     * rather than beside a single caller because the emitters, the water
     * style's idle rain and its finger strokes all have to agree: a splat
     * colour is what [WaterMath.isCatchWell] classifies a drain by, so two
     * copies of this arithmetic would be two ways to be wrong about it.
     */
    fun hsv(
        h: Float,
        s: Float,
        v: Float,
    ): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        hsv(h, s, v, out)
        return Triple(out[0], out[1], out[2])
    }

    /**
     * [hsv] writing into [out] (r, g, b) instead of returning a `Triple`,
     * which boxes all three floats. Identical arithmetic - this IS the
     * arithmetic; the `Triple` form above delegates to it - so a caller can
     * move to this form without the colour changing.
     *
     * The per-splat and per-body callers use it because they convert several
     * times a frame forever; [out] is theirs, so no state is shared between
     * them and this object stays free of mutable state.
     */
    fun hsv(
        h: Float,
        s: Float,
        v: Float,
        out: FloatArray,
    ) {
        val hh = wrap01(h)
        val sextant = hh * 6f
        val i = sextant.toInt() % 6
        val fr = sextant - sextant.toInt()
        val p = v * (1f - s)
        val q = v * (1f - fr * s)
        val t = v * (1f - (1f - fr) * s)
        when (i) {
            0 -> set(out, v, t, p)
            1 -> set(out, q, v, p)
            2 -> set(out, p, v, t)
            3 -> set(out, p, q, v)
            4 -> set(out, t, p, v)
            else -> set(out, v, p, q)
        }
    }

    private fun set(
        out: FloatArray,
        r: Float,
        g: Float,
        b: Float,
    ) {
        out[0] = r
        out[1] = g
        out[2] = b
    }

    /** [hsv] at full value, as the (r, g, b) triple splat callers want. */
    fun rgb(
        hue: Float,
        saturation: Float,
    ): Triple<Float, Float, Float> = hsv(hue, saturation.coerceIn(0f, 1f), 1f)

    /** [rgb] into a caller-owned array; see the [hsv] out-param overload. */
    fun rgb(
        hue: Float,
        saturation: Float,
        out: FloatArray,
    ) = hsv(hue, saturation.coerceIn(0f, 1f), 1f, out)
}
