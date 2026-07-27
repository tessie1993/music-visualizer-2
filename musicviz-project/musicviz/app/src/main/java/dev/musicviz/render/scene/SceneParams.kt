package dev.musicviz.render.scene

/**
 * User-tunable visual parameters, applied uniformly to every scene type.
 * Grouped as they appear in the Customize panel tabs.
 */
data class SceneParams(
    // Motion
    val speed: Float = 1f,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val endlessZoom: Boolean = false,
    val endlessZoomSpeed: Float = 0.3f,
    val sway: Float = 0f,
    val pulse: Float = 0f,
    val driftX: Float = 0f,
    val driftY: Float = 0f,
    val shake: Float = 0f,
    // Behavior
    val audioDrive: Float = 1f,
    val beatResponse: Float = 1f,
    val turbulence: Float = 0f,
    val density: Float = 1f,
    val trails: Boolean = false,
    val trailLength: Float = 0.5f,
    val mirror: Boolean = false,
    // Shape
    val warp: Float = 0f,
    val ripple: Float = 0f,
    val symmetry: Int = 0,
    val kaleidoscope: Boolean = false,
    val morph: Float = 0f,
    val pixelate: Float = 0f,
    val posterize: Float = 0f,
    val particleShape: Int = 0,
    val particleSize: Float = 1f,
    val tile: Float = 1f,
    val twist: Float = 0f,
    // Color
    val palette: Int = 0,
    val palette2: Int = 1,
    val paletteMix: Float = 0f,
    val colorShift: Float = 0f,
    val hueRange: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val gamma: Float = 1f,
    val colorCycle: Boolean = false,
    val cycleSpeed: Float = 0.1f,
    val invert: Boolean = false,
    val intensity: Float = 1f,
    val duotone: Boolean = false,
    val bloom: Float = 0f,
    val temperature: Float = 0f,
    val solarize: Boolean = false,
    // Behavior extras
    val bassGain: Float = 1f,
    val midGain: Float = 1f,
    val trebGain: Float = 1f,
    val flash: Float = 0f,
    // Post FX (composite pass - applies to every scene type)
    val chromaAb: Float = 0f,
    val vignette: Float = 0f,
    val scanlines: Float = 0f,
    val grain: Float = 0f,
    val glitch: Float = 0f,
    val fisheye: Float = 0f,
    val strobe: Float = 0f,
    // Automation: seconds to fade toward newly applied settings (0 = instant)
    val paramFadeSec: Float = 0f,
) {
    companion object {
        val DEFAULT: SceneParams = SceneParams()

        /** Palette definitions: name, base hue, hue span multiplier. */
        val PALETTES: List<Triple<String, Float, Float>> =
            listOf(
                Triple("Spectrum", 0.0f, 1.0f),
                Triple("Neon", 0.5f, 0.45f),
                Triple("Fire", 0.0f, 0.14f),
                Triple("Ocean", 0.5f, 0.2f),
                Triple("Mono", 0.6f, 0.02f),
                Triple("Candy", 0.85f, 0.5f),
                Triple("Forest", 0.33f, 0.18f),
                Triple("Aurora", 0.45f, 0.7f),
                Triple("Sunset", 0.05f, 0.3f),
                Triple("Ice", 0.55f, 0.15f),
                Triple("Vapor", 0.78f, 0.35f),
                Triple("Toxic", 0.25f, 0.25f),
                Triple("Royal", 0.7f, 0.25f),
                Triple("Blush", 0.93f, 0.12f),
                Triple("Copper", 0.07f, 0.1f),
                Triple("Mint", 0.4f, 0.12f),
                Triple("Galaxy", 0.65f, 0.5f),
                Triple("Cherry", 0.97f, 0.08f),
            )

        /** Particle shape names for the shape selector. */
        val PARTICLE_SHAPES: List<String> = listOf("Dot", "Ring", "Star", "Square", "Spark", "Hex", "Bubble")

        /** Symmetry fold options; 0 = off. */
        val SYMMETRY_FOLDS: List<Int> = listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 12, 16)
    }

    val paletteBase: Float get() = PALETTES[palette.coerceIn(0, PALETTES.size - 1)].second
    val paletteRange: Float get() = PALETTES[palette.coerceIn(0, PALETTES.size - 1)].third
    val palette2Base: Float get() = PALETTES[palette2.coerceIn(0, PALETTES.size - 1)].second
    val palette2Range: Float get() = PALETTES[palette2.coerceIn(0, PALETTES.size - 1)].third
}

/**
 * Applies the per-band gain faders to the features every scene consumes.
 * Shared by the live renderer and the video exporter so exports react to
 * bass/mid/treble gain exactly like the live view.
 */
fun applyBandGains(
    f: dev.musicviz.analysis.AudioFeatures,
    p: SceneParams,
): dev.musicviz.analysis.AudioFeatures {
    if (p.bassGain == 1f && p.midGain == 1f && p.trebGain == 1f) return f
    return f.copy(
        bass = (f.bass * p.bassGain).coerceIn(0f, 2f),
        mid = (f.mid * p.midGain).coerceIn(0f, 2f),
        treble = (f.treble * p.trebGain).coerceIn(0f, 2f),
    )
}
