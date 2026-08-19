package dev.geode.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** A milkdrop texture image available to presets that reference it by name. */
data class MilkTexture(
    val name: String,
    val path: String,
)

/**
 * What happened to one picked file in [TextureStore.importDetailed]: either
 * it landed under [storedName] (the name presets reference), or [skipReason]
 * says - in words fit for an import note - why it did not.
 */
data class TextureImportResult(
    /** The picked file's display name, as the user knows it. */
    val name: String,
    /** The file name it was saved under (may be hashed), null when skipped. */
    val storedName: String?,
    /** Null when imported; otherwise why the file was skipped. */
    val skipReason: String?,
) {
    val imported: Boolean get() = storedName != null
}

/** Everything [TextureStore.importDetailed] has to say: per-file outcomes plus the updated listing. */
data class TextureImportOutcome(
    val results: List<TextureImportResult>,
    /** The texture list after the import, exactly as [TextureStore.list] would return it. */
    val textures: List<MilkTexture>,
)

/**
 * The result of [TextureStore.removeDetailed]. [removedGeneratedPresetPaths]
 * holds the absolute paths of the generated display presets deleted along
 * with the texture (empty when there were none): the caller may be RENDERING
 * one of them right now, so it needs the paths to know whether its current
 * .milk selection just went away.
 */
data class TextureRemoveOutcome(
    /** Whether the texture file itself was deleted. */
    val removed: Boolean,
    val removedGeneratedPresetPaths: List<String>,
    /** The texture list after the removal, exactly as [TextureStore.list] would return it. */
    val textures: List<MilkTexture>,
) {
    /**
     * The preset a single-path caller means: the one keyed on the removed
     * texture's own stored name. Derived, never stored beside the list, so the
     * two can never disagree.
     */
    val removedGeneratedPresetPath: String? get() = removedGeneratedPresetPaths.firstOrNull()
}

/**
 * Manages the shared milkdrop texture directory (filesDir/milk/textures),
 * which [dev.geode.render.scene.MilkdropScene] already adds to projectM's
 * texture search paths. Many MilkDrop presets reference external image
 * textures (the classic Milkdrop texture pack, plus per-preset images); by
 * importing images here, presets that reference them by filename can render
 * correctly instead of falling back to noise or black.
 *
 * Both writers publish through [AtomicWrite]. Copying straight into the
 * destination truncates it to zero first, so re-importing an image over one a
 * preset is already using, or being killed part-way through a large copy, left
 * a truncated file that [list] still offers - and projectM answers a texture
 * it cannot decode with noise or black, which is exactly the failure this
 * store exists to prevent. The in-progress copy is `<name>.<ext>.tmp`, whose
 * extension is not in [IMAGE_EXTS], so it never appears as a saved texture.
 */
class TextureStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dir = File(context.filesDir, "milk/textures").apply { mkdirs() }

    fun list(): List<MilkTexture> =
        dir
            .listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name.lowercase() }
            ?.map { MilkTexture(it.name, it.absolutePath) }
            .orEmpty()

    /**
     * [importDetailed] for callers that only need the updated listing.
     * Per-file failures are dropped here, not surfaced - new UI should call
     * [importDetailed] and show the skip reasons.
     */
    fun import(uris: List<Uri>): List<MilkTexture> = importDetailed(uris).textures

    /**
     * Copies picked images into the texture directory under
     * [safeTextureFileName]: presets reference textures by name, so a name
     * that is already identifier-safe is preserved exactly.
     *
     * Every file gets a [TextureImportResult] instead of being silently
     * swallowed, and content is VALIDATED before anything touches the disk:
     * projectM answers a texture it cannot decode with noise or black, so a
     * copied-in non-image is a broken import the user only discovers later,
     * mid-show. Bitmap-decodable types go through
     * [BitmapFactory.Options.inJustDecodeBounds]; dds/tga - which
     * [BitmapFactory] cannot read - are header-sniffed. Because validation
     * happens on the buffered bytes before the [AtomicWrite] begins, a
     * skipped file leaves no temp artifact and never clobbers a texture
     * already saved under the same name.
     */
    fun importDetailed(uris: List<Uri>): TextureImportOutcome = TextureImportOutcome(uris.map(::importOne), list())

    private fun importOne(uri: Uri): TextureImportResult {
        val name = displayName(uri) ?: "texture_${System.currentTimeMillis()}.png"
        val skipped = { reason: String -> TextureImportResult(name, null, reason) }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in IMAGE_EXTS) {
            return skipped("not a supported image type (" + IMAGE_EXTS.sorted().joinToString(", ") + ")")
        }
        val bytes =
            runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?: return skipped("could not be read")
        if (bytes.isEmpty()) return skipped("file is empty")
        validateImage(bytes, ext)?.let { return skipped(it) }
        val storedName = safeTextureFileName(name)
        val ok = runCatching { AtomicWrite.stream(File(dir, storedName)) { out -> out.write(bytes) } }.getOrDefault(false)
        return if (ok) TextureImportResult(name, storedName, null) else skipped("could not be written")
    }

    /** Null when [bytes] look like a decodable [ext] image, else the skip reason. */
    private fun validateImage(
        bytes: ByteArray,
        ext: String,
    ): String? =
        when (ext) {
            "dds" ->
                if (bytes.size >= 4 && bytes[0] == 'D'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
                    bytes[2] == 'S'.code.toByte() && bytes[3] == ' '.code.toByte()
                ) {
                    null
                } else {
                    "not a DDS texture (missing DDS header)"
                }
            "tga" -> if (isTgaHeader(bytes)) null else "not a TGA image (unrecognized header)"
            else -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
                if (opts.outWidth > 0 && opts.outHeight > 0) null else "not a decodable image"
            }
        }

    /**
     * TGA has no leading magic (only an optional v2 footer), so this checks
     * the three header fields with small legal domains: color-map type (0/1),
     * image type (1-3 or their RLE forms 9-11; 0 means "no image data"), and
     * pixel depth.
     */
    private fun isTgaHeader(b: ByteArray): Boolean {
        if (b.size < 18) return false
        val colorMapType = b[1].toInt() and 0xff
        val imageType = b[2].toInt() and 0xff
        val pixelDepth = b[16].toInt() and 0xff
        return colorMapType <= 1 && imageType in TGA_IMAGE_TYPES && pixelDepth in TGA_PIXEL_DEPTHS
    }

    /** [removeDetailed] for callers that only need the updated listing. */
    fun remove(name: String): List<MilkTexture> = removeDetailed(name).textures

    /**
     * Deletes the texture AND the display preset [generateDisplayPreset] wrote
     * for it, which references the texture by name and renders noise or black
     * once the image is gone - an orphan there silently outlives every removal
     * otherwise. Both sides derive the path through [generatedPresetFile], so
     * they cannot disagree about which file that is.
     *
     * Also sweeps the OLDER name for the same preset - the stem alone - left
     * on disk by installs written before the key changed. Only when no
     * surviving texture still shares that stem, though: with `cover.png` and
     * `cover.jpg` both imported, the legacy file belongs to whichever of them
     * generated it last, and deleting it out from under the other is the very
     * collision this scheme fixed.
     *
     * The outcome carries every deleted preset path so a caller whose CURRENT
     * .milk selection was one of them can react.
     */
    fun removeDetailed(name: String): TextureRemoveOutcome {
        val removed = runCatching { File(dir, name).delete() }.getOrDefault(false)
        val generated = generatedPresetFile(name)
        val remaining = list()
        val stem = name.substringBeforeLast('.')
        val legacy = File(generatedDir(), "show_$stem.milk")
        val sweepLegacy = legacy != generated && remaining.none { it.name.substringBeforeLast('.') == stem }
        val gone =
            listOfNotNull(
                generated.takeIf { runCatching { it.isFile && it.delete() }.getOrDefault(false) },
                legacy.takeIf { sweepLegacy && runCatching { it.isFile && it.delete() }.getOrDefault(false) },
            )
        return TextureRemoveOutcome(
            removed = removed,
            removedGeneratedPresetPaths = gone.map { it.absolutePath },
            textures = remaining,
        )
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun generatedDir(): File = File(appContext.filesDir, "milk/generated")

    /**
     * Where the display preset for the stored texture [name] lives.
     *
     * Keyed on the WHOLE stored name, extension included, because the stem
     * alone is not unique: `cover.png` and `cover.jpg` both survive
     * [safeTextureFileName] as themselves and both used to claim
     * `show_cover.milk`, so using one and then deleting the other deleted the
     * preset that was on screen and left the pointer to it persisted. The
     * stored name is unique by construction, so this is.
     *
     * Note this is the PRESET FILE's name only. The sampler the preset
     * declares still carries the stem, because that is the name projectM
     * resolves a texture by.
     */
    private fun generatedPresetFile(name: String): File = File(generatedDir(), "show_${name.replace('.', '_')}.milk")

    /**
     * Generates a .milk preset that displays [textureName] full-screen with
     * audio-reactive zoom/rotation/brightness, and returns its path. This is
     * what makes imported textures visibly usable: MilkDrop presets only show
     * textures they reference by name, so we write one that does.
     */
    fun generateDisplayPreset(textureName: String): String {
        val base = textureName.substringBeforeLast('.')
        generatedDir().mkdirs()
        val file = generatedPresetFile(textureName)
        // Kept deliberately minimal: projectM's HLSL->GLSL transpiler is fragile
        // with comp shaders (see projectM issue #310), so we use a single
        // sampler declaration, no per-pixel branching, and only intrinsics
        // known to translate cleanly (tex2D, basic math). GetPixel-style
        // feedback via sampler_main keeps motion without extra textures.
        AtomicWrite.text(
            file,
            """
            MILKDROP_PRESET_VERSION=201
            PSVERSION=2
            PSVERSION_WARP=2
            PSVERSION_COMP=2
            [preset00]
            fRating=3.0
            fGammaAdj=1.6
            fDecay=0.97
            fVideoEchoZoom=1.0
            fVideoEchoAlpha=0.0
            nWaveMode=7
            bAdditiveWaves=0
            fWaveAlpha=0.001
            zoom=1.0
            rot=0.0
            cx=0.5
            cy=0.5
            warp=0.0
            sx=1.0
            sy=1.0
            ob_size=0.0
            ib_size=0.0
            per_frame_1=wave_a = 0;
            comp_1=`sampler sampler_$base;
            comp_2=`shader_body
            comp_3=`{
            comp_4=`   float2 uv2 = (uv - 0.5) / (1.0 + 0.10 * bass) + 0.5;
            comp_5=`   float3 img = tex2D(sampler_$base, uv2).xyz;
            comp_6=`   float pulse = 0.75 + 0.45 * treb + 0.30 * bass;
            comp_7=`   ret = img * pulse;
            comp_8=`}
            """.trimIndent(),
        )
        return file.absolutePath
    }

    internal companion object {
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tga", "dds", "dib")

        /** Legal TGA image-type codes: colormapped/truecolor/mono, raw (1-3) and RLE (9-11). */
        private val TGA_IMAGE_TYPES = setOf(1, 2, 3, 9, 10, 11)

        /** Legal TGA pixel depths in bits. */
        private val TGA_PIXEL_DEPTHS = setOf(8, 15, 16, 24, 32)

        /**
         * Filesystem-safe, collision-free file name for a picked image.
         * Texture base names double as shader identifiers (presets reference
         * them as sampler_<basename>), so the base is restricted to
         * [A-Za-z0-9_] and must not start with a digit - which is why this
         * cannot share [PresetStore.safeFileName]: that scheme keeps spaces
         * and hyphens and joins its digest with '-', all illegal in an
         * identifier. The collision rule is the same though: a base that is
         * already identifier-safe keeps its exact old name, and any base this
         * function has to alter carries a short stable digest of the raw base
         * - replacement alone collapsed distinct picked names ("夜曲.png" and
         * "月光.png" both became "__.png"), so importing one silently replaced
         * the other. No on-disk migration pairs with this: an image carries no
         * name inside it to recompute a stem from, and the stored file name IS
         * the name presets already reference, so renaming existing textures
         * would break every preset using them.
         */
        internal fun safeTextureFileName(name: String): String {
            val rawBase = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = rawBase.replace(Regex("[^A-Za-z0-9]"), "_")
            val safeBase = if (base.firstOrNull()?.isDigit() == true) "t$base" else base.ifEmpty { "tex" }
            if (safeBase == rawBase) return "$safeBase.$ext"
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(rawBase.toByteArray(Charsets.UTF_8))
                    .take(4)
                    .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
            return "${safeBase}_$hash.$ext"
        }
    }
}
