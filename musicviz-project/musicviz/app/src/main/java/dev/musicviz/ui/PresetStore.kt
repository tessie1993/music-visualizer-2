package dev.musicviz.ui

import android.content.Context
import dev.musicviz.render.scene.SceneParams
import org.json.JSONObject
import java.io.File

/** A saved visual configuration: scene, reactivity and optional custom shader. */
data class Preset(
    val name: String,
    val sceneId: String,
    val attack: Float,
    val decay: Float,
    val customShader: String? = null,
    val params: SceneParams = SceneParams.DEFAULT,
)

/** JSON file persistence for presets in app-private storage. */
class PresetStore(context: Context) {
    private val dir = File(context.filesDir, "presets").apply { mkdirs() }

    /** Relative folder ("" = root) for each preset name, for the tree UI. */
    fun folderOf(name: String): String {
        val f = findFile(name) ?: return ""
        return f.parentFile?.relativeTo(dir)?.path.orEmpty()
    }

    fun folders(): List<String> =
        dir.walkTopDown()
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
        dir.walkTopDown().firstOrNull { it.isFile && it.extension == "json" && it.nameWithoutExtension == sanitize(name) }

    fun list(): List<Preset> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.name }
            .toList()

    fun save(
        preset: Preset,
        folder: String = "",
    ) {
        val destDir = if (folder.isEmpty()) dir else File(dir, sanitize(folder)).apply { mkdirs() }
        File(destDir, sanitize(preset.name) + ".json").writeText(toJson(preset))
    }

    fun delete(name: String) {
        findFile(name)?.delete()
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9-_ ]"), "_")

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
                .apply { if (p.customShader != null) put("customShader", p.customShader) }
                .toString(2)

        internal fun fromJson(json: String): Preset {
            val o = JSONObject(json)
            return Preset(
                name = o.getString("name"),
                sceneId = o.getString("sceneId"),
                attack = o.getDouble("attack").toFloat(),
                decay = o.getDouble("decay").toFloat(),
                customShader = if (o.has("customShader")) o.getString("customShader") else null,
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
                    ),
            )
        }
    }
}
