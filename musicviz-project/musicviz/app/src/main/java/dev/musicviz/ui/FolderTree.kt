package dev.musicviz.ui

/**
 * Names for the Device folders list: short, and never shared by two folders.
 *
 * The list is flat - one row per folder holding tracks - so every row needs a
 * name the user can act on. Those two requirements pull against each other.
 * MediaStore reports folders as absolute paths, and a phone's music lives eight
 * segments below `/storage/emulated/0`, so showing the path shows the same
 * prefix on every row. Showing only the last segment was what the list did, and
 * it merged: two folders called "Live" became a single row with both folders'
 * tracks flattened into it, indistinguishable from a real folder that happens to
 * hold both albums. "Live", "Singles", "Disc 1" and "Various Artists" are not
 * rare, and nothing in the UI could tell the user it had happened.
 *
 * So a label here is the *shortest suffix of a folder's path that no other
 * folder shares*. One folder called Rock stays "Rock"; two folders called Live
 * become "Music/Live" and "Podcasts/Live"; a third folder called Rock, three
 * levels deeper, does not make either Live row any longer. Extension is per
 * collision, not global, because the whole point is that rows stay readable.
 *
 * Pure string work, deliberately: this is the piece a folder browser and the
 * flat list agree on, and keeping it free of [DeviceTrack], `Context` and SAF is
 * what lets it be tested exhaustively without a device.
 *
 * The nested-node half of folder browsing is not here. It belongs with the
 * browser UI that needs it, which has to list SAF children lazily per expanded
 * node - a different traversal from anything this map-shaped input can express,
 * and writing it now would mean writing it twice.
 */
object FolderTree {
    /**
     * [folders] keyed by absolute path, re-keyed by display label.
     *
     * Ordered by label, case-insensitively, since that is the order the user
     * reads. Every folder in, every folder out: this never merges two of them,
     * which is the entire reason it exists.
     */
    fun <T> rows(folders: Map<String, List<T>>): Map<String, List<T>> {
        val segments = folders.keys.associateWith { path -> path.split('/').filter(String::isNotEmpty) }
        val depths = folders.keys.associateWithTo(HashMap()) { 1 }
        var widening = true
        while (widening) widening = widenCollisions(segments, depths)
        val labels =
            folders.keys.associateWith { path ->
                label(path, segments.getValue(path), depths.getValue(path))
            }
        val resolved = fallBackToWholePath(labels)
        return folders.entries
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { resolved.getValue(it.key) })
            .associate { (path, items) -> resolved.getValue(path) to items }
    }

    /**
     * Lengthens every label that another folder currently shares, by one parent.
     *
     * Returns whether anything moved, so the caller can run this to a fixed
     * point: one pass can create a collision it did not have (two folders whose
     * parents are both called "Music"), and can also leave a folder alone that
     * a sibling's growth has just freed. A folder already showing its whole path
     * cannot grow, so this terminates.
     */
    private fun widenCollisions(
        segments: Map<String, List<String>>,
        depths: MutableMap<String, Int>,
    ): Boolean {
        var widened = false
        val sharing = depths.keys.groupBy { label(it, segments.getValue(it), depths.getValue(it)) }
        for ((_, paths) in sharing) {
            if (paths.size < 2) continue
            for (path in paths) {
                if (depths.getValue(path) >= segments.getValue(path).size) continue
                depths[path] = depths.getValue(path) + 1
                widened = true
            }
        }
        return widened
    }

    /**
     * The last resort for folders whose paths are exhausted and still collide.
     *
     * Two keys can spell one folder (`/m/Rock` and `/m/Rock/`), which MediaStore
     * does not produce - `substringBeforeLast('/', "")` cannot leave a trailing
     * separator - but which must not silently drop a row if it ever arrives. The
     * raw path is unique because it was a map key.
     */
    private fun fallBackToWholePath(labels: Map<String, String>): Map<String, String> {
        val exhausted =
            labels.entries
                .groupBy { it.value }
                .filterValues { it.size > 1 }
                .values
                .flatten()
                .map { it.key }
                .toSet()
        return labels.mapValues { (path, label) -> if (path in exhausted) path else label }
    }

    /**
     * The last [depth] segments of [path], or [path] itself when it has none.
     *
     * MediaStore reports the folder of a bare filename as the empty string, so
     * "no segments" is a real input rather than a guard.
     */
    private fun label(
        path: String,
        segments: List<String>,
        depth: Int,
    ): String = if (segments.isEmpty()) path else segments.takeLast(depth).joinToString("/")
}
