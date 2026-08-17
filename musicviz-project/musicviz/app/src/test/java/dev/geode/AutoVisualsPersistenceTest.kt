package dev.geode

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.geode.ui.AutoVisualsPrefsStore
import dev.geode.ui.PlayerViewModel
import dev.geode.ui.VizUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence of the auto-visuals knobs (Random + visual playlist settings).
 * Before this store existed every one of them was session-only: a user who
 * tuned Random to switch every 90 seconds on strong beats, styles only, got
 * 20-seconds-anything back on every app start.
 *
 * Pinned here: the store round-trips all nine knobs AND the playlist entries,
 * coerces intervals into the slider range on load, [PlayerViewModel] loads
 * them into [VizUiState] at construction and writes them back on every setter
 * - and the one thing that must NOT persist (Random's own on/off) stays
 * session-only. The entries persist because `vizPlaylistEnabled` does: a flag
 * that survived a restart while the hearts it rotates did not came back
 * enabled-but-inert, so the two now travel together, with an
 * enabled-but-empty restore cleared as the backstop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoVisualsPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("geode-viz", Context.MODE_PRIVATE)
    private val store get() = AutoVisualsPrefsStore(context)

    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    private val heartedLooks =
        listOf(
            dev.geode.ui.VizPlaylistEntry(sceneId = "fluid", presetName = "Dusk", label = "Dusk"),
            dev.geode.ui.VizPlaylistEntry(sceneId = "milkdrop", milkPath = "/x/y.milk", label = "y"),
        )

    /** A knob set where every value differs from its [VizUiState] default. */
    private val tuned =
        VizUiState(
            randomIntervalSec = 45,
            randomOnBeat = false,
            randomIncludeStyles = false,
            randomIncludePresets = false,
            randomIncludeMilk = true,
            randomizeColors = true,
            vizPlaylist = heartedLooks,
            vizPlaylistEnabled = true,
            vizPlaylistIntervalSec = 120,
            vizPlaylistIntelligent = true,
        )

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun freshInstallKeepsTheStateDefaults() {
        assertEquals(VizUiState(), store.applyTo(VizUiState()))
    }

    @Test
    fun everyKnobRoundTrips() {
        store.save(tuned)
        val loaded = store.applyTo(VizUiState())
        assertEquals(45, loaded.randomIntervalSec)
        assertFalse(loaded.randomOnBeat)
        assertFalse(loaded.randomIncludeStyles)
        assertFalse(loaded.randomIncludePresets)
        assertTrue(loaded.randomIncludeMilk)
        assertTrue(loaded.randomizeColors)
        assertTrue(loaded.vizPlaylistEnabled)
        assertEquals(120, loaded.vizPlaylistIntervalSec)
        assertTrue(loaded.vizPlaylistIntelligent)
        assertEquals(heartedLooks, loaded.vizPlaylist)
    }

    @Test
    fun anEnabledPlaylistNeverRestoresInert() {
        // Older builds persisted the flag without the entries. Restoring the
        // standing instruction with nothing to rotate draws a switch that is
        // on and does nothing - the flag is cleared instead.
        prefs.edit().putBoolean("auto_playlist_enabled", true).commit()
        assertFalse(store.applyTo(VizUiState()).vizPlaylistEnabled)
    }

    @Test
    fun aMalformedEntriesDocumentRestoresEmptyInsteadOfCrashing() {
        prefs
            .edit()
            .putBoolean("auto_playlist_enabled", true)
            .putString("auto_playlist_entries", "not json at all")
            .commit()
        val loaded = store.applyTo(VizUiState())
        assertTrue(loaded.vizPlaylist.isEmpty())
        assertFalse(loaded.vizPlaylistEnabled)
    }

    @Test
    fun heartingALookPersistsItAndDedupsByPresetName() {
        val v = vm()
        val entry = dev.geode.ui.VizPlaylistEntry(sceneId = "fluid", presetName = "Dusk", label = "Dusk")
        v.addToVizPlaylist(entry)
        // The same preset hearted again (from any surface) must not stack: a
        // duplicate the heart cannot show is one the user cannot remove.
        v.addToVizPlaylist(entry.copy(label = "Dusk again"))
        v.addToVizPlaylist(entry)
        assertEquals(1, v.vizState.value.vizPlaylist.size)
        v.setVizPlaylistEnabled(true)

        // A fresh ViewModel = a fresh app process: the entries come back WITH
        // the enabled flag, so the standing instruction has something to do.
        val restored = vm().vizState.value
        assertEquals(listOf(entry), restored.vizPlaylist)
        assertTrue(restored.vizPlaylistEnabled)
    }

    @Test
    fun unheartingTheLastLookDisablesTheRestoredPlaylist() {
        val v = vm()
        v.addToVizPlaylist(dev.geode.ui.VizPlaylistEntry(sceneId = "fluid", presetName = "Dusk", label = "Dusk"))
        v.setVizPlaylistEnabled(true)
        v.removeVizPlaylistAt(0)
        val restored = vm().vizState.value
        assertTrue(restored.vizPlaylist.isEmpty())
        assertFalse("an empty playlist restored enabled-but-inert", restored.vizPlaylistEnabled)
    }

    @Test
    fun intervalsAreCoercedIntoTheSliderRangeOnLoad() {
        prefs
            .edit()
            .putInt("auto_random_interval_sec", 100_000)
            .putInt("auto_playlist_interval_sec", 1)
            .commit()
        val loaded = store.applyTo(VizUiState())
        assertEquals(AutoVisualsPrefsStore.INTERVAL_SEC.last, loaded.randomIntervalSec)
        assertEquals(AutoVisualsPrefsStore.INTERVAL_SEC.first, loaded.vizPlaylistIntervalSec)
    }

    @Test
    fun viewModelLoadsTheKnobsIntoVizUiStateOnConstruction() {
        store.save(tuned)
        val s = vm().vizState.value
        assertEquals(45, s.randomIntervalSec)
        assertFalse(s.randomOnBeat)
        assertFalse(s.randomIncludeStyles)
        assertFalse(s.randomIncludePresets)
        assertTrue(s.randomIncludeMilk)
        assertTrue(s.randomizeColors)
        assertTrue(s.vizPlaylistEnabled)
        assertEquals(120, s.vizPlaylistIntervalSec)
        assertTrue(s.vizPlaylistIntelligent)
        // The playlist entries persist with their flag; Random's own on/off
        // does not - it stays with the Now Playing Auto button.
        assertEquals(heartedLooks, s.vizPlaylist)
        assertFalse(s.randomEnabled)
    }

    @Test
    fun everySetterWritesStraightBackToDisk() {
        val v = vm()
        // The playlist needs a hearted look for the enabled flag to survive
        // the round trip - enabled-with-nothing-to-rotate is never restored.
        v.addToVizPlaylist(heartedLooks.first())
        v.setRandomInterval(66)
        v.setRandomOnBeat(false)
        v.setRandomIncludeStyles(false)
        v.setRandomIncludePresets(false)
        v.setRandomIncludeMilk(true)
        v.setRandomizeColors(true)
        v.setVizPlaylistInterval(99)
        v.setVizPlaylistIntelligent(true)
        v.setVizPlaylistEnabled(true)
        val loaded = store.applyTo(VizUiState())
        assertEquals(66, loaded.randomIntervalSec)
        assertFalse(loaded.randomOnBeat)
        assertFalse(loaded.randomIncludeStyles)
        assertFalse(loaded.randomIncludePresets)
        assertTrue(loaded.randomIncludeMilk)
        assertTrue(loaded.randomizeColors)
        assertTrue(loaded.vizPlaylistEnabled)
        assertEquals(99, loaded.vizPlaylistIntervalSec)
        assertTrue(loaded.vizPlaylistIntelligent)
    }

    @Test
    fun turningRandomOnPersistsThePlaylistHandOff() {
        // setRandomEnabled clears the PERSISTED vizPlaylistEnabled; a restart
        // must not resurrect the playlist the user watched Random replace.
        val v = vm()
        v.setVizPlaylistEnabled(true)
        v.setRandomEnabled(true)
        assertFalse(store.applyTo(VizUiState()).vizPlaylistEnabled)
        assertFalse(vm().vizState.value.vizPlaylistEnabled)
    }
}
