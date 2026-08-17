package dev.geode

import dev.geode.render.scene.CymaticsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * The CYMATICS family's clock/phase contract and its flat-field safety gate.
 *
 * Source-level where the property lives in GLSL (like [RenderClockWrapTest]
 * and [HyperspaceUniformParityTest]), behavioural where the maths is pure
 * Kotlin. Four claims:
 *
 *  1. CLOCK WRAP. `CymaticsScene.time` is uploaded as `uTime` and the live
 *     wallpaper renders for days, so the clock wraps - at 200*pi seconds,
 *     and the shader only ever reads it as `sin/cos(uTime * k)` with k a
 *     literal constant whose product with the period is a whole number of
 *     turns. Anything else pops once per wrap on someone's wallpaper.
 *  2. INTEGRATED PHASES. Swirl and travel are SPEEDS (the repo's rotation
 *     convention): the scene integrates them into wrapped phase accumulators
 *     and the shader never multiplies a rate by uTime - the product form
 *     teleports the field whenever a preset fade or LFO moves the rate.
 *  3. FLAT-FIELD SAFETY. A field with nothing ringing must render near
 *     black. The shader's nodal/halo Gaussians divide by fwidth(h), so a
 *     constant h with the 1e-5 width floor lit ~74% of the screen; the gate
 *     (fwidth half here as [CymaticsMath.nodalGate], amplitude half as
 *     [CymaticsMath.fieldLiveness]) closes that, and `safeDrive` keeps raw
 *     preset doubles (negative, NaN) from producing the degenerate field in
 *     the first place. This sits BELOW VisualSafety, so the shader itself
 *     has to be safe.
 *  4. UNIFORM PARITY. Every uniform the shader declares is uploaded and
 *     vice versa - an unset phase uniform reads 0 and freezes the motion,
 *     silently.
 */
class CymaticsClockSafetyTest {
    private val shader: String by lazy { stripComments(repoFile("src/main/res/raw/cymatics_field_frag.glsl")) }
    private val sceneSource: String by lazy { repoFile("src/main/java/dev/geode/render/scene/CymaticsScene.kt") }

    private val period: Float by lazy {
        val m = Regex("""TIME_WRAP_SECONDS\s*=\s*([0-9_.]+)f""").find(sceneSource)
        if (m == null) {
            fail("CymaticsScene no longer declares TIME_WRAP_SECONDS")
            error("unreachable")
        }
        m.groupValues[1].replace("_", "").toFloat()
    }

    // ------------------------------------------------------------- the clock

    @Test
    fun theSceneClockIsWrapped() {
        assertTrue(
            "CymaticsScene.update no longer wraps its clock - uTime will drift into float32 mush",
            sceneSource.contains("time = (time + dt) % TIME_WRAP_SECONDS"),
        )
        // 200*pi: the specific period every two-decimal sin/cos multiplier is
        // a whole number of turns for. Change it only with the shader's uses.
        assertEquals("TIME_WRAP_SECONDS is no longer 200*pi", 200.0 * PI, period.toDouble(), 1e-3)
    }

    @Test
    fun everyShaderUseOfTheClockIsATrigOfALiteralMultiplier() {
        val body = shader.replace("uniform float uTime;", " ")
        val uses = Regex("""uTime""").findAll(body).toList()
        assertTrue("the shader no longer reads uTime at all - update this test's premise", uses.isNotEmpty())
        val multiplied = Regex("""uTime\s*\*\s*[0-9]+\.[0-9]+""").findAll(body).count()
        assertEquals(
            "every use of uTime must be `uTime * <literal>` so the wrap continuity below can see it",
            uses.size,
            multiplied,
        )
        for (use in uses) {
            val call = enclosingCall(body, use.range.first)
            assertTrue(
                "uTime at offset ${use.range.first} is inside `$call`, not sin/cos - fract/floor " +
                    "of the clock needs a whole-NUMBER period, which 200*pi is not",
                call == "sin" || call == "cos",
            )
        }
    }

    @Test
    fun everyClockMultiplierIsPhaseContinuousAcrossTheWrap() {
        val tau = 2.0 * PI
        val ks =
            Regex("""uTime\s*\*\s*([0-9]+\.[0-9]+)""")
                .findAll(shader)
                .map { it.groupValues[1].toFloat() }
                .toSet()
        assertTrue("no uTime multipliers found in cymatics_field_frag.glsl", ks.isNotEmpty())
        for (k in ks) {
            val product = k.toDouble() * period.toDouble()
            val jump = minOf(product % tau, tau - product % tau)
            assertTrue(
                "sin(uTime * $k) jumps $jump rad at the ${period}s wrap - use a two-decimal multiplier",
                jump < 1e-3,
            )
        }
    }

    // -------------------------------------------------------- phases, not products

    @Test
    fun swirlAndTravelArriveAsIntegratedPhases() {
        // The defect: uploads of rate * speed consumed as sin(uSwirl * uTime),
        // so ANY change to the rate (preset fade, LFO on Speed) teleported the
        // field by (new - old) * uptime radians.
        assertTrue("the shader lost its swirl phase uniform", shader.contains("uniform float uSwirlPhase;"))
        assertTrue("the shader lost its travel phase uniform", shader.contains("uniform float uTravelPhase;"))
        assertTrue("the shader lost its plate scroll uniform", shader.contains("uniform float uDriftShift;"))
        assertTrue("a raw uSwirl rate uniform is back", !Regex("""\buSwirl\b""").containsMatchIn(shader))
        assertTrue("a raw uTravel rate uniform is back", !Regex("""\buTravel\b""").containsMatchIn(shader))
        // The dish's per-mode travel ladder must be an INTEGER multiple of the
        // phase, or the phase's own 2*pi wrap pops the rings.
        assertTrue(
            "the dish travel harmonic is no longer quantized to integers",
            shader.contains("uTravelPhase * max(1.0, floor("),
        )
        // And the scene integrates + wraps all three accumulators.
        for (name in listOf("swirlPhase", "travelPhase", "driftShift")) {
            assertTrue(
                "CymaticsScene no longer wraps its $name accumulator through CymaticsMath.wrapPhase",
                sceneSource.contains("CymaticsMath.wrapPhase($name"),
            )
        }
    }

    @Test
    fun wrapPhaseIsAFlooredModuloThatSurvivesGarbage() {
        val tau = (2.0 * PI).toFloat()
        assertEquals(0.5f, CymaticsMath.wrapPhase(0.5f, tau), 1e-6f)
        assertEquals(7f - tau, CymaticsMath.wrapPhase(7f, tau), 1e-5f)
        // Swirl runs both ways: negative input still lands in [0, period).
        val negative = CymaticsMath.wrapPhase(-0.25f, tau)
        assertTrue("negative phase left the wrap range", negative >= 0f && negative < tau)
        assertEquals(tau - 0.25f, negative, 1e-5f)
        assertEquals(0f, CymaticsMath.wrapPhase(Float.NaN, tau), 0f)
        assertEquals(0f, CymaticsMath.wrapPhase(1f, 0f), 0f)
    }

    // ------------------------------------------------------------ flat-field safety

    @Test
    fun aFlatFieldGetsNoLineLight() {
        // The wash: h constant everywhere -> az = 0 and fwidth(h) = 0, and
        // with the 1e-5 width floor both Gaussians evaluated to 1 across the
        // whole screen. The gradient gate must be CLOSED there...
        assertEquals(0f, CymaticsMath.nodalGate(0f), 0f)
        assertTrue("a near-dead field still passes the gate", CymaticsMath.nodalGate(1e-6f) < 0.1f)
        // ...and OPEN for any genuinely live figure: the coarsest mode the
        // catalog can put on screen still moves h by ~2.5e-3 per pixel.
        assertEquals(1f, CymaticsMath.nodalGate(2.5e-3f), 1e-4f)
        assertTrue(CymaticsMath.nodalGate(1e-4f) >= CymaticsMath.nodalGate(5e-5f))
        // The shader half: nodal and halo are multiplied by the gate, the
        // fill/spec layers by the amplitude gate.
        assertTrue(
            "the shader lost its lineLive gradient gate",
            shader.contains("float lineLive = uFieldLive * smoothstep("),
        )
        assertTrue("nodal is no longer gated", shader.contains("(narrow * narrow)) * lineLive;"))
        assertTrue("halo is no longer gated", shader.contains("(wide * wide)) * lineLive;"))
        assertTrue(
            "the filled surface is no longer gated by uFieldLive",
            shader.contains("uFill * (0.10 + 0.80 * diffuse) * uFieldLive"),
        )
    }

    @Test
    fun fieldLivenessFollowsTheRenderedAmplitude() {
        assertEquals(0f, CymaticsMath.fieldLiveness(0f), 0f)
        assertEquals(1f, CymaticsMath.fieldLiveness(CymaticsMath.LIVE_AMPLITUDE), 1e-6f)
        assertEquals(1f, CymaticsMath.fieldLiveness(1f), 0f)
        assertEquals(0.5f, CymaticsMath.fieldLiveness(CymaticsMath.LIVE_AMPLITUDE * 0.5f), 1e-6f)
        assertEquals("NaN amplitude must read as silence, not as poison", 0f, CymaticsMath.fieldLiveness(Float.NaN), 0f)
    }

    @Test
    fun theDriveIsClampedAtReadIn() {
        // Presets and preset links load raw doubles into SceneParams, so the
        // slider can arrive at anything at all.
        assertEquals(1.2f, CymaticsMath.safeDrive(1.2f), 0f)
        assertEquals(0f, CymaticsMath.safeDrive(-3f), 0f)
        assertEquals(CymaticsMath.MAX_DRIVE, CymaticsMath.safeDrive(99f), 0f)
        assertEquals("NaN slips through coerceIn and poisons the resonator bank", 0f, CymaticsMath.safeDrive(Float.NaN), 0f)
        assertEquals(0f, CymaticsMath.safeDrive(Float.NEGATIVE_INFINITY), 0f)
        assertTrue(
            "CymaticsScene no longer routes Audio drive through safeDrive",
            sceneSource.contains("CymaticsMath.safeDrive(p.audioDrive)"),
        )
    }

    @Test
    fun theSeamMakersAreGone() {
        // sign() was both permanent-seam sources: the Levitator's mirrored
        // fold at y = 0 (fwidth blows up on the crease) and Rosensweig's
        // signed pow(|h|, 0.48), whose infinite derivative at h = 0 lit every
        // nodal line. Neither mapping may come back in that form.
        assertTrue("sign() is back in the cymatics shader - it puts a seam wherever its argument crosses 0", !shader.contains("sign("))
        // The shell's polar unwrap must stay FOLDED: abs(atan) is continuous
        // across the branch cut, the raw angle is a radial seam.
        assertTrue("the harmonic shell lost its symmetric angle fold", shader.contains("abs(atan("))
    }

    // ------------------------------------------------------------- uniform parity

    @Test
    fun theSceneUploadsExactlyWhatTheShaderDeclares() {
        val declared = declaredUniforms(shader)
        val uploaded = uploadedUniforms(stripComments(sceneSource))
        assertTrue("no uniforms found in cymatics_field_frag.glsl", declared.isNotEmpty())
        assertEquals(
            "cymatics_field_frag.glsl declares uniforms CymaticsScene never uploads - an unset " +
                "uniform reads 0 (a frozen phase, a dead safety gate)",
            emptyList<String>(),
            (declared - uploaded).sorted(),
        )
        assertEquals(
            "CymaticsScene uploads uniforms the shader does not declare (location -1, value dropped)",
            emptyList<String>(),
            (uploaded - declared).sorted(),
        )
    }

    // ------------------------------------------------------------------ parse

    /** Name of the innermost call wrapping [pos], or null at top level. */
    private fun enclosingCall(
        src: String,
        pos: Int,
    ): String? {
        var depth = 0
        var i = pos - 1
        while (i >= 0) {
            when (src[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        var j = i - 1
                        while (j >= 0 && (src[j].isLetterOrDigit() || src[j] == '_')) j--
                        return src.substring(j + 1, i)
                    }
                    depth--
                }
            }
            i--
        }
        return null
    }

    private fun declaredUniforms(src: String): Set<String> =
        Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)\s*(?:\[[^\]]*\])?\s*;""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()

    private fun uploadedUniforms(src: String): Set<String> =
        Regex("""loc\("(\w+)"\)""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

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
