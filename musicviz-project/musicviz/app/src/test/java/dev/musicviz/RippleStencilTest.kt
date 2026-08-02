package dev.musicviz

import dev.musicviz.render.fluid.RippleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The two gates in `ripple_update_frag.glsl`, and the property each one is
 * for.
 *
 * That shader is not one style's. It runs for WATER and it runs for the
 * renderer-owned ripple overlay, and the overlay rides on top of EVERY style -
 * so the risk in adding anything to it is not that the new thing is wrong, it
 * is that the old thing moved. Both features therefore default to OFF, both
 * defaults are the number zero (which is what GL ES leaves in an active
 * uniform nobody uploads), and the bulk of this file is the proof that OFF is
 * the same arithmetic as before rather than a close approximation of it:
 * [Stencil] mirrors the NEW shader line for line, and
 * [theDefaultPathIsFloatForFloatWhatRippleMathAlreadyMirrors] runs it against
 * [RippleMath.waveStep] - the CPU mirror of the OLD shader, untouched by this
 * work - at zero tolerance.
 *
 * The rest is each feature's justification, asserted as a PROPERTY rather than
 * as a formula. The 9-point stencil has to be measurably more isotropic than
 * the 5-point one, because that is its whole reason to exist. The vessel mask
 * has to make its rim a Dirichlet node, which is a genuinely different
 * boundary from the square grid edge the sim already has, rather than a crop.
 *
 * Source-level where the question is about the code, following
 * [SharedShaderPreludeTest]: a unit test has no GL context to render with.
 */
class RippleStencilTest {
    private companion object {
        val RAW: File = File(ParamSurface.moduleRoot, "app/src/main/res/raw")

        /** The 5-point Laplacian, exactly as the shader has always spelled it. */
        const val FIVE_POINT = "lap = sampleH(vL) + sampleH(vR) + sampleH(vT) + sampleH(vB) - 4.0 * hv.x;"

        /** Every main source file, for the "nobody uploads these" scan. */
        val MAIN_SOURCES: List<File> by lazy {
            File(ParamSurface.moduleRoot, "app/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
                .also { assertTrue("no main sources found", it.size > 50) }
        }
    }

    private val shader: String by lazy { File(RAW, "ripple_update_frag.glsl").readText() }

    // ---- the mirror ------------------------------------------------------

    /**
     * A line-for-line CPU mirror of the shader as it now stands, both gates
     * included, on a [w] x [h] grid with clamped-edge neighbour reads (the
     * `CLAMP_TO_EDGE` analogue of `sampleH`'s half-texel clamp).
     *
     * The shader's two halves are simultaneous - the v update reads only h and
     * the h update reads only v - so updating every cell's v and then every
     * cell's h reproduces it, which is the argument [RippleMath.waveStep]
     * already makes for itself. The summation ORDER matches it too, because
     * float addition is not associative and this class's job is to be exact.
     */
    private class Stencil(
        val w: Int,
        val h: Int,
    ) {
        /** Sim-space size of one cell; the domain is 2 sim units tall. */
        val dx: Float = 2f / h
        val height = FloatArray(w * h)
        val velocity = FloatArray(w * h)

        private fun at(
            x: Int,
            y: Int,
        ) = height[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

        /** `vSim.x` for a cell column: x in [-aspect, aspect]. */
        fun simX(x: Int) = (x - (w - 1) * 0.5f) * dx

        /** `vSim.y` for a cell row: y in [-1, 1]. */
        fun simY(y: Int) = (y - (h - 1) * 0.5f) * dx

        /** `1.0 - step(1.0, dot(vSim, vSim) * uVesselInvR2)`, exactly. */
        fun vessel(
            x: Int,
            y: Int,
            invR2: Float,
        ): Float {
            val sx = simX(x)
            val sy = simY(y)
            return if ((sx * sx + sy * sy) * invR2 >= 1f) 0f else 1f
        }

        fun step(
            c: Float,
            dt: Float,
            damping: Float,
            heightDecay: Float = 1f,
            stencil9: Boolean = false,
            vesselInvR2: Float = 0f,
        ) {
            val k = c * c * dt / (dx * dx)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val centre = height[i]
                    val face = at(x - 1, y) + at(x + 1, y) + at(x, y + 1) + at(x, y - 1)
                    val lap =
                        if (stencil9) {
                            val diag = at(x + 1, y + 1) + at(x - 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y - 1)
                            (4f * face + diag - 20f * centre) / 6f
                        } else {
                            face - 4f * centre
                        }
                    velocity[i] = (velocity[i] + k * lap) * damping * vessel(x, y, vesselInvR2)
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val raw = (height[i] + velocity[i] * dt) * heightDecay
                    height[i] = raw.coerceIn(-RippleMath.MAX_HEIGHT, RippleMath.MAX_HEIGHT) * vessel(x, y, vesselInvR2)
                }
            }
        }
    }

    // ---- neutrality ------------------------------------------------------

    @Test
    fun theDefaultPathIsFloatForFloatWhatRippleMathAlreadyMirrors() {
        // The whole risk, in one assertion. RippleMath.waveStep mirrors the
        // shader BEFORE the gates went in and is unchanged by this work;
        // Stencil mirrors it after. Run both over the same field with both
        // gates at their defaults and every cell has to agree at ZERO
        // tolerance, not at 1e-6: this solver is iterated up to six times a
        // frame forever, so a rounding difference is a visibly different pool
        // within a second, and half-float storage would widen it faster still.
        val w = 65
        val h = 40
        val mirror = Stencil(w, h)
        val refH = FloatArray(w * h)
        val refV = FloatArray(w * h)
        // Asymmetric and not smooth, so a stencil that rounded differently
        // could not hide behind the symmetry of a centred drop.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val value = wobble(x * 0.37f + y * 0.11f) * 0.8f - wobble(x * 0.043f - y * 0.29f) * 0.35f
                mirror.height[y * w + x] = value
                refH[y * w + x] = value
                val vel = wobble(x * 0.19f - y * 0.53f) * 0.2f
                mirror.velocity[y * w + x] = vel
                refV[y * w + x] = vel
            }
        }
        val dx = 2f / h
        val c = 1.2f
        val dt = RippleMath.cflClampedDt(c, 1f / 60f, dx)
        val decay = RippleMath.heightDecayPerSubstep(0.985f, dt)
        repeat(60) {
            mirror.step(c = c, dt = dt, damping = 0.985f, heightDecay = decay)
            RippleMath.waveStep(refH, refV, w, h, c, dt, dx, 0.985f, decay)
        }
        for (i in refH.indices) {
            assertEquals("height at cell $i moved with the gates at their defaults", refH[i], mirror.height[i], 0f)
            assertEquals("velocity at cell $i moved with the gates at their defaults", refV[i], mirror.velocity[i], 0f)
        }
        assertTrue("the reference run went flat, so it proved nothing", refH.maxOf { abs(it) } > 0.05f)
    }

    @Test
    fun theShaderStillSpellsTheFivePointStencilTheWayRippleMathMirrorsIt() {
        // Lockstep held as text as well as as arithmetic. RippleMath's header
        // says "if a formula changes in a shader, change it here too", and
        // nothing checked that the two spellings still matched. The default
        // branch has to be the ORIGINAL expression character for character:
        // an algebraically equal rewrite is not good enough, because neither
        // float addition nor half-float rounding is associative.
        assertTrue("the shader's default Laplacian is no longer the shipping expression", shader.contains(FIVE_POINT))
        assertTrue(
            "RippleMath's Laplacian no longer matches the shader's default branch",
            ParamSurface.source("render/fluid/RippleMath.kt").contains("k * (l + r + t + b - 4f * h[i])"),
        )
    }

    @Test
    fun theVesselFactorIsExactlyOneUntilSomebodyAsksForAVessel() {
        // The vessel is gated by a multiply rather than a branch, so its
        // neutrality rests on two things: the factor being exactly 1.0, and
        // 1.0 being a multiplicative identity. Both are checked - the factor
        // across a whole domain including its corners, and the identity over a
        // sweep of bit patterns compared AS RAW BITS, because assertEquals on
        // floats would happily accept a -0.0 that had become a +0.0.
        val grid = Stencil(97, 61)
        for (y in 0 until grid.h) {
            for (x in 0 until grid.w) {
                assertEquals("vessel factor at $x,$y", 1f, grid.vessel(x, y, 0f), 0f)
            }
        }
        val samples =
            floatArrayOf(
                0f, -0f, 1f, -1f, 0.1f, -7.999f, RippleMath.MAX_HEIGHT, -RippleMath.MAX_HEIGHT,
                Float.MIN_VALUE, -Float.MIN_VALUE, 6.1035156e-5f, 3.4028235e38f, 1f / 3f, 0.985f,
            )
        for (s in samples) {
            assertEquals("multiplying by the off vessel factor moved $s", s.toRawBits(), (s * 1f).toRawBits())
        }
    }

    @Test
    fun nothingThatShipsCanTurnEitherGateOn() {
        // The gates are defaults only because no Kotlin uploads them: GL ES
        // initialises an active uniform to 0 at link, and RippleSim.step sets
        // uK, uDt, uDamping and uHeightDecay and nothing else. If this ever
        // fails because a real consumer landed - bessel_drum is the first one
        // the plan names - the fix is to name that consumer here, not to
        // delete the scan. What must not happen is a style acquiring the
        // 9-point stencil or a vessel by accident and taking WATER and the
        // all-styles overlay with it.
        for (uniform in listOf("uStencil9", "uVesselInvR2")) {
            val writers = MAIN_SOURCES.filter { it.readText().contains(uniform) }.map { it.name }
            assertTrue("$uniform is uploaded by $writers - it is no longer defaulted off", writers.isEmpty())
        }
        // ...and they really are declared, or "nobody uploads them" would also
        // be true of a feature that had never been wired at all.
        assertTrue("uStencil9 is not declared", shader.contains("uniform float uStencil9;"))
        assertTrue("uVesselInvR2 is not declared", shader.contains("uniform float uVesselInvR2;"))
        assertTrue("the stencil gate is not a uniform branch", shader.contains("if (uStencil9 > 0.5) {"))
        assertTrue(
            "the vessel gate stopped being an exact multiply by one",
            shader.contains("float vessel = 1.0 - step(1.0, dot(vSim, vSim) * uVesselInvR2);"),
        )
        // vSim carries the sim-space position the vessel is round in, and it
        // has to arrive from the shared vertex shader rather than be rebuilt.
        assertTrue("the update pass does not read vSim", shader.contains("in vec2 vSim;"))
        assertTrue(
            "fluid_base_vert no longer supplies vSim",
            File(RAW, "fluid_base_vert.glsl").readText().contains("out vec2 vSim;"),
        )
    }

    // ---- what the 9-point stencil is FOR ---------------------------------

    /**
     * The stencil applied to a sampled plane wave `cos(k . x)` at the origin
     * of a unit grid. The exact Laplacian answers `-|k|^2` whichever way the
     * wave points; what the discrete operator answers instead, as a function
     * of direction, IS its anisotropy.
     */
    private fun response(
        kx: Double,
        ky: Double,
        nine: Boolean,
    ): Double {
        val face = 2 * cos(kx) + 2 * cos(ky)
        if (!nine) return face - 4.0
        val diag = 2 * cos(kx + ky) + 2 * cos(kx - ky)
        return (4 * face + diag - 20.0) / 6.0
    }

    /** Spread of the effective `|k|^2` over direction, relative to its mean. */
    private fun anisotropy(
        kh: Double,
        nine: Boolean,
    ): Double {
        val values =
            (0..90).map { deg ->
                val th = Math.toRadians(deg.toDouble())
                -response(kh * cos(th), kh * sin(th), nine) / (kh * kh)
            }
        return (values.max() - values.min()) / values.average()
    }

    @Test
    fun theNinePointStencilIsIsotropicWhereTheFivePointOneIsNot() {
        // The property, not the formula. A rotationally invariant operator
        // answers the same thing for a plane wave of a given wavelength no
        // matter which way that wave points; a square-grid stencil does not,
        // and the 5-point one's leading error term, d4/dx4 + d4/dy4, is
        // largest exactly along the axes. Swept from twenty cells per
        // wavelength down to three, which is the whole resolvable band.
        val band = listOf(0.3, 0.5, 0.8, 1.0, 1.3, 1.5, 2.0)
        for (kh in band) {
            val five = anisotropy(kh, nine = false)
            val nine = anisotropy(kh, nine = true)
            assertTrue("the 5-point stencil is already isotropic at kh=$kh ($five) - the premise is wrong", five > 0.003)
            assertTrue("the 9-point stencil is not rounder at kh=$kh: $nine vs $five", nine < five / 6.0)
        }
        // Across the band a style actually resolves, it is an order of
        // magnitude rather than a few percent.
        for (kh in band.filter { it <= 1.5 }) {
            val gain = anisotropy(kh, nine = false) / anisotropy(kh, nine = true)
            assertTrue("only ${gain}x rounder at kh=$kh", gain > 10.0)
        }
        // Along a grid axis the two are IDENTICAL - both reduce to 2cos(k) - 2
        // - so the 9-point form is not simply a different amount of smoothing
        // bolted on. It moves the diagonals and only the diagonals, which is
        // exactly what "more isotropic" had to mean here.
        for (kh in listOf(0.3, 0.8, 1.5)) {
            assertEquals("the stencils disagree along the axis at kh=$kh", response(kh, 0.0, false), response(kh, 0.0, true), 1e-12)
        }
    }

    @Test
    fun theNinePointStencilChangesNeitherTheConservedMeanNorTheCflClamp() {
        // Two things the 5-point stencil guarantees that a replacement must
        // not quietly take away. Its weights sum to zero, which is why the
        // Neumann grid conserves mean(h) exactly - the fact RippleDrainTest
        // and uHeightDecay are both built on, and a stencil that broke it
        // would make the height drain either useless or ruinous. And its
        // spectral radius is 8, which is where RippleMath's 0.7 CFL constant
        // comes from; a LARGER radius would demand a tighter clamp, and this
        // one's is 32/6, so the shipping clamp only gets more conservative.
        assertEquals("the 9-point weights no longer sum to zero", 0.0, 4 * 4 + 4 * 1 - 20.0, 0.0)
        assertEquals("the 5-point response at zero wavenumber", 0.0, response(0.0, 0.0, false), 1e-12)
        assertEquals("the 9-point response at zero wavenumber", 0.0, response(0.0, 0.0, true), 1e-12)
        var worstFive = 0.0
        var worstNine = 0.0
        for (i in 0..180) {
            for (j in 0..180) {
                val kx = Math.PI * i / 180.0
                val ky = Math.PI * j / 180.0
                worstFive = maxOf(worstFive, -response(kx, ky, false))
                worstNine = maxOf(worstNine, -response(kx, ky, true))
            }
        }
        assertEquals("the 5-point spectral radius moved", 8.0, worstFive, 1e-9)
        assertEquals("the 9-point spectral radius is not 32/6", 32.0 / 6.0, worstNine, 1e-9)
        assertTrue("the 9-point stencil would need a tighter CFL clamp than the shipping one", worstNine < worstFive)
    }

    @Test
    fun aCircularFrontStaysCircularUnderTheNinePointStencilAndDoesNotUnderTheFivePoint() {
        // The same isotropy, as the artefact it actually is on a screen. A
        // symmetric pulse is released at the centre of a square grid,
        // propagated, and then asked where its front got to along an axis
        // versus along the 45-degree diagonal. Under the 5-point stencil the
        // diagonal runs ahead by ten percent of the radius - the ring is a
        // rounded square - and that is what the plan means by a drumhead that
        // "will not read as circular".
        val five = Stencil(161, 161).also { pulse(it) }
        repeat(90) { five.step(c = cflSpeed(five), dt = 1f, damping = 1f, stencil9 = false) }
        val nine = Stencil(161, 161).also { pulse(it) }
        repeat(90) { nine.step(c = cflSpeed(nine), dt = 1f, damping = 1f, stencil9 = true) }
        val axisFive = frontRadius(five, diagonal = false)
        val outFive = abs(frontRadius(five, diagonal = true) - axisFive) / axisFive
        val axisNine = frontRadius(nine, diagonal = false)
        val outNine = abs(frontRadius(nine, diagonal = true) - axisNine) / axisNine
        assertTrue("the front never travelled ($axisFive / $axisNine cells)", axisFive > 30f && axisNine > 30f)
        assertTrue("the 5-point ring came out round (${outFive * 100}%) - the premise is wrong", outFive > 0.05f)
        assertTrue("the 9-point ring is out of round by ${outNine * 100}%", outNine < 0.01f)
        assertTrue("the 9-point ring is only ${outFive / outNine}x rounder", outFive / outNine > 10f)
    }

    // ---- what the vessel mask is FOR -------------------------------------

    @Test
    fun theVesselErasesEverythingAtAndOutsideItsRadiusAndNothingInside() {
        // What the mask claims at the boundary, taken literally. The rim cell
        // ITSELF is zeroed - `step(1.0, r2 * invR2)` fires at r == R, not past
        // it - because a Dirichlet condition pins the boundary, and a rim that
        // was merely the last live cell would put the boundary half a cell
        // outside the radius the caller asked for.
        val g = Stencil(121, 121)
        val radius = 0.45f
        val invR2 = 1f / (radius * radius)
        // Fill the whole grid, so the mask has something to erase everywhere
        // rather than nothing to do outside.
        for (i in g.height.indices) g.height[i] = 0.5f
        repeat(3) { g.step(c = cflSpeed(g), dt = 1f, damping = 1f, vesselInvR2 = invR2) }
        var live = 0
        for (y in 0 until g.h) {
            for (x in 0 until g.w) {
                val r = sqrt(g.simX(x) * g.simX(x) + g.simY(y) * g.simY(y))
                val i = y * g.w + x
                if (r >= radius) {
                    assertEquals("height survived at r=$r, outside the rim", 0f, g.height[i], 0f)
                    assertEquals("velocity survived at r=$r, outside the rim", 0f, g.velocity[i], 0f)
                } else if (abs(g.height[i]) > 1e-6f) {
                    live++
                }
            }
        }
        assertTrue("the vessel emptied itself along with its exterior ($live live cells)", live > 1000)
        // The radius is in SIM units, so on a 2:1 grid the rim stays a circle
        // instead of becoming an ellipse the moment the screen is not square.
        // Forty cells out in x and forty cells out in y are the same distance,
        // so a vessel has to admit both or neither.
        val wide = Stencil(240, 120)
        for ((r, expected) in listOf(0.7f to 1f, 0.6f to 0f)) {
            val inv = 1f / (r * r)
            assertEquals("x offset at radius $r", expected, wide.vessel(160, 60, inv), 0f)
            assertEquals("y offset at radius $r", expected, wide.vessel(120, 100, inv), 0f)
        }
    }

    @Test
    fun theVesselRimReflectsInvertedWhereTheSquareEdgeDoesNot() {
        // The point of the mask, and why it is not merely a crop: a Dirichlet
        // boundary is a NODE, so the wave that comes back off it has the
        // opposite sign - which is what a clamped drumhead does. The sim's own
        // square edge is the opposite boundary, a Neumann antinode (the
        // "square bathtub" the plan wants replaced), and it reflects without
        // inverting. Both runs release the same pulse at the centre and watch
        // the centre cell over the round trip to their own boundary; the rim
        // sits at 27 cells and the grid edge at 60, so each return lands in
        // its own window and what is compared is the SIGN of the first
        // arrival, never the timing.
        val vessel = Stencil(121, 121).also { pulse(it) }
        val vesselTrace = ArrayList<Float>()
        repeat(160) {
            vessel.step(c = cflSpeed(vessel), dt = 1f, damping = 1f, vesselInvR2 = 1f / (0.45f * 0.45f))
            vesselTrace += vessel.height[60 * 121 + 60]
        }
        val bathtub = Stencil(121, 121).also { pulse(it) }
        val bathtubTrace = ArrayList<Float>()
        repeat(260) {
            bathtub.step(c = cflSpeed(bathtub), dt = 1f, damping = 1f)
            bathtubTrace += bathtub.height[60 * 121 + 60]
        }
        val fromRim = firstArrival(vesselTrace, 70, 130)
        val fromEdge = firstArrival(bathtubTrace, 170, 240)
        assertNotNull("nothing came back off the vessel rim", fromRim)
        assertNotNull("nothing came back off the square edge", fromEdge)
        assertTrue("the Dirichlet rim reflected without inverting ($fromRim)", fromRim!! < 0f)
        assertTrue("the square edge inverted its reflection ($fromEdge) - it is a Neumann boundary", fromEdge!! > 0f)
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * A wave speed giving `c*dt/dx = 0.59` at `dt = 1` on [g] - inside
     * [RippleMath.cflClampedDt]'s 0.7 ceiling, so the propagation runs above
     * are stable for the same reason the sim is.
     */
    private fun cflSpeed(g: Stencil): Float = sqrt(0.35f) * g.dx

    /** A tight symmetric pulse at the grid centre: one drop, sharply focused. */
    private fun pulse(g: Stencil) {
        val cx = (g.w - 1) / 2
        val cy = (g.h - 1) / 2
        for (y in 0 until g.h) {
            for (x in 0 until g.w) {
                val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat()
                g.height[y * g.w + x] = exp(-d2 / 4f)
            }
        }
    }

    /**
     * Where the front got to along one ray, in cells, with a parabolic fit
     * across the peak so the answer is not quantised to whole cells - the
     * effect being measured is a few percent of a radius.
     */
    private fun frontRadius(
        g: Stencil,
        diagonal: Boolean,
    ): Float {
        val cx = (g.w - 1) / 2
        val cy = (g.h - 1) / 2
        val reach = if (diagonal) ((g.w / 2 - 2) / sqrt(2f)).toInt() else g.w / 2 - 2
        val ray = (1 until reach).map { i -> abs(g.height[(cy + if (diagonal) i else 0) * g.w + cx + i]) }
        val peak = ray.indexOf(ray.max())
        var shift = 0f
        if (peak in 1 until ray.size - 1) {
            val denom = ray[peak - 1] - 2 * ray[peak] + ray[peak + 1]
            if (abs(denom) > 1e-12f) shift = (ray[peak - 1] - ray[peak + 1]) / (2f * denom)
        }
        return (peak + 1 + shift) * if (diagonal) sqrt(2f) else 1f
    }

    /**
     * The signed value of the first arrival inside a window: the first sample
     * reaching 30% of the window's peak. A threshold rather than an extremum,
     * because the caller wants the sign of the LEADING lobe and an extremum
     * would find the largest one.
     */
    private fun firstArrival(
        trace: List<Float>,
        from: Int,
        to: Int,
    ): Float? {
        val window = trace.subList(from, to)
        val peak = window.maxOf { abs(it) }
        return window.firstOrNull { abs(it) > 0.3f * peak }
    }

    /** A deterministic wobble in [-1,1]; a gate does not get a random source. */
    private fun wobble(t: Float): Float = sin(t.toDouble()).toFloat()
}
