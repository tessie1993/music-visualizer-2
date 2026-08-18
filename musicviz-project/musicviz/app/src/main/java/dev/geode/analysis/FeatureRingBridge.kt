package dev.geode.analysis

import dev.geode.engine.audio.FeatureFrame
import dev.geode.engine.audio.FeatureRing

/**
 * Maps [AudioFeatures] snapshots into [FeatureRing] rows and back — the
 * legacy bridge §5.6 asks for, so the ring can carry today's features
 * without waiting for the fixed-layout ABI.
 *
 * The ring carries the SCALAR channels and the events. The array channels —
 * bands, waveform, chroma — stay on the snapshot path for now: they are on
 * their way to being GPU resources (§5.4), and a ring row is not where a
 * 64-float array wants to live twice per hop. [snapshot] therefore returns
 * an [AudioFeatures] with empty arrays, which every consumer already treats
 * as "no reading" rather than as silence.
 *
 * Why this exists: a latest-state field loses every event between two
 * reads — at 30 fps over a 62.5 Hz hop, half of all one-frame beat flags.
 * Published through the ring and acquired with the span since the last
 * read, the same renderer sees every one of them exactly once. The span
 * semantics are proven IDENTICAL to `FeatureTimeline.featuresAt`'s
 * peak-hold/OR by `FeatureRingBridgeTest` before any consumer switches;
 * kick/snare/hat additionally get the same max treatment, which the
 * timeline never offered them.
 *
 * One instance per writer; the scratch rows make [publish] allocation-free.
 */
class FeatureRingBridge {
    private val continuousScratch = FloatArray(CONTINUOUS_SLOTS)
    private val eventScratch = FloatArray(EVENT_SLOTS)

    /** Publishes one snapshot's scalars and events at [sampleIndex]. */
    fun publish(
        ring: FeatureRing,
        sampleIndex: Long,
        features: AudioFeatures,
    ) {
        val c = continuousScratch
        c[SLOT_RMS] = features.rms
        c[SLOT_BASS] = features.bass
        c[SLOT_MID] = features.mid
        c[SLOT_TREBLE] = features.treble
        c[SLOT_CENTROID] = features.centroid
        c[SLOT_BEAT_PHASE] = features.beatPhase
        c[SLOT_PULSE_CONFIDENCE] = features.pulseConfidence
        c[SLOT_BPM] = features.bpm
        c[SLOT_MACRO_ENERGY] = features.macroEnergy
        c[SLOT_STEREO_WIDTH] = features.stereoWidth
        c[SLOT_STEREO_CORRELATION] = features.stereoCorrelation
        c[SLOT_STEREO_PAN] = features.stereoPan
        val e = eventScratch
        e[EVENT_BEAT] = if (features.beat) 1f else 0f
        e[EVENT_ONSET] = features.onset
        e[EVENT_FLUX] = features.flux
        e[EVENT_BEAT_STRENGTH] = features.beatStrength
        e[EVENT_TRANSIENT] = features.transient
        e[EVENT_KICK] = features.kick
        e[EVENT_SNARE] = features.snare
        e[EVENT_HAT] = features.hat
        ring.publish(sampleIndex, c, e)
    }

    /** The scalar view of an acquisition; arrays deliberately empty. */
    fun snapshot(frame: FeatureFrame): AudioFeatures {
        val c = frame.continuous
        val e = frame.events
        return AudioFeatures(
            bands = EMPTY,
            waveform = EMPTY,
            rms = c[SLOT_RMS],
            bass = c[SLOT_BASS],
            mid = c[SLOT_MID],
            treble = c[SLOT_TREBLE],
            centroid = c[SLOT_CENTROID],
            beatPhase = c[SLOT_BEAT_PHASE],
            pulseConfidence = c[SLOT_PULSE_CONFIDENCE],
            bpm = c[SLOT_BPM],
            macroEnergy = c[SLOT_MACRO_ENERGY],
            stereoWidth = c[SLOT_STEREO_WIDTH],
            stereoCorrelation = c[SLOT_STEREO_CORRELATION],
            stereoPan = c[SLOT_STEREO_PAN],
            beat = e[EVENT_BEAT] > 0f,
            onset = e[EVENT_ONSET],
            flux = e[EVENT_FLUX],
            beatStrength = e[EVENT_BEAT_STRENGTH],
            transient = e[EVENT_TRANSIENT],
            kick = e[EVENT_KICK],
            snare = e[EVENT_SNARE],
            hat = e[EVENT_HAT],
        )
    }

    companion object {
        const val SLOT_RMS = 0
        const val SLOT_BASS = 1
        const val SLOT_MID = 2
        const val SLOT_TREBLE = 3
        const val SLOT_CENTROID = 4
        const val SLOT_BEAT_PHASE = 5
        const val SLOT_PULSE_CONFIDENCE = 6
        const val SLOT_BPM = 7
        const val SLOT_MACRO_ENERGY = 8
        const val SLOT_STEREO_WIDTH = 9
        const val SLOT_STEREO_CORRELATION = 10
        const val SLOT_STEREO_PAN = 11
        const val CONTINUOUS_SLOTS = 12

        const val EVENT_BEAT = 0
        const val EVENT_ONSET = 1
        const val EVENT_FLUX = 2
        const val EVENT_BEAT_STRENGTH = 3
        const val EVENT_TRANSIENT = 4
        const val EVENT_KICK = 5
        const val EVENT_SNARE = 6
        const val EVENT_HAT = 7
        const val EVENT_SLOTS = 8

        private val EMPTY = FloatArray(0)

        /** A ring shaped for this bridge's layout. */
        fun newRing(capacityFrames: Int = 512): FeatureRing = FeatureRing(CONTINUOUS_SLOTS, EVENT_SLOTS, capacityFrames)
    }
}
