package dev.geode.data

import android.content.Context
import androidx.annotation.WorkerThread
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
 *
 * Both writers go through [AtomicWrite]. A take is a performance that cannot
 * be repeated - the same reason [save] never overwrites one - and
 * `File.writeText` truncates its target to zero before writing, so the app
 * being killed mid-save left a file that [list] silently skips and [load]
 * silently refuses to replay. [AtomicWrite.TEMP_SUFFIX] keeps the in-progress
 * copy out of that listing: it lands as `Take 3.json.tmp`, whose extension is
 * not `json`.
 */
class TakeStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "takes").apply { mkdirs() }

    init {
        migrateLegacyFileNames()
    }

    /**
     * One-time rename of takes saved under the pre-hash sanitizer, which
     * collapsed every disallowed character to '_' ("夜曲" and "月光" both
     * landed on "__.json"), so [load] and [delete] resolved BOTH names to the
     * first file: [fileOf] goes through [PresetStore.safeFileName] now, and a
     * file left under its old stem would be unloadable and undeletable. The
     * take's real name lives in the document ([list] reads it from there), so
     * the target stem is recomputed from it. Idempotent - a file already under
     * its hashed stem is left alone - and never clobbering: a taken target
     * keeps the old file in place, unrenamed rather than destroyed.
     */
    private fun migrateLegacyFileNames() {
        dir
            .listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .forEach { f ->
                val name = runCatching { PerformanceTake.Timeline(f.readText()).name }.getOrNull() ?: return@forEach
                val stem = PresetStore.safeFileName(name)
                if (f.nameWithoutExtension == stem) return@forEach
                val target = File(dir, "$stem.json")
                if (!target.exists()) f.renameTo(target)
            }
    }

    private fun fileOf(name: String): File = File(dir, PresetStore.safeFileName(name) + ".json")

    /** Saved takes, newest first. Unreadable files are skipped, not fatal. */
    @WorkerThread
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
    @WorkerThread
    fun load(name: String): PerformanceTake.Timeline? =
        runCatching { PerformanceTake.Timeline(fileOf(name).readText()) }
            .onFailure { dev.geode.RingLog.note("TakeStore", "take failed to load: $name", it) }
            .getOrNull()

    /**
     * Writes [json] under [name], returning the name actually used.
     *
     * Collisions get a numeric suffix rather than overwriting: a take is a
     * performance that cannot be repeated, so silently replacing one because
     * two carry the same default name would destroy work with no undo.
     */
    @WorkerThread
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
        // The name in the document is what [list] shows and what [load] and
        // [delete] resolve through, so a suffixed candidate must be carried
        // inside the file too - otherwise the take lists under a name whose
        // file is a different take's.
        val body =
            if (candidate == name) {
                json
            } else {
                runCatching {
                    org.json
                        .JSONObject(json)
                        .put("name", candidate)
                        .toString()
                }.getOrDefault(json)
            }
        AtomicWrite.text(fileOf(candidate), body)
        return candidate
    }

    @WorkerThread
    fun delete(name: String) {
        fileOf(name).delete()
    }

    fun rename(
        from: String,
        to: String,
    ): Boolean {
        // Trimmed HERE, not just in whatever dialog fronts this: the store is
        // the contract. A blank name can never become a take's name, and
        // " Encore " and "Encore" are the same take rather than two files
        // whose names differ only in whitespace the list renders identically.
        val target = to.trim()
        val src = fileOf(from)
        if (!src.isFile || target.isEmpty()) return false
        val dest = fileOf(target)
        if (dest.exists()) return false
        // The name lives in the document as well as in the filename; the list
        // reads the document, so renaming only the file would show the old one.
        val updated =
            runCatching {
                org.json
                    .JSONObject(src.readText())
                    .put("name", target)
                    .toString()
            }.getOrNull() ?: return false
        // The source is only dropped once the destination is whole on disk.
        // A truncated write followed by an unconditional delete is how a
        // rename loses the take instead of moving it.
        if (!AtomicWrite.text(dest, updated)) return false
        src.delete()
        return true
    }
}
