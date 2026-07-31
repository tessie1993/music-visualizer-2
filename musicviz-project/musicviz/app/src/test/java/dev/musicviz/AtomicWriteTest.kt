package dev.musicviz

import dev.musicviz.ui.AtomicWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AtomicWriteTest {
    private fun tempDir(): File =
        File.createTempFile("atomicwrite", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @Test
    fun writes_the_content() {
        val dir = tempDir()
        val f = File(dir, "library.json")
        assertTrue(AtomicWrite.text(f, """{"tracks":[]}"""))
        assertEquals("""{"tracks":[]}""", f.readText())
    }

    @Test
    fun leaves_no_temp_file_behind() {
        val dir = tempDir()
        val f = File(dir, "library.json")
        AtomicWrite.text(f, "x")
        assertEquals(listOf("library.json"), dir.list()!!.sorted())
    }

    @Test
    fun replaces_existing_content_whole() {
        val dir = tempDir()
        val f = File(dir, "presets.json")
        f.writeText("a".repeat(5000))
        assertTrue(AtomicWrite.text(f, "short"))
        // Not 5000 bytes with "short" at the front: the file is replaced, not
        // overwritten in place.
        assertEquals("short", f.readText())
    }

    @Test
    fun roundtrips_unicode_and_large_documents() {
        val dir = tempDir()
        val f = File(dir, "playlist.json")
        val payload = """{"name":"Café · naïve · 日本語 · 🎧","tracks":[${"\"uri\",".repeat(20_000)}"end"]}"""
        assertTrue(AtomicWrite.text(f, payload))
        assertEquals(payload, f.readText())
    }

    @Test
    fun creates_the_parent_directory() {
        val dir = tempDir()
        val f = File(File(dir, "music-playlists"), "chill.json")
        assertTrue(AtomicWrite.text(f, "{}"))
        assertEquals("{}", f.readText())
    }

    /**
     * The guarantee the whole class exists for. A write that cannot complete
     * must leave the previous document readable rather than a truncated one:
     * the stores parse inside runCatching, so a half-written file does not
     * raise anything - it silently reads back as an empty library.
     */
    @Test
    fun a_failed_write_leaves_the_previous_document_intact() {
        val dir = tempDir()
        val f = File(dir, "history.json")
        val good = """[{"uri":"a","last":1,"count":1}]"""
        assertTrue(AtomicWrite.text(f, good))

        // Make the temp path un-writable by putting a directory where the
        // temp file needs to go; the write cannot even start.
        val blocker = File(f.absolutePath + AtomicWrite.TEMP_SUFFIX)
        assertTrue(blocker.mkdirs())

        assertFalse(AtomicWrite.text(f, "REPLACEMENT THAT MUST NOT LAND"))
        assertEquals(good, f.readText())
    }

    @Test
    fun a_stale_temp_file_from_an_earlier_crash_does_not_block_the_next_write() {
        val dir = tempDir()
        val f = File(dir, "palettes.json")
        File(f.absolutePath + AtomicWrite.TEMP_SUFFIX).writeText("truncated {")
        assertTrue(AtomicWrite.text(f, "{}"))
        assertEquals("{}", f.readText())
        assertEquals(listOf("palettes.json"), dir.list()!!.sorted())
    }

    /**
     * Stores that list a directory to find their documents must not pick the
     * in-progress copy up as one. The suffix is exposed so they can filter it,
     * and it has to stay distinguishable from the real extension.
     */
    @Test
    fun the_temp_suffix_is_distinguishable_from_a_json_document() {
        assertFalse(("chill.json" + AtomicWrite.TEMP_SUFFIX).endsWith(".json"))
    }
}
