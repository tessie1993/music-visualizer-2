package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.CompositeGrade
import dev.musicviz.render.fluid.FlowField
import dev.musicviz.render.scene.NebulaScene
import dev.musicviz.render.scene.ParticleSceneBase
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * The prelude every scene shader carries, held as an INVARIANT rather than as
 * a list of instances.
 *
 * `view()` and `grade()` are copied byte-for-byte into all twenty-two scene
 * fragment shaders. GLSL has no include for a function that needs the whole
 * uniform block in scope, so the duplication is deliberate (`GlUtil.INCLUDES`
 * carries only the leaf libraries) - but duplication with nothing checking it
 * is how the palette came to live in twenty files at once before
 * `lib_palette.glsl` existed. These tests are that check, plus the properties
 * the prelude's individual stages have to have:
 *
 *  - every shader shares ONE `view()` and ONE `grade()`;
 *  - the endless-zoom exponent is a triangle wave everywhere, so the phase
 *    wrap never halves the frame;
 *  - the beat pulse is a beat RESPONSE, so it is flat without one;
 *  - drift is bounded, so a centred subject always comes back;
 *  - and no shader applies something the composite pass already owns for the
 *    shader family, or re-reads something its own clock already carries.
 *
 * Source-level, like [RecoveredShaderStylesTest] and [ParticleStyleTest]:
 * these are properties of the code, and a unit test has no GL context to
 * render a frame with. The exception is the FlowField coupling at the bottom,
 * which is pure CPU and can simply be stepped.
 */
class SharedShaderPreludeTest {
    private companion object {
        /** `res/raw`, found the way [ParamSurface] finds the project root. */
        val RAW: File = File(ParamSurface.moduleRoot, "app/src/main/res/raw")

        /** A style already wired the current way, used as the reference. */
        const val REFERENCE = "plasma_frag.glsl"

        /** The endless-zoom scale, as every scene shader must spell it. */
        const val ZOOM_EXPONENT = "pow(2.0, 1.0 - abs(2.0 * uZoomPhase - 1.0))"

        /** The sawtooth it replaced: 2x, then a snap back to 1x at the wrap. */
        const val SAWTOOTH_EXPONENT = "pow(2.0, uZoomPhase)"

        /**
         * Morph is the one prelude stage three shaders do not carry. Recorded
         * rather than repaired: adding the block would start moving pictures a
         * Morph slider has never moved, which is a look change and not a fix.
         * Everything AROUND it still has to match, so the comparison below
         * drops the block from all of them and holds the rest to the letter.
         */
        val WITHOUT_MORPH = setOf("hexgrid_frag.glsl", "solar_frag.glsl", "spiral_frag.glsl")

        /** One frame at 60 Hz, the rate the headless steps below run at. */
        const val FRAME: Float = 1f / 60f
    }

    /** Every fullscreen scene shader, i.e. every file carrying the prelude. */
    private val sceneShaders: Map<String, String> by lazy {
        RAW
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith("_frag.glsl") }
            .sortedBy { it.name }
            .associate { it.name to it.readText() }
            .filterValues { it.contains("vec2 view() {") }
            .also { assertTrue("no scene shaders found under $RAW", it.size >= 20) }
    }

    @Test
    fun everySceneShaderCarriesTheSameGrade() {
        val header = "vec3 grade(vec3 col) {"
        val reference = block(sceneShaders.getValue(REFERENCE), header)
        sceneShaders.forEach { (name, src) ->
            assertEquals("$name has drifted from the shared grade()", reference, block(src, header))
        }
    }

    @Test
    fun everySceneShaderCarriesTheSameView() {
        val views = sceneShaders.mapValues { (_, src) -> dropMorph(block(src, "vec2 view() {")) }
        val reference = views.getValue(REFERENCE)
        views.forEach { (name, view) -> assertEquals("$name has drifted from the shared view()", reference, view) }
        assertEquals(
            "the set of shaders missing the Morph stage changed - add the block or update the record",
            WITHOUT_MORPH,
            sceneShaders.filterValues { !it.contains("uMorph > 0.001") }.keys,
        )
    }

    @Test
    fun theEndlessZoomExponentIsTheTriangleFormEverywhere() {
        // A sawtooth exponent reaches 2x and then snaps straight back to 1x
        // when ShaderScene's zoomPhase wraps, halving the whole frame between
        // one frame and the next - roughly once a second at the top of Dive
        // speed, and most visible on exactly the styles whose own text invites
        // the pairing ("Mandelbrot dive: pair with Endless zoom for an
        // infinite descent"). The milkdrop post pass had already been fixed
        // and named the bug in its comment, so it is checked here too: one
        // spelling, every pass that zooms.
        (sceneShaders + ("pm_post_frag.glsl" to raw("pm_post_frag.glsl"))).forEach { (name, src) ->
            val code = stripComments(src)
            assertTrue("$name does not use the shared endless-zoom exponent", code.contains(ZOOM_EXPONENT))
            assertTrue("$name still carries the sawtooth endless-zoom ramp", !code.contains(SAWTOOTH_EXPONENT))
        }
    }

    @Test
    fun theBeatPulseCannotSwellWithoutABeat() {
        // "Beat pulse" is a beat RESPONSE on every other family, through
        // CompositeGrade.pulseAmount - the slider times the squared envelope,
        // which is exactly 0 between hits. On a scene shader it used to ride
        // ShaderScene's BPM phase clock alone, and that clock free-runs at the
        // last detected tempo, so stopping the music left the frame breathing
        // once a second forever. One slider, two meanings.
        sceneShaders.forEach { (name, src) ->
            assertTrue(
                "$name computes its beat pulse without the beat envelope",
                stripComments(src).contains("float pulse = 1.0 + uPulse * 0.22 * beatEnv * beatEnv * beatBump;"),
            )
        }
        for (step in 0..40) {
            assertEquals("a shader pulse appeared with no beat", 1f, shaderPulse(1f, 0f, step / 40f), 0f)
        }
        // And on the beat it has to swell by the SAME amount the composite
        // gives the families it grades, or one slider still means two things.
        for (slider in listOf(0.25f, 0.5f, 1f)) {
            assertEquals(
                "the shader pulse peak disagrees with the composite's",
                CompositeGrade.pulseScale(CompositeGrade.pulseAmount(slider, 1f)),
                shaderPulse(slider, 1f, 0f),
                1e-6f,
            )
        }
    }

    @Test
    fun driftIsBoundedAndContinuousOnEveryStyle() {
        sceneShaders.forEach { (name, src) ->
            val code = stripComments(src)
            assertTrue(
                "$name lets drift accumulate without bound",
                code.contains("vec2 driftPhase = fract(vec2(uDriftX, uDriftY) * uTime * 0.025 + 0.25);") &&
                    code.contains("uv += 1.0 - 2.0 * abs(2.0 * driftPhase - 1.0);"),
            )
        }
        // The three properties that spelling was chosen for. BOUNDED is the
        // fix: at Drift Y 1 a centred subject used to leave frame in ten
        // seconds and never come back, and the randomizer reaches +-0.5 on its
        // own, so a black screen was one press away. CONTINUOUS is what lets
        // the styles that read as scrolling fields keep scrolling, where the
        // composite's hard `fract` wrap would teleport them by a screen width
        // once a cycle - the composite can afford that because it samples a
        // bounded IMAGE, and this indexes an unbounded procedural domain. UNIT
        // SLOPE inside the first cycle is what makes it a strictly bounding
        // change: until the excursion reaches the edge of the frame, the
        // offset is identical to the unbounded one it replaces.
        var previous = drift(-40f)
        var distance = -40f
        while (distance < 40f) {
            distance += 1f / 512f
            val offset = drift(distance)
            assertTrue("drift left [-1,1] at $distance: $offset", abs(offset) <= 1f + 1e-5f)
            assertTrue("drift jumped at $distance", abs(offset - previous) <= 1f / 512f + 1e-4f)
            previous = offset
        }
        for (i in -100..100) {
            val d = i / 100f
            assertEquals("drift is no longer the identity inside its first cycle", d, drift(d), 1e-5f)
        }
    }

    @Test
    fun noSceneShaderRereadsWhatItsOwnClockAlreadyCarries() {
        // uTime IS the speed-integrated clock (ShaderScene.shaderTime), and
        // that integration exists to stop a speed change from teleporting the
        // animation. Julia multiplied by uSpeed on top of it, so its orbit
        // rate went as speed^2 and a nudge of the slider after ten minutes
        // jumped the fractal to a different shape - precisely the teleport the
        // integration was written to prevent. The uniform stays DECLARED (the
        // prelude is also the contract for user-written GLSL in the editor),
        // so what is asserted is that no built-in style reads it.
        sceneShaders.forEach { (name, src) ->
            val code = stripComments(src).replace("uniform float uSpeed;", "")
            assertTrue("$name reads uSpeed on top of the speed-integrated uTime", !code.contains("uSpeed"))
        }
        assertTrue(
            "uTime must stay integrated, or reading uSpeed becomes the only way to honour Speed",
            ParamSurface
                .source("render/scene/ShaderScene.kt")
                // Integrated AND wrapped: the wrap (TIME_WRAP convention)
                // changes nothing about the speed integration this test
                // exists to protect.
                .contains("shaderTime = (shaderTime + p.speed * dt) % TIME_WRAP_SECONDS"),
        )
    }

    @Test
    fun noSceneShaderAppliesWhatTheCompositeAlreadyOwnsForTheShaderFamily() {
        // The shader gate is all-false, so the composite applies no geometry
        // and no grade here - but the FlowField warp is NOT part of that gate:
        // `postFx` bends the fetch for every style. A scene shader sampling
        // uFlow therefore applies one velocity field twice.
        assertEquals(
            CompositeGrade.Gate(geo = false, mirrorInvert = false, grade = false, pulse = false),
            CompositeGrade.gateFor(CompositeGrade.SceneFamily.SHADER),
        )
        sceneShaders.forEach { (name, src) ->
            assertTrue("$name samples uFlow, which the composite already applies", !stripComments(src).contains("uFlow"))
        }
    }

    @Test
    fun theCyclicColourMapIsNotThrottledByPaletteData() {
        // uPalRange is the selected built-in palette's hue SPAN - table data
        // from SceneParams.PALETTES, not a control - and it has no meaning for
        // a measured colour ramp. While it scaled the lookup, picking "Mono"
        // (span 0.02) or one of the 0.08-span hues left every colour map
        // sweeping a few percent of its 256 entries and painting the frame one
        // flat tone, with nothing in the panel connecting the two chip rows.
        val lookup = raw("lib_palette.glsl").substringAfter("vec3 pal(float t) {")
        assertTrue(
            "the colour-map lookup is gated by palette data again",
            lookup.contains("float u = fract(t * uHueRange + uColorShift);"),
        )
        // The procedural ramp still uses it: there it IS the palette's span.
        assertTrue(
            "the procedural palette lost its hue span",
            raw("lib_palette.glsl").contains("t * uPalRange * uHueRange + vec3(0.0, 0.33, 0.67)"),
        )
        val narrow = SceneParams.PALETTES.filter { it.third <= 0.1f }.map { it.first }
        assertTrue("the narrow built-ins this guards against are gone: $narrow", narrow.size >= 4)
    }

    @Test
    fun theParticleFamilyLeavesMirrorToTheComposite() {
        // Mirror shares one gate component with invert, and no particle
        // pipeline can invert - so the composite has to own the pair, and
        // owning it means owning it alone. The population mirror that used to
        // run in postProcess folded about a DIFFERENT axis (pre-rotation NDC,
        // where particle_vert rotates afterwards), so with Rotation up the
        // pairs missed the composite's fold and read as ghost duplicates, and
        // at Rotation 0 they landed exactly on it and were thrown away.
        assertTrue(
            "the composite no longer owns mirror for the particle family",
            CompositeGrade.gateFor(CompositeGrade.SceneFamily.PARTICLE).mirrorInvert,
        )
        val postProcess =
            ParamSurface
                .source("render/scene/ParticleSceneBase.kt")
                .substringAfter("private fun postProcess(p: SceneParams) {")
                .substringBefore("\n    }")
        assertTrue("ParticleSceneBase mirrors the population as well as the composite", !postProcess.contains("mirror"))
    }

    @Test
    fun theFlowFieldCarriesParticlesFromOneFrameToTheNext() {
        // The advection used to be written straight into vertexData, which
        // every style overwrites from its own state on the next frame, so
        // "Particles ride the field" displaced nothing at all: the offset
        // never compounded and the +-1.2 rail was unreachable by construction.
        // Stepped headlessly against a uniform rightward current, where the
        // answer is arithmetic. Both scenes are seeded identically and driven
        // identically, so the field is the ONLY difference between them.
        val params = SceneParams.DEFAULT.copy(flowEnabled = true, flowAdvectParticles = true, flowStrength = 1f)
        val free = NebulaScene(ParticleSceneBase.ShaderSources("", ""), count = 200)
        val ridden = NebulaScene(ParticleSceneBase.ShaderSources("", ""), count = 200)
        ridden.flowGrid = uniformField(vx = 0.6f, vy = 0f)
        free.setParams(params)
        ridden.setParams(params)
        repeat(120) {
            free.update(features(), FRAME)
            ridden.update(features(), FRAME)
        }
        val travelled = meanX(ridden) - meanX(free)
        assertTrue("the field displaced the population by $travelled - it is being discarded again", travelled > 0.2f)
        rowsOf(ridden).forEachIndexed { i, r ->
            assertTrue("particle $i left the advection rail at ${r[0]}, ${r[1]}", abs(r[0]) <= 1.2f && abs(r[1]) <= 1.2f)
        }
        // ...and letting go has to give the population back, rather than
        // parking every particle wherever the current left it.
        ridden.setParams(params.copy(flowEnabled = false))
        repeat(600) {
            free.update(features(), FRAME)
            ridden.update(features(), FRAME)
        }
        val residue = meanX(ridden) - meanX(free)
        assertTrue("the field never let go: $residue still displaced", abs(residue) < 0.02f)
    }

    // ---- helpers -------------------------------------------------------

    /** `view()`'s beat swell, as the shared prelude computes it. */
    private fun shaderPulse(
        slider: Float,
        envelope: Float,
        phase: Float,
    ): Float {
        val env = envelope.coerceIn(0f, 1f)
        val bump = 0.5f + 0.5f * cos(2f * PI.toFloat() * phase)
        return 1f + slider * CompositeGrade.PULSE_GAIN * env * env * bump * bump
    }

    /** The prelude's bounded drift offset, for one axis. */
    private fun drift(distance: Float): Float {
        val phase = (distance * 0.25f + 0.25f).let { it - floor(it) }
        return 1f - 2f * abs(2f * phase - 1f)
    }

    /** A CpuGrid whose velocity is the same everywhere, in clip units/second. */
    private fun uniformField(
        vx: Float,
        vy: Float,
    ): FlowField.CpuGrid =
        FlowField.CpuGrid().apply {
            scale = 1f
            aspect = 1f
            for (cell in 0 until FlowField.CPU_GRID * FlowField.CPU_GRID) {
                data[cell * 4] = vx
                data[cell * 4 + 1] = vy
            }
        }

    private fun features() =
        AudioFeatures(
            bands = FloatArray(64) { 0.2f },
            waveform = FloatArray(64),
            rms = 0.3f,
            bass = 0.3f,
            mid = 0.3f,
            treble = 0.2f,
        )

    private fun rowsOf(scene: ParticleSceneBase): List<FloatArray> {
        val data = scene.particleRecords()
        val stride = ParticleSceneBase.FLOATS_PER_PARTICLE
        return (0 until data.size / stride).map { i -> data.copyOfRange(i * stride, i * stride + stride) }
    }

    private fun meanX(scene: ParticleSceneBase): Float = rowsOf(scene).map { it[0] }.average().toFloat()

    /** A top-level function body, from its signature to the closing brace. */
    private fun block(
        source: String,
        header: String,
    ): String {
        val start = source.indexOf(header)
        assertTrue("$header not found", start >= 0)
        val end = source.indexOf("\n}\n", start)
        assertTrue("$header is never closed", end > start)
        return source.substring(start, end + 3)
    }

    /** [text] without the optional Morph stage; see [WITHOUT_MORPH]. */
    private fun dropMorph(text: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.contains("// Morph:") }
        if (start < 0) return text
        val end = lines.subList(start, lines.size).indexOfFirst { it == "    }" } + start
        return (lines.subList(0, start) + lines.subList(end + 1, lines.size)).joinToString("\n")
    }

    private fun raw(name: String): String = File(RAW, name).readText()

    private fun stripComments(text: String): String = text.replace(Regex("""//[^\n]*"""), "")
}
