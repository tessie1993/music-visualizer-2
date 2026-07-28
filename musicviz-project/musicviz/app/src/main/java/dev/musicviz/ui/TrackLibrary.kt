package dev.musicviz.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An imported audio track and any analysis we've cached for it. [analyzed]
 * distinguishes tracks whose BPM/duration are known (shown in the library)
 * from ones only just added.
 */
data class LibraryTrack(
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
    val bpm: Float = 0f,
    val key: String = "",
    val analyzed: Boolean = false,
)

/**
 * JSON-file persistence for the imported-track library in app-private
 * storage. One file holds the whole list; the set is small (user's own
 * imports) so full rewrites are fine.
 */
class TrackLibrary(
    context: Context,
) {
    private val file = File(context.filesDir, "library.json")

    fun list(): List<LibraryTrack> =
        runCatching {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

    /** Adds tracks not already present (dedup by uri); returns the new list. */
    fun addAll(tracks: List<LibraryTrack>): List<LibraryTrack> {
        val existing = list().associateBy { it.uri }.toMutableMap()
        for (t in tracks) if (!existing.containsKey(t.uri)) existing[t.uri] = t
        val merged = existing.values.sortedBy { it.title.lowercase() }
        write(merged)
        return merged
    }

    /** Records analysis results for a track, creating the entry if needed. */
    fun updateAnalysis(
        uri: String,
        title: String,
        durationMs: Long,
        bpm: Float,
        key: String = "",
    ): List<LibraryTrack> {
        val map = list().associateBy { it.uri }.toMutableMap()
        val prev = map[uri]
        map[uri] =
            LibraryTrack(
                uri = uri,
                title = prev?.title ?: title,
                artist = prev?.artist ?: "",
                durationMs = if (durationMs > 0) durationMs else prev?.durationMs ?: 0L,
                bpm = bpm,
                key = key.ifBlank { prev?.key ?: "" },
                analyzed = true,
            )
        val merged = map.values.sortedBy { it.title.lowercase() }
        write(merged)
        return merged
    }

    /** Overwrites title/artist for an entry (metadata refresh). */
    fun updateMetadata(
        uri: String,
        title: String,
        artist: String,
    ): List<LibraryTrack> {
        val map = list().associateBy { it.uri }.toMutableMap()
        val prev = map[uri] ?: return list()
        map[uri] = prev.copy(title = title, artist = artist)
        val merged = map.values.sortedBy { it.title.lowercase() }
        write(merged)
        return merged
    }

    fun remove(uri: String): List<LibraryTrack> {
        val merged = list().filterNot { it.uri == uri }
        write(merged)
        return merged
    }

    private fun write(tracks: List<LibraryTrack>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(toJson(it)) }
        runCatching { file.writeText(arr.toString()) }
    }

    private fun toJson(t: LibraryTrack): JSONObject =
        JSONObject()
            .put("uri", t.uri)
            .put("title", t.title)
            .put("artist", t.artist)
            .put("durationMs", t.durationMs)
            .put("bpm", t.bpm.toDouble())
            .put("key", t.key)
            .put("analyzed", t.analyzed)

    private fun fromJson(o: JSONObject): LibraryTrack =
        LibraryTrack(
            uri = o.getString("uri"),
            title = o.optString("title", "Track"),
            artist = o.optString("artist", ""),
            durationMs = o.optLong("durationMs", 0L),
            bpm = o.optDouble("bpm", 0.0).toFloat(),
            key = o.optString("key", ""),
            analyzed = o.optBoolean("analyzed", false),
        )
}
