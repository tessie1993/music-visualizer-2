package dev.geode.analysis

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * A per-frame 12-bin chromagram: how much energy is sitting on each pitch
 * class right now.
 *
 * ## Why this is not [KeyDetector]
 *
 * [KeyDetector] accumulates one chromagram over an entire track and calls
 * `finish()` once, which correctly answers "what key is this song" and cannot
 * answer "what is playing right now". Every harmony-driven visual needs the
 * second question, so this keeps a decaying chromagram that is readable on any
 * frame.
 *
 * ## Why it is built from FFT magnitudes, not from the 64 bands
 *
 * The band array is logarithmic over roughly nine octaves, so one band spans
 * about 1.7 semitones - wider than the thing a chromagram is trying to
 * separate. A chromagram folded from it would put C and C sharp in the same
 * bin about half the time and would be a smoothed copy of the spectrum rather
 * than a harmonic reading. The raw magnitudes are already computed and already
 * where [KeyDetector] reads from, so this reads them too.
 *
 * ## The resolution limit, stated plainly
 *
 * At a 2048-point FFT and 48 kHz the bin width is 23.4 Hz. A semitone is 7.8 Hz
 * at C3 and 31 Hz at C5, so **fundamentals below roughly C4 are not resolved**
 * and this is not a transcription. What makes the reading work anyway is that
 * a low note puts strong harmonics into the resolved region, and its octaves
 * fold onto its own pitch class. That is the standard chromagram bargain.
 *
 * It also means the usual chromagram caveats apply and are not bugs: a note's
 * third harmonic lands a fifth away and its fifth harmonic a major third away,
 * so a single loud note lights neighbouring bins. Removing that needs harmonic
 * summation against a template, which is a different and much heavier thing.
 *
 * Pure JVM, allocation-free after construction, stateful and ordered.
 */
class Chromagram(
    private val hopRateHz: Float = 60f,
    attackSeconds: Float = ATTACK_SECONDS,
    releaseSeconds: Float = RELEASE_SECONDS,
) {
    /**
     * Energy per pitch class, index 0 = C, scaled so the largest bin is 1.
     * Normalised rather than absolute because every consumer wants the SHAPE
     * of the harmony; loudness is already available as [AudioFeatures.rms] and
     * folding it in here would make a chord look different at two volumes.
     */
    val bins: FloatArray = FloatArray(12)

    /**
     * Peak-to-median ratio mapped to 0..1: how much this frame looks like
     * pitched material rather than noise.
     *
     * A drum fill, a cymbal wash or silence produce a flat chromagram and
     * score near 0; a sustained chord scores high. A scene should hold its
     * last good reading below about 0.35 rather than following the noise -
     * which is the whole reason this is published instead of being left for
     * each consumer to re-derive.
     */
    var confidence: Float = 0f
        private set

    /** Index of the loudest pitch class, 0 = C. 0 when nothing has been fed. */
    var dominantPitchClass: Int = 0
        private set

    private val raw = DoubleArray(12)
    private val scratch = FloatArray(12)

    // Asymmetric so a chord change registers promptly but a gap between
    // strums does not blank the reading. Same shape as FeatureExtractor's
    // treble smoother, for the same reason.
    private val attack = poleFor(attackSeconds)
    private val release = poleFor(releaseSeconds)

    private fun poleFor(seconds: Float): Float = if (seconds <= 0f) 1f else 1f - exp(-1f / (seconds * hopRateHz).coerceAtLeast(1e-3f))

    /** Forgets one piece of audio; see [FeatureExtractor.reset]. */
    fun reset() {
        java.util.Arrays.fill(bins, 0f)
        java.util.Arrays.fill(raw, 0.0)
        confidence = 0f
        dominantPitchClass = 0
    }

    /**
     * Folds one FFT magnitude frame into the running chromagram.
     *
     * [magnitudes] is the half-spectrum [FftProcessor] computes; it is read,
     * never retained.
     */
    fun step(
        magnitudes: FloatArray,
        sampleRateHz: Int,
        fftSize: Int,
    ) {
        // The window is narrower at the bottom than KeyDetector's 60 Hz. That
        // detector averages a whole track, so unresolved low bins wash out;
        // here they would be a per-frame error, and every note below the floor
        // is represented by its harmonics above it anyway.
        val total = foldPeaks(magnitudes, sampleRateHz, fftSize, MIN_HZ, MAX_HZ, raw)

        if (total <= SILENCE) {
            // Silence must not renormalise noise up to a full-scale chord.
            // Decay toward zero instead and report no confidence.
            for (i in 0 until 12) bins[i] += (0f - bins[i]) * release
            confidence = 0f
            return
        }

        var peak = 0.0
        for (i in 0 until 12) peak = maxOf(peak, raw[i])
        for (i in 0 until 12) {
            val target = (raw[i] / peak).toFloat()
            val k = if (target > bins[i]) attack else release
            bins[i] += (target - bins[i]) * k
        }

        var best = 0
        for (i in 1 until 12) if (bins[i] > bins[best]) best = i
        dominantPitchClass = best
        confidence = peakToMedian()
    }

    /**
     * Peak over median of the SMOOTHED bins, squashed to 0..1.
     *
     * Median rather than mean: three of twelve bins are legitimately loud in a
     * triad, and a mean is dragged up by exactly the peaks that are supposed
     * to stand out from it.
     */
    private fun peakToMedian(): Float {
        System.arraycopy(bins, 0, scratch, 0, 12)
        java.util.Arrays.sort(scratch)
        val median = (scratch[5] + scratch[6]) * 0.5f
        val peak = scratch[11]
        if (peak <= 1e-6f) return 0f
        if (median <= 1e-6f) return 1f
        val ratio = peak / median
        return ((ratio - 1f) / (CONFIDENT_RATIO - 1f)).coerceIn(0f, 1f)
    }

    /**
     * The [n] loudest pitch classes, loudest first, written into [out] and
     * returning how many were written. Allocation-free; [out] must hold [n].
     */
    fun top(
        n: Int,
        out: IntArray,
    ): Int {
        val count = n.coerceIn(0, 12)
        System.arraycopy(bins, 0, scratch, 0, 12)
        for (slot in 0 until count) {
            var best = 0
            for (i in 1 until 12) if (scratch[i] > scratch[best]) best = i
            out[slot] = best
            scratch[best] = -1f
        }
        return count
    }

    companion object {
        /** 0 = C, matching [KeyDetector]'s naming. */
        val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /**
         * Folds a magnitude spectrum's LOCAL PEAKS into pitch classes,
         * writing per-class energy into [out] (cleared first) and returning
         * the total that was folded.
         *
         * Peaks, not every bin, because the window's mainlobe is wider than
         * a semitone through the middle register — at a 1024-point FFT and
         * 22.05 kHz a single C4 puts real energy on bins whose CENTRES round
         * to A#, C, C# and D, and folding all of them credited a third of
         * every note to classes nobody played. The corpus caught both
         * symptoms: a clean triad scored 0.19 confidence, and the fake F
         * that E4's mainlobe painted turned A minor into F major. A local
         * maximum, parabolically refined between its neighbours, is one
         * vote at (approximately) the true frequency instead.
         *
         * [PEAK_FLOOR] keeps sidelobes out: Hann's first sidelobe is -31 dB
         * (0.028 of the peak), under the floor, so a sine's sidelobes do
         * not vote. Broadband noise still produces peaks everywhere, which
         * is exactly the flat, unconfident chromagram it should.
         */
        fun foldPeaks(
            magnitudes: FloatArray,
            sampleRateHz: Int,
            fftSize: Int,
            minHz: Float,
            maxHz: Float,
            out: DoubleArray,
        ): Double {
            java.util.Arrays.fill(out, 0.0)
            val minBin = ceil(minHz * fftSize / sampleRateHz).toInt().coerceAtLeast(1)
            val maxBin = (maxHz * fftSize / sampleRateHz).toInt().coerceAtMost(magnitudes.size - 2)
            var frameMax = 0f
            for (k in minBin..maxBin) if (magnitudes[k] > frameMax) frameMax = magnitudes[k]
            if (frameMax <= 0f) return 0.0
            val floor = frameMax * PEAK_FLOOR
            var total = 0.0
            for (k in minBin..maxBin) {
                val mid = magnitudes[k]
                if (mid < floor || mid <= magnitudes[k - 1] || mid < magnitudes[k + 1]) continue
                val left = magnitudes[k - 1].toDouble()
                val right = magnitudes[k + 1].toDouble()
                val denominator = left - 2.0 * mid + right
                val offset =
                    if (denominator < -1e-12) {
                        (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
                    } else {
                        0.0
                    }
                val f = (k + offset) * sampleRateHz.toDouble() / fftSize
                val midi = 69.0 + 12.0 * log2(f / 440.0)
                val pc = ((midi.roundToInt() % 12) + 12) % 12
                out[pc] += mid.toDouble()
                total += mid.toDouble()
            }
            return total
        }

        /** Fraction of the frame's loudest in-range bin a peak must reach. */
        const val PEAK_FLOOR = 0.05f

        /**
         * Bottom of the analysis window. Below about C4 a 2048-point FFT at
         * 48 kHz cannot separate adjacent semitones, and an unresolved bin is
         * a per-frame error rather than something that averages out.
         */
        const val MIN_HZ = 200f

        /** Above this is mostly cymbal and noise, which flattens the reading. */
        const val MAX_HZ = 5_000f

        /** Fast enough that a chord change reads on the frame after it. */
        const val ATTACK_SECONDS = 0.06f

        /** Slow enough to hold through the gap between two strums. */
        const val RELEASE_SECONDS = 0.35f

        /** Peak/median at which a frame is called fully pitched. */
        const val CONFIDENT_RATIO = 6f

        /** Total magnitude below which a frame carries no pitch information. */
        const val SILENCE = 1e-7
    }
}
