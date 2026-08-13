package dev.musicviz.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 0.3, integration half. [SafetyChoice] is only worth anything if the
 * preferences the renderer actually reads honour it.
 *
 * The exit gate the master plan sets is behavioural: a fresh install *and* an
 * upgraded install with no v2 choice must not be able to reach a 9 Hz
 * full-screen strobe. Since `GuiPrefs.safety` is what the renderer consumes,
 * these assert on that, not on the clamp in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyChoiceMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    // The literal on-disk keys. Deliberately not ThemeStore's constants: what an
    // upgrading install actually has on disk IS the migration contract, so a
    // rename of the constant must not quietly rewrite what this test checks.
    private val keySafeVisuals = "gui_safe_visuals"
    private val keySafetyChoiceVersion = "gui_safety_choice_version"

    @Test
    fun `a fresh install loads with safe visuals on`() {
        val gui = ThemeStore(context).loadGui()
        assertTrue("a user who has never been asked must be protected", gui.safeVisuals)
        assertTrue(gui.safety.enabled)
    }

    @Test
    fun `an upgraded install with the old false and no choice is still protected`() {
        // Exactly the pre-2.0 on-disk state: the old default was written out,
        // and no choice version exists because that key did not exist yet.
        prefs().edit().putBoolean(keySafeVisuals, false).apply()

        val gui = ThemeStore(context).loadGui()
        assertTrue("an unanswered question is not an opt-out", gui.safeVisuals)
        assertTrue(gui.safety.enabled)
    }

    @Test
    fun `an explicit opt-out survives a reload`() {
        prefs()
            .edit()
            .putBoolean(keySafeVisuals, false)
            .putInt(keySafetyChoiceVersion, SafetyChoice.CURRENT_VERSION)
            .apply()

        val gui = ThemeStore(context).loadGui()
        assertFalse("an adult's explicit choice must be honoured", gui.safeVisuals)
        assertFalse(gui.safety.enabled)
    }

    @Test
    fun `saving a choice records the version, so the user is not asked twice`() {
        val store = ThemeStore(context)
        store.saveGui(store.loadGui().copy(safeVisuals = false), choiceMade = true)

        assertEquals(
            SafetyChoice.CURRENT_VERSION,
            prefs().getInt(keySafetyChoiceVersion, -1),
        )
        assertFalse(ThemeStore(context).loadGui().safeVisuals)
        assertFalse(store.safetyChoice().mustPrompt)
    }

    @Test
    fun `an ordinary save does not silently answer the question`() {
        // Toggling some unrelated setting must not count as making the safety
        // choice - that would let the prompt be dismissed by a side effect.
        val store = ThemeStore(context)
        store.saveGui(store.loadGui().copy(textScale = 1.1f))

        assertTrue("the prompt is still owed", store.safetyChoice().mustPrompt)
        assertTrue(ThemeStore(context).loadGui().safeVisuals)
    }

    @Test
    fun `reduced motion stays independent of the safety choice`() {
        // Vestibular comfort and seizure safety are different concerns; the
        // plan requires them not to be coupled.
        val store = ThemeStore(context)
        store.saveGui(store.loadGui().copy(safeVisuals = false, reducedMotion = false), choiceMade = true)

        val gui = ThemeStore(context).loadGui()
        assertFalse(gui.safeVisuals)
        assertFalse(gui.reducedMotion)
    }
}
