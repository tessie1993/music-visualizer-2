package dev.musicviz

import java.io.File

/**
 * The shaders as the app assembles them, for tests that need the real thing.
 *
 * One copy of include resolution. `GlUtil` owns the production one but needs
 * an Android `Context` for `R.raw`, so a test cannot call it; without this
 * every shader test grows its own resolver and they drift apart.
 */
object ShaderSources {
    val rawDir: File = File(ParamSurface.moduleRoot, "app/src/main/res/raw")

    /**
     * `GlUtil.INCLUDE_PATTERN`, character for character. Anchored at both ends
     * because `lib_particle_common.glsl` mentions the directive in prose, and a
     * substring search would read that as a real one.
     */
    val includePattern = Regex("^[ \\t]*//#include[ \\t]+(\\w+)[ \\t]*$", RegexOption.MULTILINE)

    fun all(): List<File> = rawDir.listFiles { f -> f.extension == "glsl" }.orEmpty().sortedBy { it.name }

    /** Fragments meant to be pasted into another shader; not compilable alone. */
    fun libraries(): List<File> = all().filter { it.name.startsWith("lib_") }

    /** Shaders that carry a `#version` and a `main`, so a compiler can take them. */
    fun standalone(): List<File> = all() - libraries().toSet()

    fun includesIn(file: File): List<String> = includePattern.findAll(file.readText()).map { it.groupValues[1] }.toList()

    /** The source a driver would see: one level of includes, as `GlUtil` resolves them. */
    fun expand(file: File): String {
        val bodies = libraries().associate { it.name.removeSuffix(".glsl") to it.readText() }
        return includePattern.replace(file.readText()) { match ->
            Regex.escapeReplacement(bodies[match.groupValues[1]] ?: error("unknown include ${match.groupValues[1]}"))
        }
    }

    /** `vert` or `frag`, by the naming every shader here follows. */
    fun stageOf(file: File): String = if (file.name.endsWith("_vert.glsl")) "vert" else "frag"
}
