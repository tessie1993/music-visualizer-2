package dev.geode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.geode.data.ExportDefaults
import dev.geode.data.ExportPrefsStore
import dev.geode.export.ExportQuality
import dev.geode.export.ExportRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence of the export defaults (quality/fps/ratio/loop-safe). These
 * used to be ephemeral `remember` state inside the export dialog, reset to
 * 1080p/60/16:9/full-length on every open; now the Settings › Export tab
 * edits them as standing defaults and the dialog writes its choices back.
 *
 * Pinned here: fresh-install defaults match the dialog's old hardcoded
 * initial values (so the redesign changes nothing for an untouched install),
 * every combination round-trips, garbage on disk falls back instead of
 * crashing or leaking an impossible value into the encoder, and the key
 * names stay the documented export_* set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportDefaultsPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("geode-prefs", Context.MODE_PRIVATE)
    private val store get() = ExportPrefsStore(context)

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun freshInstallMatchesTheDialogsOldHardcodedChoices() {
        val d = store.load()
        assertEquals(ExportDefaults(), d)
        assertEquals(ExportQuality.FHD1080, d.quality)
        assertEquals(60, d.fps)
        assertEquals(ExportRatio.R16_9, d.ratio)
        assertFalse(d.loopSafe)
    }

    @Test
    fun everyChoiceCombinationRoundTrips() {
        for (q in ExportQuality.entries) {
            for (r in ExportRatio.entries) {
                for (fps in listOf(30, 60)) {
                    for (loop in listOf(true, false)) {
                        val d = ExportDefaults(quality = q, fps = fps, ratio = r, loopSafe = loop)
                        store.save(d)
                        assertEquals(d, store.load())
                    }
                }
            }
        }
    }

    @Test
    fun garbageOnDiskFallsBackToTheDefaults() {
        prefs
            .edit()
            .putString("export_quality", "EIGHT_K")
            .putString("export_ratio", "R2_39")
            .putInt("export_fps", 24)
            .commit()
        val d = store.load()
        assertEquals(ExportQuality.FHD1080, d.quality)
        assertEquals(ExportRatio.R16_9, d.ratio)
        // The renderer only offers 30 and 60; anything else snaps to 60.
        assertEquals(60, d.fps)
    }

    @Test
    fun theKeysAreTheDocumentedExportSet() {
        store.save(ExportDefaults(quality = ExportQuality.UHD4K, fps = 30, ratio = ExportRatio.R9_16, loopSafe = true))
        assertEquals("UHD4K", prefs.getString("export_quality", null))
        assertEquals(30, prefs.getInt("export_fps", 0))
        assertEquals("R9_16", prefs.getString("export_ratio", null))
        assertTrue(prefs.getBoolean("export_loop", false))
    }
}
