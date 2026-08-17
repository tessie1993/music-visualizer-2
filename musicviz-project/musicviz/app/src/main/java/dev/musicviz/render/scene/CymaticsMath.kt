package dev.musicviz.render.scene

import dev.musicviz.engine.audio.LogBands
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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
 * formula below has a twin in `cymatics_field_frag.glsl`; if one changes, the
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
    /** Lowest band edge the analyzer resolves; mirrors [LogBands.DEFAULT_MIN_HZ]. */
    const val MIN_BAND_HZ: Float = LogBands.DEFAULT_MIN_HZ

    /** Highest band edge the analyzer resolves; mirrors [LogBands.DEFAULT_MAX_HZ]. */
    const val MAX_BAND_HZ: Float = LogBands.DEFAULT_MAX_HZ

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
     * Amplitude scale of [besselApprox]. The asymptotic envelope is
     * sqrt(2/(pi x)) ~ 0.8 / sqrt(x); written as `1 / sqrt(1 + 2x)` (finite at
     * the centre, where the real envelope is not) that is short by about this
     * factor over the range a dish actually shows.
     */
    const val BESSEL_GAIN: Float = 1.7f

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
     * Centre frequency of band [band] of [bandCount]: the geometric mean of
     * the frequency span that band covers.
     *
     * The analyzer's band edges are pure log spacing between [MIN_BAND_HZ] and
     * [MAX_BAND_HZ], so this is a closed form. It used to mirror the old
     * analyzer's *bin* edges instead, complete with a "bump any repeated edge
     * up one bin" pass, because that analyzer quantized its band edges to FFT
     * bins and the bottom twenty bands all landed on the same one — the map
     * had to reproduce the quantization or a bass note drove the wrong mode.
     * The bands are no longer quantized that way, so neither is this, and the
     * reference-Nyquist and reference-bin-count constants that existed only to
     * feed the quantization are gone with it.
     */
    fun bandCenterHz(
        band: Int,
        bandCount: Int,
    ): Float {
        if (bandCount <= 0) return MIN_BAND_HZ
        val ratio = ln(MAX_BAND_HZ / MIN_BAND_HZ)
        val low = MIN_BAND_HZ * exp(ratio * band / bandCount).toFloat()
        val high = MIN_BAND_HZ * exp(ratio * (band + 1) / bandCount).toFloat()
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
    ): IntArray =
        IntArray(bandCount) { band ->
            modeIndexFor(wavenumberFor(bandCenterHz(band, bandCount), fundamentalHz))
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
     * Angular order of [mode] when the field is read as a circular dish: how
     * many petals the figure has around the centre.
     *
     * The mode table is enumerated once, as square-plate (n, m) pairs, and
     * BOTH geometries read those two orders - the dish as (angular, radial),
     * the plate as its own (n, m). One table means one resonator bank and one
     * pitch -> figure law however the field is drawn, rather than two that can
     * disagree about what the music is doing.
     */
    fun angularOrder(mode: Mode): Int = mode.m

    /** Radial order of [mode] on a dish: how many rings out from the centre. */
    fun radialOrder(mode: Mode): Int = maxOf(mode.n - mode.m, 1)

    /**
     * Where the [radialOrder]-th zero of J_[angularOrder] falls - McMahon's
     * expansion, `pi * (s + m/2 - 1/4)` - which is the radial wavenumber the
     * dish rings at. Twin of `cymatics_field_frag.glsl`'s `beta`.
     */
    fun dishBeta(mode: Mode): Float = PI.toFloat() * (radialOrder(mode) + 0.5f * angularOrder(mode) - 0.25f)

    /**
     * Bessel J_m, cheap: the asymptotic form - amplitude ~ sqrt(2/(pi x)),
     * zeros a half period apart - with a core factor so an angular order above
     * 0 vanishes at the centre of the dish as the real function does, and J_0
     * peaks there as IT does.
     *
     * Twin of the shader's `besselApprox`. Exact enough for the visual claim
     * that matters (rings land where the real function's zeros are);
     * `CymaticsMathTest` pins it against a series expansion of the real J_m.
     *
     * [phase] is the travelling-wave offset behind the "Flow" control: a
     * standing wave at 0, rings marching outward above it. It rides the
     * oscillating factor rather than the radius, because shifting the radius
     * takes the argument negative near the centre - where the amplitude term
     * is not defined, which showed up as a black hole punched through the
     * middle of the dish.
     */
    fun besselApprox(
        m: Float,
        x: Float,
        phase: Float = 0f,
    ): Float {
        val ax = abs(x)
        val pi = PI.toFloat()
        val core = if (m < 0.5f) 1f else ax * ax / (ax * ax + 0.45f * m * m + 0.05f)
        val w = x - m * pi * 0.5f - pi * 0.25f - phase
        // Two terms of Hankel's expansion, not one: the leading cosine alone
        // puts the INNERMOST rings badly out (J_4's first zero at 7.85 instead
        // of 7.59), and those are the rings filling the middle of the screen.
        // Clamped only to bound the terms near the centre, where they diverge
        // and the core factor above owns the shape anyway.
        val inv = 1f / (8f * maxOf(ax, 0.75f))
        val mu = 4f * m * m
        val c1 = ((mu - 1f) * inv).coerceIn(-3f, 3f)
        val c0 = (1f - (mu - 1f) * (mu - 9f) * inv * inv * 0.5f).coerceIn(-3f, 3f)
        return (c0 * cos(w) - c1 * sin(w)) / sqrt(1f + 2f * ax) * core * BESSEL_GAIN
    }

    /**
     * The dish's displacement at plate coordinates [x], [y]: the superposition
     * of [count] modes packed into [modes] as (n, m, amplitude, phase) quads -
     * the shader's `uModes[]` layout. CPU mirror of the circular half of
     * `field()`.
     */
    fun dishHeight(
        modes: FloatArray,
        count: Int,
        x: Float,
        y: Float,
        travel: Float = 0f,
    ): Float {
        val r = sqrt(x * x + y * y)
        val a = atan2(y, x)
        var h = 0f
        for (i in 0 until count) {
            val base = i * 4
            val mode = Mode(modes[base].toInt(), modes[base + 1].toInt())
            val ang = angularOrder(mode).toFloat()
            val beta = dishBeta(mode)
            h += modes[base + 2] * besselApprox(ang, beta * r, travel) * cos(ang * a + modes[base + 3])
        }
        return h
    }

    /** "Ring" slider (0..1) as a decay time constant in seconds. */
    fun ringSeconds(ring: Float): Float = MIN_RING_SECONDS + (MAX_RING_SECONDS - MIN_RING_SECONDS) * ring.coerceIn(0f, 1f)

    /** One-pole coefficient for a [tau]-second time constant over [dt]. */
    fun smoothing(
        dt: Float,
        tau: Float,
    ): Float = if (tau <= 0f) 1f else (1f - exp(-dt / tau)).coerceIn(0f, 1f)

    /**
     * How fast a figure of this order MOVES, in Hz: the rate its phase
     * advances, so finer figures shimmer and coarse ones swell, and the field
     * is never still while something is playing.
     *
     * A real plate vibrates at the frequency it is driven at - hundreds of
     * hertz, invisible, and inside the band the app's visual-safety work
     * exists to stay out of. This is that motion strobed down into a band
     * capped well under the WCAG three flashes per second.
     */
    fun vibrationHz(wavenumber: Float): Float = (VIBRATION_HZ_PER_ORDER * wavenumber).coerceIn(MIN_VIBRATION_HZ, MAX_VIBRATION_HZ)

    /** "Audio drive" slider ceiling; the floor is 0 (a plate cannot un-ring). */
    const val MAX_DRIVE: Float = 4f

    /**
     * Summed rendered amplitude at which the shader's additive layers reach
     * full strength (`uFieldLive` = 1). Below it the picture fades toward
     * black instead of degenerating into the flat-field wash.
     */
    const val LIVE_AMPLITUDE: Float = 0.02f

    /** Modes the Standing Chamber's room-mode recomposition superposes. */
    const val ROOM_MODES: Int = 4

    /**
     * The "Audio drive" value the plate is actually driven with.
     *
     * SceneParams carries raw doubles from presets and preset links, so the
     * slider can arrive negative, absurd or NaN - and NaN slips straight
     * through `coerceIn` (both comparisons are false), then poisons every
     * mode amplitude it multiplies. A non-finite drive means "no credible
     * drive", i.e. zero; everything else is clamped to the slider's range.
     */
    fun safeDrive(raw: Float): Float = if (raw.isFinite()) raw.coerceIn(0f, MAX_DRIVE) else 0f

    /**
     * `uFieldLive`: how alive the field is, 0 silent .. 1 driven, from the
     * summed amplitude of the modes actually rendered. The shader multiplies
     * every additive layer that is not already closed on a flat field by
     * this, so "nothing ringing" renders near black - the flat-field wash
     * fix's scene half (the shader's own half is the fwidth gate).
     */
    fun fieldLiveness(totalAmplitude: Float): Float =
        if (totalAmplitude.isFinite()) (totalAmplitude / LIVE_AMPLITUDE).coerceIn(0f, 1f) else 0f

    /**
     * Floored modulo into [0, period): the wrap every phase accumulator in
     * [CymaticsScene] goes through. Handles negative rates (Swirl runs both
     * ways), and a non-finite input resets to 0 rather than sticking NaN
     * into a uniform forever.
     */
    fun wrapPhase(
        value: Float,
        period: Float,
    ): Float {
        if (!value.isFinite() || period <= 0f) return 0f
        val r = value % period
        return if (r < 0f) r + period else r
    }

    /**
     * One smoothing step of a CIRCULAR hue (0..1 wraps) toward [target]: the
     * pitch-class -> hue coupling has to go the short way round the wheel,
     * or a B -> C change would sweep the palette through eleven semitones.
     */
    fun approachHue(
        current: Float,
        target: Float,
        alpha: Float,
    ): Float {
        var d = (target - current) % 1f
        if (d > 0.5f) d -= 1f
        if (d < -0.5f) d += 1f
        val next = current + d * alpha.coerceIn(0f, 1f)
        return next - kotlin.math.floor(next)
    }

    /**
     * Three plane waves at 120 degrees - the interference lattice a
     * ferrofluid's spike array relaxes into. 1.0 exactly at the spike sites,
     * smooth everywhere. Twin of the shader's `hexLattice`.
     */
    fun hexLattice(
        x: Float,
        y: Float,
    ): Float {
        val s3 = 0.8660254f
        return (cos(x) + cos(-0.5f * x + s3 * y) + cos(-0.5f * x - s3 * y)) / 3f
    }

    /**
     * The Standing Chamber's recomposition: PRODUCT room modes,
     * `cos(n pi x) cos(m pi y)` summed over the first [ROOM_MODES] rendered
     * modes - rectangular pressure cells, not the plate's difference-formula
     * filigree. Twin of the shader's `uStyle == 8` loop; [drift] is the
     * scrolled x offset, 2-periodic exactly as every cosine term is.
     */
    fun roomModeHeight(
        modes: FloatArray,
        count: Int,
        x: Float,
        y: Float,
        drift: Float = 0f,
    ): Float {
        val pi = PI.toFloat()
        var h = 0f
        for (i in 0 until minOf(count, ROOM_MODES)) {
            val base = i * 4
            h += modes[base + 2] *
                cos(modes[base] * pi * (x + drift)) *
                cos(modes[base + 1] * pi * y) *
                cos(modes[base + 3])
        }
        return h
    }

    /**
     * The gradient half of the shader's flat-field gate: how much nodal/halo
     * light a pixel with this `fwidth(h)` may emit. Twin of `lineLive`'s
     * smoothstep - a field with no slope under the pixel gets no line light,
     * which is what keeps the degenerate flat field black instead of letting
     * the 1e-5 width floor turn it into a full-screen wash.
     */
    fun nodalGate(fwidthH: Float): Float = smoothstepf(2.0e-5f, 1.2e-4f, fwidthH)

    private fun smoothstepf(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The Faraday substyle's droplet bank: up to [SLOTS] beat-spawned impact
 * rings, `A * sin(k*d - phase) * exp(-decay*d)`, fed to the shader as
 * `uDrops[]` quads of (x, y, phase, amplitude).
 *
 * Pure Kotlin and allocation-free per frame, like [CymaticsPlate]: spawning
 * picks slots round-robin (a seventh drop replaces the oldest), each drop's
 * phase advances at [OMEGA] and wraps at 2*pi (the shader reads it inside a
 * sine, so the wrap is exact), and its amplitude decays to zero - every
 * accumulator here is bounded by construction.
 */
class CymaticsDrops {
    companion object {
        /** Drop slots; must match the shader's `uDrops[6]`. */
        const val SLOTS = 6

        /** Ring oscillation rate, rad/s - well under the flashing band. */
        const val OMEGA = 2.4f

        /** Amplitude decay time constant, seconds. */
        const val DECAY_SECONDS = 1.4f

        /** Beat impulse below this spawns nothing (transients stay texture). */
        const val SPAWN_THRESHOLD = 0.3f

        /** Refractory period, so a drum roll reads as drops, not as foam. */
        const val COOLDOWN_SECONDS = 0.12f

        /** Amplitude floor below which a slot is reclaimed as silent. */
        const val SILENCE = 0.004f

        /** Field-unit half-range the drops land inside. */
        const val SPREAD = 1.1f

        private const val TWO_PI = 2f * PI.toFloat()
    }

    /** (x, y, phase, amplitude) per slot - the shader's `uDrops[]` layout. */
    val packed = FloatArray(SLOTS * 4)

    private var next = 0
    private var cooldown = 0f
    private var seed = 0

    /** Ticks every ringing drop and spawns one on a strong enough beat. */
    fun update(
        dt: Float,
        beatImpulse: Float,
    ) {
        if (dt <= 0f) return
        cooldown = (cooldown - dt).coerceAtLeast(0f)
        val fade = exp(-dt / DECAY_SECONDS)
        for (i in 0 until SLOTS) {
            val base = i * 4
            if (packed[base + 3] <= 0f) continue
            packed[base + 2] = CymaticsMath.wrapPhase(packed[base + 2] + OMEGA * dt, TWO_PI)
            packed[base + 3] *= fade
            if (packed[base + 3] < SILENCE) packed[base + 3] = 0f
        }
        if (beatImpulse > SPAWN_THRESHOLD && cooldown <= 0f) {
            spawn(beatImpulse)
            cooldown = COOLDOWN_SECONDS
        }
    }

    /** True while any slot still rings (for tests and idle bookkeeping). */
    val ringing: Boolean
        get() {
            for (i in 0 until SLOTS) {
                if (packed[i * 4 + 3] > 0f) return true
            }
            return false
        }

    /** Forgets every drop - a track change or a lost GL context. */
    fun reset() {
        packed.fill(0f)
        cooldown = 0f
    }

    private fun spawn(strength: Float) {
        val base = next * 4
        next = (next + 1) % SLOTS
        seed++
        packed[base] = (hash(seed) - 0.5f) * 2f * SPREAD
        packed[base + 1] = (hash(seed * 7 + 3) - 0.5f) * 2f * SPREAD
        packed[base + 2] = 0f
        packed[base + 3] = 0.22f + 0.33f * strength.coerceIn(0f, 1.5f)
    }

    /** Deterministic scatter; the classic fract(sin) hash, seeded by count. */
    private fun hash(n: Int): Float {
        val x = sin(n * 12.9898f) * 43758.547f
        return x - kotlin.math.floor(x)
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

    /**
     * Phase of every mode, in radians.
     *
     * Per mode rather than one global clock, and kept across frames rather
     * than recomputed from elapsed time: a mode that drops out of the rendered
     * set and comes back has to return where it would have been, or the figure
     * jumps every time the loudest handful changes.
     */
    private val phases = FloatArray(CymaticsMath.MODES.size)

    /** Wavenumber of the loudest mode, 0 when nothing is ringing. */
    var dominantWavenumber: Float = 0f
        private set

    /** Forgets everything ringing - a track change, or a lost GL context. */
    fun reset() {
        amplitudes.fill(0f)
        excitation.fill(0f)
        phases.fill(0f)
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
     * Advances every mode's phase by [dt].
     *
     * Each runs at its own [CymaticsMath.vibrationHz] - finer figures shimmer,
     * coarse ones swell - which is what keeps the field alive between changes
     * in the music instead of freezing into a still image whenever a note is
     * held. Advancing ALL of them, not only the rendered ones, is what makes
     * the set of loudest modes safe to change from frame to frame.
     */
    fun advancePhases(
        dt: Float,
        speed: Float,
    ) {
        if (dt <= 0f) return
        val rate = speed.coerceIn(0.05f, 4f) * TWO_PI * dt
        for (i in phases.indices) {
            if (amplitudes[i] <= CymaticsMath.SILENCE) continue
            phases[i] = (phases[i] + CymaticsMath.vibrationHz(CymaticsMath.MODES[i].wavenumber) * rate) % TWO_PI
        }
    }

    /**
     * Packs the [limit] loudest modes into [out] as (n, m, amplitude, phase)
     * quads - the shader's `uModes[]` layout - and returns how many were
     * written, loudest first.
     *
     * Amplitudes are normalized so their sum is at most 1: a field answering a
     * full mix would otherwise stack eight modes of full displacement each and
     * blow out, and a chord would read eight times louder than a single note
     * for reasons the listener cannot see. Only ever scales DOWN, so quiet
     * passages stay quiet.
     */
    fun snapshot(
        limit: Int,
        out: FloatArray,
    ): Int {
        val want = limit.coerceIn(1, CymaticsMath.MAX_RENDERED_MODES).coerceAtMost(out.size / 4)
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
            val base = written * 4
            out[base] = mode.n.toFloat()
            out[base + 1] = mode.m.toFloat()
            out[base + 2] = best
            out[base + 3] = phases[bestIndex]
            total += best
            written++
        }
        if (written == 0) return 0
        val norm = 1f / maxOf(1f, total)
        for (i in 0 until written) out[i * 4 + 2] *= norm
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
