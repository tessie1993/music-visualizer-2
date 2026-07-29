package dev.musicviz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The crystal texture that sits behind every shell screen, per the theme
 * mockups: a vertical depth gradient, two soft radial glows (primary top
 * left, theme glow bottom right), faint mineral veins and sparkle flecks
 * in the theme's vein color (pyrite gold on Lapis, jade on Malachite...).
 * Geometry comes from [CrystalMath] seeded by the theme name so each theme
 * has its own stable stone pattern; drawing is a single static Canvas pass,
 * no animation, so it costs nothing after composition.
 */
@Composable
fun CrystalBackground(
    theme: AppTheme,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val glow = Color(theme.glowArgb())
    val vein = Color(theme.veinArgb())
    val veins = remember(theme) { CrystalMath.veins(seed = theme.name.hashCode(), count = 7, segments = 14) }
    val flecks = remember(theme) { CrystalMath.flecks(seed = theme.name.hashCode() + 1, count = 90) }
    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val w = size.width
        val h = size.height
        drawRect(
            Brush.verticalGradient(
                0f to scheme.background,
                1f to lerp(scheme.background, scheme.surface, 0.55f),
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(w * 0.18f, h * 0.12f),
                radius = w * 0.95f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.78f),
                radius = w * 0.8f,
            ),
        )
        val veinStroke = Stroke(width = 1.dp.toPx())
        veins.forEach { points ->
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h)
            }
            drawPath(path, vein.copy(alpha = 0.07f), style = veinStroke)
        }
        flecks.forEach { f ->
            drawCircle(
                color = vein.copy(alpha = 0.04f + f.weight * 0.16f),
                radius = (0.4f + f.weight) * 1.2f.dp.toPx(),
                center = Offset(f.x * w, f.y * h),
            )
        }
    }
}
