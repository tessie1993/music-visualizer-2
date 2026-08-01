package dev.musicviz.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Crystal interactive controls: the faceted counterparts of Material's
 * Button / Slider / NavigationBar / TabRow / segmented selector, so every
 * touch target in the shell carries the same cut-gem language as the panels
 * in Crystal.kt. Selection is always marked the same two ways — the shard
 * silhouette lights up, and a small CrystalGem diamond appears.
 */

// ------------------------------------------------------------- buttons

/**
 * Faceted button in the shard silhouette. [filled] is the primary action
 * (luminous gradient gem fill + outer halo); un-filled is the quiet variant
 * (translucent glass + luminous edge), replacing OutlinedButton.
 */
@Composable
fun CrystalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = crystalShardShape(if (compact) 9.dp else 12.dp, 4.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, label = "crystalButtonPress")
    val fill =
        if (filled) {
            Brush.verticalGradient(
                0f to lerp(cs.primary, Color.White, 0.28f),
                0.35f to cs.primary,
                1f to lerp(cs.primary, Color.Black, 0.30f),
            )
        } else {
            Brush.verticalGradient(
                0f to cs.primary.copy(alpha = 0.16f),
                1f to cs.primary.copy(alpha = 0.05f),
            )
        }
    val edge =
        Brush.verticalGradient(
            0f to (if (filled) Color.White.copy(alpha = 0.75f) else cs.primary.copy(alpha = 0.75f)),
            0.55f to cs.primary.copy(alpha = if (filled) 0.35f else 0.25f),
            1f to cs.primary.copy(alpha = 0.55f),
        )
    Row(
        modifier
            .scale(press)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (filled && enabled) {
                    Modifier.drawBehind { crystalHalo(cs.primary, 12.dp.toPx(), 0.7f) }
                } else {
                    Modifier
                },
            ).clip(shape)
            .background(fill)
            .drawBehind { crystalFacets(if (filled) 1.6f else 0.9f) }
            .border(1.dp, edge, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = if (compact) 36.dp else 44.dp)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Text on the filled gem rides onAccentTextColor(): "White font" must
        // not paint a label white on a near-white primary (Clear Quartz, Mono).
        val label = if (filled) onAccentTextColor() else accentTextColor()
        CompositionLocalProvider(LocalContentColor provides label) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.8.sp)) {
                content()
            }
        }
    }
}

/**
 * The hero transport control: a diamond-cut gem with a luminous gradient
 * fill and halo, holding the play/pause icon. The 50%-cut corners turn the
 * square into the kit's gem silhouette at button scale.
 */
@Composable
fun CrystalPlayButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val shape = CutCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, label = "crystalPlayPress")
    Box(
        modifier
            .size(56.dp)
            .scale(press)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(if (enabled) Modifier.softGlow(cs.primary, 16.dp, 0.9f) else Modifier)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to lerp(cs.primary, Color.White, 0.35f),
                    0.45f to cs.primary,
                    1f to lerp(cs.primary, Color.Black, 0.35f),
                ),
            ).border(1.dp, Color.White.copy(alpha = 0.65f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = cs.onPrimary)
    }
}

// ------------------------------------------------------------- slider

/**
 * Crystal slider: a thin luminous track whose active side runs
 * secondary→primary, gem-diamond thumb with bloom, and small tick diamonds
 * when [steps] > 0. Drop-in for the Material Slider call sites in this app
 * (value/range/steps/enabled only). The custom thumb/track Slider overload
 * is still experimental in Material 3, hence the opt-in.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CrystalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        interactionSource = interaction,
        thumb = {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(13.dp)
                        .then(if (enabled) Modifier.softGlow(cs.primary, 8.dp, 0.9f) else Modifier)
                        .rotate(45f)
                        .background(
                            Brush.linearGradient(
                                listOf(lerp(cs.primary, Color.White, 0.55f), cs.primary),
                            ),
                        ).border(1.dp, Color.White.copy(alpha = if (enabled) 0.8f else 0.3f)),
                )
            }
        },
        track = {
            val span = valueRange.endInclusive - valueRange.start
            val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
            val dim = if (enabled) 1f else 0.4f
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val y = size.height / 2f
                drawLine(
                    color = cs.onSurface.copy(alpha = 0.16f * dim),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (steps > 0) {
                    for (i in 1..steps) {
                        val x = size.width * i / (steps + 1)
                        drawCircle(cs.onSurface.copy(alpha = 0.30f * dim), radius = 1.5.dp.toPx(), center = Offset(x, y))
                    }
                }
                if (fraction > 0f) {
                    val endX = size.width * fraction
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(cs.secondary, cs.primary), endX = endX.coerceAtLeast(1f)),
                        start = Offset(0f, y),
                        end = Offset(endX, y),
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    // Soft light spill under the lit part of the track.
                    drawLine(
                        color = cs.primary.copy(alpha = 0.20f * dim),
                        start = Offset(0f, y),
                        end = Offset(endX, y),
                        strokeWidth = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        },
    )
}

// ------------------------------------------------------------- navigation

/** One destination of [CrystalNavBar]. */
data class CrystalNavItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * Bottom navigation as cut glass: gradient glass bar, and the selected
 * destination lit by a floating gem diamond above its icon. Items expose
 * selectable semantics (Role.Tab) like Material's NavigationBarItem.
 */
@Composable
fun CrystalNavBar(
    items: List<CrystalNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    opacity: Float,
) {
    val cs = MaterialTheme.colorScheme
    val fillAlpha = (opacity * 0.9f).coerceIn(0f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to cs.surfaceContainer.copy(alpha = fillAlpha),
                    1f to lerp(cs.surfaceContainer, Color.Black, 0.4f).copy(alpha = (fillAlpha + 0.05f).coerceAtMost(1f)),
                ),
            ).windowInsetsPadding(WindowInsets.navigationBars)
            .height(68.dp)
            .selectableGroup(),
    ) {
        // The icon keeps the accent; the LABEL follows accentTextColor() so
        // "White font" reaches the nav writing too (the icon is not writing).
        val selectedLabel = accentTextColor()
        items.forEachIndexed { i, item ->
            val sel = i == selected
            val tint by animateColorAsState(
                if (sel) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.75f),
                label = "crystalNavTint",
            )
            val labelTint by animateColorAsState(
                if (sel) selectedLabel else cs.onSurfaceVariant.copy(alpha = 0.75f),
                label = "crystalNavLabel",
            )
            val gemAlpha by animateFloatAsState(if (sel) 1f else 0f, label = "crystalNavGem")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(7.dp).graphicsLayer { alpha = gemAlpha }) {
                    CrystalGem(cs.primary, size = 7.dp)
                }
                Spacer(Modifier.height(3.dp))
                Icon(item.icon, item.label, tint = tint)
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = labelTint,
                )
            }
        }
    }
}

/**
 * Tab strip in the crystal language: transparent, a luminous hairline as the
 * divider, and the selected title underscored by a gem diamond instead of a
 * Material indicator. Space for the gem is always reserved so titles do not
 * jump when selection moves.
 */
@Composable
fun CrystalTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    ScrollableTabRow(
        selectedTabIndex = selected,
        modifier = modifier,
        edgePadding = 8.dp,
        containerColor = Color.Transparent,
        indicator = { },
        divider = {
            Box(Modifier.fillMaxWidth().height(1.dp).luminousHairline(cs.primary.copy(alpha = 0.45f)))
        },
    ) {
        titles.forEachIndexed { i, title ->
            val sel = i == selected
            Tab(
                selected = sel,
                onClick = { onSelect(i) },
                // Selection stays legible under "White font" (where both
                // colours resolve to white) through the gem and the weight.
                selectedContentColor = accentTextColor(),
                unselectedContentColor = cs.onSurfaceVariant.copy(alpha = 0.7f),
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Spacer(Modifier.height(3.dp))
                        val gemAlpha by animateFloatAsState(if (sel) 1f else 0f, label = "crystalTabGem")
                        Box(Modifier.size(5.dp).graphicsLayer { alpha = gemAlpha }) {
                            CrystalGem(cs.primary, size = 5.dp)
                        }
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------- segmented

/**
 * Segmented selector cut as one shard: cells share a faceted glass body,
 * the chosen cell fills with primary glass behind a gem marker. Replaces
 * the "● label" OutlinedButton rows.
 */
@Composable
fun CrystalSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = crystalShardShape(10.dp, 4.dp)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(cs.surfaceVariant.copy(alpha = 0.22f))
            .drawBehind { crystalFacets(0.8f) }
            .border(1.dp, cs.primary.copy(alpha = 0.35f), shape),
    ) {
        options.forEachIndexed { i, label ->
            if (i > 0) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(cs.primary.copy(alpha = 0.18f)))
            }
            val sel = i == selected
            val fill by animateColorAsState(
                if (sel) cs.primary.copy(alpha = 0.30f) else Color.Transparent,
                label = "crystalSegmentFill",
            )
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(fill)
                    .selectable(selected = sel, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val gemAlpha by animateFloatAsState(if (sel) 1f else 0f, label = "crystalSegmentGem")
                Box(Modifier.size(5.dp).graphicsLayer { alpha = gemAlpha }) {
                    CrystalGem(cs.primary, size = 5.dp)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                )
            }
        }
    }
}
