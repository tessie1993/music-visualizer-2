package dev.musicviz.analysis

/**
 * Bounds the number of [TimelineFrame]s the offline analyzer holds, so a very
 * long file cannot run the process out of memory mid-analysis.
 *
 * A 60 Hz frame costs roughly a kilobyte (64 band floats + 128 waveform floats
 * plus object headers), so the analyzer's frame list grows about 3.5 MB per
 * track minute. A 90-minute album rip is ~300 MB and a 3-hour DJ set ~650 MB -
 * an OOM kill during "Analyzing...", not a slow analysis.
 *
 * The bound works by halving the time resolution instead of truncating the
 * track: when [maxFrames] is reached, the whole history is merged pairwise
 * (doubling the effective hop) and every subsequent [groupSize] incoming
 * frames merge into one stored frame, so spacing stays uniform - which is what
 * keeps [FeatureTimeline]'s nearest-index lookup valid - and the whole track
 * stays covered. Memory peaks at ~[maxFrames] frames and halves immediately.
 *
 * Merging follows the same rules as [FeatureTimeline.featuresAt] with a span:
 * the one-frame impulses (the beat flag, onset, flux, beatStrength, transient,
 * kick/snare/hat) are OR-ed / max-held across the group so no beat or hit
 * disappears, while continuous levels (bands, waveform, rms, bass/mid/treble,
 * centroid, bpm, phase, confidence, energy, chroma) keep the group's first
 * point sample - averaging those would low-pass the visuals, and averaging a
 * waveform cancels its phase.
 *
 * Pure JVM, single-threaded, deterministic: the same frames in produce the
 * same frames out, so cached analysis and export stay in step.
 */
class FrameAccumulator(
    private val maxFrames: Int = MAX_OFFLINE_FRAMES,
) {
    init {
        require(maxFrames >= 2) { "maxFrames must be at least 2" }
    }

    private val frames = ArrayList<TimelineFrame>()
    private val pending = ArrayList<TimelineFrame>(2)

    /**
     * How many incoming frames each stored frame now represents: 1 until
     * [maxFrames] is first reached, doubling on each halving. The caller
     * divides its hop rate by this when building the [FeatureTimeline].
     */
    var groupSize: Int = 1
        private set

    /** Stored frames so far (a partial in-progress group is not counted). */
    val size: Int get() = frames.size

    /** Appends one analysis frame, merging and halving as the bound demands. */
    fun add(frame: TimelineFrame) {
        if (groupSize == 1) {
            frames.add(frame)
        } else {
            pending.add(frame)
            if (pending.size >= groupSize) {
                frames.add(mergeGroup(pending))
                pending.clear()
            }
        }
        if (frames.size >= maxFrames) {
            halveInPlace()
            groupSize *= 2
        }
    }

    /**
     * Flushes any partial trailing group and returns the frames. The
     * accumulator is spent afterwards; build the timeline and drop it.
     */
    fun finish(): List<TimelineFrame> {
        if (pending.isNotEmpty()) {
            frames.add(mergeGroup(pending))
            pending.clear()
        }
        return frames
    }

    /** Pairwise in-place merge; an odd trailing frame is kept as-is. */
    private fun halveInPlace() {
        var w = 0
        var r = 0
        while (r + 1 < frames.size) {
            frames[w++] = merge(frames[r], frames[r + 1])
            r += 2
        }
        if (r < frames.size) frames[w++] = frames[r]
        while (frames.size > w) frames.removeAt(frames.size - 1)
    }

    companion object {
        /**
         * 30 minutes at the 60 Hz offline hop. Below this nothing changes at
         * all; a longer track analyses at 30 Hz (60-minute reach), then 15 Hz,
         * and so on - still finer than any scene needs for a track that long,
         * and bounded however long the file is.
         */
        const val MAX_OFFLINE_FRAMES = 108_000

        /**
         * Merges two consecutive frames into one covering both: impulses are
         * OR-ed / max-held, continuous levels keep [a]'s point sample, and the
         * merged frame keeps [a]'s timestamp.
         */
        fun merge(
            a: TimelineFrame,
            b: TimelineFrame,
        ): TimelineFrame {
            val fa = a.features
            val fb = b.features
            val merged =
                fa.copy(
                    beat = fa.beat || fb.beat,
                    onset = maxOf(fa.onset, fb.onset),
                    flux = maxOf(fa.flux, fb.flux),
                    beatStrength = maxOf(fa.beatStrength, fb.beatStrength),
                    transient = maxOf(fa.transient, fb.transient),
                    kick = maxOf(fa.kick, fb.kick),
                    snare = maxOf(fa.snare, fb.snare),
                    hat = maxOf(fa.hat, fb.hat),
                )
            // Identity-preserving when nothing differs, like featuresAt.
            return if (merged == fa) a else TimelineFrame(a.timeMs, merged)
        }

        private fun mergeGroup(group: List<TimelineFrame>): TimelineFrame {
            var acc = group[0]
            for (i in 1 until group.size) acc = merge(acc, group[i])
            return acc
        }
    }
}
