package dev.geode

import dev.geode.render.scene.TouchTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for pinch-and-twist on the canvas.
 *
 * The gesture writes to the Zoom and Rotation SLIDERS rather than to a private
 * view transform, so the two ranges here are the sliders' own: a gesture must
 * never leave a value somewhere the slider cannot show or undo.
 */
class TouchTransformTest {
    @Test
    fun pinchingScalesZoomMultiplicatively() {
        // Multiplicative so the gesture feels the same however far in you are:
        // a given pinch doubles what is on screen, wherever it started.
        assertEquals(2f, TouchTransform.zoom(1f, 2f), 1e-5f)
        assertEquals(1f, TouchTransform.zoom(0.5f, 2f), 1e-5f)
        assertEquals(0.5f, TouchTransform.zoom(1f, 0.5f), 1e-5f)
        assertEquals("no pinch must be no change", 1.3f, TouchTransform.zoom(1.3f, 1f), 1e-5f)
    }

    @Test
    fun zoomStaysInsideTheSlidersOwnTravel() {
        assertEquals(TouchTransform.ZOOM_MAX, TouchTransform.zoom(2.9f, 8f), 1e-5f)
        assertEquals(TouchTransform.ZOOM_MIN, TouchTransform.zoom(0.4f, 0.01f), 1e-5f)
        // Repeated pinching must settle at the rail, not creep past it.
        var z = 1f
        repeat(50) { z = TouchTransform.zoom(z, 1.3f) }
        assertEquals(TouchTransform.ZOOM_MAX, z, 1e-5f)
    }

    @Test
    fun twistingPushesRotationSpeedInTheDirectionOfTheTwist() {
        // Rotation is a SPEED here - every scene integrates it - so a twist
        // cannot set an angle; it changes the pace, and the sign has to follow
        // the wrist.
        assertTrue(TouchTransform.rotation(0f, 45f) > 0f)
        assertTrue(TouchTransform.rotation(0f, -45f) < 0f)
        assertEquals("no twist must be no change", 0.7f, TouchTransform.rotation(0.7f, 0f), 1e-5f)
    }

    @Test
    fun aQuarterTurnIsAClearButNotTotalChangeOfPace() {
        // The gesture has to be worth making and not overwhelming: one
        // comfortable wrist turn should move the slider noticeably without
        // crossing its whole range.
        val span = TouchTransform.ROTATION_MAX - TouchTransform.ROTATION_MIN
        val quarterTurn = TouchTransform.rotation(0f, 90f)
        assertTrue("a quarter turn barely moves it ($quarterTurn)", quarterTurn > span * 0.1f)
        assertTrue("a quarter turn crosses too much of the range ($quarterTurn)", quarterTurn < span * 0.3f)
    }

    @Test
    fun rotationStaysInsideTheSlidersOwnTravel() {
        assertEquals(TouchTransform.ROTATION_MAX, TouchTransform.rotation(2.9f, 720f), 1e-5f)
        assertEquals(TouchTransform.ROTATION_MIN, TouchTransform.rotation(-2.9f, -720f), 1e-5f)
    }

    @Test
    fun aFrameWithNoPinchOrTwistIsNotATransform() {
        // This is what routes a one-finger drag to the smear instead: the same
        // detector serves both, and a pan with no pinch is not a transform.
        assertFalse(TouchTransform.isTransform(1f, 0f))
        assertTrue(TouchTransform.isTransform(1.02f, 0f))
        assertTrue(TouchTransform.isTransform(1f, 0.5f))
    }

    @Test
    fun degenerateGestureValuesLeaveTheParametersAlone() {
        // A pointer stream can produce a zero or NaN scale on the frame a
        // finger lands or lifts; that must not blank the user's zoom.
        assertEquals(1.5f, TouchTransform.zoom(1.5f, 0f), 1e-5f)
        assertEquals(1.5f, TouchTransform.zoom(1.5f, Float.NaN), 1e-5f)
        assertEquals(1.5f, TouchTransform.zoom(1.5f, -1f), 1e-5f)
        assertEquals(0.5f, TouchTransform.rotation(0.5f, Float.NaN), 1e-5f)
    }
}
