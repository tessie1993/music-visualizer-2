package dev.musicviz.wallpaper

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.sin

/**
 * Gentle synthetic audio for the live wallpaper when nothing is playing.
 *
 * A wallpaper is on screen for hours with the music app closed, so "no audio"
 * is its NORMAL state, not an edge case. Freezing on the last frame would look
 * broken and holding silence would leave most styles a black rectangle - the
 * fluid family already synthesizes idle motion for exactly this reason
 * (`WaterScene.idleFeatures`), and this does the same for every other style.
 *
 * Deliberately slow and beatless. A wallpaper that pulses like a track nobody
 * can hear is unsettling, and a fake beat would drive the flash and shake
 * paths - the ones the photosensitivity limits exist for - with no music to
 * justify them. The bands drift on incommensurable periods so the loop never
 * visibly repeats.
 */
class IdleFeatures(
    /**
     * Shaped like the real analyzer's output, not smaller.
     *
     * Every consumer scales by `bands.size`, so a shorter array is safe - but
     * it is not the same: `ShaderScene` resamples the bands into a fixed-width
     * audio texture and MilkDrop is handed the waveform verbatim, so a
     * quarter-length spectrum would make the idle look measurably coarser than
     * the same scene playing music. Matching the analyzer (64 bands, 128
     * waveform samples) means the wallpaper's idle frames are shaped exactly
     * like its live ones.
     */
    private val bandCount: Int = 64,
    private val waveformSize: Int = 128,
) {
    private var phase = 0f

    /** Advances by [dt] seconds and returns the breathing "audio". */
    fun tick(dt: Float): AudioFeatures {
        phase += dt
        val t = phase
        // Fresh arrays per tick, the same answer the live path reached with
        // `bands.copyOf()` in [dev.musicviz.analysis.FeatureExtractor]:
        // [AudioFeatures] is an immutable snapshot, and the GL thread keeps
        // reading the bands and waveform of whatever frame it grabbed for as
        // long as that frame takes to draw - routinely longer than the 16 ms
        // until the next tick. One shared pair rewritten in place handed it a
        // spectrum that was half this tick and half the next, on every idle
        // frame, which is the wallpaper's normal state rather than an edge
        // case.
        //
        // Allocating at 62 Hz is the price: ~800 bytes a tick of immediately
        // dead garbage, which is a rounding error against the frames the
        // renderer itself produces. A recycled ring of buffers would avoid it
        // only by guessing how many frames the renderer might still be
        // holding, and guessing low is the same tear again.
        val bands =
            FloatArray(bandCount) { i ->
                // Irrational-ish ratios per band: no common period, so the whole
                // field never lines up into a pulse the eye can latch onto.
                val rate = 0.07f + i * 0.0131f
                0.10f + 0.06f * sin(t * rate * TAU + i * 0.7f)
            }
        val waveform = FloatArray(waveformSize) { i -> 0.12f * sin(t * 0.9f + i * 0.19f) }
        return AudioFeatures(
            bands = bands,
            waveform = waveform,
            rms = 0.16f + 0.04f * sin(t * 0.11f * TAU),
            bass = 0.18f + 0.08f * sin(t * 0.09f * TAU),
            mid = 0.14f + 0.06f * sin(t * 0.13f * TAU + 1.7f),
            treble = 0.06f + 0.03f * sin(t * 0.17f * TAU + 3.1f),
            // Never a beat: a wallpaper must not drive the flash and shake
            // paths with music nobody is playing.
            beat = false,
        )
    }

    private companion object {
        const val TAU = 6.2831855f
    }
}
