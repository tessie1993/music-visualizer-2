package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * "Glass" chrome helpers: translucent panels whose opacity follows the
 * Settings "Bar opacity" slider (GuiPrefs.barOpacity). This is flat
 * alpha glass by design - no backdrop blur (RenderEffect cannot blur the
 * content BEHIND a composable here), and behind the main shell screens
 * there is no live visualizer anyway, so the glass reveals the theme
 * background color. Only Now Playing hosts the GL canvas in v1.
 */

/** Hairline stroke alpha: slightly more opaque than the panel fill. */
internal fun glassBorderAlpha(opacity: Float): Float = (opacity.coerceIn(0f, 1f) + 0.15f).coerceAtMost(1f)

/**
 * Translucent crystal panel: [color] at [opacity] as a top-lit vertical
 * gradient, plus a luminous [glow]-tinted gradient border so the glass edge
 * reads against whatever sits behind it (brightest along the top, like the
 * mockups' "luminous stroke" material). [corner] > 0 rounds the panel.
 */
fun Modifier.glassPanel(
    opacity: Float,
    color: Color,
    corner: Dp = 0.dp,
    glow: Color? = null,
): Modifier {
    val alpha = opacity.coerceIn(0f, 1f)
    val shape: Shape = if (corner > 0.dp) RoundedCornerShape(corner) else RectangleShape
    val edge = glow ?: color
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                0f to lerp(color, edge, 0.15f).copy(alpha = (alpha + 0.06f).coerceAtMost(1f)),
                1f to lerp(color, Color.Black, 0.2f).copy(alpha = alpha),
            ),
        ).border(
            1.dp,
            Brush.verticalGradient(
                0f to edge.copy(alpha = glassBorderAlpha(alpha)),
                0.6f to edge.copy(alpha = glassBorderAlpha(alpha) * 0.3f),
                1f to edge.copy(alpha = glassBorderAlpha(alpha) * 0.55f),
            ),
            shape,
        )
}

/**
 * Semi-transparent reading plate for chrome that sits ON the live visualizer
 * (the clear-overlay Visuals menu).
 *
 * The clear overlay used to be a flat 28% black wash over the whole screen:
 * enough to grey the visuals down, never enough to make small label text on a
 * moving bright scene comfortable to read. This is the other trade - the
 * visuals stay bright and legible THROUGH the plate, while the text has a
 * consistent surface behind it instead of whatever colour the animation
 * happens to be under a given word.
 *
 * [opacity] comes from the Settings "Bar opacity" slider, so the one control
 * that governs how see-through the app's chrome is governs this too. The
 * gradient is deliberately stronger at the top and bottom edges, where the
 * header and the scrolling list's ends sit, and thinnest across the middle
 * where the visuals are worth looking at.
 */
fun Modifier.readingPlate(
    opacity: Float,
    tint: Color,
    corner: Dp = 0.dp,
): Modifier {
    val a = opacity.coerceIn(0f, 0.92f)
    // Zero means zero: the gradient below lifts the top and bottom edges above
    // `a`, so without this an "off" plate still left two visible bands.
    if (a <= 0f) return this
    val shape: Shape = if (corner > 0.dp) RoundedCornerShape(corner) else RectangleShape
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                0f to lerp(tint, Color.Black, 0.4f).copy(alpha = (a + 0.14f).coerceAtMost(0.94f)),
                0.14f to tint.copy(alpha = a),
                0.86f to tint.copy(alpha = a),
                1f to lerp(tint, Color.Black, 0.4f).copy(alpha = (a + 0.1f).coerceAtMost(0.94f)),
            ),
        )
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
