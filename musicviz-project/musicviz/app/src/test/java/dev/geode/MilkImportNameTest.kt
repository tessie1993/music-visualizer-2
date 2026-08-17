package dev.geode

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.geode.data.PresetStore
import dev.geode.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Importing a .milk preset must fail as a failure and name as the user knows.
 *
 * Two shipped defects pinned shut:
 *  - a null content stream still returned the destination path, so a failed
 *    import reported success and handed the engine a file that was never
 *    written ("success-on-failure");
 *  - the name came from `uri.lastPathSegment`, which for SAF uris is an
 *    opaque document id (`document/1234`), so imports listed under names no
 *    user chose. It now resolves through [OpenableColumns.DISPLAY_NAME] and
 *    is sanitized through [PresetStore.milkFileName], the rule every other
 *    .milk in the directory obeys. The copy itself goes through AtomicWrite,
 *    so a kill mid-copy cannot leave a truncated preset behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MilkImportNameTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun vm(): PlayerViewModel = PlayerViewModel(app)

    private val source = "MILKDROP_PRESET_VERSION=201\n[preset00]\nzoom=1.0\n"

    private fun uriWith(
        display: String?,
        bytes: ByteArray?,
        raw: String = "content://com.provider.docs/document/4711",
    ): Uri {
        val uri = Uri.parse(raw)
        val shadow = Shadows.shadowOf(app.contentResolver)
        if (display != null) {
            val cursor = RoboCursor()
            cursor.setColumnNames(listOf(OpenableColumns.DISPLAY_NAME))
            cursor.setResults(arrayOf(arrayOf<Any>(display)))
            shadow.setCursor(uri, cursor)
        }
        if (bytes != null) shadow.registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun milkDir(): File = File(app.filesDir, "milk")

    @Test
    fun `the import is named from DISPLAY_NAME, not the opaque document id`() {
        val path = vm().importMilkPresetBlocking(uriWith("Neon Tunnel.milk", source.toByteArray()))
        assertEquals(
            "the user's name for the file did not survive the import",
            PresetStore.milkFileName("Neon Tunnel.milk"),
            File(path!!).name,
        )
        assertEquals(source, File(path).readText())
    }

    @Test
    fun `a display name with filesystem-hostile characters is sanitized`() {
        val path = vm().importMilkPresetBlocking(uriWith("weird/na:me.milk", source.toByteArray()))
        val file = File(path!!)
        assertTrue(file.isFile)
        assertEquals(PresetStore.milkFileName("weird/na:me.milk"), file.name)
        // Whatever the sanitizer produced, it landed IN the milk dir, not in
        // a subdirectory a slash smuggled in.
        assertEquals(milkDir().absolutePath, file.parentFile?.absolutePath)
    }

    @Test
    fun `a stream that cannot be opened returns null and writes nothing`() {
        // No stream registered: the resolver has nothing to give. The old
        // code returned the destination path anyway.
        fun files() =
            milkDir()
                .walkTopDown()
                .filter { it.isFile }
                .map { it.name }
                .toSet()
        val v = vm()
        val before = files()
        val path = v.importMilkPresetBlocking(uriWith("Ghost.milk", bytes = null))
        assertNull("an unreadable import reported success", path)
        assertEquals("a failed import left files behind (not even a .tmp)", before, files())
    }

    @Test
    fun `a uri with no display name still imports under a usable fallback`() {
        val path =
            vm().importMilkPresetBlocking(
                uriWith(display = null, bytes = source.toByteArray(), raw = "content://x/tunnel.milk"),
            )
        assertTrue(File(path!!).isFile)
        assertTrue(File(path).name.endsWith(".milk"))
        assertEquals(source, File(path).readText())
    }
}
