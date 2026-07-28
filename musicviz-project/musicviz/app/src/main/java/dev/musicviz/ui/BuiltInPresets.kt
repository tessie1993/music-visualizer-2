package dev.musicviz.ui

import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.scene.SceneParams

/**
 * Bundled presets: a set of strongly-differentiated Customize bundles applied
 * to every scene, so each visual style ships with ready-made looks that each
 * exercise a distinct combination of motion, shape, color and screen-FX
 * parameters. They appear next to the user's saved presets and can be applied,
 * added to the visual playlist, and picked by Random mode. Names use the
 * "scene · Look" pattern; the separator never appears in user preset
 * filenames, so they can't clash.
 */
object BuiltInPresets {
    private data class Look(
        val name: String,
        val attack: Float,
        val decay: Float,
        val params: SceneParams,
    )

    private val LOOKS: List<Look> =
        listOf(
            Look(
                "Chill",
                0.4f,
                0.06f,
                SceneParams(
                    speed = 0.5f,
                    audioDrive = 0.85f,
                    beatResponse = 0.45f,
                    trails = true,
                    trailLength = 0.8f,
                    saturation = 0.8f,
                    brightness = 0.9f,
                    colorCycle = true,
                    cycleSpeed = 0.025f,
                    palette = 3,
                    sway = 0.35f,
                    vignette = 0.4f,
                    paramFadeSec = 1.5f,
                ),
            ),
            Look(
                "Punchy",
                0.85f,
                0.24f,
                SceneParams(
                    speed = 1.4f,
                    audioDrive = 1.6f,
                    beatResponse = 1.9f,
                    pulse = 0.6f,
                    flash = 0.4f,
                    bloom = 0.5f,
                    contrast = 1.2f,
                    intensity = 1.15f,
                    palette = 1,
                    bassGain = 1.4f,
                ),
            ),
            Look(
                "Hypno",
                0.5f,
                0.1f,
                SceneParams(
                    endlessZoom = true,
                    endlessZoomSpeed = 0.35f,
                    kaleidoscope = true,
                    symmetry = 6,
                    colorCycle = true,
                    cycleSpeed = 0.07f,
                    turbulence = 0.35f,
                    palette = 7,
                    warp = 0.3f,
                ),
            ),
            Look(
                "Vivid",
                0.6f,
                0.12f,
                SceneParams(
                    saturation = 1.35f,
                    intensity = 1.2f,
                    bloom = 0.4f,
                    palette = 5,
                    palette2 = 10,
                    paletteMix = 0.5f,
                    hueRange = 1.25f,
                    warp = 0.25f,
                    chromaAb = 0.3f,
                ),
            ),
            Look(
                "Retro",
                0.55f,
                0.14f,
                SceneParams(
                    speed = 0.9f,
                    palette = 2,
                    scanlines = 0.6f,
                    grain = 0.35f,
                    vignette = 0.5f,
                    posterize = 0.4f,
                    contrast = 1.1f,
                    saturation = 1.1f,
                ),
            ),
            Look(
                "Glitch",
                0.9f,
                0.3f,
                SceneParams(
                    speed = 1.2f,
                    audioDrive = 1.4f,
                    beatResponse = 1.6f,
                    glitch = 0.5f,
                    chromaAb = 0.5f,
                    shake = 0.4f,
                    palette = 8,
                    intensity = 1.1f,
                    trebGain = 1.5f,
                ),
            ),
            Look(
                "Dream",
                0.35f,
                0.05f,
                SceneParams(
                    speed = 0.45f,
                    trails = true,
                    trailLength = 0.85f,
                    bloom = 0.55f,
                    fisheye = 0.4f,
                    saturation = 0.9f,
                    brightness = 1.05f,
                    palette = 9,
                    paletteMix = 0.35f,
                    palette2 = 3,
                    driftX = 0.15f,
                    sway = 0.4f,
                ),
            ),
            Look(
                "Warp",
                0.6f,
                0.16f,
                SceneParams(
                    speed = 1.1f,
                    zoom = 1.1f,
                    twist = 0.5f,
                    warp = 0.5f,
                    morph = 0.4f,
                    turbulence = 0.6f,
                    palette = 4,
                    beatResponse = 1.3f,
                    pulse = 0.4f,
                ),
            ),
            Look(
                "Prism",
                0.5f,
                0.12f,
                SceneParams(
                    kaleidoscope = true,
                    symmetry = 8,
                    palette = 5,
                    palette2 = 7,
                    paletteMix = 0.6f,
                    saturation = 1.2f,
                    bloom = 0.35f,
                    rotation = 0.2f,
                    tile = 2f,
                ),
            ),
            Look(
                "Noir",
                0.55f,
                0.1f,
                SceneParams(
                    palette = 4,
                    saturation = 0.15f,
                    contrast = 1.35f,
                    vignette = 0.6f,
                    grain = 0.4f,
                    duotone = true,
                    brightness = 1.0f,
                ),
            ),
            Look(
                "Strobe",
                0.95f,
                0.35f,
                SceneParams(
                    speed = 1.5f,
                    audioDrive = 1.7f,
                    beatResponse = 2.0f,
                    strobe = 0.5f,
                    flash = 0.6f,
                    bloom = 0.45f,
                    palette = 1,
                    intensity = 1.2f,
                    bassGain = 1.5f,
                ),
            ),
            Look(
                "Deep",
                0.4f,
                0.07f,
                SceneParams(
                    endlessZoom = true,
                    endlessZoomSpeed = 0.5f,
                    zoom = 1.05f,
                    palette = 9,
                    saturation = 1.0f,
                    bloom = 0.4f,
                    vignette = 0.45f,
                    trails = true,
                    trailLength = 0.6f,
                ),
            ),
        )

    /**
     * The six fluid launch variants: strongly differentiated starting points
     * for the FLUID scene, each leaning on a different part of the system
     * (emitter pattern, particle layer, chromatic aging, look chain).
     */
    private val FLUID_VARIANTS: List<Preset> =
        listOf(
            Preset(
                name = "fluid · Inkdrop",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.35f,
                decay = 0.5f,
                customShader = null,
                params =
                    SceneParams(
                        fluidBeatPattern = 0,
                        fluidBeatSplats = 2,
                        fluidStirrers = 1,
                        fluidStirrerSpeed = 0.5f,
                        fluidCurl = 20f,
                        fluidDensityDissipation = 0.35f,
                        fluidChromaticAging = 0.45f,
                        fluidSplatRadius = 0.16f,
                        fluidBloomIntensity = 0.5f,
                        fluidParticlesEnabled = false,
                        palette = 5,
                        // Slow single-spawn drift: ink wells up from one
                        // wandering source; no catch wells.
                        fluidSpawnPath = 4,
                        fluidSpawnPoints = 1,
                        fluidCatchPoints = 0,
                    ),
            ),
            Preset(
                name = "fluid · Vortex",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.5f,
                decay = 0.4f,
                customShader = null,
                params =
                    SceneParams(
                        fluidBeatPattern = 1,
                        fluidBeatSplats = 4,
                        fluidStirrers = 2,
                        fluidStirrerSpeed = 1.4f,
                        fluidCurl = 45f,
                        fluidChromaticAging = 0.5f,
                        fluidParticlesEnabled = true,
                        fluidParticleDrag = 0.35f,
                        palette = 2,
                        // Orbiting spawns with a strong central drain.
                        fluidSpawnPath = 0,
                        fluidSpawnPoints = 3,
                        fluidCatchPoints = 1,
                        fluidCatchPull = 1.6f,
                        fluidCatchRadius = 0.1f,
                    ),
            ),
            Preset(
                name = "fluid · Spectrum",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.6f,
                decay = 0.35f,
                customShader = null,
                params =
                    SceneParams(
                        fluidBeatPattern = 3,
                        fluidBeatSplats = 6,
                        fluidStirrers = 0,
                        fluidBassPump = true,
                        fluidSunrays = true,
                        fluidSunraysWeight = 1.2f,
                        fluidFadeAudio = 1.0f,
                        fluidPaletteCycleSpeed = 1.2f,
                        colorCycle = true,
                        cycleSpeed = 0.05f,
                        palette = 3,
                        fluidSpawnPath = 1,
                        fluidSpawnPoints = 4,
                        fluidCatchPoints = 0,
                    ),
            ),
            Preset(
                name = "fluid · Nebula",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.4f,
                decay = 0.55f,
                customShader = null,
                params =
                    SceneParams(
                        fluidDyeEnabled = false,
                        fluidParticlesEnabled = true,
                        fluidParticleDrag = 0.6f,
                        fluidParticleBrightness = 1.6f,
                        fluidCurl = 40f,
                        fluidStirrers = 3,
                        fluidStirrerSpeed = 0.8f,
                        fluidBloomIntensity = 1.1f,
                        palette = 7,
                        // Pure-particle scene: golden-angle bloom births with
                        // two gravity wells the streams fall into.
                        fluidSpawnPath = 3,
                        fluidSpawnPoints = 5,
                        fluidParticleLife = 9f,
                        fluidCatchPoints = 2,
                        fluidCatchPull = 1.2f,
                        fluidCatchRadius = 0.14f,
                    ),
            ),
            Preset(
                name = "fluid · Lava",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.25f,
                decay = 0.7f,
                customShader = null,
                params =
                    SceneParams(
                        fluidChromaticAging = 1.0f,
                        fluidCurl = 15f,
                        fluidVelocityDissipation = 0.6f,
                        fluidDensityDissipation = 0.25f,
                        fluidSplatRadius = 0.22f,
                        fluidSplatForce = 0.7f,
                        fluidBloomIntensity = 1.2f,
                        fluidBloomThreshold = 0.4f,
                        fluidStirrerSpeed = 0.4f,
                        palette = 1,
                        fluidSpawnPath = 2,
                        fluidSpawnPoints = 2,
                        fluidCatchPoints = 0,
                        fluidSpawnProgress = 0.6f,
                    ),
            ),
            Preset(
                name = "fluid · Storm",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.7f,
                decay = 0.25f,
                customShader = null,
                params =
                    SceneParams(
                        fluidBeatPattern = 2,
                        fluidBeatSplats = 5,
                        fluidStirrers = 4,
                        fluidStirrerSpeed = 1.8f,
                        fluidCurl = 50f,
                        fluidBassPump = true,
                        fluidParticlesEnabled = true,
                        fluidFadeAudio = 0.15f,
                        fluidSunrays = true,
                        palette = 6,
                        fluidSpawnPath = 4,
                        fluidSpawnPoints = 6,
                        fluidParticleLife = 3.5f,
                        fluidCatchPoints = 3,
                        fluidCatchPull = 2.2f,
                        fluidCatchRadius = 0.08f,
                    ),
            ),
            Preset(
                name = "fluid · Journey",
                sceneId = dev.musicviz.render.scene.SceneIds.FLUID,
                attack = 0.45f,
                decay = 0.45f,
                customShader = null,
                params =
                    SceneParams(
                        // The progression showcase: everything rides the
                        // track - spawns weave a rose that blooms outward,
                        // catches spiral in for the finale drain.
                        fluidSpawnPath = 2,
                        fluidSpawnPoints = 4,
                        fluidSpawnProgress = 1f,
                        fluidCatchPoints = 2,
                        fluidCatchPull = 1.4f,
                        fluidCatchRadius = 0.12f,
                        fluidParticleLife = 7f,
                        fluidBeatPattern = 1,
                        fluidBeatSplats = 3,
                        fluidStirrers = 2,
                        fluidChromaticAging = 0.4f,
                        fluidCurl = 35f,
                        colorCycle = true,
                        cycleSpeed = 0.03f,
                        palette = 16,
                    ),
            ),
            Preset(
                name = "curlflow · Streams",
                sceneId = dev.musicviz.render.scene.SceneIds.CURLFLOW,
                attack = 0.4f,
                decay = 0.5f,
                customShader = null,
                params =
                    SceneParams(
                        // Curl-noise streams born at lissajous spawns, drawn
                        // into two drifting wells; trails carry the motion.
                        fluidSpawnPath = 1,
                        fluidSpawnPoints = 3,
                        fluidCatchPoints = 2,
                        fluidCatchPull = 1.2f,
                        fluidCatchRadius = 0.12f,
                        fluidParticleLife = 8f,
                        fluidParticleDrag = 0.4f,
                        turbulence = 0.7f,
                        audioDrive = 1.2f,
                        particleSize = 1.4f,
                        trails = true,
                        trailLength = 0.92f,
                        palette = 10,
                    ),
            ),
        )

    val ALL: List<Preset> =
        (VisualizerRenderer.PARTICLE_SCENES + VisualizerRenderer.SHADER_SCENES.keys).flatMap { id ->
            LOOKS.map { look ->
                Preset(
                    name = "$id · ${look.name}",
                    sceneId = id,
                    attack = look.attack,
                    decay = look.decay,
                    customShader = null,
                    params = look.params,
                )
            }
        } + FLUID_VARIANTS

    fun isBuiltIn(name: String): Boolean = name.contains(" · ")
}
