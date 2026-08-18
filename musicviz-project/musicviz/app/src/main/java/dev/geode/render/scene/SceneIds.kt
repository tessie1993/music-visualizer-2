package dev.geode.render.scene

/** Stable identifiers shared by renderer, presets and the intelligence layer. */
object SceneIds {
    /**
     * The style a fresh install (or a stale persisted id) lands on. A catalog
     * substyle id, not a constant of its own family, so it lives here where
     * every fallback reads it.
     */
    const val DEFAULT: String = "silk_web"
    const val JULIA: String = "julia"
    const val TUNNEL: String = "tunnel"
    const val BARS: String = "bars"
    const val RING: String = "ring"
    const val SCOPE: String = "scope"
    const val PLASMA: String = "plasma"
    const val KALEIDO: String = "kaleido"
    const val WARP: String = "warp"
    const val GRID: String = "grid"
    const val VORONOI: String = "voronoi"
    const val MANDEL: String = "mandel"
    const val LISS: String = "liss"
    const val METABALLS: String = "metaballs"
    const val RIPPLES: String = "ripples"
    const val STARFIELD: String = "starfield"
    const val WAVES: String = "waves"
    const val HEXGRID: String = "hexgrid"
    const val SPIRAL: String = "spiral"
    const val AURORA: String = "aurora"
    const val SOLAR: String = "solar"
    const val WINTER: String = "winter"
    const val LAVA: String = "lava"
    const val MILKDROP: String = "milkdrop"
    const val FLUID: String = "fluid"
    const val CURLFLOW: String = "curlflow"
    const val WATER: String = "water"
    const val CYMATICS: String = "cymatics"
    const val HYPERSPACE: String = "hyperspace"
    const val BEAM: String = "beam"
}
