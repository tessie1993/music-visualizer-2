package dev.musicviz.ui

import android.content.Context
import android.net.Uri
import java.io.File

/** A milkdrop texture image available to presets that reference it by name. */
data class MilkTexture(
    val name: String,
    val path: String,
)

/**
 * Manages the shared milkdrop texture directory (filesDir/milk/textures),
 * which [dev.musicviz.render.scene.ProjectMScene] already adds to projectM's
 * texture search paths. Many MilkDrop presets reference external image
 * textures (the classic Milkdrop texture pack, plus per-preset images); by
 * importing images here, presets that reference them by filename can render
 * correctly instead of falling back to noise or black.
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
     * Copies picked images into the texture directory, preserving each file's
     * original name (presets reference textures by name, so the name matters).
     * Returns the updated texture list.
     */
    fun import(uris: List<Uri>): List<MilkTexture> {
        for (uri in uris) {
            runCatching {
                val name = displayName(uri) ?: "texture_${System.currentTimeMillis()}.png"
                if (name.substringAfterLast('.', "").lowercase() !in IMAGE_EXTS) return@runCatching
                val dest = File(dir, sanitize(name))
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return list()
    }

    fun remove(name: String): List<MilkTexture> {
        runCatching { File(dir, name).delete() }
        return list()
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Texture base names double as shader identifiers (presets reference them
     * as sampler_<basename>), so the base is restricted to [A-Za-z0-9_] and
     * must not start with a digit.
     */
    private fun sanitize(name: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9]"), "_")
        val ext = name.substringAfterLast('.', "").lowercase()
        val safeBase = if (base.firstOrNull()?.isDigit() == true) "t$base" else base.ifEmpty { "tex" }
        return "$safeBase.$ext"
    }

    /**
     * Generates a .milk preset that displays [textureName] full-screen with
     * audio-reactive zoom/rotation/brightness, and returns its path. This is
     * what makes imported textures visibly usable: MilkDrop presets only show
     * textures they reference by name, so we write one that does.
     */
    fun generateDisplayPreset(textureName: String): String {
        val base = textureName.substringBeforeLast('.')
        val genDir = File(appContext.filesDir, "milk/generated").apply { mkdirs() }
        val file = File(genDir, "show_$base.milk")
        // Kept deliberately minimal: projectM's HLSL->GLSL transpiler is fragile
        // with comp shaders (see projectM issue #310), so we use a single
        // sampler declaration, no per-pixel branching, and only intrinsics
        // known to translate cleanly (tex2D, basic math). GetPixel-style
        // feedback via sampler_main keeps motion without extra textures.
        file.writeText(
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

    private companion object {
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tga", "dds", "dib")
    }
}
