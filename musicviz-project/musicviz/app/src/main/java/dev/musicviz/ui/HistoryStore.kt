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
) {
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

    fun recordPlay(
        uri: String,
        title: String,
    ) {
        val e = entries.getOrPut(uri) { Entry(uri, 0L, 0, title) }
        e.lastPlayedMs = System.currentTimeMillis()
        e.playCount++
        e.title = title
        persist()
    }

    fun recentlyPlayed(limit: Int = 20): List<Entry> = entries.values.sortedByDescending { it.lastPlayedMs }.take(limit)

    fun mostPlayed(limit: Int = 20): List<Entry> = entries.values.sortedByDescending { it.playCount }.take(limit)

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
            AtomicWrite.text(file, arr.toString())
        }
    }
}
