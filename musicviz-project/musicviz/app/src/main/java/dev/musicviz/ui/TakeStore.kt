package dev.musicviz.ui

import android.content.Context
import java.io.File

/** A saved performance take, as the Takes list shows it. */
data class TakeInfo(
    val name: String,
    val durationMs: Long,
    val eventCount: Int,
    val trackUri: String?,
    val sizeBytes: Long,
)

/**
 * JSON-file persistence for performance takes, alongside [PresetStore]'s
 * presets. One file per take in app-private storage; takes are small (tens of
 * kilobytes) and re-read whole, so there is nothing to gain from a database.
 */
class TakeStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "takes").apply { mkdirs() }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9-_ ]"), "_")

    private fun fileOf(name: String): File = File(dir, sanitize(name) + ".json")

    /** Saved takes, newest first. Unreadable files are skipped, not fatal. */
    fun list(): List<TakeInfo> =
        dir
            .listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching {
                    val t = PerformanceTake.Timeline(f.readText())
                    TakeInfo(t.name, t.durationMs, t.eventCount, t.trackUri, f.length())
                }.getOrNull()
            }

    /** Reads a take back for replay, or null when it is gone or corrupt. */
    fun load(name: String): PerformanceTake.Timeline? = runCatching { PerformanceTake.Timeline(fileOf(name).readText()) }.getOrNull()

    /**
     * Writes [json] under [name], returning the name actually used.
     *
     * Collisions get a numeric suffix rather than overwriting: a take is a
     * performance that cannot be repeated, so silently replacing one because
     * two carry the same default name would destroy work with no undo.
     */
    fun save(
        name: String,
        json: String,
    ): String {
        var candidate = name
        var n = 2
        while (fileOf(candidate).exists()) {
            candidate = "$name $n"
            n++
        }
        fileOf(candidate).writeText(json)
        return candidate
    }

    fun delete(name: String) {
        fileOf(name).delete()
    }

    fun rename(
        from: String,
        to: String,
    ): Boolean {
        val src = fileOf(from)
        if (!src.isFile || to.isBlank()) return false
        val dest = fileOf(to)
        if (dest.exists()) return false
        // The name lives in the document as well as in the filename; the list
        // reads the document, so renaming only the file would show the old one.
        val updated =
            runCatching {
                org.json
                    .JSONObject(src.readText())
                    .put("name", to)
                    .toString()
            }.getOrNull() ?: return false
        dest.writeText(updated)
        src.delete()
        return true
    }
}
