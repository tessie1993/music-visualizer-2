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
    // Track-position context for progression-driven scenes (fluid spawn/catch
    // choreography): 0 when unknown, so every existing call site keeps its
    // meaning and scenes degrade to a static-progress layout.
    /** Playback position as a fraction of track duration, 0..1. */
    val progress: Float = 0f,
    /** Index of the current detected section (0 until analysis knows more). */
    val sectionIndex: Int = 0,
    /** Total detected sections for the track (0 = no offline analysis). */
    val sectionCount: Int = 0,
) {
    companion object {
        fun empty(
            bandCount: Int = 64,
            waveformSize: Int = 128,
        ): AudioFeatures = AudioFeatures(FloatArray(bandCount), FloatArray(waveformSize))
    }
}
