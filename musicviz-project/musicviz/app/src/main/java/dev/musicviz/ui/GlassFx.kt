package dev.musicviz.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Crystal-theme press feedback (the design sheets' "motion & feel" notes:
 * subtle glow on focus, smooth transitions, ripple on beat). The element
 * springs down to 94% while pressed and bounces back on release, with a
 * slight dim - the "pressing into glass" animation.
 *
 * Observes presses on the Initial pass WITHOUT consuming, so it stacks on
 * any clickable/button/card without stealing its gesture, and needs no
 * shared InteractionSource.
 */
fun Modifier.pressGlow(): Modifier =
    composed {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.94f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
            label = "pressScale",
        )
        val dim by animateFloatAsState(
            targetValue = if (pressed) 0.85f else 1f,
            animationSpec = tween(durationMillis = 110),
            label = "pressDim",
        )
        this
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }.graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            }
    }
