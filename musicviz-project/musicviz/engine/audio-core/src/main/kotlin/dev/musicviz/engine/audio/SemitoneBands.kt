package dev.musicviz.engine.audio

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * Spectrum energy laid out on the semitone grid, with the under-resolved notes
 * marked rather than filled in.
 *
 * Music moves in semitones, so bands spaced that way track what a listener
 * hears as one note moving; arbitrary log spacing puts band edges mid-note and
 * smears a melody across neighbours. The layout idea comes from Clubber
 * (REIMPLEMENT tier); the mapping itself is the published MIDI standard,
 * `note = 12 log2(f / 8.175798915643707)`, and this is written from that.
 *
 * ## Why validity, not interpolation
 *
 * A note's span is about `0.0578 f` wide, so it narrows as pitch falls while
 * the FFT's bins stay evenly spaced. Below [resolutionCrossoverHz] the grid
 * asks for more resolution than the transform has, and notes start coming up
 * empty. Measured over [AnalysisBranch.STACK] at 48 kHz, and locked by
 * `SemitoneBandsTest` so these stay measurements rather than claims:
 *
 * | Branch | bin | crossover | [fullyResolved] |
 * |---|---:|---:|---|
 * | transient, 512 | 93.75 Hz | 1623 Hz | 87..127, from 1244 Hz |
 * | general, 1024 | 46.88 Hz | 811 Hz | 75..127, from 622 Hz |
 * | pitch, 4096 | 11.72 Hz | 203 Hz | 51..127, from 156 Hz |
 * | harmony, 8192 | 5.86 Hz | 101 Hz | 39..127, from 78 Hz |
 *
 * Each run reaches a few notes below its own crossover, where alignment
 * happens to drop a bin inside a span the guarantee had given up on. Which is
 * the measured reason §5.3 runs a long window at all: the transient branch
 * has no bass register whatsoever.
 *
 * Below a run the notes are a patchwork, and that is the case this class is
 * built around. On the harmony branch, notes 21..38 come out
 * `.R..R..R.R.R.R.RR.` — a bass guitar's open E at 41 Hz resolves, its open A
 * at 55 Hz does not. Interpolating would draw a bass line that moves smoothly
 * through both; reporting the A as unresolved says the branch cannot see it,
 * which is true, and lets a caller reach for the longer window instead of
 * trusting a number that was invented from its neighbours.
 *
 * Clubber fills those gaps by interpolating from the notes that did get bins.
 * This does not: an unresolved note reports zero and `false` from
 * [isResolved], and [fullyResolved] names the run a caller can trust.
 * Interpolated bass would look like a working bass response while moving only
 * with its neighbours, which is the kind of confident-but-invented value the
 * validity semantics in §5.5 exist to prevent.
 */
class SemitoneBands(
    val fftSize: Int,
    val sampleRateHz: Int,
    val lowestNote: Int = LOWEST_NOTE,
    val highestNote: Int = HIGHEST_NOTE,
) {
    init {
        require(fftSize >= 2 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two, was $fftSize" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(lowestNote in 0..127 && highestNote in 0..127 && lowestNote < highestNote) {
            "notes must be an ascending pair inside 0..127, were $lowestNote..$highestNote"
        }
    }

    /** How many notes this layout covers. */
    val noteCount: Int = highestNote - lowestNote + 1

    private val binHz = sampleRateHz.toDouble() / fftSize
    private val binCount = fftSize / 2 + 1

    /** Inclusive first bin of each note and exclusive last; equal when the note got none. */
    private val binStart = IntArray(noteCount)
    private val binEnd = IntArray(noteCount)

    init {
        for (i in 0 until noteCount) {
            val note = lowestNote + i
            // A note owns the half-open span either side of its centre — the
            // equal-tempered boundary, a quartertone out. Bin k belongs to the
            // note containing its centre frequency k * binHz, so every bin
            // lands in exactly one note and none is counted twice: note i's
            // exclusive end is note i+1's inclusive start, by construction.
            val first = ceil(hzOf(note - 0.5) / binHz).toInt().coerceIn(0, binCount)
            val end = ceil(hzOf(note + 0.5) / binHz).toInt().coerceIn(first, binCount)
            binStart[i] = first
            binEnd[i] = end
        }
    }

    /** True when at least one FFT bin centre falls inside this note's span. */
    fun isResolved(index: Int): Boolean = binEnd[index] > binStart[index]

    /** Bins contributing to [index]; zero means the transform cannot separate this note. */
    fun binsIn(index: Int): Int = binEnd[index] - binStart[index]

    /** Lowest bin contributing to [index]; only meaningful when [binsIn] is positive. */
    fun firstBinOf(index: Int): Int = binStart[index]

    /**
     * Note numbers a caller can read without checking: every note in this
     * range is resolved. Empty when the transform resolves nothing.
     *
     * Notes below it are narrower than a bin, notes above it are past Nyquist,
     * and either can still be resolved by luck of alignment — [isResolved]
     * answers for one note, this answers for the run.
     */
    val fullyResolved: IntRange = computeFullyResolved()

    private fun computeFullyResolved(): IntRange {
        val last = (noteCount - 1 downTo 0).firstOrNull { isResolved(it) } ?: return IntRange.EMPTY
        var first = last
        while (first > 0 && isResolved(first - 1)) first--
        return (lowestNote + first)..(lowestNote + last)
    }

    /**
     * Strongest bin in each note's span, into [out], which must hold
     * [noteCount] entries. Unresolved notes are set to zero.
     *
     * The peak rather than the mean or the sum, because band width varies by
     * an order of magnitude across this layout: a note near the top spans
     * twenty-odd bins where one near the bottom spans one. A mean would divide
     * a single strong partial by that width and make the same melody read
     * quiet in the treble; a sum would accumulate twenty bins of noise floor
     * and make it read loud. The peak is what the note's loudest partial
     * measures, which does not depend on the layout at all — and it is what
     * `FftProcessor` already did per band, so the visual character of the
     * bars does not change under it.
     */
    fun fill(
        magnitudes: FloatArray,
        out: FloatArray,
    ) {
        require(magnitudes.size >= binCount) { "expected $binCount bins, got ${magnitudes.size}" }
        require(out.size >= noteCount) { "out holds ${out.size}, layout needs $noteCount" }
        for (i in 0 until noteCount) {
            var peak = 0f
            for (k in binStart[i] until binEnd[i]) {
                val m = magnitudes[k]
                if (m > peak) peak = m
            }
            out[i] = peak
        }
    }

    /** Centre frequency of the note at [index]. */
    fun centerHz(index: Int): Double = hzOf((lowestNote + index).toDouble())

    private fun hzOf(note: Double): Double = NOTE_ZERO_HZ * 2.0.pow(note / SEMITONES_PER_OCTAVE)

    companion object {
        /** MIDI note 0, the standard reference the whole grid hangs from. */
        const val NOTE_ZERO_HZ = 8.175798915643707

        const val SEMITONES_PER_OCTAVE = 12.0

        /** A0, the lowest note on a piano — below this is felt more than heard. */
        const val LOWEST_NOTE = 21

        /** The top of the MIDI range; 127 is 12.5 kHz, past where pitch means much. */
        const val HIGHEST_NOTE = 127

        /** MIDI note number for [hz], fractional. */
        fun noteOf(hz: Double): Double {
            require(hz > 0.0) { "hz must be positive, was $hz" }
            return SEMITONES_PER_OCTAVE * ln(hz / NOTE_ZERO_HZ) / ln(2.0)
        }

        /**
         * Above this frequency every note is resolved; below it some are not.
         *
         * A note centred at *f* spans `f (2^(1/24) - 2^(-1/24))`. Once that is
         * a full bin wide the span must contain a bin centre, because a
         * half-open interval at least one lattice step long always does — so
         * this is a guarantee above, and only a likelihood below, where
         * whether a note catches a bin comes down to alignment.
         */
        fun resolutionCrossoverHz(
            fftSize: Int,
            sampleRateHz: Int,
        ): Double {
            val binHz = sampleRateHz.toDouble() / fftSize
            val spanPerHz = 2.0.pow(0.5 / SEMITONES_PER_OCTAVE) - 2.0.pow(-0.5 / SEMITONES_PER_OCTAVE)
            return binHz / spanPerHz
        }
    }
}
