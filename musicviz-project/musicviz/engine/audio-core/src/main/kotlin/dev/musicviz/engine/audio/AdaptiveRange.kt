package dev.musicviz.engine.audio

import kotlin.math.exp
import kotlin.math.max

/**
 * Per-band causal AGC: maps a dB level onto 0..1 against a floor and ceiling
 * the band learns from its own recent history.
 *
 * This is the node that makes the engine audio-*reactive*. Everything upstream
 * measures the signal in absolute units; a visual needs to know where this
 * moment sits inside *this* music's dynamics, which is a different question and
 * the only one whose answer is loudness-independent. MASTER_PLAN §5.5 specifies
 * it as "adaptive live: causal robust floor/ceiling with attack/release and
 * session reset".
 *
 * ## What it replaces, and why the old shape could not work
 *
 * The legacy `FftProcessor` mapped band dB onto 0..1 against a **fixed** -72 dB
 * floor: `(db + 72) / 72`. Measured over pink noise at a normal master level
 * that put the bass/mid/treble drivers at 0.076/0.020/0.066 — every scene
 * multiplying by them ran at a twentieth of the amplitude it was written for,
 * and `NebulaScene`'s `bass * audioDrive > 0.55f` burst could not fire at all.
 * At -30 dBFS the mid and treble drivers were identically **zero**. An absolute
 * scale cannot be fixed by retuning its constants: a quiet passage and a quiet
 * master are indistinguishable to it, and only one of them should be still.
 *
 * ## The model
 *
 * Two followers per band, in the dB domain:
 *
 * - the **floor** falls quickly to catch a passage getting quieter and rises
 *   slowly, so it settles on the band's quiet level rather than its average;
 * - the **ceiling** rises quickly to catch a hit and falls slowly, so a bar
 *   without a peak does not immediately inflate everything after it.
 *
 * The output is where the input sits between them. This is the same idea as
 * MilkDrop's `imm / longAvg` (projectM `Loudness.cpp`, Butterchurn
 * `audioLevels.js`) and Clubber's adaptive mode, in the form §5.5 asks for:
 * a window with an explicit floor and ceiling rather than a bare ratio, so the
 * output is bounded and uses its whole range by construction.
 *
 * ## The two failure modes it is built to avoid
 *
 * **Amplified silence.** A band with no dynamics has a floor and ceiling that
 * converge, and dividing by a vanishing span turns dither into a strobe.
 * [minSpanDb] is the guard: a band quieter than about 15 dB peak-to-peak simply
 * reads low, which is what a dead channel should look like.
 *
 * **Re-learning after a gap.** Below [SILENCE_DB] adaptation *freezes* rather
 * than adapting toward silence, so the quiet bar before a drop does not hand
 * the drop a floor and ceiling learned from near-nothing. Session boundaries
 * are [reset]'s job, and nothing else's.
 *
 * Holds two floats per band. Allocates nothing per frame; one instance per
 * worker, like every other node here.
 */
class AdaptiveRange(
    val bandCount: Int,
    private val floorRiseSeconds: Float = 6f,
    private val floorFallSeconds: Float = 0.5f,
    private val ceilingRiseSeconds: Float = 0.15f,
    private val ceilingFallSeconds: Float = 2.5f,
    /** Narrowest window the normalizer will divide by; see the class doc. */
    private val minSpanDb: Float = 15f,
    private val warmupSeconds: Float = 1.5f,
) {
    init {
        require(bandCount > 0) { "bandCount must be positive, was $bandCount" }
        require(minSpanDb > 0f) { "minSpanDb must be positive, was $minSpanDb" }
        require(warmupSeconds > 0f) { "warmupSeconds must be positive, was $warmupSeconds" }
    }

    private val floorDb = FloatArray(bandCount)
    private val ceilingDb = FloatArray(bandCount)
    private var primed = false
    private var adaptedSeconds = 0f

    /**
     * How far the window has opened, 0..1 — 1 once the range has seen
     * [warmupSeconds] of non-silent audio.
     *
     * A consumer should fade its reaction in over this rather than trusting the
     * first frames after a track change, where the floor and ceiling are still
     * a guess centred on whatever the first frame happened to be.
     */
    val warmup: Float get() = (adaptedSeconds / warmupSeconds).coerceIn(0f, 1f)

    /**
     * Normalizes [inputDb] into [out], both of length [bandCount].
     *
     * Bands at or below [SILENCE_DB] read 0 and do not adapt.
     */
    fun normalize(
        inputDb: FloatArray,
        dtSeconds: Float,
        out: FloatArray,
    ) {
        require(inputDb.size == bandCount) { "expected $bandCount bands, got ${inputDb.size}" }
        require(out.size == bandCount) { "expected $bandCount outputs, got ${out.size}" }

        if (!primed) {
            // A fresh window centred on the first frame, half [minSpanDb] wide,
            // so the very first output is 0.5 rather than a full-scale flash
            // inherited from wherever the previous track left off.
            for (b in 0 until bandCount) {
                val x = inputDb[b]
                floorDb[b] = x - minSpanDb * 0.5f
                ceilingDb[b] = x + minSpanDb * 0.5f
            }
            primed = true
        }

        var adapted = false
        for (b in 0 until bandCount) {
            val x = inputDb[b]
            if (x <= SILENCE_DB) {
                out[b] = 0f
                continue
            }
            adapted = true
            floorDb[b] = follow(floorDb[b], x, if (x > floorDb[b]) floorRiseSeconds else floorFallSeconds, dtSeconds)
            ceilingDb[b] =
                follow(ceilingDb[b], x, if (x > ceilingDb[b]) ceilingRiseSeconds else ceilingFallSeconds, dtSeconds)
            val span = max(ceilingDb[b] - floorDb[b], minSpanDb)
            out[b] = ((x - floorDb[b]) / span).coerceIn(0f, 1f)
        }
        if (adapted && dtSeconds > 0f) adaptedSeconds += dtSeconds
    }

    /**
     * Forgets every learned floor and ceiling. Call on a track change or a
     * seek: a new piece of music judged against the previous one's dynamics is
     * judged wrong, and the wrongness lasts as long as [floorRiseSeconds].
     */
    fun reset() {
        floorDb.fill(0f)
        ceilingDb.fill(0f)
        primed = false
        adaptedSeconds = 0f
    }

    private fun follow(
        current: Float,
        target: Float,
        tauSeconds: Float,
        dtSeconds: Float,
    ): Float {
        if (dtSeconds <= 0f) return current
        if (tauSeconds <= 0f) return target
        val k = (1f - exp(-dtSeconds / tauSeconds)).coerceIn(0f, 1f)
        return current + (target - current) * k
    }

    companion object {
        /**
         * Absolute level below which a band is silent: it reads 0 and stops
         * adapting.
         *
         * An absolute constant is right *here* and wrong for the reaction curve
         * above it. Digital silence is a fact about the signal, not about the
         * music's dynamics, and something has to stop the normalizer from
         * learning a window inside the noise floor of a fade-out.
         */
        const val SILENCE_DB: Float = -90f
    }
}
