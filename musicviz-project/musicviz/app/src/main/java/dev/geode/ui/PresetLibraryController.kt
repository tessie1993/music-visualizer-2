package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.data.Preset
import dev.geode.data.PresetStore
import dev.geode.render.scene.SceneIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The saved-preset library - folders, save/delete, sharing, imports and the
 * best-effort mirror into the user's chosen folder - extracted from
 * [PlayerViewModel]. The preset list itself stays inside [VizUiState] (the
 * browser renders it with the rest of the visual state), so reads and
 * updates go through [Host]; the disk work runs on the shared store-writer
 * lane so mutations stay ordered and off the main thread.
 */
internal class PresetLibraryController(
    private val application: Application,
    private val scope: CoroutineScope,
    /** The shared store-writer lane; every mutation and re-list rides it. */
    private val storeWriter: java.util.concurrent.ExecutorService,
    private val host: Host,
) {
    /** Where the preset list lives and what a save captures. */
    interface Host {
        val vizState: StateFlow<VizUiState>

        /**
         * Applies [transform] to the current preset list atomically. Carries
         * optimistic inserts/removes, the post-mutation re-list, and the
         * initial fill's "has anything published a list yet" identity guard.
         */
        fun updatePresets(transform: (List<Preset>) -> List<Preset>)

        /** The user's chosen mirror folder (Settings > Paths), or null. */
        val presetMirrorUri: String?

        /** The .milk the engine is showing, captured into a MilkDrop save. */
        val activeMilkPath: String?
    }

    private val store = PresetStore(application)

    // Preset folder tree
    fun presetFolders(): List<String> = store.folders()

    fun presetFolderOf(name: String): String = store.folderOf(name)

    fun addPresetFolder(path: String) = store.addFolder(path)

    fun renamePresetFolder(
        from: String,
        to: String,
    ) = store.renameFolder(from, to)

    fun movePresetToFolder(
        name: String,
        folder: String,
    ) {
        storeWriter.execute {
            store.moveToFolder(name, folder)
            // The mirror tracks every write path, not just savePreset: a move
            // that skipped it left the mirrored copy wherever the preset used to
            // be, drifting from the store it exists to reflect.
            mirrorPresetToChosenFolder(name)
            relistPresets()
        }
    }

    /** User .milk files (imports + saves), newest first. Built-ins removed. */
    fun userMilkPresets(): List<java.io.File> {
        val dir = java.io.File(application.filesDir, "milk")
        return dir
            .listFiles { f -> f.isFile && f.extension == "milk" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /**
     * Fills the preset list once at startup, off the main thread.
     *
     * [PresetStore.list] walks the preset directory and parses every file in
     * it, so a user with a couple of hundred presets was blocking their own
     * first frame on a couple of hundred reads. The built-ins are in the
     * state's initial value, so the browser is populated from the start and
     * the user's own presets join the list a moment later rather than
     * replacing something wrong. The identity guard keeps a listing that
     * began before a mutation from landing on top of it: the untouched
     * built-ins are still the same list instance the state started from,
     * which is exactly the question "has anything published a list yet".
     */
    fun refreshInitial() {
        scope.launch(Dispatchers.IO) {
            val listed = store.list()
            withContext(Dispatchers.Main) {
                host.updatePresets { current ->
                    if (current !== BuiltInPresets.ALL) current else BuiltInPresets.ALL + listed
                }
            }
        }
    }

    /** Re-reads the preset list from disk; runs on [storeWriter] after a mutation. */
    private fun relistPresets() {
        val listed = BuiltInPresets.ALL + store.list()
        host.updatePresets { listed }
    }

    fun savePreset(
        name: String,
        customShader: String?,
        folder: String = "",
    ) {
        // " · " is reserved for built-in presets (isBuiltIn matches on it);
        // a user preset containing it would be undeletable in the browser.
        @Suppress("NAME_SHADOWING")
        val name = name.replace(" · ", " - ").trim().ifEmpty { "Preset" }
        // Captured on the caller so the preset is what the user saw when they
        // pressed Save; everything after is disk - a .milk read, two fsync'd
        // writes, a full re-list - and runs on the store writer, off the main
        // thread, where an fsync on busy flash was a jank/ANR risk.
        val s = host.vizState.value
        val milkPath = host.activeMilkPath
        storeWriter.execute {
            // On the milkdrop scene the parameters are only half the look: the
            // .milk preset paints the picture they post-process. Its SOURCE goes
            // into the preset itself so the saved state is the whole visual - a
            // preset that carries only the params reloads as projectM's idle "M"
            // logo, which is the bug this closes - and a copy is materialized in
            // the user's milk dir so the file is reachable from the MilkDrop tab
            // like any other .milk they loaded.
            val milkSource =
                if (s.sceneId == SceneIds.MILKDROP) {
                    milkPath?.let { src -> runCatching { java.io.File(src).readText() }.getOrNull() }
                } else {
                    null
                }
            milkSource?.let { source -> store.materializeMilk(name, source) }
            store.save(Preset(name, s.sceneId, s.attack, s.decay, customShader, s.params, milkSource), folder)
            mirrorPresetToChosenFolder(name)
            relistPresets()
        }
    }

    /**
     * Mirrors the just-saved preset JSON (and paired .milk on the milkdrop
     * scene) into the user's chosen preset folder (Settings > Paths) so their
     * own file-manager sorting stays in sync. Internal storage remains the
     * working store; mirroring is best-effort.
     */
    private fun mirrorPresetToChosenFolder(name: String) {
        val uriStr = host.presetMirrorUri ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tree =
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(application, Uri.parse(uriStr))
                        ?: return@runCatching

                fun copyInto(
                    src: java.io.File,
                    mime: String,
                ) {
                    if (!src.exists()) return
                    tree.findFile(src.name)?.delete()
                    val dest = tree.createFile(mime, src.name) ?: return
                    application.contentResolver.openOutputStream(dest.uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }
                store.fileOf(name)?.let { copyInto(it, "application/json") }
                // Same sanitized base name the .json got (PresetStore.milkFileName):
                // the raw name was a different file for anything with a slash or
                // a colon in it, so the mirror silently skipped the .milk.
                milkFileFor(name).let { copyInto(it, "text/plain") }
            }
        }
    }

    /**
     * The .milk file a preset named [presetName] owns, whether or not it
     * exists yet. Named through [PresetStore.milkFileName] so a preset's
     * .milk and its .json always share one sanitized base name.
     */
    private fun milkFileFor(presetName: String): java.io.File =
        java.io.File(
            java.io.File(application.filesDir, "milk").apply { mkdirs() },
            PresetStore.milkFileName(presetName),
        )

    /**
     * The .milk file [preset] should render, materializing its carried source
     * on the way, or null when it has none. The two-era resolution (and the
     * atomic write under the engine's feet) lives in
     * [PresetStore.materializeMilk]; this only adds the scene gate.
     */
    fun milkPresetPathFor(preset: Preset): String? {
        if (preset.sceneId != SceneIds.MILKDROP) return null
        return store.materializeMilk(preset.name, preset.milkPreset)
    }

    /**
     * A shareable link for [name], or null when it is too long to survive a
     * chat app (a preset carrying a custom shader) - the caller then offers
     * the file instead.
     */
    fun presetShareLink(name: String): String? {
        val preset = host.vizState.value.presets.firstOrNull { it.name == name } ?: return null
        val link = PresetLink.encode(PresetStore.toJson(preset))
        return link.takeIf { it.length <= PresetLink.MAX_LINK_LENGTH }
    }

    /**
     * Imports a preset from a link (or from text containing one). Returns the
     * name it was saved under, or null when the text holds no readable preset.
     *
     * Imported under its own name with a numeric suffix on collision, like a
     * take: overwriting a preset the user built because a stranger's happens
     * to share its name would be destroying work to save a rename.
     */
    fun importPresetLink(text: String): String? {
        val link = PresetLink.findIn(text) ?: return null
        return importPresetJson(PresetLink.decode(link) ?: return null)
    }

    /**
     * Imports a preset from a picked `.json` file - the other half of sharing.
     *
     * A preset too long to survive a chat message goes out as its file
     * instead ([presetFile]), and MilkDrop presets always do now that they
     * carry their .milk source. Without a way back IN, that branch of Share
     * produced a file the receiving app could do nothing with.
     */
    fun importPresetFile(
        uri: Uri,
        onResult: (String?) -> Unit,
    ) {
        scope.launch {
            // A SAF read can block on the provider (a cloud file is fetched on
            // demand), so it happens off the main thread; the decode-and-save
            // tail then runs back here, where the state lives.
            val json =
                withContext(Dispatchers.IO) {
                    runCatching {
                        application
                            .contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                    }.getOrNull()
                }
            onResult(json?.let { importPresetJson(it) })
        }
    }

    /** Saves an incoming preset document; the shared tail of both imports. */
    private fun importPresetJson(json: String): String? {
        val incoming = runCatching { PresetStore.fromJson(json) }.getOrNull() ?: return null
        val existing = host.vizState.value.presets.map { it.name }.toSet()
        // The same laundering savePreset applies: " · " is reserved for
        // built-in presets (isBuiltIn matches on it), so an imported name
        // carrying it was classified built-in - hidden from the user's list
        // and undeletable in the browser.
        val base =
            incoming.name
                .replace(" · ", " - ")
                .trim()
                .ifBlank { "Shared preset" }
        var name = base
        var n = 2
        while (name in existing) {
            name = "$base $n"
            n++
        }
        val preset = incoming.copy(name = name)
        // Into the state right away - back-to-back imports must see each other
        // for the numeric suffix to hold - and onto the disk via the writer,
        // off the caller's thread: this runs from onCreate on a deep link,
        // where the fsync'd save plus a full re-list was an ANR risk at the
        // worst possible moment, app launch.
        host.updatePresets { it + preset }
        storeWriter.execute {
            store.save(preset)
            relistPresets()
        }
        return name
    }

    /** On-disk file for a preset, for sharing one too big to be a link. */
    fun presetFile(name: String): java.io.File? = store.fileOf(name)

    fun deletePreset(name: String) {
        if (BuiltInPresets.isBuiltIn(name)) return
        // Gone from the list immediately; the disk catches up on the writer.
        host.updatePresets { presets -> presets.filterNot { it.name == name } }
        storeWriter.execute {
            // The mirror follows the store both ways. Save-only sync meant every
            // deleted preset lived on in the user's chosen folder, so the mirror
            // slowly became a directory of ghosts. File names are captured BEFORE
            // the delete - fileOf resolves through the disk.
            removeMirroredPreset(store.fileOf(name)?.name, milkFileFor(name).name)
            store.delete(name)
            relistPresets()
        }
    }

    /** Best-effort removal of a deleted preset's mirrored files (see [mirrorPresetToChosenFolder]). */
    private fun removeMirroredPreset(
        jsonName: String?,
        milkName: String?,
    ) {
        val uriStr = host.presetMirrorUri ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tree =
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(application, Uri.parse(uriStr))
                        ?: return@runCatching
                jsonName?.let { tree.findFile(it)?.delete() }
                milkName?.let { tree.findFile(it)?.delete() }
            }
        }
    }
}
