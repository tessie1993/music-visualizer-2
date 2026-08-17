package dev.geode.data

import android.content.Context

/**
 * The tracks you marked. A set of uris in shared preferences - the smallest
 * thing that can be the truth for a heart in the transport, a shelf on Home
 * and a filter in the library at the same time.
 *
 * Insertion order is preserved so "Favourites" reads newest-first rather than
 * in whatever order a hash set happened to hold them; `SharedPreferences`
 * string sets do NOT preserve order, so the list is stored as one delimited
 * string instead.
 */
class FavouritesStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("geode-favourites", Context.MODE_PRIVATE)

    private val uris: LinkedHashSet<String> =
        LinkedHashSet(
            prefs
                .getString(KEY, "")
                .orEmpty()
                .split(SEPARATOR)
                .filter { it.isNotBlank() },
        )

    fun isFavourite(uri: String): Boolean = uri in uris

    /** Toggles and returns the new state. */
    fun toggle(uri: String): Boolean {
        val added = uri !in uris
        // Remove-then-add rather than a no-op on re-add: a re-marked track
        // should move to the front of "Favourites", and LinkedHashSet keeps
        // the ORIGINAL position of a key that is already present.
        uris.remove(uri)
        if (added) uris.add(uri)
        persist()
        return added
    }

    fun remove(uri: String) {
        if (uris.remove(uri)) persist()
    }

    /** Newest first. */
    fun all(): List<String> = uris.toList().asReversed()

    val size: Int get() = uris.size

    private fun persist() {
        prefs.edit().putString(KEY, uris.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY = "uris"

        /**
         * A newline, because a uri can contain almost anything else - a comma
         * and a semicolon are both legal in a content uri's document id.
         */
        const val SEPARATOR = "\n"
    }
}
