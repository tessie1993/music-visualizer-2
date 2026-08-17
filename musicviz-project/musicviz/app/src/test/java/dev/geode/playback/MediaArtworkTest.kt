package dev.geode.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The decode behind every sleeve in the app.
 *
 * The lock screen showed a generic icon for the app's whole life because
 * artwork was never attached to the session at all; now that one decode feeds
 * both the session and the UI, the properties that matter are that it
 * downsamples rather than holding full-resolution covers, and that "this track
 * has no art" comes back as null instead of an exception — most of a
 * home-ripped library takes that path, so it is the common case, not the
 * error case.
 *
 * Pinned to SDK 34: Robolectric 4.14 ships no SDK 36 image, 34 is what the rest
 * of the suite uses, and nothing here is version-sensitive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaArtworkTest {
    private fun jpegOf(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test
    fun `a cover larger than the target is downsampled`() {
        val decoded = MediaArtwork.decodeBytes(jpegOf(2048, 2048), maxPx = 384)
        assertNotNull("decode returned nothing", decoded)
        val longest = maxOf(decoded!!.width, decoded.height)
        assertTrue("decoded to $longest px, wanted well under 2048", longest < 1024)
        assertTrue("decoded to $longest px, wanted at least the target", longest >= 384)
    }

    /** Never upscale: a small cover is cheap and enlarging it only wastes memory. */
    @Test
    fun `a cover smaller than the target is left alone`() {
        val decoded = MediaArtwork.decodeBytes(jpegOf(128, 128), maxPx = 384)
        assertNotNull(decoded)
        assertEquals(128, decoded!!.width)
        assertEquals(128, decoded.height)
    }

    @Test
    fun `a non-square cover keeps its aspect ratio`() {
        val decoded = MediaArtwork.decodeBytes(jpegOf(1600, 800), maxPx = 384)
        assertNotNull(decoded)
        assertEquals(
            "aspect ratio changed",
            2f,
            decoded!!.width.toFloat() / decoded.height.toFloat(),
            0.05f,
        )
    }

    /**
     * Asserts "does not throw" rather than "returns null" on purpose.
     *
     * Robolectric's BitmapFactory shadow fabricates a bitmap for any byte array
     * it is handed, so it cannot express the real decoder's null-on-garbage
     * result. What it CAN express is the property a caller depends on — a
     * malformed or truncated cover must not take down the notification or the
     * artwork thread — and that is what a corrupt tag actually risks.
     */
    @Test
    fun `bytes that are not an image do not throw`() {
        MediaArtwork.decodeBytes(ByteArray(64) { it.toByte() })
        MediaArtwork.decodeBytes(ByteArray(0))
        MediaArtwork.decodeBytes(ByteArray(3) { 0xFF.toByte() })
    }

    @Test
    fun `a file with no embedded picture reads as no artwork`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertNull(MediaArtwork.decodeEmbedded(context, "content://media/external/audio/media/999999"))
    }

    @Test
    fun `the shipped target is large enough for a lock screen`() {
        assertTrue(MediaArtwork.DEFAULT_MAX_PX >= 320)
    }

    /** Sanity-checks the fixture itself, so a failure above is the decoder's. */
    @Test
    fun `the test fixture really is a decodable jpeg`() {
        val bytes = jpegOf(64, 64)
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}
