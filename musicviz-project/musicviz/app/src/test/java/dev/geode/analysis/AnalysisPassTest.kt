package dev.geode.analysis

import dev.geode.engine.audio.SampleRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * One analysis hop, driven a tick at a time.
 *
 * The worker loop runs on `Dispatchers.Default` behind a wall-clock deadline,
 * so nothing could reach the work it does. That was not a theoretical gap:
 * hard-wiring the stereo reading to `MONO`, and building the waveform out of
 * the side channel instead of the mid, both left the entire suite green.
 */
class AnalysisPassTest {
    private val rate = 48_000
    private val fftSize = AnalysisEngine.DEFAULT_FFT_SIZE

    private fun ring() = SampleRing(capacityFrames = 1 shl 16, channelCount = 2)

    /** [frames] of a tone, written as interleaved stereo with [sideGain] of anti-phase. */
    private fun SampleRing.writeTone(
        frames: Int,
        hz: Float = 440f,
        sideGain: Float = 0f,
    ) {
        val block = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val m = sin(2.0 * PI * hz * i / rate).toFloat() * 0.5f
            val s = sideGain * sin(2.0 * PI * 97.0 * i / rate).toFloat()
            block[i * 2] = m + s
            block[i * 2 + 1] = m - s
        }
        write(block, frames, 2)
    }

    private fun engineOn(ring: SampleRing) = AnalysisEngine(ring).also { it.sampleRateHz = rate }

    @Test
    fun `a tick with no window published publishes nothing`() {
        val engine = engineOn(ring())
        assertTrue("an empty ring must not yield a frame", !engine.Pass().tick())
        assertEquals(0f, engine.features.value.rms, 0f)
    }

    @Test
    fun `a tone raises the bands it occupies`() {
        val ring = ring()
        ring.writeTone(fftSize * 2)
        val engine = engineOn(ring)
        assertTrue(engine.Pass().tick())
        val bands = engine.features.value.bands
        assertTrue("no band responded to a 440 Hz tone", bands.any { it > 0.2f })
        assertTrue("every band responded, so this is not measuring a spectrum", bands.count { it > 0.2f } < bands.size)
    }

    @Test
    fun `the waveform is the mid signal, not the side one`() {
        // Side is anti-phase and much louder here, so a waveform built from it
        // is easy to tell apart from one built from the mid.
        val ring = ring()
        ring.writeTone(fftSize * 2, hz = 60f, sideGain = 0.4f)
        val engine = engineOn(ring)
        assertTrue(engine.Pass().tick())
        val waveform = engine.features.value.waveform
        assertEquals(AnalysisEngine.WAVEFORM_POINTS, waveform.size)
        assertTrue("the waveform is flat, so it came from neither channel", waveform.any { kotlin.math.abs(it) > 0.05f })

        val midOnly = ring()
        midOnly.writeTone(fftSize * 2, hz = 60f, sideGain = 0f)
        val plain = engineOn(midOnly)
        assertTrue(plain.Pass().tick())
        assertArrayEqualsWithin(
            "the side channel leaked into the waveform",
            plain.features.value.waveform,
            waveform,
        )
    }

    @Test
    fun `stereo content is measured, not reported as mono`() {
        val wide = ring().also { it.writeTone(fftSize * 2, sideGain = 0.5f) }
        val narrow = ring().also { it.writeTone(fftSize * 2, sideGain = 0f) }
        val wideEngine = engineOn(wide).also { assertTrue(it.Pass().tick()) }
        val narrowEngine = engineOn(narrow).also { assertTrue(it.Pass().tick()) }

        assertTrue(
            "the stereo field is not being measured at all",
            wideEngine.features.value.stereoWidth > narrowEngine.features.value.stereoWidth,
        )
        assertEquals("a signal with no side content is perfectly correlated", 1f, narrowEngine.features.value.stereoCorrelation, 1e-3f)
    }

    @Test
    fun `a mono source is not reported as decorrelated`() {
        // The trap SampleRing.sourceChannelCount exists for: a mono write
        // leaves the ring's second channel silent, and a downmix that averaged
        // it in would halve the mid and invent side content.
        val ring = ring()
        val block = FloatArray(fftSize * 2) { sin(2.0 * PI * 220.0 * it / rate).toFloat() * 0.5f }
        ring.write(block, block.size, 1)
        val engine = engineOn(ring)
        assertTrue(engine.Pass().tick())
        assertEquals(0f, engine.features.value.stereoWidth, 1e-6f)
        assertEquals(1f, engine.features.value.stereoCorrelation, 1e-6f)
    }

    private fun assertArrayEqualsWithin(
        why: String,
        expected: FloatArray,
        actual: FloatArray,
    ) {
        assertEquals(why, expected.size, actual.size)
        for (i in expected.indices) assertEquals(why, expected[i], actual[i], 1e-6f)
    }
}
