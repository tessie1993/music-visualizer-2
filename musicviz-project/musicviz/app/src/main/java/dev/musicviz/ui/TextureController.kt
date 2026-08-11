package dev.musicviz.ui

import android.app.Application
import android.net.Uri
import dev.musicviz.data.MilkTexture
import dev.musicviz.data.TextureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shared milkdrop texture folder - list, import, remove, use - extracted
 * from [PlayerViewModel]. All of it is disk work and runs on IO; the one
 * coupling back into the ViewModel (a removed texture's generated display
 * preset may be the .milk the engine is showing) goes through [Host].
 */
internal class TextureController(
    application: Application,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** The milk-selection coherence hook (see [removeTexture]). */
    interface Host {
        /** Generated display presets [paths] were deleted; drop any selection pointing at them. */
        fun onGeneratedPresetsRemoved(paths: List<String>)
    }

    private val store = TextureStore(application)

    /** Filled by [refresh]; only the milkdrop texture picker reads it. */
    private val _textures = MutableStateFlow<List<MilkTexture>>(emptyList())
    val textures: StateFlow<List<MilkTexture>> = _textures

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val listed = store.list()
            // Same one-shot rule as the library: an import or a removal
            // publishes its own list and this one may predate it.
            withContext(Dispatchers.Main) { if (_textures.value.isEmpty()) _textures.value = listed }
        }
    }

    /**
     * Imports images into the shared milkdrop texture folder. [onImported] is
     * invoked so the caller can reload the current preset and have projectM
     * pick the new textures up.
     */
    fun importTextures(
        uris: List<Uri>,
        onImported: () -> Unit,
    ) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val updated = store.import(uris)
            withContext(Dispatchers.Main) {
                _textures.value = updated
                onImported()
            }
        }
    }

    /**
     * Deletes a texture off the main thread (it is disk work, same as
     * [importTextures]) and keeps the milk selection coherent: removing a
     * texture also removes the generated display preset(s) written for it,
     * and when one of THOSE is the preset the engine is showing, the persisted
     * `milk_path` would point at a dead file on the next launch - so the host
     * clears it and the engine simply keeps its currently loaded frame instead
     * of being offered a preset that no longer exists.
     */
    fun removeTexture(name: String) {
        scope.launch(Dispatchers.IO) {
            val outcome = store.removeDetailed(name)
            withContext(Dispatchers.Main) {
                _textures.value = outcome.textures
                val gone = outcome.removedGeneratedPresetPaths
                if (gone.isNotEmpty()) host.onGeneratedPresetsRemoved(gone)
            }
        }
    }

    /** Generates a display preset for [name] and hands its path to the caller. */
    fun useTexture(
        name: String,
        onReady: (String) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            val path = runCatching { store.generateDisplayPreset(name) }.getOrNull()
            withContext(Dispatchers.Main) { path?.let(onReady) }
        }
    }
}
