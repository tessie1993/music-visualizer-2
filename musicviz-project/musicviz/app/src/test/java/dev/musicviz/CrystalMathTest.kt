package dev.musicviz

import dev.musicviz.ui.CrystalMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless gate for the crystal background geometry: the texture must be
 * deterministic per seed (recompositions redraw the identical stone), stay
 * inside the normalized 0..1 canvas, and actually vary between seeds so
 * each theme gets its own veining.
 */
class CrystalMathTest {
    @Test
    fun veinsAreDeterministicPerSeed() {
        val a = CrystalMath.veins(seed = 7, count = 5, segments = 12)
        val b = CrystalMath.veins(seed = 7, count = 5, segments = 12)
        assertEquals(a, b)
    }

    @Test
    fun veinsHaveRequestedShapeAndStayInBounds() {
        val veins = CrystalMath.veins(seed = 42, count = 6, segments = 10)
        assertEquals(6, veins.size)
        veins.forEach { vein ->
            assertEquals(11, vein.size)
            vein.forEach { (x, y) ->
                assertTrue(x in 0f..1f)
                assertTrue(y in 0f..1f)
            }
        }
    }

    @Test
    fun differentSeedsProduceDifferentVeins() {
        assertNotEquals(
            CrystalMath.veins(seed = 1, count = 3, segments = 8),
            CrystalMath.veins(seed = 2, count = 3, segments = 8),
        )
    }

    @Test
    fun flecksAreDeterministicAndBounded() {
        val a = CrystalMath.flecks(seed = 9, count = 40)
        val b = CrystalMath.flecks(seed = 9, count = 40)
        assertEquals(a, b)
        assertEquals(40, a.size)
        a.forEach { f ->
            assertTrue(f.x in 0f..1f)
            assertTrue(f.y in 0f..1f)
            assertTrue(f.weight in 0f..1f)
        }
    }
}
