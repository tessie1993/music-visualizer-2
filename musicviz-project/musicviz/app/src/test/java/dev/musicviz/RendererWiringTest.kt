package dev.musicviz

import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.scene.SceneIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * How `VisualizerRenderer` is wired, for the three things about it that no
 * device test would report and no crash would name: which styles exist, which
 * thread may look at them, and what a cached uniform location belongs to.
 *
 * Source-level, like [ParticleGatingTest] reading its gating back out of the
 * UI: the renderer needs a GL context and a GL thread, so the invariants here
 * are ones that can only be stated about the code itself.
 */
class RendererWiringTest {
    private companion object {
        /**
         * The GLSurfaceView.Renderer callbacks - the only code that runs on
         * the GL thread, and so the only code allowed to read the registry.
         */
        val GL_THREAD_CALLBACKS = setOf("onSurfaceCreated", "onSurfaceChanged", "onDrawFrame")
    }

    private val source: String by lazy { repoFile("src/main/java/dev/musicviz/render/VisualizerRenderer.kt") }

    /** `SceneIds.NAME` -> the id string, read off the object by reflection. */
    private val sceneIdValues: Map<String, String> by lazy {
        SceneIds::class
            .java
            .declaredFields
            .filter { it.type == String::class.java }
            .associate {
                it.isAccessible = true
                it.name to it.get(SceneIds) as String
            }
    }

    @Test
    fun theRegistryIsBuiltFromTheListOfOfferedStyles() {
        // These were two parallel spellings of the same thing, and they
        // drifted: Curl Flow was offered for a release while nothing ever
        // constructed it, so picking it silently did nothing at all. One list
        // walked into one factory is what stops that recurring.
        val onSurfaceCreated = functionBody("onSurfaceCreated")
        assertTrue(
            "onSurfaceCreated no longer builds the registry from availableSceneIds()",
            onSurfaceCreated.contains("availableSceneIds()") && onSurfaceCreated.contains("createScene("),
        )
        assertFalse(
            "a style is being constructed under a hardcoded id again",
            Regex("""scenes\[SceneIds\.\w+\] =""").containsMatchIn(source),
        )
    }

    @Test
    fun everyOfferedStyleCanBeBuiltAndEveryBuildableStyleIsOffered() {
        val offered = idsIn(functionBody("availableSceneIds"))
        val buildable = idsIn(functionBody("createScene"))
        assertTrue("no styles parsed out of availableSceneIds", offered.size > 30)
        assertEquals(
            "availableSceneIds and createScene disagree about which styles exist",
            offered,
            buildable,
        )
    }

    @Test
    fun onlyTheGlThreadCallbacksReadTheSceneRegistry() {
        // The GL thread clears and repopulates the registry wholesale on every
        // context recreation. A read from any other thread lands mid-rehash: a
        // key that IS there comes back null (the persisted .milk preset was
        // dropped exactly this way, and MilkDrop came back to projectM's idle
        // logo), and a half-observed resize can spin or throw instead.
        val readers = mutableSetOf<String>()
        var current: String? = null
        source.lines().forEach { line ->
            val declared = Regex("""^ {4}(?:private |internal |override |protected )*fun (\w+)""").find(line)
            when {
                declared != null -> current = declared.groupValues[1]
                line == "    }" -> current = null
            }
            val trimmed = line.trim()
            val prose = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            if (!prose && !line.contains("private val scenes") && Regex("""\bscenes\b""").containsMatchIn(line)) {
                readers += current ?: "outside any function"
            }
        }
        assertEquals("the scene registry is being read off the GL thread", GL_THREAD_CALLBACKS, readers)
    }

    @Test
    fun whatCrossesThreadsIsVolatile() {
        // The class's own convention, and the reason the registry lookup was
        // the odd one out: every other channel between the UI thread and the
        // GL thread here is a @Volatile field or a Concurrent* collection.
        listOf("milkdropScene", "lastMilkPreset").forEach {
            assertTrue(
                "$it is read from the main thread and written on the GL thread, so it must be @Volatile",
                Regex("""@Volatile\s+private var $it\b""").containsMatchIn(source),
            )
        }
    }

    @Test
    fun uniformLocationsAreNeverCachedAgainstAGlProgramName() {
        // glDeleteProgram frees the name and the next glCreateProgram is free
        // to hand the same number straight back, so a program handle is not an
        // identity a cache can be keyed by. It was: evicting a transition
        // variant left its locations behind for whatever program was issued
        // that name next, and since each spliced variant declares its own
        // uniforms, the recycled entries pointed at other slots - a sampler on
        // the wrong unit, a uProgress that never advanced, a black or frozen
        // transition until the context was lost.
        assertFalse(
            "uniform locations keyed by GL program handle",
            Regex("""Map<Int,\s*\w*Map<String, Int>>""").containsMatchIn(source),
        )
        assertTrue(
            "the composite's locations must be resolved through the program object that owns them",
            functionBody("cLoc").contains("compositeProgram.loc("),
        )
    }

    @Test
    fun anEvictedTransitionProgramTakesItsUniformLocationsWithIt() {
        // Cache and program are one object, so "drop the entry" and "drop the
        // locations" are the same statement and cannot come apart again.
        assertTrue(
            "transition variants must be held as CompositeProgram, not as bare handles",
            source.contains("LinkedHashMap<String, CompositeProgram>"),
        )
        val evicting = functionBody("transitionProgram")
        assertTrue(
            "the LRU eviction must remove the map entry, which is what frees the locations",
            evicting.contains("transitionPrograms.remove(oldest)") && evicting.contains("glDeleteProgram("),
        )
    }

    /**
     * The style ids named in [name]'s body, resolved to the strings they
     * stand for. `PARTICLE_SCENES` / `SHADER_SCENES` are expanded from the
     * companion so the two sides can be compared as sets of actual ids.
     */
    private fun idsIn(body: String): Set<String> =
        buildSet {
            if (body.contains("PARTICLE_SCENES")) addAll(VisualizerRenderer.PARTICLE_SCENES)
            if (body.contains("SHADER_SCENES")) addAll(VisualizerRenderer.SHADER_SCENES.keys)
            Regex("""SceneIds\.(\w+)""").findAll(body).forEach { match ->
                val name = match.groupValues[1]
                val id = sceneIdValues[name]
                assertTrue("SceneIds has no $name", id != null)
                if (id != null) add(id)
            }
        }

    /**
     * One member function, declaration included.
     *
     * A block body runs to its matching brace; an expression body (which has
     * no brace of its own to close, and may still open one for a `buildList`)
     * runs to the blank line that separates it from the next member.
     */
    private fun functionBody(name: String): String {
        val declaration =
            Regex("""^ {4}(?:private |internal |override |protected )*fun $name\b""", RegexOption.MULTILINE)
                .find(source)
        if (declaration == null) {
            fail("VisualizerRenderer has no function named $name")
            error("unreachable")
        }
        // Step over the parameter list first: its parentheses are the only
        // reliable end of a signature that may be wrapped over five lines.
        var i = declaration.range.last
        var parens = 0
        var opened = false
        while (i < source.length && !(opened && parens == 0)) {
            when (source[i]) {
                '(' -> {
                    parens++
                    opened = true
                }
                ')' -> parens--
                else -> Unit
            }
            i++
        }
        val brace = source.indexOf('{', i)
        if (brace < 0 || source.substring(i, brace).contains('=')) {
            val blank = source.indexOf("\n\n", i)
            return source.substring(declaration.range.first, if (blank < 0) source.length else blank)
        }
        var depth = 0
        var j = brace
        while (j < source.length) {
            if (source[j] == '{') depth++
            if (source[j] == '}' && --depth == 0) break
            j++
        }
        return source.substring(declaration.range.first, minOf(j + 1, source.length))
    }

    /** Resolves a path under `app/`, whichever directory the tests run from. */
    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
