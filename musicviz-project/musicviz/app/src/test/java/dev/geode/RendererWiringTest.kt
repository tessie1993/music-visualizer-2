package dev.geode

import dev.geode.render.VisualizerRenderer
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.VisualStyleCatalog
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
         * The GLSurfaceView.Renderer callbacks, plus the private helpers only
         * those callbacks call - the only code that runs on the GL thread, and
         * so the only code allowed to read the registry.
         *
         * `sceneFor` is a helper rather than a callback: it is how the two
         * reading callbacks resolve an id, and it may also BUILD the scene, so
         * it is as GL-thread-bound as they are. `buildScene` is the same: it is
         * reached only from `sceneFor` and is the sole writer of the registry.
         * Both are listed here rather than exempted, so a reader added anywhere
         * else still fails this test.
         */
        val GL_THREAD_CALLBACKS =
            setOf("onSurfaceCreated", "onSurfaceChanged", "onDrawFrame", "sceneFor", "buildScene")
    }

    private val source: String by lazy { repoFile("src/main/java/dev/geode/render/VisualizerRenderer.kt") }

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
            "onSurfaceCreated no longer seeds the buildable set from availableSceneIds()",
            onSurfaceCreated.contains("availableSceneIds()"),
        )
        assertFalse(
            "onSurfaceCreated constructs a style again - every style is built on demand by " +
                "buildScene(), and constructing them here is what made the visuals slow to appear",
            onSurfaceCreated.contains("createScene("),
        )
        assertTrue(
            "sceneFor must refuse ids the factory cannot build, or createScene throws on the GL thread",
            functionBody("sceneFor").contains("buildableIds"),
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
    fun everyOfferedStyleExportsThroughTheSameSwitchThatBuildsTheRegistry() {
        // exportSceneFactory was a SECOND hand-maintained scene switch, with a
        // silent `else -> Nebula`: an id it had drifted away from exported a
        // nebula clip without a word said. Delegated to createScene, the walk
        // below is the registry's own walk - every offered id is exportable
        // because offered == buildable (the test above), and an unknown id
        // errors on the export GL thread instead of impersonating Nebula.
        val body = functionBody("exportSceneFactory")
        assertTrue("exportSceneFactory must build through createScene", body.contains("createScene("))
        assertFalse(
            "a second hand-maintained scene switch is growing back in exportSceneFactory",
            body.contains("when (") || body.contains("when {"),
        )
        assertFalse(
            "the silent Nebula fallback is back: an unknown export id must error like createScene's else",
            body.contains("NebulaScene("),
        )
        val offered = idsIn(functionBody("availableSceneIds"))
        val exportable = idsIn(functionBody("exportSceneFactory") + functionBody("createScene"))
        assertEquals("a style is offered that the export factory cannot build", offered, exportable)
    }

    @Test
    fun theLayerSceneRidesTheFlowFieldExactlyAsTheActiveSceneDoes() {
        // The layer path did setParams/update/draw with no flow plumbing while
        // every flow decision looked only at the ACTIVE scene: Inkflow as a
        // layer - a style DEFINED by the field - rode a field that was never
        // stepped for it, never read back into its grid and never took its
        // kicks, so the layer sat on its faint fallback curl instead of
        // flowing. One shared wiring function for both targets is what keeps
        // the two paths from drifting apart again.
        val draw = functionBody("onDrawFrame")
        assertTrue(
            "the FlowField step must consider the layer scene, not just the active one",
            draw.contains("sceneNeedsFlow || layerNeedsFlow"),
        )
        assertTrue(
            "the layer must be resolved before the field steps, or a field-defined layer rides a frozen field",
            draw.indexOf("layerScene =") in 0 until draw.indexOf("ff.step("),
        )
        assertEquals(
            "the active scene and the layer scene must share one flow-consumer wiring",
            2,
            Regex("""wireFlowConsumers\(""").findAll(draw).count(),
        )
        assertEquals(
            "the active scene and the layer scene must both push their kicks back into the field",
            2,
            Regex("""drainFlowKicks\(""").findAll(draw).count(),
        )
    }

    @Test
    fun everyStyleIsBuiltOnDemandRatherThanAtSurfaceCreation() {
        // The registry keys a constructed, init()ed instance per id, and init()
        // is where the shader programs get compiled - ShaderScene one apiece,
        // HyperspaceScene the raymarcher AND a FluidSim, FLUID about a dozen
        // between the sim, the look and the particles. Building the twenty
        // substyles up front put roughly a hundred and thirty compiles on the
        // GL thread before the first frame of ANY style and read as the app
        // freezing; they were made lazy and the other thirty-eight styles kept
        // paying the same toll, about sixty compiles plus every fluid grid
        // allocation, which is why the visuals were slow to appear.
        //
        // So nothing is eager now. The whole point is one construction path,
        // and these are the properties that keep it honest.
        assertFalse(
            "no style may be constructed at surface creation",
            functionBody("onSurfaceCreated").contains("createScene("),
        )
        val build = functionBody("buildScene")
        assertTrue("buildScene must construct through the one factory", build.contains("createScene("))
        assertTrue(
            "buildScene must init() what it builds, or the scene draws with no program",
            build.contains(".init()"),
        )
        assertTrue(
            "buildScene must size what it builds, or it renders at the 1x1 default",
            build.contains(".resize("),
        )
        // The restores that used to be separate loops over a registry assumed
        // to hold every style. An on-demand build has to be indistinguishable
        // from an eager one, and each of these was a real piece of state the
        // context loss destroyed.
        assertTrue("buildScene must re-apply edited user GLSL", build.contains("activeCustomShaders["))
        assertTrue("buildScene must re-queue the last milkdrop preset", build.contains("lastMilkPreset"))
        assertTrue("buildScene must re-apply fluid injection shaders", build.contains("setInjectionShaders("))
        assertTrue("buildScene must adopt the milkdrop scene", build.contains("milkdropScene ="))
    }

    @Test
    fun aTransitionIsStampedWithTheClockAsItStandsAfterTheSceneBuild() {
        // onDrawFrame reads the clock once at the top, then resolves the
        // requested scene - and sceneFor may BUILD it, a lazy substyle being a
        // shader compile plus (Hyperspace) a FluidSim, hundreds of ms. The
        // transition used to be stamped with the pre-build timestamp, so a
        // 500 ms build burned a 1200 ms transition down to its tail before
        // the first frame of it was visible. The stamp must come from a clock
        // read AFTER the sceneFor call, and lastFrameMs must move with it so
        // the build is not billed to the next frame's dt either.
        val draw = functionBody("onDrawFrame")
        val build = draw.indexOf("sceneFor(requestedSceneId)")
        assertTrue("onDrawFrame no longer resolves the scene through sceneFor", build >= 0)
        val reread = draw.indexOf("SystemClock.elapsedRealtime()", build)
        assertTrue("no clock re-read after the potential scene build", reread > build)
        val stamp = draw.indexOf("transitionStartMs =")
        assertTrue(
            "the transition start must be stamped from the post-build clock read",
            stamp > reread,
        )
        assertFalse(
            "the transition is stamped with the top-of-frame timestamp again",
            Regex("""transitionStartMs = now\b""").containsMatchIn(draw),
        )
        assertTrue(
            "lastFrameMs must be restamped after a build, or the build lands in the next dt",
            draw.indexOf("lastFrameMs =", reread) in (reread + 1) until stamp,
        )
        // The frame clock itself still wraps: timeSeconds is an accumulator.
        assertTrue("the TIME_WRAP_SEC wrap on timeSeconds is gone", draw.contains("% TIME_WRAP_SEC"))
    }

    @Test
    fun bothSceneConstructionPathsShareOneTypeDirectedWiring() {
        // onSurfaceCreated wired the particle family's error channel and the
        // shader scenes' palette LUT inline, so a scene built on demand arrived
        // with neither: a driver-rejected shader had nowhere to report and the
        // cyclic colour maps sampled nothing. That was two construction paths
        // sharing a helper; there is now exactly one, which is why
        // onSurfaceCreated must NOT wire anything - it has nothing to wire.
        val surface = functionBody("onSurfaceCreated")
        assertFalse(
            "onSurfaceCreated wires a scene again - construction belongs to buildScene alone",
            surface.contains("wireScene("),
        )
        val build = functionBody("buildScene")
        assertTrue("buildScene must wire what it builds through wireScene", build.contains("wireScene("))
        assertTrue(
            "wiring must precede init(), or a shader rejected in init() reports into the void",
            build.indexOf("wireScene(") in 0 until build.indexOf(".init()"),
        )
        val wire = functionBody("wireScene")
        assertTrue("the particle error channel left wireScene", wire.contains("onShaderError"))
        assertTrue("the palette LUT wiring left wireScene", wire.contains("setPaletteLut"))
        assertFalse(
            "a second inline particle wiring is growing back outside wireScene",
            surface.contains("onShaderError"),
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
            if (body.contains("VisualStyleCatalog.cymatics")) addAll(VisualStyleCatalog.cymaticsIds)
            if (body.contains("VisualStyleCatalog.hyperspace")) addAll(VisualStyleCatalog.hyperspaceIds)
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
