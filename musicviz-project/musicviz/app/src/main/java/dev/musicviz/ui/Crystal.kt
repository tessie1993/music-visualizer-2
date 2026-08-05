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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.drawscope.translate
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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
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
    val scheme = appTheme.colorScheme(gui.accentIntensity, gui.backgroundDim, gui.fontColorOverride)
    CompositionLocalProvider(
        LocalCrystalTheme provides appTheme,
        LocalFontColor provides fontColor?.let { Color(it) },
        // MaterialTheme does not touch LocalContentColor - only a Surface
        // does, and the shell has none: the Scaffold is transparent so the
        // crystal backdrop shows through, and contentColorFor(Transparent)
        // resolves to nothing. Every `Text` without an explicit colour -
        // track titles, style names, most list rows - therefore painted in
        // the compositionLocal's own default (opaque black) whatever the
        // theme said, which is what made the Appearance "Font color" option
        // look hardcoded: it repaints the onSurface roles those Texts never
        // read. Providing the role here is what connects the two.
        LocalContentColor provides scheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = scheme,
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
            fun vein(
                points: List<Offset>,
                a: Float,
            ) {
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
        CrystalTextureKind.MALACHITE -> drawMalachiteTexture(alpha, panel)
        CrystalTextureKind.CLEAR_QUARTZ -> drawClearQuartzTexture(alpha, panel)
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

            fun facet(
                a: Int,
                b: Int,
                c: Int,
                seed: Int,
            ) {
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
        CrystalTextureKind.KYANITE -> drawKyaniteTexture(alpha, panel)
        CrystalTextureKind.ONYX -> drawOnyxTexture(alpha, panel)
        CrystalTextureKind.GENERIC -> drawGenericCrystalline(theme, primary, alpha, panel)
    }
}

private fun fract01(v: Float): Float = v - kotlin.math.floor(v)

// ------------------------------------------------- mineral texture helpers
//
// Sizing follows the texture brief: marks scale from the SHORT canvas edge
// (minDim), and a "hairline" is max(1dp, 0.15% of minDim). Everything is
// seeded through [fract01] so layouts are deterministic and static.

private val DEG = (PI / 180.0).toFloat()

/**
 * Ring radii for one malachite eye: 6-9 concentric bands (capped at
 * [maxRings] so panels stay light) whose radius grows geometrically x1.3-1.6
 * from an innermost 1.5-3% of [minDim] — the band spacing visibly tightens
 * toward the eye centre like real botryoidal banding. Pure so it can be
 * unit-tested.
 */
internal fun malachiteRingRadii(
    seed: Float,
    minDim: Float,
    maxRings: Int = 9,
): List<Float> {
    val count = min(maxRings, 6 + (fract01(seed * 0.618034f) * 3.99f).toInt())
    var r = minDim * (0.015f + 0.015f * fract01(seed * 0.754877f + 0.11f))
    return List(count) { i ->
        val cur = r
        r *= 1.3f + 0.3f * fract01(seed * 0.414214f + i * 0.618034f)
        cur
    }
}

internal enum class OnyxBandKind { BROAD, HAIRLINE, MEDIUM, WARM }

internal data class OnyxBand(
    /** Band centreline, as radial distance from the group's starting edge. */
    val offset: Float,
    val width: Float,
    val kind: OnyxBandKind,
    val alpha: Float,
)

/**
 * Quasi-periodic onyx band rhythm: broad band -> short gap -> trio of tight
 * hairlines -> long gap -> medium band, repeated [units] times with the gaps
 * jittered +/-30%, plus an occasional warm honey band, clipped to [span].
 * Offsets are strictly outward so consumers can lay the bands on concentric
 * arcs. Pure so it can be unit-tested.
 */
internal fun onyxBandRhythm(
    seed: Float,
    span: Float,
    minDim: Float,
    units: Int,
): List<OnyxBand> {
    val bands = mutableListOf<OnyxBand>()
    var cursor = 0f

    fun gap(
        base: Float,
        spread: Float,
        k: Float,
    ) {
        cursor += minDim * (base + spread * fract01(seed * 0.5417f + k * 0.754877f)) *
            (0.7f + 0.6f * fract01(seed * 0.318309f + k * 0.618034f))
    }

    fun push(
        width: Float,
        kind: OnyxBandKind,
        alpha: Float,
    ): Boolean {
        if (cursor + width > span) return false
        bands += OnyxBand(cursor + width / 2f, width, kind, alpha)
        cursor += width
        return true
    }

    for (u in 0 until units) {
        val broad = minDim * (0.04f + 0.03f * fract01(seed * 0.414214f + u * 0.271828f))
        if (!push(broad, OnyxBandKind.BROAD, 0.85f)) return bands
        gap(0.02f, 0f, u * 4f + 1f)
        for (hi in 0 until 3) {
            val hw = minDim * (0.002f + 0.004f * fract01(seed * 0.867532f + (u * 3 + hi) * 0.569840f))
            if (!push(hw, OnyxBandKind.HAIRLINE, 0.5f + 0.3f * fract01(seed + (u * 3 + hi) * 0.31f))) return bands
            cursor += minDim * (0.008f + 0.007f * fract01(seed * 0.13f + (u * 3 + hi) * 0.77f))
        }
        gap(0.06f, 0.06f, u * 4f + 2f)
        if (!push(minDim * (0.015f + 0.015f * fract01(seed * 0.618034f + u * 0.414214f)), OnyxBandKind.MEDIUM, 0.7f)) return bands
        if (u == units - 1 && fract01(seed * 0.271828f + 0.4f) > 0.5f) {
            gap(0.03f, 0f, u * 4f + 3f)
            push(minDim * 0.02f, OnyxBandKind.WARM, 0.6f)
        }
        gap(0.05f, 0.05f, u * 4f + 4f)
    }
    return bands
}

private class MalachiteEye(
    val center: Offset,
    val rings: List<Float>,
    val ecc: Float,
    val rotDeg: Float,
)

/**
 * Malachite: the concentric contour-eye system of a slice through botryoidal
 * growth. Banded eyes drift off-centre as they grow, wide contour ribbons
 * flow between neighbouring eyes, the thin chalky light bands sit on top and
 * a couple of tiny specular arcs catch the polish. Dark green must dominate.
 */
private fun DrawScope.drawMalachiteTexture(
    alpha: Float,
    panel: Boolean,
) {
    val w = size.width
    val h = size.height
    val minDim = min(w, h)
    val hairline = max(1.dp.toPx(), minDim * 0.0015f)
    val dark = Color(0xFF0E4D33)
    val midGreen = Color(0xFF17805A)
    val lightGreen = Color(0xFF35B37F)
    val chalk = Color(0xFF7FDCAF)

    // Base: deepen the field downwards and darken the corners so the eyes
    // float in broad velvety dark green.
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color(0xFF0B3D2E).copy(alpha = alpha * 0.55f),
        ),
    )
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.62f to Color.Transparent,
            1f to Color(0xFF041710).copy(alpha = alpha * 0.5f),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = max(w, h) * 0.78f,
        ),
    )

    // Eyes on well-spread anchors (>= 0.25*minDim apart; the last one runs
    // part off-canvas), each jittered by the hash with its own eccentricity
    // and rotation.
    val anchors =
        if (panel) {
            listOf(Offset(0.70f, 0.32f), Offset(0.18f, 0.78f))
        } else {
            listOf(Offset(0.70f, 0.30f), Offset(0.22f, 0.58f), Offset(0.52f, 0.94f), Offset(1.04f, 0.70f))
        }
    val eyes =
        anchors.mapIndexed { i, a ->
            MalachiteEye(
                center =
                    Offset(
                        w * (a.x + (fract01(i * 0.754877f + 0.19f) - 0.5f) * 0.06f),
                        h * (a.y + (fract01(i * 0.569840f + 0.47f) - 0.5f) * 0.06f),
                    ),
                rings = malachiteRingRadii(seed = i * 3.7f + 1.3f, minDim = minDim, maxRings = if (panel) 5 else 9),
                ecc = 1f + 0.25f * fract01(i * 0.318309f + 0.05f),
                rotDeg = fract01(i * 0.271828f) * 180f - 90f,
            )
        }

    // Connecting ribbons UNDER the rings: wide soft bands running tangent
    // between two eyes' outer rings like shared topographic contours, each
    // topped by one thin light echo line.
    repeat(if (panel) 1 else 3) { k ->
        val a = eyes[k]
        val b = eyes[k + 1]
        val dx = b.center.x - a.center.x
        val dy = b.center.y - a.center.y
        val len = max(1f, sqrt(dx * dx + dy * dy))
        val ux = dx / len
        val uy = dy / len
        val start = Offset(a.center.x + ux * a.rings.last() * 0.9f, a.center.y + uy * a.rings.last() * 0.9f)
        val end = Offset(b.center.x - ux * b.rings.last() * 0.9f, b.center.y - uy * b.rings.last() * 0.9f)
        val swing = minDim * (0.08f + 0.08f * fract01(k * 0.618034f + 0.33f)) * (if (k % 2 == 0) 1f else -1f)
        val ribbon =
            Path().apply {
                moveTo(start.x, start.y)
                cubicTo(
                    start.x + (end.x - start.x) * 0.33f - uy * swing,
                    start.y + (end.y - start.y) * 0.33f + ux * swing,
                    start.x + (end.x - start.x) * 0.66f + uy * swing * 0.6f,
                    start.y + (end.y - start.y) * 0.66f - ux * swing * 0.6f,
                    end.x,
                    end.y,
                )
            }
        drawPath(
            ribbon,
            midGreen.copy(alpha = alpha * (0.30f + 0.15f * fract01(k * 0.5417f))),
            style = Stroke(width = minDim * (0.03f + 0.03f * fract01(k * 0.318309f))),
        )
        translate(-uy * minDim * 0.018f, ux * minDim * 0.018f) {
            drawPath(ribbon, lightGreen.copy(alpha = alpha * 0.6f), style = Stroke(width = hairline))
        }
    }

    // Rings: alternating dark / mid / THIN light bands whose shared centre
    // drifts cumulatively outward, so the eyes read grown rather than drawn.
    eyes.forEachIndexed { ei, eye ->
        rotate(eye.rotDeg, eye.center) {
            var cx = eye.center.x
            var cy = eye.center.y
            val driftAng = fract01(ei * 0.867532f + 0.23f) * (2f * PI.toFloat())
            eye.rings.forEachIndexed { ri, r ->
                if (ri > 0) {
                    val drift = minDim * (0.01f + 0.02f * fract01((ei * 11 + ri) * 0.618034f))
                    cx += cos(driftAng) * drift
                    cy += sin(driftAng) * drift
                }
                val rx = r * eye.ecc
                val tone: Color
                val strokeW: Float
                val a: Float
                when (ri % 3) {
                    0 -> {
                        tone = dark
                        strokeW = minDim * (0.008f + 0.017f * fract01((ei * 7 + ri) * 0.754877f))
                        a = 0.85f + 0.15f * fract01((ei + ri) * 0.31f)
                    }
                    1 -> {
                        tone = midGreen
                        strokeW = minDim * (0.010f + 0.010f * fract01((ei * 7 + ri) * 0.569840f))
                        a = 0.70f + 0.20f * fract01((ei + ri) * 0.53f)
                    }
                    else -> {
                        tone = lightGreen
                        strokeW = minDim * (0.002f + 0.003f * fract01((ei * 7 + ri) * 0.414214f))
                        a = 0.9f
                    }
                }
                drawOval(
                    color = tone.copy(alpha = alpha * a),
                    topLeft = Offset(cx - rx, cy - r),
                    size = Size(rx * 2f, r * 2f),
                    style = Stroke(width = max(hairline * 0.8f, strokeW)),
                )
                // Roughly one light band in four carries a chalky hairline
                // echo just outside it.
                if (ri % 3 == 2 && fract01((ei * 5 + ri) * 0.271828f) < 0.25f) {
                    val er = r + minDim * 0.003f
                    drawOval(
                        color = chalk.copy(alpha = alpha * 0.5f),
                        topLeft = Offset(cx - er * eye.ecc, cy - er),
                        size = Size(er * eye.ecc * 2f, er * 2f),
                        style = Stroke(width = hairline),
                    )
                }
            }
        }
    }

    // Tiny specular arcs on the lit (upper-left) side of the largest eyes.
    val lit = eyes.sortedByDescending { it.rings.last() }.take(2)
    repeat(if (panel) 1 else 3) { k ->
        val eye = lit[k % lit.size]
        val r = eye.rings.last() * (0.55f + 0.25f * fract01(k * 0.618034f + 0.4f))
        drawArc(
            color = Color.White.copy(alpha = alpha * (0.06f + 0.06f * fract01(k * 0.414214f))),
            startAngle = -150f + 25f * fract01(k * 0.754877f),
            sweepAngle = 30f + 30f * fract01(k * 0.271828f),
            useCenter = false,
            topLeft = Offset(eye.center.x - r, eye.center.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = max(hairline, minDim * 0.004f)),
        )
    }
}

/**
 * Clear quartz: water-clear stone whose only marks are honest inclusions —
 * milky clouds low in the stone, long straight prism edges in two 60-degree
 * families, wispy "veil" fans of partially healed fractures, one or two tiny
 * rainbow flashes confined to a fracture plane, and pin-point glints.
 */
private fun DrawScope.drawClearQuartzTexture(
    alpha: Float,
    panel: Boolean,
) {
    val w = size.width
    val h = size.height
    val d = max(w, h)
    val minDim = min(w, h)
    val hairline = max(1.dp.toPx(), minDim * 0.0015f)
    val prismTone = Color(0xFFBFD9E8)
    val veilTone = Color(0xFFDCEFF7)
    val milk = Color(0xFFF2F8FB)

    // Base: cool diagonal deepening; milky clouds sit UNDER everything else.
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            1f to Color(0xFF0D1420).copy(alpha = alpha * 0.6f),
            start = Offset.Zero,
            end = Offset(w, h),
        ),
    )
    repeat(2) { i ->
        val c =
            Offset(
                w * (0.22f + 0.55f * fract01(i * 0.618034f + 0.07f)),
                h * (0.68f + 0.24f * fract01(i * 0.414214f + 0.51f)),
            )
        val r = minDim * (0.25f + 0.15f * fract01(i * 0.754877f + 0.29f))
        drawCircle(
            Brush.radialGradient(
                0f to milk.copy(alpha = alpha * 0.06f),
                1f to Color.Transparent,
                center = c,
                radius = r,
            ),
            r,
            c,
        )
    }

    // Prism hints: long straight edges at one dominant angle plus a smaller
    // family at -60 degrees (hexagonal prism geometry), and a few short
    // striation ticks running across the dominant edges.
    val theta = 1.05f + 0.35f * fract01(0.734f)
    val ux = cos(theta)
    val uy = -sin(theta)
    val nx = -uy
    val ny = ux
    val cx = w * 0.5f
    val cy = h * 0.5f
    val mains = if (panel) 3 else 6
    repeat(mains) { k ->
        val off = ((k + 0.5f) / mains - 0.5f) * d * 1.15f + (fract01(k * 0.618034f + 0.13f) - 0.5f) * minDim * 0.08f
        val px = cx + nx * off
        val py = cy + ny * off
        drawLine(
            prismTone.copy(alpha = alpha * (0.05f + 0.07f * fract01(k * 0.754877f + 0.31f))),
            Offset(px - ux * d, py - uy * d),
            Offset(px + ux * d, py + uy * d),
            hairline + minDim * 0.0015f * fract01(k * 0.414214f),
        )
    }
    val theta2 = theta - PI.toFloat() / 3f
    val u2x = cos(theta2)
    val u2y = -sin(theta2)
    repeat(if (panel) 1 else 3) { k ->
        val off = ((k + 0.5f) / 3f - 0.5f) * d * 0.9f + (fract01(k * 0.271828f + 0.61f) - 0.5f) * minDim * 0.1f
        val px = cx - u2y * off
        val py = cy + u2x * off
        drawLine(
            prismTone.copy(alpha = alpha * (0.05f + 0.05f * fract01(k * 0.569840f + 0.17f))),
            Offset(px - u2x * d, py - u2y * d),
            Offset(px + u2x * d, py + u2y * d),
            hairline,
        )
    }
    repeat(if (panel) 1 else 3) { k ->
        val along = (fract01(k * 0.867532f + 0.43f) - 0.5f) * d * 0.8f
        val off = (fract01(k * 0.5417f + 0.09f) - 0.5f) * d * 0.7f
        val px = cx + ux * along + nx * off
        val py = cy + uy * along + ny * off
        val t = minDim * 0.02f
        drawLine(
            prismTone.copy(alpha = alpha * 0.06f),
            Offset(px - nx * t, py - ny * t),
            Offset(px + nx * t, py + ny * t),
            hairline,
        )
    }

    // Veils: fans of nested quadratics sharing endpoints — the wispy curved
    // sheets of partially healed fracture planes. One cluster spans half the
    // short edge; the rest stay smaller.
    var chordAx = w * 0.3f
    var chordAy = h * 0.4f
    var chordBx = w * 0.6f
    var chordBy = h * 0.6f
    val clusters = if (panel) 2 else 4
    repeat(clusters) { ci ->
        val span = minDim * (if (ci == 0) 0.5f else 0.26f + 0.16f * fract01(ci * 0.318309f + 0.21f))
        val ax = w * (0.08f + 0.7f * fract01(ci * 0.754877f + 0.11f))
        val ay = h * (0.12f + 0.75f * fract01(ci * 0.569840f + 0.41f))
        val ang = fract01(ci * 0.867532f + 0.05f) * (2f * PI.toFloat())
        val bx = ax + cos(ang) * span
        val by = ay + sin(ang) * span
        if (ci == clusters - 1) {
            chordAx = ax
            chordAy = ay
            chordBx = bx
            chordBy = by
        }
        val fan = minDim * (0.02f + 0.03f * fract01(ci * 0.271828f + 0.53f))
        val sheets = if (panel) 4 else 5 + (fract01(ci * 0.5417f + 0.37f) * 3.99f).toInt()
        repeat(sheets) { si ->
            val k = (si.toFloat() / max(1, sheets - 1) - 0.5f) * 2f
            val veil =
                Path().apply {
                    moveTo(ax, ay)
                    quadraticTo(
                        (ax + bx) / 2f - sin(ang) * fan * k * 2.2f,
                        (ay + by) / 2f + cos(ang) * fan * k * 2.2f,
                        bx,
                        by,
                    )
                }
            drawPath(
                veil,
                veilTone.copy(alpha = alpha * (0.04f + 0.06f * fract01((ci * 9 + si) * 0.618034f))),
                style = Stroke(width = hairline),
            )
        }
    }

    // Rainbow flashes: small and rare — thin-film interference clipped to a
    // slim parallelogram along the last veil's chord, never free-floating.
    val chordDx = chordBx - chordAx
    val chordDy = chordBy - chordAy
    val chordLen = max(1f, sqrt(chordDx * chordDx + chordDy * chordDy))
    val cux = chordDx / chordLen
    val cuy = chordDy / chordLen
    repeat(if (panel) 1 else 2) { fi ->
        val t = 0.2f + 0.5f * fract01(fi * 0.754877f + 0.61f)
        val fcx = chordAx + cux * chordLen * t
        val fcy = chordAy + cuy * chordLen * t
        val len = minDim * (0.08f + 0.07f * fract01(fi * 0.414214f + 0.27f))
        val wid = minDim * (0.015f + 0.015f * fract01(fi * 0.271828f + 0.73f))
        val skew = len * 0.18f
        val fa = alpha * (0.15f + 0.13f * fract01(fi * 0.618034f + 0.47f))
        val flash =
            Path().apply {
                moveTo(fcx - cux * len / 2f + cuy * wid / 2f, fcy - cuy * len / 2f - cux * wid / 2f)
                lineTo(fcx + cux * len / 2f + cuy * wid / 2f, fcy + cuy * len / 2f - cux * wid / 2f)
                lineTo(fcx + cux * (len / 2f + skew) - cuy * wid / 2f, fcy + cuy * (len / 2f + skew) + cux * wid / 2f)
                lineTo(fcx - cux * (len / 2f - skew) - cuy * wid / 2f, fcy - cuy * (len / 2f - skew) + cux * wid / 2f)
                close()
            }
        drawPath(
            flash,
            Brush.linearGradient(
                0f to Color(0xFFFF9AA2).copy(alpha = fa),
                0.25f to Color(0xFFFFE29A).copy(alpha = fa),
                0.5f to Color(0xFF9AF0C8).copy(alpha = fa),
                0.75f to Color(0xFF9AC8FF).copy(alpha = fa),
                1f to Color(0xFFD9A9FF).copy(alpha = fa),
                start = Offset(fcx - cux * len / 2f, fcy - cuy * len / 2f),
                end = Offset(fcx + cux * len / 2f, fcy + cuy * len / 2f),
            ),
        )
    }

    // Point glints: crossed hairlines with a soft dot.
    repeat(if (panel) 1 else 2) { gi ->
        val gp =
            Offset(
                w * (0.15f + 0.7f * fract01(gi * 0.618034f + 0.77f)),
                h * (0.3f + 0.55f * fract01(gi * 0.754877f + 0.23f)),
            )
        val ga = alpha * 0.25f
        val arm = minDim * 0.015f
        drawLine(Color.White.copy(alpha = ga), Offset(gp.x - arm, gp.y), Offset(gp.x + arm, gp.y), hairline)
        drawLine(Color.White.copy(alpha = ga), Offset(gp.x, gp.y - arm), Offset(gp.x, gp.y + arm), hairline)
        val dot = minDim * 0.005f
        drawCircle(
            Brush.radialGradient(0f to Color.White.copy(alpha = ga), 1f to Color.Transparent, center = gp, radius = dot),
            dot,
            gp,
        )
    }
}

private class KyaniteBlade(
    val path: Path,
    val axisStart: Offset,
    val axisEnd: Offset,
    val normal: Offset,
    val center: Offset,
    val length: Float,
    val width: Float,
    val front: Boolean,
    val seed: Int,
)

/**
 * Kyanite: the zoned parallel-blade system. Long flat laths (8-12:1) within
 * ~15 degrees of a shared direction, splintery angled ends, colour zoning
 * ACROSS each blade (pale edges, deep sapphire spine slightly off-centre),
 * lengthwise striations, a pearly sheen band on some blades and a silver
 * edge streak on the front one.
 */
private fun DrawScope.drawKyaniteTexture(
    alpha: Float,
    panel: Boolean,
) {
    val w = size.width
    val h = size.height
    val minDim = min(w, h)
    val hairline = max(1.dp.toPx(), minDim * 0.0015f)
    val deep = Color(0xFF1B3C7A)
    val deepFront = Color(0xFF142E63)
    val edgeA = Color(0xFF7FA8D9)
    val edgeB = Color(0xFF9FC3E8)
    val pearl = Color(0xFFE8F1FA)

    drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFF0D1B33).copy(alpha = alpha * 0.6f)))

    val count = if (panel) 4 else 8
    val thetaBase = (35f + 20f * fract01(0.437f)) * DEG
    val blades =
        List(count) { i ->
            val front = i >= count - 2
            val len = minDim * (0.5f + 0.4f * fract01(i * 0.414214f + 0.09f))
            val wid = len / (8f + 4f * fract01(i * 0.569840f + 0.45f))
            val bladeTheta = thetaBase + (fract01(i * 0.271828f + 0.31f) - 0.5f) * 30f * DEG
            val ux = cos(bladeTheta)
            val uy = -sin(bladeTheta)
            val nxx = -uy
            val nyy = ux
            val ccx = w * (0.06f + 0.88f * fract01(i * 0.618034f + 0.19f))
            val ccy = h * (0.12f + 0.76f * fract01(i * 0.754877f + 0.53f))
            // Angled, splintery parallelogram ends (60-75 degrees, no points)
            // and a slight taper toward the far end.
            val sk1 = wid * (0.13f + 0.16f * fract01(i * 0.318309f + 0.71f))
            val sk2 = wid * (0.13f + 0.16f * fract01(i * 0.867532f + 0.27f))
            val wFar = wid * 0.78f
            val e1x = ccx - ux * len / 2f
            val e1y = ccy - uy * len / 2f
            val e2x = ccx + ux * len / 2f
            val e2y = ccy + uy * len / 2f
            val path =
                Path().apply {
                    moveTo(e1x + nxx * wid / 2f - ux * sk1, e1y + nyy * wid / 2f - uy * sk1)
                    lineTo(e2x + nxx * wFar / 2f + ux * sk2, e2y + nyy * wFar / 2f + uy * sk2)
                    lineTo(e2x - nxx * wFar / 2f - ux * sk2, e2y - nyy * wFar / 2f - uy * sk2)
                    lineTo(e1x - nxx * wid / 2f + ux * sk1, e1y - nyy * wid / 2f + uy * sk1)
                    close()
                }
            KyaniteBlade(path, Offset(e1x, e1y), Offset(e2x, e2y), Offset(nxx, nyy), Offset(ccx, ccy), len, wid, front, i)
        }

    // Back-to-front: back blades 20% darker and more translucent.
    blades.forEach { blade ->
        val zc = 0.45f + 0.10f * fract01(blade.seed * 0.5417f + 0.13f)
        val core = if (blade.front && fract01(blade.seed * 0.31f) > 0.5f) deepFront else deep
        val shade = if (blade.front) 0f else 0.2f
        val bladeAlpha =
            if (blade.front) {
                0.85f + 0.15f * fract01(blade.seed * 0.77f)
            } else {
                0.5f + 0.2f * fract01(blade.seed * 0.77f)
            }
        val half = blade.width / 2f
        drawPath(
            blade.path,
            Brush.linearGradient(
                0f to lerp(edgeA, Color.Black, shade).copy(alpha = alpha * bladeAlpha),
                zc to lerp(core, Color.Black, shade).copy(alpha = alpha * bladeAlpha),
                1f to lerp(edgeB, Color.Black, shade).copy(alpha = alpha * bladeAlpha),
                start = Offset(blade.center.x - blade.normal.x * half, blade.center.y - blade.normal.y * half),
                end = Offset(blade.center.x + blade.normal.x * half, blade.center.y + blade.normal.y * half),
            ),
        )
    }

    // Striations along the length, then a pearly sheen band on every third
    // blade (confined to the blade by reusing its own path as the fill).
    blades.forEachIndexed { i, blade ->
        val ux = (blade.axisEnd.x - blade.axisStart.x) / blade.length
        val uy = (blade.axisEnd.y - blade.axisStart.y) / blade.length
        val strias = if (panel) 3 else 3 + (fract01(i * 0.414214f + 0.61f) * 3.99f).toInt()
        repeat(strias) { s ->
            val tAcross = (fract01((i * 7 + s) * 0.618034f + 0.29f) - 0.5f) * 0.8f * blade.width
            val fracLen = 0.6f + 0.35f * fract01((i * 7 + s) * 0.754877f + 0.11f)
            val shift = (fract01((i * 7 + s) * 0.271828f + 0.43f) - 0.5f) * (1f - fracLen) * blade.length
            val cxs = blade.center.x + blade.normal.x * tAcross + ux * shift
            val cys = blade.center.y + blade.normal.y * tAcross + uy * shift
            val halfLen = blade.length * fracLen / 2f
            drawLine(
                lerp(edgeA, pearl, fract01((i * 7 + s) * 0.569840f))
                    .copy(alpha = alpha * (0.10f + 0.15f * fract01((i * 7 + s) * 0.867532f))),
                Offset(cxs - ux * halfLen, cys - uy * halfLen),
                Offset(cxs + ux * halfLen, cys + uy * halfLen),
                hairline,
            )
        }
        if (i % 3 == 1) {
            val tm = 0.3f + 0.4f * fract01(i * 0.318309f + 0.57f)
            val bw = 0.08f + 0.05f * fract01(i * 0.5417f + 0.83f)
            drawPath(
                blade.path,
                Brush.linearGradient(
                    0f to Color.Transparent,
                    tm - bw to Color.Transparent,
                    tm to pearl.copy(alpha = alpha * 0.14f),
                    tm + bw to Color.Transparent,
                    1f to Color.Transparent,
                    start = blade.axisStart,
                    end = blade.axisEnd,
                ),
            )
        }
    }

    // Silver streak on one long edge of the front blade, plus a tip glint.
    val frontBlade = blades.last()
    val fux = (frontBlade.axisEnd.x - frontBlade.axisStart.x) / frontBlade.length
    val fuy = (frontBlade.axisEnd.y - frontBlade.axisStart.y) / frontBlade.length
    val edgeX = frontBlade.center.x + frontBlade.normal.x * frontBlade.width * 0.48f
    val edgeY = frontBlade.center.y + frontBlade.normal.y * frontBlade.width * 0.48f
    val streak = frontBlade.length * (0.3f + 0.2f * fract01(0.9134f))
    drawLine(
        pearl.copy(alpha = alpha * 0.4f),
        Offset(edgeX - fux * streak / 2f, edgeY - fuy * streak / 2f),
        Offset(edgeX + fux * streak / 2f, edgeY + fuy * streak / 2f),
        hairline,
    )
    if (!panel) {
        val tip = frontBlade.axisEnd
        val arm = minDim * 0.012f
        drawLine(Color.White.copy(alpha = alpha * 0.2f), Offset(tip.x - arm, tip.y), Offset(tip.x + arm, tip.y), hairline)
        drawLine(Color.White.copy(alpha = alpha * 0.2f), Offset(tip.x, tip.y - arm), Offset(tip.x, tip.y + arm), hairline)
    }
}

/**
 * Onyx: the parallel-arc band system — never agate's loops. All bands are
 * arcs of concentric circles around one far-off-canvas centre, so they run
 * gently curved and strictly parallel. The [onyxBandRhythm] group covers
 * under half the canvas, leaving a wide mirror-black field; broad bands keep
 * one crisp and one softly graded edge, hairlines fade out along their arc,
 * and a single diagonal gloss sweep crosses the band direction.
 */
private fun DrawScope.drawOnyxTexture(
    alpha: Float,
    panel: Boolean,
) {
    val w = size.width
    val h = size.height
    val d = max(w, h)
    val minDim = min(w, h)
    val hairline = max(1.dp.toPx(), minDim * 0.0015f)
    val broadTone = Color(0xFFB9B2A6)
    val hairTone = Color(0xFFEDEAE3)
    val midTone = Color(0xFF6E6A63)
    val warmTone = Color(0xFFA98253)

    // Base: barely-there vertical lift off pure black.
    drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFF0C0E11).copy(alpha = alpha * 0.8f)))

    val phi = (68f + 44f * fract01(0.941f)) * DEG
    val dist = minDim * (2.5f + 1.2f * fract01(0.377f))
    val c0x = w * 0.5f + cos(phi) * dist
    val c0y = h * 0.5f - sin(phi) * dist
    val span = h * (0.35f + 0.2f * fract01(0.613f))
    val r0 = dist - h * 0.5f + h * 0.38f
    val bands = onyxBandRhythm(seed = 3.1f, span = span, minDim = minDim, units = if (panel) 2 else 3)
    val thetaC = atan2(h * 0.5f - c0y, w * 0.5f - c0x) * (180f / PI.toFloat())
    bands.forEach { band ->
        val r = r0 + band.offset
        when (band.kind) {
            OnyxBandKind.BROAD -> {
                // Crisp full-alpha outer edge; the inner quarter of the width
                // fades down to 60%.
                val outer = r + band.width / 2f
                val inner = r - band.width / 2f
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            inner / outer to broadTone.copy(alpha = alpha * band.alpha * 0.6f),
                            (inner + band.width * 0.25f) / outer to broadTone.copy(alpha = alpha * band.alpha),
                            1f to broadTone.copy(alpha = alpha * band.alpha),
                            center = Offset(c0x, c0y),
                            radius = outer,
                        ),
                    radius = r,
                    center = Offset(c0x, c0y),
                    style = Stroke(width = band.width),
                )
            }
            OnyxBandKind.MEDIUM ->
                drawCircle(
                    midTone.copy(alpha = alpha * band.alpha),
                    r,
                    Offset(c0x, c0y),
                    style = Stroke(width = band.width),
                )
            OnyxBandKind.WARM ->
                drawCircle(
                    warmTone.copy(alpha = alpha * band.alpha),
                    r,
                    Offset(c0x, c0y),
                    style = Stroke(width = band.width),
                )
            OnyxBandKind.HAIRLINE -> {
                // Drawn as an explicit arc so one end can fade along its length.
                val halfSpan = asin((0.75f * d / r).coerceIn(0f, 0.95f)) * (180f / PI.toFloat())
                val fadeAtStart = fract01(band.offset * 0.754877f) < 0.5f
                val sweep = 2f * halfSpan
                val fade = sweep * 0.13f
                drawArc(
                    color = hairTone.copy(alpha = alpha * band.alpha),
                    startAngle = if (fadeAtStart) thetaC - halfSpan + fade else thetaC - halfSpan,
                    sweepAngle = sweep - fade,
                    useCenter = false,
                    topLeft = Offset(c0x - r, c0y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = max(hairline * 0.8f, band.width)),
                )
                drawArc(
                    color = hairTone.copy(alpha = alpha * band.alpha * 0.25f),
                    startAngle = if (fadeAtStart) thetaC - halfSpan else thetaC + halfSpan - fade,
                    sweepAngle = fade,
                    useCenter = false,
                    topLeft = Offset(c0x - r, c0y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = max(hairline * 0.8f, band.width)),
                )
            }
        }
    }

    // One diagonal specular sweep crossing the band direction, with a single
    // hairline glint inside it. No shadows anywhere — onyx is mirror-gloss.
    val glossDir = phi + 90f * DEG + (25f + 15f * fract01(0.271f)) * DEG
    val gx = cos(glossDir)
    val gy = -sin(glossDir)
    val gpx = w * 0.58f
    val gpy = h * 0.52f
    val halfBand = minDim * 0.15f
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = alpha * 0.07f),
            1f to Color.Transparent,
            start = Offset(gpx + gy * halfBand, gpy - gx * halfBand),
            end = Offset(gpx - gy * halfBand, gpy + gx * halfBand),
        ),
    )
    val glintLen = minDim * 0.12f
    drawLine(
        Color.White.copy(alpha = alpha * 0.15f),
        Offset(gpx - gx * glintLen, gpy - gy * glintLen),
        Offset(gpx + gx * glintLen, gpy + gy * glintLen),
        hairline,
    )
}

/**
 * The shared crystalline language for the legacy accent themes: 2-3 huge
 * whisper-alpha facet planes (the alpha step IS the edge), a few kinked
 * hairline fractures with one micro-branch, and a single glint (rarely two)
 * kept out of the top content safe-area. Counts and alphas are constant
 * across themes; only angles, positions and the glint vary with the theme's
 * seed, so the family reads related rather than randomized. Light-surface
 * themes flip the ink to black and pull all alphas down.
 */
private fun DrawScope.drawGenericCrystalline(
    theme: AppTheme,
    primary: Color,
    alpha: Float,
    panel: Boolean,
) {
    val w = size.width
    val h = size.height
    val minDim = min(w, h)
    val hairline = max(1.dp.toPx(), minDim * 0.0015f)
    val light = theme.isLight
    val a = alpha * (if (light) 0.7f else 1f)
    val ink = if (light) Color.Black else Color.White
    val facetTint = lerp(primary, ink, 0.7f)
    val s = theme.ordinal.toFloat()

    // Facet planes: alphas fixed so any two overlapping planes stay <= 0.08
    // combined (the cap over text regions).
    val quadAlpha = floatArrayOf(0.045f, 0.03f, 0.02f)
    val baseAng = fract01(s * 0.618034f + 0.07f) * (2f * PI.toFloat())
    repeat(if (panel) 2 else 3) { q ->
        val ang = baseAng + (fract01(s * 0.754877f + q * 0.414214f) - 0.5f) * 24f * DEG
        val ux = cos(ang)
        val uy = sin(ang)
        val qcx = w * (0.16f + 0.34f * q + 0.2f * (fract01(s * 0.271828f + q * 0.569840f) - 0.5f))
        val qcy = h * (0.25f + 0.5f * fract01(s * 0.318309f + q * 0.867532f))
        val lenq = minDim * (0.6f + 0.6f * fract01(s * 0.5417f + q * 0.31f))
        val widq = lenq * (0.35f + 0.3f * fract01(s * 0.13f + q * 0.77f))
        val quad =
            Path().apply {
                moveTo(qcx - ux * lenq / 2f + uy * widq / 2f, qcy - uy * lenq / 2f - ux * widq / 2f)
                lineTo(qcx + ux * lenq / 2f + uy * widq / 2f, qcy + uy * lenq / 2f - ux * widq / 2f)
                lineTo(qcx + ux * lenq / 2f - uy * widq / 2f, qcy + uy * lenq / 2f + ux * widq / 2f)
                lineTo(qcx - ux * lenq / 2f - uy * widq / 2f, qcy - uy * lenq / 2f + ux * widq / 2f)
                close()
            }
        drawPath(quad, facetTint.copy(alpha = a * quadAlpha[q]))
    }

    // Hairline fractures: three segments with one clear 15-35 degree kink;
    // the first fracture grows a micro-branch at its kink.
    var glintAnchorX = w * 0.5f
    var glintAnchorY = h * 0.6f
    repeat(if (panel) 2 else 3) { f ->
        var px = w * (0.1f + 0.8f * fract01(s * 0.754877f + f * 0.618034f + 0.37f))
        var py = h * (0.18f + 0.7f * fract01(s * 0.569840f + f * 0.271828f + 0.11f))
        val total = minDim * (0.4f + 0.5f * fract01(s * 0.414214f + f * 0.867532f))
        var ang = fract01(s * 0.318309f + f * 0.5417f) * (2f * PI.toFloat())
        var kinkX = px
        var kinkY = py
        var kinkAng = ang
        val crack = Path().apply { moveTo(px, py) }
        for (seg in 0 until 3) {
            px += cos(ang) * total / 3f
            py += sin(ang) * total / 3f
            crack.lineTo(px, py)
            if (seg == 0) {
                kinkX = px
                kinkY = py
                ang += (15f + 20f * fract01(s * 0.13f + f * 0.7f)) * DEG *
                    (if (fract01(f * 0.9f + s * 0.77f) < 0.5f) 1f else -1f)
                kinkAng = ang
            } else {
                ang += (fract01(s * 0.41f + f * 0.3f + seg * 0.7f) - 0.5f) * 10f * DEG
            }
        }
        drawPath(
            crack,
            ink.copy(alpha = a * (0.06f + 0.06f * fract01(s * 0.5f + f * 0.61f))),
            style = Stroke(width = hairline),
        )
        if (f == 0) {
            val bAng = kinkAng + 1.2f
            drawLine(
                ink.copy(alpha = a * 0.05f),
                Offset(kinkX, kinkY),
                Offset(kinkX + cos(bAng) * minDim * 0.05f, kinkY + sin(bAng) * minDim * 0.05f),
                hairline,
            )
            glintAnchorX = kinkX
            glintAnchorY = kinkY
        }
    }

    // Glint(s): sitting ON the first fracture's kink, clamped out of the top
    // ~20% content safe-area and the side gutters.
    val glints = if (!panel && fract01(s * 0.867532f + 0.29f) > 0.7f) 2 else 1
    repeat(glints) { g ->
        val rawX = if (g == 0) glintAnchorX else w * fract01(s * 0.31f + 0.83f)
        val rawY = if (g == 0) glintAnchorY else h * fract01(s * 0.77f + 0.53f)
        val gpx = rawX.coerceIn(w * 0.08f, w * 0.92f)
        val gpy = rawY.coerceIn(h * 0.25f, h * 0.9f)
        val ga = a * (0.15f + 0.10f * fract01(s * 0.41f + g * 0.5f))
        val armL = minDim * 0.02f
        val armS = minDim * 0.01f
        drawLine(ink.copy(alpha = ga), Offset(gpx - armL, gpy), Offset(gpx + armL, gpy), hairline)
        drawLine(ink.copy(alpha = ga), Offset(gpx, gpy - armS), Offset(gpx, gpy + armS), hairline)
        val dot = minDim * 0.004f
        drawCircle(
            Brush.radialGradient(
                0f to ink.copy(alpha = ga),
                1f to Color.Transparent,
                center = Offset(gpx, gpy),
                radius = dot,
            ),
            dot,
            Offset(gpx, gpy),
        )
    }
}

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

    fun TextStyle.body(tracking: Float? = null) = scaled().copy(fontFamily = ManropeFamily, letterSpacing = tracking?.sp ?: letterSpacing)
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
