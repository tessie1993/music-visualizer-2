package dev.musicviz.ui

import android.content.Context
import dev.musicviz.render.scene.SceneParams
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A saved visual configuration: scene, reactivity, the whole [SceneParams]
 * bundle, and - for the styles whose look is not expressible as parameters at
 * all - the source they render.
 *
 * [milkPreset] is the .milk source itself, not a path, for the same reason
 * [customShader] is the GLSL source: a preset has to BE the look. A path is a
 * pointer into one installation's private storage, so a preset carrying one
 * survives neither sharing nor a reinstall, and on the MILKDROP style a preset
 * whose .milk cannot be found renders projectM's idle "M" logo instead of the
 * visual that was saved. Null on every other style (and on MilkDrop presets
 * saved before the source was carried - see `PlayerViewModel.milkPresetPathFor`
 * for how those still resolve).
 */
data class Preset(
    val name: String,
    val sceneId: String,
    val attack: Float,
    val decay: Float,
    val customShader: String? = null,
    val params: SceneParams = SceneParams.DEFAULT,
    val milkPreset: String? = null,
)

/** JSON file persistence for presets in app-private storage. */
class PresetStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "presets").apply { mkdirs() }

    /** Where a MilkDrop preset's paired .milk lives (see [milkFileName]). */
    private val milkDir = File(context.filesDir, "milk")

    init {
        migrateLegacyFileNames()
    }

    /**
     * One-time rename of files saved under the pre-hash sanitizer, which
     * collapsed every disallowed character to '_' ("夜曲" and "月光" both
     * landed on "__.json"): [findFile] resolves names through [safeFileName]
     * now, so a file left under its old stem would be unloadable and
     * undeletable. Idempotent - a file already under its hashed stem is left
     * alone - and never clobbering: a taken target keeps the old file in
     * place, unrenamed rather than destroyed.
     */
    private fun migrateLegacyFileNames() {
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .toList()
            .forEach { f ->
                val name = runCatching { fromJson(f.readText()).name }.getOrNull() ?: return@forEach
                val stem = safeFileName(name)
                if (f.nameWithoutExtension == stem) return@forEach
                val target = File(f.parentFile, "$stem.json")
                if (target.exists() || !f.renameTo(target)) return@forEach
                // The paired .milk moves too: a preset saved before sources
                // were carried resolves its visual through the file that
                // shares its .json's stem (see milkFileName).
                val milk = File(milkDir, f.nameWithoutExtension + ".milk")
                val milkTarget = File(milkDir, "$stem.milk")
                if (milk.isFile && !milkTarget.exists()) milk.renameTo(milkTarget)
            }
    }

    /** Relative folder ("" = root) for each preset name, for the tree UI. */
    fun folderOf(name: String): String {
        val f = findFile(name) ?: return ""
        return f.parentFile
            ?.relativeTo(dir)
            ?.path
            .orEmpty()
    }

    fun folders(): List<String> =
        dir
            .walkTopDown()
            .filter { it.isDirectory && it != dir }
            .map { it.relativeTo(dir).path }
            .sorted()
            .toList()

    fun addFolder(path: String) {
        File(dir, sanitize(path)).mkdirs()
    }

    fun renameFolder(
        from: String,
        to: String,
    ) {
        val src = File(dir, sanitize(from))
        if (src.isDirectory) src.renameTo(File(dir, sanitize(to)))
    }

    fun moveToFolder(
        name: String,
        folder: String,
    ) {
        val f = findFile(name) ?: return
        val destDir = if (folder.isEmpty()) dir else File(dir, sanitize(folder)).apply { mkdirs() }
        f.renameTo(File(destDir, f.name))
    }

    /** The on-disk JSON file for a saved preset, for mirroring/export. */
    fun fileOf(name: String): File? = findFile(name)

    private fun findFile(name: String): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.extension == "json" && it.nameWithoutExtension == safeFileName(name) }

    fun list(): List<Preset> =
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.name }
            .toList()

    fun save(
        preset: Preset,
        folder: String = "",
    ) {
        val destDir = if (folder.isEmpty()) dir else File(dir, sanitize(folder)).apply { mkdirs() }
        val dest = File(destDir, safeFileName(preset.name) + ".json")
        val previous = findFile(preset.name)?.takeIf { it != dest }
        // AtomicWrite, not writeText: a truncating write's kill window sits
        // on the only copy of the preset. The old location (a folder move) is
        // only removed once the new file is whole on disk.
        if (AtomicWrite.text(dest, toJson(preset))) previous?.delete()
    }

    fun delete(name: String) {
        findFile(name)?.delete()
    }

    companion object {
        internal fun toJson(p: Preset): String =
            JSONObject()
                .put("name", p.name)
                .put("sceneId", p.sceneId)
                .put("attack", p.attack.toDouble())
                .put("decay", p.decay.toDouble())
                .put("speed", p.params.speed.toDouble())
                .put("zoom", p.params.zoom.toDouble())
                .put("rotation", p.params.rotation.toDouble())
                .put("endlessZoom", p.params.endlessZoom)
                .put("endlessZoomSpeed", p.params.endlessZoomSpeed.toDouble())
                .put("audioDrive", p.params.audioDrive.toDouble())
                .put("beatResponse", p.params.beatResponse.toDouble())
                .put("turbulence", p.params.turbulence.toDouble())
                .put("density", p.params.density.toDouble())
                .put("trails", p.params.trails)
                .put("trailLength", p.params.trailLength.toDouble())
                .put("trailZoom", p.params.trailZoom.toDouble())
                .put("trailWarp", p.params.trailWarp.toDouble())
                .put("mirror", p.params.mirror)
                .put("palette", p.params.palette)
                .put("colorShift", p.params.colorShift.toDouble())
                .put("hueRange", p.params.hueRange.toDouble())
                .put("saturation", p.params.saturation.toDouble())
                .put("brightness", p.params.brightness.toDouble())
                .put("colorCycle", p.params.colorCycle)
                .put("cycleSpeed", p.params.cycleSpeed.toDouble())
                .put("invert", p.params.invert)
                .put("contrast", p.params.contrast.toDouble())
                .put("gamma", p.params.gamma.toDouble())
                .put("intensity", p.params.intensity.toDouble())
                .put("sway", p.params.sway.toDouble())
                .put("pulse", p.params.pulse.toDouble())
                .put("warp", p.params.warp.toDouble())
                .put("ripple", p.params.ripple.toDouble())
                .put("symmetry", p.params.symmetry)
                .put("kaleidoscope", p.params.kaleidoscope)
                .put("morph", p.params.morph.toDouble())
                .put("pixelate", p.params.pixelate.toDouble())
                .put("posterize", p.params.posterize.toDouble())
                .put("particleShape", p.params.particleShape)
                .put("particleSize", p.params.particleSize.toDouble())
                .put("palette2", p.params.palette2)
                .put("paletteMix", p.params.paletteMix.toDouble())
                .put("paletteBaseOverride", p.params.paletteBaseOverride.toDouble())
                .put("paletteRangeOverride", p.params.paletteRangeOverride.toDouble())
                .put("palette2BaseOverride", p.params.palette2BaseOverride.toDouble())
                .put("palette2RangeOverride", p.params.palette2RangeOverride.toDouble())
                .put("customPaletteId", p.params.customPaletteId)
                .put("customPalette2Id", p.params.customPalette2Id)
                .put("milkdropPaletteTint", p.params.milkdropPaletteTint.toDouble())
                .put("duotone", p.params.duotone)
                .put("bloom", p.params.bloom.toDouble())
                .put("driftX", p.params.driftX.toDouble())
                .put("driftY", p.params.driftY.toDouble())
                .put("shake", p.params.shake.toDouble())
                .put("tile", p.params.tile.toDouble())
                .put("twist", p.params.twist.toDouble())
                .put("temperature", p.params.temperature.toDouble())
                .put("bassGain", p.params.bassGain.toDouble())
                .put("midGain", p.params.midGain.toDouble())
                .put("trebGain", p.params.trebGain.toDouble())
                .put("flash", p.params.flash.toDouble())
                .put("chromaAb", p.params.chromaAb.toDouble())
                .put("vignette", p.params.vignette.toDouble())
                .put("scanlines", p.params.scanlines.toDouble())
                .put("grain", p.params.grain.toDouble())
                .put("glitch", p.params.glitch.toDouble())
                .put("fisheye", p.params.fisheye.toDouble())
                .put("strobe", p.params.strobe.toDouble())
                .put("paramFadeSec", p.params.paramFadeSec.toDouble())
                .put("solarize", p.params.solarize)
                .put("fluidQuality", p.params.fluidQuality)
                .put("fluidAutoQuality", p.params.fluidAutoQuality)
                .put("fluidIterations", p.params.fluidIterations)
                .put("fluidPressure", p.params.fluidPressure.toDouble())
                .put("fluidCurl", p.params.fluidCurl.toDouble())
                .put("fluidVelocityDissipation", p.params.fluidVelocityDissipation.toDouble())
                .put("fluidDensityDissipation", p.params.fluidDensityDissipation.toDouble())
                .put("fluidChromaticAging", p.params.fluidChromaticAging.toDouble())
                .put("fluidSplatRadius", p.params.fluidSplatRadius.toDouble())
                .put("fluidSplatForce", p.params.fluidSplatForce.toDouble())
                .put("fluidBeatPattern", p.params.fluidBeatPattern)
                .put("fluidBeatSplats", p.params.fluidBeatSplats)
                .put("fluidStirrers", p.params.fluidStirrers)
                .put("fluidStirrerSpeed", p.params.fluidStirrerSpeed.toDouble())
                .put("fluidBassPump", p.params.fluidBassPump)
                .put("fluidPaletteCycleSpeed", p.params.fluidPaletteCycleSpeed.toDouble())
                .put("fluidParticlesEnabled", p.params.fluidParticlesEnabled)
                .put("fluidParticleDrag", p.params.fluidParticleDrag.toDouble())
                .put("fluidParticleBrightness", p.params.fluidParticleBrightness.toDouble())
                .put("fluidDyeEnabled", p.params.fluidDyeEnabled)
                .put("fluidShading", p.params.fluidShading)
                .put("fluidBloom", p.params.fluidBloom)
                .put("fluidBloomIntensity", p.params.fluidBloomIntensity.toDouble())
                .put("fluidBloomThreshold", p.params.fluidBloomThreshold.toDouble())
                .put("fluidSunrays", p.params.fluidSunrays)
                .put("fluidSunraysWeight", p.params.fluidSunraysWeight.toDouble())
                .put("fluidCurlAudio", p.params.fluidCurlAudio.toDouble())
                .put("fluidBloomAudio", p.params.fluidBloomAudio.toDouble())
                .put("fluidFadeAudio", p.params.fluidFadeAudio.toDouble())
                .put("fluidRadiusPulse", p.params.fluidRadiusPulse.toDouble())
                .put("fluidSparkle", p.params.fluidSparkle)
                .put("fluidSpawnPath", p.params.fluidSpawnPath)
                .put("fluidSpawnPoints", p.params.fluidSpawnPoints)
                .put("fluidSpawnProgress", p.params.fluidSpawnProgress.toDouble())
                .put("fluidCatchPoints", p.params.fluidCatchPoints)
                .put("fluidCatchPull", p.params.fluidCatchPull.toDouble())
                .put("fluidCatchRadius", p.params.fluidCatchRadius.toDouble())
                .put("fluidParticleLife", p.params.fluidParticleLife.toDouble())
                .put("flowEnabled", p.params.flowEnabled)
                .put("flowStrength", p.params.flowStrength.toDouble())
                .put("flowForce", p.params.flowForce.toDouble())
                .put("flowCurl", p.params.flowCurl.toDouble())
                .put("flowAdvectParticles", p.params.flowAdvectParticles)
                .put("waterWaveSpeed", p.params.waterWaveSpeed.toDouble())
                .put("waterDamping", p.params.waterDamping.toDouble())
                .put("waterRippleStrength", p.params.waterRippleStrength.toDouble())
                .put("waterDepth", p.params.waterDepth.toDouble())
                .put("waterSpecular", p.params.waterSpecular.toDouble())
                .put("waterFlow", p.params.waterFlow.toDouble())
                .put("waterLiquid", p.params.waterLiquid.toDouble())
                .put("waterLiquidFlow", p.params.waterLiquidFlow.toDouble())
                .put("waterLiquidFade", p.params.waterLiquidFade.toDouble())
                .put("paletteLut", p.params.paletteLut)
                .put("beamXy", p.params.beamXy)
                .put("beamWidth", p.params.beamWidth.toDouble())
                .put("beamIntensity", p.params.beamIntensity.toDouble())
                .put("beamTail", p.params.beamTail.toDouble())
                .put("cymaticsGeometry", p.params.cymaticsGeometry)
                .put("cymaticsFundamental", p.params.cymaticsFundamental.toDouble())
                .put("cymaticsModes", p.params.cymaticsModes)
                .put("cymaticsRing", p.params.cymaticsRing.toDouble())
                .put("cymaticsFocus", p.params.cymaticsFocus.toDouble())
                .put("cymaticsScale", p.params.cymaticsScale.toDouble())
                .put("cymaticsFill", p.params.cymaticsFill.toDouble())
                .put("cymaticsLine", p.params.cymaticsLine.toDouble())
                .put("cymaticsGlow", p.params.cymaticsGlow.toDouble())
                .put("cymaticsIridescence", p.params.cymaticsIridescence.toDouble())
                .put("cymaticsCaustic", p.params.cymaticsCaustic.toDouble())
                .put("cymaticsFlow", p.params.cymaticsFlow.toDouble())
                .put("cymaticsSwirl", p.params.cymaticsSwirl.toDouble())
                .put("hyperJourney", p.params.hyperJourney)
                .put("hyperAct", p.params.hyperAct)
                .put("hyperCycleSeconds", p.params.hyperCycleSeconds.toDouble())
                .put("hyperBodies", p.params.hyperBodies.toDouble())
                .put("hyperLifetime", p.params.hyperLifetime.toDouble())
                .put("hyperSpin", p.params.hyperSpin.toDouble())
                .put("hyperOrbit", p.params.hyperOrbit.toDouble())
                .put("hyperSpecies", p.params.hyperSpecies)
                .put("hyperFold", p.params.hyperFold.toDouble())
                .put("hyperDetail", p.params.hyperDetail.toDouble())
                .put("hyperGlow", p.params.hyperGlow.toDouble())
                .put("hyperNeon", p.params.hyperNeon.toDouble())
                .put("hyperField", p.params.hyperField.toDouble())
                .put("hyperHaze", p.params.hyperHaze.toDouble())
                .put("hyperCamera", p.params.hyperCamera.toDouble())
                .put("hyperMirrorFolds", p.params.hyperMirrorFolds)
                .put("hyperTrap", p.params.hyperTrap.toDouble())
                .put("hyperMelt", p.params.hyperMelt.toDouble())
                .put("hyperStain", p.params.hyperStain.toDouble())
                .put("hyperLiquid", p.params.hyperLiquid.toDouble())
                .put("hyperRidges", p.params.hyperRidges.toDouble())
                .put("hyperStir", p.params.hyperStir.toDouble())
                .put("hyperSwirl", p.params.hyperSwirl.toDouble())
                .put("hyperFlowFade", p.params.hyperFlowFade.toDouble())
                .put("rippleOverlayEnabled", p.params.rippleOverlayEnabled)
                .put("rippleOverlayStrength", p.params.rippleOverlayStrength.toDouble())
                .put("rippleOverlaySpecular", p.params.rippleOverlaySpecular.toDouble())
                .apply { if (p.customShader != null) put("customShader", p.customShader) }
                .apply { if (p.milkPreset != null) put("milkPreset", p.milkPreset) }
                .toString(2)

        /** Preset-envelope keys; everything else in the object is a parameter. */
        internal val ENVELOPE_KEYS = setOf("name", "sceneId", "attack", "decay", "customShader", "milkPreset")

        /**
         * The .milk file name a MilkDrop preset's source is materialized under,
         * sanitized exactly like the preset's own .json so the two always sit
         * side by side under the same base name. A preset called "Live / set 1"
         * used to write its .json as "Live _ set 1.json" and its .milk as
         * "Live / set 1.milk" - a path with a directory in it, which silently
         * failed to copy and left the preset with no visual to restore.
         */
        internal fun milkFileName(presetName: String): String = safeFileName(presetName.removeSuffix(".milk")) + ".milk"

        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9-_ ]")

        /** Folder paths only; item files resolve through [safeFileName]. */
        private fun sanitize(name: String): String = name.replace(UNSAFE_CHARS, "_")

        /**
         * Filesystem-safe, collision-free file stem for a user-chosen item
         * name. A name made only of safe characters keeps its exact old stem,
         * so nothing saved by earlier builds moves. Any other name also
         * carries a short stable digest of the raw name: replacement alone
         * collapsed distinct names onto one file ("夜曲" and "月光" both
         * became "__"), so saving one silently destroyed the other. Shared by
         * presets (and their paired .milk), music playlists and palettes so
         * every store migrates the same way.
         */
        internal fun safeFileName(name: String): String {
            val stem = name.replace(UNSAFE_CHARS, "_")
            if (stem == name) return name
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(name.toByteArray(Charsets.UTF_8))
                    .take(4)
                    .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
            return "$stem-$hash"
        }

        /**
         * Scene parameters alone, without the preset envelope.
         *
         * Routed through [toJson] rather than written out a second time
         * because that serializer is the ONE place every [SceneParams] field
         * has to be listed - `PresetRoundtripTest` fails the build if a new
         * field is missing from it. A parallel writer here would be a second
         * list to remember, and the failure mode of forgetting is silent:
         * performance takes and change counts would just quietly ignore the
         * new parameter.
         */
        internal fun paramsToJson(params: SceneParams): JSONObject =
            JSONObject(toJson(Preset("", "", 0f, 0f, null, params)))
                .also { o -> ENVELOPE_KEYS.forEach(o::remove) }

        /** Inverse of [paramsToJson]; unknown/missing keys fall back to the default. */
        internal fun paramsFromJson(o: JSONObject): SceneParams {
            val full = JSONObject(o.toString())
            full.put("name", "")
            full.put("sceneId", "")
            full.put("attack", 0.0)
            full.put("decay", 0.0)
            return fromJson(full.toString()).params
        }

        internal fun fromJson(json: String): Preset {
            val o = JSONObject(json)
            return Preset(
                name = o.getString("name"),
                sceneId = o.getString("sceneId"),
                attack = o.getDouble("attack").toFloat(),
                decay = o.getDouble("decay").toFloat(),
                customShader = if (o.has("customShader")) o.getString("customShader") else null,
                milkPreset = if (o.has("milkPreset")) o.getString("milkPreset") else null,
                params =
                    SceneParams(
                        speed = o.optDouble("speed", 1.0).toFloat(),
                        zoom = o.optDouble("zoom", 1.0).toFloat(),
                        rotation = o.optDouble("rotation", 0.0).toFloat(),
                        endlessZoom = o.optBoolean("endlessZoom", false),
                        endlessZoomSpeed = o.optDouble("endlessZoomSpeed", 0.3).toFloat(),
                        sway = o.optDouble("sway", 0.0).toFloat(),
                        pulse = o.optDouble("pulse", 0.0).toFloat(),
                        driftX = o.optDouble("driftX", 0.0).toFloat(),
                        driftY = o.optDouble("driftY", 0.0).toFloat(),
                        shake = o.optDouble("shake", 0.0).toFloat(),
                        audioDrive = o.optDouble("audioDrive", 1.0).toFloat(),
                        beatResponse = o.optDouble("beatResponse", 1.0).toFloat(),
                        turbulence = o.optDouble("turbulence", 0.0).toFloat(),
                        density = o.optDouble("density", 1.0).toFloat(),
                        trails = o.optBoolean("trails", false),
                        trailLength = o.optDouble("trailLength", 0.5).toFloat(),
                        trailZoom = o.optDouble("trailZoom", 0.0).toFloat(),
                        trailWarp = o.optDouble("trailWarp", 0.0).toFloat(),
                        mirror = o.optBoolean("mirror", false),
                        warp = o.optDouble("warp", 0.0).toFloat(),
                        ripple = o.optDouble("ripple", 0.0).toFloat(),
                        symmetry = o.optInt("symmetry", 0),
                        kaleidoscope = o.optBoolean("kaleidoscope", false),
                        morph = o.optDouble("morph", 0.0).toFloat(),
                        pixelate = o.optDouble("pixelate", 0.0).toFloat(),
                        posterize = o.optDouble("posterize", 0.0).toFloat(),
                        particleShape = o.optInt("particleShape", 0),
                        particleSize = o.optDouble("particleSize", 1.0).toFloat(),
                        tile = o.optDouble("tile", 1.0).toFloat(),
                        twist = o.optDouble("twist", 0.0).toFloat(),
                        palette = o.optInt("palette", 0),
                        palette2 = o.optInt("palette2", 1),
                        paletteMix = o.optDouble("paletteMix", 0.0).toFloat(),
                        // Presets saved before the palette maker carry no
                        // override keys; the UNSET sentinel keeps them on the
                        // built-in PALETTES entry they were tuned with.
                        paletteBaseOverride =
                            o.optDouble("paletteBaseOverride", SceneParams.UNSET_OVERRIDE.toDouble()).toFloat(),
                        paletteRangeOverride =
                            o.optDouble("paletteRangeOverride", SceneParams.UNSET_OVERRIDE.toDouble()).toFloat(),
                        palette2BaseOverride =
                            o.optDouble("palette2BaseOverride", SceneParams.UNSET_OVERRIDE.toDouble()).toFloat(),
                        palette2RangeOverride =
                            o.optDouble("palette2RangeOverride", SceneParams.UNSET_OVERRIDE.toDouble()).toFloat(),
                        customPaletteId = o.optString("customPaletteId", SceneParams.NO_CUSTOM_PALETTE),
                        customPalette2Id = o.optString("customPalette2Id", SceneParams.NO_CUSTOM_PALETTE),
                        // Absent in every preset saved before the MilkDrop
                        // palette tint existed; 0 keeps those looking exactly
                        // as they were authored.
                        milkdropPaletteTint = o.optDouble("milkdropPaletteTint", 0.0).toFloat(),
                        colorShift = o.optDouble("colorShift", 0.0).toFloat(),
                        hueRange = o.optDouble("hueRange", 1.0).toFloat(),
                        saturation = o.optDouble("saturation", 1.0).toFloat(),
                        brightness = o.optDouble("brightness", 1.0).toFloat(),
                        contrast = o.optDouble("contrast", 1.0).toFloat(),
                        gamma = o.optDouble("gamma", 1.0).toFloat(),
                        colorCycle = o.optBoolean("colorCycle", false),
                        cycleSpeed = o.optDouble("cycleSpeed", 0.1).toFloat(),
                        invert = o.optBoolean("invert", false),
                        intensity = o.optDouble("intensity", 1.0).toFloat(),
                        duotone = o.optBoolean("duotone", false),
                        bloom = o.optDouble("bloom", 0.0).toFloat(),
                        temperature = o.optDouble("temperature", 0.0).toFloat(),
                        solarize = o.optBoolean("solarize", false),
                        bassGain = o.optDouble("bassGain", 1.0).toFloat(),
                        midGain = o.optDouble("midGain", 1.0).toFloat(),
                        trebGain = o.optDouble("trebGain", 1.0).toFloat(),
                        flash = o.optDouble("flash", 0.0).toFloat(),
                        chromaAb = o.optDouble("chromaAb", 0.0).toFloat(),
                        vignette = o.optDouble("vignette", 0.0).toFloat(),
                        scanlines = o.optDouble("scanlines", 0.0).toFloat(),
                        grain = o.optDouble("grain", 0.0).toFloat(),
                        glitch = o.optDouble("glitch", 0.0).toFloat(),
                        fisheye = o.optDouble("fisheye", 0.0).toFloat(),
                        strobe = o.optDouble("strobe", 0.0).toFloat(),
                        paramFadeSec = o.optDouble("paramFadeSec", 0.0).toFloat(),
                        fluidQuality = o.optInt("fluidQuality", 2),
                        fluidAutoQuality = o.optBoolean("fluidAutoQuality", true),
                        fluidIterations = o.optInt("fluidIterations", 20),
                        fluidPressure = o.optDouble("fluidPressure", 0.8).toFloat(),
                        fluidCurl = o.optDouble("fluidCurl", 30.0).toFloat(),
                        fluidVelocityDissipation = o.optDouble("fluidVelocityDissipation", 0.2).toFloat(),
                        fluidDensityDissipation = o.optDouble("fluidDensityDissipation", 1.0).toFloat(),
                        fluidChromaticAging = o.optDouble("fluidChromaticAging", 0.3).toFloat(),
                        fluidSplatRadius = o.optDouble("fluidSplatRadius", 0.12).toFloat(),
                        fluidSplatForce = o.optDouble("fluidSplatForce", 1.0).toFloat(),
                        fluidBeatPattern = o.optInt("fluidBeatPattern", 1),
                        fluidBeatSplats = o.optInt("fluidBeatSplats", 3),
                        fluidStirrers = o.optInt("fluidStirrers", 2),
                        fluidStirrerSpeed = o.optDouble("fluidStirrerSpeed", 1.0).toFloat(),
                        fluidBassPump = o.optBoolean("fluidBassPump", false),
                        fluidPaletteCycleSpeed = o.optDouble("fluidPaletteCycleSpeed", 0.5).toFloat(),
                        fluidParticlesEnabled = o.optBoolean("fluidParticlesEnabled", true),
                        fluidParticleDrag = o.optDouble("fluidParticleDrag", 0.5).toFloat(),
                        fluidParticleBrightness = o.optDouble("fluidParticleBrightness", 1.0).toFloat(),
                        fluidDyeEnabled = o.optBoolean("fluidDyeEnabled", true),
                        fluidShading = o.optBoolean("fluidShading", true),
                        fluidBloom = o.optBoolean("fluidBloom", true),
                        fluidBloomIntensity = o.optDouble("fluidBloomIntensity", 0.8).toFloat(),
                        fluidBloomThreshold = o.optDouble("fluidBloomThreshold", 0.6).toFloat(),
                        fluidSunrays = o.optBoolean("fluidSunrays", true),
                        fluidSunraysWeight = o.optDouble("fluidSunraysWeight", 1.0).toFloat(),
                        fluidCurlAudio = o.optDouble("fluidCurlAudio", 0.5).toFloat(),
                        fluidBloomAudio = o.optDouble("fluidBloomAudio", 0.5).toFloat(),
                        fluidFadeAudio = o.optDouble("fluidFadeAudio", 0.6).toFloat(),
                        fluidRadiusPulse = o.optDouble("fluidRadiusPulse", 0.4).toFloat(),
                        fluidSparkle = o.optBoolean("fluidSparkle", true),
                        fluidSpawnPath = o.optInt("fluidSpawnPath", 1),
                        fluidSpawnPoints = o.optInt("fluidSpawnPoints", 3),
                        fluidSpawnProgress = o.optDouble("fluidSpawnProgress", 1.0).toFloat(),
                        // Legacy migration: presets saved before v0.13.0 have
                        // no journey keys - default them to NO catch wells and
                        // a slow lifecycle (close to the old ~12.5 s mean
                        // rebirth) so a tuned pre-rebuild look doesn't gain
                        // suction/churn it was never designed with. Presets
                        // saved from v0.13.0 on always carry explicit values.
                        fluidCatchPoints = o.optInt("fluidCatchPoints", 0),
                        fluidCatchPull = o.optDouble("fluidCatchPull", 1.0).toFloat(),
                        fluidCatchRadius = o.optDouble("fluidCatchRadius", 0.12).toFloat(),
                        fluidParticleLife = o.optDouble("fluidParticleLife", 12.0).toFloat(),
                        flowEnabled = o.optBoolean("flowEnabled", false),
                        flowStrength = o.optDouble("flowStrength", 0.35).toFloat(),
                        flowForce = o.optDouble("flowForce", 1.0).toFloat(),
                        flowCurl = o.optDouble("flowCurl", 25.0).toFloat(),
                        flowAdvectParticles = o.optBoolean("flowAdvectParticles", true),
                        waterWaveSpeed = o.optDouble("waterWaveSpeed", 1.0).toFloat(),
                        waterDamping = o.optDouble("waterDamping", 0.985).toFloat(),
                        waterRippleStrength = o.optDouble("waterRippleStrength", 1.0).toFloat(),
                        waterDepth = o.optDouble("waterDepth", 0.6).toFloat(),
                        waterSpecular = o.optDouble("waterSpecular", 0.7).toFloat(),
                        waterFlow = o.optDouble("waterFlow", 0.3).toFloat(),
                        waterLiquid = o.optDouble("waterLiquid", 0.85).toFloat(),
                        waterLiquidFlow = o.optDouble("waterLiquidFlow", 1.4).toFloat(),
                        waterLiquidFade = o.optDouble("waterLiquidFade", 0.35).toFloat(),
                        paletteLut = o.optInt("paletteLut", SceneParams.NO_PALETTE_LUT),
                        beamXy = o.optBoolean("beamXy", false),
                        beamWidth = o.optDouble("beamWidth", 1.0).toFloat(),
                        beamIntensity = o.optDouble("beamIntensity", 1.0).toFloat(),
                        beamTail = o.optDouble("beamTail", 0.35).toFloat(),
                        cymaticsGeometry = o.optInt("cymaticsGeometry", 0),
                        cymaticsFundamental = o.optDouble("cymaticsFundamental", 110.0).toFloat(),
                        cymaticsModes = o.optInt("cymaticsModes", 5),
                        cymaticsRing = o.optDouble("cymaticsRing", 0.35).toFloat(),
                        cymaticsFocus = o.optDouble("cymaticsFocus", 0.7).toFloat(),
                        cymaticsScale = o.optDouble("cymaticsScale", 3.2).toFloat(),
                        cymaticsFill = o.optDouble("cymaticsFill", 0.45).toFloat(),
                        cymaticsLine = o.optDouble("cymaticsLine", 1.0).toFloat(),
                        cymaticsGlow = o.optDouble("cymaticsGlow", 1.0).toFloat(),
                        cymaticsIridescence = o.optDouble("cymaticsIridescence", 0.5).toFloat(),
                        cymaticsCaustic = o.optDouble("cymaticsCaustic", 0.8).toFloat(),
                        cymaticsFlow = o.optDouble("cymaticsFlow", 0.35).toFloat(),
                        cymaticsSwirl = o.optDouble("cymaticsSwirl", 0.05).toFloat(),
                        hyperJourney = o.optInt("hyperJourney", 0),
                        hyperAct = o.optInt("hyperAct", 2),
                        hyperCycleSeconds = o.optDouble("hyperCycleSeconds", 30.0).toFloat(),
                        hyperBodies = o.optDouble("hyperBodies", 1.0).toFloat(),
                        hyperLifetime = o.optDouble("hyperLifetime", 14.0).toFloat(),
                        hyperSpin = o.optDouble("hyperSpin", 1.0).toFloat(),
                        hyperOrbit = o.optDouble("hyperOrbit", 1.0).toFloat(),
                        hyperSpecies = o.optInt("hyperSpecies", 0),
                        hyperFold = o.optDouble("hyperFold", 0.5).toFloat(),
                        hyperDetail = o.optDouble("hyperDetail", 1.0).toFloat(),
                        hyperGlow = o.optDouble("hyperGlow", 1.0).toFloat(),
                        hyperNeon = o.optDouble("hyperNeon", 1.0).toFloat(),
                        hyperField = o.optDouble("hyperField", 1.0).toFloat(),
                        hyperHaze = o.optDouble("hyperHaze", 0.7).toFloat(),
                        hyperCamera = o.optDouble("hyperCamera", 1.0).toFloat(),
                        hyperMirrorFolds = o.optInt("hyperMirrorFolds", 6),
                        hyperTrap = o.optDouble("hyperTrap", 0.8).toFloat(),
                        hyperMelt = o.optDouble("hyperMelt", 0.55).toFloat(),
                        hyperStain = o.optDouble("hyperStain", 0.5).toFloat(),
                        hyperLiquid = o.optDouble("hyperLiquid", 0.35).toFloat(),
                        hyperRidges = o.optDouble("hyperRidges", 0.5).toFloat(),
                        hyperStir = o.optDouble("hyperStir", 1.0).toFloat(),
                        hyperSwirl = o.optDouble("hyperSwirl", 26.0).toFloat(),
                        hyperFlowFade = o.optDouble("hyperFlowFade", 0.35).toFloat(),
                        rippleOverlayEnabled = o.optBoolean("rippleOverlayEnabled", false),
                        rippleOverlayStrength = o.optDouble("rippleOverlayStrength", 0.4).toFloat(),
                        rippleOverlaySpecular = o.optDouble("rippleOverlaySpecular", 0.3).toFloat(),
                    ),
            )
        }
    }
}
