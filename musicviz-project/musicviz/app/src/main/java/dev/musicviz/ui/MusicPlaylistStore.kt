package dev.musicviz.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A named, ordered music playlist referencing tracks by uri. */
data class MusicPlaylist(
    val name: String,
    val trackUris: List<String> = emptyList(),
)

/**
 * JSON-file persistence for user music playlists (one file per playlist).
 * Track order is the list order; reordering rewrites the file.
 *
 * Every mutator below is a read-modify-write of one whole file, so two things
 * have to hold or a playlist quietly loses its tracks. The write goes through
 * [AtomicWrite] rather than `File.writeText`, which truncates to zero first -
 * process death inside that window used to leave invalid JSON that [list]
 * silently skips. And a file that is present but unreadable is NOT treated as
 * an absent playlist (see [readable]), because the fallback for absent is a
 * fresh empty [MusicPlaylist] and saving that is what turns a damaged file
 * into a one-track one.
 */
class MusicPlaylistStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "music-playlists").apply { mkdirs() }

    init {
        migrateLegacyFileNames()
    }

    /**
     * One-time rename of files saved under the pre-hash sanitizer (see
     * [PresetStore.safeFileName]): [fileOf] resolves through the hashed stem
     * now, so a playlist left under its old stem would be unreachable by
     * every mutator. A taken target keeps the old file in place, unrenamed
     * rather than destroyed.
     */
    private fun migrateLegacyFileNames() {
        dir
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .forEach { f ->
                val name = runCatching { fromJson(f.readText()).name }.getOrNull() ?: return@forEach
                val stem = sanitize(name)
                if (f.nameWithoutExtension == stem) return@forEach
                val target = File(dir, "$stem.json")
                if (!target.exists()) f.renameTo(target)
            }
    }

    fun list(): List<MusicPlaylist> =
        dir
            .listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun save(playlist: MusicPlaylist) {
        AtomicWrite.text(fileOf(playlist.name), toJson(playlist))
    }

    fun delete(name: String) {
        fileOf(name).delete()
    }

    /** Appends a track uri if not already present. */
    fun addTrack(
        name: String,
        uri: String,
    ): MusicPlaylist {
        val current = current(name) ?: return MusicPlaylist(name)
        val updated =
            if (current.trackUris.contains(uri)) current else current.copy(trackUris = current.trackUris + uri)
        save(updated)
        return updated
    }

    /**
     * Renames a playlist, refusing a blank or already-taken name so the file
     * on disk can never be orphaned or overwritten. Returns whether it ran.
     */
    fun rename(
        oldName: String,
        newName: String,
    ): Boolean {
        val current = list().firstOrNull { it.name == oldName } ?: return false
        if (newName.isBlank() || list().any { it.name == newName }) return false
        // The old file is only removed once the new one is whole on disk.
        // Deleting first, or deleting after a write that failed, is the one
        // way this method can destroy a playlist rather than move it.
        if (!AtomicWrite.text(fileOf(newName), toJson(current.copy(name = newName)))) return false
        if (sanitize(oldName) != sanitize(newName)) delete(oldName)
        return true
    }

    /** Moves the track at [from] to [to], clamping to valid bounds. */
    fun move(
        name: String,
        from: Int,
        to: Int,
    ): MusicPlaylist {
        val current = current(name) ?: return MusicPlaylist(name)
        val uris = current.trackUris.toMutableList()
        if (from !in uris.indices) return current
        val target = to.coerceIn(0, uris.size - 1)
        val item = uris.removeAt(from)
        uris.add(target, item)
        val updated = current.copy(trackUris = uris)
        save(updated)
        return updated
    }

    fun removeTrack(
        name: String,
        uri: String,
    ): MusicPlaylist {
        val current = current(name) ?: return MusicPlaylist(name)
        val updated = current.copy(trackUris = current.trackUris.filterNot { it == uri })
        save(updated)
        return updated
    }

    /**
     * The playlist a mutator should start from, or null when it must not
     * write at all.
     *
     * An absent playlist is a real case - "add to playlist" on a name that
     * does not exist yet creates it - so it answers with a fresh empty one.
     * A file that IS there and did not read back is not that case: [list]
     * skips it, so the mutator would compute its update from an empty base
     * and [save] would replace fifty tracks with the one being added. The
     * caller keeps whatever it is already showing instead, and the bytes stay
     * on disk where a later read (or the user) can still recover them.
     */
    private fun current(name: String): MusicPlaylist? {
        list().firstOrNull { it.name == name }?.let { return it }
        return if (readable(fileOf(name))) MusicPlaylist(name) else null
    }

    /** True when [f] holds nothing, or holds a playlist that parses. */
    private fun readable(f: File): Boolean = !f.exists() || runCatching { fromJson(f.readText()) }.isSuccess

    private fun fileOf(name: String): File = File(dir, sanitize(name) + ".json")

    // The shared collision-free scheme: distinct names must never share a file.
    private fun sanitize(name: String): String = PresetStore.safeFileName(name)

    private fun toJson(p: MusicPlaylist): String {
        val arr = JSONArray()
        p.trackUris.forEach { arr.put(it) }
        return JSONObject().put("name", p.name).put("tracks", arr).toString()
    }

    private fun fromJson(text: String): MusicPlaylist {
        val o = JSONObject(text)
        val arr = o.getJSONArray("tracks")
        return MusicPlaylist(
            name = o.getString("name"),
            trackUris = (0 until arr.length()).map { arr.getString(it) },
        )
    }
}
