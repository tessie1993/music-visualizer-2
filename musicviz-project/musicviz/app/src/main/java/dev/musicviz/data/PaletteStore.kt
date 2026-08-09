package dev.musicviz.data

import android.content.Context
import dev.musicviz.render.scene.SceneParams
import org.json.JSONObject
import java.io.File

/**
 * A user-made palette. Deliberately the same shape as a built-in entry in
 * [SceneParams.PALETTES] - a name, the hue the gradient starts at, and how far
 * around the colour wheel it sweeps - so every scene family renders it through
 * the existing `paletteBase`/`paletteRange` path with no render-side change.
 *
 * [id] is the stable key a preset stores in `SceneParams.customPaletteId`; it
 * is derived from the name, so re-saving under the same name replaces the
 * palette instead of piling up duplicates (the same rule [PresetStore] uses).
 */
data class CustomPalette(
    val id: String,
    val name: String,
    val baseHue: Float,
    val hueSpan: Float,
)

/**
 * JSON-file-per-palette persistence in app-private storage, modelled on
 * [PresetStore]. The companion also owns the pure param plumbing (apply /
 * clear / forget-deleted) so the sentinel rules live in exactly one place and
 * can be tested without Compose.
 *
 * [save] publishes through [AtomicWrite] rather than `File.writeText`, which
 * truncates the file to zero before writing it. That matters more here than
 * the tiny document suggests: re-saving under the same name deliberately
 * REPLACES the palette, so the truncation window sits on top of the only copy
 * of it, and [list] skips anything that does not parse - a palette killed
 * there just disappears, taking the gradient of every preset that referenced
 * its id with it.
 */
class PaletteStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "palettes").apply { mkdirs() }

    init {
        migrateLegacyIds()
    }

    /**
     * One-time re-key of palettes saved under the pre-hash sanitizer (see
     * [PresetStore.safeFileName]). The id doubles as the file stem AND lives
     * inside the JSON, so a bare rename would leave [save]/[delete]
     * addressing the old stem; the file is rewritten under the new id
     * instead. A preset still holding the old id keeps its gradient - the
     * resolved hues live in the preset - and only the "which palette" label
     * dangles, exactly as after a deletion (see [forgetDeleted]). A taken
     * target keeps the old file in place rather than destroying either.
     */
    private fun migrateLegacyIds() {
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .forEach { f ->
                val p = runCatching { fromJson(f.readText()) }.getOrNull() ?: return@forEach
                val id = idFor(p.name)
                if (f.nameWithoutExtension == id) return@forEach
                val target = File(dir, "$id.json")
                if (target.exists()) return@forEach
                if (AtomicWrite.text(target, toJson(p.copy(id = id)))) f.delete()
            }
    }

    /** All saved palettes, name-sorted; unreadable files are skipped rather than fatal. */
    fun list(): List<CustomPalette> =
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun get(id: String): CustomPalette? = list().firstOrNull { it.id == id }

    /** Writes [palette] (clamped and re-keyed) and returns the value actually stored. */
    fun save(palette: CustomPalette): CustomPalette {
        val clean = sanitized(palette)
        AtomicWrite.text(File(dir, clean.id + ".json"), toJson(clean))
        return clean
    }

    fun delete(id: String) {
        File(dir, sanitize(id) + ".json").delete()
    }

    companion object {
        /** Number of colour stops the gradient preview samples. */
        const val PREVIEW_STOPS: Int = 9

        /** Palette ids are the sanitized name, so saving a name twice overwrites. */
        fun idFor(name: String): String = sanitize(name)

        /** Builds a palette from maker input, clamped into the renderable range. */
        fun create(
            name: String,
            baseHue: Float,
            hueSpan: Float,
        ): CustomPalette = sanitized(CustomPalette(idFor(name), name, baseHue, hueSpan))

        /**
         * Points a palette slot at an explicit gradient. [id] is bookkeeping
         * only - [SceneParams.NO_CUSTOM_PALETTE] marks an unnamed one-off the
         * user is auditioning before saving.
         */
        fun applyGradient(
            p: SceneParams,
            baseHue: Float,
            hueSpan: Float,
            id: String = SceneParams.NO_CUSTOM_PALETTE,
            second: Boolean = false,
        ): SceneParams {
            // Both values are clamped to 0..1: a negative would read back as
            // "no override" (see SceneParams.UNSET_OVERRIDE) and silently fall
            // through to the built-in table.
            val base = baseHue.coerceIn(0f, 1f)
            val span = hueSpan.coerceIn(0f, 1f)
            return if (second) {
                p.copy(palette2BaseOverride = base, palette2RangeOverride = span, customPalette2Id = id)
            } else {
                p.copy(paletteBaseOverride = base, paletteRangeOverride = span, customPaletteId = id)
            }
        }

        /** Applies a saved palette to a slot, recording its id so the UI can show which one is live. */
        fun applyPalette(
            p: SceneParams,
            palette: CustomPalette,
            second: Boolean = false,
        ): SceneParams = applyGradient(p, palette.baseHue, palette.hueSpan, palette.id, second)

        /**
         * Drops a slot's override so it resolves from [SceneParams.PALETTES]
         * again - the palette-UI spelling of
         * [SceneParams.withoutCustomPalette], which owns the sentinel rule so
         * `render.scene` never has to reach into `ui` for it.
         */
        fun clear(
            p: SceneParams,
            second: Boolean = false,
        ): SceneParams = p.withoutCustomPalette(second)

        /**
         * Repairs params after a saved palette is deleted, for either slot.
         * The resolved hues stay put - what is on screen must not jump because
         * the user tidied their library - and only the now-dangling id is
         * cleared, demoting the slot to an unnamed one-off gradient. Selecting
         * any built-in afterwards clears the override properly via [clear].
         */
        fun forgetDeleted(
            p: SceneParams,
            deletedId: String,
        ): SceneParams {
            if (deletedId == SceneParams.NO_CUSTOM_PALETTE) return p
            var out = p
            if (out.customPaletteId == deletedId) out = out.copy(customPaletteId = SceneParams.NO_CUSTOM_PALETTE)
            if (out.customPalette2Id == deletedId) out = out.copy(customPalette2Id = SceneParams.NO_CUSTOM_PALETTE)
            return out
        }

        /**
         * Hue of the [index]-th of [stops] samples along a gradient, wrapped
         * into 0..1. Pure so the preview swatch and any test agree on what a
         * palette looks like.
         */
        fun sampleHue(
            baseHue: Float,
            hueSpan: Float,
            index: Int,
            stops: Int = PREVIEW_STOPS,
        ): Float {
            val t = if (stops <= 1) 0f else index.toFloat() / (stops - 1).toFloat()
            val h = baseHue + hueSpan * t
            return ((h % 1f) + 1f) % 1f
        }

        /**
         * HSV (all components 0..1) to a 0..1 red/green/blue triple. Hand-rolled
         * so the swatch preview depends on no framework colour helper and can be
         * checked in the headless test gate.
         */
        fun hueRgb(
            hue: Float,
            saturation: Float = 0.85f,
            value: Float = 1f,
        ): Triple<Float, Float, Float> {
            val h = (((hue % 1f) + 1f) % 1f) * 6f
            val sector = h.toInt() % 6
            val f = h - h.toInt()
            val lo = value * (1f - saturation)
            val fall = value * (1f - saturation * f)
            val rise = value * (1f - saturation * (1f - f))
            return when (sector) {
                0 -> Triple(value, rise, lo)
                1 -> Triple(fall, value, lo)
                2 -> Triple(lo, value, rise)
                3 -> Triple(lo, fall, value)
                4 -> Triple(rise, lo, value)
                else -> Triple(value, lo, fall)
            }
        }

        // The shared collision-free scheme: distinct names must never share
        // a file (and so, here, an id).
        internal fun sanitize(name: String): String =
            PresetStore
                .safeFileName(name)
                .trim()
                .ifEmpty { "palette" }

        internal fun sanitized(c: CustomPalette): CustomPalette =
            CustomPalette(
                id = sanitize(c.id.ifBlank { c.name }),
                name = c.name.trim().ifEmpty { "Palette" },
                baseHue = c.baseHue.coerceIn(0f, 1f),
                hueSpan = c.hueSpan.coerceIn(0f, 1f),
            )

        internal fun toJson(c: CustomPalette): String =
            JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("baseHue", c.baseHue.toDouble())
                .put("hueSpan", c.hueSpan.toDouble())
                .toString(2)

        internal fun fromJson(json: String): CustomPalette {
            val o = JSONObject(json)
            val name = o.getString("name")
            return CustomPalette(
                id = o.optString("id").ifBlank { idFor(name) },
                name = name,
                baseHue = o.optDouble("baseHue", 0.0).toFloat().coerceIn(0f, 1f),
                hueSpan = o.optDouble("hueSpan", 1.0).toFloat().coerceIn(0f, 1f),
            )
        }
    }
}
