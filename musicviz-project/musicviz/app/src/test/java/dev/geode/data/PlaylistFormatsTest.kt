package dev.geode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the playlist files other players wrote.
 *
 * Two things make this more than a line-splitter. The formats disagree about
 * everything - PLS numbers its entries and may write them out of order, XSPF
 * measures duration in milliseconds where the other two use seconds, and only
 * M3U can carry the playlist's own name. And the paths inside are written for
 * the machine that exported them, so matching them to this device's library has
 * to go through the file name the way [dev.geode.ui.TrackLibrary.identityKey]
 * does, not the path.
 */
class PlaylistFormatsTest {
    private fun parsed(
        fileName: String,
        text: String,
    ): PlaylistParse.Parsed {
        val result = PlaylistFormats.parse(fileName, text)
        assertTrue("expected a parse, got $result", result is PlaylistParse.Parsed)
        return result as PlaylistParse.Parsed
    }

    private fun locations(
        fileName: String,
        text: String,
    ): List<String> = parsed(fileName, text).entries.map { it.location }

    // ---- format detection ----

    @Test
    fun `each format is recognised by its content`() {
        assertEquals(PlaylistFormat.M3U, parsed("x.txt", "#EXTM3U\na.mp3").format)
        assertEquals(PlaylistFormat.PLS, parsed("x.txt", "[playlist]\nFile1=a.mp3").format)
        assertEquals(
            PlaylistFormat.XSPF,
            parsed("x.txt", "<playlist><trackList><track><location>a.mp3</location></track></trackList></playlist>").format,
        )
    }

    @Test
    fun `a headerless m3u is recognised by its extension`() {
        // Plenty of .m3u files are just paths, one per line, with no #EXTM3U.
        assertEquals(listOf("a.mp3", "b.mp3"), locations("mix.m3u", "a.mp3\nb.mp3"))
    }

    @Test
    fun `something that is not a playlist is refused rather than guessed at`() {
        val result = PlaylistFormats.parse("notes.txt", "just some prose\nabout music")
        assertTrue("expected Unreadable, got $result", result is PlaylistParse.Unreadable)
    }

    // ---- M3U ----

    @Test
    fun `m3u comments and blank lines are not entries`() {
        assertEquals(
            listOf("a.mp3", "b.mp3"),
            locations("x.m3u", "#EXTM3U\n\n# a note\na.mp3\n\n#EXTINF:1,t\nb.mp3\n"),
        )
    }

    @Test
    fun `m3u EXTINF carries the title and a duration in seconds`() {
        val entry = parsed("x.m3u", "#EXTM3U\n#EXTINF:123,Artist - Title\na.mp3").entries.single()
        assertEquals("Artist - Title", entry.title)
        assertEquals(123_000L, entry.durationMs)
    }

    @Test
    fun `an unknown m3u duration stays unknown rather than becoming zero`() {
        // -1 is what exporters write when they did not read the file, and a
        // zero-length track would be shown as 0:00 rather than as unknown.
        val entry = parsed("x.m3u", "#EXTM3U\n#EXTINF:-1,Live set\na.mp3").entries.single()
        assertEquals(PlaylistFormats.UNKNOWN_DURATION, entry.durationMs)
        assertEquals("Live set", entry.title)
    }

    @Test
    fun `a malformed EXTINF still yields its track`() {
        // Losing the metadata is fine; losing the track is not.
        val entry = parsed("x.m3u", "#EXTM3U\n#EXTINF:bogus\na.mp3").entries.single()
        assertEquals("a.mp3", entry.location)
        assertEquals(PlaylistFormats.UNKNOWN_DURATION, entry.durationMs)
    }

    @Test
    fun `m3u carries the playlist name and otherwise falls back to the file stem`() {
        assertEquals("Road Trip", parsed("x.m3u", "#EXTM3U\n#PLAYLIST:Road Trip\na.mp3").name)
        assertEquals("summer 24", parsed("summer 24.m3u8", "#EXTM3U\na.mp3").name)
    }

    @Test
    fun `windows line endings and a byte order mark do not corrupt the first entry`() {
        // A BOM left on the front of "#EXTM3U" is how an import ends up with one
        // unplayable track named after the whole header.
        assertEquals(listOf("a.mp3", "b.mp3"), locations("x.m3u", "\uFEFF#EXTM3U\r\na.mp3\r\nb.mp3\r\n"))
    }

    // ---- PLS ----

    @Test
    fun `pls reads file title and length per numbered entry`() {
        val entries =
            parsed(
                "x.pls",
                """
                [playlist]
                NumberOfEntries=2
                File1=a.mp3
                Title1=First
                Length1=90
                File2=b.mp3
                Title2=Second
                Length2=-1
                Version=2
                """.trimIndent(),
            ).entries
        assertEquals(listOf("a.mp3", "b.mp3"), entries.map { it.location })
        assertEquals(listOf("First", "Second"), entries.map { it.title })
        assertEquals(listOf(90_000L, PlaylistFormats.UNKNOWN_DURATION), entries.map { it.durationMs })
    }

    @Test
    fun `pls entries written out of order come back in order`() {
        // The index is the order, not the position in the file, and exporters do
        // write them scrambled.
        assertEquals(
            listOf("a.mp3", "b.mp3", "c.mp3"),
            locations("x.pls", "[playlist]\nFile3=c.mp3\nFile1=a.mp3\nFile2=b.mp3"),
        )
    }

    @Test
    fun `pls numbering gaps do not truncate the rest`() {
        assertEquals(
            listOf("a.mp3", "b.mp3"),
            locations("x.pls", "[playlist]\nFile1=a.mp3\nFile7=b.mp3"),
        )
    }

    @Test
    fun `a wrong NumberOfEntries does not decide how many there are`() {
        // It is a hint that is routinely stale; the entries themselves are not.
        assertEquals(
            listOf("a.mp3", "b.mp3", "c.mp3"),
            locations("x.pls", "[playlist]\nNumberOfEntries=1\nFile1=a.mp3\nFile2=b.mp3\nFile3=c.mp3"),
        )
    }

    @Test
    fun `pls keys are read whatever their case`() {
        assertEquals(listOf("a.mp3"), locations("x.pls", "[Playlist]\nfile1=a.mp3\nTITLE1=t"))
        assertEquals("t", parsed("x.pls", "[Playlist]\nfile1=a.mp3\nTITLE1=t").entries.single().title)
    }

    // ---- XSPF ----

    @Test
    fun `xspf durations are already milliseconds`() {
        val entry =
            parsed(
                "x.xspf",
                "<playlist><title>Mix</title><trackList><track>" +
                    "<location>a.mp3</location><title>T</title><duration>90000</duration>" +
                    "</track></trackList></playlist>",
            ).entries.single()
        assertEquals(90_000L, entry.durationMs)
        assertEquals("T", entry.title)
    }

    @Test
    fun `xspf carries the playlist title`() {
        val text = "<playlist><title>Mix &amp; Match</title><trackList><track><location>a.mp3</location></track></trackList></playlist>"
        assertEquals("Mix & Match", parsed("x.xspf", text).name)
    }

    @Test
    fun `xspf namespace prefixes do not hide the tags`() {
        val text =
            "<xspf:playlist xmlns:xspf=\"http://xspf.org/ns/0/\"><xspf:trackList>" +
                "<xspf:track><xspf:location>a.mp3</xspf:location></xspf:track>" +
                "</xspf:trackList></xspf:playlist>"
        assertEquals(listOf("a.mp3"), locations("x.xspf", text))
    }

    @Test
    fun `a doctype is refused instead of expanded`() {
        // An imported playlist is untrusted input, and entity expansion is the
        // one thing in these formats that can read a file the user did not pick.
        val text =
            "<!DOCTYPE p [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" +
                "<playlist><trackList><track><location>&x;</location></track></trackList></playlist>"
        val result = PlaylistFormats.parse("x.xspf", text)
        assertTrue("a DOCTYPE must be refused, got $result", result is PlaylistParse.Unreadable)
    }

    // ---- resolution against the library ----

    @Test
    fun `a track is matched by file name whatever the exporter's path was`() {
        // The whole point: the playlist was written on a PC, or before a rescan
        // moved the library, so the path identifies a route and the name is what
        // survives - the same reasoning as TrackLibrary.identityKey.
        val library = mapOf("song.mp3" to listOf("content://media/42"))
        val resolved =
            PlaylistFormats.resolve(
                listOf(PlaylistEntry("D:\\Music\\Rock\\Song.mp3"), PlaylistEntry("/home/pc/song.MP3")),
                library,
            )
        assertEquals(listOf("content://media/42", "content://media/42"), resolved.uris)
        assertEquals(emptyList<PlaylistEntry>(), resolved.missing)
    }

    @Test
    fun `a percent-encoded file uri resolves by its decoded name`() {
        val library = mapOf("my song.mp3" to listOf("content://media/7"))
        val resolved = PlaylistFormats.resolve(listOf(PlaylistEntry("file:///music/My%20Song.mp3")), library)
        assertEquals(listOf("content://media/7"), resolved.uris)
    }

    @Test
    fun `a query string is not part of the file name`() {
        val library = mapOf("song.mp3" to listOf("content://media/9"))
        val resolved = PlaylistFormats.resolve(listOf(PlaylistEntry("http://host/music/song.mp3?token=abc")), library)
        assertEquals(listOf("content://media/9"), resolved.uris)
    }

    @Test
    fun `tracks the library does not have are reported not dropped`() {
        // Importing a 200-track playlist and silently getting 180 is the failure
        // mode; the caller has to be able to say which 20 and why.
        val resolved =
            PlaylistFormats.resolve(
                listOf(PlaylistEntry("have.mp3"), PlaylistEntry("gone.mp3")),
                mapOf("have.mp3" to listOf("uri:1")),
            )
        assertEquals(listOf("uri:1"), resolved.uris)
        assertEquals(listOf("gone.mp3"), resolved.missing.map { it.location })
    }

    @Test
    fun `two copies of one name resolve to the first and say so`() {
        // Dropping the track would be worse and picking silently would be worse
        // still, so it resolves and is listed as ambiguous.
        val resolved =
            PlaylistFormats.resolve(
                listOf(PlaylistEntry("song.mp3")),
                mapOf("song.mp3" to listOf("uri:flac", "uri:mp3")),
            )
        assertEquals(listOf("uri:flac"), resolved.uris)
        assertEquals(listOf("song.mp3"), resolved.ambiguous.map { it.location })
    }

    @Test
    fun `an empty playlist resolves to nothing without complaining`() {
        val resolved = PlaylistFormats.resolve(emptyList(), mapOf("a.mp3" to listOf("uri:1")))
        assertEquals(emptyList<String>(), resolved.uris)
        assertEquals(emptyList<PlaylistEntry>(), resolved.missing)
    }

    @Test
    fun `the library key is the name both sides agree on`() {
        // resolve() matches on baseName, so a caller building the library map any
        // other way gets misses it cannot explain. Published for that reason.
        assertEquals("song.mp3", PlaylistFormats.baseName("D:\\Music\\SONG.mp3"))
        assertEquals("song.mp3", PlaylistFormats.baseName("file:///music/Song.mp3"))
        assertEquals("song.mp3", PlaylistFormats.baseName("song.mp3"))
    }
}
