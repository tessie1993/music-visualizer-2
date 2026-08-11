package dev.musicviz.ui

/**
 * One selectable swatch for the Appearance "Font color" option. [argb] is
 * stored straight into [GuiPrefs.fontColorArgb]; null is the automatic
 * choice (theme-derived text colors).
 *
 * The Settings picker renders [CHOICES] and writes the picked [argb] via
 * `viewModel.setGuiPrefs(gui.copy(fontColorArgb = choice.argb))`. Whether a
 * swatch actually takes effect on the current pack is [fontColorActive] -
 * light packs ignore swatches that cannot be read on the surfaces they are
 * painting, and the picker can grey those out.
 */
data class FontColorChoice(
    val label: String,
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
                FontColorChoice("Auto", null),
                FontColorChoice("White", WHITE_ARGB),
                FontColorChoice("Moonstone ivory", 0xFFF2EAD9.toInt()),
                FontColorChoice("Pyrite gold", 0xFFD6B15A.toInt()),
                FontColorChoice("Rose", 0xFFF6BFD0.toInt()),
                FontColorChoice("Lavender", 0xFFCDB8F2.toInt()),
                FontColorChoice("Ice blue", 0xFFBFE2F2.toInt()),
                FontColorChoice("Mint", 0xFFBDEFD8.toInt()),
            )
    }
}
