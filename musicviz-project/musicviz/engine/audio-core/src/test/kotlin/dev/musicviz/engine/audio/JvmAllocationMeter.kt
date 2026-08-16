package dev.musicviz.engine.audio

import java.lang.management.ManagementFactory

/**
 * Bytes this thread has allocated, from HotSpot's `getThreadAllocatedBytes`.
 *
 * Direct rather than reflective, unlike `:engine:audio-android`'s copy: this is
 * a plain JVM module, so `java.lang.management` is on the compile classpath.
 * The Android module cannot do that — `android.jar` carries no such package —
 * which is the whole reason there are two.
 */
internal object JvmAllocationMeter {
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Suppress("DEPRECATION")
    fun bytes(): Long = bean.getThreadAllocatedBytes(Thread.currentThread().id)

    /** Bytes [block] allocates per run, after an equal warm-up pass. */
    inline fun perRun(
        runs: Int,
        block: () -> Unit,
    ): Double {
        repeat(runs) { block() }
        val before = bytes()
        repeat(runs) { block() }
        return (bytes() - before).toDouble() / runs
    }
}
