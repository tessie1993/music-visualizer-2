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
    // Fluid (FLUID scene) - grid & solver
    // index into FluidQuality.TIERS (0 Ultra .. 4 Min)
    val fluidQuality: Int = 2,
    val fluidAutoQuality: Boolean = true,
    // 8..40 Jacobi pressure iterations
    val fluidIterations: Int = 20,
    // warm-start damping 0..1
    val fluidPressure: Float = 0.8f,
    // Fluid - character
    // vorticity confinement 0..50
    val fluidCurl: Float = 30f,
    // 0..4
    val fluidVelocityDissipation: Float = 0.2f,
    // 0..4
    val fluidDensityDissipation: Float = 1f,
    // per-channel decay spread 0..1
    val fluidChromaticAging: Float = 0.3f,
    // Fluid - emitters
    // sim units 0.02..0.4
    val fluidSplatRadius: Float = 0.12f,
    // emitter speed multiplier 0..3
    val fluidSplatForce: Float = 1f,
    // 0 center | 1 ring | 2 random | 3 spectrum arc
    val fluidBeatPattern: Int = 1,
    // 0..8
    val fluidBeatSplats: Int = 3,
    // 0..4
    val fluidStirrers: Int = 2,
    // 0..2
    val fluidStirrerSpeed: Float = 1f,
    val fluidBassPump: Boolean = false,
    // 0..2
    val fluidPaletteCycleSpeed: Float = 0.5f,
    // Fluid - particles
    val fluidParticlesEnabled: Boolean = true,
    // 0.02..1; <1 = inertia streaks
    val fluidParticleDrag: Float = 0.5f,
    // 0..2
    val fluidParticleBrightness: Float = 1f,
    // draw the ink layer
    val fluidDyeEnabled: Boolean = true,
    // Fluid - look
    val fluidShading: Boolean = true,
    val fluidBloom: Boolean = true,
    // "Glow (fluid)" - distinct from FX bloom
    val fluidBloomIntensity: Float = 0.8f,
    // 0..1
    val fluidBloomThreshold: Float = 0.6f,
    val fluidSunrays: Boolean = true,
    // 0.3..1
    val fluidSunraysWeight: Float = 1f,
    // Fluid - audio routing
    // mids swirl harder 0..1
    val fluidCurlAudio: Float = 0.5f,
    // loud glows 0..1
    val fluidBloomAudio: Float = 0.5f,
    // quiet passages clear the canvas 0..1
    val fluidFadeAudio: Float = 0.6f,
    // beat radius swell 0..1
    val fluidRadiusPulse: Float = 0.4f,
    // FlowField: fluid principles for EVERY style (composite fluidWarp,
    // particle advection, uFlow sampler for shader scenes)
    val flowEnabled: Boolean = false,
    // fluidWarp amount in the composite 0..1
    val flowStrength: Float = 0.35f,
    // emitter speed multiplier 0..3
    val flowForce: Float = 1f,
    // 0..50
    val flowCurl: Float = 25f,
    // particle scenes ride the field
    val flowAdvectParticles: Boolean = true,
    // Custom colors: a user-built 3-stop RGB gradient (the "gradient maker").
    // When enabled it recolors EVERY scene through the composite gradient
    // map, and the FLUID scene's ink + particles emit these exact colors.
    val gradientEnabled: Boolean = false,
    // 0..1 how strongly the gradient map recolors the frame
    val gradientAmount: Float = 1f,
    // 0..1 speed at which the three stops crossfade into each other
    // ("fading between colours"); 0 = static gradient
    val gradientFade: Float = 0f,
    // Stop A (shadows), B (mids), C (highlights) as 0..1 RGB
    val gradAR: Float = 0.05f,
    val gradAG: Float = 0.15f,
    val gradAB: Float = 0.85f,
    val gradBR: Float = 0.9f,
    val gradBG: Float = 0.2f,
    val gradBB: Float = 0.6f,
    val gradCR: Float = 1f,
    val gradCG: Float = 0.9f,
    val gradCB: Float = 0.35f,
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

        /** Fluid beat-splat emitter patterns (index = fluidBeatPattern). */
        val FLUID_PATTERNS: List<String> = listOf("Center", "Ring", "Random", "Spectrum")
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

/**
 * Resolves the custom gradient's three stops at a moment in time. With
 * gradientFade > 0 the colors ROTATE smoothly through the stop positions
 * (A takes B's color, B takes C's, ...), which loops perfectly - so "fading
 * between colours" never pops at the cycle boundary. Shared by the live
 * composite, the export compositor, and the fluid emitters/particles so the
 * animation is identical everywhere.
 */
object GradientMap {
    /** Returns 9 floats: stopA rgb, stopB rgb, stopC rgb. */
    fun resolvedStops(
        p: SceneParams,
        timeSeconds: Float,
    ): FloatArray {
        val stops =
            arrayOf(
                floatArrayOf(p.gradAR, p.gradAG, p.gradAB),
                floatArrayOf(p.gradBR, p.gradBG, p.gradBB),
                floatArrayOf(p.gradCR, p.gradCG, p.gradCB),
            )
        val fade = p.gradientFade.coerceIn(0f, 1f)
        if (fade <= 0.001f) {
            return floatArrayOf(
                stops[0][0], stops[0][1], stops[0][2],
                stops[1][0], stops[1][1], stops[1][2],
                stops[2][0], stops[2][1], stops[2][2],
            )
        }
        // 0.25 Hz at full fade; phase in [0,3) walks each stop to the next.
        val ph = (timeSeconds * fade * 0.75f).mod(3f)
        val i = ph.toInt()
        val f = ph - i
        val out = FloatArray(9)
        for (s in 0 until 3) {
            val from = stops[(s + i) % 3]
            val to = stops[(s + i + 1) % 3]
            out[s * 3] = from[0] + (to[0] - from[0]) * f
            out[s * 3 + 1] = from[1] + (to[1] - from[1]) * f
            out[s * 3 + 2] = from[2] + (to[2] - from[2]) * f
        }
        return out
    }

    /** Samples the gradient at [t] in 0..1 (A -> B -> C) into [out] (rgb). */
    fun colorAt(
        stops: FloatArray,
        t: Float,
        out: FloatArray,
    ) {
        val x = t.coerceIn(0f, 1f)
        if (x < 0.5f) {
            val f = x * 2f
            for (c in 0 until 3) out[c] = stops[c] + (stops[3 + c] - stops[c]) * f
        } else {
            val f = x * 2f - 1f
            for (c in 0 until 3) out[c] = stops[3 + c] + (stops[6 + c] - stops[3 + c]) * f
        }
    }
}
