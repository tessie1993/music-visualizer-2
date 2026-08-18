package dev.geode.engine.audio

/**
 * One acquisition's worth of feature values, preallocated by the reader so
 * [FeatureRing.acquireAt] can fill it without allocating.
 */
class FeatureFrame(
    continuousSlots: Int,
    eventSlots: Int,
) {
    /** Interpolated continuous values, valid after an OK acquire. */
    val continuous: FloatArray = FloatArray(continuousSlots)

    /** MAX/OR-combined event strengths over the span; 0 means "did not fire". */
    val events: FloatArray = FloatArray(eventSlots)

    /** The epoch the values belong to, set on an OK acquire. */
    var epoch: Int = -1
        internal set
}

/**
 * A single-writer, multi-reader ring of feature frames addressed by
 * ABSOLUTE SAMPLE INDEX — §5.6's replacement for latest-wins consumption.
 *
 * A renderer that reads a latest-state field misses every event that fired
 * between two of its reads: at 30 fps over a 62.5 Hz analysis hop, half of
 * all one-frame beat flags simply vanish. [acquireAt] answers the question
 * the renderer actually has — "what happened at sample t, and since I last
 * looked?" — with continuous slots LINEARLY INTERPOLATED at `t` and event
 * slots MAX-combined (OR, for flags carried as strengths) over the span.
 *
 * The span's frame selection deliberately mirrors the app's
 * `FeatureTimeline.featuresAt`, the reference implementation this ring must
 * prove itself against before any consumer switches: the base frame is the
 * one NEAREST `t`, the last is the frame nearest `t + span` minus one, and
 * events combine over that inclusive range. The mirror is pinned app-side,
 * event for event, against the timeline itself.
 *
 * Per §5.1's contract there are no silent clamps: a reader ahead of the
 * writer gets [Acquire.NOT_YET_AVAILABLE], one fallen off the back gets
 * [Acquire.GAP], an unstarted epoch [Acquire.EMPTY]. [beginEpoch] restarts
 * the numbering on seek or source change. The writer never blocks on
 * readers; a reader that raced a lapping writer detects it (the same
 * re-check discipline [SampleRing] uses) and reports the gap it actually
 * has. Neither side allocates after construction.
 */
class FeatureRing(
    val continuousSlots: Int,
    val eventSlots: Int,
    val capacityFrames: Int = 512,
) {
    init {
        require(continuousSlots >= 0 && eventSlots >= 0 && continuousSlots + eventSlots > 0) {
            "need at least one slot"
        }
        require(capacityFrames > 1 && capacityFrames and (capacityFrames - 1) == 0) {
            "capacityFrames must be a power of two above 1, was $capacityFrames"
        }
    }

    enum class Acquire { OK, GAP, NOT_YET_AVAILABLE, EMPTY, DISCONTINUITY }

    private val mask = capacityFrames - 1
    private val sampleAt = LongArray(capacityFrames)
    private val continuousRows = FloatArray(capacityFrames * continuousSlots)
    private val eventRows = FloatArray(capacityFrames * eventSlots)

    @Volatile
    private var written: Int = 0

    @Volatile
    private var epochValue: Int = 0

    /** Increments whenever the sample numbering restarts. */
    val epoch: Int get() = epochValue

    /**
     * Oldest frame a reader can still trust. A quarter of the ring is the
     * writer's runway, the same proportional headroom [SampleRing] reserves
     * and for the same published-after-stored reason.
     */
    private val oldestReadable: Int get() = maxOf(0, written + capacityFrames / 4 - capacityFrames)

    /** Restarts the numbering; call on seek, source or format change. */
    fun beginEpoch() {
        written = 0
        epochValue += 1
    }

    /**
     * Publishes one frame. Writer thread only; [sampleIndex] must be
     * strictly greater than the previous frame's within the epoch.
     */
    fun publish(
        sampleIndex: Long,
        continuous: FloatArray,
        events: FloatArray,
    ) {
        require(continuous.size == continuousSlots) { "expected $continuousSlots continuous slots" }
        require(events.size == eventSlots) { "expected $eventSlots event slots" }
        val w = written
        require(w == 0 || sampleIndex > sampleAt[(w - 1) and mask]) {
            "sampleIndex must increase; got $sampleIndex after ${sampleAt[(w - 1) and mask]}"
        }
        val slot = w and mask
        sampleAt[slot] = sampleIndex
        continuous.copyInto(continuousRows, slot * continuousSlots)
        events.copyInto(eventRows, slot * eventSlots)
        written = w + 1
    }

    /**
     * Fills [out] for sample [sampleIndex] with events combined over
     * `[sampleIndex, sampleIndex + spanSamples]`. Reader-safe; see the
     * class doc for the outcome contract.
     */
    fun acquireAt(
        sampleIndex: Long,
        spanSamples: Long,
        out: FeatureFrame,
    ): Acquire {
        require(spanSamples >= 0L) { "spanSamples must not be negative" }
        val epochBefore = epochValue
        val w = written
        if (w == 0) return Acquire.EMPTY
        val newestSlot = (w - 1) and mask
        if (sampleIndex > sampleAt[newestSlot]) return Acquire.NOT_YET_AVAILABLE
        val oldest = oldestReadable
        if (sampleIndex < sampleAt[oldest and mask]) return Acquire.GAP

        val base = frameNearest(sampleIndex, oldest, w - 1)
        interpolateInto(sampleIndex, base, w - 1, out)

        val last =
            if (spanSamples <= 0L) {
                base
            } else {
                (frameNearest(sampleIndex + spanSamples, oldest, w - 1) - 1).coerceIn(base, w - 1)
            }
        for (e in 0 until eventSlots) out.events[e] = eventRows[(base and mask) * eventSlots + e]
        for (frame in base + 1..last) {
            val row = (frame and mask) * eventSlots
            for (e in 0 until eventSlots) {
                val v = eventRows[row + e]
                if (v > out.events[e]) out.events[e] = v
            }
        }

        // The writer does not stop for readers: re-check that nothing read
        // above was overwritten mid-copy, and that the epoch held.
        if (epochValue != epochBefore) return Acquire.DISCONTINUITY
        val writtenNow = written
        val oldestNow = maxOf(0, writtenNow + capacityFrames / 4 - capacityFrames)
        if (base < oldestNow) return Acquire.GAP
        out.epoch = epochBefore
        return Acquire.OK
    }

    /** Frame index (write-order) whose sample is nearest [sampleIndex]. */
    private fun frameNearest(
        sampleIndex: Long,
        first: Int,
        last: Int,
    ): Int {
        var lo = first
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (sampleAt[mid and mask] <= sampleIndex) lo = mid else hi = mid - 1
        }
        // lo is the last frame at-or-before; the next one may be closer.
        if (lo < last) {
            val below = sampleIndex - sampleAt[lo and mask]
            val above = sampleAt[(lo + 1) and mask] - sampleIndex
            if (above < below) return lo + 1
        }
        return lo
    }

    private fun interpolateInto(
        sampleIndex: Long,
        base: Int,
        last: Int,
        out: FeatureFrame,
    ) {
        val floor = if (sampleAt[base and mask] <= sampleIndex) base else (base - 1).coerceAtLeast(0)
        val ceil = (floor + 1).coerceAtMost(last)
        val a = sampleAt[floor and mask]
        val b = sampleAt[ceil and mask]
        val t = if (b <= a) 0f else ((sampleIndex - a).toFloat() / (b - a).toFloat()).coerceIn(0f, 1f)
        val rowA = (floor and mask) * continuousSlots
        val rowB = (ceil and mask) * continuousSlots
        for (c in 0 until continuousSlots) {
            val va = continuousRows[rowA + c]
            out.continuous[c] = va + (continuousRows[rowB + c] - va) * t
        }
    }
}
