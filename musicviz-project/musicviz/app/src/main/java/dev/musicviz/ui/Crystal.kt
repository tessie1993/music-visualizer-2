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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.musicviz.R
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
 *
 * Also the single provider of [LocalFontColor]: the RESOLVED font colour
 * override (null when automatic, or when the light-theme contrast gate in
 * [AppTheme.resolvedFontColor] rejected it), so [accentTextColor] call sites
 * never have to re-derive whether the override is readable.
 */
@Composable
internal fun CrystalMaterialTheme(
    appTheme: AppTheme,
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    val fontColor = appTheme.resolvedFontColor(gui.fontColorOverride)
    CompositionLocalProvider(
        LocalCrystalTheme provides appTheme,
        LocalFontColor provides fontColor?.let { Color(it) },
    ) {
        MaterialTheme(
            colorScheme = appTheme.colorScheme(gui.accentIntensity, gui.backgroundDim, gui.fontColorOverride),
            shapes = gui.cornerStyle.shapes(),
            typography = crystalTypography(gui.textScale),
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
            // Cobblestone mosaic per the reference slab: rounded patches of
            // royal blue / teal / navy on a shared jittered lattice, GOLD
            // pyrite veins running BETWEEN the patches, and pyrite fleck
            // clusters gathered near the veins.
            val cols = if (panel) 4 else 6
            val rows = if (panel) 5 else 9
            val nx = cols + 1
            val ny = rows + 1
            val teal = lerp(primary, Color(0xFF17858C), 0.55f)
            val navy = lerp(primary, Color.Black, 0.5f)
            // Lattice vertices are jittered once and SHARED by neighbouring
            // cells, so patch borders meet along common seams. The grid
            // overshoots the bounds slightly so no background margin shows.
            val px = FloatArray(nx * ny)
            val py = FloatArray(nx * ny)
            for (j in 0 until ny) {
                for (i in 0 until nx) {
                    val idx = j * nx + i
                    val jx = (fract01(idx * 0.7548777f + 0.13f) - 0.5f) * 0.55f
                    val jy = (fract01(idx * 0.5698403f + 0.71f) - 0.5f) * 0.55f
                    px[idx] = w * (-0.04f + 1.08f * (i + jx) / cols)
                    py[idx] = h * (-0.04f + 1.08f * (j + jy) / rows)
                }
            }
            // Patches: each cell's corners pulled 10% toward its centre, so a
            // vein channel opens between every pair of neighbours.
            for (j in 0 until rows) {
                for (i in 0 until cols) {
                    val cell = j * cols + i
                    val c = intArrayOf(j * nx + i, j * nx + i + 1, (j + 1) * nx + i + 1, (j + 1) * nx + i)
                    val cxm = (px[c[0]] + px[c[1]] + px[c[2]] + px[c[3]]) / 4f
                    val cym = (py[c[0]] + py[c[1]] + py[c[2]] + py[c[3]]) / 4f
                    val path =
                        Path().apply {
                            for (k in 0..3) {
                                val x = px[c[k]] + (cxm - px[c[k]]) * 0.10f
                                val y = py[c[k]] + (cym - py[c[k]]) * 0.10f
                                if (k == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close()
                        }
                    val tealMix = fract01(cell * 0.318309f + 0.07f)
                    val darkMix = fract01(cell * 0.867532f + 0.51f)
                    val tone = lerp(lerp(primary, teal, tealMix * 0.7f), navy, 0.25f + darkMix * 0.55f)
                    drawPath(path, tone.copy(alpha = alpha * (0.10f + 0.09f * fract01(cell * 0.5417f))))
                }
            }
            // Gold veins along the lattice seams - one polyline per grid row
            // and column, each with its own brightness so the net reads
            // hand-laid rather than woven.
            fun vein(points: List<Offset>, a: Float) {
                val path =
                    Path().apply {
                        moveTo(points[0].x, points[0].y)
                        for (p in points.drop(1)) lineTo(p.x, p.y)
                    }
                drawPath(path, secondary.copy(alpha = alpha * a), style = Stroke(width = max(0.8f, d * 0.0016f)))
            }
            for (j in 0 until ny) {
                vein(List(nx) { i -> Offset(px[j * nx + i], py[j * nx + i]) }, 0.16f + 0.14f * fract01(j * 0.6180f + 0.2f))
            }
            for (i in 0 until nx) {
                vein(List(ny) { j -> Offset(px[j * nx + i], py[j * nx + i]) }, 0.16f + 0.14f * fract01(i * 0.6180f + 0.6f))
            }
            // Pyrite fleck clusters seeded on lattice vertices (i.e. on the
            // veins), two fleck sizes per cluster.
            val clusters = if (panel) 6 else 14
            repeat(clusters) { n ->
                val v = (fract01(n * 0.754877f + 0.29f) * (nx * ny - 1)).toInt()
                val fx = px[v]
                val fy = py[v]
                repeat(4) { k ->
                    val ang = fract01((n * 4 + k) * 0.618034f) * (2f * PI.toFloat())
                    val dist = d * 0.004f * (1f + fract01((n * 4 + k) * 0.414214f) * 3f)
                    val r = (if (k % 2 == 0) 1.4f else 0.7f) * density * max(1f, d * 0.0011f)
                    drawCircle(
                        secondary.copy(alpha = alpha * (0.30f + 0.25f * fract01(k * 0.31f + n * 0.17f))),
                        r,
                        Offset(fx + cos(ang) * dist, fy + sin(ang) * dist),
                    )
                }
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
            // Light blush marble per the reference: soft white cloudy veils,
            // a fine network of high-curvature cracks in white and deep
            // rose, and one pale gold vein. Airy - the lowest mark count of
            // the four hero stones.
            repeat(if (panel) 4 else 9) { i ->
                val x = fract01(i * 0.618034f + 0.11f) * w
                val y = fract01(i * 0.414214f + 0.27f) * h
                val r = d * (0.06f + fract01(i * 0.2718f) * 0.11f)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = alpha * 0.30f),
                        0.7f to Color.White.copy(alpha = alpha * 0.10f),
                        1f to Color.Transparent,
                        center = Offset(x, y),
                        radius = r,
                    ),
                    r,
                    Offset(x, y),
                )
            }
            // Crack network: each crack wanders as chained cubics whose
            // control points swing hard sideways, alternating white and deep
            // rose. Short branch cracks split off half of them.
            val deepRose = lerp(primary, Color.Black, 0.30f)
            val cracks = if (panel) 6 else 14
            repeat(cracks) { i ->
                var x = fract01(i * 0.754877f + 0.05f) * w
                var y = fract01(i * 0.569840f + 0.43f) * h
                val steps = 3
                val path = Path().apply { moveTo(x, y) }
                repeat(steps) { s ->
                    val hgen = i * 7 + s * 3
                    val dx = (fract01(hgen * 0.618034f) - 0.5f) * w * 0.24f
                    val dy = (fract01(hgen * 0.414214f + 0.3f) - 0.5f) * h * 0.16f
                    val swing = (fract01(hgen * 0.271828f + 0.7f) - 0.5f) * d * 0.09f
                    path.cubicTo(x + dx * 0.3f - swing, y + dy * 0.3f + swing, x + dx * 0.7f + swing, y + dy * 0.7f - swing, x + dx, y + dy)
                    x += dx
                    y += dy
                }
                val white = i % 2 == 0
                drawPath(
                    path,
                    (if (white) Color.White else deepRose).copy(alpha = alpha * (if (white) 0.45f else 0.16f)),
                    style = Stroke(width = max(0.6f, d * 0.0011f)),
                )
                if (i % 2 == 1) {
                    val ang = fract01(i * 0.318309f) * (2f * PI.toFloat())
                    drawLine(
                        deepRose.copy(alpha = alpha * 0.12f),
                        Offset(x, y),
                        Offset(x + cos(ang) * d * 0.05f, y + sin(ang) * d * 0.05f),
                        max(0.5f, d * 0.0009f),
                    )
                }
            }
            // The single pale gold vein, with a faint echo alongside.
            val gold =
                Path().apply {
                    moveTo(-w * 0.05f, h * 0.68f)
                    cubicTo(w * 0.25f, h * 0.60f, w * 0.45f, h * 0.80f, w * 0.72f, h * 0.70f)
                    cubicTo(w * 0.86f, h * 0.65f, w * 0.96f, h * 0.72f, w * 1.05f, h * 0.66f)
                }
            drawPath(gold, secondary.copy(alpha = alpha * 0.30f), style = Stroke(width = max(0.8f, d * 0.0015f)))
            drawPath(gold, secondary.copy(alpha = alpha * 0.10f), style = Stroke(width = max(1.6f, d * 0.004f)))
        }
        CrystalTextureKind.SUGILITE -> {
            // Violet-periwinkle marble slab per the reference: broad darker
            // diagonal fracture bands, PINK veins tracing the band edges
            // with small branches, lighter lavender cloud zones between the
            // dark regions, and a fine granular dusting.
            val bands = if (panel) 2 else 3
            val lavender = lerp(primary, Color.White, 0.55f)
            repeat(bands) { k ->
                val c = 0.10f + 0.34f * k + 0.05f * fract01(k * 0.618f)
                val yA = h * (c + 0.38f)
                val yB = h * (c - 0.42f)
                val th = h * (0.09f + 0.05f * fract01(k * 0.414f + 0.2f))
                val band =
                    Path().apply {
                        moveTo(-w * 0.05f, yA)
                        lineTo(w * 1.05f, yB)
                        lineTo(w * 1.05f, yB + th)
                        lineTo(-w * 0.05f, yA + th)
                        close()
                    }
                drawPath(band, Color.Black.copy(alpha = alpha * (0.10f + 0.04f * fract01(k * 0.87f))))
                // Pink vein tracing this band's top edge: jittered polyline
                // plus a couple of short branches peeling away from it.
                val pts = 8
                val vein = Path()
                for (p in 0..pts) {
                    val t = p / pts.toFloat()
                    val x = w * (-0.05f + 1.10f * t)
                    val y = yA + (yB - yA) * t + h * 0.014f * (fract01((k * 9 + p) * 0.754877f) - 0.5f)
                    if (p == 0) vein.moveTo(x, y) else vein.lineTo(x, y)
                }
                drawPath(vein, secondary.copy(alpha = alpha * 0.28f), style = Stroke(width = max(0.8f, d * 0.0018f)))
                repeat(3) { b ->
                    val t = fract01((k * 3 + b) * 0.618034f + 0.15f)
                    val x = w * (-0.05f + 1.10f * t)
                    val y = yA + (yB - yA) * t
                    val ang = -(0.5f + fract01((k * 3 + b) * 0.271828f) * 0.9f)
                    drawLine(
                        secondary.copy(alpha = alpha * 0.18f),
                        Offset(x, y),
                        Offset(x + cos(ang) * d * 0.06f, y + sin(ang) * d * 0.06f),
                        max(0.6f, d * 0.0012f),
                    )
                }
            }
            // Lavender cloud zones between the dark bands.
            repeat(if (panel) 4 else 9) { i ->
                val x = fract01(i * 0.73205f + 0.09f) * w
                val y = fract01(i * 0.54321f + 0.33f) * h
                val rx = d * (0.035f + fract01(i * 0.19f) * 0.075f)
                drawOval(
                    color = lavender.copy(alpha = alpha * 0.055f),
                    topLeft = Offset(x - rx, y - rx * 0.6f),
                    size = Size(rx * 2f, rx * 1.2f),
                )
            }
            // Fine granular specks - the sugary crystal grain of the stone.
            repeat(if (panel) 26 else 70) { i ->
                val x = fract01(i * 0.754877f + 0.21f) * w
                val y = fract01(i * 0.569840f + 0.57f) * h
                val r = (0.5f + fract01(i * 0.314159f) * 1.1f) * density
                val tone =
                    when (i % 3) {
                        0 -> Color.Black.copy(alpha = alpha * 0.10f)
                        1 -> secondary.copy(alpha = alpha * 0.12f)
                        else -> Color.White.copy(alpha = alpha * 0.07f)
                    }
                drawCircle(tone, r, Offset(x, y))
            }
        }
        CrystalTextureKind.AMETHYST -> {
            // Densely packed faceted terminations per the reference: a
            // jittered triangular mosaic, every facet lit from its own
            // pseudo-random direction, white fracture lines between facets,
            // and bright glints on a few corners.
            val cols = if (panel) 3 else 5
            val rows = if (panel) 4 else 7
            val nx = cols + 1
            val ny = rows + 1
            val px = FloatArray(nx * ny)
            val py = FloatArray(nx * ny)
            for (j in 0 until ny) {
                for (i in 0 until nx) {
                    val idx = j * nx + i
                    val jx = (fract01(idx * 0.7548777f + 0.37f) - 0.5f) * 0.62f
                    val jy = (fract01(idx * 0.5698403f + 0.83f) - 0.5f) * 0.62f
                    px[idx] = w * (-0.04f + 1.08f * (i + jx) / cols)
                    py[idx] = h * (-0.04f + 1.08f * (j + jy) / rows)
                }
            }

            fun facet(a: Int, b: Int, c: Int, seed: Int) {
                val path =
                    Path().apply {
                        moveTo(px[a], py[a])
                        lineTo(px[b], py[b])
                        lineTo(px[c], py[c])
                        close()
                    }
                val cx = (px[a] + px[b] + px[c]) / 3f
                val cy = (py[a] + py[b] + py[c]) / 3f
                val ang = fract01(seed * 0.618034f + 0.11f) * (2f * PI.toFloat())
                val reach = d * 0.09f
                val deep = lerp(primary, Color.Black, 0.45f + 0.25f * fract01(seed * 0.414214f))
                val pale = lerp(primary, Color.White, 0.30f + 0.30f * fract01(seed * 0.271828f))
                drawPath(
                    path,
                    Brush.linearGradient(
                        0f to deep.copy(alpha = alpha * 0.16f),
                        1f to pale.copy(alpha = alpha * 0.12f),
                        start = Offset(cx - cos(ang) * reach, cy - sin(ang) * reach),
                        end = Offset(cx + cos(ang) * reach, cy + sin(ang) * reach),
                    ),
                )
                // White fracture/edge line between this facet and the next.
                drawPath(path, Color.White.copy(alpha = alpha * 0.10f), style = Stroke(width = max(0.6f, d * 0.0011f)))
            }
            for (j in 0 until rows) {
                for (i in 0 until cols) {
                    val v00 = j * nx + i
                    val v10 = v00 + 1
                    val v01 = v00 + nx
                    val v11 = v01 + 1
                    val cell = j * cols + i
                    // Alternate the split diagonal by hash so the mosaic
                    // never settles into a woven herringbone.
                    if (fract01(cell * 0.867532f) < 0.5f) {
                        facet(v00, v10, v11, cell * 2)
                        facet(v00, v11, v01, cell * 2 + 1)
                    } else {
                        facet(v10, v11, v01, cell * 2)
                        facet(v10, v01, v00, cell * 2 + 1)
                    }
                }
            }
            // Glint dots where light catches a termination corner.
            repeat(if (panel) 5 else 13) { g ->
                val v = (fract01(g * 0.754877f + 0.19f) * (nx * ny - 1)).toInt()
                drawCircle(
                    Color.White.copy(alpha = alpha * (0.30f + 0.30f * fract01(g * 0.5417f))),
                    max(1f, d * 0.0016f) * density,
                    Offset(px[v], py[v]),
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
 * Bundled OFL families (see THIRD_PARTY_NOTICES). Both ship as variable
 * TTFs; each [Font] entry pins its weight through a `wght` variation
 * setting, so every requested weight is a true instance rather than a
 * fake-bold. Cinzel is the display serif (mystic-premium engraved capitals),
 * Manrope the clean body/label sans.
 *
 * The variation-settings Font constructor is still marked experimental in
 * this Compose release; the opt-in is confined to these two declarations.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val CinzelFamily =
    FontFamily(
        Font(R.font.cinzel, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.cinzel, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.cinzel, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.cinzel, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val ManropeFamily =
    FontFamily(
        Font(R.font.manrope, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
        Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

/**
 * Theme typography per the mockups: serif display/headline ("Display Serif —
 * for headlines & hero moments") in Cinzel, body/labels in Manrope.
 * [textScale] (the Appearance "Text size" option, [GuiPrefs.textScale])
 * multiplies every font size; line heights scale with them so multi-line
 * text does not collide at large scales.
 */
fun crystalTypography(textScale: Float = 1f): Typography {
    val base = Typography()
    val scale = textScale.coerceIn(GuiPrefs.TEXT_SCALE_MIN, GuiPrefs.TEXT_SCALE_MAX)

    fun TextStyle.scaled(): TextStyle =
        copy(
            fontSize = fontSize * scale,
            lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight,
        )

    fun TextStyle.display(tracking: Float) =
        scaled().copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Medium, letterSpacing = tracking.sp)

    fun TextStyle.body(tracking: Float? = null) =
        scaled().copy(fontFamily = ManropeFamily, letterSpacing = tracking?.sp ?: letterSpacing)
    return Typography(
        displayLarge = base.displayLarge.display(1.5f),
        displayMedium = base.displayMedium.display(1.2f),
        displaySmall = base.displaySmall.display(1f),
        headlineLarge = base.headlineLarge.display(1f),
        headlineMedium = base.headlineMedium.display(0.8f),
        headlineSmall = base.headlineSmall.display(0.6f),
        titleLarge = base.titleLarge.display(0.4f),
        titleMedium = base.titleMedium.body(0.5f).copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.body(0.4f),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body(1f),
        labelMedium = base.labelMedium.body(1.3f),
        labelSmall = base.labelSmall.body(1.1f),
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
