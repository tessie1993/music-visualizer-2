package dev.musicviz.analysis

import dev.musicviz.render.scene.SceneParams

/**
 * Starting points for microphone input, one per kind of thing a phone is
 * likely to be pointed at.
 *
 * Live input is not one signal. A speaker in the same room arrives loud,
 * bass-heavy and already compressed; a guitar arrives as sharp transients over
 * near-silence; a voice has strong mids, weak bass and no beat at all; a PA
 * heard across a room arrives smeared by reverb with the transients gone. The
 * same beat threshold and the same band gains cannot serve all four - and the
 * settings that fix each are spread across three different screens, which is
 * exactly the kind of tuning nobody does mid-session.
 *
 * Each profile is a starting point, not a mode: everything it writes stays a
 * normal slider afterwards, and nothing here is remembered as "the profile in
 * use" - there is no state to get out of sync with the sliders it moved.
 *
 * Pure so the numbers can be reasoned about and pinned by tests: the ranges
 * are the sliders' own, and the relationships between profiles (which is more
 * sensitive than which, and why) are what the gate checks.
 */
enum class LiveInputProfile(
    val label: String,
    val summary: String,
    /** Beat threshold in sigmas; higher = fewer, surer beats. */
    val beatSigma: Float,
    /** Floor on the gap between beats, in ms. */
    val beatIntervalMs: Float,
    /** Band smoother attack (fast = snappier response to onsets). */
    val attack: Float,
    /** Band smoother decay (slow = longer afterglow). */
    val decay: Float,
    private val audioDrive: Float,
    private val bassGain: Float,
    private val midGain: Float,
    private val trebGain: Float,
    private val beatResponse: Float,
) {
    /**
     * A speaker in the room, playing music, close enough to be the loudest
     * thing there. The nearest case to line-level playback, so it stays close
     * to the app's own defaults.
     */
    SPEAKER(
        label = "Speaker",
        summary = "Music playing nearby, loud and clear. Closest to normal playback.",
        beatSigma = BeatTuning.SENSITIVITY_DEFAULT,
        beatIntervalMs = BeatTuning.INTERVAL_MS_DEFAULT,
        attack = 0.6f,
        decay = 0.12f,
        audioDrive = 1f,
        bassGain = 1f,
        midGain = 1f,
        trebGain = 1f,
        beatResponse = 1f,
    ),

    /**
     * A PA heard across a room. Reverb smears the transients the beat detector
     * keys off, and the low end blooms, so this asks for the LOWEST usable
     * threshold (the onsets are softer) while pulling the bass back and
     * pushing the treble up to recover the detail the room ate.
     *
     * The original design wanted 1.15 sigma, below [BeatTuning.SENSITIVITY_MIN];
     * the engine clamps there because a lower gate fires on noise, so the old
     * value silently ran at the floor anyway while Settings displayed 1.15.
     * Pinned to the floor until a DSP review widens the engine range.
     */
    ROOM(
        label = "Room",
        summary = "A PA across a room: softer transients, boomy low end, reverb tail.",
        beatSigma = BeatTuning.SENSITIVITY_MIN,
        beatIntervalMs = 260f,
        attack = 0.45f,
        decay = 0.2f,
        audioDrive = 1.35f,
        bassGain = 0.75f,
        midGain = 1.05f,
        trebGain = 1.3f,
        beatResponse = 1.15f,
    ),

    /**
     * A guitar, a piano, a drum - something played into the phone. Sharp
     * transients over near-silence, so the threshold drops as far as the
     * engine allows so a pluck registers, the attack goes fast enough to
     * catch it, and the gap floor sits at the engine minimum so a fast
     * passage is thinned as little as possible.
     *
     * The original design wanted 1 sigma / 130 ms, below both engine floors
     * ([BeatTuning.SENSITIVITY_MIN] against noise triggers,
     * [BeatTuning.INTERVAL_MS_MIN] = the 300 BPM refractory); the old
     * values silently ran at the floors anyway while Settings displayed them.
     * Pinned to the floors until a DSP review widens the engine range.
     */
    INSTRUMENT(
        label = "Instrument",
        summary = "Played into the phone: sharp attacks, wide dynamics, no steady beat.",
        beatSigma = BeatTuning.SENSITIVITY_MIN,
        beatIntervalMs = BeatTuning.INTERVAL_MS_MIN,
        attack = 0.85f,
        decay = 0.28f,
        audioDrive = 1.6f,
        bassGain = 0.85f,
        midGain = 1.25f,
        trebGain = 1.15f,
        beatResponse = 1.4f,
    ),

    /**
     * Speech or singing. There is no beat to find, so the threshold goes HIGH
     * and the gap floor long - the point is to stop syllables being read as a
     * tempo and strobing the screen - while the mids carry everything.
     */
    VOICE(
        label = "Voice",
        summary = "Speech or singing: mids carry it, and syllables are not beats.",
        beatSigma = 2.2f,
        beatIntervalMs = 500f,
        attack = 0.5f,
        decay = 0.35f,
        audioDrive = 1.5f,
        bassGain = 0.5f,
        midGain = 1.4f,
        trebGain = 0.9f,
        beatResponse = 0.7f,
    ),
    ;

    /**
     * The profile's band balance and drive, applied to [params]. Everything
     * else in the parameter set - the look the user built - is untouched: a
     * profile tunes how the visuals HEAR, never what they look like.
     */
    fun apply(params: SceneParams): SceneParams =
        params.copy(
            audioDrive = audioDrive,
            bassGain = bassGain,
            midGain = midGain,
            trebGain = trebGain,
            beatResponse = beatResponse,
        )
}
