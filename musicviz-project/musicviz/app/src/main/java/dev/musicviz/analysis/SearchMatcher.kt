package dev.musicviz.analysis

/**
 * Pure matching/dedup logic for the search overlay. Kept free of Android and
 * UI-type imports so the headless gate compiles and unit-tests it; the UI
 * layer adapts its row types via the lambdas on [filterTracks].
 */
object SearchMatcher {
    /** Maximum merged track results shown by the search overlay. */
    const val TRACK_LIMIT = 30

    /** Splits a raw query into non-blank whitespace-separated terms. */
    fun terms(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /** True when EVERY term matches at least one field (case-insensitive). */
    fun matches(
        terms: List<String>,
        fields: List<String>,
    ): Boolean = terms.isNotEmpty() && terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }

    /**
     * Filters [items] to those whose fields match all [terms], dedupes by uri
     * (a [preferred] item - e.g. a device-index row - replaces a non-preferred
     * one with the same uri, keeping the earlier list position; otherwise the
     * first occurrence wins) and caps the result at [max].
     */
    fun <T> filterTracks(
        terms: List<String>,
        items: List<T>,
        max: Int = TRACK_LIMIT,
        uriOf: (T) -> String,
        fieldsOf: (T) -> List<String>,
        preferred: (T) -> Boolean = { false },
    ): List<T> {
        if (terms.isEmpty()) return emptyList()
        val byUri = LinkedHashMap<String, T>()
        for (item in items) {
            if (!matches(terms, fieldsOf(item))) continue
            val uri = uriOf(item)
            val current = byUri[uri]
            if (current == null || (!preferred(current) && preferred(item))) byUri[uri] = item
        }
        return byUri.values.take(max)
    }
}
