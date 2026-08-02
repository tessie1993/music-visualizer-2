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
    /** Graded transient impulse from [PulseTracker], 0..1: fires for EVERY
     *  detected onset - including the off-grid ones the beat grid holds back
     *  - with its size following the hit's own amplitude, damped and metered
     *  by a per-beat budget so dense runs taper off instead of strobing. The
     *  "the player actually hit something there" texture channel; on beat
     *  frames it mirrors [beatStrength]. */
    val transient: Float = 0f,
    /** [PulseTracker]'s confidence that its beat grid matches the music,
     *  0..1. Low on ambient/rubato material - scenes wanting tempo-synced
     *  choreography should fall back to energy-driven motion below ~0.5. */
    val pulseConfidence: Float = 0f,
    /** Track-relative macro-dynamics envelope, 0..1: how loud this moment is
     *  against the song's own recent peak (fast attack, slow release). The
     *  continuous "arc of the song" signal - verses sit low, choruses high -
     *  where [rms] is the instantaneous level. */
    val macroEnergy: Float = 0f,
    /**
     * Graded 0..1 impulse for a low-band (kick-range) onset this frame, 0
     * otherwise. See [DrumChannels] for what these three do and do not claim:
     * they are band-activity channels named after what usually dominates them,
     * not a drum classifier.
     *
     * Same one-frame contract as [beatStrength] - consumers build their own
     * envelope. 0 on synthesised features and on any frame no [DrumChannels]
     * ran over, which is indistinguishable from "no hit" and is the correct
     * degradation: a scene reading these gets stillness, never a false trigger.
     */
    val kick: Float = 0f,
    /** Mid-band (snare-range) onset impulse. See [kick]. */
    val snare: Float = 0f,
    /** High-band (hat/cymbal-range) onset impulse. See [kick]. */
    val hat: Float = 0f,
    /**
     * Stereo width in 0..1 over the analysis window: 0 is mono, 0.5 a
     * hard-panned source, 1 a purely out-of-phase difference signal. See
     * [StereoField.width].
     *
     * 0 when the source is mono, when no side channel was supplied, and on
     * synthesised features - all of which mean the same thing to a scene
     * (nothing to widen), so the degradation is silent and correct.
     */
    val stereoWidth: Float = 0f,
    /**
     * Interchannel correlation in -1..1; +1 mono or hard-panned, 0
     * decorrelated, negative out of phase. See [StereoField.correlation].
     *
     * Defaults to 1, not 0: 1 is what a mono source genuinely measures, and a
     * default of 0 would tell every scene that unanalysed audio is perfectly
     * decorrelated.
     */
    val stereoCorrelation: Float = 1f,
    /**
     * Per-frame 12-bin chromagram, index 0 = C, largest bin scaled to 1. See
     * [Chromagram], including what its resolution limit means.
     *
     * EMPTY when no chromagram ran - a live fallback, a synthesised feature, a
     * cache entry. Empty rather than twelve zeros so a consumer can tell "no
     * pitch information" from "silence", which are different situations: the
     * first should leave a harmony-driven visual on its last reading, the
     * second should let it settle.
     */
    val chroma: FloatArray = EMPTY_CHROMA,
    /**
     * How pitched this frame is, 0..1 - [Chromagram.confidence]. Scenes
     * should hold their last harmony below about 0.35 rather than follow a
     * drum fill.
     */
    val chromaConfidence: Float = 0f,
) {
    /** True when [chroma] carries a reading rather than "not measured". */
    val hasChroma: Boolean get() = chroma.size == 12

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

    /**
     * What CONTINUOUS motion envelopes should ride: the tempo-locked
     * [beatImpulse], topped up by off-grid transients at reduced weight - so
     * the visuals breathe with what is actually being played between beats,
     * scaled by each hit's own amplitude, without turning every transient
     * back into a full trigger. Discrete event triggers (bursts, ripple
     * rings, flash-style uBeat effects) should stay on [beatImpulse], or
     * they would fire per transient again.
     */
    val motionImpulse: Float
        get() = maxOf(beatImpulse, transient * TRANSIENT_MOTION_WEIGHT)

    companion object {
        /** Weight of the transient channel inside [motionImpulse]: texture at
         *  up to half the presence of a confirmed beat. */
        const val TRANSIENT_MOTION_WEIGHT = 0.5f

        /** Shared "no chromagram ran" marker; see [AudioFeatures.chroma]. */
        val EMPTY_CHROMA = FloatArray(0)

        fun empty(
            bandCount: Int = 64,
            waveformSize: Int = 128,
        ): AudioFeatures = AudioFeatures(FloatArray(bandCount), FloatArray(waveformSize))
    }
}
