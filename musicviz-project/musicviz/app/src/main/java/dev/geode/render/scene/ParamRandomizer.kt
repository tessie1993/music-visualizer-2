package dev.geode.render.scene

import kotlin.random.Random

object ParamRandomizer {
    private const val REROLLS = 8

    val NEVER_ROLLED: Map<String, String> =
        mapOf(
            "paletteBaseOverride" to "a user-made palette's own hue",
            "paletteRangeOverride" to "a user-made palette's own hue span",
            "palette2BaseOverride" to "a user-made palette's own hue (slot 2)",
            "palette2RangeOverride" to "a user-made palette's own hue span (slot 2)",
            "customPaletteId" to "which saved palette slot 1 uses",
            "customPalette2Id" to "which saved palette slot 2 uses",
            "paramFadeSec" to "an automation preference, not a look",
            "fluidQuality" to "a performance setting, not a look",
            "fluidAutoQuality" to "a performance setting, not a look",
            "fluidSpawnProgress" to "how much the song itself drives the look",
            "fluidParticlesEnabled" to "the fluid particle layer's master switch",
            "fluidDyeEnabled" to "the fluid ink layer's master switch",
            "flowEnabled" to "the FlowField's master switch",
            "rippleOverlayEnabled" to "the water-ripple overlay's master switch",
            "hyperDetail" to "a performance setting, not a look",
            "hyperJourney" to "how HYPERSPACE picks its act; a roll would unpin a held one",
        )

    fun randomize(
        current: SceneParams,
        locked: Set<String>,
        rng: Random = Random.Default,
        tab: CustomizeTab? = null,
    ): SceneParams {
        repeat(REROLLS) {
            val rolled = roll(current, locked, rng, tab, null)
            if (rolled != current) return rolled
        }
        return current
    }

    val KEYS_BY_TAB: Map<CustomizeTab, List<String>> =
        mutableListOf<Pair<CustomizeTab, String>>()
            .also { roll(SceneParams.DEFAULT, emptySet(), Random(0), null, it) }
            .groupBy({ it.first }, { it.second })

    val KEYS: List<String> = KEYS_BY_TAB.values.flatten()

    val LOCKABLE_LABELS: Set<String> = KEYS.toSet()

    fun keysFor(tab: CustomizeTab): List<String> = KEYS_BY_TAB[tab].orEmpty()

    private fun roll(
        current: SceneParams,
        locked: Set<String>,
        rng: Random,
        tab: CustomizeTab?,
        keySink: MutableList<Pair<CustomizeTab, String>>?,
    ): SceneParams {
        fun f(
            lo: Float,
            hi: Float,
        ) = lo + rng.nextFloat() * (hi - lo)

        fun n(
            lo: Int,
            hi: Int,
        ) = lo + rng.nextInt(hi - lo + 1)

        fun chance(p: Float) = rng.nextFloat() < p

        fun sometimes(
            p: Float,
            lo: Float,
            hi: Float,
        ) = if (chance(p)) f(lo, hi) else 0f

        val folds = SceneParams.SYMMETRY_FOLDS.filter { fold -> fold >= 2 }
        var s = current

        var owner = CustomizeTab.MOTION

        fun section(of: CustomizeTab) {
            owner = of
        }

        fun r(
            key: String,
            block: (SceneParams) -> SceneParams,
        ) {
            keySink?.add(owner to key)
            if (tab != null && tab != owner) return
            if (key !in locked) s = block(s)
        }

        section(CustomizeTab.MOTION)
        r("Speed") { it.copy(speed = f(0.2f, 2.5f)) }
        r("Zoom") { it.copy(zoom = f(0.6f, 2f)) }
        r("Rotation") { it.copy(rotation = f(-1.5f, 1.5f)) }
        r("Sway") { it.copy(sway = sometimes(0.5f, 0.1f, 0.8f)) }
        r("Drift X") { it.copy(driftX = sometimes(0.3f, -0.5f, 0.5f)) }
        r("Drift Y") { it.copy(driftY = sometimes(0.3f, -0.5f, 0.5f)) }
        r("Beat pulse") { it.copy(pulse = sometimes(0.6f, 0.15f, 0.9f)) }
        r("Beat shake") { it.copy(shake = sometimes(0.3f, 0.1f, 0.6f)) }
        r("Endless zoom") { it.copy(endlessZoom = chance(0.2f)) }
        r("Dive speed") { it.copy(endlessZoomSpeed = f(0.1f, 0.8f)) }

        section(CustomizeTab.SHAPE)
        r("XY plot") { it.copy(beamXy = chance(0.35f)) }
        r("Beam width") { it.copy(beamWidth = f(0.4f, 2.5f)) }
        r("Beam brightness") { it.copy(beamIntensity = f(0.5f, 2f)) }
        r("Beam tail") { it.copy(beamTail = f(0.05f, 0.8f)) }
        r("Domain warp") { it.copy(warp = sometimes(0.5f, 0.1f, 0.8f)) }
        r("Ripple") { it.copy(ripple = sometimes(0.4f, 0.1f, 0.8f)) }
        r("Morph") { it.copy(morph = sometimes(0.5f, 0.1f, 0.8f)) }
        r("Twist") { it.copy(twist = sometimes(0.4f, -0.8f, 0.8f)) }
        r("Kaleidoscope") {
            val on = chance(0.3f)
            it.copy(kaleidoscope = on, symmetry = if (on) folds.random(rng) else it.symmetry)
        }
        r("Tile") { it.copy(tile = if (chance(0.25f)) f(2f, 4f) else 1f) }
        r("Pixelate") { it.copy(pixelate = sometimes(0.15f, 0.2f, 0.6f)) }
        r("Posterize") { it.copy(posterize = sometimes(0.15f, 0.2f, 0.6f)) }
        r("Particle shape") { it.copy(particleShape = rng.nextInt(SceneParams.PARTICLE_SHAPES.size)) }
        r("Particle size") { it.copy(particleSize = f(0.5f, 1.8f)) }

        section(CustomizeTab.BEHAVIOR)
        r("Audio drive") { it.copy(audioDrive = f(0.6f, 1.8f)) }
        r("Beat response") { it.copy(beatResponse = f(0.3f, 2f)) }
        r("Beat flash") { it.copy(flash = sometimes(0.5f, 0.1f, 0.6f)) }
        r("Blend preset changes") { it.copy(milkdropBlendPresets = chance(0.35f)) }
        r("Bass gain") { it.copy(bassGain = f(0.8f, 1.4f)) }
        r("Mid gain") { it.copy(midGain = f(0.8f, 1.4f)) }
        r("Treble gain") { it.copy(trebGain = f(0.8f, 1.4f)) }
        r("Turbulence") { it.copy(turbulence = sometimes(0.5f, 0.1f, 1f)) }
        r("Density") { it.copy(density = f(0.4f, 1f)) }
        r("Mirror") { it.copy(mirror = chance(0.15f)) }
        r("Trails (particle scenes)") { it.copy(trails = chance(0.4f)) }
        r("Trail length") { it.copy(trailLength = f(0.3f, 0.9f)) }
        r("Trail zoom (echo in/out)") { it.copy(trailZoom = sometimes(0.3f, -0.3f, 0.3f)) }
        r("Trail warp (liquid echo)") { it.copy(trailWarp = sometimes(0.3f, 0.1f, 0.6f)) }

        section(CustomizeTab.COLOR)
        r("Palette") {
            it.copy(palette = rng.nextInt(SceneParams.PALETTES.size)).withoutCustomPalette()
        }
        r("Colour map") {
            it.copy(
                paletteLut =
                    if (chance(0.25f)) rng.nextInt(SceneParams.CYCLIC_PALETTES.size) else SceneParams.NO_PALETTE_LUT,
            )
        }
        r("Palette 2") {
            it.copy(palette2 = rng.nextInt(SceneParams.PALETTES.size)).withoutCustomPalette(second = true)
        }
        r("Palette blend") { it.copy(paletteMix = sometimes(0.5f, 0.2f, 0.8f)) }
        r("MilkDrop palette tint") { it.copy(milkdropPaletteTint = sometimes(0.4f, 0.2f, 0.9f)) }
        r("Hue shift") { it.copy(colorShift = f(0f, 1f)) }
        r("Hue range") { it.copy(hueRange = f(0.5f, 1.5f)) }
        r("Color cycle") { it.copy(colorCycle = chance(0.3f)) }
        r("Cycle speed") { it.copy(cycleSpeed = f(0.02f, 0.3f)) }
        r("Saturation") { it.copy(saturation = f(0.4f, 1.4f)) }
        r("Brightness") { it.copy(brightness = f(0.7f, 1.3f)) }
        r("Contrast") { it.copy(contrast = f(0.8f, 1.4f)) }
        r("Gamma") { it.copy(gamma = f(0.8f, 1.3f)) }
        r("Intensity") { it.copy(intensity = f(0.7f, 1.4f)) }
        r("Temperature") { it.copy(temperature = sometimes(0.4f, -0.6f, 0.6f)) }
        r("Bloom") { it.copy(bloom = sometimes(0.5f, 0.1f, 0.7f)) }
        r("Duotone") { it.copy(duotone = chance(0.1f)) }
        r("Solarize") { it.copy(solarize = chance(0.05f)) }
        r("Invert") { it.copy(invert = chance(0.03f)) }

        section(CustomizeTab.FX)
        r("Chromatic aberration") { it.copy(chromaAb = sometimes(0.4f, 0.1f, 0.5f)) }
        r("Vignette") { it.copy(vignette = sometimes(0.5f, 0.1f, 0.6f)) }
        r("Scanlines") { it.copy(scanlines = sometimes(0.25f, 0.15f, 0.5f)) }
        r("Film grain") { it.copy(grain = sometimes(0.3f, 0.1f, 0.4f)) }
        r("Glitch") { it.copy(glitch = sometimes(0.2f, 0.1f, 0.4f)) }
        r("Fisheye") { it.copy(fisheye = sometimes(0.25f, -0.5f, 0.5f)) }
        r("Strobe") { it.copy(strobe = sometimes(0.08f, 0.15f, 0.4f)) }

        section(CustomizeTab.FLUID)
        r("Solver iterations") { it.copy(fluidIterations = n(12, 28)) }
        r("Pressure") { it.copy(fluidPressure = f(0.5f, 0.95f)) }

        r("Fluid curl") { it.copy(fluidCurl = f(5f, 45f)) }
        r("Motion fade") { it.copy(fluidVelocityDissipation = f(0.02f, 0.6f)) }
        r("Fluid fade") { it.copy(fluidDensityDissipation = f(0.2f, 2.2f)) }
        r("Chromatic aging") { it.copy(fluidChromaticAging = f(0f, 0.8f)) }

        r("Beat pattern") { it.copy(fluidBeatPattern = rng.nextInt(SceneParams.FLUID_PATTERNS.size)) }
        r("Beat splats") { it.copy(fluidBeatSplats = n(1, 6)) }
        r("Stirrers") { it.copy(fluidStirrers = n(0, 3)) }
        r("Stirrer speed") { it.copy(fluidStirrerSpeed = f(0.3f, 1.6f)) }
        r("Fluid splat radius") { it.copy(fluidSplatRadius = f(0.05f, 0.25f)) }
        r("Fluid splat force") { it.copy(fluidSplatForce = f(0.5f, 2f)) }
        r("Bass pump") { it.copy(fluidBassPump = chance(0.25f)) }
        r("Treble sparkle") { it.copy(fluidSparkle = chance(0.7f)) }
        r("Palette cycle") { it.copy(fluidPaletteCycleSpeed = f(0f, 1.2f)) }

        r("Path") { it.copy(fluidSpawnPath = rng.nextInt(SceneParams.FLUID_PATHS.size)) }
        r("Spawn points") { it.copy(fluidSpawnPoints = n(2, 5)) }
        r("Catch points") { it.copy(fluidCatchPoints = n(0, 3)) }
        r("Catch pull") { it.copy(fluidCatchPull = f(0.4f, 1.8f)) }
        r("Catch radius") { it.copy(fluidCatchRadius = f(0.06f, 0.2f)) }
        r("Particle life (s)") { it.copy(fluidParticleLife = f(3f, 12f)) }

        r("Particle drag") { it.copy(fluidParticleDrag = f(0.15f, 0.9f)) }
        r("Particle brightness") { it.copy(fluidParticleBrightness = f(0.6f, 1.6f)) }
        r("Shading (embossed ink)") { it.copy(fluidShading = chance(0.7f)) }
        r("Glow (fluid)") { it.copy(fluidBloom = chance(0.8f)) }
        r("Fluid glow") { it.copy(fluidBloomIntensity = f(0.4f, 1.4f)) }
        r("Glow threshold") { it.copy(fluidBloomThreshold = f(0.4f, 0.8f)) }
        r("Sunrays") { it.copy(fluidSunrays = chance(0.7f)) }
        r("Sunrays weight") { it.copy(fluidSunraysWeight = f(0.4f, 1f)) }

        r("Curl from mids") { it.copy(fluidCurlAudio = f(0.1f, 0.9f)) }
        r("Glow from loudness") { it.copy(fluidBloomAudio = f(0.1f, 0.9f)) }
        r("Fade when quiet") { it.copy(fluidFadeAudio = f(0.2f, 0.9f)) }
        r("Radius on beat") { it.copy(fluidRadiusPulse = f(0f, 0.8f)) }

        r("Flow strength") { it.copy(flowStrength = f(0.1f, 0.7f)) }
        r("Flow force") { it.copy(flowForce = f(0.4f, 2f)) }
        r("Flow curl") { it.copy(flowCurl = f(5f, 40f)) }

        r("Wave speed") { it.copy(waterWaveSpeed = f(0.5f, 1.6f)) }
        r("Damping") { it.copy(waterDamping = f(0.96f, 0.995f)) }
        r("Ripple strength") { it.copy(waterRippleStrength = f(0.5f, 1.6f)) }
        r("Depth") { it.copy(waterDepth = f(0.3f, 0.9f)) }
        r("Specular") { it.copy(waterSpecular = f(0.3f, 0.9f)) }
        r("Flow drift") { it.copy(waterFlow = f(0.05f, 0.6f)) }
        r("Liquid") { it.copy(waterLiquid = f(0.4f, 1f)) }
        r("Liquid flow") { it.copy(waterLiquidFlow = f(0.6f, 2.6f)) }
        r("Liquid fade") { it.copy(waterLiquidFade = f(0.1f, 1.2f)) }
        r("Ripple overlay strength") { it.copy(rippleOverlayStrength = f(0.15f, 0.7f)) }
        r("Ripple glint") { it.copy(rippleOverlaySpecular = f(0.1f, 0.6f)) }

        section(CustomizeTab.CYMATICS)
        r("Geometry") { it.copy(cymaticsGeometry = rng.nextInt(SceneParams.CYMATICS_GEOMETRIES.size)) }
        r("Fundamental (Hz)") { it.copy(cymaticsFundamental = f(55f, 260f)) }
        r("Standing waves") { it.copy(cymaticsModes = n(2, CymaticsMath.MAX_RENDERED_MODES)) }
        r("Tonal focus") { it.copy(cymaticsFocus = f(0.35f, 1f)) }
        r("Plate ring") { it.copy(cymaticsRing = f(0.15f, 0.8f)) }
        r("Field scale") { it.copy(cymaticsScale = f(1.6f, 5.5f)) }
        r("Wave flow") { it.copy(cymaticsFlow = f(0.05f, 0.8f)) }
        r("Field swirl") { it.copy(cymaticsSwirl = f(-0.3f, 0.3f)) }
        r("Fill") { it.copy(cymaticsFill = f(0.05f, 1f)) }
        r("Nodal lines") { it.copy(cymaticsLine = f(0.4f, 1.7f)) }
        r("Nodal glow") { it.copy(cymaticsGlow = f(0.3f, 1.7f)) }
        r("Iridescence") { it.copy(cymaticsIridescence = f(0f, 1f)) }
        r("Caustic sheen") { it.copy(cymaticsCaustic = f(0.2f, 1.3f)) }

        section(CustomizeTab.HYPERSPACE)
        r("Act") { it.copy(hyperAct = rng.nextInt(HyperspaceMath.ACTS.size)) }
        r("Act length (s)") { it.copy(hyperCycleSeconds = f(12f, 90f)) }
        r("Fractal") { it.copy(hyperSpecies = rng.nextInt(SceneParams.HYPERSPACE_SPECIES.size)) }
        r("Bodies") { it.copy(hyperBodies = f(0.5f, 1.6f)) }
        r("Body life (s)") { it.copy(hyperLifetime = f(6f, 30f)) }
        r("Body spin") { it.copy(hyperSpin = f(0.3f, 2f)) }
        r("Orbit drift") { it.copy(hyperOrbit = f(0.3f, 2f)) }
        r("Camera drift") { it.copy(hyperCamera = f(0.3f, 2f)) }
        r("Fold") { it.copy(hyperFold = f(0.15f, 0.9f)) }
        r("Body glow") { it.copy(hyperGlow = f(0.4f, 1.6f)) }
        r("Neon rim") { it.copy(hyperNeon = f(0.3f, 1.8f)) }
        r("Filigree") { it.copy(hyperField = f(0.2f, 1.6f)) }
        r("Haze") { it.copy(hyperHaze = f(0.2f, 1.4f)) }
        r("Mirror folds") { it.copy(hyperMirrorFolds = n(3, 12)) }
        r("Colour banding") { it.copy(hyperTrap = f(0.2f, 1.3f)) }
        r("Melt") { it.copy(hyperMelt = f(0.15f, 1.1f)) }
        r("Ink stain") { it.copy(hyperStain = f(0.15f, 1.1f)) }
        r("Liquid light") { it.copy(hyperLiquid = f(0.1f, 1.1f)) }
        r("Ridges") { it.copy(hyperRidges = f(0f, 0.9f)) }
        r("Stir") { it.copy(hyperStir = f(0.4f, 2f)) }
        r("Vorticity") { it.copy(hyperSwirl = f(8f, 42f)) }
        r("Flow fade") { it.copy(hyperFlowFade = f(0.1f, 1.2f)) }

        return s
    }
}
