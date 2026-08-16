package dev.musicviz.engine.audioandroid

import dev.musicviz.engine.audio.AudioPresentationClock
import dev.musicviz.engine.audio.ClockSegment
import kotlin.math.roundToLong

/**
 * Drives [clock] from the audio pipeline's own flush points.
 *
 * Every number a segment carries is latched inside the one playback-thread
 * call stack where all of them exist at once: the speed media3 just applied,
 * the frames the ended generation captured, and the silence-skipping stage's
 * per-generation counter, which is zeroed a few instructions later. A
 * `Player.Listener` would see the same events on the application looper at an
 * unordered time — a good label and a useless anchor — and would die with the
 * screen while playback continued.
 *
 * The driver keeps its own anchor rather than reading [clock] back, so the
 * clock keeps exactly one writer and there is no read-modify-write against a
 * published snapshot.
 *
 * ## What it does not know
 *
 * *Where* silence was removed. Media3 announces the skip-silence **toggle**,
 * never a skip event; the frames vanish inside the stage with no callback. So
 * every segment carries `skippedInputSamples = 0` and the removed frames are
 * folded into the next anchor instead. Segment interiors therefore run late by
 * the silence removed so far within them — corrected at the next boundary,
 * zero when skip-silence is off, and the reason
 * [dev.musicviz.engine.audio.PresentationTime.Skipped] is unreachable in
 * production until the slice that locates the spans.
 *
 * Anchors are exact with one named exception: on a reconfiguration media3
 * cascades `queueEndOfStream` in ascending pipeline order, so this reads the
 * counter before the silence-skipping stage adds the tail it is still holding,
 * and the next flush zeroes it. The anchor runs ahead by under one
 * `minimumSilenceDurationUs` of input (100 ms by default) per drain boundary,
 * and by nothing at all with skip-silence off. Documented in
 * `AUDIO_FEATURE_ABI.md` §2.2 rather than absorbed as rounding.
 */
class SinkClockDriver(
    private val clock: AudioPresentationClock,
) : SinkClockHooks, TapBoundaryListener {
    /** Set on the main thread at construction time, read on the playback thread. */
    @Volatile
    private var skipped: SkippedFrameSource = SkippedFrameSource { 0L }

    // Playback-thread confined: all three hooks and onTapBoundary arrive on
    // one call stack. @Volatile here would imply a cross-thread reader that
    // does not exist, the same reasoning as PcmTap.staging.
    private var speed: Float = 1f
    private var sawSpeedHook = false
    private var sawSkipHook = false
    private var speedAuthoritative = false

    /** Presentation time at which the currently open generation starts. */
    private var anchorUs: Long = 0L

    /** Slope of the open generation; 0.0 means its span cannot be measured. */
    private var openSlope: Double = 0.0

    /** False once a span went unmodelled; see [distrustAnchor]. */
    private var anchorTrusted = true

    private var boundaries = 0L
    private var segmentsAppended = 0L
    private var hookedBoundaries = 0L
    private var refusedSpeedNotAuthoritative = 0L
    private var refusedUnreadableFormat = 0L
    private var refusedUntrustedAnchor = 0L
    private var discardedSkipExceedingFrames = 0L
    private var refusedByClockInvariant = 0L
    private var unmeasuredBoundaries = 0L

    val diagnostics: SinkClockDiagnostics
        get() =
            SinkClockDiagnostics(
                boundaries = boundaries,
                segmentsAppended = segmentsAppended,
                hookedBoundaries = hookedBoundaries,
                refusedSpeedNotAuthoritative = refusedSpeedNotAuthoritative,
                refusedUnreadableFormat = refusedUnreadableFormat,
                refusedUntrustedAnchor = refusedUntrustedAnchor,
                discardedSkipExceedingFrames = discardedSkipExceedingFrames,
                refusedByClockInvariant = refusedByClockInvariant,
                unmeasuredBoundaries = unmeasuredBoundaries,
                skippedFramesAttached = skippedFramesAttached,
                anchorTrusted = anchorTrusted,
            )

    @Volatile
    private var skippedFramesAttached = false

    override fun attachSkippedFrames(source: SkippedFrameSource) {
        skipped = source
        skippedFramesAttached = true
    }

    override fun onSpeedApplied(speed: Float) {
        this.speed = speed
        sawSpeedHook = true
    }

    override fun onSkipSilenceApplied(enabled: Boolean) {
        sawSkipHook = true
    }

    override fun onTapBoundary(
        ended: PcmTapFormat?,
        endedFrames: Long,
        begun: PcmTapFormat,
    ) {
        boundaries++
        if (sawSkipHook) hookedBoundaries++

        // Read only where there is something to attribute the silence to. The
        // boundary that carries the frames is the DRAIN one - media3 drains to
        // end of stream before reconfiguring, so the tap sees the whole ended
        // generation there - and the hooked flush that follows carries zero.
        // Reading at the empty one would subtract the same silence twice.
        val rawSkip = if (ended != null && endedFrames > 0L) skipped.skippedInputFramesSinceFlush() else 0L
        // More silence removed than frames captured is arithmetically
        // impossible, so the number is not a large skip - it is a bad read.
        // Discarding it keeps the anchor on the frames that WERE captured,
        // which is the part still trustworthy; believing it would silently
        // declare a whole generation silent.
        val trustworthy = rawSkip in 0L..endedFrames
        if (!trustworthy) discardedSkipExceedingFrames++
        val skip = if (trustworthy) rawSkip else 0L

        if (openSlope > 0.0) {
            anchorUs += ((endedFrames - skip) / openSlope).roundToLong()
        } else if (ended != null && endedFrames > 0L) {
            // Frames were captured across a span whose slope was never known,
            // so real presentation time advanced by an amount nothing here can
            // compute. Gated on endedFrames because a zero-frame boundary
            // loses nothing and would otherwise report a hole that is not one.
            unmeasuredBoundaries++
            distrustAnchor()
        }

        // A skip hook without a speed hook proves media3 routed speed to the
        // AudioTrack and never told Sonic; a slope built from `speed` would be
        // fiction. Only a hooked boundary carries this evidence, so a seek -
        // which raises neither - must leave the verdict alone.
        if (sawSkipHook) speedAuthoritative = sawSpeedHook
        sawSpeedHook = false
        sawSkipHook = false

        openSlope = 0.0
        if (!speedAuthoritative) {
            refusedSpeedNotAuthoritative++
            distrustAnchor()
            return
        }
        // `!(speed > 0f)` rather than `speed <= 0f`: every comparison with NaN
        // is false, so the negative form is the only one that refuses it.
        if (begun.sampleRateHz <= 0 || begun.channelCount <= 0 || begun.sampleWidth == null || !(speed > 0f)) {
            refusedUnreadableFormat++
            distrustAnchor()
            return
        }
        if (!anchorTrusted) {
            refusedUntrustedAnchor++
            return
        }

        // Inside the try, not above it: ClockSegment.fromFormat and the data
        // class's own init both require(), and this runs inside
        // AudioProcessor.flush where a throw stops the music. Building the
        // segment outside would have left that net to PcmTap's catch, which
        // would swallow the fault without counting it here.
        try {
            val segment =
                ClockSegment.fromFormat(
                    epoch = begun.generation,
                    discontinuityGeneration = begun.generation,
                    // Exact: the tap zeroed its counter immediately before calling.
                    inputSampleStart = 0L,
                    presentationUsStart = anchorUs,
                    sampleRateHz = begun.sampleRateHz,
                    speed = speed,
                    skippedInputSamples = 0L,
                )
            clock.append(segment)
            openSlope = segment.inputSamplesPerPresentationUs
            segmentsAppended++
        } catch (unexpected: IllegalArgumentException) {
            refusedByClockInvariant++
        }
    }

    /**
     * Records that presentation time advanced across a span this could not
     * model, and stops appending for good.
     *
     * Resuming would be worse than stopping. The anchor is short by the whole
     * unmodelled span, so every later segment would map input frames to times
     * that are early by minutes - a confident `At` where `StaleEpoch` is the
     * truthful answer, which is the exact failure §5.1 exists to prevent. The
     * span cannot be recovered either: its duration is frames times a slope
     * that was, by definition, unknown.
     *
     * Only latched once the timeline has started. Refusals before the first
     * append are not gaps - nothing was being modelled yet.
     */
    private fun distrustAnchor() {
        if (segmentsAppended > 0L) anchorTrusted = false
    }
}
