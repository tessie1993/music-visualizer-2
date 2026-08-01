package dev.musicviz

import dev.musicviz.render.fluid.RippleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Headless gate for the finger-smear input: the drag -> heightfield mapping
 * that lets a touch push the visuals around.
 *
 * A smear has to be DIRECTIONAL. A symmetric drop under the fingertip only
 * rings; it is the crest ahead of the finger and the trough behind it that
 * tilt the surface along the drag, and the refraction pass turns that tilt
 * into the image moving with the finger. These tests pin that asymmetry, the
 * speed response, and the two ways a stroke must not misbehave: a held finger
 * still touching the water, and a strength of zero doing nothing at all.
 */
class TouchSmearTest {
    private val dt = 1f / 60f
    private val radius = 0.11f

    @Test
    fun aDragRaisesTheSurfaceAheadAndDipsItBehind() {
        val drops = RippleMath.strokeDrops(0f, 0f, dx = 0.06f, dy = 0f, dt = dt, radius = radius, strength = 1f)
        assertEquals("a drag is a crest/trough pair", 2, drops.size)
        val (crest, trough) = drops
        assertTrue("the leading drop must raise the surface", crest.amplitude > 0f)
        assertTrue("the trailing drop must dip it", trough.amplitude < 0f)
        assertTrue("the crest must lead the touch point", crest.x > 0f)
        assertTrue("the trough must trail it", trough.x < 0f)
        assertEquals("the pair must straddle the drag axis", 0f, crest.y, 1e-6f)
        assertEquals("the pair must straddle the drag axis", 0f, trough.y, 1e-6f)
    }

    @Test
    fun thePairFollowsTheDragDirection() {
        for ((dx, dy) in listOf(0f to 0.05f, -0.05f to 0f, 0.03f to -0.03f)) {
            val (crest, trough) = RippleMath.strokeDrops(0.2f, -0.1f, dx, dy, dt, radius, 1f)
            // Crest -> trough points opposite the drag, by construction.
            val alongX = crest.x - trough.x
            val alongY = crest.y - trough.y
            assertTrue("the pair must lie along the drag ($dx, $dy)", alongX * dx + alongY * dy > 0f)
        }
    }

    @Test
    fun aFasterFlickPushesHarder() {
        val slow = RippleMath.strokeDrops(0f, 0f, 0.01f, 0f, dt, radius, 1f).first().amplitude
        val fast = RippleMath.strokeDrops(0f, 0f, 0.09f, 0f, dt, radius, 1f).first().amplitude
        assertTrue("a flick must displace more than a crawl ($fast vs $slow)", fast > slow)
        // Bounded, so a fling cannot slam the height rail in one frame.
        assertTrue("a flick must stay inside the height rail ($fast)", fast < RippleMath.MAX_HEIGHT * 0.5f)
    }

    @Test
    fun aHeldFingerStillTouchesTheWater() {
        // No direction to lean into: one dimple under the fingertip, not a
        // dipole - and definitely not nothing, which would read as the feature
        // being broken the moment someone rests a finger on the glass.
        val drops = RippleMath.strokeDrops(0.3f, 0.4f, 0f, 0f, dt, radius, 1f)
        assertEquals(1, drops.size)
        assertTrue(drops[0].amplitude > 0f)
        assertEquals(0.3f, drops[0].x, 1e-6f)
        assertEquals(0.4f, drops[0].y, 1e-6f)
    }

    @Test
    fun theStrengthSettingScalesTheWholeStroke() {
        val soft = RippleMath.strokeDrops(0f, 0f, 0.05f, 0f, dt, radius, 0.4f).first().amplitude
        val hard = RippleMath.strokeDrops(0f, 0f, 0.05f, 0f, dt, radius, 1.6f).first().amplitude
        assertEquals("strength must scale linearly", 4f, hard / soft, 1e-3f)
        assertTrue("strength 0 must be an exact no-op", RippleMath.strokeDrops(0f, 0f, 0.05f, 0f, dt, radius, 0f).isEmpty())
    }

    @Test
    fun theStrokeFootprintFollowsTheRequestedRadius() {
        for (r in floatArrayOf(0.04f, 0.11f, 0.3f)) {
            val drops = RippleMath.strokeDrops(0f, 0f, 0.05f, 0f, dt, r, 1f)
            drops.forEach { assertEquals(r, it.radius, 1e-6f) }
            // The pair separates with the footprint, so a wide touch smears
            // over a wide band rather than pinching two drops together.
            assertEquals(r * 1.2f, abs(drops[0].x - drops[1].x), 1e-5f)
        }
    }

    @Test
    fun aZeroLengthTimestepDoesNotProduceInfinities() {
        // Compose can deliver two pointer events in the same millisecond.
        val drops = RippleMath.strokeDrops(0f, 0f, 0.05f, 0f, dt = 0f, radius = radius, strength = 1f)
        drops.forEach { assertTrue("amplitude ${it.amplitude} is not finite", it.amplitude.isFinite()) }
    }
}
