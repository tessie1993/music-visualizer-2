package dev.musicviz.render

/**
 * How the two live scenes of a Layers composite are combined.
 *
 * The ordinal IS the `uBlendMode` value `composite_frag.glsl` switches on, so
 * the order here is part of the shader contract and entries may only be
 * appended. `CompositeGradeTest` pins the count against the shader's switch.
 *
 * These are the classic separable blend modes, computed per channel on the
 * graded output of each layer (i.e. after `postFx`, so each layer keeps its own
 * family's colour treatment). Nothing here is a Porter-Duff operator - both
 * layers are opaque, and what varies is the FUNCTION, not the coverage.
 */
enum class BlendMode {
    /** The top layer, ignoring the one underneath. What a plain cut does. */
    NORMAL,

    /** `1-(1-b)(1-a)`: never darkens. The safe default for two emissive styles. */
    SCREEN,

    /** `b+a`: brighter and clips. Good for sparse styles, blows out dense ones. */
    ADD,

    /** `b*a`: never brightens. Turns the top layer into a mask over the bottom. */
    MULTIPLY,

    /** `|b-a|`: shared colour cancels to black, so it draws where they DISAGREE. */
    DIFFERENCE,

    /** Multiply in the shadows, screen in the highlights; keeps the base's contrast. */
    OVERLAY,

    /** `max(a,b)`: whichever layer is brighter wins, per channel. */
    LIGHTEN,

    /** `min(a,b)`: whichever layer is darker wins, per channel. */
    DARKEN,
    ;

    companion object {
        /** Never throws: an out-of-range stored value degrades to [SCREEN]. */
        fun fromOrdinal(i: Int): BlendMode = entries.getOrElse(i) { SCREEN }
    }
}
