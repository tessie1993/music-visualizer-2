package dev.musicviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.musicviz.ui.AppTheme
import dev.musicviz.ui.FontColorChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Appearance "Font color" option (successor of the old white-font
 * switch). The invariants: null must reproduce the untouched theme (existing
 * users see no change), an override must repaint every text role on the dark
 * themes, and on the light themes an override must survive the contrast gate
 * or be ignored - pale text on a near-white surface would blank the whole
 * UI, which is the failure mode this test exists to catch.
 */
class FontColorThemeTest {
    private val lightThemes = AppTheme.entries.filter { it.isLight }
    private val darkThemes = AppTheme.entries.filterNot { it.isLight }
    private val white = FontColorChoice.WHITE_ARGB
    private val swatches = FontColorChoice.CHOICES.mapNotNull { it.argb }

    /** A deliberately dark override, darker than anything in the curated set. */
    private val plum = 0xFF2A1520.toInt()

    @Test
    fun theFourHeroStonesLeadTheThemeList() {
        assertEquals(
            listOf(AppTheme.LAPIS, AppTheme.SUGILITE, AppTheme.ROSE_QUARTZ, AppTheme.AMETHYST),
            AppTheme.entries.take(4),
        )
        // Light themes close the list; persistence is by name so the order
        // itself is free to change, but Light/Paper trailing is a deliberate
        // "the stones come first" presentation choice.
        assertEquals(listOf(AppTheme.LIGHT, AppTheme.PAPER), AppTheme.entries.takeLast(2))
    }

    @Test
    fun roseQuartzIsALightTheme() {
        assertEquals(
            listOf(AppTheme.ROSE_QUARTZ, AppTheme.LIGHT, AppTheme.PAPER),
            lightThemes,
        )
    }

    @Test
    fun autoLeavesTextRolesUntouched() {
        for (theme in AppTheme.entries) {
            val plain = theme.colorScheme()
            val auto = theme.colorScheme(fontColorArgb = null)
            assertEquals(theme.name, plain.onSurface, auto.onSurface)
            assertEquals(theme.name, plain.onBackground, auto.onBackground)
            assertEquals(theme.name, plain.onSurfaceVariant, auto.onSurfaceVariant)
            assertNotEquals("${theme.name} is white before any override", Color.White, auto.onSurface)
        }
    }

    @Test
    fun overridePaintsDarkThemeTextRoles() {
        for (theme in darkThemes) {
            for (argb in swatches) {
                val on = theme.colorScheme(fontColorArgb = argb)
                val want = Color(argb)
                assertEquals(theme.name, want, on.onSurface)
                assertEquals(theme.name, want, on.onBackground)
                assertEquals(theme.name, want, on.onSurfaceVariant)
            }
        }
    }

    @Test
    fun overridePaintsTheContainerTextRolesToo() {
        // The historic bug: "not all writing turns white". Three surface
        // roles were repainted, so anything drawn with an on*Container role
        // - every chip and filled selection in the shell - stayed
        // theme-coloured.
        for (theme in darkThemes) {
            val on = theme.colorScheme(fontColorArgb = white)
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
    fun lightThemesIgnoreEveryCuratedSwatch() {
        // All curated swatches are pale; none can be read on a near-white
        // surface, so the contrast gate must reject every one of them on the
        // light themes and the scheme must come out untouched.
        for (theme in lightThemes) {
            val plain = theme.colorScheme()
            for (argb in swatches) {
                assertFalse("${theme.name} accepted #${Integer.toHexString(argb)}", theme.fontColorActive(argb))
                val on = theme.colorScheme(fontColorArgb = argb)
                assertEquals(theme.name, plain.onSurface, on.onSurface)
                assertEquals(theme.name, plain.onBackground, on.onBackground)
            }
            assertTrue("${theme.name} text is not dark enough to read", plain.onSurface.luminance() < 0.5f)
            assertTrue("${theme.name} background is not light", plain.background.luminance() > 0.5f)
        }
    }

    @Test
    fun aGenuinelyDarkOverridePassesTheLightThemeContrastGate() {
        for (theme in lightThemes) {
            assertTrue(theme.name, theme.fontColorActive(plum))
            assertEquals(theme.name, Color(plum), theme.colorScheme(fontColorArgb = plum).onSurface)
        }
    }

    @Test
    fun textOnASaturatedAccentFillIsNeverForcedToTheOverride() {
        // onPrimary is text on the primary FILL, and several themes anchor a
        // near-white primary (Clear Quartz, Mono). Forcing a pale override
        // there would trade one unreadable case for another, so those call
        // sites pick by luminance instead - which means the role itself must
        // stay untouched.
        for (theme in darkThemes) {
            val on = theme.colorScheme(fontColorArgb = white)
            assertEquals(theme.name, theme.colorScheme().onPrimary, on.onPrimary)
        }
    }

    @Test
    fun resolvedFontColorAgreesWithTheSchemeItProduces() {
        // fontColorActive is what the Settings picker greys swatches out
        // with, so it must never disagree with what colorScheme paints.
        for (theme in AppTheme.entries) {
            for (argb in swatches + plum) {
                val painted = theme.colorScheme(fontColorArgb = argb).onSurface == Color(argb)
                assertEquals("${theme.name} #${Integer.toHexString(argb)}", theme.fontColorActive(argb), painted)
            }
            assertFalse("${theme.name} active with null", theme.fontColorActive(null))
        }
    }

    @Test
    fun overrideLeavesAccentAndSurfaceRolesAlone() {
        for (theme in darkThemes) {
            val off = theme.colorScheme()
            val on = theme.colorScheme(fontColorArgb = white)
            assertEquals(theme.name, off.primary, on.primary)
            assertEquals(theme.name, off.secondary, on.secondary)
            assertEquals(theme.name, off.background, on.background)
            assertEquals(theme.name, off.surface, on.surface)
            assertEquals(theme.name, off.outline, on.outline)
        }
    }

    @Test
    fun everyCuratedSwatchReadsOnEveryDarkThemeSurface() {
        for (theme in darkThemes) {
            val on = theme.colorScheme(fontColorArgb = white)
            assertTrue("${theme.name} surface too bright", on.surface.luminance() < 0.5f)
            assertTrue("${theme.name} background too bright", on.background.luminance() < 0.5f)
            for (argb in swatches) {
                assertTrue(
                    "${theme.name} vs #${Integer.toHexString(argb)}",
                    Color(argb).luminance() - on.surface.luminance() >= 0.3f,
                )
            }
        }
    }
}
