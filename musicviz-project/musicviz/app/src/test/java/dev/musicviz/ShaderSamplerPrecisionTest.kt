package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every shader in `res/raw` that declares a `sampler2D` must pin its sampler
 * precision.
 *
 * GLSL ES 3.00 gives `sampler2D` a default precision of LOWP (range [-2, 2),
 * ~8 fraction bits) in BOTH stages. Most of this app's sampled textures are
 * not 8-bit: the audio texture and the beam waveform are `GL_R32F`, the fluid
 * family's velocity/dye/height fields are half-float, and the scene buffers
 * are HDR. On desktop-ish drivers the qualifier is ignored, but Mali honors
 * it - every read through a default-precision sampler comes back clamped and
 * quantized, which is a live device bug ("a few pixels then black" in the
 * fluid sim; a flatlined trace in `beam_vert.glsl`), not a style nit.
 *
 * A shader satisfies the gate either way the codebase already spells it:
 *  - a file-level `precision highp sampler2D;` statement, or
 *  - an explicit `highp`/`mediump` qualifier on EVERY sampler2D declaration
 *    (the `trail_warp_frag.glsl` spelling).
 *
 * Source-scanned like [HyperspaceUniformParityTest]: a precision default is
 * not observable from any runtime object, and there is no GL in a unit test
 * to ask.
 */
class ShaderSamplerPrecisionTest {
    /**
     * Shaders allowed to declare an unqualified sampler2D, with the reason.
     * Keyed by file name. Empty today - every shader in the tree pins its
     * precision - and a new entry needs a reason a reviewer can check
     * against an actual texture format (an 8-bit LUT sampled through lowp
     * would be a legitimate one).
     */
    private val exempt: Map<String, String> = emptyMap()

    @Test
    fun every_sampler2d_shader_pins_its_sampler_precision() {
        val rawDir = rawDir()
        val shaders = rawDir.listFiles { f -> f.name.endsWith(".glsl") }!!.sortedBy { it.name }
        assertTrue("no shaders found in ${rawDir.path}", shaders.isNotEmpty())

        val failures = mutableListOf<String>()
        val exemptionsUsed = mutableSetOf<String>()
        for (file in shaders) {
            val src = stripComments(file.readText())
            val declarations =
                Regex("""\bsampler2D\s+\w+\s*;""")
                    .findAll(src)
                    .map { it.value }
                    .toList()
            if (declarations.isEmpty()) continue
            val hasStatement = Regex("""precision\s+(highp|mediump)\s+sampler2D\s*;""").containsMatchIn(src)
            val allInlineQualified =
                Regex("""uniform\s+(?!(?:highp|mediump)\s+sampler2D)[\w\s]*?sampler2D\s+\w+\s*;""")
                    .findAll(src)
                    .none()
            if (hasStatement || allInlineQualified) continue
            if (file.name in exempt) {
                exemptionsUsed += file.name
                continue
            }
            failures += file.name
        }
        assertEquals(
            "shaders declaring sampler2D without pinned precision (default is LOWP; " +
                "R32F/half-float texels read clamped and quantized on Mali). Add " +
                "`precision highp sampler2D;` after the float precision statement, or " +
                "qualify each declaration: $failures",
            emptyList<String>(),
            failures,
        )
        assertEquals(
            "stale exemptions - these shaders now pin their precision (or no longer " +
                "declare a sampler); remove the entries",
            emptyList<String>(),
            (exempt.keys - exemptionsUsed).sorted(),
        )
    }

    /**
     * The two shaders this gate was written around must stay covered: the
     * beam waveform and the shared audio texture are `GL_R32F`, where LOWP
     * flattens every texel. Named so a future "simplification" that drops
     * the statement from either fails with the reason attached.
     */
    @Test
    fun the_r32f_readers_declare_highp_samplers() {
        for (name in listOf("beam_vert.glsl", "plasma_frag.glsl", "lib_palette.glsl")) {
            val src = stripComments(File(rawDir(), name).readText())
            assertTrue(
                "$name reads an R32F (or LUT) texture and must declare `precision highp sampler2D;`",
                Regex("""precision\s+highp\s+sampler2D\s*;""").containsMatchIn(src),
            )
        }
    }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    /** Walks up from the working directory to the raw resource folder. */
    private fun rawDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf("src/main/res/raw", "app/src/main/res/raw")) {
                val f = File(dir, candidate)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        fail("res/raw not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
