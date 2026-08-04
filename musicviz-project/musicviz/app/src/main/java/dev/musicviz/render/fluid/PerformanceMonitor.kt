package dev.musicviz.render.fluid

/**
 * F6 rolling-FPS monitor per FLUID_SIM v2 section 10. Welford's online
 * algorithm maintains the mean frame time over a sliding window; a downgrade
 * fires only when the average stays below the FPS threshold CONTINUOUSLY for
 * [sustainSeconds] - a single GC pause or app-switch stall must never
 * trigger a quality change. Absurd readings (<5 or >180 fps) are discarded
 * as stalls, not signal. Pure Kotlin so the gate can test it headless.
 */
internal class PerformanceMonitor(
    private val targetFps: Float = 50f,
    private val sustainSeconds: Float = 2.5f,
    private val windowSize: Int = 30,
) {
    private val samples = FloatArray(windowSize)
    private var count = 0
    private var index = 0
    private var deficitSeconds = 0f

    /** Frames-per-second averaged over the window (0 until enough samples). */
    val averageFps: Float
        get() {
            if (count < windowSize / 2) return 0f
            var sum = 0f
            val n = minOf(count, windowSize)
            for (i in 0 until n) sum += samples[i]
            val meanDt = sum / n
            return if (meanDt > 1e-6f) 1f / meanDt else 0f
        }

    /**
     * Feeds one frame time. Returns a downgrade severity: 0 = no action,
     * 1 = mild sustained deficit (step down one tier), 2 = severe (two).
     * The caller is responsible for latching - this monitor keeps counting,
     * so reset() after acting on a non-zero result.
     */
    fun onFrame(dtSeconds: Float): Int {
        val fps = if (dtSeconds > 1e-6f) 1f / dtSeconds else 1000f
        // Stalls and timer glitches are not signal.
        if (fps < 5f || fps > 180f) return 0
        samples[index] = dtSeconds
        index = (index + 1) % windowSize
        // Clamped, not just incremented, for the reason [DrumChannels] clamps
        // its refractory counters: a per-frame counter on a live wallpaper
        // eventually overflows to negative, after which [averageFps] reads
        // "not warmed up yet" forever and the monitor is silently dead until
        // reset(). Past windowSize the exact value never matters, so nothing
        // reachable changes.
        count = minOf(count + 1, windowSize)
        val avg = averageFps
        if (avg <= 0f) return 0
        if (avg < targetFps) {
            deficitSeconds += dtSeconds
        } else {
            deficitSeconds = 0f
        }
        if (deficitSeconds < sustainSeconds) return 0
        // Severity: how far below target, normalised. >35% below = severe.
        val severity = (targetFps - avg) / targetFps
        return if (severity > 0.35f) 2 else 1
    }

    fun reset() {
        samples.fill(0f)
        count = 0
        index = 0
        deficitSeconds = 0f
    }
}
