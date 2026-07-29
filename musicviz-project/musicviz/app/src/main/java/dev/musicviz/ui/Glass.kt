package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Glass" chrome helpers: translucent panels whose opacity follows the
 * Settings "Bar opacity" slider ([GuiPrefs.barOpacity]). This is flat
 * alpha glass by design - no backdrop blur (RenderEffect cannot blur the
 * content BEHIND a composable here), and behind the main shell screens
 * there is no live visualizer anyway, so the glass reveals the theme
 * background color. Only Now Playing hosts the GL canvas in v1.
 */

/** Hairline stroke alpha: slightly more opaque than the panel fill. */
internal fun glassBorderAlpha(opacity: Float): Float = (opacity.coerceIn(0f, 1f) + 0.15f).coerceAtMost(1f)

/**
 * Translucent panel: [color] at [opacity] plus a subtle hairline top
 * border so the glass edge reads against whatever sits behind it.
 * [corner] > 0 rounds the panel (border clipped to the shape).
 */
fun Modifier.glassPanel(
    opacity: Float,
    color: Color,
    corner: Dp = 0.dp,
): Modifier {
    val alpha = opacity.coerceIn(0f, 1f)
    val shape: Shape = if (corner > 0.dp) RoundedCornerShape(corner) else RectangleShape
    return this
        .clip(shape)
        .background(color.copy(alpha = alpha))
        .drawBehind {
            // Default stroke width = hairline.
            drawLine(
                color = color.copy(alpha = glassBorderAlpha(alpha)),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
            )
        }
}

/**
 * Vertical scrim (transparent at the top, [color] at [maxAlpha] at the
 * bottom) for text/control readability over bright visuals - use under
 * bottom-anchored glass chrome when the panel opacity can be low.
 */
fun Modifier.glassScrim(
    color: Color = Color.Black,
    maxAlpha: Float = 0.35f,
): Modifier =
    background(
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to color.copy(alpha = maxAlpha.coerceIn(0f, 1f)),
        ),
    )
