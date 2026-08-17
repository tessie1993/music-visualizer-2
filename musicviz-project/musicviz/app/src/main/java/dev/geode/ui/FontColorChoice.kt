package dev.geode.ui

import androidx.annotation.StringRes
import dev.geode.R

/**
 * One selectable swatch for the Appearance "Font color" option. [argb] is
 * stored straight into [GuiPrefs.fontColorArgb]; null is the automatic
 * choice (theme-derived text colors). [labelRes] is display only, so the
 * swatch names can translate without touching what persists.
 *
 * The Settings picker renders [CHOICES] and writes the picked [argb] via
 * `viewModel.setGuiPrefs(gui.copy(fontColorArgb = choice.argb))`. Whether a
 * swatch actually takes effect on the current pack is [fontColorActive] -
 * light packs ignore swatches that cannot be read on the surfaces they are
 * painting, and the picker can grey those out.
 */
data class FontColorChoice(
    @StringRes val labelRes: Int,
    val argb: Int?,
) {
    companion object {
        /** The one override colour the legacy white-font switch maps to. */
        const val WHITE_ARGB = 0xFFFFFFFF.toInt()

        /**
         * The curated palette, mineral-flavoured to match the crystal shell.
         * First entry is always Auto (null), so a picker can render the list
         * as-is.
         */
        val CHOICES: List<FontColorChoice> =
            listOf(
                FontColorChoice(R.string.font_color_auto, null),
                FontColorChoice(R.string.font_color_white, WHITE_ARGB),
                FontColorChoice(R.string.font_color_moonstone, 0xFFF2EAD9.toInt()),
                FontColorChoice(R.string.font_color_pyrite, 0xFFD6B15A.toInt()),
                FontColorChoice(R.string.font_color_rose, 0xFFF6BFD0.toInt()),
                FontColorChoice(R.string.font_color_lavender, 0xFFCDB8F2.toInt()),
                FontColorChoice(R.string.font_color_ice, 0xFFBFE2F2.toInt()),
                FontColorChoice(R.string.font_color_mint, 0xFFBDEFD8.toInt()),
            )
    }
}
