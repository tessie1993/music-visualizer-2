package dev.geode.render.scene

/**
 * Stable, user-facing variants for the two authored visual families.
 *
 * The renderer keeps one implementation per family. A style is therefore a
 * compact recipe: it selects a shader branch and biases the family's existing
 * controls without duplicating the audio plumbing, lifecycle, fluid solver or
 * export path. IDs are persisted by presets, so append new entries and never
 * rename an existing id.
 */
internal object VisualStyleCatalog {
    data class HyperspaceStyle(
        val id: String,
        val label: String,
        val shaderStyle: Int,
        val bodyScale: Float = 1f,
        /** Eye distance multiplier. Distance ONLY - the drift rate has its
         *  own field below, because one number applied to both used to move
         *  the camera further out AND faster around, coupling two unrelated
         *  aesthetics. */
        val cameraScale: Float = 1f,
        /** Camera drift-rate multiplier (the half [cameraScale] used to
         *  double as). */
        val driftScale: Float = 1f,
        val fieldScale: Float = 1f,
        val glowScale: Float = 1f,
        val neonScale: Float = 1f,
        val hazeScale: Float = 1f,
        val meltScale: Float = 1f,
        val stainScale: Float = 1f,
        val liquidScale: Float = 1f,
        val ridgeScale: Float = 1f,
        val forcedSpecies: Int? = null,
        /**
         * Worst-case Jacobian norm of this substyle's `styleBody()` deform,
         * uploaded as `uLipschitz` and divided into every distance estimate.
         * The deform runs BEFORE the estimator, so the estimate bounds
         * distance in the deformed frame; a twist or a shell modulation can
         * overestimate the marched-space distance by this factor, and a ray
         * stepping the raw estimate walks through thin geometry (holes and
         * shimmer, not a visible overshoot). Always >= 1; exactly 1 for
         * styles that do not deform. `HyperspaceReworkTest` audits the table.
         */
        val lipschitz: Float = 1f,
        /**
         * The substyle's own screen pre-fold, 0 = none. Applied only while
         * the act's `styleMirror` intent allows it (BREAKTHROUGH releases
         * every fold - see `HyperspaceMath.ACT_PROFILES`), and rescaled by
         * the user's Mirror-folds control around its default of 6.
         */
        val kaleidoFolds: Int = 0,
        /**
         * Floor on the substyle signature weight in `styleSky()`. The shared
         * filigree is gated by Filigree/act field, and at 0 the substyles
         * used to blank into eleven identical voids; the signature now mixes
         * by `max(uField, floor)` so identity survives the slider.
         */
        val signatureFloor: Float = 0.25f,
        /** Accent hue as an OFFSET from the user's base hue, in turns - the
         *  per-substyle colour identity, expressed relative to the palette
         *  so it respects the user's hue controls. */
        val tintHue: Float = 0f,
        val tintSat: Float = 0.7f,
        /** 0 leaves the palette untouched (the Original's setting). */
        val tintAmount: Float = 0f,
        /** Base rate of the substyle's CPU-integrated phase (`uStylePhase`,
         *  wraps at 1), in turns per second before Speed. */
        val phaseRate: Float = 0.05f,
        /** Extra phase rate at full slew-limited bass: the hex tunnel flies
         *  and the wormhole lurches on the low end. */
        val phaseBassRate: Float = 0f,
    )

    data class CymaticsStyle(
        val id: String,
        val label: String,
        val shaderStyle: Int,
        /** null keeps the user's geometry choice; 0 dish, 1 plate. */
        val geometryOverride: Int? = null,
        val scale: Float = 1f,
        val fill: Float = 1f,
        val line: Float = 1f,
        val glow: Float = 1f,
        val iridescence: Float = 1f,
        val caustic: Float = 1f,
        val flow: Float = 1f,
        val swirl: Float = 1f,
        /** Palette identity: added to the family hue ramp's base, in turns.
         *  Unique per substyle, so each sits at its own point on the user's
         *  palette instead of all eleven wearing the same tint. */
        val hueOffset: Float = 0f,
        /** Palette identity: scales the ramp's span (colour-band density). */
        val hueSpan: Float = 1f,
        /** Cap on superposed modes: a struck drum rings one clean figure, a
         *  resonant field stacks eight - catalog data, not a parallel list. */
        val modeCap: Int = CymaticsMath.MAX_RENDERED_MODES,
    )

    val hyperspace: List<HyperspaceStyle> =
        listOf(
            // Backwards-compatible original. It remains selectable beside the
            // ten substyles and keeps every saved v1.4-v1.7 preset valid.
            // tintAmount 0 and signatureFloor 0: no accent, no substyle sky.
            HyperspaceStyle(SceneIds.HYPERSPACE, "Original · Living Fractals", 0, signatureFloor = 0f),
            // KIFS breathing cathedral: a three-deep kaleidoscopic IFS
            // pre-fold over the box species, its fold rotation leaning on the
            // slewed bass so the architecture visibly breathes on kicks.
            // lipschitz = 1.24^3 (three uniform scales); bodyScale up from
            // 0.82 because the pre-fold contracts the visible body by the
            // same factor.
            HyperspaceStyle(
                "hyper_polytope",
                "Polytope",
                1,
                bodyScale = 1.05f,
                cameraScale = 1.08f,
                driftScale = 0.9f,
                fieldScale = 0.72f,
                neonScale = 1.28f,
                forcedSpecies = 3,
                lipschitz = 1.95f,
                kaleidoFolds = 4,
                tintHue = 0.62f,
                tintSat = 0.55f,
                tintAmount = 0.3f,
                phaseRate = 0.04f,
            ),
            // Thin fluid skin: the deform is a travelling wave plus a flatten,
            // bounded by 1 + 0.13*3.1 before the compression.
            HyperspaceStyle(
                "hyper_liquid_warp",
                "Liquid Warp",
                2,
                bodyScale = 0.78f,
                fieldScale = 0.82f,
                glowScale = 1.18f,
                meltScale = 1.35f,
                liquidScale = 1.35f,
                ridgeScale = 0.55f,
                lipschitz = 1.45f,
                tintHue = 0.45f,
                tintSat = 0.6f,
                tintAmount = 0.25f,
                phaseRate = 0.06f,
            ),
            // Double helix: pure axial torsion over the BULB (whose power also
            // breathes on the slewed bass - the mandelbulb breathing lives at
            // species level in the shader). lipschitz = 1 + twist * localRadius
            // = 1 + 0.9 * 1.35, with headroom.
            HyperspaceStyle(
                "hyper_caduceus",
                "Caduceus",
                3,
                bodyScale = 0.72f,
                cameraScale = 1.12f,
                driftScale = 0.85f,
                fieldScale = 0.58f,
                neonScale = 1.38f,
                meltScale = 0.85f,
                forcedSpecies = 5,
                lipschitz = 2.3f,
                tintHue = 0.1f,
                tintSat = 0.75f,
                tintAmount = 0.3f,
            ),
            // Folded organic ridges (1 + 0.075 * 4.3), synaptic sky.
            HyperspaceStyle(
                "hyper_cortex",
                "Cortex",
                4,
                bodyScale = 1.14f,
                cameraScale = 0.94f,
                fieldScale = 1.15f,
                hazeScale = 0.82f,
                ridgeScale = 1.45f,
                stainScale = 1.2f,
                forcedSpecies = 4,
                lipschitz = 1.35f,
                tintHue = 0.88f,
                tintAmount = 0.25f,
            ),
            // Cut facets: abs() and a rotation are isometries, lipschitz 1.
            HyperspaceStyle(
                "hyper_reliquary",
                "Reliquary",
                5,
                bodyScale = 0.68f,
                cameraScale = 1.18f,
                driftScale = 0.8f,
                fieldScale = 0.45f,
                glowScale = 0.82f,
                neonScale = 1.5f,
                hazeScale = 0.72f,
                forcedSpecies = 2,
                kaleidoFolds = 4,
                tintHue = 0.08f,
                tintSat = 0.5f,
                tintAmount = 0.4f,
            ),
            // Hex-grid tunnel: identity moved off the body (the old twin
            // micro-rotations were invisible) and into the spectral hex sky,
            // which flies on the bass. High signature floor: the tunnel IS
            // the style.
            HyperspaceStyle(
                "hyper_moire",
                "Moiré",
                6,
                bodyScale = 0.92f,
                fieldScale = 1.5f,
                glowScale = 0.92f,
                neonScale = 0.72f,
                meltScale = 0.45f,
                liquidScale = 0.42f,
                kaleidoFolds = 12,
                signatureFloor = 0.35f,
                tintHue = 0.5f,
                tintSat = 0.8f,
                tintAmount = 0.3f,
                phaseRate = 0.25f,
                phaseBassRate = 0.45f,
            ),
            // Apollonian jewels: broad pressure shells over the sphere
            // packing, swelling on the slewed bass (the fold wobble lives in
            // the shader's map()). lipschitz = 1 + eps + eps*k*R =
            // 1 + 0.05 + 0.05*3*1.85.
            HyperspaceStyle(
                "hyper_foam",
                "Foam",
                7,
                bodyScale = 1.32f,
                cameraScale = 0.9f,
                fieldScale = 0.38f,
                glowScale = 1.22f,
                neonScale = 0.72f,
                hazeScale = 1.15f,
                stainScale = 1.3f,
                forcedSpecies = 1,
                lipschitz = 1.4f,
                tintHue = 0.55f,
                tintSat = 0.25f,
                tintAmount = 0.35f,
            ),
            // Kaliset star nest: the old 0.022-unit skin displacement sat
            // below the hit epsilon and was never visible, so the body is
            // clean (lipschitz 1) and the identity is the nebula sky driven
            // by slewed bass/mid, plus the treble-lit grain signature.
            HyperspaceStyle(
                "hyper_dustskin",
                "Dustskin",
                8,
                bodyScale = 0.9f,
                fieldScale = 0.82f,
                glowScale = 1.3f,
                neonScale = 0.55f,
                hazeScale = 1.3f,
                liquidScale = 0.75f,
                ridgeScale = 0.65f,
                signatureFloor = 0.4f,
                tintHue = 0.04f,
                tintSat = 0.8f,
                tintAmount = 0.25f,
                phaseRate = 0.02f,
            ),
            // Phyllotaxis chrysanthemum: stretched drifting corals under a
            // golden-angle seed spiral whose seeds are lit by their own
            // spectrum buckets. lipschitz = (1 + 0.3*2.1) * (1 + 0.07*2.2).
            HyperspaceStyle(
                "hyper_plume",
                "Plume",
                9,
                bodyScale = 0.62f,
                cameraScale = 1.2f,
                driftScale = 0.9f,
                fieldScale = 0.5f,
                glowScale = 1.35f,
                neonScale = 0.48f,
                hazeScale = 1.42f,
                meltScale = 1.7f,
                stainScale = 1.2f,
                liquidScale = 1.75f,
                ridgeScale = 0.3f,
                forcedSpecies = 4,
                lipschitz = 1.9f,
                signatureFloor = 0.35f,
                tintHue = 0.93f,
                tintSat = 0.55f,
                tintAmount = 0.3f,
                phaseRate = 0.03f,
                phaseBassRate = 0.05f,
            ),
            // Log-polar Droste descent: the endless approach lives in the sky
            // and the full-frame pulse, so the bodies stay clean (lipschitz
            // 1) and the zoom lurches on the bass via the phase rate.
            HyperspaceStyle(
                "hyper_resonant_wormhole",
                "Resonant Wormhole",
                10,
                bodyScale = 1.18f,
                cameraScale = 0.92f,
                fieldScale = 0.92f,
                glowScale = 1.12f,
                neonScale = 0.9f,
                hazeScale = 0.84f,
                meltScale = 1.15f,
                stainScale = 1.25f,
                liquidScale = 1.15f,
                signatureFloor = 0.35f,
                tintHue = 0.68f,
                tintAmount = 0.3f,
                phaseRate = 0.2f,
                phaseBassRate = 0.35f,
            ),
        )

    val cymatics: List<CymaticsStyle> =
        listOf(
            CymaticsStyle(SceneIds.CYMATICS, "Original · Resonant Field", 0),
            // Sand on a dark plate: grains gather along the nodal filigree
            // and the music shakes them loose. Gold, narrow-span palette.
            CymaticsStyle(
                "chladni_sand",
                "Chladni Sand",
                1,
                geometryOverride = 1,
                scale = 1.08f,
                fill = 0.2f,
                line = 1.28f,
                glow = 0.58f,
                iridescence = 0.18f,
                caustic = 0.25f,
                flow = 0.35f,
                hueOffset = 0.06f,
                hueSpan = 0.45f,
            ),
            // A struck circular membrane: TWO clean Bessel modes at most -
            // rings crossed by diametral nodes, clamped at a hard rim -
            // instead of the original's eight-mode interference field.
            CymaticsStyle(
                "bessel_drum",
                "Drumhead",
                2,
                geometryOverride = 0,
                scale = 0.92f,
                fill = 1.15f,
                line = 0.88f,
                glow = 0.85f,
                iridescence = 0.45f,
                caustic = 0.9f,
                hueOffset = -0.04f,
                hueSpan = 0.7f,
                modeCap = 2,
            ),
            CymaticsStyle(
                "harmonograph",
                "Harmonograph",
                3,
                scale = 0.82f,
                fill = 0.42f,
                line = 1.3f,
                glow = 1.25f,
                iridescence = 0.8f,
                caustic = 0.35f,
                flow = 1.35f,
                swirl = 1.4f,
                hueOffset = 0.18f,
                hueSpan = 1.6f,
            ),
            CymaticsStyle(
                "faraday",
                "Faraday",
                4,
                geometryOverride = 0,
                scale = 1.18f,
                fill = 1.1f,
                line = 0.78f,
                glow = 1.08f,
                iridescence = 0.65f,
                caustic = 1.2f,
                flow = 1.55f,
                hueOffset = -0.12f,
                hueSpan = 1.15f,
            ),
            CymaticsStyle(
                "harmonic_shell",
                "Harmonic Shell",
                5,
                geometryOverride = 0,
                scale = 0.72f,
                fill = 1.2f,
                line = 0.72f,
                glow = 0.95f,
                iridescence = 1.25f,
                caustic = 1.3f,
                swirl = 0.45f,
                hueOffset = 0.1f,
                hueSpan = 1.35f,
            ),
            // Sunlight through rippled water: few, coarse modes make clean
            // caustic webs (and keep the style's extra curvature taps cheap).
            CymaticsStyle(
                "caustic_sheet",
                "Caustic Sheet",
                6,
                scale = 1.05f,
                fill = 1.28f,
                line = 0.55f,
                glow = 0.88f,
                iridescence = 1.3f,
                caustic = 1.65f,
                flow = 1.2f,
                hueOffset = -0.22f,
                hueSpan = 0.5f,
                modeCap = 4,
            ),
            // Acoustic levitation: a few modes give clean antinode shelves
            // for the droplet lattice to hang from.
            CymaticsStyle(
                "levitator",
                "Levitator",
                7,
                geometryOverride = 1,
                scale = 0.88f,
                fill = 0.45f,
                line = 0.92f,
                glow = 1.42f,
                iridescence = 0.6f,
                caustic = 0.72f,
                flow = 0.55f,
                hueOffset = 0.32f,
                hueSpan = 0.8f,
                modeCap = 3,
            ),
            // Room modes: the shader recomposes the first four modes as
            // product cosines, so the cap documents what is actually drawn.
            CymaticsStyle(
                "standing_chamber",
                "Standing Chamber",
                8,
                geometryOverride = 1,
                scale = 0.78f,
                fill = 0.75f,
                line = 0.92f,
                glow = 1.18f,
                iridescence = 0.5f,
                caustic = 0.8f,
                flow = 0.65f,
                hueOffset = -0.3f,
                hueSpan = 0.9f,
                modeCap = 4,
            ),
            CymaticsStyle(
                "rosensweig",
                "Rosensweig Spikes",
                9,
                geometryOverride = 0,
                scale = 1.15f,
                fill = 1.35f,
                line = 0.65f,
                glow = 1.0f,
                iridescence = 0.35f,
                caustic = 1.45f,
                flow = 0.48f,
                hueOffset = 0.45f,
                hueSpan = 0.35f,
            ),
            CymaticsStyle(
                "kundt_tube",
                "Kundt Tube",
                10,
                geometryOverride = 1,
                scale = 0.7f,
                fill = 0.68f,
                line = 1.25f,
                glow = 0.9f,
                iridescence = 0.35f,
                caustic = 0.55f,
                flow = 1.1f,
                hueOffset = 0.03f,
                hueSpan = 0.6f,
            ),
        )

    /**
     * SILK - strange-attractor velocity fields rendered as continuous
     * advected dye ([SilkScene]). A style is a FIELD plus its stroke
     * geometry; the damping parameter b breathes as b = bBase + bAmp *
     * sin(TAU * t / bPeriod), the reference behaviour of this field family.
     */
    data class SilkStyle(
        val id: String,
        val label: String,
        /** Which velocity field, `fieldAt`/2D branches in silk_step_frag. */
        val field: Int,
        val flow: Float = 1f,
        val fieldScale: Float = 1f,
        val strokes: Float = 1f,
        val elong: Float = 1f,
        /** Dye survival per 60 Hz frame; the scene compensates frame rate. */
        val decay: Float = 0.985f,
        val fold: Int = 0,
        val swirl: Float = 0.25f,
        val exposure: Float = 1.35f,
        val hueOffset: Float = 0f,
        val hueSpan: Float = 1f,
        val bBase: Float = 0.17f,
        val bAmp: Float = 0.05f,
        val bPeriod: Float = 37f,
        /** Slab-orbit rate, turns per second: how fast the 2D slice explores. */
        val slabRate: Float = 0.02f,
    )

    val silk: List<SilkStyle> =
        listOf(
            SilkStyle("silk_web", "Halvorsen Web", 0),
            SilkStyle(
                "silk_bloom", "Cosine Bloom", 1,
                flow = 0.85f, fieldScale = 0.8f, decay = 0.988f, swirl = 0.15f,
                hueOffset = 0.55f, bBase = 0.16f, bAmp = 0.05f, bPeriod = 41f,
            ),
            SilkStyle(
                "silk_weave", "Triaxial Weave", 2,
                flow = 1.15f, fieldScale = 1.25f, strokes = 1.3f, elong = 0.7f,
                decay = 0.975f, hueOffset = 0.12f, bBase = 0.16f, bAmp = 0.045f, bPeriod = 47f,
            ),
            SilkStyle(
                "silk_shell", "Concentric Shells", 3,
                flow = 0.9f, fieldScale = 0.9f, strokes = 0.8f, elong = 1.4f,
                decay = 0.987f, swirl = 0.4f, hueOffset = -0.18f,
                bBase = 0.18f, bAmp = 0.035f, bPeriod = 38f,
            ),
            SilkStyle(
                "silk_spiral", "Phase Spiral", 4,
                flow = 1.2f, fieldScale = 1.05f, elong = 1.8f, decay = 0.982f,
                swirl = 0.5f, hueOffset = 0.3f, bBase = 0.17f, bAmp = 0.045f, bPeriod = 49f,
            ),
            SilkStyle(
                "silk_fold", "Recursive Fold", 5,
                fieldScale = 1.15f, strokes = 1.2f, decay = 0.98f,
                hueOffset = 0.78f, bBase = 0.2f, bAmp = 0.04f, bPeriod = 44f,
            ),
            SilkStyle(
                "silk_hyper", "Hyperbolic Bloom", 6,
                flow = 0.8f, fieldScale = 0.75f, elong = 2.2f, decay = 0.99f,
                hueOffset = 0.48f, bBase = 0.22f, bAmp = 0.05f, bPeriod = 35f,
            ),
            SilkStyle(
                "silk_resonance", "Nested Resonance", 7,
                flow = 1.05f, swirl = 0.3f, hueOffset = 0.06f,
                bBase = 0.19f, bAmp = 0.04f, bPeriod = 43f,
            ),
            SilkStyle(
                "silk_curl", "Curl Weave", 8,
                flow = 1.3f, fieldScale = 1.2f, strokes = 1.4f, elong = 0.9f,
                decay = 0.978f, hueOffset = 0.62f,
            ),
            SilkStyle(
                "silk_pendulum", "Pendulum Garden", 9,
                flow = 0.95f, fieldScale = 0.85f, elong = 1.5f, decay = 0.986f,
                fold = 3, swirl = 0f, hueOffset = 0.9f,
            ),
        )

    /**
     * LIFE - continuous cellular matter ([LifeScene]). A style is a SPECIES:
     * rule 0 is Lenia (published catalogue parameters, reimplemented engine),
     * rule 1 is Gray-Scott (curated stable feed/kill organisms). `look`
     * selects the material in life_show_frag.
     */
    data class LifeStyle(
        val id: String,
        val label: String,
        /** 0 Lenia, 1 Gray-Scott. */
        val rule: Int,
        /** Lenia: 1/T. Gray-Scott: the per-pass integration step. */
        val dt: Float,
        val core: Int = 0,
        val growth: Int = 0,
        val mu: Float = 0.15f,
        val sigma: Float = 0.017f,
        val radius: Float = 13f,
        val rings: Int = 1,
        val b1: Float = 1f,
        val b2: Float = 0f,
        val b3: Float = 0f,
        val feed: Float = 0f,
        val kill: Float = 0f,
        val aniso: Float = 0f,
        /** Sim passes per frame at Speed 1; Gray-Scott wants several. */
        val substeps: Int = 1,
        val look: Int = 0,
        val seedJitter: Float = 9f,
        val hueOffset: Float = 0f,
        val hueSpan: Float = 1f,
    )

    val life: List<LifeStyle> =
        listOf(
            // Orbium unicaudatus - the iconic Lenia glider, in soup.
            LifeStyle("life_orbium", "Orbium Drift", 0, dt = 0.1f, mu = 0.15f, sigma = 0.017f, look = 0),
            // Gyrorbium - curving swimmers.
            LifeStyle(
                "life_gyre",
                "Gyre Garden",
                0,
                dt = 0.1f,
                mu = 0.156f,
                sigma = 0.0224f,
                look = 3,
                hueOffset = 0.5f,
            ),
            // Helicium - rotating spirals.
            LifeStyle(
                "life_helix", "Helicium Reef", 0, dt = 0.1f, mu = 0.3f, sigma = 0.0505f,
                look = 2, hueOffset = 0.12f, seedJitter = 7f,
            ),
            // Circium ventilans - pulsing rings.
            LifeStyle(
                "life_pulsar", "Pulsar Colony", 0, dt = 0.1f, mu = 0.38f, sigma = 0.07f,
                look = 0, hueOffset = 0.07f, seedJitter = 6f,
            ),
            // Hydrogeminium natans - big three-ring swimmer, quad4 kernel.
            LifeStyle(
                "life_hydro", "Hydrogeminium", 0, dt = 0.1f, core = 1, growth = 1,
                mu = 0.26f, sigma = 0.036f, radius = 18f, rings = 3, b2 = 1f, b3 = 1f,
                look = 2, hueOffset = 0.4f, seedJitter = 5f,
            ),
            // The SmoothLife bug: step kernel, T = 1.
            LifeStyle(
                "life_bug", "Smooth Bugs", 0, dt = 1f, core = 2, mu = 0.31f, sigma = 0.049f,
                look = 4, hueOffset = 0.85f, seedJitter = 8f,
            ),
            // Gray-Scott classes: the (f, k) pair is the organism.
            LifeStyle(
                "life_mitosis", "Mitosis", 1, dt = 1f, feed = 0.0367f, kill = 0.0649f,
                substeps = 4, look = 0, hueOffset = 0.6f, seedJitter = 11f,
            ),
            LifeStyle(
                "life_coral", "Coral Bloom", 1, dt = 1f, feed = 0.0545f, kill = 0.062f,
                substeps = 5, look = 2, hueOffset = 0.02f, seedJitter = 8f,
            ),
            LifeStyle(
                "life_labyrinth", "Living Ink", 1, dt = 1f, feed = 0.026f, kill = 0.055f,
                substeps = 5, look = 1, hueOffset = 0.09f, seedJitter = 7f,
            ),
            LifeStyle(
                "life_worms", "Ember Worms", 1, dt = 1f, feed = 0.078f, kill = 0.061f,
                substeps = 4, look = 5, seedJitter = 10f,
            ),
        )

    /**
     * ACID - the video-synthesis feedback loop ([AcidScene]). A style is a
     * warp recipe (mode), a source drawing (source), the loop constants and
     * the CRT dressing. Zoom/rotate/feedback are per-frame at 60 Hz; the
     * scene compensates frame rate and hard-caps feedback below 1.
     */
    data class AcidStyle(
        val id: String,
        val label: String,
        /** Warp recipe in acid_step_frag. */
        val mode: Int,
        /** Audio source drawing: 0 chroma mandala, 1 rings, 2 bars, 3 orbit. */
        val source: Int,
        val zoom: Float = 1.010f,
        val rotate: Float = 0.0015f,
        /** Hue rotation, turns per second. */
        val hueRate: Float = 0.04f,
        val feedback: Float = 0.955f,
        val modulate: Float = 0.35f,
        val glitch: Float = 0f,
        val overdrive: Float = 0f,
        val liquid: Float = 0f,
        val scanline: Float = 0f,
        val curve: Float = 0f,
        val saturation: Float = 1.05f,
        val hueOffset: Float = 0f,
        val hueSpan: Float = 1f,
    )

    val acid: List<AcidStyle> =
        listOf(
            AcidStyle(
                "acid_tv", "TV Acid", 0, 0,
                overdrive = 0.8f, liquid = 0.8f, glitch = 0.3f, scanline = 0.25f,
                curve = 0.3f, saturation = 1.15f,
            ),
            AcidStyle(
                "acid_well", "Phosphor Well", 1, 1,
                zoom = 1.035f, rotate = 0.001f, hueRate = 0.015f, feedback = 0.965f,
                modulate = 0.2f, scanline = 0.45f, curve = 0.5f, saturation = 0.8f,
                hueOffset = 0.33f,
            ),
            AcidStyle(
                "acid_kaleid", "Kaleido Melt", 2, 0,
                zoom = 1.014f, rotate = 0.002f, hueRate = 0.05f, feedback = 0.95f,
                modulate = 0.5f, liquid = 0.5f, saturation = 1.1f,
            ),
            AcidStyle(
                "acid_droste", "Droste Throat", 3, 1,
                zoom = 1.0f, rotate = 0.0012f, hueRate = 0.03f, feedback = 0.96f,
                modulate = 0.3f, hueOffset = 0.72f,
            ),
            AcidStyle(
                "acid_prism", "Prism Drift", 4, 1,
                zoom = 1.008f, rotate = -0.0014f, hueRate = 0.02f, feedback = 0.96f,
                hueOffset = 0.45f,
            ),
            AcidStyle(
                "acid_mosh", "Datamosh", 5, 2,
                zoom = 1.004f, rotate = 0f, hueRate = 0.06f, feedback = 0.945f,
                glitch = 1f, overdrive = 0.5f, hueOffset = 0.18f,
            ),
            AcidStyle(
                "acid_scan", "Scanline Surge", 6, 2,
                zoom = 1.006f, rotate = 0f, hueRate = 0.03f, feedback = 0.95f,
                glitch = 0.7f, scanline = 0.8f, curve = 0.6f, hueOffset = 0.55f,
            ),
            AcidStyle(
                "acid_solar", "Solar Flare", 7, 1,
                zoom = 1.012f, rotate = 0.0018f, hueRate = 0.08f, feedback = 0.93f,
                overdrive = 0.3f, hueOffset = 0.06f,
            ),
            AcidStyle(
                "acid_mirror", "Mirror Room", 8, 0,
                zoom = 1.009f, rotate = 0.0008f, hueRate = 0.035f, feedback = 0.958f,
                modulate = 0.45f, hueOffset = 0.85f,
            ),
            AcidStyle(
                "acid_smear", "Neon Smear", 9, 3,
                zoom = 1.005f, rotate = 0.0005f, hueRate = 0.045f, feedback = 0.962f,
                overdrive = 0.6f, liquid = 0.3f, hueOffset = 0.6f,
            ),
        )

    /**
     * MYCELIUM - the Physarum trail ecology ([MycoScene]). A style is a
     * COLONY: its sensor geometry, stride, deposit/decay metabolism, and -
     * for two-species styles - the 2x2 sense matrix that makes rivalry,
     * symbiosis or predation. Angles in radians.
     */
    data class MycoStyle(
        val id: String,
        val label: String,
        val agentRes: Int = 192,
        val sensorDist: Float = 9f,
        val sensorAngle: Float = 0.3927f,
        val turnAngle: Float = 0.7854f,
        val moveStep: Float = 1f,
        val jitter: Float = 0.06f,
        val deposit: Float = 0.12f,
        val decay: Float = 0.905f,
        val speciesMix: Float = 0f,
        val selfA: Float = 1f,
        val crossAb: Float = 0f,
        val crossBa: Float = 0f,
        val selfB: Float = 1f,
        val snap: Float = 0f,
        val reaim: Float = 0f,
        val aniso: Float = 0f,
        val look: Int = 0,
        val exposure: Float = 3.4f,
        val hueOffset: Float = 0f,
        val hueSpan: Float = 1f,
    )

    val myco: List<MycoStyle> =
        listOf(
            MycoStyle("myco_polycephalum", "Polycephalum", reaim = 0.15f),
            MycoStyle(
                "myco_rivals", "Rival Colonies",
                sensorDist = 11f, speciesMix = 0.5f, crossAb = -1f, crossBa = -1f,
                look = 1, exposure = 2.6f, hueSpan = 1.2f, reaim = 0.2f,
            ),
            MycoStyle(
                "myco_symbiosis", "Symbiosis",
                sensorDist = 8f, sensorAngle = 0.45f, speciesMix = 0.5f,
                crossAb = 0.35f, crossBa = 0.35f, hueOffset = 0.1f, reaim = 0.15f,
            ),
            MycoStyle(
                "myco_predator", "Predator",
                agentRes = 176, sensorDist = 12f, moveStep = 1.25f, speciesMix = 0.35f,
                selfA = 0.35f, crossAb = 1.6f, crossBa = -1.3f,
                look = 4, hueOffset = 0.03f, reaim = 0.25f,
            ),
            MycoStyle(
                "myco_ghosts", "Ghost Veil",
                agentRes = 160, sensorDist = 14f, jitter = 0.12f, deposit = 0.05f,
                decay = 0.955f, look = 2, exposure = 4.2f, hueOffset = 0.58f,
            ),
            MycoStyle(
                "myco_circuit", "Circuit Bloom",
                agentRes = 176, sensorAngle = 0.6f, jitter = 0f, deposit = 0.12f,
                decay = 0.87f, snap = 0.7854f, look = 3, exposure = 3.2f, hueOffset = 0.35f,
            ),
            MycoStyle(
                "myco_silkroad", "Silk Roads",
                agentRes = 176, sensorDist = 22f, sensorAngle = 0.18f, turnAngle = 0.12f,
                moveStep = 1.35f, jitter = 0.02f, deposit = 0.10f, decay = 0.93f,
                look = 1, hueOffset = -0.2f,
            ),
            MycoStyle(
                "myco_sporestorm",
                "Spore Storm",
                sensorDist = 8f,
                deposit = 0.16f,
                decay = 0.87f,
                reaim = 0.5f,
                hueOffset = 0.68f,
            ),
            MycoStyle(
                "myco_capillary", "Capillaries",
                agentRes = 208, sensorDist = 5f, sensorAngle = 0.85f, turnAngle = 1.1f,
                moveStep = 0.75f, deposit = 0.14f, decay = 0.9f,
                look = 5, exposure = 3.8f, hueOffset = 0.98f,
            ),
            MycoStyle(
                "myco_frostvein", "Frost Veins",
                agentRes = 176, sensorDist = 10f, sensorAngle = 0.35f, turnAngle = 0.5f,
                moveStep = 0.9f, deposit = 0.12f, decay = 0.915f, aniso = 0.8f,
                look = 1, hueOffset = 0.52f,
            ),
        )

    val hyperspaceIds: List<String> = hyperspace.map { it.id }
    val cymaticsIds: List<String> = cymatics.map { it.id }
    val silkIds: List<String> = silk.map { it.id }
    val lifeIds: List<String> = life.map { it.id }
    val acidIds: List<String> = acid.map { it.id }
    val mycoIds: List<String> = myco.map { it.id }

    private val hyperspaceById = hyperspace.associateBy { it.id }
    private val cymaticsById = cymatics.associateBy { it.id }
    private val silkById = silk.associateBy { it.id }
    private val lifeById = life.associateBy { it.id }
    private val acidById = acid.associateBy { it.id }
    private val mycoById = myco.associateBy { it.id }

    fun hyperspace(id: String): HyperspaceStyle? = hyperspaceById[id]

    fun cymatics(id: String): CymaticsStyle? = cymaticsById[id]

    fun silk(id: String): SilkStyle? = silkById[id]

    fun life(id: String): LifeStyle? = lifeById[id]

    fun acid(id: String): AcidStyle? = acidById[id]

    fun myco(id: String): MycoStyle? = mycoById[id]

    fun isHyperspace(id: String): Boolean = id in hyperspaceById

    fun isCymatics(id: String): Boolean = id in cymaticsById

    fun label(id: String): String =
        hyperspaceById[id]?.label
            ?: cymaticsById[id]?.label
            ?: silkById[id]?.label
            ?: lifeById[id]?.label
            ?: acidById[id]?.label
            ?: mycoById[id]?.label
            ?: id
}
