package dev.musicviz

import dev.musicviz.ui.writeTextAtomic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Interrupted-write recovery contract for the JSON stores: writes go through
 * a temp file + rename, so a reader never observes a half-written file and
 * a crash between the temp write and the rename leaves the previous content
 * intact (plus a `*.tmp` the `extension == "json"` listing filters ignore).
 */
class AtomicWriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun replacesExistingContentAtomically() {
        val f = File(tmp.root, "store.json")
        f.writeText("""{"v":"old"}""")
        f.writeTextAtomic("""{"v":"new"}""")
        assertEquals("""{"v":"new"}""", f.readText())
        assertFalse("temp file must not linger", File(tmp.root, "store.json.tmp").exists())
    }

    @Test
    fun createsFreshFileWhenAbsent() {
        val f = File(tmp.root, "fresh.json")
        f.writeTextAtomic("content")
        assertEquals("content", f.readText())
        assertFalse(File(tmp.root, "fresh.json.tmp").exists())
    }

    @Test
    fun strandedTempFileIsInvisibleToJsonListings() {
        // Simulates a crash after the temp write but before the rename: the
        // good file survives and the orphan .tmp has extension "tmp", so the
        // stores' `extension == "json"` filters never try to parse it.
        val good = File(tmp.root, "preset.json")
        good.writeText("""{"ok":true}""")
        File(tmp.root, "preset.json.tmp").writeText("""{"half":""")

        val listed =
            tmp.root
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .toList()
        assertEquals(listOf(good), listed)
        assertEquals("""{"ok":true}""", good.readText())
    }
}
