package dev.geode.analysis

import dev.geode.engine.audio.DrumChannels
import dev.geode.engine.audio.PulseReplay
import kotlin.math.sqrt

/** One analysis frame at a fixed hop, produced by offline analysis. */
data class TimelineFrame(
    val timeMs: Long,
    val features: AudioFeatures,
)

/**
 * Full-track analysis result: frames at a fixed hop plus track-level summary.
 * Used by the intelligence modes and by deterministic export.
 */
class FeatureTimeline(
    val frames: List<TimelineFrame>,
    val hopMs: Long,
    /** Estimated musical key, e.g. "A minor"; empty when unknown. */
    val key: String = "",
    /**
     * Rate the frames were produced at. Not derivable from [hopMs], which is
     * an integer-truncated 16 for the offline analyzer's true 60 Hz hop - and
     * [withBeatSensitivity] measures both the refractory window and the flux
     * history in frames, so a 62.5 vs 60 mix-up would shift every beat.
     */
    val hopRateHz: Float = 60f,
) {
    val durationMs: Long = frames.lastOrNull()?.timeMs ?: 0L
    val averageEnergy: Float = if (frames.isEmpty()) 0f else frames.map { it.features.rms }.average().toFloat()
    val averageCentroid: Float = if (frames.isEmpty()) 0f else frames.map { it.features.centroid }.average().toFloat()
    val bpm: Float = frames.lastOrNull()?.features?.bpm ?: 0f
    val beatDensity: Float =
        if (frames.isEmpty()) 0f else frames.count { it.features.beat } / (frames.size / 60f + 1e-6f)

    /**
     * The frames' ACTUAL spacing in ms (durationMs / (n-1)), not the nominal
     * [hopMs]: the offline hop is sampleRate/60 samples (16.67 ms), so
     * dividing by a truncated 16 ms would drift ~4% over a track.
     */
    private val frameSpacingMs: Double =
        if (frames.size > 1) durationMs.toDouble() / (frames.size - 1) else hopMs.toDouble()

    /**
     * Re-decides every frame's beat fields ([AudioFeatures.beat] plus the
     * graded [AudioFeatures.beatStrength] / [AudioFeatures.beatPhase] /
     * [AudioFeatures.pulseConfidence] / [AudioFeatures.macroEnergy]) from the
     * stored onset and rms curves at the given sensitivity, returning a new
     * timeline.
     *
     * This is why the analysis cache stores the raw flux rather than the
     * decided beats: changing "Beat sensitivity" or "Minimum gap between
     * beats" then applies to already-analysed tracks immediately, and an
     * exported video keeps matching what playback just showed - the live
     * path and this one are the same [PulseReplay] code fed the same
     * numbers in the same order.
     *
     * Timelines with no onset curve (analysed before it was stored, or
     * synthesised) are returned untouched: re-deciding from all-zero flux
     * would silently erase every beat.
     */
    fun withBeatSensitivity(
        beatSensitivity: Float,
        beatMinIntervalMs: Float,
    ): FeatureTimeline {
        if (frames.isEmpty()) return this
        val flux = FloatArray(frames.size) { frames[it].features.flux }
        if (flux.none { it > 0f }) return this
        val rms = FloatArray(frames.size) { frames[it].features.rms }
        val pulse = PulseReplay.decide(flux, rms, hopRateHz, beatSensitivity, beatMinIntervalMs)
        val out = ArrayList<TimelineFrame>(frames.size)
        var anyChanged = false
        for (i in frames.indices) {
            val fr = frames[i]
            val f = fr.features
            val unchanged =
                f.beat == pulse.beat[i] &&
                    f.beatStrength == pulse.strength[i] &&
                    f.transient == pulse.transient[i] &&
                    f.beatPhase == pulse.phase[i] &&
                    f.pulseConfidence == pulse.confidence[i] &&
                    f.macroEnergy == pulse.energy[i]
            out +=
                if (unchanged) {
                    fr
                } else {
                    anyChanged = true
                    fr.copy(
                        features =
                            f.copy(
                                beat = pulse.beat[i],
                                beatStrength = pulse.strength[i],
                                transient = pulse.transient[i],
                                beatPhase = pulse.phase[i],
                                pulseConfidence = pulse.confidence[i],
                                macroEnergy = pulse.energy[i],
                            ),
                    )
                }
        }
        // Nothing moved - hand back the receiver rather than an equal copy.
        // The beat-sensitivity slider re-runs this on every settle, and a drag
        // that returns to the value already applied is the common case; the
        // frames are shared either way, so this only saves the list, but it
        // also lets callers use identity to skip their own downstream work.
        if (!anyChanged) return this
        return FeatureTimeline(out, hopMs, key, hopRateHz)
    }

    /**
     * Fills in [AudioFeatures.kick] / [snare] / [hat] for every frame by
     * replaying band-limited onset detection over the stored band spectra.
     *
     * Needed because a cache entry reconstructs its frames from stored scalars
     * and the three channels are not among them - but the BANDS they are
     * derived from are, so no cache-format change is required and existing v2
     * entries keep working. The live and offline paths get these from
     * [dev.geode.engine.audio.ReactiveAnalyzer] directly and never need
     * this.
     *
     * A reconstruction, not a reproduction: the live channels are derived from
     * whitened band POWER, and what a cache entry stores is the normalized,
     * smoothed band levels a scene sees. The events land in the same places
     * because the same detector runs over the same bands, but the strengths
     * are graded against a different curve. Re-analysing the track is what
     * gets the exact values back.
     *
     * [sampleRateHz] defaults to 48 kHz because the cache header does not
     * carry it. The band ranges are logarithmic, so 44.1 vs 48 kHz moves every
     * boundary by well under one band; a hi-res 96 kHz source shifts them by a
     * few bands, which stays inside the same channel and is a far cheaper
     * error than invalidating every cached track to store one integer. A
     * caller that knows the true rate should pass it.
     *
     * Timelines with no bands are returned untouched.
     */
    fun withDrumChannels(sampleRateHz: Int = 48_000): FeatureTimeline {
        if (frames.isEmpty()) return this
        val bandCount = frames[0].features.bands.size
        if (bandCount == 0) return this
        val channels = DrumChannels(bandCount, hopRateHz, sampleRateHz)
        val out = ArrayList<TimelineFrame>(frames.size)
        var anyChanged = false
        for (fr in frames) {
            val f = fr.features
            // A frame whose band array is a different width cannot be stepped
            // through the same instance; leaving it alone keeps the replay
            // total rather than throwing on a damaged entry.
            if (f.bands.size != bandCount) {
                out += fr
                continue
            }
            channels.step(f.bands)
            if (channels.kick == f.kick && channels.snare == f.snare && channels.hat == f.hat) {
                out += fr
                continue
            }
            anyChanged = true
            out +=
                TimelineFrame(
                    fr.timeMs,
                    f.copy(kick = channels.kick, snare = channels.snare, hat = channels.hat),
                )
        }
        return if (anyChanged) FeatureTimeline(out, hopMs, key, hopRateHz) else this
    }

    /** Index of the frame nearest [timeMs], clamped to the timeline. */
    private fun indexAt(timeMs: Long): Int =
        if (frameSpacingMs > 0.0) {
            Math.round(timeMs / frameSpacingMs).toInt().coerceIn(0, frames.size - 1)
        } else {
            0
        }

    /**
     * Features for the half-open span `[timeMs, timeMs + spanMs)`.
     *
     * With [spanMs] <= 0 (the default, and what live playback uses) this is
     * the plain nearest-frame lookup it always was.
     *
     * A consumer that samples this 60 Hz timeline at a LOWER rate - an export
     * at 24 or 30 fps - only ever looks at every second or third frame, and
     * `AudioFeatures.beat` is exactly ONE frame wide by construction
     * ([FeatureExtractor.BeatGate] raises it for a single frame per onset). A
     * 30 fps export therefore never observed about half the track's beats: no
     * `uBeat`, no flash/shake, and no "Beat pulse" envelope on those. Passing
     * the exported frame's own duration as [spanMs] fixes that - the flag is
     * OR-ed across every timeline frame that exported frame is on screen for,
     * along with a peak-hold of the onset curve it was decided from
     * ([AudioFeatures.onset] / [AudioFeatures.flux]) and of the graded
     * [AudioFeatures.beatStrength], so strength and flag stay consistent
     * with each other.
     *
     * Everything CONTINUOUS - bands, waveform, rms/bass/mid/treble, centroid,
     * bpm - stays point-sampled at the nearest frame, exactly as before.
     * Averaging those over the span would low-pass every exported clip (and
     * averaging a waveform cancels its phase outright): that is a change of
     * character, not a bug fix. Impulses get a max, levels get a sample.
     *
     * Consecutive spans tile the timeline exactly - one span's last index is
     * the next span's first minus one - so no frame is observed twice and none
     * is skipped, and both ends clamp to the timeline.
     */
    fun featuresAt(
        timeMs: Long,
        spanMs: Long = 0L,
    ): AudioFeatures {
        if (frames.isEmpty()) return AudioFeatures.empty()
        val first = indexAt(timeMs)
        val f = frames[first].features
        if (spanMs <= 0L || frameSpacingMs <= 0.0) return f
        val last = (indexAt(timeMs + spanMs) - 1).coerceIn(first, frames.size - 1)
        if (last <= first) return f
        var beat = f.beat
        var onset = f.onset
        var flux = f.flux
        var strength = f.beatStrength
        var transient = f.transient
        for (i in first + 1..last) {
            val g = frames[i].features
            beat = beat || g.beat
            onset = maxOf(onset, g.onset)
            flux = maxOf(flux, g.flux)
            strength = maxOf(strength, g.beatStrength)
            transient = maxOf(transient, g.transient)
        }
        if (beat == f.beat && onset == f.onset && flux == f.flux && strength == f.beatStrength && transient == f.transient) return f
        return f.copy(beat = beat, onset = onset, flux = flux, beatStrength = strength, transient = transient)
    }

    /**
     * [featuresAt] plus track-position context (progress + section index)
     * for progression-driven scenes. [sections] is a detectSections() result
     * the caller computed once - recomputing per frame would be O(n) each.
     * Deterministic, so live playback and export agree exactly.
     *
     * [spanMs] is forwarded to [featuresAt]: an export passes the exported
     * frame's duration so a sub-60 fps render still observes every one-frame
     * beat flag. The progress/section context is taken at [timeMs] itself.
     */
    fun progressionAt(
        timeMs: Long,
        sections: List<Long>,
        spanMs: Long = 0L,
    ): AudioFeatures {
        val f = featuresAt(timeMs, spanMs)
        if (durationMs <= 0L) return f
        var idx = 0
        for (s in sections) {
            if (s <= timeMs) idx++ else break
        }
        return f.copy(
            progress = (timeMs.toFloat() / durationMs).coerceIn(0f, 1f),
            sectionIndex = idx,
            sectionCount = sections.size + 1,
        )
    }

    /**
     * Section boundaries (ms) from a novelty curve: distance between averaged
     * band vectors before/after each frame, peak-picked.
     */
    fun detectSections(
        windowFrames: Int = 90,
        minGapFrames: Int = 300,
    ): List<Long> {
        if (frames.size < windowFrames * 2) return emptyList()
        val novelty = FloatArray(frames.size)
        val bandCount = frames[0].features.bands.size
        val before = FloatArray(bandCount)
        val after = FloatArray(bandCount)
        for (i in windowFrames until frames.size - windowFrames) {
            java.util.Arrays.fill(before, 0f)
            java.util.Arrays.fill(after, 0f)
            for (w in 0 until windowFrames) {
                val fb = frames[i - 1 - w].features.bands
                val fa = frames[i + w].features.bands
                for (b in 0 until bandCount) {
                    before[b] += fb[b]
                    after[b] += fa[b]
                }
            }
            var dist = 0f
            for (b in 0 until bandCount) {
                val d = (after[b] - before[b]) / windowFrames
                dist += d * d
            }
            novelty[i] = sqrt(dist)
        }
        val mean = novelty.average().toFloat()
        val threshold = mean * 2f
        val boundaries = mutableListOf<Long>()
        var lastPeak = -minGapFrames
        for (i in 1 until novelty.size - 1) {
            val isPeak = novelty[i] > threshold && novelty[i] >= novelty[i - 1] && novelty[i] >= novelty[i + 1]
            if (isPeak && i - lastPeak >= minGapFrames) {
                boundaries += frames[i].timeMs
                lastPeak = i
            }
        }
        return boundaries
    }
}
