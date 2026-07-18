package dev.musicviz.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

class BandSmootherTest {
    @Test
    fun `attack rises faster than decay falls`() {
        val smoother = BandSmoother(1, attack = 0.6f, decay = 0.1f)
        val out = FloatArray(1)
        smoother.apply(floatArrayOf(1f), out)
        val afterAttack = out[0]
        assertTrue("attack step should be large", afterAttack > 0.5f)
        smoother.apply(floatArrayOf(0f), out)
        val drop = afterAttack - out[0]
        assertTrue("decay step should be smaller than attack step", drop < afterAttack * 0.2f)
    }

    @Test
    fun `converges toward input`() {
        val smoother = BandSmoother(1, attack = 0.5f, decay = 0.5f)
        val out = FloatArray(1)
        repeat(50) { smoother.apply(floatArrayOf(0.8f), out) }
        assertTrue(kotlin.math.abs(out[0] - 0.8f) < 0.01f)
    }
}
