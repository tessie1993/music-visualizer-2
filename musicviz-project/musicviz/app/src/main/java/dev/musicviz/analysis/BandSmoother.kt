package dev.musicviz.analysis

/**
 * Per-band asymmetric exponential smoothing: fast attack, slow decay.
 * This is the user-tunable "reactivity" layer.
 */
class BandSmoother(
    private val bandCount: Int,
    var attack: Float = 0.6f,
    var decay: Float = 0.12f,
) {
    private val state = FloatArray(bandCount)

    fun apply(
        raw: FloatArray,
        out: FloatArray,
    ) {
        for (i in 0 until bandCount) {
            val a = if (raw[i] > state[i]) attack else decay
            state[i] += (raw[i] - state[i]) * a
            out[i] = state[i]
        }
    }
}
