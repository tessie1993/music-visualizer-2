package dev.musicviz

import dev.musicviz.ui.ColorDerive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless coverage for the theme color-derivation math: lerp endpoints and
 * clamping, saturation-scale identity at 1f, and dim identity at 0f. These
 * are the invariants the Appearance sliders rely on (100% accent / 0% dim
 * must reproduce the untouched theme exactly).
 */
class ColorDeriveTest {
    private val samples =
        intArrayOf(
            0xFF7C9CFF.toInt(),
            0xFF00E5FF.toInt(),
            0xFFFF3DDA.toInt(),
            0xFF05060B.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFFE0E0E0.toInt(),
        )

    @Test
    fun lerpEndpointsAreExact() {
        for (a in samples) {
            for (b in samples) {
                assertEquals(a, ColorDerive.lerpArgb(a, b, 0f))
                assertEquals(b, ColorDerive.lerpArgb(a, b, 1f))
            }
        }
    }

    @Test
    fun lerpClampsOutOfRangeT() {
        val a = samples[0]
        val b = samples[1]
        assertEquals(a, ColorDerive.lerpArgb(a, b, -3f))
        assertEquals(b, ColorDerive.lerpArgb(a, b, 7f))
    }

    @Test
    fun lerpMidpointStaysBetweenChannels() {
        val mid = ColorDerive.lerpArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)
        for (shift in intArrayOf(0, 8, 16)) {
            val c = (mid ushr shift) and 0xFF
            assertTrue("channel $c not mid-gray", c in 127..128)
        }
        assertEquals(0xFF, (mid ushr 24) and 0xFF)
    }

    @Test
    fun saturationScaleIsIdentityAtOne() {
        for (c in samples) {
            assertEquals(c, ColorDerive.scaleSaturation(c, 1f))
        }
    }

    @Test
    fun saturationScaleZeroIsGray() {
        val gray = ColorDerive.scaleSaturation(0xFFFF3DDA.toInt(), 0f)
        val r = (gray ushr 16) and 0xFF
        val g = (gray ushr 8) and 0xFF
        val b = gray and 0xFF
        assertTrue("expected r==g==b, got $r $g $b", r == g && g == b)
    }

    @Test
    fun saturationBoostStaysInRange() {
        for (c in samples) {
            val boosted = ColorDerive.scaleSaturation(c, 1.5f)
            assertEquals(0xFF, (boosted ushr 24) and 0xFF)
        }
    }

    @Test
    fun dimZeroIsIdentity() {
        for (c in samples) {
            assertEquals(c, ColorDerive.dim(c, 0f))
        }
    }

    @Test
    fun dimDarkensEveryChannelAndKeepsAlpha() {
        val c = 0xFF7C9CFF.toInt()
        val d = ColorDerive.dim(c, 0.5f)
        assertEquals(0xFF, (d ushr 24) and 0xFF)
        for (shift in intArrayOf(0, 8, 16)) {
            val orig = (c ushr shift) and 0xFF
            val dimmed = (d ushr shift) and 0xFF
            assertTrue("channel not darkened", dimmed < orig)
        }
    }
}
