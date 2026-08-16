package dev.musicviz.engine.audio

/**
 * One span over which input samples map linearly to presentation time.
 *
 * The fields are MASTER_PLAN §5.2's, and the reason there is a slope at all is
 * that the tap sits **above** Sonic and silence skipping. Below it, speed
 * rescales how much input a second of playback consumes and skipped silence
 * removes spans from the mapping entirely, so `presentationTime = sampleTime +
 * offset` is wrong by a factor and by a gap.
 *
 * ## Which clock "presentation" means
 *
 * The output timeline — the one the listener hears on — not `currentPosition`.
 * The difference is exactly [speed]: at 2x, one second of presentation consumes
 * two seconds of input, while media position advances at 2x wall clock. If
 * presentation meant media position, [speed] and a variable slope would both be
 * dead fields.
 *
 * That timeline only ever moves forward. A seek does not rewind it — it changes
 * which input samples land there — which is why [presentationUsStart] is
 * non-decreasing across every segment while [inputSampleStart] restarts with
 * each epoch.
 *
 * @param inputSampleStart first input frame this segment covers, in [epoch]'s numbering
 * @param presentationUsStart presentation time at that frame, after [skippedInputSamples]
 * @param inputSamplesPerPresentationUs the slope; `sampleRateHz * speed / 1e6`
 * @param skippedInputSamples frames at the head of this segment that silence
 *   skipping removed — they consume input numbering and produce no presentation
 *   time, so they are a hole in the mapping rather than a shift of it
 * @param discontinuityGeneration §5.1's counter, so a consumer holding an index
 *   from before a break can tell it is stale rather than reading plausible nonsense
 */
data class ClockSegment(
    val epoch: Int,
    val discontinuityGeneration: Int,
    val inputSampleStart: Long,
    val presentationUsStart: Long,
    val inputSamplesPerPresentationUs: Double,
    val speed: Float,
    val skippedInputSamples: Long,
) {
    init {
        require(inputSamplesPerPresentationUs > 0.0) {
            "slope must be positive, was $inputSamplesPerPresentationUs"
        }
        require(inputSampleStart >= 0) { "inputSampleStart must not be negative" }
        require(skippedInputSamples >= 0) { "skippedInputSamples must not be negative" }
    }

    /** First frame after this segment's skipped head — the first one that is heard. */
    val firstPresentedSample: Long get() = inputSampleStart + skippedInputSamples

    companion object {
        private const val US_PER_SECOND = 1_000_000.0

        /**
         * Builds a segment from the physical quantities rather than the slope.
         *
         * The slope is a function of rate and speed, so a constructor that takes
         * all three lets a caller supply a set that cannot happen. This derives
         * it once, here.
         */
        fun fromFormat(
            epoch: Int,
            discontinuityGeneration: Int,
            inputSampleStart: Long,
            presentationUsStart: Long,
            sampleRateHz: Int,
            speed: Float,
            skippedInputSamples: Long = 0L,
        ): ClockSegment {
            require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
            require(speed > 0f) { "speed must be positive, was $speed" }
            return ClockSegment(
                epoch = epoch,
                discontinuityGeneration = discontinuityGeneration,
                inputSampleStart = inputSampleStart,
                presentationUsStart = presentationUsStart,
                inputSamplesPerPresentationUs = sampleRateHz * speed / US_PER_SECOND,
                speed = speed,
                skippedInputSamples = skippedInputSamples,
            )
        }
    }
}
