package dev.geode.engine.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mel-frequency cepstral coefficients — timbre as a handful of numbers.
 *
 * The classic Davis & Mermelstein pipeline over a [MelBank] frame:
 * `10 log10(power)` with the corpus oracle's 1e-10 floor and no whole-track
 * normalization (a causal engine cannot see the track's maximum), then an
 * orthonormal DCT-II keeping the first [count] coefficients. c0 is level;
 * c1 up are spectral shape, which is why [timbreFlux] — the L2 distance
 * between successive frames — deliberately excludes c0: level change is
 * already measured, twice, elsewhere.
 *
 * [delta] is the plain causal frame difference, not the Savitzky-Golay
 * smoothing offline toolkits use — that filter needs future frames.
 *
 * Deterministic and ordered: [compute] must see every frame, in order.
 * Allocates nothing per frame.
 */
class Mfcc(
    private val melCount: Int,
    val count: Int = 13,
) {
    init {
        require(count in 1..melCount) { "need 1..$melCount coefficients, got $count" }
    }

    /** DCT-II basis, orthonormal: row c, column n over the mel axis. */
    private val basis =
        Array(count) { c ->
            val scale = if (c == 0) sqrt(1.0 / melCount) else sqrt(2.0 / melCount)
            DoubleArray(melCount) { n -> scale * cos(PI * c * (2 * n + 1) / (2.0 * melCount)) }
        }

    private val logMel = DoubleArray(melCount)
    private val previous = FloatArray(count)
    private var hasPrevious = false

    /** The cepstrum of the last [compute] frame; c0 first. */
    val coefficients: FloatArray = FloatArray(count)

    /** Frame difference of [coefficients]; zeros on the first frame. */
    val delta: FloatArray = FloatArray(count)

    /** L2 distance from the previous frame over c1..c[count]; 0 on the first. */
    var timbreFlux: Float = 0f
        private set

    /** Feeds one [MelBank.power] frame of [melCount] values. */
    fun compute(melPower: FloatArray) {
        require(melPower.size == melCount) { "expected $melCount mels, got ${melPower.size}" }
        for (m in 0 until melCount) {
            logMel[m] = 10.0 * log10(max(melPower[m].toDouble(), LOG_POWER_FLOOR))
        }
        for (c in 0 until count) {
            val row = basis[c]
            var acc = 0.0
            for (m in 0 until melCount) acc += row[m] * logMel[m]
            coefficients[c] = acc.toFloat()
        }
        if (hasPrevious) {
            var sq = 0.0
            for (c in 0 until count) {
                val d = coefficients[c] - previous[c]
                delta[c] = d
                if (c > 0) sq += d.toDouble() * d
            }
            timbreFlux = sqrt(sq).toFloat()
        } else {
            delta.fill(0f)
            timbreFlux = 0f
            hasPrevious = true
        }
        coefficients.copyInto(previous)
    }

    /** Forgets the previous frame; call on a track change or a seek. */
    fun reset() {
        hasPrevious = false
        previous.fill(0f)
        coefficients.fill(0f)
        delta.fill(0f)
        timbreFlux = 0f
    }

    companion object {
        /** The oracle's floor: below this, power is silence, not signal. */
        const val LOG_POWER_FLOOR: Double = 1e-10
    }
}
