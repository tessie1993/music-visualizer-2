package dev.geode.render.scene

import kotlin.random.Random

/**
 * One-tap randomization of the Customize parameters ("Randomize <tab>").
 *
 * A roll is scoped to ONE [CustomizeTab] - the tab the user is looking at.
 * Every key below is declared inside a section that names its tab, and
 * [randomize] rolls only the sections belonging to the requested one, because
 * the button sits inside a tab and a button inside a tab acts on that tab:
 * rolling the whole parameter set from the Color tab silently threw away the
 * motion and shape a user had just dialled in, with no undo. Rolling with no
 * tab at all still rolls everything, which is what the tests use.
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
 * modest amounts. The fluid/water/FlowField block rolls whole whatever style
 * is active - the Fluid tab is one tab, not one per style - because those
 * params are ignored by scenes that do not read them, so a roll taken on a
 * particle scene still leaves FLUID/CURLFLOW/WATER in a sane state when the
 * user switches over. Same for Cymatics, whose tab only appears on its own
 * style anyway.
 *
 * What is deliberately never randomized is declared in [NEVER_ROLLED], with
 * the reason for each - `CustomizeSurfaceTest` checks that list against the
 * parameters this file actually leaves alone, so a parameter added later is
 * either rolled or explained, and can never quietly become neither.
 */
object ParamRandomizer {
    /**
     * How many times [randomize] re-rolls a scope that came back unchanged.
     * Eight takes the FX tab's ~1-in-11 no-op down to about one press in a
     * billion, and bounds the loop when every key in scope is locked.
     */
    private const val REROLLS = 8

    /**
     * The [SceneParams] fields a roll must never write, and why.
     *
     * A declaration rather than a paragraph, because it is the half of the
     * randomizer that is easiest to break by accident: adding a parameter and
     * forgetting to roll it looks exactly like deciding not to.
     * `CustomizeSurfaceTest` compares this to the fields no `r(...)` call
     * below writes and fails on any difference in either direction.
     */
    val NEVER_ROLLED: Map<String, String> =
        mapOf(
            // A roll must not hijack a palette the user built and saved by
            // inventing hues for it. Rolling a slot's BUILT-IN index does
            // clear that slot (withoutCustomPalette), because an active
            // override outranks the PALETTES lookup and the roll would
            // otherwise be invisible to anyone using a custom palette.
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
            // Switching both fluid layers off yields a blank screen, and a
            // master toggle the user turned on is a decision, not a look, so
            // only the amounts behind these roll.
            "fluidParticlesEnabled" to "the fluid particle layer's master switch",
            "fluidDyeEnabled" to "the fluid ink layer's master switch",
            "flowEnabled" to "the FlowField's master switch",
            "rippleOverlayEnabled" to "the water-ripple overlay's master switch",
            // A rolled march budget could hand a slow phone one it cannot
            // hold, and a rolled journey would silently switch a held act
            // back to following the music.
            "hyperDetail" to "a performance setting, not a look",
            "hyperJourney" to "how HYPERSPACE picks its act; a roll would unpin a held one",
        )

    /**
     * Randomizes the unlocked parameters of [tab] within their slider ranges.
     *
     * [tab] `null` means every tab, which is what the tests roll with; the app
     * always passes the tab the Randomize button was pressed on.
     */
    fun randomize(
        current: SceneParams,
        locked: Set<String>,
        rng: Random = Random.Default,
        tab: CustomizeTab? = null,
    ): SceneParams {
        // A press has to DO something. Most screen-FX and shape params are
        // "off" by default and roll through `sometimes`, which draws "leave it
        // alone" most of the time - and once a roll is scoped to one tab, the
        // whole tab can draw that at once. On FX that is about one press in
        // eleven landing on the same look, which reads as a dead button. The
        // answer is another roll rather than fatter odds: the rare-effect
        // probabilities are what keep a roll watchable in the first place.
        repeat(REROLLS) {
            val rolled = roll(current, locked, rng, tab, null)
            if (rolled != current) return rolled
        }
        // Nothing in scope can move (every key locked): rolling harder will
        // not change that, so the look stays as it is.
        return current
    }

    /** Every lock key this randomizer honours, by the tab that owns it. */
    val KEYS_BY_TAB: Map<CustomizeTab, List<String>> =
        mutableListOf<Pair<CustomizeTab, String>>()
            .also { roll(SceneParams.DEFAULT, emptySet(), Random(0), null, it) }
            .groupBy({ it.first }, { it.second })

    /** Every lock key this randomizer honours, in Customize-panel order. */
    val KEYS: List<String> = KEYS_BY_TAB.values.flatten()

    /**
     * [KEYS] as a set: the labels a lock actually guards. Locks exist for
     * "Randomize unlocked" alone, so a control whose label is not in here has
     * nothing a lock could hold - `CustomizeTabs`'s `LockChip` consults this
     * to render no chip on such controls (settings-fade, reactivity, ADSR/LFO
     * card sliders), instead of persisting keys no roll ever honours. Derived
     * from the keys the roll actually uses, never hand-maintained.
     */
    val LOCKABLE_LABELS: Set<String> = KEYS.toSet()

    /** The lock keys [randomize] rolls for [tab]. */
    fun keysFor(tab: CustomizeTab): List<String> = KEYS_BY_TAB[tab].orEmpty()

    /**
     * The single implementation behind [randomize] and [KEYS_BY_TAB]:
     * [keySink], when non-null, collects every key touched (with its tab) so
     * the published key lists can never drift away from the keys actually
     * honoured. Keys are collected whatever [tab] is asked for, so the
     * bookkeeping stays complete even on a scoped roll.
     */
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

        // The tab the keys below belong to, until the next section() call.
        var owner = CustomizeTab.MOTION

        /**
         * Opens the block of keys owned by [of]. Everything between one
         * section and the next belongs to that tab, so a key can never end up
         * in a different tab from the controls it sits with on screen.
         */
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

        // ---- Motion ----
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

        // ---- Shape ----
        section(CustomizeTab.SHAPE)
        // BEAM's own four. Their controls live in ShapeTab, so they roll with
        // Shape - the tab-scope gate requires the two to agree. They sit first
        // because [KEYS] is published in Customize-panel order and the beam
        // group is what ShapeTab renders first, above Distortion.
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
        r("Emergence field") { it.copy(emergenceField = rng.nextInt(SceneParams.EMERGENCE_FIELDS.size)) }
        r("Field current") { it.copy(emergenceSwarm = f(0.35f, 1.2f)) }
        r("Growth tuning") { it.copy(emergenceGrowth = f(0.25f, 0.85f)) }
        r("Acid warp") { it.copy(emergenceAcid = sometimes(0.35f, 0.15f, 0.8f)) }

        // ---- Behavior ----
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

        // ---- Color ----
        section(CustomizeTab.COLOR)
        // Clearing the slot's custom-palette override is what makes the rolled
        // index visible; an override otherwise wins over the PALETTES lookup.
        r("Palette") {
            it.copy(palette = rng.nextInt(SceneParams.PALETTES.size)).withoutCustomPalette()
        }
        // A colour map is a different KIND of palette, so it rolls on its own
        // key: mostly off, because the procedural palettes are the app's own
        // look and a roll that replaced them half the time would flatten it.
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
        section(CustomizeTab.FX)
        r("Chromatic aberration") { it.copy(chromaAb = sometimes(0.4f, 0.1f, 0.5f)) }
        r("Vignette") { it.copy(vignette = sometimes(0.5f, 0.1f, 0.6f)) }
        r("Scanlines") { it.copy(scanlines = sometimes(0.25f, 0.15f, 0.5f)) }
        r("Film grain") { it.copy(grain = sometimes(0.3f, 0.1f, 0.4f)) }
        r("Glitch") { it.copy(glitch = sometimes(0.2f, 0.1f, 0.4f)) }
        r("Fisheye") { it.copy(fisheye = sometimes(0.25f, -0.5f, 0.5f)) }
        r("Strobe") { it.copy(strobe = sometimes(0.08f, 0.15f, 0.4f)) }

        // ---- Fluid: solver ----
        section(CustomizeTab.FLUID)
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
        section(CustomizeTab.CYMATICS)
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

        // ---- Hyperspace (the room of living 3D fractals) ----
        section(CustomizeTab.HYPERSPACE)
        // Rolled unconditionally like the blocks above; "Detail" and
        // "Journey" are held back, with their reasons, in [NEVER_ROLLED].
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
        // The melt. "Melt" itself is rolled modestly: it is the one control
        // here that costs frames (two texture fetches per march step AND a
        // relaxed step, so a high roll marches further for the same picture),
        // and it is also the one that can pull a fractal past the point where
        // it still reads as a fractal.
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
