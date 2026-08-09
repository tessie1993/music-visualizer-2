package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.BandSmoother
import dev.musicviz.analysis.FeatureExtractor
import dev.musicviz.analysis.FftProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Headless reactivity harness: synthetic audio in, real analysis chain out,
 * numbers on the console.
 *
 * The whole analysis chain ([FftProcessor], [BandSmoother],
 * [FeatureExtractor], `PulseTracker`) is pure JVM with no Android imports, so
 * the thing the visuals actually ride can be measured here at full speed
 * instead of only being judged by eye on a device. That matters because
 * "the visualizer does not feel audio reactive" is not a debuggable
 * statement - it has to become "an off-grid hit reaches the screen at N% of
 * an on-grid one", which is what this file produces.
 *
 * Deliberately REPORT-ONLY on the reactivity metrics: the assertions below
 * cover only "the pipeline is alive at all", because the interesting numbers
 * are currently bad by design (see the tempo-grid suppression in
 * `PulseTracker`) and a red build here would say nothing new. As the drive
 * path is reworked, these measurements are the target to tighten against -
 * turn a metric into an assertion once it is meant to hold.
 *
 * Mirrors [dev.musicviz.analysis.AnalysisEngine]'s exact per-hop ordering
 * (process -> smooth -> extract) so what is measured is what ships; if that
 * order changes, change it here too or the numbers stop meaning anything.
 */
class AudioReactivityHarnessTest {
    /**
     * One analysis hop, wired exactly as `AnalysisEngine` wires it.
     *
     * [smoothing] is the one knob: with it off, [FeatureExtractor] is handed
     * the raw FFT bands instead of the [BandSmoother] output, which is how
     * the harness measures what the smoother costs the onset detector.
     */
    private class Pipeline(
        private val smoothing: Boolean = true,
    ) {
        val processor = FftProcessor()
        private val smoother = BandSmoother(processor.bandCount)
        private val extractor = FeatureExtractor(processor.bandCount, hopRateHz = HOP_HZ)
        private val raw = FloatArray(processor.bandCount)
        private val smoothed = FloatArray(processor.bandCount)
        private val waveform = FloatArray(WAVEFORM_SIZE)

        fun step(window: FloatArray): AudioFeatures {
            processor.process(window, SAMPLE_RATE, raw)
            val bands =
                if (smoothing) {
                    smoother.apply(raw, smoothed)
                    smoothed
                } else {
                    raw
                }
            val stride = window.size / waveform.size
            for (i in waveform.indices) waveform[i] = window[i * stride]
            return extractor.extract(bands, waveform, SAMPLE_RATE)
        }
    }

    /** Runs [signal] through [pipeline] hop by hop, newest-window-per-hop. */
    private fun run(
        signal: FloatArray,
        pipeline: Pipeline,
    ): List<AudioFeatures> {
        val out = ArrayList<AudioFeatures>()
        val window = FloatArray(pipeline.processor.fftSize)
        var pos = 0
        while (pos + window.size <= signal.size) {
            System.arraycopy(signal, pos, window, 0, window.size)
            out += pipeline.step(window)
            pos += HOP_SAMPLES
        }
        return out
    }

    /**
     * A percussive hit: exponentially decaying broadband noise, so every band
     * sees the transient rather than only whichever one a sine would land in.
     */
    private fun addHit(
        signal: FloatArray,
        atSample: Int,
        gain: Float,
        random: Random,
    ) {
        val length = (SAMPLE_RATE * HIT_SECONDS).toInt()
        for (i in 0 until length) {
            val at = atSample + i
            if (at >= signal.size) return
            val envelope = exp(-i.toFloat() / (SAMPLE_RATE * HIT_DECAY_SECONDS))
            signal[at] += (random.nextFloat() * 2f - 1f) * envelope * gain
        }
    }

    /** A steady click track, plus any extra hits at arbitrary offsets. */
    private fun clickTrack(
        seconds: Float,
        bpm: Float,
        gain: Float = 1f,
        extraHitsSec: List<Float> = emptyList(),
        stopAfterSec: Float = seconds,
    ): FloatArray {
        val signal = FloatArray((SAMPLE_RATE * seconds).toInt())
        val random = Random(SEED)
        val period = SAMPLE_RATE * 60f / bpm
        var beat = 0
        while (beat * period < SAMPLE_RATE * stopAfterSec) {
            addHit(signal, (beat * period).toInt(), gain, random)
            beat++
        }
        extraHitsSec.forEach { addHit(signal, (it * SAMPLE_RATE).toInt(), gain, random) }
        return signal
    }

    private fun hopToMs(hops: Int): Float = hops * 1000f / HOP_HZ

    @Test
    fun `measure end to end reactivity of the live analysis chain`() {
        val report = StringBuilder("\n=== AUDIO REACTIVITY HARNESS ===\n")
        report.append("sampleRate=$SAMPLE_RATE fftSize=2048 hop=${HOP_SAMPLES}smp (${HOP_HZ}Hz)\n")
        report.append("fft window = ${"%.1f".format(2048f * 1000 / SAMPLE_RATE)} ms\n\n")

        // ---- 1. Onset latency: one hit in silence, how long until it shows.
        val single = FloatArray((SAMPLE_RATE * 2f).toInt())
        addHit(single, SAMPLE_RATE, 1f, Random(SEED))
        val singleRun = run(single, Pipeline())
        val hitHop = SAMPLE_RATE / HOP_SAMPLES
        val peakHop = singleRun.indices.maxByOrNull { singleRun[it].bass } ?: 0
        val peakBass = singleRun[peakHop].bass
        val riseHop = singleRun.indices.firstOrNull { it >= hitHop && singleRun[it].bass >= peakBass * 0.5f } ?: -1
        report.append("[1] ONSET LATENCY (single hit in silence)\n")
        report.append("    hit at hop $hitHop; bass peaks at hop $peakHop (${"%.1f".format(hopToMs(peakHop - hitHop))} ms)\n")
        report.append("    bass reaches 50% of peak at +${"%.1f".format(hopToMs(riseHop - hitHop))} ms\n\n")

        // ---- 2. Off-grid survival: lock the grid, then hit between beats.
        // 120 BPM = 500 ms period; +250 ms is the maximally off-grid position.
        val lockSeconds = 14f
        val offGridAt = 12.25f
        val offGrid = clickTrack(lockSeconds, bpm = 120f, extraHitsSec = listOf(offGridAt))
        val offGridRun = run(offGrid, Pipeline())
        val offGridHop = (offGridAt * HOP_HZ).toInt()
        val onGridHop = (12.0f * HOP_HZ).toInt()
        fun peakNear(
            hop: Int,
            pick: (AudioFeatures) -> Float,
        ): Float =
            (hop - 1..hop + WINDOW_HOPS)
                .filter { it in offGridRun.indices }
                .maxOfOrNull { pick(offGridRun[it]) } ?: 0f
        val onGridBeat = (onGridHop - 1..onGridHop + WINDOW_HOPS).any { it in offGridRun.indices && offGridRun[it].beat }
        val offGridBeat = (offGridHop - 1..offGridHop + WINDOW_HOPS).any { it in offGridRun.indices && offGridRun[it].beat }
        val onGridMotion = peakNear(onGridHop) { it.motionImpulse }
        val offGridMotion = peakNear(offGridHop) { it.motionImpulse }
        report.append("[2] OFF-GRID ONSET SURVIVAL (120 BPM click, extra hit at +250ms)\n")
        report.append("    on-grid  hit -> beat=$onGridBeat  motionImpulse=${"%.3f".format(onGridMotion)}\n")
        report.append("    off-grid hit -> beat=$offGridBeat  motionImpulse=${"%.3f".format(offGridMotion)}\n")
        val survival = if (onGridMotion > 1e-6f) offGridMotion / onGridMotion * 100f else 0f
        report.append("    off-grid reaches ${"%.0f".format(survival)}% of an on-grid hit\n\n")

        // ---- 3. Coasting: does the grid keep firing after the audio stops?
        val coast = clickTrack(18f, bpm = 120f, stopAfterSec = 13f)
        val coastRun = run(coast, Pipeline())
        val silenceFrom = (14f * HOP_HZ).toInt()
        val beatsInSilence = coastRun.drop(silenceFrom).count { it.beat }
        val bpmInSilence = coastRun.lastOrNull()?.bpm ?: 0f
        report.append("[3] SILENCE COASTING (click stops at 13 s, measured from 14 s)\n")
        report.append("    beats flagged with no audio present: $beatsInSilence\n")
        report.append("    bpm still reported at end: ${"%.1f".format(bpmInSilence)}\n\n")

        // ---- 4. Level dependence: the AGC question, loud vs quiet material.
        fun meanBass(gain: Float): Float {
            val r = run(clickTrack(8f, bpm = 120f, gain = gain), Pipeline())
            return r.drop(r.size / 2).map { it.bass }.average().toFloat()
        }
        val loud = meanBass(1f)
        val quiet = meanBass(0.05f)
        report.append("[4] LEVEL DEPENDENCE (identical click track, 26 dB apart)\n")
        report.append("    mean bass @ gain 1.00 = ${"%.4f".format(loud)}\n")
        report.append("    mean bass @ gain 0.05 = ${"%.4f".format(quiet)}\n")
        report.append("    ratio = ${"%.2f".format(if (quiet > 1e-9f) loud / quiet else 0f)}x ")
        report.append("(MilkDrop's imm/long_avg AGC would put this near 1.0x)\n\n")

        // ---- 5. What the upstream smoother costs the onset detector.
        val sharp = clickTrack(8f, bpm = 120f)
        val smoothedRun = run(sharp, Pipeline(smoothing = true))
        val rawRun = run(sharp, Pipeline(smoothing = false))
        val smoothedFlux = smoothedRun.maxOf { it.flux }
        val rawFlux = rawRun.maxOf { it.flux }
        report.append("[5] TRANSIENT COST OF UPSTREAM SMOOTHING (peak flux)\n")
        report.append("    detector fed BandSmoother output = ${"%.4f".format(smoothedFlux)}\n")
        report.append("    detector fed raw FFT bands       = ${"%.4f".format(rawFlux)}\n")
        report.append("    smoothing retains ${"%.0f".format(if (rawFlux > 1e-9f) smoothedFlux / rawFlux * 100f else 0f)}% of peak transient\n")
        report.append("================================\n")
        println(report)

        // Liveness only - see the class doc for why the metrics above are not
        // assertions yet.
        assertTrue("pipeline produced no frames", singleRun.isNotEmpty())
        assertTrue("a loud broadband hit produced no band energy", peakBass > 0f)
        assertTrue("flux never responded to a click track", rawFlux > 0f)
        assertTrue("features contained NaN", singleRun.all { !it.bass.isNaN() && !it.rms.isNaN() })
        assertTrue("silence produced signal", abs(singleRun.first().rms) < 1f)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100

        /** `AnalysisEngine` polls with `delay(16)`, i.e. ~62.5 hops/second. */
        const val HOP_HZ = 62.5f
        const val HOP_SAMPLES = (SAMPLE_RATE / HOP_HZ).toInt()

        /** Matches the decimated waveform `AnalysisEngine` publishes. */
        const val WAVEFORM_SIZE = 128

        /** How far past a hit to look for its response, in hops. */
        const val WINDOW_HOPS = 6

        const val HIT_SECONDS = 0.05f
        const val HIT_DECAY_SECONDS = 0.012f
        const val SEED = 20260809
    }
}
