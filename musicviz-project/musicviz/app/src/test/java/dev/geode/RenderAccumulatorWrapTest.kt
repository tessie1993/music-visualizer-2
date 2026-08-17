package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every `+= dt`-shaped accumulator under `render/` must wrap, decay or reset.
 *
 * The live wallpaper renders for days without a context loss. A float that
 * only ever accumulates `dt` crosses 2^24 within weeks, after which adding
 * 1/60 s does nothing (absorption) - and long before that its trig consumers
 * are chewing on catastrophic-cancellation noise. The fixed clocks follow
 * the TIME_WRAP convention (`VisualizerRenderer.TIME_WRAP_SEC`,
 * `CymaticsScene.TIME_WRAP_SECONDS`): wrapped in the same statement as the
 * accumulation - `x = (x + dt) % WRAP` - which this scanner deliberately
 * does NOT flag, so the fixed form is invisible to it and the `+=` spelling
 * is what stays under guard.
 *
 * A flagged accumulator passes if the file shows one of:
 *  - decay-toward-target on the same line (`x += (target - x) * k`, the EMA
 *    idiom - bounded by construction);
 *  - a decay, drain or reset of the same variable anywhere else in the file
 *    (`x -=`, `x *=`, or a plain non-declaration `x = ...` such as a respawn
 *    or a clamp-assign);
 *  - an entry in [NOT_WRAPPED] carrying a reason.
 *
 * Stale [NOT_WRAPPED] entries fail the test, so an entry for an accumulator
 * that a later change fixes (e.g. one owned by a scene being reworked in a
 * parallel change) disappears with the fix instead of rotting.
 */
class RenderAccumulatorWrapTest {
    private companion object {
        /**
         * Accumulators allowed to keep a bare `+=` with a dt term, keyed
         * `FileName.kt:variable`. Reasons must describe why the value is
         * bounded anyway - "it looks fine" is not one.
         */
        val NOT_WRAPPED: Map<String, String> = emptyMap()

        val CANDIDATE = Regex("""(\w+)(\[[^\]]*\])?\s*\+=\s*([^\n;]*)""")
        val DT_TOKEN = Regex("""\b(dt|lastDt|dtSeconds|simDt)\b""")
    }

    @Test
    fun every_dt_accumulator_wraps_decays_or_carries_a_reason() {
        val renderDir = renderDir()
        val files = renderDir.walkTopDown().filter { it.name.endsWith(".kt") }.sortedBy { it.name }.toList()
        assertTrue("no Kotlin sources under ${renderDir.path}", files.isNotEmpty())

        val failures = mutableListOf<String>()
        val exemptionsUsed = mutableSetOf<String>()
        for (file in files) {
            val src = stripComments(file.readText())
            for (match in CANDIDATE.findAll(src)) {
                val name = match.groupValues[1]
                val rhs = match.groupValues[3]
                if (!DT_TOKEN.containsMatchIn(rhs)) continue
                // EMA idiom: `x += (target - x) * k` converges, never grows.
                if (Regex("""-\s*${Regex.escape(name)}\b""").containsMatchIn(rhs)) continue
                // Decay, drain, or reset of the same variable elsewhere.
                val evidence =
                    Regex(
                        """(?<!var )(?<!val )\b${Regex.escape(name)}(\[[^\]]*\])?\s*(=(?!=)|-=|\*=)""",
                    ).containsMatchIn(src)
                if (evidence) continue
                val key = "${file.name}:$name"
                if (key in NOT_WRAPPED) {
                    exemptionsUsed += key
                    continue
                }
                failures += "$key  (+= ${rhs.trim().take(60)})"
            }
        }
        assertEquals(
            "unbounded `+= dt` accumulators in render/**. Wrap them in the same " +
                "statement (`x = (x + dt) % WRAP`, TIME_WRAP convention - pattern in " +
                "CymaticsScene), decay them, or add a justified NOT_WRAPPED entry: $failures",
            emptyList<String>(),
            failures,
        )
        assertEquals(
            "stale NOT_WRAPPED entries - the accumulator now wraps (or is gone); " +
                "remove the entries so the exemption cannot hide a regression",
            emptyList<String>(),
            (NOT_WRAPPED.keys - exemptionsUsed).sorted(),
        )
    }

    /**
     * The wrap constants must stay periodic-exact where the fix relied on
     * it: the 200*pi family exists because every consumer is sin/cos with a
     * TWO-DECIMAL rate constant, and k * 200pi is then k * 100 whole turns.
     * If someone "rounds" the constant to 628.32f the wrap becomes a visible
     * phase pop on every cycle instead of a no-op.
     */
    @Test
    fun the_200pi_wrap_constants_are_exactly_200pi() {
        val expected = (200.0 * Math.PI).toFloat() // 628.31853f
        val renderDir = renderDir()
        val declarations =
            renderDir
                .walkTopDown()
                .filter { it.name.endsWith(".kt") }
                .flatMap { file ->
                    Regex("""(?:TIME_WRAP_SECONDS|NOISE_WRAP_SECONDS|IDLE_WRAP_SECONDS|PHASE_WRAP_SECONDS)\s*=\s*628\.\d+f""")
                        .findAll(stripComments(file.readText()))
                        .map { file.name to it.value }
                }.toList()
        assertTrue("no 200*pi wrap constants found under render/ - the convention moved?", declarations.isNotEmpty())
        for ((fileName, decl) in declarations) {
            val value = Regex("""628\.\d+""").find(decl)!!.value.toFloat()
            assertEquals("$fileName: $decl is not 200*pi", expected, value, 1e-3f)
        }
    }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    private fun renderDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf("src/main/java/dev/geode/render", "app/src/main/java/dev/geode/render")) {
                val f = File(dir, candidate)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        fail("render source dir not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
