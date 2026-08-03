package dev.musicviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.musicviz.ui.AppTheme
import dev.musicviz.ui.TextColorPref
import dev.musicviz.ui.TextContrast
import dev.musicviz.ui.customTextColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Appearance "Custom text colour" option, successor to the "White
 * font" switch, and the compatibility promise made to everyone who had that
 * switch on.
 *
 * The invariants are the ones the old `WhiteFontThemeTest` pinned, restated
 * for an arbitrary colour: no colour must reproduce the untouched theme, white
 * must still paint the text roles white on the dark themes and still be a
 * no-op on the light ones, and an unreadable colour must never be rendered.
 *
 * The light-theme no-op is now a CONSEQUENCE rather than a rule - white simply
 * fails the contrast floor on Light and Paper - so
 * [whiteLandsOnTheSameSideOfTheLineAsTheOldSwitchEverywhere] carries the weight
 * that the old `isLight` check used to.
 */
class TextColorThemeTest {
    private val white = TextColorPref.WHITE
    private val lightThemes = listOf(AppTheme.LIGHT, AppTheme.PAPER)
    private val darkThemes = AppTheme.entries.filterNot { it in lightThemes }

    /** The accent-intensity / background-dim range the Appearance sliders allow. */
    private val accents = listOf(0.5f, 1f, 1.5f)
    private val dims = listOf(0f, 0.3f, 0.6f)

    @Test
    fun noColourLeavesTextRolesUntouched() {
        for (theme in AppTheme.entries) {
            val plain = theme.colorScheme()
            val off = theme.colorScheme(textColor = null)
            assertEquals(theme.name, plain.onSurface, off.onSurface)
            assertEquals(theme.name, plain.onBackground, off.onBackground)
            assertEquals(theme.name, plain.onSurfaceVariant, off.onSurfaceVariant)
            assertNotEquals("${theme.name} is white before the option is on", Color.White, off.onSurface)
        }
    }

    @Test
    fun whitePaintsDarkThemeTextWhite() {
        for (theme in darkThemes) {
            val on = theme.colorScheme(textColor = white)
            assertEquals(theme.name, Color.White, on.onSurface)
            assertEquals(theme.name, Color.White, on.onBackground)
            assertEquals(theme.name, Color.White, on.onSurfaceVariant)
        }
    }

    @Test
    fun whiteIsRefusedOnLightThemesSoTextStaysLegible() {
        for (theme in lightThemes) {
            val on = theme.colorScheme(textColor = white)
            assertEquals(theme.name, theme.colorScheme().onSurface, on.onSurface)
            assertEquals(theme.name, theme.colorScheme().onBackground, on.onBackground)
            assertTrue("${theme.name} text is not dark enough to read", on.onSurface.luminance() < 0.5f)
            assertTrue("${theme.name} background is not light", on.background.luminance() > 0.5f)
        }
    }

    /**
     * The compatibility promise, checked across the whole slider range rather
     * than at the defaults: white was previously applied on exactly the dark
     * themes and never on the light ones, whatever the accent-intensity and
     * background-dim sliders were set to. The contrast floor that replaced
     * that enum test has to draw the line in the same place.
     */
    @Test
    fun whiteLandsOnTheSameSideOfTheLineAsTheOldSwitchEverywhere() {
        for (theme in AppTheme.entries) {
            for (accent in accents) {
                for (dim in dims) {
                    val worst = TextContrast.worstRatio(white, theme.textBackdrops(accent, dim))
                    val where = "${theme.name} accent=$accent dim=$dim ($worst:1)"
                    if (theme in lightThemes) {
                        assertTrue("$where should refuse white", worst < TextContrast.MIN_LEGIBLE)
                    } else {
                        assertTrue("$where should accept white", worst >= TextContrast.MIN_LEGIBLE)
                        // With margin: the floor is 3:1 and the worst dark
                        // theme measures well past it, so no future retune of a
                        // container colour can flip a user's text back by a
                        // rounding error.
                        assertTrue("$where has no margin", worst >= TextContrast.AA_TEXT)
                    }
                }
            }
        }
    }

    @Test
    fun whitePaintsTheContainerTextRolesToo() {
        // The reported bug behind the original option: "not all writing turns
        // white". Three surface roles were repainted, so anything drawn with an
        // on*Container role - every chip and filled selection in the shell -
        // stayed theme-coloured.
        for (theme in darkThemes) {
            val on = theme.colorScheme(textColor = white)
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
    fun textOnASaturatedAccentFillIsNeverRepaintedByTheScheme() {
        // onPrimary is text on the primary FILL, and several themes anchor a
        // near-white primary (Clear Quartz, Rose Quartz, Mono). Repainting it
        // would trade one unreadable case for another, so those call sites pick
        // by luminance instead - which means the role itself must stay
        // untouched.
        for (theme in darkThemes) {
            val on = theme.colorScheme(textColor = white)
            assertEquals(theme.name, theme.colorScheme().onPrimary, on.onPrimary)
        }
    }

    @Test
    fun theSchemeItselfReportsTheColourInForce() {
        // Accent-coloured WRITING (section headers, selected rows, the lock
        // chip) names its colour explicitly, so it cannot be repainted by the
        // scheme - it asks the scheme instead. That only works while an
        // untouched scheme never looks like a repainted one.
        for (theme in AppTheme.entries) {
            assertNull("${theme.name} reads as repainted with no colour set", theme.colorScheme().customTextColor)
            assertEquals(
                "${theme.name} disagrees with textColorActive",
                theme.textColorActive(white),
                theme.colorScheme(textColor = white).customTextColor != null,
            )
        }
        for (theme in darkThemes) {
            assertEquals(theme.name, Color.White, theme.colorScheme(textColor = white).customTextColor)
        }
    }

    @Test
    fun anArbitraryLegibleColourIsAppliedExactly() {
        // Amber, which reads on most of the dark themes. Byte-exact: the option
        // must hand back the colour the user chose, not an approximation.
        val amber = 0xFFFFC107.toInt()
        var applied = 0
        for (theme in darkThemes) {
            if (!TextContrast.isLegible(amber, theme.textBackdrops())) continue
            val on = theme.colorScheme(textColor = amber)
            assertEquals(theme.name, amber, on.onSurface.toArgb())
            assertEquals(theme.name, amber, on.onPrimaryContainer.toArgb())
            applied++
        }
        assertTrue("amber was legible on no theme at all, so this test proved nothing", applied > 0)
    }

    @Test
    fun aDarkColourIsRefusedOnDarkThemesAndAcceptedOnLightOnes() {
        // The black-on-black case, from both ends.
        val nearBlack = 0xFF101010.toInt()
        for (theme in darkThemes) {
            val on = theme.colorScheme(textColor = nearBlack)
            assertEquals("${theme.name} rendered near-black text", theme.colorScheme().onSurface, on.onSurface)
        }
        for (theme in lightThemes) {
            assertEquals(theme.name, nearBlack, theme.colorScheme(textColor = nearBlack).onSurface.toArgb())
        }
    }

    @Test
    fun aTranslucentStoredColourIsForcedOpaqueRatherThanRenderedInvisible() {
        val ghostWhite = 0x00FFFFFF
        for (theme in darkThemes) {
            assertEquals(theme.name, Color.White, theme.colorScheme(textColor = ghostWhite).onSurface)
        }
    }

    @Test
    fun accentAndSurfaceRolesAreLeftAlone() {
        for (theme in darkThemes) {
            val off = theme.colorScheme()
            val on = theme.colorScheme(textColor = white)
            assertEquals(theme.name, off.primary, on.primary)
            assertEquals(theme.name, off.secondary, on.secondary)
            assertEquals(theme.name, off.background, on.background)
            assertEquals(theme.name, off.surface, on.surface)
            assertEquals(theme.name, off.outline, on.outline)
        }
    }

    @Test
    fun everyThemeCanBeGivenSomeLegibleTextColour() {
        // The picker's "nudge to legible" button is only honest if a legible
        // colour exists on every theme. It does, because one of pure white and
        // pure black always clears the floor on a coherent scheme.
        for (theme in AppTheme.entries) {
            assertNotNull(
                "${theme.name} cannot be nudged to a legible colour from mid-grey",
                TextContrast.nearestLegible(0xFF808080.toInt(), theme.textBackdrops()),
            )
        }
    }
}
