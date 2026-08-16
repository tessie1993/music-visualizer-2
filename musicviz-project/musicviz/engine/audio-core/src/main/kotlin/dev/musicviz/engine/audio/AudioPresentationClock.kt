package dev.musicviz.engine.audio

import kotlin.math.roundToLong

/**
 * The piecewise map between captured input frames and presentation time,
 * published as an immutable snapshot.
 *
 * Reading is pure and allocation-free apart from the result object; the render
 * thread takes [current] once and asks it as often as it likes, with no lock
 * and no risk of the answer changing mid-frame. MASTER_PLAN §5.2 asks for
 * exactly that, and for segments never to be appended from the audio callback —
 * they are appended on seek, speed change, silence-skip discontinuity, route
 * rebuild and source replacement, all of which are playback-thread events.
 *
 * Older segments are dropped past [maxSegments]. A clock that grows a segment
 * per seek grows without bound over a long listening session, and the honest
 * cost of the cap is that very old times answer [PresentationTime.Unknown]
 * rather than something invented.
 */
class AudioPresentationClock(
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
) {
    init {
        require(maxSegments > 0) { "maxSegments must be positive, was $maxSegments" }
    }

    /**
     * A volatile reference to an immutable value is the whole publication
     * mechanism: there is one writer, so nothing needs to compare-and-set, and
     * a reader either sees the old snapshot whole or the new one whole.
     */
    @Volatile
    private var snapshot: PresentationSnapshot = PresentationSnapshot(emptyList())

    /** The current mapping. Hold it for a frame rather than re-reading per query. */
    val current: PresentationSnapshot get() = snapshot

    /**
     * Adds [segment] to the end of the timeline.
     *
     * The invariants are checked rather than assumed, because every one of them
     * failing produces a mapping that still returns numbers.
     */
    fun append(segment: ClockSegment) {
        val last = snapshot.segments.lastOrNull()
        if (last != null) {
            require(segment.presentationUsStart >= last.presentationUsStart) {
                "presentation time only moves forward; ${segment.presentationUsStart} follows ${last.presentationUsStart}"
            }
            require(segment.discontinuityGeneration >= last.discontinuityGeneration) {
                "discontinuity generation only increases"
            }
            require(segment.epoch != last.epoch || segment.inputSampleStart >= last.inputSampleStart) {
                "within an epoch input frames only move forward; " +
                    "${segment.inputSampleStart} follows ${last.inputSampleStart} in epoch ${segment.epoch}"
            }
        }
        val kept = snapshot.segments + segment
        snapshot = PresentationSnapshot(if (kept.size > maxSegments) kept.takeLast(maxSegments) else kept)
    }

    private companion object {
        /**
         * Enough to hold a long run of seeks and speed changes; at ~90 bytes a
         * segment the whole history costs less than one audio buffer.
         */
        const val DEFAULT_MAX_SEGMENTS = 64
    }
}

/**
 * An immutable timeline. Every query is answered from this one object, so two
 * questions asked of the same snapshot are always answered consistently.
 */
class PresentationSnapshot(
    val segments: List<ClockSegment>,
) {
    /** The numbering currently being captured, or null before anything is recorded. */
    val epoch: Int? get() = segments.lastOrNull()?.epoch

    /** When [inputSample] of [epoch] is heard. */
    fun presentationTimeOf(
        inputSample: Long,
        epoch: Int,
    ): PresentationTime {
        val currentEpoch = this.epoch ?: return PresentationTime.Unknown
        if (epoch != currentEpoch) return PresentationTime.StaleEpoch(epoch, currentEpoch)

        val segment = segmentCovering(inputSample, epoch) ?: return PresentationTime.Unknown
        val offset = inputSample - segment.inputSampleStart
        if (offset < segment.skippedInputSamples) {
            return PresentationTime.Skipped(segment.inputSampleStart, segment.firstPresentedSample)
        }
        val heardOffset = offset - segment.skippedInputSamples
        return PresentationTime.At(
            segment.presentationUsStart + (heardOffset / segment.inputSamplesPerPresentationUs).roundToLong(),
        )
    }

    /** Which input frame is heard at [presentationUs]. */
    fun inputPositionAt(presentationUs: Long): InputPosition {
        // Newest first: presentation time never moves backwards, so the last
        // segment that started at or before this time is the one covering it.
        val segment =
            segments.lastOrNull { it.presentationUsStart <= presentationUs }
                ?: return InputPosition.Unknown
        val elapsedUs = presentationUs - segment.presentationUsStart
        return InputPosition.At(
            inputSample = segment.firstPresentedSample + (elapsedUs * segment.inputSamplesPerPresentationUs).roundToLong(),
            epoch = segment.epoch,
        )
    }

    private fun segmentCovering(
        inputSample: Long,
        epoch: Int,
    ): ClockSegment? = segments.lastOrNull { it.epoch == epoch && it.inputSampleStart <= inputSample }
}
