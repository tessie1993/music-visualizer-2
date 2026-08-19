package dev.geode

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.geode.export.StudioClips
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clip management against a real MediaStore.
 *
 * The JVM suite can only check the input handling, because Robolectric's
 * ContentResolver shadow reports success for any content uri it is handed —
 * it cannot express "no rows matched", so a delete of a file that does not
 * exist comes back true there. That makes the interesting half of this feature
 * untestable off a device: whether a rename actually renames, whether a delete
 * actually deletes, and whether the list reflects either afterwards.
 *
 * These write a real file into the app's own MediaStore collection, operate on
 * it and clean up. Scoped storage means the app owns what it inserts, so no
 * user confirmation is involved and nothing outside Movies/Geode is touched.
 */
@RunWith(AndroidJUnit4::class)
class StudioClipsInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var inserted: Uri? = null

    @Before
    fun setUp() {
        // MediaStore.Video RELATIVE_PATH inserts need Q; below that the app
        // writes through the file system and this test does not apply.
        assumeTrue("scoped-storage insert needs API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        inserted = insertClip("geode_instrumented_${System.currentTimeMillis()}.mp4")
        assertNotNull("could not seed a clip to operate on", inserted)
    }

    @After
    fun tearDown() {
        inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    private fun insertClip(name: String): Uri? {
        val values =
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Geode")
            }
        // EXTERNAL_CONTENT_URI, not the volume-qualified form, because that is
        // what both exporters insert through — and the two produce different
        // uri STRINGS for the same row, so seeding the other way made the
        // listing assertion compare a clip against itself and lose.
        val uri =
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return null
        // A few bytes so the row has a real file behind it.
        context.contentResolver.openOutputStream(uri)?.use { it.write(ByteArray(1024)) }
        return uri
    }

    private fun displayNameOf(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Test
    fun renaming_a_clip_changes_its_display_name_and_keeps_the_extension() {
        val uri = inserted!!
        val before = displayNameOf(uri)
        val renamed = StudioClips.rename(context, uri.toString(), "sunset drop")
        assertTrue("rename reported failure: ${StudioClips.lastRenameDiagnostic}", renamed)
        // The answer is read from the COLLECTION, not the seeded uri:
        // MediaStore may honour a rename by re-identifying the row (observed
        // on the API 30 emulator - the old _ID dies with the old path), and
        // the Studio itself refreshes its listing after a rename for exactly
        // that reason. The uri-stability claim would be a claim about
        // MediaStore's internals, not about this app's feature.
        val clips = StudioClips.list(context)
        val renamedClip = clips.firstOrNull { it.name.startsWith("sunset drop") }
        assertNotNull("renamed clip missing from the listing of ${clips.size}", renamedClip)
        assertTrue("extension was lost: ${renamedClip!!.name}", renamedClip.name.endsWith(".mp4"))
        assertFalse(
            "the old name survived the rename",
            clips.any { it.name == before },
        )
        // Tear down the row wherever the rename left it - the seeded uri may
        // no longer resolve if the row was re-identified.
        inserted = Uri.parse(renamedClip.uri)
    }

    @Test
    fun a_rename_that_would_escape_the_collection_leaves_the_file_alone() {
        val uri = inserted!!
        val before = displayNameOf(uri)
        assertFalse(StudioClips.rename(context, uri.toString(), "../escaped"))
        assertEquals("the file was renamed anyway", before, displayNameOf(uri))
    }

    @Test
    fun deleting_a_clip_removes_it_from_the_store() {
        val uri = inserted!!
        assertTrue("delete reported failure", StudioClips.delete(context, uri.toString()))
        assertEquals("the row survived the delete", null, displayNameOf(uri))
        inserted = null
    }

    /** The real resolver's answer for a row that is not there. */
    @Test
    fun deleting_a_clip_twice_reports_failure_the_second_time() {
        val uri = inserted!!
        assertTrue(StudioClips.delete(context, uri.toString()))
        assertFalse("a second delete claimed success", StudioClips.delete(context, uri.toString()))
        inserted = null
    }

    @Test
    fun a_seeded_clip_appears_in_the_studio_listing() {
        val clips = StudioClips.list(context)
        assertTrue(
            "the seeded clip is not in the listing of ${clips.size}",
            clips.any { it.uri == inserted.toString() },
        )
    }
}
