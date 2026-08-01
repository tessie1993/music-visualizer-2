package dev.musicviz.render.scene

/**
 * Pinch and twist on the visualizer canvas, as changes to the parameters the
 * Customize panel already owns.
 *
 * Deliberately NOT a separate view transform. Zoom and Rotation are sliders,
 * they are saved into presets and takes, and they are what every scene already
 * reads - a gesture that moved some other, invisible transform would drift
 * away from the sliders the moment either was touched, and would vanish from
 * anything exported. Pinching here is the same act as dragging the Zoom
 * slider, just with two fingers.
 *
 * Pure so the mapping - especially how much twist is how much rotation - can
 * be pinned without a touchscreen.
 */
object TouchTransform {
    /** The Zoom slider's own range (CustomizeTabs "Zoom"). */
    const val ZOOM_MIN = 0.3f
    const val ZOOM_MAX = 3f

    /** The Rotation slider's own range (CustomizeTabs "Rotation"). */
    const val ROTATION_MIN = -3f
    const val ROTATION_MAX = 3f

    /**
     * Rotation-slider units added per degree of twist.
     *
     * Rotation in this app is a SPEED, not an angle: every scene integrates
     * `rotationAngle += rotation * dt`. So a twist cannot map onto it
     * one-to-one - there is no angle to set. It instead pushes the speed, and
     * this constant sets how hard: a quarter turn of the wrist (90 degrees)
     * moves the slider by about 1.1 of its 6 units, which is a clear change of
     * pace from one comfortable gesture without crossing the whole range.
     */
    const val ROTATION_PER_DEGREE = 0.012f

    /**
     * A pinch by [zoomFactor] (1 = no change) applied to the Zoom slider.
     * Multiplicative, so the gesture feels the same at every zoom level, and
     * clamped to the slider's own travel so a gesture can never leave the
     * value somewhere the slider cannot reach.
     */
    fun zoom(
        current: Float,
        zoomFactor: Float,
    ): Float {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f) return current
        return (current * zoomFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /** A twist of [degrees] applied to the Rotation slider (a speed). */
    fun rotation(
        current: Float,
        degrees: Float,
    ): Float {
        if (!degrees.isFinite()) return current
        return (current + degrees * ROTATION_PER_DEGREE).coerceIn(ROTATION_MIN, ROTATION_MAX)
    }

    /**
     * Smallest pinch that counts as a pinch (2% of the current zoom).
     *
     * Two fingers dragging together never hold their separation to the exact
     * pixel, so the measured zoom arrives as 1.0001-ish rather than 1. An
     * exact `!= 1f` test called that a pinch and routed the frame to the
     * transform branch, which is why a two-finger drag could never smear: the
     * smear branch is the `else`, and floating-point noise took every frame
     * before it. Same story for the twist below.
     */
    const val ZOOM_DEADZONE = 0.02f

    /** Smallest twist that counts as a twist, in degrees. */
    const val ROTATION_DEADZONE = 1.5f

    /**
     * True when a gesture frame carries a real pinch or twist - as opposed to
     * the measurement noise any multi-finger drag produces.
     *
     * A frame that fails this test is a DRAG, and belongs to whatever the
     * caller does with drags (here: the touch smear). Non-finite input is not
     * a transform: a NaN would otherwise pass `!= 1f` and swallow the drag.
     */
    fun isTransform(
        zoomFactor: Float,
        degrees: Float,
    ): Boolean {
        if (!zoomFactor.isFinite() || !degrees.isFinite()) return false
        val pinched = zoomFactor > 0f && kotlin.math.abs(zoomFactor - 1f) > ZOOM_DEADZONE
        return pinched || kotlin.math.abs(degrees) > ROTATION_DEADZONE
    }
}
