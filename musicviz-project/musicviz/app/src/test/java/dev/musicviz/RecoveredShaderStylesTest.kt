package dev.musicviz

import dev.musicviz.render.BlueNoise
import dev.musicviz.render.CompositeGrade
import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.BuiltInPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The two styles that were written on branches that never merged - `winter`
 * (a frozen pond under parallax snowfall) and `lava` (a lava lamp with a real
 * blob lifecycle) - and the reasons a straight copy of either would have been
 * wrong.
 *
 * Both predate three engine changes that a recovered shader cannot be trusted
 * to have kept up with, and none of them fails loudly:
 *
 *  - The palette moved into `lib_palette.glsl`. A shader carrying its own
 *    `pal()` still compiles and still draws; it just stops tracking the app's
 *    colour system and never gets the cyclic colour maps, which is precisely
 *    the drift `//#include` exists to end.
 *  - `ShaderScene` uploads a fixed uniform set. A uniform it does not set
 *    reads 0, and 0 is "off" for every gate in these shaders - so a stale
 *    declaration is a feature that silently disables itself.
 *  - The composite pass warps every style through the FlowField and, for
 *    `SceneFamily.SHADER`, applies NOTHING else (`CompositeGrade.gateFor`
 *    returns an all-false gate, because `view()`/`grade()` in the shader do
 *    the work). A scene shader that also samples `uFlow` therefore applies one
 *    velocity field twice - the same double-apply that made Rotation a no-op
 *    on Hyperspace - and one that drops `view()`/`grade()` gets no zoom, no
 *    rotation and no grade from anywhere at all.
 *
 * Source-level, like [ParticleStyleTest] and [RendererWiringTest]: unit tests
 * have no GL context, and every property here is a property of the code.
 */
class RecoveredShaderStylesTest {
    private companion object {
        /** The two recovered styles, as `scene id to fragment shader`. */
        val RECOVERED = mapOf(SceneIds.WINTER to "winter_frag.glsl", SceneIds.LAVA to "lava_frag.glsl")

        /** A style already wired the current way, used as the reference. */
        const val SIBLING = "plasma_frag.glsl"

        /** Spatial masks a beat-driven addition may ride on. */
        val MASKS = listOf("ring", "rim", "crack", "snow", "flake", "core", "shore")
    }

    private val shaderSceneSource: String by lazy { source("render/scene/ShaderScene.kt") }

    /** The endless-zoom scale statement, as the reference sibling spells it. */
    private val zoomLine: String by lazy {
        stripComments(rawShader(SIBLING))
            .lines()
            .map { it.trim() }
            .single { it.startsWith("float z = uZoom") }
    }

    /** Every uniform name `ShaderScene.draw` actually uploads. */
    private val uploadedUniforms: Set<String> by lazy {
        val byHelper = Regex("""setUniform1f\("(\w+)"""").findAll(shaderSceneSource)
        val direct = Regex("""glGetUniformLocation\(program,\s*"(\w+)"\)""").findAll(shaderSceneSource)
        (byHelper + direct).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun bothStylesAreRegisteredEndToEnd() {
        // Id, registry, and therefore picker and presets: the Shaders tab and
        // the built-in preset table are both derived from SHADER_SCENES, so
        // registering there is what makes a style reachable at all. Asserted
        // rather than assumed, because "the shader exists but nothing offers
        // it" is exactly how these two spent a release on a dead branch.
        assertEquals("winter", SceneIds.WINTER)
        assertEquals("lava", SceneIds.LAVA)
        val renderer = source("render/VisualizerRenderer.kt")
        RECOVERED.forEach { (id, shader) ->
            assertTrue("$id is not in SHADER_SCENES", id in VisualizerRenderer.SHADER_SCENES)
            assertTrue("res/raw/$shader is missing", rawShader(shader).isNotEmpty())
            assertTrue(
                "SHADER_SCENES does not point $id at res/raw/$shader",
                renderer.contains("SceneIds.${id.uppercase()} to R.raw.${shader.removeSuffix(".glsl")},"),
            )
            assertTrue(
                "no built-in preset ships for $id",
                BuiltInPresets.ALL.any { it.sceneId == id },
            )
        }
        // The picker is the registry, not a second list beside it.
        val hub = source("ui/VisualsHub.kt")
        assertTrue(
            "the Shaders tab must be built from VisualizerRenderer.SHADER_SCENES",
            hub.contains("SceneList(VisualizerRenderer.SHADER_SCENES.keys.toList()"),
        )
        // ...and so is the built-in preset table.
        val presets = source("ui/BuiltInPresets.kt")
        assertTrue(
            "built-in looks must be generated from SHADER_SCENES",
            presets.contains("VisualizerRenderer.SHADER_SCENES.keys"),
        )
        // The launch variants are only reachable if ALL concatenates them.
        assertTrue("SHADER_VARIANTS is defined but never added to ALL", presets.contains("+ SHADER_VARIANTS"))
    }

    @Test
    fun theyDeclareOnlyUniformsShaderSceneSets() {
        assertTrue("no uniform uploads found in ShaderScene.kt", uploadedUniforms.isNotEmpty())
        val library = declaredUniforms(rawShader("lib_palette.glsl"))
        RECOVERED.values.forEach { shader ->
            val declared = declaredUniforms(rawShader(shader)) + library
            assertEquals(
                "$shader declares uniforms ShaderScene never sets " +
                    "(they read 0, which switches off whatever they gate)",
                emptyList<String>(),
                (declared - uploadedUniforms).sorted(),
            )
        }
    }

    @Test
    fun theyDeclareExactlyWhatEverySiblingShaderDeclares() {
        // Subset is not enough: a MISSING declaration is the other half of the
        // contract. `uMorph` or `uPaletteMix` absent here would leave the
        // Shape/Color controls that Customize shows only on shader styles
        // (see ShaderLookGatingTest) moving nothing on these two.
        val reference = declaredUniforms(rawShader(SIBLING))
        RECOVERED.values.forEach { shader ->
            assertEquals(
                "$shader has drifted from the shared scene-shader prelude",
                reference.sorted(),
                declaredUniforms(rawShader(shader)).sorted(),
            )
        }
    }

    @Test
    fun theyIncludeTheSharedPaletteLibraryRatherThanCarryingACopy() {
        RECOVERED.values.forEach { shader ->
            val src = rawShader(shader)
            assertEquals(
                "$shader must pull the palette from the shared library",
                listOf("lib_palette"),
                includesOf(src),
            )
            assertTrue(
                "$shader defines its own pal() instead of including lib_palette",
                !Regex("""\bvec3\s+pal(Procedural)?\s*\(""").containsMatchIn(stripComments(src)),
            )
            assertTrue("$shader never calls pal()", stripComments(src).contains("pal("))
        }
        // And the include has to be one the resolver knows, or it expands to
        // nothing on a device and the shader fails with an undefined function.
        assertTrue(
            "GlUtil.INCLUDES does not register lib_palette",
            source("render/scene/GlUtil.kt").contains("\"lib_palette\" to R.raw.lib_palette"),
        )
    }

    @Test
    fun theyDoNotDoubleApplyWhatTheCompositePassAlreadyOwns() {
        // SceneFamily.SHADER's gate is all-false, so the composite applies no
        // geometry and no grade to these - but the FlowField warp is NOT part
        // of that gate: `postFx` bends the fetch for every style. `winter`
        // used to sample uFlow itself, which meant the field moved the picture
        // twice on the one style that read it.
        val gate = CompositeGrade.gateFor(CompositeGrade.SceneFamily.SHADER)
        assertFalse("the shader gate must leave geometry to view()", gate.geo)
        assertFalse("the shader gate must leave mirror/invert to the scene", gate.mirrorInvert)
        assertFalse("the shader gate must leave the colour grade to grade()", gate.grade)
        assertFalse("the shader gate must leave the beat pulse to view()", gate.pulse)
        val composite = rawShader("composite_frag.glsl")
        assertTrue("the composite no longer owns the flow warp", composite.contains("uFlowStrength > 0.001"))
        RECOVERED.values.forEach { shader ->
            val src = stripComments(rawShader(shader))
            assertTrue("$shader samples uFlow, which the composite already applies", !src.contains("uFlow"))
        }
        // The other half: with the gate all-false, whatever the scene does not
        // apply is applied nowhere. Both must run the canonical view()/grade().
        RECOVERED.values.forEach { shader ->
            val src = stripComments(rawShader(shader))
            assertTrue("$shader does not transform through view()", src.contains("view()"))
            assertTrue("$shader does not colour through grade()", src.contains("fragColor = vec4(grade("))
            // Endless zoom's spelling has changed twice, and both times a
            // recovered shader was the one left behind - so this compares
            // against the SIBLING's own line rather than restating a formula
            // that would go stale the same way a third time.
            assertTrue(
                "$shader spells the endless-zoom scale differently from $SIBLING:\n  $zoomLine",
                src.lines().map { it.trim() }.contains(zoomLine),
            )
        }
        // ...and the line the pair is held against has to be the right one.
        // The exponent is a TRIANGLE wave (1x -> 2x -> 1x), the form
        // pm_post_frag uses: a sawtooth `pow(2.0, uZoomPhase)` reaches 2x and
        // then snaps back to 1x at every phase wrap, a visible halving of the
        // whole frame roughly once a second at the top of the Dive speed
        // range. Asserted here so the two recovered styles cannot agree with
        // their siblings ON A REGRESSION.
        assertTrue(
            "the shared endless-zoom exponent is not the triangle form:\n  $zoomLine",
            zoomLine.contains("pow(2.0, 1.0 - abs(2.0 * uZoomPhase - 1.0))"),
        )
    }

    @Test
    fun theyAreDitheredByTheSharedBlueNoiseMaskLikeEveryOtherStyle() {
        // Long smooth ramps are exactly what these two are made of - a pond
        // falloff and a metaball field - so 8-bit banding would be visible on
        // both. Neither needs to do anything for it: the mask is applied once,
        // last, in the composite pass that every scene renders through. What
        // WOULD break it is a scene adding its own noise on top, so that is
        // what is asserted here alongside the shared path still existing.
        val composite = rawShader("composite_frag.glsl")
        assertTrue(
            "the composite pass no longer applies the blue-noise dither",
            composite.contains("texture(uNoise, gl_FragCoord.xy / 64.0).r - 0.5) * uDither"),
        )
        assertTrue(
            "the renderer no longer uploads the dither amount",
            source("render/VisualizerRenderer.kt").contains("BlueNoise.DITHER_AMOUNT"),
        )
        assertEquals("the mask is 64x64, which is what the /64.0 above assumes", 64, BlueNoise.SIZE)
        RECOVERED.values.forEach { shader ->
            val src = stripComments(rawShader(shader))
            assertTrue("$shader dithers itself, doubling the composite's mask", !src.contains("uDither"))
            assertTrue("$shader must not sample the mask directly", !src.contains("uNoise"))
        }
    }

    @Test
    fun bothReactToTheAudioContractTheOtherStylesUse() {
        // The dead-slider gate for these two: `audioDrive` reaches a shader
        // only by scaling uBass/uMid/uTreble/uEnergy in ShaderScene.update, so
        // a style that reads none of them ignores the slider entirely, and
        // `beatResponse` is uBeatResponse or nothing.
        RECOVERED.forEach { (id, shader) ->
            val src = stripComments(rawShader(shader))
            listOf("uBass", "uMid", "uTreble", "uEnergy", "uBeat", "uBeatResponse").forEach {
                assertTrue("$id never reads $it", Regex("""\b$it\b""").containsMatchIn(src))
            }
            // The spectrum texture too: the scalars are three numbers, the
            // texture is what lets a style respond per band.
            assertTrue("$id never samples the spectrum", src.contains("aband("))
        }
        // Both must move on the shared Motion controls, which arrive through
        // the prelude's view() - pinned by the uniform-parity test above - and
        // through the speed-integrated clock.
        RECOVERED.values.forEach { shader ->
            assertTrue("$shader ignores uTime", stripComments(rawShader(shader)).contains("uTime"))
        }
        assertTrue(
            "uTime must stay the speed-integrated clock, or Speed stops meaning anything",
            shaderSceneSource.contains("shaderTime += p.speed * dt"),
        )
    }

    @Test
    fun neitherAddsAFullFrameFlashOutsideTheGradedBudget() {
        // VisualSafety converts its depth budget into slider units by dividing
        // out the coefficient the shader applies (`uFlash * uBeat * 0.6`).
        // That arithmetic only bounds what goes through grade(), so a scene
        // that adds its own beat term to the WHOLE frame escapes the clamp.
        // Both of these keep every beat-driven add multiplied by a spatial
        // mask (a ring, a rim, a flake), which is what the assertion checks:
        // no `uBeat` appears in a statement that adds to col unconditionally.
        RECOVERED.forEach { (id, shader) ->
            val src = stripComments(rawShader(shader))
            assertTrue(
                "$id must apply the shared flash term through grade()",
                src.contains("col += uFlash * uBeat * 0.6;"),
            )
            val body = src.substringAfter("void main()")
            val unmasked =
                body
                    .lines()
                    .map { it.trim() }
                    .filter { it.startsWith("col +=") && it.contains("uBeat") }
                    .filterNot { line -> MASKS.any { line.contains(it) } }
            assertEquals("$id adds an unmasked full-frame beat flash", emptyList<String>(), unmasked)
        }
    }

    private fun declaredUniforms(shader: String): Set<String> =
        Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)\s*;""")
            .findAll(shader)
            .map { it.groupValues[1] }
            .toSet()

    /** `//#include` directives, using `GlUtil.INCLUDE_PATTERN`'s own spelling. */
    private fun includesOf(shader: String): List<String> =
        Regex("""^[ \t]*//#include[ \t]+(\w+)[ \t]*$""", RegexOption.MULTILINE)
            .findAll(shader)
            .map { it.groupValues[1] }
            .toList()

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    private fun rawShader(name: String): String = repoFile("src/main/res/raw/$name")

    private fun source(relative: String): String = repoFile("src/main/java/dev/musicviz/$relative")

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
