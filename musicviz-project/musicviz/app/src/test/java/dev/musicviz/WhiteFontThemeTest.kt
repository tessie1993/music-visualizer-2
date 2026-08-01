package dev.musicviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.musicviz.ui.AppTheme
import dev.musicviz.ui.whiteFontOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Appearance "White font" option. Three invariants: off must
 * reproduce the untouched theme (existing users see no change), on must paint
 * the text roles pure white on the dark themes, and on must be a no-op for the
 * light themes — forcing white text there would blank the whole UI, which is
 * the failure mode this test exists to catch.
 */
class WhiteFontThemeTest {
    private val lightThemes = listOf(AppTheme.LIGHT, AppTheme.PAPER)
    private val darkThemes = AppTheme.entries.filterNot { it in lightThemes }

    @Test
    fun offLeavesTextRolesUntouched() {
        for (theme in AppTheme.entries) {
            val plain = theme.colorScheme()
            val off = theme.colorScheme(whiteFont = false)
            assertEquals(theme.name, plain.onSurface, off.onSurface)
            assertEquals(theme.name, plain.onBackground, off.onBackground)
            assertEquals(theme.name, plain.onSurfaceVariant, off.onSurfaceVariant)
            assertNotEquals("${theme.name} is white before the option is on", Color.White, off.onSurface)
        }
    }

    @Test
    fun onPaintsDarkThemeTextWhite() {
        for (theme in darkThemes) {
            val on = theme.colorScheme(whiteFont = true)
            assertEquals(theme.name, Color.White, on.onSurface)
            assertEquals(theme.name, Color.White, on.onBackground)
            assertEquals(theme.name, Color.White, on.onSurfaceVariant)
        }
    }

    @Test
    fun onALightThemeTheSurfacesComeDownToMeetTheWhiteText() {
        // The "white font does nothing" report. This used to be an opt-out:
        // white text on a near-white surface IS unreadable, so the option
        // refused to apply on Light and Paper. But `followSystemDark` swaps in
        // LIGHT whenever the phone is in day mode, so a user on a dark theme
        // could flip the switch and see NOTHING change, having never chosen a
        // light theme at all.
        //
        // Refusing was the wrong half to give up. White writing needs
        // something dark to sit on, so the surfaces move instead.
        for (theme in lightThemes) {
            val on = theme.colorScheme(whiteFont = true)
            assertEquals(theme.name, Color.White, on.onSurface)
            assertEquals(theme.name, Color.White, on.onBackground)
            assertTrue(
                "${theme.name} still has a light background under white text",
                on.background.luminance() < 0.2f,
            )
            assertTrue(
                "${theme.name} surface is not dark enough for white text",
                on.surface.luminance() < 0.3f,
            )
            // Off, they are still the light themes they always were.
            assertTrue("${theme.name} is no longer light with the option off", theme.colorScheme().background.luminance() > 0.5f)
        }
    }

    @Test
    fun aDarkenedLightThemeKeepsItsOwnIdentity() {
        // Darkening must not collapse Light and Paper onto one shared grey -
        // they are different themes, and the option is about text colour, not
        // about throwing away the theme the user picked.
        val schemes = lightThemes.map { it.colorScheme(whiteFont = true) }
        for (i in schemes.indices) {
            for (j in i + 1 until schemes.size) {
                assertTrue(
                    "two light themes darkened to the same background",
                    schemes[i].background != schemes[j].background,
                )
            }
        }
        for ((theme, scheme) in lightThemes.zip(schemes)) {
            assertEquals("${theme.name} lost its accent", theme.colorScheme().primary, scheme.primary)
            assertEquals("${theme.name} lost its secondary", theme.colorScheme().secondary, scheme.secondary)
        }
    }

    @Test
    fun onPaintsTheContainerTextRolesWhiteToo() {
        // The reported bug: "not all writing turns white". Three surface roles
        // were repainted, so anything drawn with an on*Container role - every
        // chip and filled selection in the shell - stayed theme-coloured.
        for (theme in darkThemes) {
            val on = theme.colorScheme(whiteFont = true)
            assertEquals(theme.name, Color.White, on.onPrimaryContainer)
            assertEquals(theme.name, Color.White, on.onSecondaryContainer)
            assertEquals(theme.name, Color.White, on.onTertiaryContainer)
            assertEquals(theme.name, Color.White, on.onErrorContainer)
            // ... and those containers have to be dark enough to read on.
            assertTrue("${theme.name} primaryContainer too bright", on.primaryContainer.luminance() < 0.5f)
            assertTrue("${theme.name} secondaryContainer too bright", on.secondaryContainer.luminance() < 0.5f)
        }
    }

    @Test
    fun textOnASaturatedAccentFillIsNeverForcedWhite() {
        // onPrimary is text on the primary FILL, and several themes anchor a
        // near-white primary (Clear Quartz, Rose Quartz, Mono). Forcing white
        // there would trade one unreadable case for another, so those call
        // sites pick by luminance instead - which means the role itself must
        // stay untouched.
        for (theme in darkThemes) {
            val on = theme.colorScheme(whiteFont = true)
            assertEquals(theme.name, theme.colorScheme().onPrimary, on.onPrimary)
        }
    }

    @Test
    fun theSchemeItselfReportsWhetherWhiteFontIsOn() {
        // Accent-coloured WRITING (section headers, selected rows, the lock
        // chip) names its colour explicitly, so it cannot be repainted by the
        // scheme - it asks the scheme instead. That only works while a scheme
        // with the option OFF never looks like one with it on.
        for (theme in AppTheme.entries) {
            assertFalse("${theme.name} reads as white-font with the option off", theme.colorScheme().whiteFontOn)
            assertEquals(
                "${theme.name} disagrees with whiteFontActive",
                theme.whiteFontActive(true),
                theme.colorScheme(whiteFont = true).whiteFontOn,
            )
        }
    }

    @Test
    fun onLeavesAccentAndSurfaceRolesAlone() {
        for (theme in darkThemes) {
            val off = theme.colorScheme()
            val on = theme.colorScheme(whiteFont = true)
            assertEquals(theme.name, off.primary, on.primary)
            assertEquals(theme.name, off.secondary, on.secondary)
            assertEquals(theme.name, off.background, on.background)
            assertEquals(theme.name, off.surface, on.surface)
            assertEquals(theme.name, off.outline, on.outline)
        }
    }

    @Test
    fun whiteTextStaysReadableOnEveryDarkThemeSurface() {
        for (theme in darkThemes) {
            val on = theme.colorScheme(whiteFont = true)
            assertTrue("${theme.name} surface too bright for white text", on.surface.luminance() < 0.5f)
            assertTrue("${theme.name} background too bright for white text", on.background.luminance() < 0.5f)
        }
    }
}
