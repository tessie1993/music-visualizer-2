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
 */
class MusicPlaylistStore(context: Context) {
    private val dir = File(context.filesDir, "music-playlists").apply { mkdirs() }

    fun list(): List<MusicPlaylist> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun save(playlist: MusicPlaylist) {
        File(dir, sanitize(playlist.name) + ".json").writeText(toJson(playlist))
    }

    fun delete(name: String) {
        File(dir, sanitize(name) + ".json").delete()
    }

    /** Appends a track uri if not already present. */
    fun addTrack(
        name: String,
        uri: String,
    ): MusicPlaylist {
        val current = list().firstOrNull { it.name == name } ?: MusicPlaylist(name)
        val updated =
            if (current.trackUris.contains(uri)) current else current.copy(trackUris = current.trackUris + uri)
        save(updated)
        return updated
    }

    /** Moves the track at [from] to [to], clamping to valid bounds. */
    fun rename(
        oldName: String,
        newName: String,
    ): Boolean {
        val current = list().firstOrNull { it.name == oldName } ?: return false
        if (newName.isBlank() || list().any { it.name == newName }) return false
        save(current.copy(name = newName))
        delete(oldName)
        return true
    }

    fun move(
        name: String,
        from: Int,
        to: Int,
    ): MusicPlaylist {
        val current = list().firstOrNull { it.name == name } ?: return MusicPlaylist(name)
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
        val current = list().firstOrNull { it.name == name } ?: return MusicPlaylist(name)
        val updated = current.copy(trackUris = current.trackUris.filterNot { it == uri })
        save(updated)
        return updated
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9-_ ]"), "_")

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
