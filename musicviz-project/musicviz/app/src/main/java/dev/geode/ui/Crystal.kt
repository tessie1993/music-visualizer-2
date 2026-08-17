package dev.geode.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.geode.render.VisualizerView
import dev.geode.ui.theme.LocalThemePack
import dev.geode.ui.theme.ThemePack
import dev.geode.ui.theme.colorScheme
import dev.geode.ui.theme.stoneTypography
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Crystal design kit - the tumbled-stone language from the crystal theme
 * packs: photographed mineral surfaces with the light inside the stone,
 * Mali UI type with Mystery Quest display headings, soft settled glows and
 * ambient stone backdrops. Nothing mineral is drawn procedurally any more;
 * the packs' photographed art IS the material (see ui/theme/ThemePack.kt).
 */

/**
 * MaterialTheme plus the selected stone pack. Keeping these together
 * prevents panels from silently falling back to the default pack when a
 * screen is moved or reused elsewhere in the shell.
 *
 * Also the single provider of [LocalFontColor]: the RESOLVED font colour
 * override (null when automatic, or when the light-pack contrast gate in
 * [resolvedFontColor] rejected it), so [accentTextColor] call sites never
 * have to re-derive whether the override is readable.
 *
 * And of [LocalContentColor], which Material does NOT provide: the shell's
 * `Scaffold` deliberately runs `containerColor = Color.Transparent` so the
 * stone backdrop shows through, and `contentColorFor(Transparent)` matches no
 * role and would hand the whole app black-on-near-black. Providing it HERE
 * rather than on the Scaffold covers the overlays composed outside it too
 * (search, the crash dialog, the fullscreen visualizer, the second screen).
 */
@Composable
internal fun CrystalMaterialTheme(
    pack: ThemePack,
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    val fontColor = pack.resolvedFontColor(gui.fontColorOverride, gui.backgroundDim)
    val scheme =
        pack.colorScheme(
            accentIntensity = gui.accentIntensity,
            backgroundDim = gui.backgroundDim,
            fontColorOverride = fontColor?.let { Color(it) },
        )
    CompositionLocalProvider(
        LocalThemePack provides pack,
        LocalFontColor provides fontColor?.let { Color(it) },
        LocalContentColor provides scheme.onBackground,
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
 * Theme typography: Mali for UI copy, Mystery Quest for display headings,
 * exactly as the packs bundle them. [textScale] (the Appearance "Text size"
 * option, [GuiPrefs.textScale]) multiplies every font size, clamped to the
 * slider's bounds.
 */
fun crystalTypography(textScale: Float = 1f): Typography =
    stoneTypography(
        textScale.coerceIn(GuiPrefs.TEXT_SCALE_MIN, GuiPrefs.TEXT_SCALE_MAX),
    )

/**
 * Tumbled-pebble silhouette for small controls built without photographed
 * art (text fields, menus). The packs' corner model is a soft tumbled
 * superellipse; a generous rounded corner is its live-layout equivalent -
 * the cut-gem corners of the old kit are gone with the rest of that look.
 */
fun crystalShardShape(
    cut: Dp = 12.dp,
    minor: Dp = 4.dp,
): Shape = RoundedCornerShape((cut + minor) * 1.2f)

/**
 * Small round pebble marker - the kit's selection/indicator glyph, lit from
 * within like the pack contract's "steady low backlight".
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

/** Display screen title with the pack's soft glow settled behind the glyphs. */
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
 * Draws outside the layout bounds so keep it on unclipped containers.
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
 * Stone panel: the pack's photographed material tiled behind a translucent
 * surface tint, clipped to a tumbled corner and finished with a settled glow
 * hairline. Replaces the old procedural glass panel; the mineral character
 * now comes from the pack's real texture at its authored `surfaceOpacity`.
 *
 * The signature is unchanged from the old kit so panel call sites carry
 * over: [opacity]/[tint] still shape the glass base, [glow] still colours
 * the edge light ([sheen] joins it when [prismatic] marks a selection), and
 * [facets] now scales the material texture's presence instead of painted
 * glints.
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
        val pack = LocalThemePack.current
        val tile = ImageBitmap.imageResource(pack.material.tile)
        val alpha = opacity.coerceIn(0f, 1f)
        val texAlpha = (pack.material.surfaceOpacity * facets.coerceIn(0f, 1.5f)).coerceIn(0f, 1f)
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
                    0f to glow.copy(alpha = (0.85f * glowStrength).coerceAtMost(1f)),
                    0.55f to glow.copy(alpha = 0.22f * glowStrength),
                    1f to glow.copy(alpha = 0.45f * glowStrength),
                )
            }
        this
            .clip(shape)
            .background(tint.copy(alpha = alpha))
            .drawBehind {
                // Tile the stone texture edge to edge; the art is seamless.
                if (texAlpha > 0f) {
                    val tw = tile.width
                    val th = tile.height
                    if (tw > 0 && th > 0) {
                        clipRect {
                            var y = 0
                            while (y < size.height.roundToInt()) {
                                var x = 0
                                while (x < size.width.roundToInt()) {
                                    drawImage(
                                        tile,
                                        dstOffset = IntOffset(x, y),
                                        dstSize = IntSize(tw, th),
                                        alpha = texAlpha,
                                    )
                                    x += tw
                                }
                                y += th
                            }
                        }
                    }
                }
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

/**
 * The pack's ambient stone photograph behind shell screens, orientation-
 * matched and centre-cropped, with a light wash of the (possibly dimmed)
 * theme background so writing keeps its measured contrast on busy areas of
 * the stone.
 *
 * The art is static by design - the pack contract keeps ambient light
 * settled - so [reducedMotion] needs nothing extra here.
 */
@Composable
fun CrystalBackground(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") reducedMotion: Boolean = false,
) {
    val pack = LocalThemePack.current
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val art =
            when {
                maxWidth > maxHeight -> pack.material.ambientLandscape
                else -> pack.material.ambientPortrait
            }
        Image(
            painter = painterResource(art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(cs.background.copy(alpha = 0.16f)),
        )
    }
}

// ------------------------------------------------------------- canvas host

/**
 * Hosts the shared [VisualizerView] in Compose, detaching it from any
 * previous parent first - the single GL view moves between Now Playing and
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
