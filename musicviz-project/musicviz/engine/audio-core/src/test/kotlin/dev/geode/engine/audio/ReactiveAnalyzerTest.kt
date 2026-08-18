package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The product requirement, asserted end to end.
 *
 * The engine was reported as "not audio reactive". Replicating the legacy
 * chain over pink noise — the spectral shape of real music — showed why: on a
 * normal master the bass/mid/treble drivers sat at 0.076/0.020/0.066 of their
 * nominal 0..1 range, and on a quiet master the mid and treble drivers were
 * identically 0. Every scene multiplies those by a drive parameter and expects
 * a signal that spans its range.
 *
 * These tests are that measurement, turned around: the same signals, the same
 * three drivers, and the ranges they must now reach. They are deliberately
 * written against the numbers from the bug report rather than against whatever
 * the implementation happens to produce.
 */
class ReactiveAnalyzerTest {
    private val sampleRate = 48_000
    private val fftSize = 2048
    private val hopRateHz = 62.5f
    private val dt = 1f / hopRateHz

    private fun analyzer() =
        ReactiveAnalyzer(
            bandCount = 64,
            fftSize = fftSize,
            sampleRateHz = sampleRate,
            hopRateHz = hopRateHz,
        )

    /** Voss-McCartney pink noise: power density falling as 1/f, like music. */
    private class Pink(
        seed: Int,
    ) {
        private val random = Random(seed)
        private val rows = FloatArray(16) { random.nextFloat() * 2f - 1f }
        private var counter = 0

        fun next(): Float {
            counter++
            for (r in rows.indices) {
                if (counter % (1 shl r) == 0) {
                    rows[r] = random.nextFloat() * 2f - 1f
                    break
                }
            }
            var sum = 0f
            for (v in rows) sum += v
            return sum / rows.size
        }
    }

    /** Hop between analysis windows, in samples — windows overlap heavily. */
    private val hopSamples = (sampleRate / hopRateHz).toInt()

    /**
     * A minimal piece of music: a pink-noise bed, a kick every half second
     * (120 BPM) and a hat on the off-eighths.
     *
     * Pink noise alone is stationary, so its band levels genuinely do not move
     * — asserting that they do would be asserting a physical falsehood. The
     * transients are what a driver is supposed to follow, so the material has
     * to contain some.
     */
    private class Material(
        private val gain: Float,
        private val sampleRate: Int,
        seed: Int,
        private val withDrums: Boolean = true,
    ) {
        private val pink = Pink(seed)
        private val noise = Random(seed + 1)

        fun sample(n: Int): Float {
            var v = pink.next() * gain
            if (withDrums) {
                val kickPhase = n % (sampleRate / 2)
                if (kickPhase < 4000) {
                    v += gain * 2.2f * sin(2.0 * PI * 60.0 * kickPhase / sampleRate).toFloat() *
                        exp(-kickPhase / 1200.0).toFloat()
                }
                val hatPhase = (n + sampleRate / 8) % (sampleRate / 4)
                if (hatPhase < 900) {
                    v += gain * 1.2f * (noise.nextFloat() * 2f - 1f) * exp(-hatPhase / 260.0).toFloat()
                }
            }
            return v
        }
    }

    /**
     * Runs [frames] analysis frames over [material] through a sliding window,
     * advancing the sample clock by one hop per frame — the way the live
     * analysis loop reads the ring. Returns the per-frame driver readings.
     */
    private fun drive(
        gain: Float,
        frames: Int = 400,
        seed: Int = 7,
        withDrums: Boolean = true,
        analyzer: ReactiveAnalyzer = analyzer(),
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val material = Material(gain, sampleRate, seed, withDrums)
        val buffer = FloatArray(fftSize)
        var sampleClock = 0
        val bass = FloatArray(frames)
        val mid = FloatArray(frames)
        val treble = FloatArray(frames)
        repeat(frames) { f ->
            System.arraycopy(buffer, hopSamples, buffer, 0, fftSize - hopSamples)
            for (i in fftSize - hopSamples until fftSize) buffer[i] = material.sample(sampleClock++)
            analyzer.analyze(buffer, dt)
            bass[f] = analyzer.bass
            mid[f] = analyzer.mid
            treble[f] = analyzer.treble
        }
        return Triple(bass, mid, treble)
    }

    private fun settled(values: FloatArray) = values.copyOfRange(values.size / 2, values.size)

    /**
     * The headline failure: on a normal master the drivers must live in the
     * usable part of their range, not in the bottom tenth.
     */
    @Test
    fun `an ordinary master drives the whole range, not the bottom tenth`() {
        val (bass, mid, treble) = drive(gain = 0.25f)
        for ((name, values) in listOf("bass" to bass, "mid" to mid, "treble" to treble)) {
            val tail = settled(values)
            val peak = tail.max()
            assertTrue("$name peaked at only $peak", peak > 0.5f)
        }
    }

    /**
     * The second failure: at -30 dBFS the legacy mid and treble drivers were
     * exactly zero. Nothing may be dead on a quiet master.
     */
    @Test
    fun `a quiet master drives the same range as a loud one`() {
        val loud = drive(gain = 0.25f)
        val quiet = drive(gain = 0.008f)
        for (i in 0..2) {
            val loudTail = settled(loud.toList()[i])
            val quietTail = settled(quiet.toList()[i])
            val name = listOf("bass", "mid", "treble")[i]
            assertTrue("$name is dead when quiet: peak ${quietTail.max()}", quietTail.max() > 0.5f)
            assertEquals(
                "$name mean differs between masters",
                loudTail.average(),
                quietTail.average(),
                0.12,
            )
        }
    }

    /** The treble channel specifically: it was the flattest of the three. */
    @Test
    fun `the treble driver actually moves on real material`() {
        val (_, _, treble) = drive(gain = 0.25f)
        val tail = settled(treble)
        assertTrue("treble spanned only ${tail.max() - tail.min()}", tail.max() - tail.min() > 0.15f)
    }

    @Test
    fun `silence settles everything to zero`() {
        val analyzer = analyzer()
        val buffer = FloatArray(fftSize)
        repeat(200) { analyzer.analyze(buffer, dt) }
        assertEquals(0f, analyzer.bass, 1e-6f)
        assertEquals(0f, analyzer.mid, 1e-6f)
        assertEquals(0f, analyzer.treble, 1e-6f)
        assertEquals(0f, analyzer.rms, 1e-6f)
        assertTrue("fired a beat on silence", !analyzer.beat)
    }

    @Test
    fun `every published driver stays inside its documented range`() {
        val analyzer = analyzer()
        val pink = Pink(11)
        val buffer = FloatArray(fftSize)
        repeat(300) { f ->
            for (i in 0 until fftSize) buffer[i] = pink.next() * if (f % 40 == 0) 2f else 0.2f
            analyzer.analyze(buffer, dt)
            for (b in analyzer.bands) assertTrue("band out of range: $b", b in 0f..1f)
            for (
            (name, v) in
            listOf(
                "bass" to analyzer.bass,
                "mid" to analyzer.mid,
                "treble" to analyzer.treble,
                "rms" to analyzer.rms,
                "centroid" to analyzer.centroid,
                "onset" to analyzer.onset,
                "beatStrength" to analyzer.beatStrength,
                "transient" to analyzer.transient,
                "beatPhase" to analyzer.beatPhase,
                "pulseConfidence" to analyzer.pulseConfidence,
                "tempoStability" to analyzer.tempoStability,
                "barPhase" to analyzer.barPhase,
                "downbeatConfidence" to analyzer.downbeatConfidence,
                "macroEnergy" to analyzer.macroEnergy,
                "kick" to analyzer.kick,
                "snare" to analyzer.snare,
                "hat" to analyzer.hat,
            )
            ) {
                assertTrue("$name out of range: $v", v in 0f..1f)
            }
        }
    }

    /** A kick pattern must reach the low-band channel and find its tempo. */
    @Test
    fun `a four-on-the-floor kick is detected and timed`() {
        val analyzer = analyzer()
        val buffer = FloatArray(fftSize)
        var sampleClock = 0
        var kicks = 0
        var beats = 0
        repeat(900) {
            System.arraycopy(buffer, hopSamples, buffer, 0, fftSize - hopSamples)
            for (i in fftSize - hopSamples until fftSize) {
                // 120 BPM: a kick every half second.
                val phase = sampleClock % (sampleRate / 2)
                buffer[i] =
                    if (phase < 4000) {
                        0.9f * sin(2.0 * PI * 60.0 * phase / sampleRate).toFloat() * exp(-phase / 1500.0).toFloat()
                    } else {
                        0f
                    }
                sampleClock++
            }
            analyzer.analyze(buffer, dt)
            if (analyzer.kick > 0f) kicks++
            if (analyzer.beat) beats++
        }
        assertTrue("low-band channel fired $kicks times", kicks > 5)
        assertTrue("fired $beats beats", beats > 5)
        assertTrue("read ${analyzer.bpm} BPM", abs(analyzer.bpm - 120f) < 12f)
    }

    /**
     * The bar. A kick pattern with a louder first-of-four should settle onto
     * a downbeat every fourth beat, hold the tempo steady, and never let the
     * bar phase leave its range.
     */
    @Test
    fun `an accented four-on-the-floor finds its downbeat and holds its tempo`() {
        val analyzer = analyzer()
        val buffer = FloatArray(fftSize)
        var sampleClock = 0
        var beats = 0
        var downbeats = 0
        repeat(1800) {
            System.arraycopy(buffer, hopSamples, buffer, 0, fftSize - hopSamples)
            for (i in fftSize - hopSamples until fftSize) {
                val phase = sampleClock % (sampleRate / 2)
                val beatIndex = sampleClock / (sampleRate / 2)
                val amp = if (beatIndex % 4 == 0) 0.95f else 0.55f
                buffer[i] =
                    if (phase < 4000) {
                        amp * sin(2.0 * PI * 60.0 * phase / sampleRate).toFloat() * exp(-phase / 1500.0).toFloat()
                    } else {
                        0f
                    }
                sampleClock++
            }
            analyzer.analyze(buffer, dt)
            if (analyzer.beat) beats++
            if (analyzer.downbeat) downbeats++
            assertTrue("barPhase out of range: ${analyzer.barPhase}", analyzer.barPhase in 0f..1f)
            assertTrue("beatInBar out of range: ${analyzer.beatInBar}", analyzer.beatInBar in 0..3)
        }
        assertTrue("fired $beats beats", beats > 12)
        assertTrue("fired $downbeats downbeats over $beats beats", downbeats >= 3)
        assertTrue("a downbeat is one beat in four, got $downbeats of $beats", downbeats <= beats / 2)
        assertTrue("tempo never settled: stability ${analyzer.tempoStability}", analyzer.tempoStability > 0.5f)
        assertTrue("no bar conviction: ${analyzer.downbeatConfidence}", analyzer.downbeatConfidence > 0.2f)
    }

    @Test
    fun `reset returns the analyzer to a fresh state`() {
        val analyzer = analyzer()
        val pink = Pink(3)
        val buffer = FloatArray(fftSize)
        repeat(120) {
            for (i in 0 until fftSize) buffer[i] = pink.next() * 0.5f
            analyzer.analyze(buffer, dt)
        }
        analyzer.reset()
        assertEquals(0f, analyzer.bass, 0f)
        assertEquals(0f, analyzer.bpm, 0f)
        assertEquals(0f, analyzer.warmup, 0f)
        assertTrue(analyzer.bands.all { it == 0f })
    }

    @Test
    fun `a changed sample rate is accepted mid-stream`() {
        val analyzer = analyzer()
        val pink = Pink(5)
        val buffer = FloatArray(fftSize)
        repeat(60) {
            for (i in 0 until fftSize) buffer[i] = pink.next() * 0.3f
            analyzer.analyze(buffer, dt)
        }
        analyzer.sampleRateHz = 16_000
        repeat(60) {
            for (i in 0 until fftSize) buffer[i] = pink.next() * 0.3f
            analyzer.analyze(buffer, dt)
        }
        assertTrue("bass read ${analyzer.bass}", analyzer.bass in 0f..1f)
    }
}
