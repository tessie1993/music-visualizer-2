package dev.geode.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Device folders list is allowed to call a folder.
 *
 * The list used to be built by regrouping the folder map on
 * `substringAfterLast('/')` and flattening the collisions, so two folders
 * called "Live" - one under Music, one under Podcasts - became a single row
 * holding both folders' tracks. There is no way to tell from the UI that it
 * happened: the row looks like a folder and plays like one, it just is not the
 * one the user tapped. Any repeated leaf name does it, and "Live", "Singles",
 * "Disc 1" and "Various Artists" are not rare.
 */
class FolderTreeTest {
    /** Items are plain ints; the labeller does not care what a track is. */
    private fun folders(vararg entries: Pair<String, Int>): Map<String, List<Int>> =
        entries.associate { (path, item) -> path to listOf(item) }

    @Test
    fun `a folder with an unambiguous name is shown by that name alone`() {
        assertEquals(
            listOf("Jazz", "Rock"),
            FolderTree.rows(folders("/m/Rock" to 1, "/m/Jazz" to 2)).keys.toList(),
        )
    }

    @Test
    fun `two folders with the same name stay two folders`() {
        // The defect. This used to return one row, "Live", holding both tracks.
        val rows = FolderTree.rows(folders("/Music/Live" to 1, "/Podcasts/Live" to 2))
        assertEquals(listOf("Music/Live", "Podcasts/Live"), rows.keys.toList())
        assertEquals(listOf(1), rows["Music/Live"])
        assertEquals(listOf(2), rows["Podcasts/Live"])
    }

    @Test
    fun `disambiguating one name does not lengthen the others`() {
        val rows = FolderTree.rows(folders("/m/Live" to 1, "/p/Live" to 2, "/m/Rock" to 3))
        // Ordered by the label the user reads, case-insensitively - "rock" sorts
        // with "Rock", which case-sensitive ASCII order gets wrong.
        assertEquals(listOf("m/Live", "p/Live", "Rock"), rows.keys.toList())
    }

    @Test
    fun `a name is extended only as far as it must be`() {
        // "c/Live" is unique after one parent; the other two need two, and must
        // not drag "c/Live" deeper with them.
        val rows = FolderTree.rows(folders("/x/b/Live" to 1, "/y/b/Live" to 2, "/c/Live" to 3))
        assertEquals(listOf("c/Live", "x/b/Live", "y/b/Live"), rows.keys.toList())
    }

    @Test
    fun `a deep folder is still shown by its name when nothing collides`() {
        // The reason this is a suffix and not a path: the only folders most
        // libraries have are eight segments below /storage/emulated/0, and
        // showing that is worse than showing nothing.
        assertEquals(
            listOf("Rock"),
            FolderTree.rows(folders("/storage/emulated/0/Music/Rock" to 1)).keys.toList(),
        )
    }

    @Test
    fun `a folder that runs out of parents keeps its whole path`() {
        // "/Live" has one segment, so it cannot be extended to beat "x/Live".
        val rows = FolderTree.rows(folders("/Live" to 1, "/x/Live" to 2))
        assertEquals(setOf("Live", "x/Live"), rows.keys)
        assertEquals(listOf(1), rows["Live"])
    }

    @Test
    fun `the empty folder MediaStore reports for a bare filename is kept`() {
        // MusicLibraryController computes folder as substringBeforeLast('/', ""),
        // so a path with no separator arrives here as the empty string. The old
        // code had an `ifEmpty { k }` for exactly this and it is still needed.
        val rows = FolderTree.rows(folders("" to 1, "/m/Rock" to 2))
        assertEquals(2, rows.size)
        assertEquals(listOf(1), rows[""])
    }

    @Test
    fun `redundant separators do not invent a folder level`() {
        assertEquals(
            listOf("Rock"),
            FolderTree.rows(folders("/m//Rock/" to 1)).keys.toList(),
        )
    }

    @Test
    fun `two spellings of the same folder stay distinct rows`() {
        // Cannot arise from MediaStore, but merging two keys into one row is the
        // bug this class exists to stop, so the degenerate case returns two rows
        // rather than silently dropping one.
        val rows = FolderTree.rows(folders("/m/Rock" to 1, "/m/Rock/" to 2))
        assertEquals(2, rows.size)
        assertEquals(listOf(1) + listOf(2), rows.values.flatten().sorted())
    }

    @Test
    fun `no folders means no rows`() {
        assertEquals(emptyMap<String, List<Int>>(), FolderTree.rows(emptyMap<String, List<Int>>()))
    }

    @Test
    fun `every track of every folder survives`() {
        val many =
            mapOf(
                "/m/Live" to listOf(1, 2),
                "/p/Live" to listOf(3),
                "/m/Rock" to listOf(4, 5, 6),
            )
        val rows = FolderTree.rows(many)
        assertEquals(many.values.flatten().sorted(), rows.values.flatten().sorted())
        assertEquals(many.size, rows.size)
    }
}
