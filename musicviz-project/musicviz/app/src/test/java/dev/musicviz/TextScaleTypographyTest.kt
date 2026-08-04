package dev.musicviz

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import dev.musicviz.ui.GuiPrefs
import dev.musicviz.ui.crystalTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Appearance "Text size" option: [crystalTypography]'s textScale must
 * multiply EVERY font size (a style that misses the factor would visibly
 * refuse to grow), keep line heights in step so multi-line text cannot
 * collide, and clamp out-of-range factors to the GuiPrefs bounds.
 */
class TextScaleTypographyTest {
    private fun styles(t: Typography): Map<String, TextStyle> =
        mapOf(
            "displayLarge" to t.displayLarge,
            "displayMedium" to t.displayMedium,
            "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge,
            "headlineMedium" to t.headlineMedium,
            "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium,
            "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge,
            "bodyMedium" to t.bodyMedium,
            "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge,
            "labelMedium" to t.labelMedium,
            "labelSmall" to t.labelSmall,
        )

    @Test
    fun defaultScaleIsIdentity() {
        val base = styles(crystalTypography())
        val one = styles(crystalTypography(1f))
        for ((name, style) in one) {
            assertEquals(name, base.getValue(name).fontSize, style.fontSize)
            assertEquals(name, base.getValue(name).lineHeight, style.lineHeight)
        }
    }

    @Test
    fun scaleMultipliesEveryFontSizeAndLineHeight() {
        val scale = 1.2f
        val base = styles(crystalTypography())
        val scaled = styles(crystalTypography(scale))
        for ((name, style) in scaled) {
            val b = base.getValue(name)
            assertTrue("$name fontSize must be sp", style.fontSize.isSp)
            assertEquals(name, b.fontSize.value * scale, style.fontSize.value, 1e-4f)
            if (b.lineHeight.isSp) {
                assertEquals(name, b.lineHeight.value * scale, style.lineHeight.value, 1e-4f)
            }
        }
    }

    @Test
    fun outOfRangeScalesAreClampedToTheGuiPrefsBounds() {
        val tooBig = styles(crystalTypography(9f))
        val max = styles(crystalTypography(GuiPrefs.TEXT_SCALE_MAX))
        val tooSmall = styles(crystalTypography(0.1f))
        val min = styles(crystalTypography(GuiPrefs.TEXT_SCALE_MIN))
        for (name in tooBig.keys) {
            assertEquals(name, max.getValue(name).fontSize, tooBig.getValue(name).fontSize)
            assertEquals(name, min.getValue(name).fontSize, tooSmall.getValue(name).fontSize)
        }
    }

    @Test
    fun scalingNeverChangesFamilyWeightOrTracking() {
        val base = styles(crystalTypography())
        val scaled = styles(crystalTypography(1.25f))
        for ((name, style) in scaled) {
            val b = base.getValue(name)
            assertEquals(name, b.fontFamily, style.fontFamily)
            assertEquals(name, b.fontWeight, style.fontWeight)
            assertEquals(name, b.letterSpacing, style.letterSpacing)
        }
    }
}
