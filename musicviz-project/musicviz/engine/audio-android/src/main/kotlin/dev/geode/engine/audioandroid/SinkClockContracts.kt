package dev.geode.engine.audioandroid

/**
 * Frames silence skipping has removed since the current generation began,
 * counted in the tap's own input frames.
 *
 * The conversion factor is exactly 1, and that is structural rather than
 * lucky: `SilenceSkippingAudioProcessor` accumulates
 * `(bytesConsumed - bytesOutput) / (channelCount * 2)` against its **own**
 * input format, `TeeAudioProcessor.onConfigure` returns its input format
 * unchanged, and the two stages are adjacent. Media3's own consumer converts
 * the same number with the post-Sonic output rate, which happens to coincide
 * only because nothing in the default chain resamples.
 *
 * Only meaningful when read on the playback thread, inside a tap flush and
 * therefore before the silence-skipping stage's own flush zeroes it.
 */
fun interface SkippedFrameSource {
    fun skippedInputFramesSinceFlush(): Long
}

/**
 * The two sink-side events that bound a clock segment.
 *
 * Both are raised from `DefaultAudioSink`'s
 * `applyAudioProcessorPlaybackParametersAndSkipSilence`, immediately before
 * the pipeline flush that opens the next generation.
 *
 * Their **presence** carries as much as their argument, because media3 gates
 * them differently:
 *
 * - both are skipped unless the sink is in int-PCM, non-tunnelled, non-float
 *   output — but in those modes the chain's processors are not installed at
 *   all, so the tap raises no boundary either;
 * - the speed hook alone is additionally skipped when the sink applies
 *   playback parameters at the `AudioTrack` instead of at Sonic.
 *
 * So a boundary that raises [onSkipSilenceApplied] without [onSpeedApplied]
 * proves the chain's Sonic stage is not the thing changing speed, and any
 * slope this driver computed from it would be fiction. A boundary raising
 * neither is an ordinary flush — a seek or a route rebuild — and must not be
 * read as evidence about speed.
 */
interface SinkClockHooks {
    fun onSpeedApplied(speed: Float)

    /**
     * [enabled] is deliberately unused by the driver: the toggle says silence
     * *may* be removed from here on, not that any was. Only the counter knows
     * that. The hook's arrival is what carries information — see above.
     */
    fun onSkipSilenceApplied(enabled: Boolean)

    /** Called once per chain construction with the stage owning the skip counter. */
    fun attachSkippedFrames(source: SkippedFrameSource)

    /** A chain built to inspect stage order drives no clock. */
    object None : SinkClockHooks {
        override fun onSpeedApplied(speed: Float) = Unit

        override fun onSkipSilenceApplied(enabled: Boolean) = Unit

        override fun attachSkippedFrames(source: SkippedFrameSource) = Unit
    }
}

/**
 * The instant one capture generation ends and the next begins.
 *
 * [endedFrames] exists nowhere else by the time this returns — the tap has
 * already reset its counter, deliberately, so that no reader can pair a new
 * generation with an old frame count.
 */
fun interface TapBoundaryListener {
    fun onTapBoundary(
        ended: PcmTapFormat?,
        endedFrames: Long,
        begun: PcmTapFormat,
    )
}

/**
 * Evidence, not decoration. Every counter is a fault the driver chose to
 * survive rather than crash on — it runs inside `AudioProcessor.flush` on the
 * playback thread, where a thrown exception stops the music.
 *
 * Materialised on read from plain playback-thread counters, so a boundary
 * allocates nothing for telemetry. A cross-thread reader may therefore catch
 * a set that is a boundary out of step with itself; that is acceptable for
 * counters nothing steers on, and exact in a single-threaded test.
 */
data class SinkClockDiagnostics(
    val boundaries: Long = 0,
    val segmentsAppended: Long = 0,
    /** Boundaries that arrived with media3's parameter hooks attached. */
    val hookedBoundaries: Long = 0,
    /** Boundaries refused because the sink applies speed at the AudioTrack. */
    val refusedSpeedNotAuthoritative: Long = 0,
    /** Boundaries refused because the new format is unreadable. */
    val refusedUnreadableFormat: Long = 0,
    /**
     * Boundaries refused because a span already went unmodelled. Non-zero means
     * the clock has stopped for good, deliberately — see [anchorTrusted].
     */
    val refusedUntrustedAnchor: Long = 0,
    /**
     * Boundaries whose skip count exceeded the frames captured, so it was
     * discarded as a bad read rather than believed.
     */
    val discardedSkipExceedingFrames: Long = 0,
    /** Appends the clock rejected. Must stay 0. */
    val refusedByClockInvariant: Long = 0,
    /** Boundaries that captured frames across a span of unknown slope. */
    val unmeasuredBoundaries: Long = 0,
    /**
     * Whether the chain ever handed over its skip counter. False means the
     * driver was never wired to a chain at all — every anchor would then
     * ignore skipped silence, silently and forever.
     */
    val skippedFramesAttached: Boolean = false,
    /**
     * False once presentation time advanced across a span the driver could not
     * model. It never returns to true: resuming would map frames to times that
     * are early by the whole missed span, which is a confident answer where
     * "I do not know" is the truthful one.
     */
    val anchorTrusted: Boolean = true,
)
