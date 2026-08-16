package dev.musicviz.engine.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * What §5.3's stack costs per hop with the FFT the project already ships.
 *
 * V2-3-02's third bullet is "benchmark current Kotlin FFT versus alternatives;
 * **do not add PFFFT yet**" — so this measures the incumbent and provides the
 * number a later comparison would have to beat. No alternative is added.
 *
 * The assertion is deliberately loose. This runs on whatever CI machine is
 * free, so a tight bound would be a flake generator; what it can honestly
 * catch is a change of order — an FFT that started allocating per call, or a
 * size that stopped hitting the radix-2 path. The measured figures live in
 * `STATUS.md`, where a human can compare them, rather than in an assertion
 * pretending to be a device benchmark under §2.1 rule 8.
 */
class SpectrumBenchmarkTest {
    @Test
    fun `the whole stack costs well under one hop`() {
        val rate = 48_000
        // §5.3's stack shares a 512-sample hop, so one hop is 10.7 ms at
        // 48 kHz. Every branch has to be computed inside that, on one core,
        // with the rest of the app's frame still to draw.
        val hopMs = 512.0 * 1000.0 / rate

        val results =
            AnalysisBranch.STACK.map { branch ->
                val spectrum = Spectrum(branch.windowFrames)
                val window = WindowTable(branch.windowFrames)
                val source = FloatArray(branch.windowFrames) { i -> sin(2.0 * PI * 440.0 * i / rate).toFloat() }
                val windowed = FloatArray(branch.windowFrames)

                repeat(WARMUP) {
                    window.applyInto(source, 0, windowed)
                    spectrum.compute(windowed)
                }
                val start = System.nanoTime()
                repeat(RUNS) {
                    window.applyInto(source, 0, windowed)
                    spectrum.compute(windowed)
                }
                val perFrameMs = (System.nanoTime() - start) / 1e6 / RUNS
                println("${branch.name} (${branch.windowFrames}): %.4f ms/frame".format(perFrameMs))
                branch.name to perFrameMs
            }

        val total = results.sumOf { it.second }
        println("stack total: %.4f ms/hop, hop budget %.2f ms".format(total, hopMs))
        assertTrue(
            "the four branches cost %.3f ms per hop against a %.2f ms budget".format(total, hopMs),
            total < hopMs,
        )
    }

    private companion object {
        const val WARMUP = 2_000
        const val RUNS = 2_000
    }
}
