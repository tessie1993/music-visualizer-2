package dev.geode.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The loader the media session asks for cover art.
 *
 * It runs on the notification path, which is rebuilt on every position update,
 * for every track — including the majority of a home-ripped library that has no
 * embedded art at all. So the interesting cases are not the happy one: they are
 * "this track has no cover", "this uri no longer resolves", and "the loader was
 * asked after the service went away". None of them may crash a notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBitmapLoaderTest {
    private val loader = SessionBitmapLoader(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() {
        loader.release()
    }

    private fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test
    fun `it accepts image mime types and refuses others`() {
        assertTrue(loader.supportsMimeType("image/jpeg"))
        assertTrue(loader.supportsMimeType("image/png"))
        assertFalse(loader.supportsMimeType("audio/mpeg"))
        assertFalse(loader.supportsMimeType("video/mp4"))
        assertFalse(loader.supportsMimeType(""))
    }

    @Test
    fun `raw picture bytes decode to a bitmap`() {
        val bitmap = loader.decodeBitmap(jpeg()).get(5, TimeUnit.SECONDS)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    /**
     * The common case. A track with no embedded picture must resolve to "no
     * artwork" rather than taking down whatever asked — the notification is
     * rebuilt constantly, so a throw here would be a throw on every update.
     */
    @Test
    fun `a track with no embedded art fails the future instead of throwing`() {
        val future = loader.loadBitmap(Uri.parse("content://media/external/audio/media/999999"))
        var failed = false
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: ExecutionException) {
            failed = true
        }
        assertTrue("a missing cover should complete the future exceptionally, not hang", failed)
    }

    /**
     * Null means "this item has no artwork", which the session reads as "draw
     * the placeholder". A failed future would mean "something went wrong", and
     * an item that simply carries no art is not an error.
     */
    @Test
    fun `metadata with neither bytes nor a uri reports no artwork at all`() {
        val bare = MediaMetadata.Builder().setTitle("No art").build()
        assertNull(loader.loadBitmapFromMetadata(bare))
    }

    @Test
    fun `metadata carrying bytes decodes them directly`() {
        val withBytes =
            MediaMetadata
                .Builder()
                .setTitle("Has art")
                .setArtworkData(jpeg(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
        val future = loader.loadBitmapFromMetadata(withBytes)
        assertNotNull("bytes on the item should be used", future)
        assertNotNull(future!!.get(5, TimeUnit.SECONDS))
    }

    /**
     * Bytes are preferred over the uri: they are already in memory, and going
     * back to a metadata retriever when the picture is right there would open a
     * file per notification update.
     */
    @Test
    fun `bytes win over a uri when an item carries both`() {
        val both =
            MediaMetadata
                .Builder()
                .setArtworkData(jpeg(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .setArtworkUri(Uri.parse("content://media/external/audio/media/999999"))
                .build()
        // The uri resolves to nothing, so a bitmap coming back at all proves
        // the bytes were taken.
        assertNotNull(loader.loadBitmapFromMetadata(both)!!.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a malformed uri does not throw on the calling thread`() {
        val future = loader.loadBitmap(Uri.parse("not a uri"))
        runCatching { future.get(5, TimeUnit.SECONDS) }
        // Reaching here at all is the assertion: the failure has to arrive
        // through the future, never as a throw out of loadBitmap itself.
    }

    @Test
    fun `releasing twice is harmless`() {
        loader.release()
        loader.release()
    }
}
