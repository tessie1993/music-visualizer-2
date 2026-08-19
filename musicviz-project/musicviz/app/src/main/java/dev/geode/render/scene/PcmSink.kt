package dev.geode.render.scene

/**
 * A scene that consumes raw mono PCM, the way MilkDrop always has.
 *
 * The analysed [dev.geode.analysis.AudioFeatures] carry a 64-point decimated
 * waveform — enough to modulate, too coarse to *draw*. The scenes that render
 * the signal itself (the beam's trace, the shader family's waveform texture
 * row, projectM's own analyzer) get the full-rate samples through this
 * interface instead, delivered once per frame by the renderer from the same
 * tap the analyzer reads.
 *
 * Contract: GL thread; [samples] is a buffer the caller reuses next frame, so
 * an implementation copies what it keeps and never holds the reference. PCM is
 * best-effort — offline export and idle playback deliver nothing — so every
 * consumer must keep rendering from `features.waveform` when no call arrives
 * between updates.
 */
interface PcmSink {
    fun acceptPcm(
        samples: FloatArray,
        count: Int,
    )
}

/** A chunk of fresh mono PCM samples; [count] entries of [data] are valid. */
class PcmChunk(
    val data: FloatArray,
    val count: Int,
)
