package dev.geode.engine.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Phase 3 gate's machine-checkable halves, in one place.
 *
 * MASTER_PLAN's gate: no app-wide consumer switch until corpus accuracy
 * (the oracle suites), callback allocations, CPU budget, epoch behavior
 * (the ring suites) and live/offline parity (app-side) pass. This file
 * carries the two that had no standing test: the audio callback's write
 * path and the per-hop analysis path must not allocate, and the analysis
 * must fit its hop with room to spare — on THIS JVM, which is a proxy; the
 * on-device budget stays an open item in `STATUS.md` until hardware exists.
 */
class Phase3GateTest {
    @Test
    fun `the callback's ring write allocates nothing`() {
        val ring = SampleRing(capacityFrames = 1 shl 14, channelCount = 2)
        val block = FloatArray(512 * 2) { sin(it * 0.01).toFloat() }
        val perRun = JvmAllocationMeter.perRun(20_000) { ring.write(block, 512, 2) }
        assertTrue("ring write allocated $perRun bytes per call", perRun < 1.0)
    }

    @Test
    fun `one analysis hop allocates nothing`() {
        val analyzer = ReactiveAnalyzer()
        val window = FloatArray(2048) { (0.4 * sin(2.0 * PI * 220.0 * it / 48_000.0)).toFloat() }
        val perRun = JvmAllocationMeter.perRun(2_000) { analyzer.analyze(window, 0.016f) }
        assertTrue("analyze allocated $perRun bytes per hop", perRun < 1.0)
    }

    @Test
    fun `one analysis hop fits far inside its 16 ms budget`() {
        // A proxy measurement, not the device benchmark the phase owes: the
        // ceiling here is absurd on purpose (an eighth of the hop) so this
        // fails only on a real regression, never on machine weather. The
        // measured medians are recorded in STATUS.md as gate evidence.
        val analyzer = ReactiveAnalyzer()
        val window = FloatArray(2048) { (0.4 * sin(2.0 * PI * 220.0 * it / 48_000.0)).toFloat() }
        repeat(500) { analyzer.analyze(window, 0.016f) }
        val runs = LongArray(200)
        for (i in runs.indices) {
            val start = System.nanoTime()
            repeat(10) { analyzer.analyze(window, 0.016f) }
            runs[i] = (System.nanoTime() - start) / 10
        }
        runs.sort()
        val medianNs = runs[runs.size / 2]
        println("Phase3Gate: analyze() median ${medianNs / 1000} us per hop on this JVM")
        assertTrue("one hop took ${medianNs / 1000} us", medianNs < 2_000_000L)
    }
}
