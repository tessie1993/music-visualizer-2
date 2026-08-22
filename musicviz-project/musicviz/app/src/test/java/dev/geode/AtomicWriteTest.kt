package dev.geode

import dev.geode.data.AtomicWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared temp-file-plus-rename helper, exercised headlessly because the
 * property it exists for is otherwise unreachable: a write that cannot
 * complete must leave the PREVIOUS document intact rather than a truncated
 * one. The stores parse inside `runCatching`, so a half-written file raises
 * nothing at all - it silently reads back as "nothing saved yet".
 */
class AtomicWriteTest {
    private fun tempDir(): File =
        File.createTempFile("atomicwrite", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @Test
    fun writes_the_content() {
        val f = File(tempDir(), "history.json")
        assertTrue(AtomicWrite.text(f, """{"tracks":[]}"""))
        assertEquals("""{"tracks":[]}""", f.readText())
    }

    @Test
    fun leaves_no_temp_file_behind() {
        val dir = tempDir()
        AtomicWrite.text(File(dir, "palettes.json"), "x")
        assertEquals(listOf("palettes.json"), dir.list()!!.sorted())
    }

    @Test
    fun replaces_existing_content_whole() {
        val f = File(tempDir(), "chill.json")
        f.writeText("a".repeat(5000))
        assertTrue(AtomicWrite.text(f, "short"))
        // Not 5000 bytes with "short" at the front: the file is replaced, not
        // overwritten in place.
        assertEquals("short", f.readText())
    }

    @Test
    fun roundtrips_unicode_and_large_documents() {
        val f = File(tempDir(), "playlist.json")
        val payload = """{"name":"Café · naïve · 日本語 · 🎧","tracks":[${"\"uri\",".repeat(20_000)}"end"]}"""
        assertTrue(AtomicWrite.text(f, payload))
        assertEquals(payload, f.readText())
    }

    @Test
    fun creates_the_parent_directory() {
        val f = File(File(tempDir(), "music-playlists"), "chill.json")
        assertTrue(AtomicWrite.text(f, "{}"))
        assertEquals("{}", f.readText())
    }

    /**
     * The mkdirs race: two threads writing different files into a missing
     * store directory can have the loser's mkdirs() return false because the
     * winner just created it - which is success, not failure. Deterministic
     * stand-in for the race: a directory that already exists at check time,
     * where mkdirs() also returns false.
     */
    @Test
    fun a_parent_created_by_someone_else_is_not_a_write_failure() {
        val parent = File(tempDir(), "music-playlists").apply { check(mkdirs()) }
        val f = File(parent, "chill.json")
        assertTrue("an existing parent directory failed the write", AtomicWrite.text(f, "{}"))
        assertEquals("{}", f.readText())
    }

    /** The verify half: a parent that exists but is a FILE must fail cleanly. */
    @Test
    fun a_parent_that_is_a_plain_file_fails_without_touching_it() {
        val notADir = File(tempDir(), "music-playlists").apply { writeText("i am a file") }
        val f = File(notADir, "chill.json")
        assertFalse(AtomicWrite.text(f, "{}"))
        assertEquals("i am a file", notADir.readText())
    }

    /** The binary overload, which is how an imported texture is copied in. */
    @Test
    fun the_stream_overload_publishes_the_whole_copy() {
        val f = File(tempDir(), "logo.png")
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        assertTrue(AtomicWrite.stream(f) { out -> out.write(bytes) })
        assertTrue(bytes.contentEquals(f.readBytes()))
    }

    /**
     * The guarantee the whole object exists for, on both overloads: a copy
     * that dies part-way must not be visible under the real name at all.
     */
    @Test
    fun a_stream_that_throws_part_way_leaves_the_previous_file_intact() {
        val f = File(tempDir(), "logo.png")
        assertTrue(AtomicWrite.text(f, "the good image"))
        assertFalse(
            AtomicWrite.stream(f) { out ->
                out.write(ByteArray(4096))
                throw java.io.IOException("provider closed the stream")
            },
        )
        assertEquals("the good image", f.readText())
    }

    @Test
    fun a_failed_write_leaves_the_previous_document_intact() {
        val f = File(tempDir(), "history.json")
        val good = """[{"uri":"a","last":1,"count":1}]"""
        assertTrue(AtomicWrite.text(f, good))

        // Make the temp path un-writable by putting a directory where the
        // temp file needs to go; the write cannot even start.
        assertTrue(File(f.absolutePath + AtomicWrite.TEMP_SUFFIX).mkdirs())

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
     * in-progress copy up as one. The suffix goes on the WHOLE name so the
     * extension filters those listings already use exclude it; if it ever
     * replaced the extension instead, every one of them would start offering
     * half-written files as saved items.
     */
    @Test
    fun the_temp_and_corrupt_suffixes_are_distinguishable_from_a_document() {
        assertEquals("tmp", File("chill.json" + AtomicWrite.TEMP_SUFFIX).extension)
        assertEquals("corrupt", File("chill.json" + AtomicWrite.CORRUPT_SUFFIX).extension)
        assertEquals("tmp", File("logo.png" + AtomicWrite.TEMP_SUFFIX).extension)
    }

    @Test
    fun quarantine_keeps_the_bytes_and_frees_the_name() {
        val dir = tempDir()
        val f = File(dir, "history.json")
        f.writeText("""{"tracks":[{"uri":"co""")
        assertTrue(AtomicWrite.quarantine(f))

        assertFalse(f.exists())
        assertEquals("""{"tracks":[{"uri":"co""", File(dir, "history.json" + AtomicWrite.CORRUPT_SUFFIX).readText())
    }
}
