package dev.musicviz.ui

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The user's `.milk` preset files (imports plus milkdrop presets saved from
 * the app), living in `filesDir/milk` next to the shared texture directory
 * [TextureStore] manages.
 *
 * Every method is blocking file I/O — call from a background dispatcher. The
 * coroutine scoping stays with the caller on purpose, so this stays a plain
 * store with no lifecycle of its own.
 *
 * Bundled `.milk` presets were removed in a later version (they were low
 * quality); [listPresets] cleans up copies left by older installs so they stop
 * appearing in the browser.
 */
class MilkAssetStore(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Where user `.milk` files live. Not created on read paths. */
    fun importDir(): File = File(appContext.filesDir, "milk")

    private fun builtInDir(): File = File(appContext.filesDir, "milk-builtin")

    /** The on-disk path a preset named [name] saves its `.milk` file to. */
    fun presetFile(name: String): File = File(importDir(), if (name.endsWith(".milk")) name else "$name.milk")

    private var cursor = -1

    /** Next `.milk` file in name order, cycling; null when there are none. */
    fun nextPreset(): String? =
        try {
            val files =
                importDir()
                    .listFiles { f -> f.extension == "milk" }
                    .orEmpty()
                    .sortedBy { it.name }
            if (files.isEmpty()) {
                null
            } else {
                cursor = (cursor + 1) % files.size
                files[cursor].absolutePath
            }
        } catch (t: Throwable) {
            null
        }

    /** All `.milk` files for the browser, name-sorted. */
    fun listPresets(): List<MilkFile> =
        try {
            builtInDir().deleteRecursively()
            File(importDir(), "textures").mkdirs()
            importDir()
                .listFiles { f -> f.extension == "milk" }
                .orEmpty()
                .map { MilkFile(it.nameWithoutExtension, it.absolutePath) }
                .sortedBy { it.name }
        } catch (t: Throwable) {
            emptyList()
        }

    /** Copies a user-picked `.milk` into the store; returns its path, or null. */
    fun importPreset(uri: Uri): String? =
        try {
            importDir().mkdirs()
            val name = (uri.lastPathSegment ?: "preset").substringAfterLast('/').ifBlank { "preset" }
            val file = presetFile(name)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            file.absolutePath
        } catch (t: Throwable) {
            null
        }

    /** User `.milk` files, newest first. */
    fun userPresets(): List<File> =
        importDir()
            .listFiles { f -> f.isFile && f.extension == "milk" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /**
     * Saves the `.milk` currently showing under a preset's name, so a saved
     * milkdrop preset is a real MilkDrop file the user can reload or share and
     * not just a Customize bundle. Best-effort: a failure here must not lose
     * the preset itself.
     */
    fun savePresetCopy(
        sourcePath: String,
        name: String,
    ) {
        runCatching {
            importDir().mkdirs()
            File(sourcePath).copyTo(presetFile(name), overwrite = true)
        }
    }
}
