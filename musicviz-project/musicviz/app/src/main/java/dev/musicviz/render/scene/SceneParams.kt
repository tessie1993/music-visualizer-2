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
    // Feedback-trail motion (docs/ORGANIC_MOTION.md): per-frame zoom of the
    // echo (+in/-out) and sine warp of the resample - the liquid-echo look.
    val trailZoom: Float = 0f,
    val trailWarp: Float = 0f,
    val mirror: Boolean = false,
    // Shape
    val warp: Float = 0f,
    val ripple: Float = 0f,
    // The fold count is a REFINEMENT of [kaleidoscope], not a second switch:
    // every gate that reads it - `uKaleido > 0.5 && uSymmetry >= 2.0` in each
    // scene shader, and the same pair in composite_frag - needs both, so a
    // default below 2 made ticking Kaleidoscope do nothing at all until the
    // user also found the Folds chips. It defaults to a real fold count for
    // the same reason `ParamRandomizer` picks one whenever it turns the
    // toggle on, and nothing reads it while the toggle is off, so the value
    // is inert until it is asked for.
    val symmetry: Int = DEFAULT_SYMMETRY_FOLDS,
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
    // Custom palettes (palette/gradient maker). Each colour slot can be driven
    // by a user-made palette instead of the built-in PALETTES table.
    //
    // SENTINEL CONVENTION (read this before wiring UI to these fields):
    //   an override is ACTIVE when it is >= 0f and INACTIVE when negative.
    //   UNSET_OVERRIDE (-1f) is the canonical "not set" value - clear an
    //   override by writing UNSET_OVERRIDE back, never by writing 0f (0f is a
    //   legitimate base hue: red).
    // Built-in bases live in 0..1 and spans in 0..1, so no real palette value
    // is ever negative. While inactive, paletteBase/paletteRange resolve
    // exactly as before, straight out of PALETTES[palette].
    // Base and range are independent: a gradient maker may override only the
    // hue span and keep the built-in base, or vice versa.
    val paletteBaseOverride: Float = UNSET_OVERRIDE,
    val paletteRangeOverride: Float = UNSET_OVERRIDE,
    val palette2BaseOverride: Float = UNSET_OVERRIDE,
    val palette2RangeOverride: Float = UNSET_OVERRIDE,
    // Id of the saved custom palette backing each slot; NO_CUSTOM_PALETTE ("")
    // means "built-in". Bookkeeping for the palette maker/PaletteStore so the
    // UI can show which saved palette a preset uses and re-resolve it after an
    // edit; rendering only ever reads the *Override floats above.
    val customPaletteId: String = NO_CUSTOM_PALETTE,
    /**
     * Cyclic scientific colour map to paint with, as an index into
     * [CYCLIC_PALETTES], or [NO_PALETTE_LUT] for the procedural palettes.
     *
     * Separate from [palette] rather than appended to PALETTES because these
     * are not expressible as a (base, span) pair at all - they are measured
     * ramps - and because PALETTES is append-only for preset compatibility, so
     * an index there must always mean a hue and a span.
     */
    val paletteLut: Int = NO_PALETTE_LUT,
    val customPalette2Id: String = NO_CUSTOM_PALETTE,
    // How far MilkDrop's own colours are steered toward the palette above, 0..1
    // (read by ProjectMScene's post pass as `uPalTint`; every other family
    // renders the palette directly and ignores this).
    //
    // 0 IS THE DEFAULT AND IS AN EXACT NO-OP, deliberately: a .milk preset
    // authors its own colours, so the palette can only be an opt-in blend
    // TOWARD - anything else would repaint every preset a user already saved
    // and flatten the whole format onto one look.
    val milkdropPaletteTint: Float = 0f,
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
    // treble sparkle splats on/off
    val fluidSparkle: Boolean = true,
    // Fluid - journey (spawn/catch progression; shared by FLUID + CURLFLOW)
    // path family: 0 orbit | 1 lissajous | 2 rose | 3 bloom | 4 drift
    val fluidSpawnPath: Int = 1,
    // choreographed spawn points 1..8
    val fluidSpawnPoints: Int = 3,
    // how strongly song progress reshapes the journey 0..1
    val fluidSpawnProgress: Float = 1f,
    // attractor/catch points 0..4
    val fluidCatchPoints: Int = 2,
    // catch pull strength 0..3 (also dye suction)
    val fluidCatchPull: Float = 1f,
    // capture radius in sim units 0.03..0.3
    val fluidCatchRadius: Float = 0.12f,
    // Fluid - particles
    val fluidParticlesEnabled: Boolean = true,
    // base particle lifetime seconds 1..20
    val fluidParticleLife: Float = 6f,
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
    // Water (WATER scene; wave character shared with the ripple overlay)
    // 0.2..2
    val waterWaveSpeed: Float = 1f,
    // 0.9..0.999
    val waterDamping: Float = 0.985f,
    // 0..2
    val waterRippleStrength: Float = 1f,
    // 0..1
    val waterDepth: Float = 0.6f,
    // 0..1
    val waterSpecular: Float = 0.7f,
    // 0..1
    val waterFlow: Float = 0.3f,
    // Water - liquid ink film. The layer that turns WATER from "a pool tinted
    // by the palette" into "the visuals themselves gone liquid": every emitter
    // splat stains a colour film, the film is transported by the surface flow
    // and refracted through the same ripples that carry it.
    // 0 = the plain depth-graded pool .. 1 = the film IS the surface
    val waterLiquid: Float = 0.85f,
    // How hard the surface slope drags the film, 0..4
    val waterLiquidFlow: Float = 1.4f,
    // How fast the film clears, 0 (never) .. 2
    val waterLiquidFade: Float = 0.35f,
    // Cymatics (CYMATICS scene): the sound's standing-wave field, fullscreen.
    // See CymaticsMath for the pitch -> mode law these parameters tune.
    // 0 = water dish (circular modes) | 1 = Chladni plate (square modes)
    val cymaticsGeometry: Int = 0,
    // Hz that answers with the coarsest mode; 40..440
    val cymaticsFundamental: Float = 110f,
    // standing waves superposed at once, 1..CymaticsMath.MAX_RENDERED_MODES
    val cymaticsModes: Int = 5,
    // how long a mode keeps ringing, 0..1 (CymaticsMath.ringSeconds)
    val cymaticsRing: Float = 0.35f,
    // 0 = raw band energy (bass-led) .. 1 = spectral peaks only (pitch-led)
    val cymaticsFocus: Float = 0.7f,
    // how much of the wave field is on screen at once, 0.5..8
    val cymaticsScale: Float = 3.2f,
    // 0 = bare filigree on dark cells .. 1 = a fully filled iridescent surface
    val cymaticsFill: Float = 0.45f,
    // nodal-line weight, 0..2
    val cymaticsLine: Float = 1f,
    // halo around the nodal lines, 0..2
    val cymaticsGlow: Float = 1f,
    // rainbow fringing on the slopes, 0..1
    val cymaticsIridescence: Float = 0.5f,
    // glassy sheen where the surface is flat, 0..1.5
    val cymaticsCaustic: Float = 0.8f,
    // standing waves (0) .. rings marching outward (1)
    val cymaticsFlow: Float = 0.35f,
    // whole-field rotation in radians/second, -1..1
    val cymaticsSwirl: Float = 0.05f,
    // Hyperspace (HYPERSPACE scene): a room of 3D fractals, each alive on its
    // own clock, walking a five-act story. See HyperspaceMath for the acts and
    // the body lifecycle these tune.
    // how the act is chosen: 0 music, 1 hold, 2 cycle (HYPERSPACE_JOURNEYS)
    val hyperJourney: Int = 0,
    // which act "Hold" pins (HyperspaceMath.ACT_NAMES). Only Hold reads it:
    // Cycle ignores it and always opens on the first act, Music follows energy
    val hyperAct: Int = 2,
    // seconds per act in "Cycle", 5..180
    val hyperCycleSeconds: Float = 30f,
    // multiplier on the act's body count, 0.2..2
    val hyperBodies: Float = 1f,
    // seconds a body lives before it dissolves, 3..45
    val hyperLifetime: Float = 14f,
    // multiplier on every body's own rotation rate, 0..3
    val hyperSpin: Float = 1f,
    // multiplier on every body's own orbit rate, 0..3
    val hyperOrbit: Float = 1f,
    // 0 = a mixed room, 1..6 = every body the same species (HYPERSPACE_SPECIES)
    val hyperSpecies: Int = 0,
    // where in each fractal's usable band it is iterated, 0..1
    val hyperFold: Float = 0.5f,
    // march steps and fractal iterations, 0.25..1.5 (MarchBudget)
    val hyperDetail: Float = 1f,
    // multiplier on the act's emissive glow, 0..2
    val hyperGlow: Float = 1f,
    // silhouette rim light, 0..2
    val hyperNeon: Float = 1f,
    // multiplier on the act's background filigree, 0..2
    val hyperField: Float = 1f,
    // how fast distance fades a body into the void, 0..2
    val hyperHaze: Float = 0.7f,
    // multiplier on the camera's drift rate, 0..3
    val hyperCamera: Float = 1f,
    // mirror sectors in the acts that fold the view, 2..16
    val hyperMirrorFolds: Int = 6,
    // orbit-trap colour banding within a body, 0..1.5
    val hyperTrap: Float = 0.8f,
    // The melt: the fluid engine running underneath HYPERSPACE. The bodies
    // stir it as they drift, the music and the finger stir it, and it stirs
    // them back. See MeltField / MeltMath.
    // how far the medium can pull the geometry out of shape, 0..2
    val hyperMelt: Float = 0.55f,
    // how much dye the medium has carried lights the surfaces, 0..1.5
    val hyperStain: Float = 0.5f,
    // how much the dye glows in the space between the bodies, 0..1.5
    val hyperLiquid: Float = 0.35f,
    // flow-aligned combing of the surfaces, 0..1
    val hyperRidges: Float = 0.5f,
    // how hard the music stirs the medium, 0..3
    val hyperStir: Float = 1f,
    // vorticity of the medium, 0..50
    val hyperSwirl: Float = 26f,
    // how fast the medium comes back to rest, 0..4
    val hyperFlowFade: Float = 0.35f,
    // Beam (BEAM scene): the oscilloscope trace.
    // false = time sweep | true = XY phase plot
    val beamXy: Boolean = false,
    // beam half-width multiplier, 0.2..4
    val beamWidth: Float = 1f,
    // beam brightness, 0..3
    val beamIntensity: Float = 1f,
    // how far the trace fades toward its oldest sample, 0..1
    val beamTail: Float = 0.35f,
    // Ripple overlay (all styles; consumed by follow-up unit F2)
    val rippleOverlayEnabled: Boolean = false,
    val rippleOverlayStrength: Float = 0.4f,
    val rippleOverlaySpecular: Float = 0.3f,
) {
    companion object {
        /** "No override" sentinel for the palette*Override fields (any negative value counts as unset). */
        const val UNSET_OVERRIDE: Float = -1f

        /** "No saved custom palette" sentinel for customPaletteId/customPalette2Id. */
        const val NO_CUSTOM_PALETTE: String = ""

        /** [paletteLut] value meaning "use the procedural palettes". */
        const val NO_PALETTE_LUT: Int = -1

        /**
         * Cyclic scientific colour maps (Fabio Crameri, MIT), in atlas order.
         * Perceptually uniform and seamless at the wrap, which the procedural
         * hue ramps are not - see `lib_palette.glsl`.
         */
        val CYCLIC_PALETTES: List<String> = dev.musicviz.render.CyclicPalettes.NAMES

        val DEFAULT: SceneParams = SceneParams()

        /**
         * The fields no scene renders, and what they are for instead.
         *
         * Every other parameter here exists to change a picture, so one that
         * nothing reads is normally a dead control - `CustomizeSurfaceTest`
         * fails the build on any field missing from both the scenes and this
         * list, and on any entry here that a scene has since started reading.
         * These two are genuinely bookkeeping: they record WHICH saved palette
         * a slot uses so the panel can show it and re-resolve it after an
         * edit, while rendering reads the resolved override hues.
         */
        val NOT_RENDERED: Map<String, String> =
            mapOf(
                "customPaletteId" to "which saved palette slot 1 uses; rendering reads the resolved hues",
                "customPalette2Id" to "which saved palette slot 2 uses; rendering reads the resolved hues",
            )

        /**
         * Palette definitions: name, base hue, hue span multiplier.
         *
         * APPEND ONLY. Presets persist `palette`/`palette2` as indices into
         * this list, so inserting or reordering an entry silently repaints
         * every preset a user has already saved. New palettes go at the end.
         */
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
                // Appended in v0.14: pure secondary hues, narrow spans so they
                // read as the named colour (cf. Fire/Ocean) rather than a wash.
                Triple("Cyan", 0.5f, 0.08f),
                Triple("Magenta", 0.833f, 0.08f),
                Triple("Yellow", 0.167f, 0.08f),
            )

        /** Particle shape names for the shape selector. */
        val PARTICLE_SHAPES: List<String> = listOf("Dot", "Ring", "Star", "Square", "Spark", "Hex", "Bubble")

        /** Symmetry fold options; 0 = off. */
        val SYMMETRY_FOLDS: List<Int> = listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 12, 16)

        /**
         * Fold count the Kaleidoscope toggle runs at until the user picks one.
         * Six-fold is the snowflake the effect is named after, and it reads as
         * symmetry at a glance where two folds read as a mirror.
         */
        const val DEFAULT_SYMMETRY_FOLDS: Int = 6

        /** Fluid beat-splat emitter patterns (index = fluidBeatPattern). */
        val FLUID_PATTERNS: List<String> = listOf("Center", "Ring", "Random", "Spectrum")

        /** Journey path families (index = fluidSpawnPath). */
        val FLUID_PATHS: List<String> = listOf("Orbit", "Lissajous", "Rose", "Bloom", "Drift")

        /**
         * What the CYMATICS field rings in (index = cymaticsGeometry): a round
         * dish of water, whose modes are concentric rings crossed by petals,
         * or a square Chladni plate, whose modes are a nodal lattice.
         */
        val CYMATICS_GEOMETRIES: List<String> = listOf("Water dish", "Chladni plate")

        /** How HYPERSPACE picks its act (index = hyperJourney). */
        val HYPERSPACE_JOURNEYS: List<String> = HyperspaceMath.JOURNEY_MODES

        /**
         * What the bodies in HYPERSPACE are (index = hyperSpecies). Index 0 is
         * a mixed room - every body rolls its own - and is the default,
         * because a room of different fractals is the whole idea; the rest
         * force one species for anyone who wants a study of it.
         *
         * A PERSISTED INDEX: presets store the number, not the name, so
         * [HyperspaceMath.Species] may only ever be appended to. See its own
         * comment.
         */
        val HYPERSPACE_SPECIES: List<String> =
            listOf("Mixed") + HyperspaceMath.SPECIES.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
    }

    /** True when this slot renders a user-made palette rather than a PALETTES entry. */
    val usesCustomPalette: Boolean
        get() = paletteBaseOverride >= 0f || paletteRangeOverride >= 0f

    /** True when the second slot renders a user-made palette rather than a PALETTES entry. */
    val usesCustomPalette2: Boolean
        get() = palette2BaseOverride >= 0f || palette2RangeOverride >= 0f

    // An active override (>= 0f, see UNSET_OVERRIDE) wins over the built-in
    // table; anything negative falls through to PALETTES exactly as before.
    val paletteBase: Float
        get() = if (paletteBaseOverride >= 0f) paletteBaseOverride else PALETTES[palette.coerceIn(0, PALETTES.size - 1)].second

    val paletteRange: Float
        get() = if (paletteRangeOverride >= 0f) paletteRangeOverride else PALETTES[palette.coerceIn(0, PALETTES.size - 1)].third

    val palette2Base: Float
        get() = if (palette2BaseOverride >= 0f) palette2BaseOverride else PALETTES[palette2.coerceIn(0, PALETTES.size - 1)].second

    val palette2Range: Float
        get() = if (palette2RangeOverride >= 0f) palette2RangeOverride else PALETTES[palette2.coerceIn(0, PALETTES.size - 1)].third

    /**
     * Drops a palette slot's custom override so it resolves from [PALETTES]
     * again. Clearing writes [UNSET_OVERRIDE], never 0f - 0f is a legitimate
     * base hue (red) and would leave the override ACTIVE.
     *
     * The sentinel rule lives here, next to the fields and the resolvers it
     * governs, because both layers that need it already depend on
     * SceneParams: the Customize palette chips (`ui`) and `ParamRandomizer`
     * (`render.scene`). While it lived on `ui.PaletteStore` the randomizer had
     * to import `ui`, the only `render.scene -> ui` edge in the tree.
     * `PaletteStore.clear` now delegates here.
     */
    fun withoutCustomPalette(second: Boolean = false): SceneParams =
        if (second) {
            copy(
                palette2BaseOverride = UNSET_OVERRIDE,
                palette2RangeOverride = UNSET_OVERRIDE,
                customPalette2Id = NO_CUSTOM_PALETTE,
            )
        } else {
            copy(
                paletteBaseOverride = UNSET_OVERRIDE,
                paletteRangeOverride = UNSET_OVERRIDE,
                customPaletteId = NO_CUSTOM_PALETTE,
            )
        }
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
