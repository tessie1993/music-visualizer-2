package dev.geode

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.geode.data.SessionStore
import dev.geode.playback.MediaArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Contracts the JVM suite can only pretend to check, run against the real
 * platform.
 *
 * Each of these exists because Robolectric's shadow answers differently from
 * a device: its BitmapFactory fabricates a bitmap for garbage bytes, and its
 * ContentResolver reports success for any uri. The assertions here are the
 * honest versions of tests the JVM suite carries in weakened form.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceContractsTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pngBytes(size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun garbage_bytes_decode_to_null_not_a_fabricated_bitmap() {
        assertNull(MediaArtwork.decodeBytes(ByteArray(4096) { (it * 31).toByte() }))
        assertNull(MediaArtwork.decodeBytes(ByteArray(0)))
    }

    @Test
    fun real_artwork_decodes_and_is_downsampled_to_the_cap() {
        val decoded = MediaArtwork.decodeBytes(pngBytes(1600), maxPx = 384)
        assertNotNull("a valid png must decode", decoded)
        assertTrue(
            "downsampling missed: ${decoded!!.width}px against a 384px cap",
            decoded.width in 200..800,
        )
    }

    @Test
    fun a_session_survives_the_real_filesystem_round_trip() {
        val store = SessionStore(context)
        store.clear()
        val saved =
            SessionStore.Saved(
                tracks =
                    listOf(
                        SessionStore.SavedTrack("content://media/1", "Alpha", "Artist A"),
                        SessionStore.SavedTrack("content://media/2", "Beta", "Artist B"),
                    ),
                index = 1,
                positionMs = 91_000L,
            )
        assertTrue(store.save(saved))
        assertEquals(saved, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun a_corrupt_session_file_is_quarantined_not_looped_on() {
        val store = SessionStore(context)
        store.clear()
        val file = File(context.filesDir, "session.json")
        file.writeText("{\"tracks\": [ TRUNCATED")
        assertNull(store.load())
        assertTrue(
            "the broken file must be preserved for diagnosis, not silently erased",
            context.filesDir
                .listFiles()
                .orEmpty()
                .any { it.name.startsWith("session.json") && it.name != "session.json" },
        )
        context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("session.json") }.forEach { it.delete() }
    }

    /**
     * The real resolver's honesty about the pending flow the exporters use:
     * insert pending, write, publish, and the row must be queryable; delete,
     * and it must be gone. Robolectric reports success for every step even
     * when nothing happened.
     */
    @Test
    fun the_export_publish_flow_works_against_the_real_media_store() {
        assumeTrue("RELATIVE_PATH needs API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "geode_contract_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Geode")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("insert into Movies/Geode failed", uri)
        try {
            resolver.openOutputStream(uri!!)?.use { it.write(ByteArray(2048)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val visible =
                resolver
                    .query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)
                    ?.use { c -> c.moveToFirst() && c.getLong(0) > 0 } ?: false
            assertTrue("a published export must be queryable with a real size", visible)
        } finally {
            resolver.delete(uri!!, null, null)
        }
        val stillThere =
            resolver
                .query(uri, arrayOf(MediaStore.Video.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        assertTrue("the deleted export still answers queries", !stillThere)
    }
}
