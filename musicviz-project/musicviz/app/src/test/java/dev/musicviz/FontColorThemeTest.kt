package dev.musicviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.musicviz.ui.FontColorChoice
import dev.musicviz.ui.fontColorActive
import dev.musicviz.ui.resolvedFontColor
import dev.musicviz.ui.theme.ThemePack
import dev.musicviz.ui.theme.ThemePackCatalog
import dev.musicviz.ui.theme.colorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Appearance "Font color" option (successor of the old white-font
 * switch). The invariants: null must reproduce the untouched pack (existing
 * users see no change), an override must repaint every text role on the dark
 * packs, and on the light packs an override must survive the contrast gate or
 * be ignored - pale text on a near-white surface would blank the whole UI,
 * which is the failure mode this test exists to catch.
 */
class FontColorThemeTest {
    private val packs = ThemePackCatalog.all
    private val lightPacks = packs.filter { it.isLight }
    private val darkPacks = packs.filterNot { it.isLight }
    private val white = FontColorChoice.WHITE_ARGB
    private val swatches = FontColorChoice.CHOICES.mapNotNull { it.argb }

    /** A deliberately dark override, darker than anything in the curated set. */
    private val plum = 0xFF2A1520.toInt()

    /** The composition the shell performs; see BackgroundDimContrastTest. */
    private fun ThemePack.painted(fontColorArgb: Int? = null) =
        colorScheme(fontColorOverride = resolvedFontColor(fontColorArgb)?.let { Color(it) })

    @Test
    fun everyShippedPackIsSelectable() {
        // Persistence is by slug, so the order is free to change; that the
        // catalog is non-empty and slug-unique is what the picker relies on.
        assertTrue(packs.isNotEmpty())
        assertEquals(packs.size, packs.map { it.slug }.toSet().size)
        assertEquals(listOf("clear-quartz"), lightPacks.map { it.slug })
    }

    @Test
    fun autoLeavesTextRolesUntouched() {
        for (pack in packs) {
            val plain = pack.painted()
            val auto = pack.painted(fontColorArgb = null)
            assertEquals(pack.slug, plain.onSurface, auto.onSurface)
            assertEquals(pack.slug, plain.onBackground, auto.onBackground)
            assertEquals(pack.slug, plain.onSurfaceVariant, auto.onSurfaceVariant)
            assertNotEquals("${pack.slug} is white before any override", Color.White, auto.onSurface)
        }
    }

    @Test
    fun overridePaintsDarkPackTextRoles() {
        for (pack in darkPacks) {
            for (argb in swatches) {
                val on = pack.painted(fontColorArgb = argb)
                val want = Color(argb)
                assertEquals(pack.slug, want, on.onSurface)
                assertEquals(pack.slug, want, on.onBackground)
                assertEquals(pack.slug, want, on.onSurfaceVariant)
            }
        }
    }

    @Test
    fun overridePaintsTheContainerTextRolesToo() {
        // The historic bug: "not all writing turns white". Three surface
        // roles were repainted, so anything drawn with an on*Container role
        // - every chip and filled selection in the shell - stayed
        // pack-coloured.
        for (pack in darkPacks) {
            val on = pack.painted(fontColorArgb = white)
            assertEquals(pack.slug, Color.White, on.onPrimaryContainer)
            assertEquals(pack.slug, Color.White, on.onSecondaryContainer)
            assertEquals(pack.slug, Color.White, on.onTertiaryContainer)
            assertEquals(pack.slug, Color.White, on.onErrorContainer)
            // ... and those containers have to be dark enough to read on.
            assertTrue("${pack.slug} primaryContainer too bright", on.primaryContainer.luminance() < 0.5f)
            assertTrue("${pack.slug} secondaryContainer too bright", on.secondaryContainer.luminance() < 0.5f)
        }
    }

    @Test
    fun lightPacksIgnoreEveryCuratedSwatch() {
        // All curated swatches are pale; none can be read on a near-white
        // surface, so the contrast gate must reject every one of them on the
        // light packs and the scheme must come out untouched.
        for (pack in lightPacks) {
            val plain = pack.painted()
            for (argb in swatches) {
                assertFalse("${pack.slug} accepted #${Integer.toHexString(argb)}", pack.fontColorActive(argb))
                val on = pack.painted(fontColorArgb = argb)
                assertEquals(pack.slug, plain.onSurface, on.onSurface)
                assertEquals(pack.slug, plain.onBackground, on.onBackground)
            }
            assertTrue("${pack.slug} text is not dark enough to read", plain.onSurface.luminance() < 0.5f)
            assertTrue("${pack.slug} background is not light", plain.background.luminance() > 0.5f)
        }
    }

    @Test
    fun aGenuinelyDarkOverridePassesTheLightPackContrastGate() {
        for (pack in lightPacks) {
            assertTrue(pack.slug, pack.fontColorActive(plum))
            assertEquals(pack.slug, Color(plum), pack.painted(fontColorArgb = plum).onSurface)
        }
    }

    @Test
    fun textOnASaturatedAccentFillIsNeverForcedToTheOverride() {
        // onPrimary is text on the primary FILL. Forcing a pale override
        // there would trade one unreadable case for another, so those call
        // sites pick by luminance instead - which means the role itself must
        // stay untouched.
        for (pack in darkPacks) {
            val on = pack.painted(fontColorArgb = white)
            assertEquals(pack.slug, pack.painted().onPrimary, on.onPrimary)
        }
    }

    @Test
    fun resolvedFontColorAgreesWithTheSchemeItProduces() {
        // fontColorActive is what the Settings picker greys swatches out
        // with, so it must never disagree with what the shell paints.
        for (pack in packs) {
            for (argb in swatches + plum) {
                val painted = pack.painted(fontColorArgb = argb).onSurface == Color(argb)
                assertEquals("${pack.slug} #${Integer.toHexString(argb)}", pack.fontColorActive(argb), painted)
            }
            assertFalse("${pack.slug} active with null", pack.fontColorActive(null))
        }
    }

    @Test
    fun overrideLeavesAccentAndSurfaceRolesAlone() {
        for (pack in darkPacks) {
            val off = pack.painted()
            val on = pack.painted(fontColorArgb = white)
            assertEquals(pack.slug, off.primary, on.primary)
            assertEquals(pack.slug, off.secondary, on.secondary)
            assertEquals(pack.slug, off.background, on.background)
            assertEquals(pack.slug, off.surface, on.surface)
            assertEquals(pack.slug, off.outline, on.outline)
        }
    }

    @Test
    fun everyCuratedSwatchReadsOnEveryDarkPackSurface() {
        for (pack in darkPacks) {
            val on = pack.painted(fontColorArgb = white)
            assertTrue("${pack.slug} surface too bright", on.surface.luminance() < 0.5f)
            assertTrue("${pack.slug} background too bright", on.background.luminance() < 0.5f)
            for (argb in swatches) {
                assertTrue(
                    "${pack.slug} vs #${Integer.toHexString(argb)}",
                    Color(argb).luminance() - on.surface.luminance() >= 0.3f,
                )
            }
        }
    }
}
