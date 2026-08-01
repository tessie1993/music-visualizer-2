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

    // PresetStore's own splitter, not a second copy of the envelope key list:
    // a key added to the envelope (the .milk source was the last one) has to
    // stop counting as a changed parameter everywhere at once.
    private fun paramsJson(params: SceneParams): JSONObject = PresetStore.paramsToJson(params)
}
