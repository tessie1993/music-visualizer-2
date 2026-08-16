package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class SemitoneBandsTest {
    private val rate = 48_000

    private fun bands(fftSize: Int) = SemitoneBands(fftSize, rate)

    /** The bins one note owns. Allocates, so it belongs in a test and not in [SemitoneBands]. */
    private fun SemitoneBands.spanOf(index: Int): IntRange = firstBinOf(index) until firstBinOf(index) + binsIn(index)

    @Test
    fun `every bin belongs to exactly one note`() {
        // The partition is the whole reason the peak per note means anything:
        // a bin counted twice would make one partial light up two neighbouring
        // notes, and a bin counted zero times would put a hole in the middle
        // of the spectrum that no validity flag would explain.
        val layout = bands(4096)
        val owner = IntArray(4096 / 2 + 1) { -1 }
        for (i in 0 until layout.noteCount) {
            for (k in layout.spanOf(i)) {
                assertEquals("bin $k is claimed by note ${owner[k]} and ${layout.lowestNote + i}", -1, owner[k])
                owner[k] = layout.lowestNote + i
            }
        }
        // Contiguous: between the first and last claimed bin there is no gap.
        val claimed = owner.indices.filter { owner[it] >= 0 }
        assertTrue("nothing was claimed", claimed.size > 1000)
        assertEquals("the claimed bins are not contiguous", claimed.last() - claimed.first() + 1, claimed.size)
    }

    @Test
    fun `a note's span holds the bins nearest its own centre`() {
        val layout = bands(4096)
        val binHz = rate.toDouble() / 4096
        for (i in 0 until layout.noteCount) {
            for (k in layout.spanOf(i)) {
                // Each bin is nearer this note than any other, on the log axis
                // the layout is defined on: within half a semitone of it.
                val distance = abs(SemitoneBands.noteOf(k * binHz) - (layout.lowestNote + i))
                assertTrue("bin $k sits $distance semitones from its note", distance <= 0.5 + 1e-9)
            }
        }
    }

    @Test
    fun `note centres agree with the published MIDI anchors`() {
        val layout = bands(4096)
        // A4 = 440 Hz by definition; the rest follow from equal temperament.
        // These are the standard's own numbers, not this code's.
        assertEquals(440.0, layout.centerHz(69 - layout.lowestNote), 1e-9)
        assertEquals(261.6255653005986, layout.centerHz(60 - layout.lowestNote), 1e-9)
        assertEquals(27.5, layout.centerHz(21 - layout.lowestNote), 1e-9)
        assertEquals(69.0, SemitoneBands.noteOf(440.0), 1e-12)
        assertEquals(21.0, SemitoneBands.noteOf(27.5), 1e-12)
    }

    @Test
    fun `the crossover is exactly where a note's span becomes one bin wide`() {
        // The definition, tested tightly. Checking only that notes above it
        // resolve would accept any bound that is merely conservative here,
        // and the guarantee below rests on this being the real crossing.
        for (fftSize in listOf(256, 512, 1024, 4096, 8192)) {
            for (sampleRate in listOf(44_100, 48_000, 22_050)) {
                val f = SemitoneBands.resolutionCrossoverHz(fftSize, sampleRate)
                val span = f * (2.0.pow(0.5 / 12) - 2.0.pow(-0.5 / 12))
                assertEquals("$fftSize @ $sampleRate", sampleRate.toDouble() / fftSize, span, 1e-9)
            }
        }
    }

    @Test
    fun `above the crossover every note is resolved`() {
        // The guarantee that follows: a note's span is then at least one bin
        // wide, and a half-open interval that long always contains a bin
        // centre. Swept wide rather than over one stack, because a bound only
        // has to fail somewhere to be wrong.
        var checked = 0
        for (fftSize in listOf(256, 512, 1024, 2048, 4096, 8192)) {
            for (sampleRate in listOf(8_000, 22_050, 44_100, 48_000, 96_000)) {
                val layout = SemitoneBands(fftSize, sampleRate)
                val crossover = SemitoneBands.resolutionCrossoverHz(fftSize, sampleRate)
                for (i in 0 until layout.noteCount) {
                    if (layout.centerHz(i) < crossover || layout.centerHz(i) > sampleRate / 2.0) continue
                    val note = layout.lowestNote + i
                    assertTrue("$fftSize @ $sampleRate: note $note above the crossover has no bins", layout.isResolved(i))
                    checked++
                }
            }
        }
        assertTrue("the sweep checked nothing", checked > 1_000)
    }

    @Test
    fun `the resolved run is where the branch stack comes from`() {
        // The measured reason the plan runs a long window for bass: on the
        // short branches the bottom of the piano is simply not there. These
        // are this layout's own numbers at 48 kHz, locked so a change to the
        // binning has to be argued for rather than absorbed.
        val expected =
            mapOf(
                "transient" to 87..127,
                "general" to 75..127,
                "pitch" to 51..127,
                "harmony" to 39..127,
            )
        for (branch in AnalysisBranch.STACK) {
            val layout = bands(branch.windowFrames)
            assertEquals(branch.name, expected.getValue(branch.name), layout.fullyResolved)
        }
    }

    @Test
    fun `the run really is the boundary in both directions`() {
        val layout = bands(1024)
        val run = layout.fullyResolved
        assertFalse("the run is empty", run.isEmpty())
        for (note in run) assertTrue("note $note in the run is unresolved", layout.isResolved(note - layout.lowestNote))
        assertFalse(
            "the note below the run resolves, so the run starts too high",
            layout.isResolved(run.first - 1 - layout.lowestNote),
        )
    }

    @Test
    fun `below the run the notes are a patchwork, and that is reported honestly`() {
        // The example the class documents: even on the longest window, a bass
        // guitar's open E resolves and its open A does not. A layout that
        // interpolated would hide exactly this, and the melody it drew through
        // the gap would be invented.
        val layout = bands(8192)
        val pattern = (21..38).joinToString("") { if (layout.isResolved(it - layout.lowestNote)) "R" else "." }
        assertEquals(".R..R..R.R.R.R.RR.", pattern)
        assertTrue("open E (41 Hz) should resolve", layout.isResolved(28 - layout.lowestNote))
        assertFalse("open A (55 Hz) should not", layout.isResolved(33 - layout.lowestNote))
    }

    @Test
    fun `an unresolved note reports zero rather than its neighbours`() {
        // The whole design decision, as a test: a note the transform cannot
        // separate must not borrow a value from the notes either side of it.
        val layout = bands(512)
        val magnitudes = FloatArray(512 / 2 + 1) { 1f }
        val out = FloatArray(layout.noteCount)
        layout.fill(magnitudes, out)
        var unresolved = 0
        for (i in 0 until layout.noteCount) {
            if (layout.isResolved(i)) continue
            unresolved++
            assertEquals("note ${layout.lowestNote + i} invented a value", 0f, out[i], 0f)
        }
        assertTrue("no note was unresolved, so this proved nothing", unresolved > 20)
    }

    @Test
    fun `one partial reads the same in a narrow note and a wide one`() {
        // Band width varies twentyfold across this layout. A mean would make
        // the same partial read quiet in the treble and a sum would make noise
        // read loud there; the peak is the only one of the three that measures
        // the partial rather than the layout.
        val layout = bands(4096)
        val binHz = rate.toDouble() / 4096
        val narrow = layout.fullyResolved.first - layout.lowestNote
        val wide = 108 - layout.lowestNote
        assertEquals("the narrow note is not narrow", 1, layout.binsIn(narrow))
        assertTrue("the wide note is not wide (${layout.binsIn(wide)} bins)", layout.binsIn(wide) > 15)

        val out = FloatArray(layout.noteCount)
        for (index in listOf(narrow, wide)) {
            val magnitudes = FloatArray(4096 / 2 + 1)
            magnitudes[(layout.centerHz(index) / binHz).toInt()] = 0.75f
            layout.fill(magnitudes, out)
            assertEquals("note ${layout.lowestNote + index} scaled its partial by its width", 0.75f, out[index], 1e-7f)
        }
    }

    @Test
    fun `a note reads its loudest bin, not the last one it looked at`() {
        val layout = bands(4096)
        val binHz = rate.toDouble() / 4096
        val index = 108 - layout.lowestNote
        val magnitudes = FloatArray(4096 / 2 + 1)
        val span = layout.spanOf(index)
        for ((n, k) in span.withIndex()) magnitudes[k] = if (n == 1) 0.9f else 0.1f
        val out = FloatArray(layout.noteCount)
        layout.fill(magnitudes, out)
        assertEquals(0.9f, out[index], 0f)
    }

    @Test
    fun `notes past Nyquist resolve nothing`() {
        val layout = SemitoneBands(1024, 8_000)
        // 4 kHz is MIDI 107.7, so 108 and up have no bins to find.
        assertTrue(layout.isResolved(107 - layout.lowestNote))
        for (note in 108..127) {
            assertFalse("note $note claims bins above Nyquist", layout.isResolved(note - layout.lowestNote))
        }
    }

    @Test
    fun `a lower sample rate resolves lower notes at the same window`() {
        // Sanity on the direction of the whole relationship: resolution is
        // sampleRate/fftSize, so halving the rate buys an octave of bass.
        val fast = SemitoneBands(1024, 48_000).fullyResolved.first
        val slow = SemitoneBands(1024, 24_000).fullyResolved.first
        assertEquals("halving the rate should buy exactly one octave", 12, fast - slow)
    }

    @Test
    fun `filling a frame allocates nothing`() {
        val layout = bands(4096)
        val magnitudes = FloatArray(4096 / 2 + 1) { (it % 17) / 17f }
        val out = FloatArray(layout.noteCount)
        val perRun = JvmAllocationMeter.perRun(2_000) { layout.fill(magnitudes, out) }
        assertTrue("fill allocated $perRun bytes per frame", perRun < 1.0)
    }

    @Test
    fun `a malformed layout is refused at construction`() {
        for (bad in listOf({ SemitoneBands(1000, rate) }, { SemitoneBands(1024, 0) }, { SemitoneBands(1024, rate, 60, 60) })) {
            try {
                bad()
                throw AssertionError("a malformed layout was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue("the message says nothing useful", expected.message!!.isNotEmpty())
            }
        }
    }
}
