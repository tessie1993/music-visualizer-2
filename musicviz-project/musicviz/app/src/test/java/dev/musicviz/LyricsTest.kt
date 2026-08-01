package dev.musicviz

import dev.musicviz.ui.LyricsLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LRC parser and the "which line is playing" lookup - the two pieces of
 * the lyrics feature that are pure and therefore worth pinning.
 */
class LyricsTest {
    @Test
    fun `parses timestamps into milliseconds`() {
        val lyrics = LyricsLoader.parse("[00:12.50]First line\n[01:04.00]Second line", "test")!!
        assertTrue(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(12_500L, lyrics.lines[0].timeMs)
        assertEquals(64_000L, lyrics.lines[1].timeMs)
    }

    @Test
    fun `reads hundredths and thousandths by their width`() {
        // LRC's own spec says hundredths; plenty of files in the wild write
        // three digits. Guessing one would put every line of the other kind
        // ten times too early or too late.
        assertEquals(1_500L, LyricsLoader.parse("[00:01.5]x", "t")!!.lines[0].timeMs)
        assertEquals(1_500L, LyricsLoader.parse("[00:01.50]x", "t")!!.lines[0].timeMs)
        assertEquals(1_500L, LyricsLoader.parse("[00:01.500]x", "t")!!.lines[0].timeMs)
    }

    @Test
    fun `a line with several timestamps becomes several lines`() {
        // How LRC writes a repeated chorus without repeating the words.
        val lyrics = LyricsLoader.parse("[00:10.00][01:10.00][02:10.00]Chorus", "test")!!
        assertEquals(3, lyrics.lines.size)
        assertEquals(listOf(10_000L, 70_000L, 130_000L), lyrics.lines.map { it.timeMs })
        assertTrue(lyrics.lines.all { it.text == "Chorus" })
    }

    @Test
    fun `output is sorted even when the file is not`() {
        val lyrics = LyricsLoader.parse("[01:00.00]late\n[00:10.00]early", "test")!!
        assertEquals(listOf("early", "late"), lyrics.lines.map { it.text })
    }

    @Test
    fun `header tags are metadata, not words`() {
        val lyrics = LyricsLoader.parse("[ti:Song]\n[ar:Someone]\nJust words", "test")!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("Just words"), lyrics.lines.map { it.text })
    }

    @Test
    fun `untimed text still parses, as unsynced`() {
        val lyrics = LyricsLoader.parse("one\ntwo\n\nthree", "test")!!
        assertFalse(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
    }

    @Test
    fun `blank input has no lyrics`() {
        assertNull(LyricsLoader.parse("   \n  ", "test"))
    }

    @Test
    fun `indexAt finds the line in force at a position`() {
        val lyrics = LyricsLoader.parse("[00:00.00]a\n[00:10.00]b\n[00:20.00]c", "test")!!
        assertEquals(0, lyrics.indexAt(0L))
        assertEquals(0, lyrics.indexAt(9_999L))
        assertEquals(1, lyrics.indexAt(10_000L))
        assertEquals(2, lyrics.indexAt(999_000L))
    }

    @Test
    fun `indexAt returns -1 before the first line and for unsynced sheets`() {
        val synced = LyricsLoader.parse("[00:05.00]a", "test")!!
        assertEquals(-1, synced.indexAt(0L))
        val plain = LyricsLoader.parse("just words", "test")!!
        assertEquals(-1, plain.indexAt(60_000L))
    }
}
