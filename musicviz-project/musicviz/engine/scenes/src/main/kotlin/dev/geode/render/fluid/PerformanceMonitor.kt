package dev.geode.render.fluid

internal class PerformanceMonitor(
    private val targetFps: Float = 50f,
    private val sustainSeconds: Float = 2.5f,
    private val windowSize: Int = 30,
) {
    private val samples = FloatArray(windowSize)
    private var count = 0
    private var index = 0
    private var deficitSeconds = 0f

    val averageFps: Float
        get() {
            if (count < windowSize / 2) return 0f
            var sum = 0f
            val n = minOf(count, windowSize)
            for (i in 0 until n) sum += samples[i]
            val meanDt = sum / n
            return if (meanDt > 1e-6f) 1f / meanDt else 0f
        }

    fun onFrame(dtSeconds: Float): Int {
        val fps = if (dtSeconds > 1e-6f) 1f / dtSeconds else 1000f
        if (fps < 5f || fps > 180f) return 0
        samples[index] = dtSeconds
        index = (index + 1) % windowSize
        count = minOf(count + 1, windowSize)
        val avg = averageFps
        if (avg <= 0f) return 0
        if (avg < targetFps) {
            deficitSeconds += dtSeconds
        } else {
            deficitSeconds = 0f
        }
        if (deficitSeconds < sustainSeconds) return 0
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
