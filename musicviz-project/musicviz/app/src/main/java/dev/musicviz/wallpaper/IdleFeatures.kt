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
    bandCount: Int = 64,
    waveformSize: Int = 128,
) {
    private val bands = FloatArray(bandCount)
    private val waveform = FloatArray(waveformSize)
    private var phase = 0f

    /** Advances by [dt] seconds and returns the breathing "audio". */
    fun tick(dt: Float): AudioFeatures {
        phase += dt
        val t = phase
        for (i in bands.indices) {
            // Irrational-ish ratios per band: no common period, so the whole
            // field never lines up into a pulse the eye can latch onto.
            val rate = 0.07f + i * 0.0131f
            bands[i] = 0.10f + 0.06f * sin(t * rate * TAU + i * 0.7f)
        }
        for (i in waveform.indices) {
            waveform[i] = 0.12f * sin(t * 0.9f + i * 0.19f)
        }
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
