package dev.geode.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Managing the clips the app renders.
 *
 * Renders land in Movies/Geode at up to 300 MB a minute at 4K, named
 * `geode_<epoch>.mp4`, and the app offered no way to delete or rename one — a
 * weekend of experimenting left gigabytes of indistinguishable files to be
 * hunted down in a gallery app.
 *
 * What is checked here is the input handling, which is the part that can be
 * checked without a real MediaStore: a rename must not be able to move a file,
 * and neither operation may throw on a uri that no longer resolves. The
 * MediaStore round trip itself needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudioClipsRenameTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uri = "content://media/external/video/media/1"

    @Test
    fun `a blank name is refused before it reaches the resolver`() {
        assertFalse(StudioClips.rename(context, uri, ""))
        assertFalse(StudioClips.rename(context, uri, "   "))
    }

    /**
     * A display name containing a separator would let a rename move the file
     * out of the collection MediaStore expects it in.
     */
    @Test
    fun `a name containing a path separator is refused`() {
        assertFalse(StudioClips.rename(context, uri, "../evil"))
        assertFalse(StudioClips.rename(context, uri, "folder/clip"))
        assertFalse(StudioClips.rename(context, uri, "back\\slash"))
    }

    /**
     * Asserts "does not throw" rather than "returns false".
     *
     * Robolectric's ContentResolver shadow reports success for any content uri
     * it is handed, so it cannot express the real resolver's zero-rows result —
     * these calls come back true under it whatever they are pointed at. What it
     * CAN express is that a stale or malformed uri does not take the Studio
     * down, and stale rows are routine: a file deleted from a gallery app
     * leaves one behind until the next refresh.
     *
     * The MediaStore round trip itself needs a device and belongs in the
     * instrumented suite.
     */
    @Test
    fun `a stale or malformed uri does not throw`() {
        StudioClips.rename(context, "content://media/external/video/media/999999", "new name")
        StudioClips.delete(context, "content://media/external/video/media/999999")
        StudioClips.delete(context, "not a uri at all")
        StudioClips.rename(context, "not a uri at all", "name")
    }

    /**
     * The total is what tells someone whether to start clearing up, so the
     * summary has to carry a size when one is known.
     */
    @Test
    fun `a clip summarises its size`() {
        val clip =
            StudioClip(
                uri = uri,
                name = "geode_1.mp4",
                durationMs = 30_000,
                sizeBytes = 128L * 1024 * 1024,
            )
        assertTrue(clip.summary(), clip.summary().contains("MB"))
    }
}
