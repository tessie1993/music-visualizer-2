package dev.musicviz.render.scene

/**
 * A Customize control's stable identity.
 *
 * [id] is what gets persisted; [label] is what the panel draws. They are kept
 * apart because a lock the user set must survive the label being reworded.
 */
data class ParamKey(
    val id: String,
    val label: String,
)

/**
 * The stable ids behind the "Randomize unlocked" lock chips.
 *
 * Locks are still keyed by the control's **label** in memory: that is what the
 * chip next to each control carries, what [ParamRandomizer] matches on, and
 * what `ParamRandomizerFluidTest` pins against the labels parsed out of
 * `CustomizeDialog.kt`. Only the *persisted* form changes here — see
 * `ui.ParamLockStore`, which converts through [idsOf] on save and [labelsOf]
 * on load. Renaming a label therefore now costs one edit in this table
 * instead of silently stranding every lock a user had set.
 *
 * The id of a control is the [SceneParams] field it rolls, which is also the
 * key that field already serializes under in preset JSON (`ui.PresetStore`),
 * so the app has one identifier space rather than two. Every id here is a real
 * SceneParams field, no two controls claim the same one, and no id collides
 * with any label — `ParamKeysTest` holds all three.
 *
 * Entries are in Customize-panel order, matching [ParamRandomizer.KEYS].
 *
 * APPEND/EDIT RULES: never change an [id] — that is the persisted value, and
 * changing it strands locks exactly the way labels used to. Rewording a
 * [label] is safe and is the whole point. A control that is removed should
 * keep its entry so old lock sets still round-trip.
 */
object ParamKeys {
    val ALL: List<ParamKey> =
        listOf(
            // Motion
            ParamKey("speed", "Speed"),
            ParamKey("zoom", "Zoom"),
            ParamKey("rotation", "Rotation"),
            ParamKey("sway", "Sway"),
            ParamKey("driftX", "Drift X"),
            ParamKey("driftY", "Drift Y"),
            ParamKey("pulse", "Beat pulse"),
            ParamKey("shake", "Beat shake"),
            ParamKey("endlessZoom", "Endless zoom"),
            ParamKey("endlessZoomSpeed", "Dive speed"),
            // Shape
            ParamKey("warp", "Domain warp"),
            ParamKey("ripple", "Ripple"),
            ParamKey("morph", "Morph"),
            ParamKey("twist", "Twist"),
            ParamKey("kaleidoscope", "Kaleidoscope"),
            ParamKey("tile", "Tile"),
            ParamKey("pixelate", "Pixelate"),
            ParamKey("posterize", "Posterize"),
            ParamKey("particleShape", "Particle shape"),
            ParamKey("particleSize", "Particle size"),
            // Behavior
            ParamKey("audioDrive", "Audio drive"),
            ParamKey("beatResponse", "Beat response"),
            ParamKey("flash", "Beat flash"),
            ParamKey("bassGain", "Bass gain"),
            ParamKey("midGain", "Mid gain"),
            ParamKey("trebGain", "Treble gain"),
            ParamKey("turbulence", "Turbulence"),
            ParamKey("density", "Density"),
            ParamKey("mirror", "Mirror"),
            ParamKey("trails", "Trails (particle scenes)"),
            ParamKey("trailLength", "Trail length"),
            ParamKey("trailZoom", "Trail zoom (echo in/out)"),
            ParamKey("trailWarp", "Trail warp (liquid echo)"),
            // Color
            ParamKey("palette", "Palette"),
            ParamKey("palette2", "Palette 2"),
            ParamKey("paletteMix", "Palette blend"),
            ParamKey("milkdropPaletteTint", "MilkDrop palette tint"),
            ParamKey("colorShift", "Hue shift"),
            ParamKey("hueRange", "Hue range"),
            ParamKey("colorCycle", "Color cycle"),
            ParamKey("cycleSpeed", "Cycle speed"),
            ParamKey("saturation", "Saturation"),
            ParamKey("brightness", "Brightness"),
            ParamKey("contrast", "Contrast"),
            ParamKey("gamma", "Gamma"),
            ParamKey("intensity", "Intensity"),
            ParamKey("temperature", "Temperature"),
            ParamKey("bloom", "Bloom"),
            ParamKey("duotone", "Duotone"),
            ParamKey("solarize", "Solarize"),
            ParamKey("invert", "Invert"),
            // Screen FX
            ParamKey("chromaAb", "Chromatic aberration"),
            ParamKey("vignette", "Vignette"),
            ParamKey("scanlines", "Scanlines"),
            ParamKey("grain", "Film grain"),
            ParamKey("glitch", "Glitch"),
            ParamKey("fisheye", "Fisheye"),
            ParamKey("strobe", "Strobe"),
            // Fluid: solver
            ParamKey("fluidIterations", "Solver iterations"),
            ParamKey("fluidPressure", "Pressure"),
            // Fluid: character
            ParamKey("fluidCurl", "Fluid curl"),
            ParamKey("fluidVelocityDissipation", "Motion fade"),
            ParamKey("fluidDensityDissipation", "Fluid fade"),
            ParamKey("fluidChromaticAging", "Chromatic aging"),
            // Fluid: emitters
            ParamKey("fluidBeatPattern", "Beat pattern"),
            ParamKey("fluidBeatSplats", "Beat splats"),
            ParamKey("fluidStirrers", "Stirrers"),
            ParamKey("fluidStirrerSpeed", "Stirrer speed"),
            ParamKey("fluidSplatRadius", "Fluid splat radius"),
            ParamKey("fluidSplatForce", "Fluid splat force"),
            ParamKey("fluidBassPump", "Bass pump"),
            ParamKey("fluidSparkle", "Treble sparkle"),
            ParamKey("fluidPaletteCycleSpeed", "Palette cycle"),
            // Fluid: journey (shared by FLUID, CURLFLOW and WATER)
            ParamKey("fluidSpawnPath", "Path"),
            ParamKey("fluidSpawnPoints", "Spawn points"),
            ParamKey("fluidCatchPoints", "Catch points"),
            ParamKey("fluidCatchPull", "Catch pull"),
            ParamKey("fluidCatchRadius", "Catch radius"),
            ParamKey("fluidParticleLife", "Particle life (s)"),
            // Fluid: particles & look
            ParamKey("fluidParticleDrag", "Particle drag"),
            ParamKey("fluidParticleBrightness", "Particle brightness"),
            ParamKey("fluidShading", "Shading (embossed ink)"),
            ParamKey("fluidBloom", "Glow (fluid)"),
            ParamKey("fluidBloomIntensity", "Fluid glow"),
            ParamKey("fluidBloomThreshold", "Glow threshold"),
            ParamKey("fluidSunrays", "Sunrays"),
            ParamKey("fluidSunraysWeight", "Sunrays weight"),
            // Fluid: audio routing
            ParamKey("fluidCurlAudio", "Curl from mids"),
            ParamKey("fluidBloomAudio", "Glow from loudness"),
            ParamKey("fluidFadeAudio", "Fade when quiet"),
            ParamKey("fluidRadiusPulse", "Radius on beat"),
            // FlowField (every style; the master toggle stays user-owned)
            ParamKey("flowStrength", "Flow strength"),
            ParamKey("flowForce", "Flow force"),
            ParamKey("flowCurl", "Flow curl"),
            ParamKey("flowAdvectParticles", "Particles ride the field"),
            // Water + the all-styles ripple overlay
            ParamKey("waterWaveSpeed", "Wave speed"),
            ParamKey("waterDamping", "Damping"),
            ParamKey("waterRippleStrength", "Ripple strength"),
            ParamKey("waterDepth", "Depth"),
            ParamKey("waterSpecular", "Specular"),
            ParamKey("waterFlow", "Flow drift"),
            ParamKey("rippleOverlayStrength", "Ripple overlay strength"),
            ParamKey("rippleOverlaySpecular", "Ripple glint"),
        )

    private val byLabel: Map<String, ParamKey> = ALL.associateBy { it.label }
    private val byId: Map<String, ParamKey> = ALL.associateBy { it.id }

    /** The persisted id for a control [label], or null if it is not a known control. */
    fun idForLabel(label: String): String? = byLabel[label]?.id

    /** The display label for a persisted [id], or null if it is not a known id. */
    fun labelForId(id: String): String? = byId[id]?.label

    /**
     * Label set -> persisted id set. Anything unrecognized passes through
     * verbatim rather than being dropped: a lock for a control this build does
     * not know about (an older or newer app version, a removed control) is
     * still the user's data, and silently discarding it on the next save would
     * be a one-way loss.
     */
    fun idsOf(labels: Set<String>): Set<String> = labels.mapTo(mutableSetOf()) { idForLabel(it) ?: it }

    /**
     * Persisted id set -> label set, the inverse of [idsOf]. Also accepts a
     * legacy set that still holds labels, since those pass straight through:
     * no id equals any label, so the two forms can never be confused.
     */
    fun labelsOf(ids: Set<String>): Set<String> = ids.mapTo(mutableSetOf()) { labelForId(it) ?: it }
}
