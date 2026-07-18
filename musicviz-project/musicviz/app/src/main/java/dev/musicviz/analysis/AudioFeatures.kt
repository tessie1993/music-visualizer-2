package dev.musicviz.analysis

/** Immutable snapshot of per-frame audio analysis, consumed by scenes. */
data class AudioFeatures(
    val bands: FloatArray,
    val waveform: FloatArray,
    val rms: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val onset: Float = 0f,
    val beat: Boolean = false,
    val bpm: Float = 0f,
    val centroid: Float = 0f,
) {
    companion object {
        fun empty(
            bandCount: Int = 64,
            waveformSize: Int = 128,
        ): AudioFeatures = AudioFeatures(FloatArray(bandCount), FloatArray(waveformSize))
    }
}
