package dev.musicviz.ui

import dev.musicviz.render.scene.SceneParams
import org.json.JSONObject

/**
 * "How far from the defaults is the current look?" - the number the Customize
 * toolbar shows next to Reset.
 *
 * Counted through [PresetStore]'s JSON rather than field by field on purpose.
 * That serializer is already the one place every [SceneParams] field has to be
 * listed (`PresetRoundtripTest` fails the build if a new field is missing from
 * it), so counting there means a parameter added later is included here with
 * no second list to remember - and no `kotlin-reflect`, which this module only
 * has on the test classpath.
 */
object CustomizeSummary {
    /** Preset envelope keys that are not scene parameters. */
    private val ENVELOPE = setOf("name", "sceneId", "attack", "decay", "customShader")

    private val defaults: JSONObject by lazy { paramsJson(SceneParams.DEFAULT) }

    /** Number of scene parameters in [params] that differ from the default. */
    fun changedCount(params: SceneParams): Int {
        if (params == SceneParams.DEFAULT) return 0
        val current = paramsJson(params)
        var changed = 0
        for (key in current.keys()) {
            // opt(): a key the defaults object lacks counts as changed rather
            // than throwing, so a half-finished serializer degrades to an
            // over-count instead of taking the panel down.
            if (current.opt(key) != defaults.opt(key)) changed++
        }
        return changed
    }

    private fun paramsJson(params: SceneParams): JSONObject {
        val all = JSONObject(PresetStore.toJson(Preset("", "", 0f, 0f, null, params)))
        for (key in ENVELOPE) all.remove(key)
        return all
    }
}
