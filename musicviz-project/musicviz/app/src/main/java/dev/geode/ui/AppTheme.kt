package dev.geode.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.analysis.BeatTuning
import dev.geode.render.VisualSafetyChoice
import dev.geode.ui.theme.ThemePack
import dev.geode.ui.theme.ThemePackCatalog

/*
 * App theming is carried by crystal theme packs (see ui/theme/ThemePack.kt):
 * photographed stone surfaces, authored palettes and pack-defined motion,
 * imported by tools/import-theme-pack.sh. The old hand-drawn 26-theme enum
 * and its derived-anchor colour model are gone - a theme is now a pack, and
 * adding one is a folder drop, not a code change.
 *
 * This file keeps the shell-level pieces that are not per-stone: the
 * appearance preferences (GuiPrefs), their persistence (ThemeStore), and the
 * font-colour override gate.
 */

/** Contrast bars the theme system holds writing to. */
object ThemeContrast {
    /**
     * Minimum relative-luminance difference between a font colour override
     * and a LIGHT pack's painted background for the override to be honoured.
     * Pale text on near-white stone would blank the UI, which is a worse
     * failure than not honouring the preference.
     */
    const val LIGHT_CONTRAST_MIN = 0.5f

    /** WCAG AA for body text. */
    const val BODY_CONTRAST_MIN = 4.5f

    /** WCAG AA for large text and UI hints. */
    const val HINT_CONTRAST_MIN = 3.0f
}

/**
 * The font colour that will actually be painted for [fontColorArgb], or null
 * when the pack's own writing colours stay in force.
 *
 * Dark packs accept any override. Light packs accept it only when it passes a
 * contrast check against the background they are ACTUALLY painting - which is
 * the [backgroundDim]-dimmed one, so a light pack dimmed to near-black admits
 * the pale swatches its undimmed surface would reject.
 */
fun ThemePack.resolvedFontColor(
    fontColorArgb: Int?,
    backgroundDim: Float = 0f,
): Int? {
    // Null override and dark packs pass straight through (null stays null).
    if (fontColorArgb == null || !isLight) return fontColorArgb
    val painted = Color(ColorDerive.dim(palette.background.toArgbInt(), backgroundDim))
    val diff = kotlin.math.abs(Color(fontColorArgb).luminance() - painted.luminance())
    return if (diff >= ThemeContrast.LIGHT_CONTRAST_MIN) fontColorArgb else null
}

/** True when the font colour override actually takes effect for this pack. */
fun ThemePack.fontColorActive(
    fontColorArgb: Int?,
    backgroundDim: Float = 0f,
): Boolean = resolvedFontColor(fontColorArgb, backgroundDim) != null

private fun Color.toArgbInt(): Int =
    ((alpha * 255f + 0.5f).toInt() shl 24) or
        ((red * 255f + 0.5f).toInt() shl 16) or
        ((green * 255f + 0.5f).toInt() shl 8) or
        (blue * 255f + 0.5f).toInt()

/**
 * The font colour override in force, or null for automatic pack colours.
 * `CrystalMaterialTheme` provides the RESOLVED override (after the light-pack
 * contrast gate in [resolvedFontColor]), so a value here is always readable
 * on the current surfaces.
 */
internal val LocalFontColor = staticCompositionLocalOf<Color?> { null }

/**
 * Colour for accent-tinted WRITING - section headers, selected list rows, tab
 * titles. The font colour override when one is set, the pack's primary
 * otherwise. Deliberately text-only: icons, gems, hairlines and glows keep
 * `colorScheme.primary`, because the font colour option is about legibility
 * of writing, not draining colour out of the shell.
 */
@Composable
fun accentTextColor(): Color = LocalFontColor.current ?: MaterialTheme.colorScheme.primary

/**
 * Colour for writing on a SATURATED accent fill (filled buttons, the play
 * gem). A light override colour is unreadable on the near-white primaries
 * some packs author, so with an override in force this picks white or black
 * by the fill's own luminance instead - the one place the font colour option
 * yields to legibility rather than the other way round.
 */
@Composable
fun onAccentTextColor(): Color {
    val cs = MaterialTheme.colorScheme
    if (LocalFontColor.current == null) return cs.onPrimary
    return if (cs.primary.luminance() > 0.55f) Color.Black else Color.White
}

/** Where the music player bar sits on screen; controls sit on the other side. */
enum class PlayerPosition(
    @StringRes val labelRes: Int,
) {
    TOP(R.string.look_position_top),
    BOTTOM(R.string.look_position_bottom),
}

/** Corner styling for the floating control surfaces. */
enum class CornerStyle(
    @StringRes val labelRes: Int,
) {
    SHARP(R.string.look_corner_sharp),
    ROUNDED(R.string.look_corner_rounded),
    PILL(R.string.look_corner_pill),
}

/** Maps the corner style to Material [Shapes] applied at the theme root. */
fun CornerStyle.shapes(): Shapes =
    when (this) {
        CornerStyle.SHARP ->
            Shapes(
                extraSmall = RoundedCornerShape(0.dp),
                small = RoundedCornerShape(0.dp),
                medium = RoundedCornerShape(0.dp),
                large = RoundedCornerShape(0.dp),
                extraLarge = RoundedCornerShape(0.dp),
            )
        CornerStyle.ROUNDED -> Shapes()
        CornerStyle.PILL ->
            Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            )
    }

/** GUI layout/appearance preferences beyond the color theme. */
data class GuiPrefs(
    val playerPosition: PlayerPosition = PlayerPosition.BOTTOM,
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    val barOpacity: Float = 0.72f,
    /** Beat-detection threshold in sigmas; higher = less sensitive. */
    val beatSensitivity: Float = BeatTuning.SENSITIVITY_DEFAULT,
    /** Minimum gap between beat flags in ms; higher = fewer flashes on slow tracks. */
    val beatMinIntervalMs: Float = BeatTuning.INTERVAL_MS_DEFAULT,
    /** SAF tree the user picked as their preset folder; presets are mirrored
     *  there on save so their own sorting is visible in any file manager. */
    val presetMirrorUri: String? = null,
    /** Preset morphing: params interpolate over this many beats on apply (0 = snap). */
    val morphBeats: Int = 4,
    /** Scales primary/secondary saturation (0.5 muted .. 1.5 vivid). */
    val accentIntensity: Float = 1f,
    /** Darkens background/surface colors (0 = off .. 0.6). */
    val backgroundDim: Float = 0f,
    /** Slimmer mini-player bar. */
    val compactPlayer: Boolean = false,
    /** When the system is in light mode, switch to the lightest pack automatically. */
    val followSystemDark: Boolean = false,
    /** Visuals hub renders as a text-only clear overlay on the live canvas,
     *  so adjustments are visible on the visuals while being adjusted. */
    val clearVisualsMenu: Boolean = false,
    /** LEGACY "White font" switch, superseded by [fontColorArgb] — the
     *  font-colour picker replaced the old Appearance switch. Kept as a
     *  field so [fontColorOverride] can still map a legacy true to a white
     *  override during migration ([ThemeStore.saveGui] then retires the old
     *  key); it is no longer persisted under its own key. */
    val whiteFont: Boolean = false,
    /** App-wide font colour override (ARGB), or null for automatic
     *  pack-derived text colors. Applied through [resolvedFontColor], so
     *  light packs ignore values that cannot be read on their surfaces. */
    val fontColorArgb: Int? = null,
    /** Multiplies every font size in the stone typography, 0.85..1.3. */
    val textScale: Float = 1f,
    /** What the user has said about flashing. [VisualSafetyChoice.UNKNOWN] -
     *  the default, and what an install that has never been asked holds -
     *  runs safe. This is the field that decides; the four below are the
     *  parameters of [VisualSafetyChoice.CUSTOM] and are read only then. */
    val safetyChoice: VisualSafetyChoice = VisualSafetyChoice.UNKNOWN,
    /** LEGACY "Safe visuals" switch, superseded by [safetyChoice]. Still
     *  persisted because it is CUSTOM's master switch, and still read at load
     *  to migrate an explicit true into [VisualSafetyChoice.SAFE]. A stored
     *  false means nothing: `saveGui` writes every key on every save, so an
     *  untouched switch is written as false the first time any other setting
     *  changes. */
    val safeVisuals: Boolean = false,
    /** Flashes per second ceiling under [VisualSafetyChoice.CUSTOM]. */
    val maxFlashHz: Float = dev.geode.render.VisualSafety.WCAG_FLASHES_PER_SECOND,
    /** Largest full-screen luminance swing a flash may make, 0..1. */
    val maxFlashDepth: Float = 0.25f,
    /** Keep full-frame invert/solarize available inside [safeVisuals]. */
    val allowInversion: Boolean = false,
    /** Scales speed/shake/drift-style motion. Independent of [safeVisuals]:
     *  this is a vestibular comfort setting, that one is about seizures. */
    val reducedMotion: Boolean = false,
    /** "Live input": drive the visuals from the microphone instead of from a
     *  track. Deliberately NOT persisted as on - an app that opens the
     *  microphone the moment it launches, because of a switch left on weeks
     *  ago, is not something a user has consented to. [ThemeStore.loadGui]
     *  always reads this back as false; the switch is a per-session decision. */
    val micReactive: Boolean = false,
    /** Let a finger drag push the visuals around (Now Playing canvas). */
    val touchSmear: Boolean = false,
    /** How hard a drag displaces the surface, 0.2..2. */
    val touchSmearStrength: Float = 1f,
    /** Colour the visuals from the track's detected musical key. Drives the
     *  Hue shift slider, so it is visible and undoable rather than a hidden
     *  second colour source; turning it off restores the value it replaced. */
    val keyColor: Boolean = false,
    /** Send the visuals to a connected TV/projector and keep the phone as the
     *  control surface. Ignored when nothing is connected. */
    val secondScreen: Boolean = true,
    /** Pinch to zoom and twist to spin on the fullscreen canvas. On by
     *  default: both are standard gestures, and both land on ordinary sliders
     *  that undo them, so there is nothing to get stuck in. */
    val touchTransform: Boolean = true,
) {
    /**
     * The font colour override actually in force: [fontColorArgb] when set,
     * else white while the legacy [whiteFont] switch is still on. Everything
     * that paints text (`CrystalMaterialTheme`, [ThemeStore.saveGui]) reads
     * THIS rather than either raw field, so the two inputs can never
     * disagree about what the user sees.
     */
    val fontColorOverride: Int?
        get() = fontColorArgb ?: FontColorChoice.WHITE_ARGB.takeIf { whiteFont }

    /**
     * [beatMinIntervalMs] after the Safe-visuals floor.
     *
     * EVERY consumer that feeds the beat detector must use this rather than
     * the raw slider value - the live engine, the offline analyzer, the cache
     * re-decision and the export. They all have to agree on one number or the
     * cached and exported beat grids would differ from what playback showed,
     * which is the invariant the whole analysis cache is built around.
     */
    val effectiveBeatMinIntervalMs: Float
        get() =
            dev.geode.render.VisualSafety
                .beatMinIntervalMs(beatMinIntervalMs, safety)

    /**
     * The engine-facing view of the safety settings above, resolved through
     * [safetyChoice].
     *
     * The live renderer, the transition picker, the exporter and the wallpaper
     * all read this and nothing else, so they cannot disagree about what the
     * user chose - and a stored switch left permissive cannot reach any of
     * them unless the choice is CUSTOM.
     */
    val safety: dev.geode.render.VisualSafety.SafetyConfig
        get() =
            dev.geode.render.VisualSafety.resolve(
                safetyChoice,
                dev.geode.render.VisualSafety.SafetyConfig(
                    enabled = safeVisuals,
                    maxFlashHz = maxFlashHz,
                    maxFlashDepth = maxFlashDepth,
                    allowInversion = allowInversion,
                    reducedMotion = reducedMotion,
                ),
            )

    companion object {
        /** Bounds for [textScale]; enforced on load and by the slider. */
        const val TEXT_SCALE_MIN = 0.85f
        const val TEXT_SCALE_MAX = 1.3f
    }
}

/** Persists the chosen [ThemePack] (by slug) in shared preferences. */
class ThemeStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("geode-prefs", Context.MODE_PRIVATE)

    /**
     * The persisted pack, defaulting to the catalog's first entry. Values
     * written by earlier releases were enum constant NAMES (`SUGILITE`,
     * `CLEAR_QUARTZ`, `LAPIS`, …); the two that map onto shipped packs are
     * migrated, and everything else falls back to the default pack rather
     * than failing.
     */
    fun load(): ThemePack = ThemePackCatalog.bySlug(migrateLegacyName(prefs.getString(KEY, null)))

    fun save(pack: ThemePack) {
        prefs.edit().putString(KEY, pack.slug).apply()
    }

    private fun migrateLegacyName(raw: String?): String? =
        when (raw) {
            "CLEAR_QUARTZ" -> "clear-quartz"
            "SUGILITE" -> "sugilite"
            else -> raw
        }

    fun loadGui(): GuiPrefs {
        // Font colour, with one-time migration off the legacy white-font
        // Boolean: an absent new key plus legacy true loads as a white
        // override; the next saveGui writes the new key and retires the
        // legacy one.
        val fontColor =
            when {
                prefs.contains(KEY_FONT_COLOR) -> prefs.getInt(KEY_FONT_COLOR, 0)
                prefs.getBoolean(KEY_WHITE_FONT, false) -> FontColorChoice.WHITE_ARGB
                else -> null
            }
        return GuiPrefs(
            playerPosition =
                runCatching { PlayerPosition.valueOf(prefs.getString(KEY_POS, PlayerPosition.BOTTOM.name)!!) }
                    .getOrDefault(PlayerPosition.BOTTOM),
            cornerStyle =
                runCatching { CornerStyle.valueOf(prefs.getString(KEY_CORNER, CornerStyle.ROUNDED.name)!!) }
                    .getOrDefault(CornerStyle.ROUNDED),
            barOpacity = prefs.getFloat(KEY_OPACITY, 0.72f),
            // Coerced on read: values persisted before the range was widened
            // are still valid, but a stored value must never fall outside the
            // slider's range or Compose would clamp the thumb and the shown
            // number would disagree with what the engine is using.
            beatSensitivity =
                prefs
                    .getFloat("beat_sigma", BeatTuning.SENSITIVITY_DEFAULT)
                    .coerceIn(BeatTuning.SENSITIVITY_MIN, BeatTuning.SENSITIVITY_MAX),
            beatMinIntervalMs =
                prefs
                    .getFloat(KEY_BEAT_INTERVAL, BeatTuning.INTERVAL_MS_DEFAULT)
                    .coerceIn(BeatTuning.INTERVAL_MS_MIN, BeatTuning.INTERVAL_MS_MAX),
            presetMirrorUri = prefs.getString("preset_mirror_uri", null),
            morphBeats = prefs.getInt("morph_beats", 4),
            accentIntensity = prefs.getFloat(KEY_ACCENT, 1f),
            backgroundDim = prefs.getFloat(KEY_DIM, 0f),
            compactPlayer = prefs.getBoolean(KEY_COMPACT, false),
            followSystemDark = prefs.getBoolean(KEY_FOLLOW_DARK, false),
            clearVisualsMenu = prefs.getBoolean(KEY_CLEAR_VIZ_MENU, false),
            fontColorArgb = fontColor,
            // whiteFont is deliberately NOT loaded: after migration the new
            // field carries the value, and "Auto" must be reachable by
            // clearing fontColorArgb alone.
            textScale =
                prefs
                    .getFloat(KEY_TEXT_SCALE, 1f)
                    .coerceIn(GuiPrefs.TEXT_SCALE_MIN, GuiPrefs.TEXT_SCALE_MAX),
            safetyChoice = loadSafetyChoice(),
            safeVisuals = prefs.getBoolean(KEY_SAFE_VISUALS, false),
            // Coerced on read for the same reason as the beat settings above:
            // a stored value outside the slider's range would leave the thumb
            // and the number disagreeing with what the renderer is using.
            maxFlashHz =
                prefs
                    .getFloat(KEY_MAX_FLASH_HZ, dev.geode.render.VisualSafety.WCAG_FLASHES_PER_SECOND)
                    .coerceIn(1f, dev.geode.render.VisualSafety.DEFAULT_STROBE_HZ),
            maxFlashDepth = prefs.getFloat(KEY_MAX_FLASH_DEPTH, 0.25f).coerceIn(0f, 1f),
            allowInversion = prefs.getBoolean(KEY_ALLOW_INVERSION, false),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, false),
            // micReactive is intentionally absent: see the field's docs. The
            // microphone is opened only by an explicit switch in this session.
            touchSmear = prefs.getBoolean(KEY_TOUCH_SMEAR, false),
            touchSmearStrength = prefs.getFloat(KEY_TOUCH_SMEAR_STRENGTH, 1f).coerceIn(0.2f, 2f),
            touchTransform = prefs.getBoolean(KEY_TOUCH_TRANSFORM, true),
            keyColor = prefs.getBoolean(KEY_KEY_COLOR, false),
            secondScreen = prefs.getBoolean(KEY_SECOND_SCREEN, true),
        )
    }

    /**
     * The stored flash-safety choice, or [VisualSafetyChoice.UNKNOWN] when
     * there is nothing that counts as one.
     *
     * Three things resolve to UNKNOWN, and each is a case where carrying a
     * value forward would be inventing consent:
     *
     *  - nothing stored - a fresh install, or one that predates the choice;
     *  - a choice recorded under an older [SAFETY_CHOICE_VERSION] - consent
     *    was to a set of behaviours that has since changed;
     *  - a name this build does not know - a downgrade, or a corrupted file.
     *
     * The legacy boolean migrates in one direction only. A stored `true` is
     * something a user did on purpose, because false was the default, so it
     * becomes [VisualSafetyChoice.SAFE]. A stored `false` proves nothing:
     * [saveGui] writes every key on every save, so the switch is written as
     * false the first time any other setting changes.
     */
    private fun loadSafetyChoice(): VisualSafetyChoice {
        val stored =
            prefs
                .getString(KEY_SAFETY_CHOICE, null)
                ?.takeIf { prefs.getInt(KEY_SAFETY_CHOICE_VERSION, 0) == SAFETY_CHOICE_VERSION }
                ?.let { name -> runCatching { VisualSafetyChoice.valueOf(name) }.getOrNull() }
        return stored
            ?: if (prefs.getBoolean(KEY_SAFE_VISUALS, false)) VisualSafetyChoice.SAFE else VisualSafetyChoice.UNKNOWN
    }

    fun saveGui(gui: GuiPrefs) {
        val fontColor = gui.fontColorOverride
        prefs
            .edit()
            .putString(KEY_POS, gui.playerPosition.name)
            .putString(KEY_CORNER, gui.cornerStyle.name)
            .putFloat(KEY_OPACITY, gui.barOpacity)
            .putFloat("beat_sigma", gui.beatSensitivity)
            .putFloat(KEY_BEAT_INTERVAL, gui.beatMinIntervalMs)
            .putString("preset_mirror_uri", gui.presetMirrorUri)
            .putInt("morph_beats", gui.morphBeats)
            .putFloat(KEY_ACCENT, gui.accentIntensity)
            .putFloat(KEY_DIM, gui.backgroundDim)
            .putBoolean(KEY_COMPACT, gui.compactPlayer)
            .putBoolean(KEY_FOLLOW_DARK, gui.followSystemDark)
            .putBoolean(KEY_CLEAR_VIZ_MENU, gui.clearVisualsMenu)
            // The resolved override is what gets persisted (so the legacy
            // whiteFont switch, while it still exists, also lands in the new
            // key), and the legacy key is retired on every save - absent
            // plus absent new key means "automatic", and a stale legacy true
            // must never re-trigger the migration after the user picks Auto.
            .apply { if (fontColor != null) putInt(KEY_FONT_COLOR, fontColor) else remove(KEY_FONT_COLOR) }
            .remove(KEY_WHITE_FONT)
            .putFloat(KEY_TEXT_SCALE, gui.textScale)
            .putString(KEY_SAFETY_CHOICE, gui.safetyChoice.name)
            .putInt(KEY_SAFETY_CHOICE_VERSION, SAFETY_CHOICE_VERSION)
            .putBoolean(KEY_SAFE_VISUALS, gui.safeVisuals)
            .putFloat(KEY_MAX_FLASH_HZ, gui.maxFlashHz)
            .putFloat(KEY_MAX_FLASH_DEPTH, gui.maxFlashDepth)
            .putBoolean(KEY_ALLOW_INVERSION, gui.allowInversion)
            .putBoolean(KEY_REDUCED_MOTION, gui.reducedMotion)
            .putBoolean(KEY_TOUCH_SMEAR, gui.touchSmear)
            .putFloat(KEY_TOUCH_SMEAR_STRENGTH, gui.touchSmearStrength)
            .putBoolean(KEY_TOUCH_TRANSFORM, gui.touchTransform)
            .putBoolean(KEY_KEY_COLOR, gui.keyColor)
            .putBoolean(KEY_SECOND_SCREEN, gui.secondScreen)
            .apply()
    }

    /**
     * `internal` rather than private so the migration tests can write a
     * legacy prefs file with the same key names the store reads, instead of
     * hardcoding the strings and quietly passing after a rename.
     */
    internal companion object {
        /**
         * Bumped whenever the behaviours a [VisualSafetyChoice] covers change.
         * A choice stored under an older version reads back as
         * [VisualSafetyChoice.UNKNOWN], so the user is asked again rather than
         * being held to consent they gave for something else.
         */
        const val SAFETY_CHOICE_VERSION = 1
        const val KEY_SAFETY_CHOICE = "gui_safety_choice"
        const val KEY_SAFETY_CHOICE_VERSION = "gui_safety_choice_version"

        const val KEY = "app_theme"
        const val KEY_POS = "gui_player_pos"
        const val KEY_CORNER = "gui_corner"
        const val KEY_OPACITY = "gui_opacity"
        const val KEY_ACCENT = "gui_accent_intensity"
        const val KEY_DIM = "gui_background_dim"
        const val KEY_COMPACT = "gui_compact_player"
        const val KEY_FOLLOW_DARK = "gui_follow_system_dark"
        const val KEY_CLEAR_VIZ_MENU = "gui_clear_visuals_menu"

        // Legacy white-font Boolean; read once for migration, never written.
        const val KEY_WHITE_FONT = "gui_white_font"
        const val KEY_FONT_COLOR = "gui_font_color"
        const val KEY_TEXT_SCALE = "gui_text_scale"
        const val KEY_BEAT_INTERVAL = "beat_min_interval_ms"
        const val KEY_SAFE_VISUALS = "gui_safe_visuals"
        const val KEY_MAX_FLASH_HZ = "gui_max_flash_hz"
        const val KEY_MAX_FLASH_DEPTH = "gui_max_flash_depth"
        const val KEY_ALLOW_INVERSION = "gui_allow_inversion"
        const val KEY_REDUCED_MOTION = "gui_reduced_motion"
        const val KEY_TOUCH_SMEAR = "gui_touch_smear"
        const val KEY_TOUCH_SMEAR_STRENGTH = "gui_touch_smear_strength"
        const val KEY_TOUCH_TRANSFORM = "gui_touch_transform"
        const val KEY_KEY_COLOR = "gui_key_color"
        const val KEY_SECOND_SCREEN = "gui_second_screen"
    }
}
