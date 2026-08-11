#!/usr/bin/env bash
# Imports a MusicViz crystal theme pack into the app's Android resources.
#
# Usage: tools/import-theme-pack.sh <extracted-pack-dir> [<pack-dir> ...]
#
# Pass EVERY pack the app should ship, in the order they should appear in the
# theme picker: this imports their assets and regenerates ThemePackCatalog.kt
# to list exactly that set. Adding a crystal is then a folder drop plus one
# re-run - no hand-written Kotlin.
#
# A pack is the unpacked `MusicViz-<Stone>-Theme-Pack/` folder. Everything the
# app needs at runtime is derived from it; `preview/` and
# `materials/material-master.png` are documentation and deliberately not
# shipped.
#
# Every raster asset is copied byte-for-byte as the pack ships it. The PNGs go
# in as PNGs - untranscoded, unresampled, unrecoloured - and the four assets
# the packs already ship as WebP are copied in that form. Nothing here
# re-encodes or regenerates artwork.
#
# That fidelity costs about 33 MB of PNG per crystal, so a build offering many
# packs at once is large; import only the packs that build should ship.
#
# Fonts are byte-identical across every pack, so they are written once.
# Icon path geometry is also pack-invariant and lives in Kotlin
# (StoneIconPaths.kt), driven by each pack's own token colours - it is not
# imported here.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <extracted-pack-dir> [<pack-dir> ...]" >&2
    exit 2
fi

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RES="$SCRIPT_DIR/../app/src/main/res"
DRAWABLE="$RES/drawable-nodpi"
RAW="$RES/raw"
FONT="$RES/font"
mkdir -p "$DRAWABLE" "$RAW" "$FONT"

# 18 component families x 5 interaction states, exactly as the packs ship them.
STATES="default focused pressed selected disabled"
FAMILIES="album-tile bottom-sheet card chip compact-button dialog icon-button \
knob list-row mini-player navigation-bar primary-button progress-ring \
secondary-button slider-thumb slider-track text-field toggle"

put_png() { # <src> <dest-basename>
    cp "$1" "$DRAWABLE/$2.png"
}

# CATALOG_ONLY=1 skips the asset encode and only regenerates the Kotlin
# catalog - useful after an interrupted import once the assets are in place.
for PACK in "$@"; do
    [ -f "$PACK/manifest.json" ] || { echo "not a theme pack: $PACK" >&2; exit 1; }
    [ -n "${CATALOG_ONLY:-}" ] && continue

    # slug -> Android resource name fragment (clear-quartz -> clear_quartz)
    SLUG=$(grep -o '"slug"[[:space:]]*:[[:space:]]*"[^"]*"' "$PACK/manifest.json" \
        | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    RS=${SLUG//-/_}
    echo "==> importing $SLUG"

    for f in $FAMILIES; do
        for s in $STATES; do
            src="$PACK/components/individual/$f--$s.png"
            [ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
            put_png "$src" "tp_${RS}_${f//-/_}_${s}"
        done
    done

    put_png "$PACK/materials/glow-overlay.png"       "tp_${RS}_glow_overlay"
    put_png "$PACK/materials/refraction-overlay.png" "tp_${RS}_refraction_overlay"
    # The tokens name this one `material.texture`: the uncropped mineral master
    # the mirrored tile is cut from, for surfaces big enough to show the seam.
    put_png "$PACK/materials/material-master.png"    "tp_${RS}_material_master"

    # Already WebP in the pack - copied verbatim, no re-encode.
    cp "$PACK/materials/material-tile.webp"        "$DRAWABLE/tp_${RS}_material_tile.webp"
    cp "$PACK/backgrounds/ambient-portrait.webp"   "$DRAWABLE/tp_${RS}_ambient_portrait.webp"
    cp "$PACK/backgrounds/ambient-landscape.webp"  "$DRAWABLE/tp_${RS}_ambient_landscape.webp"
    cp "$PACK/backgrounds/ambient-square.webp"     "$DRAWABLE/tp_${RS}_ambient_square.webp"

    # Per-pack interaction sounds (mono 48 kHz PCM WAV, as shipped).
    cp "$PACK/audio/click-soft.wav" "$RAW/tp_${RS}_click_soft.wav"
    cp "$PACK/audio/confirm.wav"    "$RAW/tp_${RS}_confirm.wav"
    cp "$PACK/audio/swoop.wav"      "$RAW/tp_${RS}_swoop.wav"

    # Fonts are identical in every pack; first one in wins, rest are no-ops.
    for ttf in "$PACK/android/res/font/"*.ttf; do
        cp -n "$ttf" "$FONT/$(basename "$ttf")"
    done
done

# ---------------------------------------------------------------------------
# Regenerate ThemePackCatalog.kt from the packs' own token files, so the
# Kotlin catalog can never drift from what was actually imported.
# ---------------------------------------------------------------------------
CATALOG="$SCRIPT_DIR/../app/src/main/java/dev/musicviz/ui/theme/ThemePackCatalog.kt"

# token <pack-dir> <json-key>  -> string value from tokens/theme.tokens.json
# (space-preserving: the pack contract requires exact names like "Clear Quartz")
token() {
    tr -d '\n' < "$1/tokens/theme.tokens.json" \
        | grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 \
        | sed 's/.*:[[:space:]]*"\(.*\)"/\1/'
}
# color <pack-dir> <name> -> 0xFFRRGGBB from tokens/colors.json
color() {
    hex=$(tr -d ' \n' < "$1/tokens/colors.json" \
        | grep -o "\"$2\":\"#[0-9A-Fa-f]*\"" | cut -d'#' -f2 | tr -d '"')
    echo "0xFF${hex^^}"
}

{
    cat <<'HEADER'
// GENERATED by tools/import-theme-pack.sh - DO NOT EDIT BY HAND.
// Values are transcribed from each pack's tokens/{colors,theme.tokens}.json;
// re-run the importer with every shipped pack to regenerate.
package dev.musicviz.ui.theme

import androidx.compose.ui.graphics.Color
import dev.musicviz.R

/** Every crystal pack this build ships, in theme-picker order. */
object ThemePackCatalog {
HEADER

    NAMES=""
    for PACK in "$@"; do
        SLUG=$(grep -o '"slug"[[:space:]]*:[[:space:]]*"[^"]*"' "$PACK/manifest.json" \
            | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
        RS=${SLUG//-/_}
        NAME=$(token "$PACK" "name")
        STONE=$(token "$PACK" "stone")
        MODE=$(token "$PACK" "mode")
        VAL=$(echo "$RS" | awk -F_ '{o="";for(i=1;i<=NF;i++)o=o toupper(substr($i,1,1)) substr($i,2)}END{print o}')
        VAL=$(echo "${VAL:0:1}" | tr '[:upper:]' '[:lower:]')${VAL:1}
        NAMES="$NAMES $VAL"
        IS_LIGHT=$([ "$MODE" = "light" ] && echo true || echo false)

        cat <<EOK
    val $VAL =
        ThemePack(
            slug = "$SLUG",
            name = "$NAME",
            stone = "$STONE",
            isLight = $IS_LIGHT,
            palette =
                StonePalette(
                    background = Color($(color "$PACK" background)),
                    backgroundDeep = Color($(color "$PACK" backgroundDeep)),
                    surface = Color($(color "$PACK" surface)),
                    surfaceHigh = Color($(color "$PACK" surfaceHigh)),
                    primary = Color($(color "$PACK" primary)),
                    secondary = Color($(color "$PACK" secondary)),
                    accent = Color($(color "$PACK" accent)),
                    glow = Color($(color "$PACK" glow)),
                    onBackground = Color($(color "$PACK" onBackground)),
                    onSurface = Color($(color "$PACK" onSurface)),
                    muted = Color($(color "$PACK" muted)),
                    outline = Color($(color "$PACK" outline)),
                    danger = Color($(color "$PACK" danger)),
                ),
            motion =
                StoneMotion(
                    pressDurationMs = $(sed -n '/"press"/,/}/p' "$PACK/tokens/motion.json" | grep durationMs | grep -o '[0-9]*'),
                    pressScale = $(sed -n '/"press"/,/}/p' "$PACK/tokens/motion.json" | grep '"scale"' | grep -o '[0-9.]*')f,
                    innerGlowGain = $(grep innerGlowGain "$PACK/tokens/motion.json" | grep -o '[0-9.]*')f,
                    releaseDurationMs = $(sed -n '/"release"/,/}/p' "$PACK/tokens/motion.json" | grep durationMs | grep -o '[0-9]*'),
                    focusDurationMs = $(sed -n '/"focus"/,/}/p' "$PACK/tokens/motion.json" | grep durationMs | grep -o '[0-9]*'),
                    edgeLightGain = $(grep edgeLightGain "$PACK/tokens/motion.json" | grep -o '[0-9.]*')f,
                    selectedDurationMs = $(sed -n '/"selected"/,/}/p' "$PACK/tokens/motion.json" | grep durationMs | grep -o '[0-9]*'),
                    reduceMotionCrossfadeMs = $(grep crossfadeMs "$PACK/tokens/motion.json" | grep -o '[0-9]*'),
                ),
            material =
                StoneMaterial(
                    tile = R.drawable.tp_${RS}_material_tile,
                    master = R.drawable.tp_${RS}_material_master,
                    glowOverlay = R.drawable.tp_${RS}_glow_overlay,
                    refractionOverlay = R.drawable.tp_${RS}_refraction_overlay,
                    ambientPortrait = R.drawable.tp_${RS}_ambient_portrait,
                    ambientLandscape = R.drawable.tp_${RS}_ambient_landscape,
                    ambientSquare = R.drawable.tp_${RS}_ambient_square,
                    backgroundOpacity = $(tr -d ' \n' < "$PACK/tokens/theme.tokens.json" | grep -o '"textureOpacity":{"background":[0-9.]*' | grep -o '[0-9.]*$')f,
                    surfaceOpacity = $(tr -d ' \n' < "$PACK/tokens/theme.tokens.json" | grep -o '"surface":[0-9.]*,"disabled"' | grep -o '0\.[0-9]*')f,
                    disabledOpacity = $(tr -d ' \n' < "$PACK/tokens/theme.tokens.json" | grep -o '"disabled":[0-9.]*' | tail -1 | grep -o '[0-9.]*$')f,
                ),
            sounds =
                StoneSounds(
                    click = R.raw.tp_${RS}_click_soft,
                    confirm = R.raw.tp_${RS}_confirm,
                    swoop = R.raw.tp_${RS}_swoop,
                ),
            surfaces =
                mapOf(
EOK
        for f in $FAMILIES; do
            FU=$(echo "${f//-/_}" | tr '[:lower:]' '[:upper:]')
            echo "                    StoneComponent.$FU to"
            echo "                        StoneStateArt("
            for s in $STATES; do
                echo "                            ${s} = R.drawable.tp_${RS}_${f//-/_}_${s},"
            done
            echo "                        ),"
        done
        cat <<'EOK'
                ),
        )

EOK
    done

    echo "    /** Picker order; the first entry is the app default. */"
    echo "    val all: List<ThemePack> = listOf($(echo $NAMES | sed 's/ /, /g'))"
    echo ""
    echo "    /** Pack for a persisted slug, or the default when unknown. */"
    echo "    fun bySlug(slug: String?): ThemePack = all.firstOrNull { it.slug == slug } ?: all.first()"
    echo "}"
} > "$CATALOG"

echo "==> regenerated $(basename "$CATALOG") with:$NAMES"
echo "==> done. drawable-nodpi is now $(du -sh "$DRAWABLE" | cut -f1)"
