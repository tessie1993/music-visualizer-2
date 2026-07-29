package dev.musicviz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// One-shot timeline (~1.4 s total): rings ripple outward around the wordmark,
// then the whole overlay fades to reveal the app underneath.
private const val RING_COUNT = 3
private const val RING_STAGGER_MS = 160
private const val RING_TRAVEL_MS = 900
private const val TEXT_IN_MS = 450
private const val FADE_START_MS = 1100L
private const val FADE_OUT_MS = 300

/**
 * Boot intro overlay, shown once per process start on top of the app shell.
 * Pure-Compose animation: the system splash (same #05060B background) hands
 * off to this ripple-rings + "MusicViz" wordmark motif, which then fades out
 * and calls [onDone]. Tapping anywhere skips immediately.
 */
@Composable
fun BootIntro(onDone: () -> Unit) {
    val overlayAlpha = remember { Animatable(1f) }
    val textAlpha = remember { Animatable(0f) }
    val textScale = remember { Animatable(0.7f) }
    // One Animatable per ring: 0 = not started, 1 = fully expanded/faded.
    val rings = remember { List(RING_COUNT) { Animatable(0f) } }

    LaunchedEffect(Unit) {
        launch { textAlpha.animateTo(1f, tween(TEXT_IN_MS, easing = LinearOutSlowInEasing)) }
        launch { textScale.animateTo(1f, tween(TEXT_IN_MS + 100, easing = FastOutSlowInEasing)) }
        rings.forEachIndexed { i, ring ->
            launch {
                delay(i * RING_STAGGER_MS.toLong())
                ring.animateTo(1f, tween(RING_TRAVEL_MS, easing = FastOutSlowInEasing))
            }
        }
        delay(FADE_START_MS)
        overlayAlpha.animateTo(0f, tween(FADE_OUT_MS))
        onDone()
    }

    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            // Matches windowSplashScreenBackground (MIDNIGHT background) so
            // the system splash hands off without a visible seam.
            .background(Color(0xFF05060B))
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val startRadius = min(size.width, size.height) * 0.16f
            val endRadius = max(size.width, size.height) * 0.72f
            val strokeWidth = 2.dp.toPx()
            rings.forEach { ring ->
                val p = ring.value
                if (p > 0f && p < 1f) {
                    drawCircle(
                        color = primary.copy(alpha = (1f - p) * 0.45f),
                        radius = startRadius + (endRadius - startRadius) * p,
                        style = Stroke(width = strokeWidth),
                    )
                }
            }
        }
        Text(
            "MusicViz",
            color = primary,
            style = MaterialTheme.typography.headlineLarge,
            modifier =
                Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    scaleX = textScale.value
                    scaleY = textScale.value
                },
        )
    }
}
