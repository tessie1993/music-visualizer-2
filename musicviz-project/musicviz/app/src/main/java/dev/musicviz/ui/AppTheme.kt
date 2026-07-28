package dev.musicviz.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Selectable app themes. Each maps to a Material 3 [ColorScheme]. The
 * visualizer canvas fills the screen behind the controls, so themes mostly
 * affect the control surfaces, dialogs, sliders and accent colors.
 */
enum class AppTheme(
    val label: String,
) {
    MIDNIGHT("Midnight"),
    NEON("Neon"),
    SUNSET("Sunset"),
    FOREST("Forest"),
    MONO("Mono"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMBER("Ember"),
    CANDY("Candy"),
    SLATE("Slate"),
    ROSE("Rose"),
    MINT("Mint"),
    COBALT("Cobalt"),
    SAND("Sand"),
    GRAPE("Grape"),
    INK("Ink"),
    LIGHT("Light"),
    PAPER("Paper"),
    ;

    fun colorScheme(): ColorScheme =
        when (this) {
            MIDNIGHT ->
                darkColorScheme(
                    primary = Color(0xFF7C9CFF),
                    secondary = Color(0xFF9DA8C7),
                    background = Color(0xFF05060B),
                    surface = Color(0xFF0F1320),
                )
            NEON ->
                darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFF3DDA),
                    background = Color(0xFF04060A),
                    surface = Color(0xFF10131C),
                )
            SUNSET ->
                darkColorScheme(
                    primary = Color(0xFFFF9E57),
                    secondary = Color(0xFFFF5E7E),
                    background = Color(0xFF120A0A),
                    surface = Color(0xFF201412),
                )
            FOREST ->
                darkColorScheme(
                    primary = Color(0xFF67D982),
                    secondary = Color(0xFFB6D96A),
                    background = Color(0xFF060D0A),
                    surface = Color(0xFF101B15),
                )
            MONO ->
                darkColorScheme(
                    primary = Color(0xFFE0E0E0),
                    secondary = Color(0xFFBDBDBD),
                    background = Color(0xFF000000),
                    surface = Color(0xFF141414),
                )
            OCEAN ->
                darkColorScheme(
                    primary = Color(0xFF4DD6C1),
                    secondary = Color(0xFF5AA7E8),
                    background = Color(0xFF03090C),
                    surface = Color(0xFF0B1A20),
                )
            VIOLET ->
                darkColorScheme(
                    primary = Color(0xFFB388FF),
                    secondary = Color(0xFFEA80FC),
                    background = Color(0xFF0A0612),
                    surface = Color(0xFF171024),
                )
            EMBER ->
                darkColorScheme(
                    primary = Color(0xFFFF6E40),
                    secondary = Color(0xFFFFC13D),
                    background = Color(0xFF0F0605),
                    surface = Color(0xFF1E100C),
                )
            CANDY ->
                darkColorScheme(
                    primary = Color(0xFFFF80AB),
                    secondary = Color(0xFF82B1FF),
                    background = Color(0xFF0C060B),
                    surface = Color(0xFF1D1120),
                )
            SLATE ->
                darkColorScheme(
                    primary = Color(0xFF90A4AE),
                    secondary = Color(0xFF78909C),
                    background = Color(0xFF090B0D),
                    surface = Color(0xFF14181B),
                )
            ROSE ->
                darkColorScheme(
                    primary = Color(0xFFFF7BA9),
                    secondary = Color(0xFFFFB7CE),
                    background = Color(0xFF120609),
                    surface = Color(0xFF221016),
                )
            MINT ->
                darkColorScheme(
                    primary = Color(0xFF5FE8B8),
                    secondary = Color(0xFFA8F2D5),
                    background = Color(0xFF04100B),
                    surface = Color(0xFF0E1F17),
                )
            COBALT ->
                darkColorScheme(
                    primary = Color(0xFF4D7CFE),
                    secondary = Color(0xFF8FB0FF),
                    background = Color(0xFF040816),
                    surface = Color(0xFF0D1530),
                )
            SAND ->
                darkColorScheme(
                    primary = Color(0xFFE8C572),
                    secondary = Color(0xFFD9B08C),
                    background = Color(0xFF12100A),
                    surface = Color(0xFF211D12),
                )
            GRAPE ->
                darkColorScheme(
                    primary = Color(0xFFB07BFF),
                    secondary = Color(0xFFD3B4FF),
                    background = Color(0xFF0C0614),
                    surface = Color(0xFF1A1026),
                )
            INK ->
                darkColorScheme(
                    primary = Color(0xFF9FB6C6),
                    secondary = Color(0xFF6E8494),
                    background = Color(0xFF000305),
                    surface = Color(0xFF0A0F14),
                )
            LIGHT ->
                lightColorScheme(
                    primary = Color(0xFF3355DD),
                    secondary = Color(0xFF5C6BC0),
                    background = Color(0xFFF6F7FB),
                    surface = Color(0xFFFFFFFF),
                )
            PAPER ->
                lightColorScheme(
                    primary = Color(0xFF8D6E63),
                    secondary = Color(0xFFA1887F),
                    background = Color(0xFFFAF6EF),
                    surface = Color(0xFFFFFDF8),
                )
        }
}

/** Where the music player bar sits on screen; controls sit on the other side. */
enum class PlayerPosition(
    val label: String,
) {
    TOP("Top"),
    BOTTOM("Bottom"),
}

/** Corner styling for the floating control surfaces. */
enum class CornerStyle(
    val label: String,
) {
    SHARP("Sharp"),
    ROUNDED("Rounded"),
    PILL("Pill"),
}

/** GUI layout/appearance preferences beyond the color theme. */
data class GuiPrefs(
    val playerPosition: PlayerPosition = PlayerPosition.BOTTOM,
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    val barOpacity: Float = 0.72f,
    val beatThresholdSigma: Float = 2.5f,
    /** SAF tree the user picked as their preset folder; presets are mirrored
     *  there on save so their own sorting is visible in any file manager. */
    val presetMirrorUri: String? = null,
    /** Preset morphing: params interpolate over this many beats on apply (0 = snap). */
    val morphBeats: Int = 4,
)

/** Persists the chosen [AppTheme] in shared preferences. */
class ThemeStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    fun load(): AppTheme =
        runCatching { AppTheme.valueOf(prefs.getString(KEY, AppTheme.MIDNIGHT.name)!!) }
            .getOrDefault(AppTheme.MIDNIGHT)

    fun save(theme: AppTheme) {
        prefs.edit().putString(KEY, theme.name).apply()
    }

    fun loadGui(): GuiPrefs =
        GuiPrefs(
            playerPosition =
                runCatching { PlayerPosition.valueOf(prefs.getString(KEY_POS, PlayerPosition.BOTTOM.name)!!) }
                    .getOrDefault(PlayerPosition.BOTTOM),
            cornerStyle =
                runCatching { CornerStyle.valueOf(prefs.getString(KEY_CORNER, CornerStyle.ROUNDED.name)!!) }
                    .getOrDefault(CornerStyle.ROUNDED),
            barOpacity = prefs.getFloat(KEY_OPACITY, 0.72f),
            beatThresholdSigma = prefs.getFloat("beat_sigma", 2.5f),
            presetMirrorUri = prefs.getString("preset_mirror_uri", null),
            morphBeats = prefs.getInt("morph_beats", 4),
        )

    fun saveGui(gui: GuiPrefs) {
        prefs
            .edit()
            .putString(KEY_POS, gui.playerPosition.name)
            .putString(KEY_CORNER, gui.cornerStyle.name)
            .putFloat(KEY_OPACITY, gui.barOpacity)
            .putFloat("beat_sigma", gui.beatThresholdSigma)
            .putString("preset_mirror_uri", gui.presetMirrorUri)
            .putInt("morph_beats", gui.morphBeats)
            .apply()
    }

    private companion object {
        const val KEY = "app_theme"
        const val KEY_POS = "gui_player_pos"
        const val KEY_CORNER = "gui_corner"
        const val KEY_OPACITY = "gui_opacity"
    }
}
