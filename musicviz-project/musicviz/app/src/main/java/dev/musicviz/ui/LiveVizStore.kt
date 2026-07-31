package dev.musicviz.ui

import android.content.Context
import dev.musicviz.render.scene.SceneParams

/**
 * The live visual customization: whatever the user has dialled in right now,
 * as opposed to a preset they explicitly saved.
 */
data class LiveViz(
    val sceneId: String,
    val attack: Float,
    val decay: Float,
    val params: SceneParams,
)

/**
 * Stores the live state as one preset-shaped JSON blob in its own preferences
 * file.
 *
 * Reusing the preset serializer is deliberate: it already round-trips every
 * [SceneParams] field, which `PresetRoundtripTest` gates, so the live state
 * cannot silently drop a slider the way a hand-written serializer would as
 * fields are added. The stored name is a fixed sentinel — this is not a
 * preset and never appears in the browser.
 *
 * Reading it back through [PresetStore] is why this class exists: it used to
 * be done inline in PlayerViewModel, which was the one place the repository
 * seam was bypassed and the concrete store reached for directly.
 */
class LiveVizStore(
    context: Context,
) : LiveVizRepository {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): LiveViz? {
        val json = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val p = PresetStore.fromJson(json)
            LiveViz(sceneId = p.sceneId, attack = p.attack, decay = p.decay, params = p.params)
        }.getOrNull()
    }

    override fun save(state: LiveViz) {
        val json =
            PresetStore.toJson(
                Preset(SENTINEL_NAME, state.sceneId, state.attack, state.decay, null, state.params),
            )
        prefs.edit().putString(KEY, json).apply()
    }

    private companion object {
        const val PREFS = "musicviz-viz"
        const val KEY = "live_state"
        const val SENTINEL_NAME = "__live__"
    }
}
