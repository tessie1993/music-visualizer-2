package dev.musicviz.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.musicviz.render.VisualizerView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Crystal design kit — the shared "luminous crystal glass" language from the
 * MusicViz theme mockups (Lapis, Sugilite, Kyanite, …): editorial serif
 * display type, tracked-caps overlines, panels with gradient glass fills,
 * luminous strokes and a soft outer glow, plus a twinkling nebula backdrop
 * behind every shell screen.
 */

// ------------------------------------------------------------- typography

/**
 * Theme typography per the mockups: serif display/headline ("Display Serif —
 * for headlines & hero moments"), tracked sans labels/overlines for UI.
 */
fun crystalTypography(): Typography {
    val base = Typography()
    val serif = FontFamily.Serif

    fun TextStyle.display(tracking: Float) = copy(fontFamily = serif, fontWeight = FontWeight.Medium, letterSpacing = tracking.sp)
    return Typography(
        displayLarge = base.displayLarge.display(1.5f),
        displayMedium = base.displayMedium.display(1.2f),
        displaySmall = base.displaySmall.display(1f),
        headlineLarge = base.headlineLarge.display(1f),
        headlineMedium = base.headlineMedium.display(0.8f),
        headlineSmall = base.headlineSmall.display(0.6f),
        titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        titleSmall = base.titleSmall.copy(letterSpacing = 0.4.sp),
        labelLarge = base.labelLarge.copy(letterSpacing = 1.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 1.3.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.1.sp),
    )
}

// ------------------------------------------------------------- text pieces

/** Tracked-caps overline label ("RECENTLY PLAYED", "COLOR PALETTE", …). */
@Composable
fun CrystalOverline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text.uppercase(),
        modifier,
        style =
            MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.6.sp,
                fontWeight = FontWeight.Medium,
            ),
        color = color.copy(alpha = 0.9f),
    )
}

/** Serif screen title with a soft accent glow behind the glyphs. */
@Composable
fun GlowTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    glow: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text,
        modifier,
        style = style.copy(shadow = Shadow(color = glow.copy(alpha = 0.8f), blurRadius = 28f)),
        color = MaterialTheme.colorScheme.onBackground,
    )
}

// ------------------------------------------------------------- glow drawing

/**
 * Soft radial bloom behind a roughly-circular control (play buttons, dots).
 * Draws outside the layout bounds so keep it on undipped containers.
 */
fun Modifier.softGlow(
    color: Color,
    radius: Dp = 16.dp,
    strength: Float = 1f,
): Modifier =
    drawBehind {
        val r = max(size.width, size.height) / 2f + radius.toPx()
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to color.copy(alpha = 0.45f * strength),
                    1f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
            radius = r,
            center = center,
        )
    }

/**
 * Crystal glass panel: soft outer glow halo, vertical gradient glass fill
 * (lit from the top like the mockups' "inner glow" material) and a luminous
 * gradient border stroke.
 */
fun Modifier.crystalPanel(
    opacity: Float,
    tint: Color,
    glow: Color,
    corner: Dp = 18.dp,
    glowStrength: Float = 1f,
): Modifier {
    val alpha = opacity.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(corner)
    return this
        .drawBehind {
            // Outer bloom: stacked halo strokes fading with distance.
            val r = corner.toPx()
            for (i in 1..4) {
                val spread = i * i * 1.4f * 1.dp.toPx()
                drawRoundRect(
                    color = glow.copy(alpha = (0.09f / i) * glowStrength),
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = CornerRadius(r + spread, r + spread),
                    style = Stroke(width = spread * 1.3f),
                )
            }
        }.clip(shape)
        .background(
            Brush.verticalGradient(
                0f to lerp(tint, glow, 0.18f).copy(alpha = min(1f, alpha + 0.08f)),
                0.4f to tint.copy(alpha = alpha),
                1f to lerp(tint, Color.Black, 0.28f).copy(alpha = alpha),
            ),
        ).border(
            1.dp,
            Brush.verticalGradient(
                0f to glow.copy(alpha = min(1f, 0.85f * glowStrength)),
                0.55f to glow.copy(alpha = 0.22f * glowStrength),
                1f to glow.copy(alpha = 0.45f * glowStrength),
            ),
            shape,
        )
}

/** Thin luminous divider line (transparent → glow → transparent). */
fun Modifier.luminousHairline(glow: Color): Modifier =
    background(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to glow.copy(alpha = 0.7f),
            1f to Color.Transparent,
        ),
    )

// ------------------------------------------------------------- backdrop

private data class Star(
    val x: Float,
    val y: Float,
    val size: Float,
    val phase: Float,
    val bright: Float,
)

/**
 * Nebula backdrop for shell screens: the theme background color with big
 * soft aurora blobs in primary/secondary plus a slowly twinkling star field —
 * the "crystal texture / caustic light" mood from the mockups, cheap enough
 * to sit behind every tab.
 */
@Composable
fun CrystalBackground(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    // Fixed seed: the constellation is stable across recompositions/tabs.
    val stars =
        remember {
            val rnd = Random(42)
            List(90) {
                Star(
                    x = rnd.nextFloat(),
                    y = rnd.nextFloat(),
                    size = 0.6f + rnd.nextFloat() * 1.7f,
                    phase = rnd.nextFloat() * (2f * PI.toFloat()),
                    bright = 0.3f + rnd.nextFloat() * 0.7f,
                )
            }
        }
    val t by rememberInfiniteTransition(label = "crystal-bg")
        .animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing)),
            label = "twinkle",
        )
    val lightTheme = cs.background.luminance() > 0.5f
    val sparkle = if (lightTheme) cs.primary else Color.White
    Canvas(modifier) {
        drawRect(cs.background)
        val d = max(size.width, size.height)
        // Aurora blobs: one primary up top, secondary low, faint primary mid.
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to cs.primary.copy(alpha = if (lightTheme) 0.10f else 0.20f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.18f, size.height * 0.05f),
                    radius = d * 0.65f,
                ),
            radius = d * 0.65f,
            center = Offset(size.width * 0.18f, size.height * 0.05f),
        )
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to cs.secondary.copy(alpha = if (lightTheme) 0.08f else 0.14f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.92f, size.height * 0.85f),
                    radius = d * 0.6f,
                ),
            radius = d * 0.6f,
            center = Offset(size.width * 0.92f, size.height * 0.85f),
        )
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to cs.primary.copy(alpha = if (lightTheme) 0.05f else 0.09f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.7f, size.height * 0.35f),
                    radius = d * 0.5f,
                ),
            radius = d * 0.5f,
            center = Offset(size.width * 0.7f, size.height * 0.35f),
        )
        stars.forEach { s ->
            val tw = 0.5f + 0.5f * sin(t + s.phase)
            val alpha = (0.04f + 0.22f * s.bright * tw) * (if (lightTheme) 0.6f else 1f)
            drawCircle(
                color = sparkle.copy(alpha = alpha),
                radius = s.size * 1.dp.toPx() * (0.7f + 0.5f * tw),
                center = Offset(s.x * size.width, s.y * size.height),
            )
        }
    }
}

// ------------------------------------------------------------- canvas host

/**
 * Hosts the shared [VisualizerView] in Compose, detaching it from any
 * previous parent first — the single GL view moves between Now Playing and
 * the clear-overlay Visuals hub, and a view can only have one parent.
 */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
    )
}
