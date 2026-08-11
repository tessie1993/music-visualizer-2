package dev.musicviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.musicviz.ui.FontColorChoice
import dev.musicviz.ui.ThemeContrast
import dev.musicviz.ui.fontColorActive
import dev.musicviz.ui.resolvedFontColor
import dev.musicviz.ui.theme.ThemePack
import dev.musicviz.ui.theme.ThemePackCatalog
import dev.musicviz.ui.theme.colorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background dim vs. the writing, on the LIGHT packs.
 *
 * `colorScheme` dims background and surfaces by the Look tab's Background dim
 * slider (0..0.6) but the packs author their writing colours against the
 * UNDIMMED stone photography. Two things have to follow the dim anyway:
 *
 *  - the writing roles themselves. Clear Quartz crosses from light to dark
 *    around a quarter of the way along the slider, so past that the panels go
 *    near-black; writing pinned to the authored near-black tone goes with
 *    them and the screen is blank;
 *  - `resolvedFontColor`, which gates a font-colour override against the
 *    background the pack is ACTUALLY painting. Gated against the undimmed
 *    one, every pale swatch is judged unreadable on that same dark-looking
 *    screen - the Settings picker greys all of them out (`alpha 0.35`,
 *    `clickable(enabled = false)`) and leaves the user no way to fix the text
 *    they are looking at.
 *
 * The dark packs are unaffected by construction and are checked here for
 * exactly that.
 */
class BackgroundDimContrastTest {
    private val packs = ThemePackCatalog.all
    private val lightPacks = packs.filter { it.isLight }
    private val swatches = FontColorChoice.CHOICES.mapNotNull { it.argb }
    private val white = FontColorChoice.WHITE_ARGB

    /** The slider's own range, as LookSettings spells it. */
    private val dims = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)

    /** The bars the derivation itself holds to, read off it rather than restated. */
    private val bodyRatio = ThemeContrast.BODY_CONTRAST_MIN
    private val hintRatio = ThemeContrast.HINT_CONTRAST_MIN

    /** WCAG contrast ratio between two opaque colours. */
    private fun ratio(
        a: Color,
        b: Color,
    ): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * The scheme the shell actually paints: `CrystalMaterialTheme` resolves
     * the override through the light-pack gate first and only then hands
     * `colorScheme` a colour. Tests go through the same composition so they
     * cannot pass on a pairing the app never produces.
     */
    private fun ThemePack.painted(
        backgroundDim: Float = 0f,
        fontColorArgb: Int? = null,
    ) = colorScheme(
        backgroundDim = backgroundDim,
        fontColorOverride = resolvedFontColor(fontColorArgb, backgroundDim)?.let { Color(it) },
    )

    @Test
    fun `every pack keeps readable body text at every dim the slider offers`() {
        for (pack in packs) {
            for (dim in dims) {
                val cs = pack.painted(backgroundDim = dim)
                assertTrue(
                    "${pack.slug} at dim $dim: onBackground ${cs.onBackground} on ${cs.background} " +
                        "is ${ratio(cs.onBackground, cs.background)}:1",
                    ratio(cs.onBackground, cs.background) >= bodyRatio,
                )
                assertTrue(
                    "${pack.slug} at dim $dim: onSurface ${cs.onSurface} on ${cs.surface} " +
                        "is ${ratio(cs.onSurface, cs.surface)}:1",
                    ratio(cs.onSurface, cs.surface) >= bodyRatio,
                )
                assertTrue(
                    "${pack.slug} at dim $dim: onSurfaceVariant ${cs.onSurfaceVariant} on ${cs.surfaceVariant} " +
                        "is ${ratio(cs.onSurfaceVariant, cs.surfaceVariant)}:1",
                    ratio(cs.onSurfaceVariant, cs.surfaceVariant) >= hintRatio,
                )
            }
        }
    }

    @Test
    fun `a light pack dimmed past the crossover flips its writing pale`() {
        // The concrete case: Clear Quartz with the slider anywhere near the
        // top is a DARK screen, and dark writing on it is the defect.
        for (pack in lightPacks) {
            val bright = pack.painted(backgroundDim = 0f)
            val dimmed = pack.painted(backgroundDim = 0.6f)
            assertTrue("${pack.slug} undimmed is not light", bright.background.luminance() > 0.5f)
            assertTrue("${pack.slug} at 60% dim is not dark", dimmed.background.luminance() < 0.5f)
            assertTrue("${pack.slug} undimmed writes dark", bright.onSurface.luminance() < 0.5f)
            assertTrue(
                "${pack.slug} at 60% dim still writes dark (${dimmed.onSurface}) on a dark surface",
                dimmed.onSurface.luminance() > 0.5f,
            )
        }
    }

    @Test
    fun `an undimmed pack paints exactly the colours it authored`() {
        // The counterweight to the test above: the photographs are the design,
        // so the readability pull must be inert wherever the pack's authored
        // pairing already holds - which is every pack as shipped.
        for (pack in packs) {
            val cs = pack.painted(backgroundDim = 0f)
            assertEquals("${pack.slug} onBackground", pack.palette.onBackground, cs.onBackground)
            assertEquals("${pack.slug} onSurface", pack.palette.onSurface, cs.onSurface)
            assertEquals("${pack.slug} onSurfaceVariant", pack.palette.muted, cs.onSurfaceVariant)
        }
    }

    @Test
    fun `the font-colour swatches unlock once the dim has darkened a light pack`() {
        // Undimmed, FontColorThemeTest proves the gate rejects every curated
        // swatch on these packs and the picker greys the whole row out. That
        // is right on a near-white surface and absurd on the near-black one
        // the same pack paints at 60% dim. The gate itself is unchanged -
        // only what it measures against - so pyrite gold (the one swatch that
        // is genuinely middling) still fails to clear LIGHT_CONTRAST_MIN; the
        // pale majority now passes.
        for (pack in lightPacks) {
            assertEquals(
                "${pack.slug} greys out every swatch undimmed",
                0,
                swatches.count { pack.fontColorActive(it, backgroundDim = 0f) },
            )
            val usable = swatches.count { pack.fontColorActive(it, backgroundDim = 0.6f) }
            assertTrue("${pack.slug} offers only $usable swatches at 60% dim", usable >= 4)
            assertTrue("${pack.slug} rejects White on a near-black surface", pack.fontColorActive(white, 0.6f))
            assertEquals(Color(white), pack.painted(backgroundDim = 0.6f, fontColorArgb = white).onSurface)
        }
    }

    @Test
    fun `the picker's greying and what the scheme paints agree at every dim`() {
        // fontColorActive is what LookSettings greys a swatch out with. The
        // two reading the dim differently is how the picker came to disable
        // the only usable swatches on the screen; they must not be able to
        // disagree, at any setting.
        for (pack in packs) {
            for (dim in dims) {
                for (argb in swatches) {
                    val painted = pack.painted(backgroundDim = dim, fontColorArgb = argb).onSurface == Color(argb)
                    assertEquals(
                        "${pack.slug} dim $dim #${Integer.toHexString(argb)}",
                        pack.fontColorActive(argb, dim),
                        painted,
                    )
                }
            }
        }
    }

    @Test
    fun `the dim never changes a dark pack's answer`() {
        // Dark packs accept every override at every setting; the parameter
        // must be inert for them rather than quietly gating something.
        for (pack in packs.filterNot { it.isLight }) {
            for (dim in dims) {
                for (argb in swatches) {
                    assertTrue("${pack.slug} dim $dim", pack.fontColorActive(argb, dim))
                }
            }
        }
    }

    @Test
    fun `the default argument leaves every existing caller on the undimmed answer`() {
        // The gate grew a parameter; call sites that do not pass one must
        // behave exactly as before.
        for (pack in packs) {
            for (argb in swatches) {
                assertEquals(pack.slug, pack.fontColorActive(argb, 0f), pack.fontColorActive(argb))
                assertEquals(pack.slug, pack.resolvedFontColor(argb, 0f), pack.resolvedFontColor(argb))
            }
        }
    }
}
