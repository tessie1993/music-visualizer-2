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

    fun list(): List<Preset> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name }
            .orEmpty()

    fun save(preset: Preset) {
        File(dir, sanitize(preset.name) + ".json").writeText(toJson(preset))
    }

    fun delete(name: String) {
        File(dir, sanitize(name) + ".json").delete()
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9-_ ]"), "_")

    private fun toJson(p: Preset): String =
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
            .put("intensity", p.params.intensity.toDouble())
            .apply { if (p.customShader != null) put("customShader", p.customShader) }
            .toString(2)

    private fun fromJson(json: String): Preset {
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
                    audioDrive = o.optDouble("audioDrive", 1.0).toFloat(),
                    beatResponse = o.optDouble("beatResponse", 1.0).toFloat(),
                    turbulence = o.optDouble("turbulence", 0.0).toFloat(),
                    density = o.optDouble("density", 1.0).toFloat(),
                    trails = o.optBoolean("trails", false),
                    trailLength = o.optDouble("trailLength", 0.5).toFloat(),
                    mirror = o.optBoolean("mirror", false),
                    palette = o.optInt("palette", 0),
                    colorShift = o.optDouble("colorShift", 0.0).toFloat(),
                    hueRange = o.optDouble("hueRange", 1.0).toFloat(),
                    saturation = o.optDouble("saturation", 1.0).toFloat(),
                    brightness = o.optDouble("brightness", 1.0).toFloat(),
                    colorCycle = o.optBoolean("colorCycle", false),
                    cycleSpeed = o.optDouble("cycleSpeed", 0.1).toFloat(),
                    invert = o.optBoolean("invert", false),
                    intensity = o.optDouble("intensity", 1.0).toFloat(),
                ),
        )
    }
}
