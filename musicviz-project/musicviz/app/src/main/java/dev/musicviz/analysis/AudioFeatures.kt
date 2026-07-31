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
    /** Playback position as a fraction of track duration, 0..1. Together with
     *  sectionIndex/sectionCount this is the track-position context for
     *  progression-driven scenes (fluid spawn/catch choreography): 0 when
     *  unknown, so every existing call site keeps its meaning and scenes
     *  degrade to a static-progress layout. */
    val progress: Float = 0f,
    /** Index of the current detected section (0 until analysis knows more). */
    val sectionIndex: Int = 0,
    /** Total detected sections for the track (0 = no offline analysis). */
    val sectionCount: Int = 0,
    /** Raw weighted spectral-flux onset strength for this frame - the quantity
     *  [FeatureExtractor.BeatGate] thresholds to produce [beat]. Carried through
     *  the timeline and the on-disk analysis cache so the beat decision can be
     *  re-made later at whatever sensitivity the user has set, without
     *  re-analysing the track. 0 for live scene fallbacks that never ran a
     *  flux calculation. */
    val flux: Float = 0f,
    /** Graded weight of this frame's beat from [PulseTracker], 0..1: how hard
     *  the hit was relative to the track's own dynamics. 0 between beats and
     *  on features that predate the tracker - consumers should read
     *  [beatImpulse], which folds that legacy case back to full strength. */
    val beatStrength: Float = 0f,
    /** Position within the tracked beat interval, 0 (on the beat) rising to 1
     *  just before the next - a continuous ramp scenes can ease against, so
     *  motion can anticipate and land on beats instead of only reacting to
     *  them. 0 when no beat grid is known. */
    val beatPhase: Float = 0f,
    /** [PulseTracker]'s confidence that its beat grid matches the music,
     *  0..1. Low on ambient/rubato material - scenes wanting tempo-synced
     *  choreography should fall back to energy-driven motion below ~0.5. */
    val pulseConfidence: Float = 0f,
    /** Track-relative macro-dynamics envelope, 0..1: how loud this moment is
     *  against the song's own recent peak (fast attack, slow release). The
     *  continuous "arc of the song" signal - verses sit low, choruses high -
     *  where [rms] is the instantaneous level. */
    val macroEnergy: Float = 0f,
) {
    /**
     * What a beat should DO to the visuals this frame: 0 off beats, the
     * graded [beatStrength] on them - except for beat flags that carry no
     * strength (synthesised features, pre-tracker cache entries), which keep
     * their historical full-strength kick. Every consumer that used to branch
     * on [beat] alone should scale by this instead.
     */
    val beatImpulse: Float
        get() =
            when {
                !beat -> 0f
                beatStrength > 0f -> beatStrength
                else -> 1f
            }

    companion object {
        fun empty(
            bandCount: Int = 64,
            waveformSize: Int = 128,
        ): AudioFeatures = AudioFeatures(FloatArray(bandCount), FloatArray(waveformSize))
    }
}
