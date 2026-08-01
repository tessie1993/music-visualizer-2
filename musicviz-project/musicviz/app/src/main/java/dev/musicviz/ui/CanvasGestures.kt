package dev.musicviz.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.musicviz.render.scene.TouchTransform

/**
 * Every touch the visualizer canvas understands, in ONE gesture loop: tap to
 * toggle the chrome, drag to smear, pinch/twist to drive Zoom and Rotation.
 *
 * One loop is the whole point. These used to be two stacked `pointerInput`
 * modifiers - `detectTapGestures` for the tap and `detectTransformGestures`
 * for the rest - and the two fight over the same pointers:
 * `detectTapGestures` consumes the initial DOWN, and `detectTransformGestures`
 * abandons a gesture the moment it sees a consumed change. Which one won
 * depended on modifier order rather than on what the fingers did, and the
 * smear was on the losing side. Detecting all three here means nothing has to
 * consume anything until we already know which gesture this is.
 *
 * The tap fires only when the fingers never travelled past touch slop, so a
 * drag no longer also toggles the controls on lift.
 */
@Composable
fun Modifier.canvasGestures(
    /** Drag pushes the visuals around. */
    smear: Boolean,
    /** Pinch/twist drive the Zoom and Rotation sliders. */
    transform: Boolean,
    /** Tap the canvas (no drag) - null disables tap handling entirely. */
    onTap: (() -> Unit)? = null,
    /** A real pinch/twist frame: (zoomFactor, degrees). */
    onTransform: (Float, Float) -> Unit = { _, _ -> },
    /**
     * One frame of a drag, normalized to the canvas (0..1, y DOWN as the UI
     * reports it). The renderer converts to sim space on the GL thread.
     */
    onSmear: (nx: Float, ny: Float, ndx: Float, ndy: Float) -> Unit = { _, _, _, _ -> },
): Modifier {
    // The gesture loop outlives recomposition - `pointerInput` only restarts
    // when its keys change - so it must not close over this frame's lambdas.
    // Without this, moving the Smear strength slider left the loop calling the
    // callback built when the loop started, i.e. with the old strength.
    val tap by rememberUpdatedState(onTap)
    val transformed by rememberUpdatedState(onTransform)
    val smeared by rememberUpdatedState(onSmear)
    return pointerInput(smear, transform, onTap == null) {
        val slop = viewConfiguration.touchSlop
        val w = size.width.toFloat().coerceAtLeast(1f)
        val h = size.height.toFloat().coerceAtLeast(1f)
        awaitEachGesture {
            // requireUnconsumed = false: a tap detector elsewhere in the tree
            // may already have claimed the down, and that must not cost us
            // the drag.
            awaitFirstDown(requireUnconsumed = false)
            var travel = 0f
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                val pan = event.calculatePan()
                if (!dragging) {
                    // Slop is measured on total travel, not per frame: a slow
                    // drag moves a pixel at a time and would otherwise never
                    // clear the threshold.
                    travel += pan.getDistance()
                    if (travel > slop) dragging = true
                }
                if (!dragging) continue
                val zoom = event.calculateZoom()
                val rotation = event.calculateRotation()
                if (transform && TouchTransform.isTransform(zoom, rotation)) {
                    transformed(zoom, rotation)
                } else if (smear) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    smeared(centroid.x / w, centroid.y / h, pan.x / w, pan.y / h)
                } else {
                    // Neither gesture is switched on: leave the changes
                    // unconsumed so anything underneath still gets them.
                    continue
                }
                // Consumed only once we have acted on it, so a gesture we
                // ignore stays available to other handlers.
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (!dragging) tap?.invoke()
        }
    }
}
