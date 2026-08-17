package dev.geode

import dev.geode.ui.LibraryTrack
import dev.geode.ui.TrackLibrary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the library.json schema migration: version 1 was a raw JSON array
 * of tracks; version 2 wraps it as {"version":2,"tracks":[...]} and adds the
 * user-editable metadata fields (album/genre/year/trackNo/comment); version 3
 * adds the file-identity fields (fileName/sizeBytes) that imports dedupe on.
 * Runs against TrackLibrary's pure (de)serialization and merge helpers so no
 * Android runtime is needed.
 */
class TrackLibraryMigrationTest {
    private val full =
        LibraryTrack(
            uri = "content://media/audio/42",
            title = "Windowlicker",
            artist = "Aphex Twin",
            durationMs = 366_000L,
            bpm = 128.5f,
            key = "Cm",
            analyzed = true,
            album = "Windowlicker EP",
            genre = "Electronic",
            year = 1999,
            trackNo = 1,
            comment = "B-side is the equation one",
            fileName = "windowlicker.flac",
            sizeBytes = 41_238_112L,
        )

    @Test
    fun legacyRawArrayParses() {
        val legacy =
            """
            [{"uri":"content://media/audio/7","title":"Old Track","artist":"Someone",
              "durationMs":1000,"bpm":120.0,"key":"Am","analyzed":true}]
            """.trimIndent()
        val parsed = TrackLibrary.parse(legacy)
        assertEquals(1, parsed.size)
        val t = parsed[0]
        assertEquals("content://media/audio/7", t.uri)
        assertEquals("Old Track", t.title)
        assertEquals("Someone", t.artist)
        assertEquals(1000L, t.durationMs)
        assertEquals(120f, t.bpm, 1e-4f)
        assertEquals("Am", t.key)
        assertTrue(t.analyzed)
        // New fields default cleanly for v1 data.
        assertEquals("", t.album)
        assertEquals("", t.genre)
        assertEquals(0, t.year)
        assertEquals(0, t.trackNo)
        assertEquals("", t.comment)
        assertEquals("", t.fileName)
        assertEquals(0L, t.sizeBytes)
    }

    @Test
    fun serializedFormIsVersionThree() {
        val root = JSONObject(TrackLibrary.serialize(listOf(full)))
        assertEquals(3, root.getInt("version"))
        val stored = root.getJSONArray("tracks").getJSONObject(0)
        assertEquals("windowlicker.flac", stored.getString("fileName"))
        assertEquals(41_238_112L, stored.getLong("sizeBytes"))
    }

    @Test
    fun preV3EntriesKeepTheirUriIdentity() {
        // Nothing on disk before v3 carries a file identity, so those entries
        // fall back to deduping by uri exactly as they always did.
        val legacy = LibraryTrack(uri = "content://media/audio/7", title = "Old Track")
        assertEquals("uri:content://media/audio/7", TrackLibrary.identityKey(legacy))
        assertEquals("file:windowlicker.flac:41238112", TrackLibrary.identityKey(full))
    }

    @Test
    fun rescanBackfillsLegacyIdentityAndCollapsesTheDuplicate() {
        // The pre-v3 entry (SAF uri, no identity) and the copy imported later
        // through MediaStore are the same file. Rescanning the folder hands
        // the SAF uri back with an identity, which backfills the old entry and
        // reveals the two as one — keeping the analysed row.
        val legacy =
            LibraryTrack(
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/1",
                title = "Windowlicker",
                bpm = 128.5f,
                analyzed = true,
            )
        val viaMediaStore = full.copy(uri = "content://media/audio/42", bpm = 0f, analyzed = false)
        val rescanned = legacy.copy(fileName = full.fileName, sizeBytes = full.sizeBytes)

        val merged = TrackLibrary.mergeAdds(listOf(legacy, viaMediaStore), listOf(rescanned))
        assertEquals(1, merged.size)
        assertEquals(legacy.uri, merged[0].uri)
        assertTrue(merged[0].analyzed)
        assertEquals(128.5f, merged[0].bpm, 1e-4f)
    }

    @Test
    fun v2RoundtripPreservesAllFields() {
        val other = full.copy(uri = "content://media/audio/43", title = "Other", bpm = 90f)
        val parsed = TrackLibrary.parse(TrackLibrary.serialize(listOf(full, other)))
        // Serialization is order-preserving; sorting is the mutators' job.
        assertEquals(listOf(full.uri, other.uri), parsed.map { it.uri })
        assertEquals(full, parsed.first { it.uri == full.uri })
        assertEquals(other, parsed.first { it.uri == other.uri })
    }

    @Test
    fun upsertCreatesEntryForUnknownUri() {
        val out =
            TrackLibrary.upsertInfo(
                emptyList(),
                uri = "content://media/audio/99",
                title = "Fresh",
                artist = "New Artist",
                album = "Album",
                genre = "Ambient",
                year = 2024,
                trackNo = 3,
                comment = "note",
            )
        assertEquals(1, out.size)
        val t = out[0]
        assertEquals("content://media/audio/99", t.uri)
        assertEquals("Fresh", t.title)
        assertEquals("Ambient", t.genre)
        assertEquals(2024, t.year)
        assertEquals(3, t.trackNo)
        assertEquals("note", t.comment)
    }

    @Test
    fun upsertUpdatesExistingAndKeepsAnalysis() {
        val out =
            TrackLibrary.upsertInfo(
                listOf(full),
                uri = full.uri,
                title = "Renamed",
                artist = "AFX",
                album = full.album,
                genre = "IDM",
                year = full.year,
                trackNo = full.trackNo,
                comment = "",
            )
        assertEquals(1, out.size)
        val t = out[0]
        assertEquals("Renamed", t.title)
        assertEquals("AFX", t.artist)
        assertEquals("IDM", t.genre)
        // Analysis results survive metadata edits.
        assertEquals(full.bpm, t.bpm, 1e-4f)
        assertEquals(full.key, t.key)
        assertEquals(full.durationMs, t.durationMs)
        assertTrue(t.analyzed)
    }

    @Test
    fun malformedJsonYieldsEmptyLibrary() {
        assertTrue(TrackLibrary.parse("").isEmpty())
        assertTrue(TrackLibrary.parse("{not json at all").isEmpty())
        assertTrue(TrackLibrary.parse("42").isEmpty())
        assertTrue(TrackLibrary.parse("""{"version":2}""").isEmpty())
        assertTrue(TrackLibrary.parse("""[{"title":"missing uri"}]""").isEmpty())
    }

    @Test
    fun parseOrNullSeparatesUnreadableFromEmpty() {
        // The store must not treat damaged content as "the user has no
        // tracks" — that reading is what a later write would make permanent.
        assertNull(TrackLibrary.parseOrNull("{not json at all"))
        assertNull(TrackLibrary.parseOrNull("""{"version":2}"""))
        assertNull(TrackLibrary.parseOrNull("""[{"title":"missing uri"}]"""))
        assertEquals(emptyList<LibraryTrack>(), TrackLibrary.parseOrNull("""{"version":3,"tracks":[]}"""))
    }
}
