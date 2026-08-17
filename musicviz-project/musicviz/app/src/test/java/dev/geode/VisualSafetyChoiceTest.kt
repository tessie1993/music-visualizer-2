package dev.geode

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.geode.render.VisualSafety
import dev.geode.render.VisualSafetyChoice
import dev.geode.ui.GuiPrefs
import dev.geode.ui.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Flash safety used to be one switch, off by default, and "off by default"
 * was doing two different jobs at once: it meant "this user wants the strobe"
 * and it meant "nobody has ever been asked". Those need different answers -
 * the first is a preference, the second is an absence of one - and the app
 * cannot tell them apart from a boolean whose default is false.
 *
 * That matters here more than it would elsewhere. The paths this governs are
 * a 9 Hz full-frame strobe, a beat flash that runs at the track's rate, and a
 * randomizer that can roll a 30 Hz luminance LFO. Reading "never asked" as
 * "wants the strobe" is a consent question, not a defaults question.
 *
 * So the state is now four-valued and versioned, and this fixes the two
 * directions that matter: an unknown choice runs safe whatever the stored
 * sliders say, and only an explicit CUSTOM can turn the limits off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisualSafetyChoiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() = context.getSharedPreferences("geode-prefs", Context.MODE_PRIVATE)

    /** Stored knobs a user could have left anywhere; deliberately permissive. */
    private val permissive =
        VisualSafety.SafetyConfig(
            enabled = false,
            maxFlashHz = VisualSafety.DEFAULT_STROBE_HZ,
            maxFlashDepth = 1f,
            allowInversion = true,
            reducedMotion = false,
        )

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun `an unknown choice runs safe whatever the stored sliders say`() {
        val resolved = VisualSafety.resolve(VisualSafetyChoice.UNKNOWN, permissive)
        assertTrue("nobody has consented to flashing, so it is limited", resolved.enabled)
        assertTrue(resolved.maxFlashHz <= VisualSafety.WCAG_FLASHES_PER_SECOND)
        assertTrue(resolved.maxFlashDepth <= 0.25f)
        assertFalse("full-frame inversion is the largest contrast event there is", resolved.allowInversion)
    }

    @Test
    fun `only an explicit custom choice can turn the limits off`() {
        val off = VisualSafety.SafetyConfig.OFF
        assertFalse(VisualSafety.resolve(VisualSafetyChoice.CUSTOM, off).enabled)
        for (choice in listOf(VisualSafetyChoice.UNKNOWN, VisualSafetyChoice.SAFE, VisualSafetyChoice.REDUCED_MOTION)) {
            assertTrue("$choice must not be reachable with limiting off", VisualSafety.resolve(choice, off).enabled)
        }
    }

    @Test
    fun `custom is the stored settings, verbatim`() {
        assertEquals(permissive, VisualSafety.resolve(VisualSafetyChoice.CUSTOM, permissive))
    }

    @Test
    fun `reduced motion is safe plus motion scaling`() {
        val safe = VisualSafety.resolve(VisualSafetyChoice.SAFE, permissive)
        val reduced = VisualSafety.resolve(VisualSafetyChoice.REDUCED_MOTION, permissive)
        assertFalse(safe.reducedMotion)
        assertTrue(reduced.reducedMotion)
        assertEquals(safe, reduced.copy(reducedMotion = false))
    }

    @Test
    fun `a fresh install has made no choice, and runs safe until it does`() {
        val gui = ThemeStore(context).loadGui()
        assertEquals(VisualSafetyChoice.UNKNOWN, gui.safetyChoice)
        assertTrue(gui.safety.enabled)
    }

    @Test
    fun `the legacy switch left off is not consent`() {
        // saveGui writes every key on every save, so an untouched switch is
        // stored as false the first time a user changes any other setting.
        // A stored false therefore proves nothing about what anyone wanted.
        prefs().edit().putBoolean(ThemeStore.KEY_SAFE_VISUALS, false).commit()
        val gui = ThemeStore(context).loadGui()
        assertEquals(VisualSafetyChoice.UNKNOWN, gui.safetyChoice)
        assertTrue("an upgrade must not infer consent from the old default", gui.safety.enabled)
    }

    @Test
    fun `the legacy switch turned on migrates to safe`() {
        // The other direction is unambiguous: false was the default, so true
        // is something a user did on purpose, and honouring it costs them no
        // choice they had already made.
        prefs().edit().putBoolean(ThemeStore.KEY_SAFE_VISUALS, true).commit()
        assertEquals(VisualSafetyChoice.SAFE, ThemeStore(context).loadGui().safetyChoice)
    }

    @Test
    fun `an explicit choice beats the legacy switch`() {
        // A user who once turned the old switch on and has since chosen
        // Custom must get Custom, not be dragged back by the migration.
        ThemeStore(context).saveGui(GuiPrefs(safetyChoice = VisualSafetyChoice.CUSTOM, safeVisuals = true))
        assertEquals(VisualSafetyChoice.CUSTOM, ThemeStore(context).loadGui().safetyChoice)
    }

    @Test
    fun `a choice recorded under an older schema is asked again`() {
        ThemeStore(context).saveGui(GuiPrefs(safetyChoice = VisualSafetyChoice.CUSTOM))
        prefs().edit().putInt(ThemeStore.KEY_SAFETY_CHOICE_VERSION, ThemeStore.SAFETY_CHOICE_VERSION - 1).commit()
        val gui = ThemeStore(context).loadGui()
        assertEquals(
            "consent is to a specific set of behaviours; when they change it is asked for again",
            VisualSafetyChoice.UNKNOWN,
            gui.safetyChoice,
        )
        assertTrue(gui.safety.enabled)
    }

    @Test
    fun `an unreadable choice runs safe rather than throwing or guessing`() {
        ThemeStore(context).saveGui(GuiPrefs(safetyChoice = VisualSafetyChoice.CUSTOM))
        prefs().edit().putString(ThemeStore.KEY_SAFETY_CHOICE, "WILD_MODE").commit()
        val gui = ThemeStore(context).loadGui()
        assertEquals(VisualSafetyChoice.UNKNOWN, gui.safetyChoice)
        assertTrue(gui.safety.enabled)
    }

    @Test
    fun `a saved choice survives a round trip`() {
        for (choice in VisualSafetyChoice.entries) {
            ThemeStore(context).saveGui(GuiPrefs(safetyChoice = choice))
            assertEquals(choice, ThemeStore(context).loadGui().safetyChoice)
        }
    }

    @Test
    fun `the beat gate follows the resolved choice, not the stored switch`() {
        // effectiveBeatMinIntervalMs is the one safety lever that reaches the
        // ANALYZER, and live, cache and export all have to agree on it. If it
        // read the legacy boolean it would disagree with everything else the
        // moment the choice and the boolean differ.
        val gui = GuiPrefs(safetyChoice = VisualSafetyChoice.UNKNOWN, safeVisuals = false, beatMinIntervalMs = 200f)
        assertEquals(1000f / VisualSafety.WCAG_FLASHES_PER_SECOND, gui.effectiveBeatMinIntervalMs, 1e-3f)
    }
}
