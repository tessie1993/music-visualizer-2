package dev.geode.ui

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.geode.render.VisualizerView

/**
 * Sending the visuals to a TV, projector or wireless display while the phone
 * stays the control surface.
 *
 * The canvas is MOVED, not duplicated. A second display could be given its own
 * `VisualizerView`, but that means a second GL context, a second copy of every
 * scene's buffers, and two renderers that have to be kept in step - two
 * sources of truth for one look, on a phone GPU that is already the
 * bottleneck. Moving the one view means the external display shows exactly
 * what the app is rendering, by construction, at no extra cost.
 *
 * Losing the canvas on the phone is the point rather than a compromise: with
 * the visuals on the big screen, the phone is free to be a control surface,
 * which is what a second screen is for.
 */
@Composable
fun rememberExternalDisplay(): Display? {
    val context = LocalContext.current
    var display by remember { mutableStateOf(firstPresentationDisplay(context)) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    display = firstPresentationDisplay(context)
                }

                override fun onDisplayRemoved(displayId: Int) {
                    display = firstPresentationDisplay(context)
                }

                override fun onDisplayChanged(displayId: Int) {
                    display = firstPresentationDisplay(context)
                }
            }
        manager?.registerDisplayListener(listener, null)
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return display
}

/**
 * The display the system nominates for presentations - an HDMI screen, a
 * projector, an active Cast session - or null when there is only the phone.
 */
private fun firstPresentationDisplay(context: Context): Display? =
    context
        .getSystemService(DisplayManager::class.java)
        ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        ?.firstOrNull()

/**
 * Shows [view] fullscreen on [display] for as long as this effect is in the
 * composition, and gives the view back when it leaves.
 *
 * Detaching from the previous parent first is what makes the move safe: a
 * View has one parent, and the in-app canvas host does the same thing on the
 * way back, so ownership passes cleanly in both directions. The GL surface is
 * destroyed and recreated by the move, which the renderer already handles -
 * it rebuilds every resource in `onSurfaceCreated` by design, because the EGL
 * context is deliberately not preserved across pauses either.
 */
@Composable
fun SecondScreenCanvas(
    display: Display,
    view: VisualizerView,
) {
    val context = LocalContext.current
    DisposableEffect(display, view) {
        val presentation =
            object : Presentation(context, display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    (view.parent as? ViewGroup)?.removeView(view)
                    setContentView(view)
                }
            }
        // A presentation on a display that vanishes mid-show (cable pulled,
        // Cast dropped) throws from show(); the canvas comes back to the phone
        // rather than taking the app down.
        val shown = runCatching { presentation.show() }.isSuccess
        onDispose {
            if (shown) runCatching { presentation.dismiss() }
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }
}
