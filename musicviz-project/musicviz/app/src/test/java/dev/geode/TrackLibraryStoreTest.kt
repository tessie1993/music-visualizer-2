package dev.geode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.geode.ui.LibraryTrack
import dev.geode.ui.TrackLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards the two ways the on-disk library could lose the user's collection,
 * both against a real file rather than the pure helpers:
 *
 *  1. Every mutator is a read-modify-write driven concurrently from IO
 *     (imports, folder scans), Default (playlist analysis) and the main
 *     thread (removal). Unsynchronised, a read that lands inside another
 *     writer's truncation window parses as an empty library and the next
 *     write persists that emptiness — every import, cached BPM and tag
 *     override gone. Interleaved mutators must instead compose.
 *  2. Imports deduped on the uri string counted the same physical file twice,
 *     because the folder scanner sees SAF document uris for files MediaStore
 *     hands out as content://media/…. Identity comes from the file (display
 *     name + byte size) instead, without collapsing genuinely distinct files
 *     that merely share a title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackLibraryStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val libraryFile = File(ctx.filesDir, "library.json")
    private lateinit var store: TrackLibrary

    @Before
    fun setUp() {
        libraryFile.delete()
        File(ctx.filesDir, "library.json.tmp").delete()
        File(ctx.filesDir, "library.json.corrupt").delete()
        store = TrackLibrary(ctx)
    }

    private fun track(
        id: Int,
        name: String = "track$id.mp3",
        size: Long = 1000L + id,
    ) = LibraryTrack(
        uri = "content://com.example.docs/document/$id",
        title = "Track $id",
        artist = "Artist",
        fileName = name,
        sizeBytes = size,
    )

    @Test
    fun concurrentMutatorsKeepEveryEntryAndNeverEmptyTheLibrary() {
        val threads = 8
        val perThread = 25
        val start = CountDownLatch(1)
        val sawEmpty = AtomicInteger(0)
        val workers =
            (0 until threads).map { w ->
                Thread {
                    start.await()
                    for (i in 0 until perThread) {
                        val id = w * perThread + i
                        store.addAll(listOf(track(id)))
                        // Interleaves the other read-modify-write shape, and
                        // re-reads: once one track is in, no mutator may ever
                        // observe an empty library again.
                        val after =
                            store.updateAnalysis(
                                uri = track(id).uri,
                                title = "Track $id",
                                durationMs = 1_000L + id,
                                bpm = 120f + id,
                                key = "Am",
                            )
                        if (after.isNullOrEmpty()) sawEmpty.incrementAndGet()
                    }
                }
            }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(60_000) }

        assertEquals(0, sawEmpty.get())
        val stored = TrackLibrary(ctx).list()
        assertEquals(threads * perThread, stored.size)
        assertEquals((0 until threads * perThread).map { track(it).uri }.toSet(), stored.map { it.uri }.toSet())
        // The analysis half of each interleaved pair survived too.
        assertTrue(stored.all { it.analyzed })
    }

    @Test
    fun concurrentRemovalDoesNotResurrectOrDropOtherTracks() {
        val kept = (0 until 20).map { track(it) }
        val doomed = (100 until 120).map { track(it) }
        assertNotNull(store.addAll(kept + doomed))
        val start = CountDownLatch(1)
        val importer =
            Thread {
                start.await()
                store.addAll(listOf(track(999)))
            }
        val removers =
            doomed.map { t ->
                Thread {
                    start.await()
                    store.remove(t.uri)
                }
            }
        val workers = removers + importer
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(60_000) }

        val stored = TrackLibrary(ctx).list().map { it.uri }.toSet()
        assertEquals((kept.map { it.uri } + track(999).uri).toSet(), stored)
    }

    @Test
    fun sameFileUnderSafAndMediaStoreUriLandsOnce() {
        val viaFolderScan =
            LibraryTrack(
                uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/91",
                title = "Windowlicker",
                artist = "Aphex Twin",
                fileName = "01 Windowlicker.flac",
                sizeBytes = 41_238_112L,
            )
        val viaMediaStore =
            viaFolderScan.copy(uri = "content://media/external/audio/media/91", title = "Windowlicker (MediaStore)")
        store.addAll(listOf(viaFolderScan))
        val merged = store.addAll(listOf(viaMediaStore))!!

        assertEquals(1, merged.size)
        // The entry already on disk is the one kept, so nothing it carries
        // (analysis, user edits) is reset by the second sighting.
        assertEquals(viaFolderScan.uri, merged.first().uri)
        assertEquals(1, TrackLibrary(ctx).list().size)
    }

    @Test
    fun differentFilesWithTheSameTitleAndArtistAreBothKept() {
        val flac = track(1, name = "Windowlicker.flac", size = 41_238_112L).copy(title = "Windowlicker")
        val mp3 = track(2, name = "Windowlicker.mp3", size = 8_112_004L).copy(title = "Windowlicker")
        val merged = store.addAll(listOf(flac, mp3))!!

        assertEquals(2, merged.size)
        assertEquals(setOf(flac.uri, mp3.uri), merged.map { it.uri }.toSet())
    }

    @Test
    fun oldFormatLibraryStillLoadsAndKeepsEveryEntry() {
        // A v1 file: raw array, no wrapper, none of the fields added since.
        libraryFile.writeText(
            """
            [{"uri":"content://media/audio/7","title":"Old Track","artist":"Someone",
              "durationMs":1000,"bpm":120.0,"key":"Am","analyzed":true},
             {"uri":"content://media/audio/8","title":"Second","artist":"Someone"}]
            """.trimIndent(),
        )
        val loaded = TrackLibrary(ctx).list()
        assertEquals(2, loaded.size)

        // Adding an unrelated track must not drop the identity-less entries,
        // and must rewrite them in the current format.
        val merged = TrackLibrary(ctx).addAll(listOf(track(1)))!!
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.uri == "content://media/audio/7" && it.analyzed })
        assertTrue(merged.any { it.uri == "content://media/audio/8" })
        assertEquals(3, TrackLibrary(ctx).list().size)
    }

    @Test
    fun interruptedWriteLeavesThePreviousLibraryIntact() {
        assertNotNull(store.addAll(listOf(track(1), track(2))))
        val good = libraryFile.readText()
        // What a process death mid-write leaves behind now: a half-written
        // temp file, never a truncated library.json.
        File(ctx.filesDir, "library.json.tmp").writeText("""{"version":3,"tracks":[{"uri":"co""")

        assertEquals(good, libraryFile.readText())
        assertEquals(2, TrackLibrary(ctx).list().size)
        // And the next write recovers, temp leftovers and all.
        assertEquals(3, store.addAll(listOf(track(3)))!!.size)
        assertEquals(3, TrackLibrary(ctx).list().size)
    }

    @Test
    fun corruptLibraryIsPreservedRatherThanOverwritten() {
        assertNotNull(store.addAll(listOf(track(1), track(2))))
        val damaged = libraryFile.readText().dropLast(30)
        libraryFile.writeText(damaged)

        // The store recovers rather than failing every import forever, but
        // the bytes it could not read are kept, not written over.
        val merged = TrackLibrary(ctx).addAll(listOf(track(3)))!!
        assertEquals(listOf(track(3).uri), merged.map { it.uri })
        val quarantined = File(ctx.filesDir, "library.json.corrupt")
        assertTrue(quarantined.exists())
        assertEquals(damaged, quarantined.readText())
    }

    @Test
    fun unreadableStoreIsNeverWrittenOver() {
        assertNotNull(store.addAll(listOf(track(1), track(2))))
        val good = libraryFile.readText()
        // A directory in place of the file is the cheap stand-in for "the
        // read failed": the mutator must report null and touch nothing.
        libraryFile.delete()
        libraryFile.mkdir()

        assertFalse(libraryFile.isFile)
        assertNull(store.addAll(listOf(track(3))))
        assertNull(store.remove(track(1).uri))
        assertTrue(libraryFile.isDirectory)

        libraryFile.delete()
        libraryFile.writeText(good)
        assertEquals(2, TrackLibrary(ctx).list().size)
    }
}
