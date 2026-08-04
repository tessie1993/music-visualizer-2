package dev.musicviz

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.FontColorChoice
import dev.musicviz.ui.GuiPrefs
import dev.musicviz.ui.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence of the font-colour override and text scale, and the one-time
 * migration off the legacy `gui_white_font` Boolean: a legacy true must load
 * as a white override, the first save must write the new `gui_font_color`
 * key and retire the legacy one, and choosing Auto afterwards must STICK -
 * a stale legacy true re-triggering the migration was the bug mode this
 * pins down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontColorMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)
    private val store get() = ThemeStore(context)

    @Before
    fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    @Test
    fun freshInstallLoadsAutomaticColorsAndDefaultScale() {
        val gui = store.loadGui()
        assertNull(gui.fontColorArgb)
        assertNull(gui.fontColorOverride)
        assertEquals(1f, gui.textScale, 0f)
    }

    @Test
    fun legacyWhiteFontLoadsAsAWhiteOverride() {
        prefs.edit().putBoolean("gui_white_font", true).commit()
        val gui = store.loadGui()
        assertEquals(FontColorChoice.WHITE_ARGB, gui.fontColorArgb)
        assertEquals(FontColorChoice.WHITE_ARGB, gui.fontColorOverride)
    }

    @Test
    fun legacyWhiteFontOffLoadsAsAutomatic() {
        prefs.edit().putBoolean("gui_white_font", false).commit()
        assertNull(store.loadGui().fontColorArgb)
    }

    @Test
    fun firstSaveWritesTheNewKeyAndRetiresTheLegacyOne() {
        prefs.edit().putBoolean("gui_white_font", true).commit()
        store.saveGui(store.loadGui())
        assertEquals(FontColorChoice.WHITE_ARGB, prefs.getInt("gui_font_color", 0))
        assertFalse("legacy key must be retired", prefs.contains("gui_white_font"))
        assertEquals(FontColorChoice.WHITE_ARGB, store.loadGui().fontColorArgb)
    }

    @Test
    fun pickingAutoAfterMigrationSticks() {
        prefs.edit().putBoolean("gui_white_font", true).commit()
        val migrated = store.loadGui()
        store.saveGui(migrated.copy(fontColorArgb = null))
        assertFalse(prefs.contains("gui_font_color"))
        assertFalse(prefs.contains("gui_white_font"))
        assertNull(store.loadGui().fontColorArgb)
    }

    @Test
    fun theLegacyWhiteFontFieldStillPersistsAsAWhiteOverride() {
        // The old Appearance switch (until the picker replaces it) only sets
        // GuiPrefs.whiteFont; saveGui persists the RESOLVED override, so the
        // switch keeps working across restarts in the interim.
        store.saveGui(GuiPrefs(whiteFont = true))
        assertEquals(FontColorChoice.WHITE_ARGB, store.loadGui().fontColorArgb)
    }

    @Test
    fun fontColorRoundTripsThroughEveryCuratedSwatch() {
        for (choice in FontColorChoice.CHOICES) {
            store.saveGui(GuiPrefs(fontColorArgb = choice.argb))
            assertEquals(choice.label, choice.argb, store.loadGui().fontColorArgb)
        }
    }

    @Test
    fun textScalePersistsAndOutOfRangeValuesAreCoercedOnRead() {
        store.saveGui(GuiPrefs(textScale = 1.2f))
        assertEquals(1.2f, store.loadGui().textScale, 0f)
        prefs.edit().putFloat("gui_text_scale", 9f).commit()
        assertEquals(GuiPrefs.TEXT_SCALE_MAX, store.loadGui().textScale, 0f)
        prefs.edit().putFloat("gui_text_scale", 0.1f).commit()
        assertEquals(GuiPrefs.TEXT_SCALE_MIN, store.loadGui().textScale, 0f)
    }

    @Test
    fun theCuratedPaletteLeadsWithAutoAndNeverRepeatsAColor() {
        assertNull(FontColorChoice.CHOICES.first().argb)
        val colors = FontColorChoice.CHOICES.mapNotNull { it.argb }
        assertEquals(colors.size, colors.toSet().size)
        assertTrue("palette should hold about 8 swatches", FontColorChoice.CHOICES.size == 8)
    }
}
