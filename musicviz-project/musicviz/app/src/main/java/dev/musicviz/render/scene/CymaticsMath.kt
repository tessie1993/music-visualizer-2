package dev.musicviz.render.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The maths behind the CYMATICS style: the sound itself, drawn as the shape
 * it would make in the physical world.
 *
 * Play a tone into a metal plate, scatter sand on it, and the grains collect
 * along the lines that do not move - the nodal lines of the plate's standing
 * wave. Chladni figures. This object turns the analyzer's spectrum into that
 * plate, so what is on screen is a depiction of the sound rather than a
 * decoration reacting to it: pitch decides HOW FINE the figure is, level
 * decides how strongly it stands up, and a chord is the superposition of the
 * figures of its notes - exactly as a real plate would answer.
 *
 * RippleMath/WaterMath convention: the maths lives here, headless and
 * testable, and the GL code in [CymaticsScene] only wires it up. Every
 * formula below has a twin in `cymatics_plate_vert.glsl`; if one changes, the
 * other has to change with it, and [dev.musicviz.CymaticsMathTest] pins them
 * against each other.
 *
 * ### The plate
 *
 * For a square plate clamped at its centre, mode (n, m) has the displacement
 *
 * ```
 * z(x, y) = cos(n*PI*x) * cos(m*PI*y) - cos(m*PI*x) * cos(n*PI*y)
 * ```
 *
 * over x, y in [-1, 1] - the classic Chladni formula, and the one the
 * reference project this style is modelled on plots (codenlighten/3D-Cymatics).
 * It is antisymmetric in (n, m), so (m, n) is the same figure inverted and
 * n == m is identically flat: [MODES] therefore enumerates n > m only.
 *
 * ### Pitch to mode
 *
 * A stiff plate's modal frequencies rise with the SQUARE of the mode order
 * (Kirchhoff plate theory, and the reason Chladni's own law is quadratic),
 * so the order that answers a frequency is
 *
 * ```
 * wavenumber = sqrt(hz / fundamental)
 * ```
 *
 * The consequence is worth stating because it is what makes the style read as
 * music: an octave up is a factor 1.41 finer, not twice as fine, so a whole
 * spectrum - 40 Hz to 16 kHz - lands inside [MAX_ORDER] instead of running off
 * the plate within three octaves.
 */
object CymaticsMath {
    /** Lowest band edge the analyzer resolves; mirrors `FftProcessor.minFreqHz`. */
    const val MIN_BAND_HZ: Float = 40f

    /**
     * Nyquist the band -> frequency map assumes, i.e. 44.1 kHz audio.
     *
     * [dev.musicviz.analysis.AudioFeatures] does not carry the rate the
     * spectrum was measured at, and this is the rate essentially all of it
     * arrives at. The error at 48 kHz is a 4% frequency shift at the very top
     * of the band range - a fraction of one mode order, against a
     * [SceneParams.cymaticsFundamental] slider that retunes the whole mapping
     * by an octave and a half either way.
     */
    const val REFERENCE_NYQUIST_HZ: Float = 22_050f

    /**
     * FFT bins the band map assumes, i.e. `FftProcessor.fftSize` / 2.
     *
     * Needed because the analyzer's low bands are not where the logarithm
     * says they are: at 44.1 kHz a bin is ~21.5 Hz wide, so the bottom ~20
     * log-spaced band edges all round onto the same bin and
     * `FftProcessor.bandEdges` pushes each one a bin further up to keep them
     * distinct. Band 12 covers 280-323 Hz there, not the 130 Hz the pure log
     * spacing suggests, and a plate that believed the logarithm would answer
     * every bass note an order too coarse.
     */
    const val REFERENCE_FFT_BINS: Int = 1024

    /** Highest mode order enumerated: finer than this is sub-pixel on a phone. */
    const val MAX_ORDER: Int = 14

    /** Modes the shader can superpose at once (`uModes[]`'s length). */
    const val MAX_RENDERED_MODES: Int = 8

    /** "Fundamental" slider domain, in Hz: the pitch that gives mode (1, 0). */
    const val MIN_FUNDAMENTAL_HZ: Float = 40f
    const val MAX_FUNDAMENTAL_HZ: Float = 440f

    /** How fast an excited mode reaches full amplitude; a plate answers fast. */
    const val ATTACK_SECONDS: Float = 0.035f

    /** "Ring" slider domain, in seconds of decay time constant. */
    const val MIN_RING_SECONDS: Float = 0.06f
    const val MAX_RING_SECONDS: Float = 2.5f

    /** Amplitudes below this are treated as silent, so the bank settles to 0. */
    const val SILENCE: Float = 1e-4f

    /** Half-width, in bands, of the local mean the tonal-focus whitening uses. */
    const val WHITEN_RADIUS: Int = 4

    /**
     * Visible oscillation rate per unit wavenumber, in Hz.
     *
     * A real plate vibrates at the frequency it is driven at - hundreds of
     * hertz, invisible, and in the flashing band the app's visual-safety work
     * exists to stay out of. This is that motion strobed down: the surface
     * still rises and falls through flat as it physically does, at a rate that
     * keeps the higher (finer) figures livelier than the low ones, and
     * [MAX_VIBRATION_HZ] caps the whole thing well under the WCAG three
     * flashes per second.
     */
    const val VIBRATION_HZ_PER_ORDER: Float = 0.14f
    const val MIN_VIBRATION_HZ: Float = 0.12f
    const val MAX_VIBRATION_HZ: Float = 1.6f

    /** One (n, m) standing wave of the plate. */
    data class Mode(
        val n: Int,
        val m: Int,
    ) {
        /** sqrt(n^2 + m^2): how fine the figure is, and what pitch maps onto. */
        val wavenumber: Float = sqrt((n * n + m * m).toFloat())
    }

    /**
     * Every mode up to [MAX_ORDER], ordered from coarsest to finest.
     *
     * n > m only: the formula is antisymmetric, so (m, n) would duplicate
     * (n, m) upside-down, and n == m is identically zero - a mode that renders
     * a flat plate is not a mode, it is a bug that looks like silence.
     */
    val MODES: List<Mode> =
        buildList {
            for (n in 1..MAX_ORDER) {
                for (m in 0 until n) add(Mode(n, m))
            }
        }.sortedBy { it.wavenumber }

    /** Wavenumbers of [MODES], for allocation-free nearest-mode lookups. */
    private val WAVENUMBERS: FloatArray = FloatArray(MODES.size) { MODES[it].wavenumber }

    /**
     * Mirror of `FftProcessor.bandEdges`: the first FFT bin of each band, and
     * therefore the frequencies each band actually measures.
     *
     * Reproduced rather than called because the analyzer holds an FFT plan and
     * a window buffer that a scene has no business allocating, and because the
     * edges are wanted once per "Fundamental" change rather than per frame -
     * but it IS the same arithmetic, including the clamp and the "bump any
     * repeated edge up one bin" pass that makes the bottom of the range
     * linear, and `CymaticsMathTest` asserts the two agree bin for bin.
     */
    fun bandEdgeBins(
        bandCount: Int,
        nyquistHz: Float = REFERENCE_NYQUIST_HZ,
        bins: Int = REFERENCE_FFT_BINS,
    ): IntArray {
        val edges = IntArray(bandCount + 1)
        val logMin = ln(MIN_BAND_HZ.toDouble())
        val logMax = ln(nyquistHz.toDouble())
        for (b in 0..bandCount) {
            val f = exp(logMin + (logMax - logMin) * b / bandCount)
            edges[b] = ((f / nyquistHz * bins).toInt()).coerceIn(1, bins - 1)
        }
        for (b in 1..bandCount) {
            if (edges[b] <= edges[b - 1]) edges[b] = minOf(bins - 1, edges[b - 1] + 1)
        }
        return edges
    }

    /**
     * Centre frequency of band [band] of [bandCount]: the geometric mean of
     * the frequency range the analyzer takes that band's peak over, i.e. bins
     * `edges[band] .. edges[band + 1]` inclusive.
     *
     * Not hot - [bandModeMap] builds the edge table once and reuses it - so
     * this recomputes the edges rather than caching them behind a lock.
     */
    fun bandCenterHz(
        band: Int,
        bandCount: Int,
        nyquistHz: Float = REFERENCE_NYQUIST_HZ,
        bins: Int = REFERENCE_FFT_BINS,
    ): Float {
        if (bandCount <= 0) return MIN_BAND_HZ
        return centerHz(bandEdgeBins(bandCount, nyquistHz, bins), band, nyquistHz, bins)
    }

    private fun centerHz(
        edges: IntArray,
        band: Int,
        nyquistHz: Float,
        bins: Int,
    ): Float {
        val binHz = nyquistHz / bins
        val low = edges[band] * binHz
        // The band's peak is taken over the last bin as well, so its span runs
        // to the far edge of edges[band + 1].
        val high = (edges[band + 1] + 1) * binHz
        return sqrt(low * high)
    }

    /** Chladni's law for a stiff plate: mode order rises as sqrt(frequency). */
    fun wavenumberFor(
        hz: Float,
        fundamentalHz: Float,
    ): Float {
        val f0 = fundamentalHz.coerceIn(MIN_FUNDAMENTAL_HZ, MAX_FUNDAMENTAL_HZ)
        return sqrt((hz / f0).coerceAtLeast(0f))
    }

    /** Index into [MODES] of the mode that answers [wavenumber] most closely. */
    fun modeIndexFor(wavenumber: Float): Int {
        var best = 0
        var bestDelta = Float.MAX_VALUE
        for (i in WAVENUMBERS.indices) {
            val delta = abs(WAVENUMBERS[i] - wavenumber)
            // Ordered coarse -> fine, so once the gap starts growing again the
            // nearest mode is already behind us.
            if (delta > bestDelta) break
            best = i
            bestDelta = delta
        }
        return best
    }

    /** Which mode each analyzer band drives, for a given "Fundamental". */
    fun bandModeMap(
        bandCount: Int,
        fundamentalHz: Float,
        nyquistHz: Float = REFERENCE_NYQUIST_HZ,
        bins: Int = REFERENCE_FFT_BINS,
    ): IntArray {
        val edges = bandEdgeBins(bandCount, nyquistHz, bins)
        return IntArray(bandCount) { band ->
            modeIndexFor(wavenumberFor(centerHz(edges, band, nyquistHz, bins), fundamentalHz))
        }
    }

    /** One mode's displacement at plate coordinates [x], [y] in [-1, 1]. */
    fun modeHeight(
        n: Int,
        m: Int,
        x: Float,
        y: Float,
    ): Float {
        val pi = PI.toFloat()
        val nx = n * pi * x
        val my = m * pi * y
        val mx = m * pi * x
        val ny = n * pi * y
        return cos(nx) * cos(my) - cos(mx) * cos(ny)
    }

    /**
     * The plate's surface: the superposition of [count] modes packed into
     * [modes] as (n, m, amplitude) triples - the exact layout the shader's
     * `uModes[]` takes, so this doubles as the CPU mirror of the vertex pass.
     */
    fun surfaceHeight(
        modes: FloatArray,
        count: Int,
        x: Float,
        y: Float,
    ): Float {
        var h = 0f
        for (i in 0 until count) {
            val base = i * 3
            h += modes[base + 2] * modeHeight(modes[base].toInt(), modes[base + 1].toInt(), x, y)
        }
        return h
    }

    /**
     * Gradient of the superposed surface at [x], [y], written into [out] as
     * (d/dx, d/dy).
     *
     * The CPU mirror of `cymatics_plate_vert.glsl`'s `grad`, which the shader
     * uses for the surface normal: an analytic derivative rather than a
     * finite difference, so the lighting stays exact however coarse the
     * vertex grid is. Nothing at runtime calls this - the GPU does that work -
     * but a normal that silently disagrees with its own surface is invisible
     * until it looks wrong on a device, so the formula is pinned here against
     * finite differences of [modeHeight] (CompositeGrade's convention for
     * shader maths that has to be provably right).
     */
    fun surfaceGradient(
        modes: FloatArray,
        count: Int,
        x: Float,
        y: Float,
        out: FloatArray,
    ) {
        val pi = PI.toFloat()
        var gx = 0f
        var gy = 0f
        for (i in 0 until count) {
            val base = i * 3
            val n = modes[base]
            val m = modes[base + 1]
            val a = modes[base + 2]
            gx += a * pi * (m * sin(m * pi * x) * cos(n * pi * y) - n * sin(n * pi * x) * cos(m * pi * y))
            gy += a * pi * (n * cos(m * pi * x) * sin(n * pi * y) - m * cos(n * pi * x) * sin(m * pi * y))
        }
        out[0] = gx
        out[1] = gy
    }

    /** "Ring" slider (0..1) as a decay time constant in seconds. */
    fun ringSeconds(ring: Float): Float = MIN_RING_SECONDS + (MAX_RING_SECONDS - MIN_RING_SECONDS) * ring.coerceIn(0f, 1f)

    /** One-pole coefficient for a [tau]-second time constant over [dt]. */
    fun smoothing(
        dt: Float,
        tau: Float,
    ): Float = if (tau <= 0f) 1f else (1f - exp(-dt / tau)).coerceIn(0f, 1f)

    /**
     * How fast the strobed-down surface oscillates for a figure of this
     * order, in Hz. Finer figures move quicker, as they physically do, inside
     * a band chosen so the plate never flickers.
     */
    fun vibrationHz(wavenumber: Float): Float = (VIBRATION_HZ_PER_ORDER * wavenumber).coerceIn(MIN_VIBRATION_HZ, MAX_VIBRATION_HZ)

    /**
     * The factor the whole surface is displaced by this frame: every mode of a
     * driven plate oscillates together, in phase, so this multiplies the
     * height and NOT the pattern.
     *
     * That distinction is the whole reason the sand stays put: the nodal lines
     * are where the summed pattern is zero, and zero times anything is still
     * zero, so the figure is unmoved while the surface between the lines rises
     * and falls. [depth] 0 freezes the surface at full relief.
     */
    fun vibrationFactor(
        phaseRadians: Float,
        depth: Float,
    ): Float {
        val d = depth.coerceIn(0f, 1f)
        return 1f - d + d * cos(phaseRadians)
    }
}

/**
 * The resonator bank behind the CYMATICS style: which of [CymaticsMath.MODES]
 * are ringing right now, and how hard.
 *
 * A plate does not answer a spectrum instantly and forget it instantly. Each
 * mode is a resonator: it takes [CymaticsMath.ATTACK_SECONDS] to stand up and
 * decays with the "Ring" time constant afterwards, which is what turns a
 * frame-by-frame FFT - jittery, and jittery in a way a physical plate never is
 * - into a figure that holds its shape through a note and dissolves after it.
 *
 * Stateful and single-threaded by design: one instance per scene, ticked on
 * the GL thread. Pure Kotlin, so the whole progression is testable headless.
 */
class CymaticsPlate {
    /** Current amplitude of every mode, indexed like [CymaticsMath.MODES]. */
    private val amplitudes = FloatArray(CymaticsMath.MODES.size)

    /** Selection marks for [snapshot]; a field so a frame allocates nothing. */
    private val taken = BooleanArray(CymaticsMath.MODES.size)

    /** This frame's excitation per mode, refilled by every [excite]. */
    private val excitation = FloatArray(CymaticsMath.MODES.size)

    private var map: IntArray = IntArray(0)
    private var mapBandCount = -1
    private var mapFundamental = Float.NaN

    /** Scratch for the whitening mean; grown to the band count on first use. */
    private var smoothed = FloatArray(0)

    /** Phase of the strobed-down surface oscillation, in radians. */
    var vibrationPhase: Float = 0f
        private set

    /** Wavenumber of the loudest mode, 0 when the plate is silent. */
    var dominantWavenumber: Float = 0f
        private set

    /** Forgets everything ringing - a track change, or a lost GL context. */
    fun reset() {
        amplitudes.fill(0f)
        excitation.fill(0f)
        vibrationPhase = 0f
        dominantWavenumber = 0f
    }

    /**
     * Drives the plate with one frame of spectrum.
     *
     * [focus] is the "Tonal focus" control, and it decides what the plate
     * hears. At 0 it answers raw band energy, which any recording weights
     * heavily toward the bass - big, slow, coarse figures. At 1 it answers
     * only what stands out ABOVE the local spectral mean, i.e. the peaks, so
     * the figure follows the notes being played rather than the mix's tilt.
     * Whitening like this is what keeps a hi-hat or a top-octave melody able
     * to put a fine figure on the plate at all.
     */
    fun excite(
        bands: FloatArray,
        dt: Float,
        fundamentalHz: Float,
        drive: Float,
        ringSeconds: Float,
        focus: Float,
    ) {
        if (bands.isEmpty() || dt <= 0f) return
        ensureMap(bands.size, fundamentalHz)
        ensureSmoothing(bands.size)
        val f = focus.coerceIn(0f, 1f)
        localMean(bands)
        excitation.fill(0f)
        for (b in bands.indices) {
            val raw = bands[b].coerceAtLeast(0f)
            val peak = (raw - smoothed[b]).coerceAtLeast(0f) * WHITEN_GAIN
            val value = (raw * (1f - f) + peak * f) * drive
            val mode = map[b]
            // The loudest band that maps onto a mode drives it, rather than
            // their sum: several adjacent bands land on one mode in the low
            // octaves, and summing there would make the bass modes grow with
            // the FFT's resolution instead of with the music.
            if (value > excitation[mode]) excitation[mode] = value
        }
        val attack = CymaticsMath.smoothing(dt, CymaticsMath.ATTACK_SECONDS)
        val release = CymaticsMath.smoothing(dt, ringSeconds)
        var loudest = 0f
        var loudestIndex = -1
        for (i in amplitudes.indices) {
            val target = excitation[i]
            val a = amplitudes[i]
            var next = a + (target - a) * (if (target > a) attack else release)
            if (next < CymaticsMath.SILENCE) next = 0f
            amplitudes[i] = next
            if (next > loudest) {
                loudest = next
                loudestIndex = i
            }
        }
        dominantWavenumber = if (loudestIndex >= 0) CymaticsMath.MODES[loudestIndex].wavenumber else 0f
    }

    /**
     * Advances the surface oscillation and returns this frame's displacement
     * factor (see [CymaticsMath.vibrationFactor]). The rate follows the
     * loudest mode, so a bass note swells slowly and a top-octave figure
     * shimmers.
     */
    fun advanceVibration(
        dt: Float,
        depth: Float,
        speed: Float,
    ): Float {
        val hz = CymaticsMath.vibrationHz(dominantWavenumber) * speed.coerceIn(0.05f, 4f)
        vibrationPhase = (vibrationPhase + TWO_PI * hz * dt) % TWO_PI
        return CymaticsMath.vibrationFactor(vibrationPhase, depth)
    }

    /**
     * Packs the [limit] loudest modes into [out] as (n, m, amplitude) triples
     * and returns how many were written.
     *
     * Amplitudes are normalized so the summed relief is at most 1 before
     * [gain] is applied: a plate answering a full mix would otherwise stack
     * eight modes of the same displacement each and tear itself apart, and a
     * chord would be eight times taller than a single note for reasons the
     * listener cannot see.
     */
    fun snapshot(
        limit: Int,
        gain: Float,
        out: FloatArray,
    ): Int {
        val want = limit.coerceIn(1, CymaticsMath.MAX_RENDERED_MODES).coerceAtMost(out.size / 3)
        var written = 0
        var total = 0f
        // Partial selection: `want` passes over ~100 modes, allocation-free.
        taken.fill(false)
        repeat(want) {
            var bestIndex = -1
            var best = 0f
            for (i in amplitudes.indices) {
                if (taken[i]) continue
                if (amplitudes[i] > best) {
                    best = amplitudes[i]
                    bestIndex = i
                }
            }
            if (bestIndex < 0 || best <= CymaticsMath.SILENCE) return@repeat
            taken[bestIndex] = true
            val mode = CymaticsMath.MODES[bestIndex]
            val base = written * 3
            out[base] = mode.n.toFloat()
            out[base + 1] = mode.m.toFloat()
            out[base + 2] = best
            total += best
            written++
        }
        if (written == 0) return 0
        // Normalize against the summed amplitude, never below 1: quiet
        // passages must stay quiet, so this only ever scales a loud plate DOWN.
        val norm = gain / maxOf(1f, total)
        for (i in 0 until written) out[i * 3 + 2] *= norm
        return written
    }

    /** True while any mode is still ringing. */
    val ringing: Boolean
        get() = amplitudes.any { it > CymaticsMath.SILENCE }

    private fun ensureMap(
        bandCount: Int,
        fundamentalHz: Float,
    ) {
        val f0 = fundamentalHz.coerceIn(CymaticsMath.MIN_FUNDAMENTAL_HZ, CymaticsMath.MAX_FUNDAMENTAL_HZ)
        if (bandCount == mapBandCount && f0 == mapFundamental) return
        map = CymaticsMath.bandModeMap(bandCount, f0)
        mapBandCount = bandCount
        mapFundamental = f0
    }

    private fun ensureSmoothing(bandCount: Int) {
        if (smoothed.size != bandCount) smoothed = FloatArray(bandCount)
    }

    /** Mean band level over +-[CymaticsMath.WHITEN_RADIUS] bands, clamped at the edges. */
    private fun localMean(bands: FloatArray) {
        val r = CymaticsMath.WHITEN_RADIUS
        for (b in bands.indices) {
            var sum = 0f
            var n = 0
            for (k in (b - r)..(b + r)) {
                if (k < 0 || k >= bands.size) continue
                sum += bands[k].coerceAtLeast(0f)
                n++
            }
            smoothed[b] = if (n > 0) sum / n else 0f
        }
    }

    private companion object {
        const val TWO_PI = 2f * PI.toFloat()

        /**
         * Whitened peaks are small by construction - a band above its own
         * neighbourhood mean by more than ~0.3 is already a strong tonal peak
         * - so tonal focus would otherwise read as "quieter" rather than as
         * "different". Restores peak excitation to the same working range as
         * raw band energy.
         */
        const val WHITEN_GAIN = 2.6f
    }
}
