package dev.geode.engine.audioandroid

/**
 * Bytes this thread has allocated, from HotSpot's `getThreadAllocatedBytes`.
 *
 * Reflective because an Android module compiles against `android.jar`, which
 * carries no `java.lang.management` — but these tests run on a real JDK, where
 * it is there. The alternative is a heap-delta guess, which cannot tell one
 * allocation per call from none.
 */
internal object AllocationMeter {
    private val bean =
        Class
            .forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean")
            .invoke(null)

    private val allocated =
        Class
            .forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.java)

    // `Thread.id` is deprecated on the Android compile classpath in favour of
    // `threadId()`, which does not exist on the JDK 17 these tests run on.
    @Suppress("DEPRECATION")
    fun bytes(): Long = allocated.invoke(bean, Thread.currentThread().id) as Long

    /**
     * Bytes [block] allocates per run, after a warm-up pass of the same length
     * so the measurement is of steady state rather than of the JIT arriving.
     */
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
