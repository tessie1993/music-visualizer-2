package dev.geode.data

import java.io.File
import java.io.InputStream

/**
 * Bulk import of a MilkDrop pack: every `.milk` (and every texture) under a
 * user-picked folder, in one gesture.
 *
 * Before this the only door was the single-file picker, so a community
 * MegaPack - thousands of presets - meant one system dialog per file. The
 * enumeration side (SAF tree walking) stays in the ViewModel where the
 * resolver lives; everything decidable is here, on plain streams and files,
 * where the headless suite can pin it.
 *
 * Files already present are skipped, never overwritten: a name collision with
 * a preset the user edited must not silently undo their work, the same rule
 * the single-file import and the starter packs follow.
 */
object MilkPackImporter {
    /** One file the walker found, name as the provider displays it. */
    class Entry(
        val name: String,
        val open: () -> InputStream?,
    )

    data class Report(
        val presets: Int,
        val textures: Int,
        val skipped: Int,
        /**
         * Imported presets that reference a sampler texture not present after
         * the import - the number one "this preset renders black" cause in
         * community packs, surfaced at import time instead of discovered one
         * broken preset at a time.
         */
        val presetsMissingTextures: Int,
    ) {
        val total: Int get() = presets + textures
    }

    private val TEXTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "tga", "dds")

    /**
     * Sampler names projectM provides itself; a reference to one of these is
     * not a missing file. `main` is the framebuffer, `blur1..3` the blur
     * chain, and the noise_ variants are generated at runtime.
     */
    private val BUILTIN_SAMPLERS =
        setOf(
            "main", "blur1", "blur2", "blur3",
            "noise_lq", "noise_lq_lite", "noise_mq", "noise_hq",
            "noisevol_lq", "noisevol_hq",
        )

    /** `sampler_XXX` with an optional wrap/filter prefix (`fw|fc|pw|pc`). */
    private val SAMPLER_REFERENCE = Regex("""\bsampler(?:_(?:fw|fc|pw|pc))?_(\w+)""")

    fun import(
        entries: List<Entry>,
        milkDir: File,
    ): Report {
        val textureDir = File(milkDir, "textures")
        milkDir.mkdirs()
        textureDir.mkdirs()
        var presets = 0
        var textures = 0
        var skipped = 0
        val importedPresets = mutableListOf<File>()
        for (entry in entries) {
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            val target = targetFor(entry.name, extension, milkDir, textureDir) ?: continue
            val written = if (target.exists()) false else copy(entry, target)
            when {
                !written -> skipped++
                extension == "milk" -> {
                    presets++
                    importedPresets += target
                }
                else -> textures++
            }
        }
        return Report(
            presets = presets,
            textures = textures,
            skipped = skipped,
            presetsMissingTextures = importedPresets.count { missesATexture(it, textureDir) },
        )
    }

    private fun targetFor(
        name: String,
        extension: String,
        milkDir: File,
        textureDir: File,
    ): File? =
        when {
            extension == "milk" -> File(milkDir, PresetStore.milkFileName(name))
            extension in TEXTURE_EXTENSIONS -> File(textureDir, name.substringAfterLast('/'))
            else -> null
        }

    private fun copy(
        entry: Entry,
        target: File,
    ): Boolean =
        runCatching {
            entry.open()?.use { input -> AtomicWrite.stream(target) { out -> input.copyTo(out) } } ?: false
        }.getOrDefault(false)

    /**
     * Whether [preset] references a sampler texture that neither the shared
     * textures directory nor the engine's own builtins provide. File checks
     * are case-insensitive because MilkDrop packs are authored on Windows,
     * where `Tex.jpg` and `tex.jpg` are the same file.
     */
    fun missesATexture(
        preset: File,
        textureDir: File,
    ): Boolean {
        val text = runCatching { preset.readText() }.getOrDefault("")
        if (text.isEmpty()) return false
        val available =
            textureDir
                .listFiles()
                .orEmpty()
                .map { it.nameWithoutExtension.lowercase() }
                .toSet()
        return SAMPLER_REFERENCE
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .filterNot { it in BUILTIN_SAMPLERS }
            .filterNot { it.startsWith("rand") }
            .any { it !in available }
    }
}
