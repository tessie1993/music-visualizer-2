package dev.musicviz.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Crystal design kit — the shared "luminous crystal glass" language from the
 * MusicViz theme mockups (Lapis, Sugilite, Kyanite, …): editorial serif
 * display type, tracked-caps overlines, panels with gradient glass fills,
 * facet glints, luminous strokes and a soft outer glow, plus a theme-specific procedural mineral backdrop behind every shell screen.
 *
 * Two silhouettes carry the identity everywhere:
 *  - the SHARD: an asymmetrically cut gem profile ([crystalShardShape]) used
 *    by buttons, chips and segmented controls, and
 *  - the GEM: a small rotated-square diamond ([CrystalGem]) used as the
 *    selection marker in nav, tabs, lists and slider thumbs.
 */

// ------------------------------------------------------------- mineral identity

/** The actual mineral structure behind a theme, not merely its accent hue. */
internal enum class CrystalTextureKind {
    LAPIS,
    MALACHITE,
    CLEAR_QUARTZ,
    ROSE_QUARTZ,
    SUGILITE,
    AMETHYST,
    KYANITE,
    ONYX,
    GENERIC,
}

internal fun AppTheme.crystalTextureKind(): CrystalTextureKind =
    when (this) {
        AppTheme.LAPIS -> CrystalTextureKind.LAPIS
        AppTheme.MALACHITE -> CrystalTextureKind.MALACHITE
        AppTheme.CLEAR_QUARTZ -> CrystalTextureKind.CLEAR_QUARTZ
        AppTheme.ROSE_QUARTZ -> CrystalTextureKind.ROSE_QUARTZ
        AppTheme.SUGILITE -> CrystalTextureKind.SUGILITE
        AppTheme.AMETHYST -> CrystalTextureKind.AMETHYST
        AppTheme.KYANITE -> CrystalTextureKind.KYANITE
        AppTheme.ONYX -> CrystalTextureKind.ONYX
        else -> CrystalTextureKind.GENERIC
    }

/** Supplied by AppShell so every panel and backdrop knows the selected stone. */
internal val LocalCrystalTheme = staticCompositionLocalOf { AppTheme.LAPIS }

/**
 * MaterialTheme plus the selected stone identity. Keeping these together
 * prevents panels from silently falling back to Lapis when a screen is moved
 * or reused elsewhere in the shell.
 */
@Composable
internal fun CrystalMaterialTheme(
    appTheme: AppTheme,
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCrystalTheme provides appTheme) {
        MaterialTheme(
            colorScheme = appTheme.colorScheme(gui.accentIntensity, gui.backgroundDim, gui.whiteFont),
            shapes = gui.cornerStyle.shapes(),
            typography = crystalTypography(),
            content = content,
        )
    }
}

/**
 * Theme-specific mineral marks. They are procedural and deterministic, so the
 * app gets real stone character without shipping photographs or redrawing
 * random noise every recomposition.
 */
private fun DrawScope.drawMineralTexture(
    theme: AppTheme,
    primary: Color,
    secondary: Color,
    alpha: Float,
    panel: Boolean,
) {
    if (alpha <= 0f) return
    val w = size.width
    val h = size.height
    val d = max(w, h)
    val density = if (panel) 0.55f else 1f
    when (theme.crystalTextureKind()) {
        CrystalTextureKind.LAPIS -> {
            // White calcite seams crossing an ultramarine body.
            repeat(if (panel) 2 else 4) { i ->
                val y = h * (0.18f + i * 0.21f)
                val path =
                    Path().apply {
                        moveTo(-w * 0.05f, y)
                        cubicTo(w * 0.22f, y - h * 0.12f, w * 0.57f, y + h * 0.10f, w * 1.05f, y - h * 0.04f)
                    }
                drawPath(path, Color.White.copy(alpha = alpha * (0.10f + i * 0.012f)), style = Stroke(width = d * 0.004f))
            }
            // Irregular pyrite flecks, never a uniform star field.
            repeat(if (panel) 11 else 34) { i ->
                val x = fract01(i * 0.7548777f + 0.17f) * w
                val y = fract01(i * 0.5698403f + 0.41f) * h
                val r = (0.55f + fract01(i * 0.314159f) * 1.8f) * density
                drawCircle(secondary.copy(alpha = alpha * 0.32f), r, Offset(x, y))
            }
        }
        CrystalTextureKind.MALACHITE -> {
            val centre = Offset(w * 0.68f, h * 0.38f)
            repeat(if (panel) 7 else 15) { i ->
                val r = d * (0.055f + i * 0.038f)
                val wobble = 1f + 0.08f * sin(i * 1.73f)
                drawOval(
                    color = (if (i % 2 == 0) primary else secondary).copy(alpha = alpha * 0.12f),
                    topLeft = Offset(centre.x - r * wobble, centre.y - r),
                    size = Size(r * 2f * wobble, r * 2f),
                    style = Stroke(width = max(1f, d * 0.006f)),
                )
            }
        }
        CrystalTextureKind.CLEAR_QUARTZ -> {
            val hubs = listOf(Offset(w * 0.24f, h * 0.36f), Offset(w * 0.78f, h * 0.68f))
            hubs.forEachIndexed { hi, hub ->
                repeat(if (panel) 4 else 8) { i ->
                    val a = (i * 0.79f + hi * 0.43f)
                    val len = d * (0.18f + 0.055f * (i % 4))
                    drawLine(
                        color = Color.White.copy(alpha = alpha * 0.13f),
                        start = hub,
                        end = hub + Offset(cos(a) * len, sin(a) * len),
                        strokeWidth = max(0.8f, d * 0.0017f),
                    )
                }
            }
            repeat(if (panel) 2 else 5) { i ->
                val x = w * (-0.15f + i * 0.31f)
                val path =
                    Path().apply {
                        moveTo(x, h)
                        lineTo(x + w * 0.26f, 0f)
                        lineTo(x + w * 0.34f, 0f)
                        lineTo(x + w * 0.08f, h)
                        close()
                    }
                drawPath(path, (if (i % 2 == 0) primary else secondary).copy(alpha = alpha * 0.045f))
            }
        }
        CrystalTextureKind.ROSE_QUARTZ -> {
            repeat(if (panel) 5 else 12) { i ->
                val x = fract01(i * 0.618034f + 0.11f) * w
                val y = fract01(i * 0.414214f + 0.27f) * h
                val r = d * (0.08f + fract01(i * 0.2718f) * 0.13f)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = alpha * 0.065f),
                        0.65f to primary.copy(alpha = alpha * 0.04f),
                        1f to Color.Transparent,
                        center = Offset(x, y),
                        radius = r,
                    ),
                    r,
                    Offset(x, y),
                )
            }
            repeat(if (panel) 2 else 5) { i ->
                val y = h * (0.16f + i * 0.18f)
                val path =
                    Path().apply {
                        moveTo(-w * 0.05f, y)
                        cubicTo(w * 0.28f, y + h * 0.08f, w * 0.58f, y - h * 0.09f, w * 1.05f, y + h * 0.03f)
                    }
                drawPath(path, Color.White.copy(alpha = alpha * 0.065f), style = Stroke(width = max(0.7f, d * 0.0015f)))
            }
        }
        CrystalTextureKind.SUGILITE -> {
            repeat(if (panel) 8 else 21) { i ->
                val x = fract01(i * 0.73205f + 0.09f) * w
                val y = fract01(i * 0.54321f + 0.33f) * h
                val rx = d * (0.016f + fract01(i * 0.19f) * 0.045f)
                drawOval(
                    color = (if (i % 3 == 0) Color.Black else secondary).copy(alpha = alpha * 0.09f),
                    topLeft = Offset(x - rx, y - rx * 0.55f),
                    size = Size(rx * 2f, rx * 1.1f),
                )
            }
            repeat(if (panel) 2 else 4) { i ->
                val path =
                    Path().apply {
                        moveTo(w * (-0.1f + i * 0.27f), h)
                        cubicTo(w * (0.08f + i * 0.21f), h * 0.72f, w * (0.12f + i * 0.30f), h * 0.31f, w * (0.42f + i * 0.22f), 0f)
                    }
                drawPath(path, primary.copy(alpha = alpha * 0.12f), style = Stroke(width = d * 0.006f))
            }
        }
        CrystalTextureKind.AMETHYST -> {
            repeat(if (panel) 5 else 12) { i ->
                val x0 = w * fract01(i * 0.382f)
                val top = h * fract01(i * 0.217f) * 0.42f
                val bw = w * (0.09f + fract01(i * 0.143f) * 0.12f)
                val path =
                    Path().apply {
                        moveTo(x0, h)
                        lineTo(x0 + bw * 0.48f, top)
                        lineTo(x0 + bw, h)
                        close()
                    }
                drawPath(path, (if (i % 2 == 0) primary else secondary).copy(alpha = alpha * 0.055f))
                drawLine(
                    Color.White.copy(alpha = alpha * 0.07f),
                    Offset(x0 + bw * 0.48f, top),
                    Offset(x0 + bw * 0.34f, h),
                    max(
                        0.8f,
                        d * 0.0014f,
                    ),
                )
            }
        }
        CrystalTextureKind.KYANITE -> {
            repeat(if (panel) 8 else 20) { i ->
                val x = w * (-0.12f + i / (if (panel) 7f else 18f))
                val lean = w * (0.10f + 0.03f * sin(i * 0.9f))
                val path =
                    Path().apply {
                        moveTo(x, h)
                        lineTo(x + lean, 0f)
                        lineTo(x + lean + w * 0.025f, 0f)
                        lineTo(x + w * 0.018f, h)
                        close()
                    }
                drawPath(path, (if (i % 3 == 0) Color.White else primary).copy(alpha = alpha * 0.045f))
            }
        }
        CrystalTextureKind.ONYX -> {
            repeat(if (panel) 7 else 15) { i ->
                val y = h * (i + 0.4f) / (if (panel) 7f else 15f)
                val path =
                    Path().apply {
                        moveTo(-w * 0.04f, y)
                        cubicTo(w * 0.22f, y + h * 0.035f, w * 0.64f, y - h * 0.04f, w * 1.04f, y + h * 0.018f)
                    }
                drawPath(
                    path,
                    (if (i % 3 == 0) secondary else Color.White).copy(alpha = alpha * (if (i % 3 == 0) 0.055f else 0.028f)),
                    style = Stroke(width = max(1f, d * 0.004f)),
                )
            }
        }
        CrystalTextureKind.GENERIC -> {
            repeat(if (panel) 2 else 4) { i ->
                val x = w * (0.12f + i * 0.24f)
                drawLine(primary.copy(alpha = alpha * 0.035f), Offset(x, h), Offset(x + w * 0.22f, 0f), d * 0.003f)
            }
        }
    }
}

private fun fract01(v: Float): Float = v - kotlin.math.floor(v)

// ------------------------------------------------------------- silhouettes

/**
 * The signature "cut shard" silhouette: a deep gem cut on the top-start and
 * bottom-end corners, a shallow one on the other two — like a sliver cleaved
 * off a larger crystal. Used by every crystal control.
 */
fun crystalShardShape(
    cut: Dp = 12.dp,
    minor: Dp = 4.dp,
): Shape = CutCornerShape(topStart = cut, topEnd = minor, bottomStart = minor, bottomEnd = cut)

/**
 * Small diamond marker — the kit's selection/indicator glyph. A rotated
 * square lit from its top point, with an unrotated soft bloom behind it.
 */
@Composable
fun CrystalGem(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
    glow: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .then(if (glow) Modifier.softGlow(color, size) else Modifier)
            .rotate(45f)
            .background(Brush.linearGradient(listOf(lerp(color, Color.White, 0.55f), color))),
    )
}

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
    color: Color = accentTextColor(),
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

/** Outer bloom shared by panels and buttons: stacked halo strokes fading with distance. */
internal fun DrawScope.crystalHalo(
    glow: Color,
    cornerPx: Float,
    strength: Float,
) {
    for (i in 1..4) {
        val spread = i * i * 1.4f * 1.dp.toPx()
        drawRoundRect(
            color = glow.copy(alpha = (0.09f / i) * strength),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(cornerPx + spread, cornerPx + spread),
            style = Stroke(width = spread * 1.3f),
        )
    }
}

/**
 * Facet glints drawn over a glass fill: a specular streak along the top edge
 * plus two sheared translucent planes, like light caught in the internal cuts
 * of a polished stone. [strength] scales all glint alphas.
 */
internal fun DrawScope.crystalFacets(strength: Float) {
    if (strength <= 0f) return
    val w = size.width
    val h = size.height
    // Specular top edge.
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.10f * strength),
                1f to Color.Transparent,
                endY = h * 0.30f,
            ),
        size = Size(w, h * 0.30f),
    )

    // Two sheared facet planes, brighter one left of center, fainter right.
    fun plane(
        topFrom: Float,
        topTo: Float,
        shear: Float,
        alpha: Float,
    ) {
        val path =
            Path().apply {
                moveTo(w * topFrom, 0f)
                lineTo(w * topTo, 0f)
                lineTo(w * (topTo - shear), h)
                lineTo(w * (topFrom - shear), h)
                close()
            }
        drawPath(
            path,
            brush =
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = alpha * strength),
                    1f to Color.White.copy(alpha = alpha * 0.25f * strength),
                ),
        )
    }
    plane(topFrom = 0.16f, topTo = 0.34f, shear = 0.10f, alpha = 0.05f)
    plane(topFrom = 0.62f, topTo = 0.71f, shear = 0.13f, alpha = 0.035f)
}

/**
 * Crystal glass panel: soft outer glow halo, vertical gradient glass fill
 * (lit from the top like the mockups' "inner glow" material), facet glints,
 * and a luminous gradient border stroke. With [prismatic] the border becomes
 * an iridescent sweep between [glow], white and [sheen] — the selected-state
 * treatment ([sheen] defaults to the glow color).
 */
fun Modifier.crystalPanel(
    opacity: Float,
    tint: Color,
    glow: Color,
    corner: Dp = 18.dp,
    glowStrength: Float = 1f,
    facets: Float = 1f,
    prismatic: Boolean = false,
    sheen: Color = glow,
): Modifier =
    composed {
        val theme = LocalCrystalTheme.current
        val alpha = opacity.coerceIn(0f, 1f)
        val shape = RoundedCornerShape(corner)
        val borderBrush =
            if (prismatic) {
                Brush.sweepGradient(
                    listOf(
                        glow.copy(alpha = 0.9f),
                        Color.White.copy(alpha = 0.95f),
                        sheen.copy(alpha = 0.85f),
                        glow.copy(alpha = 0.35f),
                        sheen.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.9f),
                        glow.copy(alpha = 0.9f),
                    ),
                )
            } else {
                Brush.verticalGradient(
                    0f to glow.copy(alpha = min(1f, 0.85f * glowStrength)),
                    0.55f to glow.copy(alpha = 0.22f * glowStrength),
                    1f to glow.copy(alpha = 0.45f * glowStrength),
                )
            }
        this
            .drawBehind { crystalHalo(glow, corner.toPx(), glowStrength) }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to lerp(tint, glow, 0.18f).copy(alpha = min(1f, alpha + 0.08f)),
                    0.4f to tint.copy(alpha = alpha),
                    1f to lerp(tint, Color.Black, 0.28f).copy(alpha = alpha),
                ),
            ).drawBehind {
                drawMineralTexture(
                    theme = theme,
                    primary = glow,
                    secondary = sheen,
                    alpha = facets.coerceIn(0f, 1.5f),
                    panel = true,
                )
                crystalFacets(facets * 0.72f)
            }.border(1.dp, borderBrush, shape)
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
 * A floating crystal splinter in the backdrop: an irregular gem outline that
 * slowly rocks around its center. Positions/sizes are normalized; [spin] is
 * the rocking amplitude in degrees and [phase] offsets the motion so the
 * shards never move in lockstep.
 */
private class BackdropShard(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val tilt: Float,
    val spin: Float,
    val phase: Float,
    val secondary: Boolean,
    // Irregular gem profile: per-vertex radial scale at even angles.
    val profile: List<Float>,
)

private val BACKDROP_SHARDS =
    listOf(
        BackdropShard(0.84f, 0.16f, 0.085f, -18f, 7f, 0.0f, false, listOf(1f, 0.52f, 0.88f, 0.5f)),
        BackdropShard(0.12f, 0.56f, 0.065f, 24f, 9f, 2.1f, true, listOf(1f, 0.6f, 0.75f, 0.42f)),
        BackdropShard(0.68f, 0.72f, 0.11f, -40f, 5f, 4.4f, false, listOf(1f, 0.45f, 0.9f, 0.6f)),
        BackdropShard(0.34f, 0.88f, 0.05f, 66f, 6f, 1.2f, true, listOf(1f, 0.5f, 0.95f, 0.55f)),
    )

/**
 * Procedural mineral backdrop for shell screens. Every named crystal gets
 * its own inclusions, banding, fractures or blades; only naturally faceted
 * themes retain the moving shard highlights.
 */
@Composable
fun CrystalBackground(
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val theme = LocalCrystalTheme.current
    val kind = theme.crystalTextureKind()
    // Fixed seed: the texture field is stable across recompositions and tabs.
    val stars =
        remember {
            val rnd = Random(42)
            List(42) {
                Star(
                    x = rnd.nextFloat(),
                    y = rnd.nextFloat(),
                    size = 0.6f + rnd.nextFloat() * 1.7f,
                    phase = rnd.nextFloat() * (2f * PI.toFloat()),
                    bright = 0.3f + rnd.nextFloat() * 0.7f,
                )
            }
        }
    // Pointed or bladed minerals get a few moving facets. Banded, cloudy
    // and granular stones keep their own structures instead of inheriting
    // the same floating crystals.
    val showShards =
        kind == CrystalTextureKind.CLEAR_QUARTZ ||
            kind == CrystalTextureKind.AMETHYST ||
            kind == CrystalTextureKind.KYANITE
    // Sparkles are inclusions only for clear/black crystal and generic
    // non-mineral themes. Lapis already has pyrite; adding stars on top
    // would turn a mineral cue back into space wallpaper.
    val showSparkles =
        kind == CrystalTextureKind.CLEAR_QUARTZ ||
            kind == CrystalTextureKind.ONYX ||
            kind == CrystalTextureKind.GENERIC
    // The infinite clock only runs when the stone actually has moving marks
    // AND the user hasn't asked for reduced motion; otherwise the backdrop
    // is a single static draw at t = 0.
    val phase =
        if ((showShards || showSparkles) && !reducedMotion) {
            rememberInfiniteTransition(label = "crystal-bg")
                .animateFloat(
                    initialValue = 0f,
                    targetValue = 2f * PI.toFloat(),
                    animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing)),
                    label = "mineral-light",
                )
        } else {
            remember { mutableFloatStateOf(0f) }
        }
    val lightTheme = cs.background.luminance() > 0.5f
    val sparkle = if (lightTheme) cs.primary else Color.White
    Canvas(modifier) {
        // Read inside the draw scope so animation frames invalidate only the
        // draw pass, never the composition.
        val t = phase.value
        drawRect(cs.background)
        val d = max(size.width, size.height)

        // Broad transmitted light INSIDE the stone. The old background leaned
        // on three strong aurora blobs in every theme, which made different
        // minerals collapse into the same AI-nebula look.
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to cs.primary.copy(alpha = if (lightTheme) 0.07f else 0.12f),
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
                    0f to cs.secondary.copy(alpha = if (lightTheme) 0.055f else 0.09f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.92f, size.height * 0.85f),
                    radius = d * 0.6f,
                ),
            radius = d * 0.6f,
            center = Offset(size.width * 0.92f, size.height * 0.85f),
        )
        drawMineralTexture(
            theme = theme,
            primary = cs.primary,
            secondary = cs.secondary,
            alpha = if (lightTheme) 0.72f else 1f,
            panel = false,
        )

        if (showShards) {
            val shardBase = if (lightTheme) 0.38f else 0.72f
            BACKDROP_SHARDS.forEach { sh ->
                val tone = if (sh.secondary) cs.secondary else cs.primary
                val pivot = Offset(sh.cx * size.width, sh.cy * size.height)
                val r = sh.radius * d
                rotate(sh.tilt + sh.spin * sin(t + sh.phase), pivot) {
                    val pts =
                        sh.profile.mapIndexed { i, k ->
                            val a = (2f * PI.toFloat()) * i / sh.profile.size
                            Offset(pivot.x + r * k * sin(a), pivot.y - r * k * cos(a))
                        }
                    val path =
                        Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                            close()
                        }
                    drawPath(path, color = tone.copy(alpha = 0.022f * shardBase))
                    drawPath(
                        path,
                        color = tone.copy(alpha = 0.10f * shardBase),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.07f * shardBase),
                        start = pts[0],
                        end = pts[2],
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }

        if (showSparkles) {
            stars.forEach { star ->
                val twinkle = 0.5f + 0.5f * sin(t + star.phase)
                val a = (0.02f + 0.12f * star.bright * twinkle) * (if (lightTheme) 0.55f else 1f)
                drawCircle(
                    color = sparkle.copy(alpha = a),
                    radius = star.size * 1.dp.toPx() * (0.7f + 0.5f * twinkle),
                    center = Offset(star.x * size.width, star.y * size.height),
                )
            }
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
