package dev.geode.ui.theme

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.ui.graphics.Color

/**
 * A crystal theme pack: one stone's complete visual and interaction identity.
 *
 * Packs are authored outside this repo as `Geode-<Stone>-Theme-Pack/`
 * folders and imported by `tools/import-theme-pack.sh`. Everything here is
 * transcribed from a pack's `tokens/theme.tokens.json` or points at the
 * resources that import produced - nothing is invented, and no stone material
 * is drawn procedurally. The photographed surfaces ARE the design.
 *
 * Adding a crystal is therefore a data change, not a code change: import the
 * folder, add one [ThemePack] here, and every screen picks it up.
 */
data class ThemePack(
    /** Stable id, matching the pack manifest (`clear-quartz`). Persisted. */
    val slug: String,
    /** Display name exactly as the pack names it (`Clear Quartz`). */
    val name: String,
    /** The mineral, for copy and accessibility descriptions. */
    val stone: String,
    /** `mode` in the pack tokens: light stones need dark writing. */
    val isLight: Boolean,
    val palette: StonePalette,
    val motion: StoneMotion,
    val material: StoneMaterial,
    val sounds: StoneSounds,
    /** Photographed surface art, per component family and interaction state. */
    val surfaces: Map<StoneComponent, StoneStateArt>,
) {
    /**
     * The surface art for [component]. Every pack ships all 18 families, and
     * [ThemePackCatalog] is covered by a test that proves it, so a miss here
     * is a packaging bug rather than something a caller should paper over.
     */
    fun surface(component: StoneComponent): StoneStateArt =
        requireNotNull(surfaces[component]) {
            "theme pack '$slug' is missing surface art for $component"
        }
}

/**
 * The thirteen colour roles a pack defines. Unlike the old four-anchor model,
 * nothing here is derived by lerping - each value is authored against the
 * actual stone photography, and the packs publish measured contrast figures
 * for the writing roles.
 */
data class StonePalette(
    val background: Color,
    val backgroundDeep: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val glow: Color,
    val onBackground: Color,
    val onSurface: Color,
    val muted: Color,
    val outline: Color,
    val danger: Color,
)

/**
 * Interaction timing, from the pack's `motion` tokens.
 *
 * The pack contract states the press response as: scale to [pressScale],
 * brighten the internal light over [pressDurationMs], then settle over
 * [releaseDurationMs]. Light travels through a surface but settles - it never
 * loops as glitter.
 */
data class StoneMotion(
    val pressDurationMs: Int,
    val pressScale: Float,
    /** Multiplier on the surface's internal light while pressed. */
    val innerGlowGain: Float,
    val releaseDurationMs: Int,
    val focusDurationMs: Int,
    /** Multiplier on the edge highlight while focused. */
    val edgeLightGain: Float,
    val selectedDurationMs: Int,
    /**
     * Reduced motion replaces scale and light travel with a plain crossfade of
     * this length. Honouring it is a pack requirement, not an option.
     */
    val reduceMotionCrossfadeMs: Int,
)

/** Material layers and the texture strengths the pack allows them at. */
data class StoneMaterial(
    @param:DrawableRes val tile: Int,
    /**
     * The uncropped mineral master the mirrored [tile] is cut from, which the
     * packs' tokens name `material.texture`. Surfaces large enough to show the
     * tile's repeat draw this instead.
     */
    @param:DrawableRes val master: Int,
    @param:DrawableRes val glowOverlay: Int,
    @param:DrawableRes val refractionOverlay: Int,
    @param:DrawableRes val ambientPortrait: Int,
    @param:DrawableRes val ambientLandscape: Int,
    @param:DrawableRes val ambientSquare: Int,
    /** Texture opacity behind a full-screen background. */
    val backgroundOpacity: Float,
    /** Texture opacity within a control surface. */
    val surfaceOpacity: Float,
    /** Texture opacity for a disabled control; it stays legible. */
    val disabledOpacity: Float,
)

/** The pack's three interaction cues (mono 48 kHz PCM WAV, as shipped). */
data class StoneSounds(
    @param:RawRes val click: Int,
    @param:RawRes val confirm: Int,
    @param:RawRes val swoop: Int,
)

/**
 * The eighteen component families every pack ships. These are the packs' own
 * names; the enum is closed so a missing family fails to compile rather than
 * at render time.
 */
enum class StoneComponent {
    ALBUM_TILE,
    BOTTOM_SHEET,
    CARD,
    CHIP,
    COMPACT_BUTTON,
    DIALOG,
    ICON_BUTTON,
    KNOB,
    LIST_ROW,
    MINI_PLAYER,
    NAVIGATION_BAR,
    PRIMARY_BUTTON,
    PROGRESS_RING,
    SECONDARY_BUTTON,
    SLIDER_THUMB,
    SLIDER_TRACK,
    TEXT_FIELD,
    TOGGLE,
}

/**
 * The five interaction states each family is photographed in.
 *
 * These are painted from real artwork rather than synthesised, so a pressed
 * control is the *pressed photograph* - the press animation carries scale and
 * timing, not a fabricated brightness ramp.
 */
enum class StoneState {
    DEFAULT,
    FOCUSED,
    PRESSED,
    SELECTED,
    DISABLED,
}

/** One family's five photographed surfaces. */
data class StoneStateArt(
    @param:DrawableRes val default: Int,
    @param:DrawableRes val focused: Int,
    @param:DrawableRes val pressed: Int,
    @param:DrawableRes val selected: Int,
    @param:DrawableRes val disabled: Int,
) {
    @DrawableRes
    fun forState(state: StoneState): Int =
        when (state) {
            StoneState.DEFAULT -> default
            StoneState.FOCUSED -> focused
            StoneState.PRESSED -> pressed
            StoneState.SELECTED -> selected
            StoneState.DISABLED -> disabled
        }
}

/**
 * Resolves the state to paint from the interaction flags, in the packs'
 * precedence order: an unusable control reads as disabled before anything
 * else, a press beats a standing selection, and focus is the weakest signal.
 */
fun stoneStateOf(
    enabled: Boolean = true,
    pressed: Boolean = false,
    selected: Boolean = false,
    focused: Boolean = false,
): StoneState =
    when {
        !enabled -> StoneState.DISABLED
        pressed -> StoneState.PRESSED
        selected -> StoneState.SELECTED
        focused -> StoneState.FOCUSED
        else -> StoneState.DEFAULT
    }
