package dev.musicviz

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.AutoVisualsPrefsStore
import dev.musicviz.ui.PlayerViewModel
import dev.musicviz.ui.VizUiState
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
 * Pinned here: the store round-trips all nine knobs, coerces intervals into
 * the slider range on load, [PlayerViewModel] loads them into [VizUiState] at
 * construction and writes them back on every setter - and the two things
 * that must NOT persist (Random's own on/off, the playlist contents) stay
 * session-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoVisualsPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("musicviz-viz", Context.MODE_PRIVATE)
    private val store get() = AutoVisualsPrefsStore(context)

    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    /** A knob set where every value differs from its [VizUiState] default. */
    private val tuned =
        VizUiState(
            randomIntervalSec = 45,
            randomOnBeat = false,
            randomIncludeStyles = false,
            randomIncludePresets = false,
            randomIncludeMilk = true,
            randomizeColors = true,
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
        // The knobs persist; the runtime switches do not. Random stays off
        // until the Now Playing Auto button, and the playlist CONTENTS stay
        // with the hearts in Visuals › Presets that build them.
        assertFalse(s.randomEnabled)
        assertTrue(s.vizPlaylist.isEmpty())
    }

    @Test
    fun everySetterWritesStraightBackToDisk() {
        val v = vm()
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
