package dev.musicviz

import dev.musicviz.ui.OnyxBandKind
import dev.musicviz.ui.malachiteRingRadii
import dev.musicviz.ui.onyxBandRhythm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure layout math behind the procedural mineral textures. The drawing
 * itself is visual, but the ring geometry and band rhythm carry the realism
 * rules from the texture brief, so those are pinned here: deterministic
 * output, brief-mandated size ranges, and the onyx thin-thick alternation.
 */
class MineralTextureMathTest {
    private val minDim = 1080f

    @Test
    fun malachiteRingsAreDeterministic() {
        assertEquals(malachiteRingRadii(2.7f, minDim), malachiteRingRadii(2.7f, minDim))
    }

    @Test
    fun malachiteRingsFollowTheBriefGeometry() {
        for (eye in 0 until 8) {
            val radii = malachiteRingRadii(eye * 3.7f + 1.3f, minDim)
            assertTrue("ring count 6-9, was ${radii.size}", radii.size in 6..9)
            assertTrue(
                "innermost radius 1.5-3% of minDim, was ${radii.first() / minDim}",
                radii.first() in minDim * 0.0149f..minDim * 0.0301f,
            )
            radii.zipWithNext { a, b ->
                val ratio = b / a
                assertTrue("geometric growth x1.3-1.6, was $ratio", ratio in 1.2999f..1.6001f)
            }
        }
    }

    @Test
    fun malachiteRingCountRespectsThePanelCap() {
        for (eye in 0 until 8) {
            assertTrue(malachiteRingRadii(eye * 3.7f + 1.3f, minDim, maxRings = 5).size <= 5)
        }
    }

    @Test
    fun onyxRhythmIsDeterministic() {
        assertEquals(onyxBandRhythm(3.1f, 800f, minDim, 3), onyxBandRhythm(3.1f, 800f, minDim, 3))
    }

    @Test
    fun onyxBandsStayInsideTheSpanAndRunStrictlyOutward() {
        val span = 700f
        val bands = onyxBandRhythm(3.1f, span, minDim, 3)
        assertTrue(bands.isNotEmpty())
        var last = 0f
        bands.forEach { band ->
            assertTrue("band inside span", band.offset - band.width / 2f >= -0.001f)
            assertTrue("band inside span", band.offset + band.width / 2f <= span + 0.001f)
            assertTrue("ordered outward", band.offset >= last)
            last = band.offset
            assertTrue("alpha bounded", band.alpha in 0.25f..0.85f)
        }
    }

    @Test
    fun onyxRhythmIsBroadThenHairlineTrioThenMedium() {
        // A huge span so no unit is clipped; the warm honey band is optional
        // and excluded from the fixed skeleton.
        val kinds =
            onyxBandRhythm(seed = 3.1f, span = 100000f, minDim = minDim, units = 2)
                .map { it.kind }
                .filterNot { it == OnyxBandKind.WARM }
        val unit =
            listOf(
                OnyxBandKind.BROAD,
                OnyxBandKind.HAIRLINE,
                OnyxBandKind.HAIRLINE,
                OnyxBandKind.HAIRLINE,
                OnyxBandKind.MEDIUM,
            )
        assertEquals(unit + unit, kinds)
    }

    @Test
    fun onyxBandWidthsMatchTheBriefRanges() {
        for (seed in listOf(3.1f, 7.7f, 11.3f)) {
            onyxBandRhythm(seed, 100000f, minDim, 3).forEach { band ->
                val pct = band.width / minDim
                when (band.kind) {
                    OnyxBandKind.BROAD -> assertTrue("broad 4-7%, was $pct", pct in 0.0399f..0.0701f)
                    OnyxBandKind.HAIRLINE -> assertTrue("hairline 0.2-0.6%, was $pct", pct in 0.00199f..0.00601f)
                    OnyxBandKind.MEDIUM -> assertTrue("medium 1.5-3%, was $pct", pct in 0.01499f..0.03001f)
                    OnyxBandKind.WARM -> assertTrue("warm 2%, was $pct", pct in 0.0199f..0.0201f)
                }
            }
        }
    }
}
