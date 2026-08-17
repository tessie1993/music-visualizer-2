package dev.geode

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The renderer's `uTime` clock and the wrap that bounds it.
 *
 * `VisualizerRenderer.timeSeconds` is the one clock in the app that is
 * neither wrapped by its own arithmetic nor reset by a context loss: the
 * per-scene clocks live on objects `onSurfaceCreated` rebuilds, this one
 * lives on the renderer, and the live wallpaper renders continuously. Left to
 * accumulate it reaches the point where the float32 ULP of `uTime * 91.7`
 * (the composite pass' Shake) exceeds the per-frame phase advance, and the
 * jitter degenerates into a stutter and then freezes.
 *
 * Source-level, like [RendererWiringTest]: the property is private state on a
 * class that needs a GL thread, but the wrap PERIOD is a fact about the
 * shaders that read the uniform, and that is checkable here - which is the
 * point, because a wrap that is not a whole number of cycles for one of those
 * readers is a visible glitch every time it comes round.
 */
class RenderClockWrapTest {
    private companion object {
        val TAU = 2.0 * PI

        /**
         * Multipliers of `uTime` that end up inside a `sin`/`cos`. These stay
         * continuous only if `k * period` is a whole number of turns.
         *
         * 0.7 sway and 91.7/77.3 shake and 1.0/1.2 warp and 3.0 ripple are
         * `composite_frag`; 0.7/0.9 are `trail_warp_frag`; 5.3 is the particle
         * twinkle in `lib_particle_shade`.
         */
        val TRIG_MULTIPLIERS = listOf(0.7f, 0.9f, 1.0f, 1.2f, 3.0f, 5.3f, 77.3f, 91.7f)

        /**
         * Multipliers that end up inside a `fract`/`floor` instead: the drift
         * scroll (0.1), the strobe (uStrobeHz, 9.0 by default and a whole
         * number of flashes per second for every value the UI offers) and the
         * glitch band clock (12.0). These stay continuous only if
         * `k * period` is a whole NUMBER - a different condition from the
         * sines, and the reason the period is not simply a multiple of 2*pi.
         */
        val STEP_MULTIPLIERS = listOf(0.1f, 9.0f, 12.0f)

        /** Shaders that read the renderer's clock, for the coverage guard. */
        val TIME_SHADERS = listOf("composite_frag.glsl", "trail_warp_frag.glsl", "lib_particle_shade.glsl")
    }

    private val rendererSource: String by lazy { repoFile("src/main/java/dev/geode/render/VisualizerRenderer.kt") }

    /** The declared wrap period, read off the renderer itself. */
    private val period: Float by lazy {
        val m = Regex("""TIME_WRAP_SEC\s*=\s*([0-9_.]+)f""").find(rendererSource)
        if (m == null) {
            fail("VisualizerRenderer no longer declares TIME_WRAP_SEC")
            error("unreachable")
        }
        m.groupValues[1].replace("_", "").toFloat()
    }

    @Test
    fun theFrameClockIsWrapped() {
        // The defect: `timeSeconds += dt` and nothing else, for the life of
        // the process, across every EGL context loss.
        assertTrue(
            "onDrawFrame no longer wraps timeSeconds - it will drift into float32 mush again",
            rendererSource.contains("timeSeconds = (timeSeconds + dt) % TIME_WRAP_SEC"),
        )
    }

    @Test
    fun everySineOfTheClockIsPhaseContinuousAcrossTheWrap() {
        // A wrap that leaves a sine mid-cycle is a jump in the image every
        // time it comes round - the exact glitch the wrap exists to avoid.
        // The bar is the float32 ULP the same product already carries at the
        // wrap point: land within a step or two of that and the discontinuity
        // is not merely small, it is the size of the rounding error the term
        // lives with anyway, and so cannot be the thing anyone sees.
        for (k in TRIG_MULTIPLIERS) {
            val product = k.toDouble() * period.toDouble()
            val jump = minOf(product % TAU, TAU - product % TAU)
            val ulp = Math.ulp(k * period).toDouble()
            assertTrue(
                "uTime * $k jumps $jump rad at the wrap, more than 2 ULPs of its own product ($ulp)",
                jump <= 2.0 * ulp,
            )
        }
    }

    @Test
    fun everyStepOfTheClockLandsOnAWholeNumberAcrossTheWrap() {
        // These take fract()/floor() of the product, so continuity needs a
        // whole number rather than a whole number of turns. The drift scroll
        // is the one that would show: at a period of 20*pi (the smallest that
        // makes the sines exact) fract(0.1 * period) is 0.28, i.e. the
        // scrolled image would pop by 28% of the frame every minute.
        for (k in STEP_MULTIPLIERS) {
            val product = k.toDouble() * period.toDouble()
            val off = abs(product - product.roundToLong())
            assertTrue("uTime * $k lands $off away from a whole number at the wrap", off < 1e-3)
        }
    }

    @Test
    fun theWrapStillLeavesTheFastestTermRoomToMove() {
        // The other half of the trade: a longer period is smoother across the
        // wrap but coarser inside it. At the far end of the period the fastest
        // term must still advance many float32 steps per frame, or Shake
        // degenerates into the stutter this whole test exists about.
        val fastest = 91.7f
        val perFrame = fastest / 60f
        val ulp = Math.ulp(fastest * period)
        assertTrue(
            "uTime * $fastest advances only ${perFrame / ulp} ULPs per frame at the end of the wrap",
            perFrame / ulp >= 8f,
        )
        // And the period has to be long enough to be worth having: a wrap
        // every few seconds would be correct and pointless.
        assertTrue("wrap period $period s is implausibly short", period >= 3600f)
    }

    @Test
    fun everyClockMultiplierInTheShadersIsOneThisTestChecked() {
        // The guard that keeps the two lists above honest: a new
        // `sin(uTime * 3.7)` in the composite pass is a new continuity
        // condition, and it must be checked against the period rather than
        // discovered as a once-a-session pop on someone's wallpaper.
        val checked = (TRIG_MULTIPLIERS + STEP_MULTIPLIERS).toSet()
        for (name in TIME_SHADERS) {
            val src = repoFile("src/main/res/raw/$name")
            for (m in Regex("""\b(?:uTime|time) \* ([0-9]+\.[0-9]+)""").findAll(src)) {
                val k = m.groupValues[1].toFloat()
                assertTrue(
                    "$name multiplies the frame clock by $k, which RenderClockWrapTest does not check",
                    k in checked,
                )
            }
        }
        // A bare `uTime` inside a sine (`sin(c.y * 8.0 + uTime)`) is the
        // multiplier 1.0 case, which the regex above cannot see.
        assertTrue("the bare-uTime case must be checked too", 1.0f in TRIG_MULTIPLIERS)
    }

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
