package dev.musicviz.render.scene

import kotlin.random.Random

/**
 * One-tap randomization of the Customize parameters ("Randomize unlocked").
 *
 * Locks are keyed by the **control label string** shown in the Customize panel
 * (`CustomizeTabs`'s `LabeledSlider` / `LabeledIntSlider` / `CheckRow` /
 * `LockableChipLabel`), because that label is exactly what the lock chip next
 * to each control persists. Every key used here therefore has to match its
 * label verbatim - a typo silently turns the lock into a no-op, which is the
 * regression `ParamRandomizerFluidTest` guards by parsing the labels back out
 * of `CustomizeTabs.kt`. Chip selectors (Palette, Palette 2, Particle shape,
 * Beat pattern, Path) render `LockableChipLabel` for exactly that reason:
 * without it they were the only rolled params a user could not protect.
 *
 * Ranges are curated subsets of each slider's range rather than the full span,
 * so a roll is always watchable *and* always reproducible by hand: nothing is
 * ever set outside the bounds the user can reach with the slider, and rare or
 * extreme effects (strobe, invert, glitch) appear with low probability and
 * modest amounts. The fluid/water/FlowField blocks roll unconditionally: they
 * are ignored by scenes that do not read them, so a roll taken on a particle
 * scene still leaves FLUID/CURLFLOW/WATER in a sane state when the user
 * switches over.
 *
 * Deliberately never randomized:
 *  - the custom-palette override fields (`paletteBaseOverride` and friends,
 *    plus `customPaletteId`/`customPalette2Id`) - a roll must not hijack a
 *    palette the user built and saved by inventing hues for it. Rolling a
 *    slot's *built-in* index does [SceneParams.withoutCustomPalette] on that
 *    slot, because an active override outranks the `PALETTES` lookup: without
 *    the clear, a rolled index would be invisible to anyone using a custom
 *    palette. That clear always writes `UNSET_OVERRIDE`, never 0f (0f is red);
 *  - performance settings (fluid quality / auto quality) and `paramFadeSec`;
 *  - the FlowField and water-ripple master toggles, and the fluid particle/ink
 *    layer toggles - switching both fluid layers off yields a blank screen, so
 *    only their *amounts* roll;
 *  - `fluidSpawnProgress`, which expresses how much the song drives the look.
 */
object ParamRandomizer {
    /** Randomizes every unlocked parameter within its slider range. */
    fun randomize(
        current: SceneParams,
        locked: Set<String>,
        rng: Random = Random.Default,
    ): SceneParams = roll(current, locked, rng, null)

    /** Every lock key this randomizer honours, in Customize-panel order. */
    val KEYS: List<String> =
        mutableListOf<String>()
            .also { roll(SceneParams.DEFAULT, emptySet(), Random(0), it) }
            .toList()

    /**
     * The single implementation behind [randomize] and [KEYS]: [keySink], when
     * non-null, collects every key touched so the published key list can never
     * drift away from the keys actually honoured.
     */
    private fun roll(
        current: SceneParams,
        locked: Set<String>,
        rng: Random,
        keySink: MutableList<String>?,
    ): SceneParams {
        fun f(
            lo: Float,
            hi: Float,
        ) = lo + rng.nextFloat() * (hi - lo)

        /** Inclusive integer draw, matching how `LabeledIntSlider` reads. */
        fun n(
            lo: Int,
            hi: Int,
        ) = lo + rng.nextInt(hi - lo + 1)

        fun chance(p: Float) = rng.nextFloat() < p

        /** A value in [lo]..[hi] with probability [p], else 0 - keeps rare FX rare. */
        fun sometimes(
            p: Float,
            lo: Float,
            hi: Float,
        ) = if (chance(p)) f(lo, hi) else 0f

        val folds = SceneParams.SYMMETRY_FOLDS.filter { fold -> fold >= 2 }
        var s = current

        fun r(
            key: String,
            block: (SceneParams) -> SceneParams,
        ) {
            keySink?.add(key)
            if (key !in locked) s = block(s)
        }

        // ---- Motion ----
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

        // ---- Shape ----
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

        // ---- Behavior ----
        r("Audio drive") { it.copy(audioDrive = f(0.6f, 1.8f)) }
        r("Beat response") { it.copy(beatResponse = f(0.3f, 2f)) }
        r("Beat flash") { it.copy(flash = sometimes(0.5f, 0.1f, 0.6f)) }
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

        // ---- Color ----
        // Clearing the slot's custom-palette override is what makes the rolled
        // index visible; an override otherwise wins over the PALETTES lookup.
        r("Palette") {
            it.copy(palette = rng.nextInt(SceneParams.PALETTES.size)).withoutCustomPalette()
        }
        r("Palette 2") {
            it.copy(palette2 = rng.nextInt(SceneParams.PALETTES.size)).withoutCustomPalette(second = true)
        }
        r("Palette blend") { it.copy(paletteMix = sometimes(0.5f, 0.2f, 0.8f)) }
        // Rolled like any other look param, but `sometimes` keeps 0 - the
        // preset's own colours - the most common outcome on MilkDrop.
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

        // ---- Screen FX ----
        r("Chromatic aberration") { it.copy(chromaAb = sometimes(0.4f, 0.1f, 0.5f)) }
        r("Vignette") { it.copy(vignette = sometimes(0.5f, 0.1f, 0.6f)) }
        r("Scanlines") { it.copy(scanlines = sometimes(0.25f, 0.15f, 0.5f)) }
        r("Film grain") { it.copy(grain = sometimes(0.3f, 0.1f, 0.4f)) }
        r("Glitch") { it.copy(glitch = sometimes(0.2f, 0.1f, 0.4f)) }
        r("Fisheye") { it.copy(fisheye = sometimes(0.25f, -0.5f, 0.5f)) }
        r("Strobe") { it.copy(strobe = sometimes(0.08f, 0.15f, 0.4f)) }

        // ---- Fluid: solver ----
        r("Solver iterations") { it.copy(fluidIterations = n(12, 28)) }
        r("Pressure") { it.copy(fluidPressure = f(0.5f, 0.95f)) }

        // ---- Fluid: character ----
        r("Fluid curl") { it.copy(fluidCurl = f(5f, 45f)) }
        r("Motion fade") { it.copy(fluidVelocityDissipation = f(0.02f, 0.6f)) }
        r("Fluid fade") { it.copy(fluidDensityDissipation = f(0.2f, 2.2f)) }
        r("Chromatic aging") { it.copy(fluidChromaticAging = f(0f, 0.8f)) }

        // ---- Fluid: emitters ----
        r("Beat pattern") { it.copy(fluidBeatPattern = rng.nextInt(SceneParams.FLUID_PATTERNS.size)) }
        r("Beat splats") { it.copy(fluidBeatSplats = n(1, 6)) }
        r("Stirrers") { it.copy(fluidStirrers = n(0, 3)) }
        r("Stirrer speed") { it.copy(fluidStirrerSpeed = f(0.3f, 1.6f)) }
        r("Fluid splat radius") { it.copy(fluidSplatRadius = f(0.05f, 0.25f)) }
        r("Fluid splat force") { it.copy(fluidSplatForce = f(0.5f, 2f)) }
        r("Bass pump") { it.copy(fluidBassPump = chance(0.25f)) }
        r("Treble sparkle") { it.copy(fluidSparkle = chance(0.7f)) }
        r("Palette cycle") { it.copy(fluidPaletteCycleSpeed = f(0f, 1.2f)) }

        // ---- Fluid: journey (shared by FLUID, CURLFLOW and WATER) ----
        r("Path") { it.copy(fluidSpawnPath = rng.nextInt(SceneParams.FLUID_PATHS.size)) }
        r("Spawn points") { it.copy(fluidSpawnPoints = n(2, 5)) }
        r("Catch points") { it.copy(fluidCatchPoints = n(0, 3)) }
        r("Catch pull") { it.copy(fluidCatchPull = f(0.4f, 1.8f)) }
        r("Catch radius") { it.copy(fluidCatchRadius = f(0.06f, 0.2f)) }
        r("Particle life (s)") { it.copy(fluidParticleLife = f(3f, 12f)) }

        // ---- Fluid: particles & look ----
        r("Particle drag") { it.copy(fluidParticleDrag = f(0.15f, 0.9f)) }
        r("Particle brightness") { it.copy(fluidParticleBrightness = f(0.6f, 1.6f)) }
        r("Shading (embossed ink)") { it.copy(fluidShading = chance(0.7f)) }
        r("Glow (fluid)") { it.copy(fluidBloom = chance(0.8f)) }
        r("Fluid glow") { it.copy(fluidBloomIntensity = f(0.4f, 1.4f)) }
        r("Glow threshold") { it.copy(fluidBloomThreshold = f(0.4f, 0.8f)) }
        r("Sunrays") { it.copy(fluidSunrays = chance(0.7f)) }
        r("Sunrays weight") { it.copy(fluidSunraysWeight = f(0.4f, 1f)) }

        // ---- Fluid: audio routing ----
        r("Curl from mids") { it.copy(fluidCurlAudio = f(0.1f, 0.9f)) }
        r("Glow from loudness") { it.copy(fluidBloomAudio = f(0.1f, 0.9f)) }
        r("Fade when quiet") { it.copy(fluidFadeAudio = f(0.2f, 0.9f)) }
        r("Radius on beat") { it.copy(fluidRadiusPulse = f(0f, 0.8f)) }

        // ---- FlowField (every style; the master toggle stays user-owned) ----
        r("Flow strength") { it.copy(flowStrength = f(0.1f, 0.7f)) }
        r("Flow force") { it.copy(flowForce = f(0.4f, 2f)) }
        r("Flow curl") { it.copy(flowCurl = f(5f, 40f)) }
        r("Particles ride the field") { it.copy(flowAdvectParticles = chance(0.7f)) }

        // ---- Water + the all-styles ripple overlay ----
        r("Wave speed") { it.copy(waterWaveSpeed = f(0.5f, 1.6f)) }
        r("Damping") { it.copy(waterDamping = f(0.96f, 0.995f)) }
        // Two distinct controls, two keys: the WATER style's own wave
        // amplitude (0..2) and the all-styles ripple overlay (0..1). They used
        // to share the label "Ripple strength", so one lock chip froze both
        // and one roll wrote both.
        r("Ripple strength") { it.copy(waterRippleStrength = f(0.5f, 1.6f)) }
        r("Depth") { it.copy(waterDepth = f(0.3f, 0.9f)) }
        r("Specular") { it.copy(waterSpecular = f(0.3f, 0.9f)) }
        r("Flow drift") { it.copy(waterFlow = f(0.05f, 0.6f)) }
        // The liquid film's own three knobs. Rolled like the rest of the water
        // block: WATER ignores nothing here, and a scene that never reads them
        // is unaffected, so a roll taken elsewhere still leaves Water sane.
        r("Liquid") { it.copy(waterLiquid = f(0.4f, 1f)) }
        r("Liquid flow") { it.copy(waterLiquidFlow = f(0.6f, 2.6f)) }
        r("Liquid fade") { it.copy(waterLiquidFade = f(0.1f, 1.2f)) }
        r("Ripple overlay strength") { it.copy(rippleOverlayStrength = f(0.15f, 0.7f)) }
        r("Ripple glint") { it.copy(rippleOverlaySpecular = f(0.1f, 0.6f)) }

        // ---- Cymatics (the standing-wave field) ----
        // Rolled unconditionally like the fluid block: a style that does not
        // read these is unaffected, so a roll taken elsewhere still leaves the
        // field in a sane state when the user switches to it.
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

        return s
    }
}
