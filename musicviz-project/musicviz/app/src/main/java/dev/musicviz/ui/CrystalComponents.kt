package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Crystal component kit — the concrete widgets from the MusicViz design
 * mockups (Clear Quartz / Rose Quartz / Sugilite / Lapis / Malachite /
 * Amethyst / Kyanite / Onyx): pill glass buttons, tracked-caps tabs with a
 * luminous underline, segmented controls, chips, glass search bar and text
 * fields, sliders with value badges, crystal-texture thumbnails, list-item
 * cards and the glass bottom navigation. All of them derive their color from
 * the active MaterialTheme so every theme keeps its character.
 */

// ---------------------------------------------------------------- buttons

enum class CrystalButtonKind { PRIMARY, SECONDARY, GHOST }

/**
 * Pill glass button per the mockups' component library: PRIMARY is a filled
 * luminous pill ("Play Track"), SECONDARY is quiet glass ("Shuffle All"),
 * GHOST is a near-transparent outline ("View Live").
 */
@Composable
fun CrystalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: CrystalButtonKind = CrystalButtonKind.PRIMARY,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val opacity: Float
    val tint: Color
    val glowStrength: Float
    when (kind) {
        CrystalButtonKind.PRIMARY -> {
            opacity = 0.55f
            tint = cs.primaryContainer
            glowStrength = 1.1f
        }
        CrystalButtonKind.SECONDARY -> {
            opacity = 0.3f
            tint = cs.surfaceVariant
            glowStrength = 0.55f
        }
        CrystalButtonKind.GHOST -> {
            opacity = 0.08f
            tint = cs.surface
            glowStrength = 0.4f
        }
    }
    val content = if (kind == CrystalButtonKind.PRIMARY) cs.onPrimaryContainer else cs.onSurface
    val contentColor = if (enabled) content else content.copy(alpha = 0.4f)
    Row(
        modifier
            .crystalPanel(
                opacity,
                tint,
                cs.primary,
                corner = 24.dp,
                glowStrength = if (enabled) glowStrength else 0.15f,
            ).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                Modifier.size(18.dp),
                tint = if (enabled) cs.primary else contentColor,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Circular glow play/pause control (the mockups' hero transport button). */
@Composable
fun CrystalPlayButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .softGlow(cs.primary, 16.dp, if (enabled) 1f else 0.2f)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    0f to cs.primary.copy(alpha = 0.45f),
                    1f to cs.primary.copy(alpha = 0.15f),
                ),
            ).border(
                1.dp,
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.8f),
                    1f to cs.primary.copy(alpha = 0.5f),
                ),
                CircleShape,
            ).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            Modifier.size(size * 0.46f),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
        )
    }
}

// ------------------------------------------------------------------ chips

/** Pill chip ("Nightcall", "Film", "Breathe" …); [selected] lights it up. */
@Composable
fun CrystalChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .crystalPanel(
                if (selected) 0.5f else 0.22f,
                if (selected) cs.primaryContainer else cs.surfaceVariant,
                cs.primary,
                corner = 24.dp,
                glowStrength = if (selected) 1f else 0.3f,
            ).clickable(onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) cs.onPrimaryContainer else cs.onSurface,
        )
    }
}

/** Tiny meta badge ("NEW", "FLAC · LOSSLESS", "80%"). */
@Composable
fun CrystalBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background((if (accent) cs.primary else cs.surfaceVariant).copy(alpha = if (accent) 0.3f else 0.45f))
            .border(1.dp, cs.primary.copy(alpha = if (accent) 0.8f else 0.3f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontSize = 9.sp),
            color = if (accent) cs.onPrimaryContainer else cs.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------- tabs

/**
 * Tracked-caps tab strip (TRACKS · ALBUMS · ARTISTS …) with a luminous
 * underline on the selected tab, per every mockup's library header.
 */
@Composable
fun CrystalTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    t.uppercase(),
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.6.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                    color = if (sel) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .then(if (sel) Modifier.luminousHairline(cs.primary) else Modifier),
                )
            }
        }
    }
}

/** Segmented control (ROUNDED | CUT | SQUARE): glass rail, lit segment. */
@Composable
fun CrystalSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val rail = RoundedCornerShape(22.dp)
    val seg = RoundedCornerShape(19.dp)
    Row(
        modifier
            .clip(rail)
            .background(cs.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, cs.primary.copy(alpha = 0.25f), rail)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .clip(seg)
                    .then(
                        if (sel) {
                            Modifier
                                .background(
                                    Brush.verticalGradient(
                                        0f to cs.primary.copy(alpha = 0.4f),
                                        1f to cs.primary.copy(alpha = 0.16f),
                                    ),
                                ).border(1.dp, cs.primary.copy(alpha = 0.8f), seg)
                        } else {
                            Modifier
                        },
                    ).clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ------------------------------------------------------------ text entry

/** Rounded glass search bar with a leading glowing search icon. */
@Composable
fun CrystalSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .crystalPanel(0.3f, cs.surfaceVariant, cs.primary, corner = 26.dp, glowStrength = 0.5f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = cs.primary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Glass text field with the mockups' tiny tracked-caps label above the box
 * (TITLE / ARTIST / ALBUM in the "Edit Track Info" sheet).
 */
@Composable
fun CrystalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            CrystalOverline(label, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cs.surfaceVariant.copy(alpha = 0.35f))
                .border(1.dp, cs.primary.copy(alpha = 0.35f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- sliders

/** Slider colors matching the luminous-track style of the mockups. */
@Composable
fun crystalSliderColors(): SliderColors =
    SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
    )

/** Switch colors: lit primary track when checked, quiet glass otherwise. */
@Composable
fun crystalSwitchColors(): SwitchColors =
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
        checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )

/**
 * Labeled slider row: tracked-caps label on the left, a small glass value
 * badge on the right ("80%"), luminous slider underneath — the mockups'
 * WAVE HEIGHT / FLOW SPEED / BAR OPACITY rows.
 */
@Composable
fun CrystalSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                color = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (valueText != null) CrystalBadge(valueText)
            trailing?.invoke(this)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = crystalSliderColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// -------------------------------------------------------- cards and rows

/**
 * Deterministic "crystal texture" thumbnail: a faceted gradient seeded from
 * [seed] so each track/style keeps a stable stone-like tile, echoing the
 * mockups' artwork squares without real artwork.
 */
@Composable
fun CrystalThumb(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    corner: Dp = 12.dp,
) {
    val cs = MaterialTheme.colorScheme
    val h = seed.hashCode()
    val palette = listOf(cs.primary, cs.secondary, cs.tertiary)
    val c1 = palette[((h % 3) + 3) % 3]
    val c2 = palette[(((h / 3) % 3) + 3) % 3]
    val dark = lerp(cs.surface, Color.Black, 0.35f)
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    0f to lerp(dark, c1, 0.55f),
                    0.55f to lerp(dark, c2, 0.3f),
                    1f to dark,
                ),
            ).border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        contentAlignment = Alignment.Center,
    ) {
        // Inner luminous facet, offset by the seed so tiles don't look cloned.
        Box(
            Modifier
                .size(size * (0.4f + (((h / 7) % 4) + 4) % 4 * 0.08f))
                .background(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.2f),
                        1f to Color.Transparent,
                    ),
                    CircleShape,
                ),
        )
    }
}

/**
 * Glass list-item card: crystal thumbnail, title + meta subtitle, optional
 * trailing controls — the mockups' library track rows and style cards.
 */
@Composable
fun CrystalListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbSeed: String = title,
    selected: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                if (selected) 0.4f else 0.18f,
                if (selected) cs.primaryContainer else cs.surfaceVariant,
                cs.primary,
                corner = 16.dp,
                glowStrength = if (selected) 0.9f else 0.2f,
            ).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrystalThumb(thumbSeed)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** Selection circle for style cards: glowing dot when selected. */
@Composable
fun CrystalRadio(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(22.dp)
            .then(if (selected) Modifier.softGlow(cs.primary, 8.dp) else Modifier)
            .border(
                1.5.dp,
                if (selected) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(cs.primary, CircleShape))
    }
}

// ------------------------------------------------------------ navigation

/**
 * Glass bottom navigation: four tracked-caps items, the selected one lit
 * primary with a soft icon glow — the mockups' bottom nav on every screen.
 * Draws its own gradient glass and handles the navigation-bar inset so the
 * glass extends behind the system bar.
 */
@Composable
fun CrystalNavBar(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    barOpacity: Float,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to cs.surfaceContainer.copy(alpha = (barOpacity * 0.9f).coerceIn(0f, 1f)),
                    1f to lerp(cs.surfaceContainer, Color.Black, 0.3f).copy(alpha = (barOpacity * 0.95f).coerceIn(0f, 1f)),
                ),
            ).navigationBarsPadding()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val sel = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    if (sel) Modifier.softGlow(cs.primary, 10.dp, 0.9f) else Modifier,
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, label, Modifier.size(22.dp), tint = if (sel) cs.primary else cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontSize = 9.sp),
                    color = if (sel) cs.primary else cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
