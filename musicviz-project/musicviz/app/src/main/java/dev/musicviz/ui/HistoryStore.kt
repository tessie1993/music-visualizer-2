package dev.musicviz.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight play-history persistence for the Home screen: last-played
 * ordering, play counts, and the resume queue. JSON in files dir - a Room
 * database is overkill until analysis data moves in (see FEATURES_TODO).
 */
class HistoryStore(
    context: Context,
) : HistoryRepository {
    private val file = java.io.File(context.filesDir, "history.json")

    /** uri -> (lastPlayedMs, playCount, title) */
    private val entries = LinkedHashMap<String, Entry>()

    data class Entry(
        val uri: String,
        var lastPlayedMs: Long,
        var playCount: Int,
        var title: String,
    )

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = Entry(o.getString("uri"), o.getLong("last"), o.getInt("count"), o.optString("title"))
                    entries[e.uri] = e
                }
            }
        }
    }

    override fun recordPlay(
        uri: String,
        title: String,
    ) {
        val e = entries.getOrPut(uri) { Entry(uri, 0L, 0, title) }
        // Strictly increasing, not just "now". The wall clock has millisecond
        // resolution and two plays can land inside one tick - skipping through
        // a queue, or a test - which left "recently played" ordering two
        // entries by whatever order the map happened to hold them in. Nudging
        // past the newest stamp keeps the order the user actually created.
        e.lastPlayedMs = maxOf(System.currentTimeMillis(), newestStamp() + 1)
        e.playCount++
        e.title = title
        persist()
    }

    private fun newestStamp(): Long = entries.values.maxOfOrNull { it.lastPlayedMs } ?: 0L

    override fun recentlyPlayed(limit: Int): List<Entry> = entries.values.sortedByDescending { it.lastPlayedMs }.take(limit)

    override fun mostPlayed(limit: Int): List<Entry> = entries.values.sortedByDescending { it.playCount }.take(limit)

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            // Cap stored history so the file stays small.
            entries.values.sortedByDescending { it.lastPlayedMs }.take(200).forEach { e ->
                arr.put(
                    JSONObject()
                        .put("uri", e.uri)
                        .put("last", e.lastPlayedMs)
                        .put("count", e.playCount)
                        .put("title", e.title),
                )
            }
            file.writeTextAtomic(arr.toString())
        }
    }
}
