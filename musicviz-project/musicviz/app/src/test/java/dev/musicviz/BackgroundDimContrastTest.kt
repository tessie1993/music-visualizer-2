package dev.musicviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.musicviz.ui.AppTheme
import dev.musicviz.ui.FontColorChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background dim vs. the writing, on the LIGHT themes.
 *
 * `colorScheme` dims background and surfaces by the Look tab's Background dim
 * slider (0..0.6). Two things used to ignore that:
 *
 *  - the light branch derived `onBackground`/`onSurface`/`onSurfaceVariant`
 *    from the primary toward BLACK, whatever the surface underneath had
 *    become. Rose Quartz crosses from light to dark around 25% dim, so past
 *    that the panels went near-black and the writing stayed near-black with
 *    them;
 *  - `resolvedFontColor` gated a font-colour override against the UNDIMMED
 *    anchor, so on that same dark-looking screen every pale swatch was still
 *    judged unreadable - the Settings picker greyed all of them out
 *    (`alpha 0.35`, `clickable(enabled = false)`) and left the user no way to
 *    fix the text they were looking at.
 *
 * Both now take the dim. The dark themes are unaffected by construction and
 * are checked here for exactly that.
 */
class BackgroundDimContrastTest {
    private val lightThemes = AppTheme.entries.filter { it.isLight }
    private val swatches = FontColorChoice.CHOICES.mapNotNull { it.argb }
    private val white = FontColorChoice.WHITE_ARGB

    /** The slider's own range, as LookSettings spells it. */
    private val dims = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)

    /** WCAG contrast ratio between two opaque colours. */
    private fun ratio(
        a: Color,
        b: Color,
    ): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** The bars the derivation itself holds to, read off it rather than restated. */
    private val bodyRatio = AppTheme.BODY_CONTRAST_MIN
    private val hintRatio = AppTheme.HINT_CONTRAST_MIN

    @Test
    fun `every theme keeps readable body text at every dim the slider offers`() {
        for (theme in AppTheme.entries) {
            for (dim in dims) {
                val cs = theme.colorScheme(backgroundDim = dim)
                assertTrue(
                    "${theme.name} at dim $dim: onBackground ${cs.onBackground} on ${cs.background} " +
                        "is ${ratio(cs.onBackground, cs.background)}:1",
                    ratio(cs.onBackground, cs.background) >= bodyRatio,
                )
                assertTrue(
                    "${theme.name} at dim $dim: onSurface ${cs.onSurface} on ${cs.surface} " +
                        "is ${ratio(cs.onSurface, cs.surface)}:1",
                    ratio(cs.onSurface, cs.surface) >= bodyRatio,
                )
                assertTrue(
                    "${theme.name} at dim $dim: onSurfaceVariant ${cs.onSurfaceVariant} on ${cs.surfaceVariant} " +
                        "is ${ratio(cs.onSurfaceVariant, cs.surfaceVariant)}:1",
                    ratio(cs.onSurfaceVariant, cs.surfaceVariant) >= hintRatio,
                )
            }
        }
    }

    @Test
    fun `a light theme dimmed past the crossover flips its writing pale`() {
        // The concrete case in the report: Rose Quartz (or Light, or Paper)
        // with the slider anywhere near the top is a DARK screen, and dark
        // writing on it is the defect.
        for (theme in lightThemes) {
            val bright = theme.colorScheme(backgroundDim = 0f)
            val dimmed = theme.colorScheme(backgroundDim = 0.6f)
            assertTrue("${theme.name} undimmed is not light", bright.background.luminance() > 0.5f)
            assertTrue("${theme.name} at 60% dim is not dark", dimmed.background.luminance() < 0.5f)
            assertTrue("${theme.name} undimmed writes dark", bright.onSurface.luminance() < 0.5f)
            assertTrue(
                "${theme.name} at 60% dim still writes dark (${dimmed.onSurface}) on a dark surface",
                dimmed.onSurface.luminance() > 0.5f,
            )
        }
    }

    @Test
    fun `the font-colour swatches unlock once the dim has darkened a light theme`() {
        // Undimmed, FontColorThemeTest proves the gate rejects every curated
        // swatch on these themes and the picker greys the whole row out. That
        // is right on a near-white surface and absurd on the near-black one
        // the same theme paints at 60% dim, which is what the report found.
        // The gate itself is unchanged - only what it measures against - so
        // pyrite gold (the one swatch that is genuinely middling) still fails
        // to clear LIGHT_CONTRAST_MIN; the pale majority now passes.
        for (theme in lightThemes) {
            assertEquals(
                "${theme.name} greys out every swatch undimmed",
                0,
                swatches.count { theme.fontColorActive(it, backgroundDim = 0f) },
            )
            val usable = swatches.count { theme.fontColorActive(it, backgroundDim = 0.6f) }
            assertTrue("${theme.name} offers only $usable swatches at 60% dim", usable >= 4)
            assertTrue("${theme.name} rejects White on a near-black surface", theme.fontColorActive(white, 0.6f))
            assertEquals(
                Color(white),
                theme.colorScheme(backgroundDim = 0.6f, fontColorArgb = white).onSurface,
            )
        }
    }

    @Test
    fun `the picker's greying and what the scheme paints agree at every dim`() {
        // fontColorActive is what LookSettings greys a swatch out with. The
        // two reading the dim differently is how the picker came to disable
        // the only usable swatches on the screen; they must not be able to
        // disagree, at any setting.
        for (theme in AppTheme.entries) {
            for (dim in dims) {
                for (argb in swatches) {
                    val painted = theme.colorScheme(backgroundDim = dim, fontColorArgb = argb).onSurface == Color(argb)
                    assertEquals(
                        "${theme.name} dim $dim #${Integer.toHexString(argb)}",
                        theme.fontColorActive(argb, dim),
                        painted,
                    )
                }
            }
        }
    }

    @Test
    fun `the dim never changes a dark theme's answer`() {
        // Dark themes accept every override at every setting; the parameter
        // must be inert for them rather than quietly gating something.
        for (theme in AppTheme.entries.filterNot { it.isLight }) {
            for (dim in dims) {
                for (argb in swatches) {
                    assertTrue("${theme.name} dim $dim", theme.fontColorActive(argb, dim))
                }
            }
        }
    }

    @Test
    fun `the default argument leaves every existing caller on the undimmed answer`() {
        // The gate grew a parameter; call sites that do not pass one must
        // behave exactly as before.
        for (theme in AppTheme.entries) {
            for (argb in swatches) {
                assertEquals(theme.name, theme.fontColorActive(argb, 0f), theme.fontColorActive(argb))
                assertEquals(theme.name, theme.resolvedFontColor(argb, 0f), theme.resolvedFontColor(argb))
            }
        }
    }
}
