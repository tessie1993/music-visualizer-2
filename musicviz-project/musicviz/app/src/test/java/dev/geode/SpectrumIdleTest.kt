package dev.geode

import dev.geode.ui.BARS
import dev.geode.ui.SPECTRUM_TICK_MS
import dev.geode.ui.driveSpectrum
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Player's spectrum row does not run when there is nothing to sample.
 *
 * Its sampler publishes a FRESH `FloatArray` on every tick, and Compose's
 * default state policy compares by reference, so every tick invalidates the
 * Canvas whether or not a single bar moved. The loop ran `while (true)`
 * regardless of `live`, which meant twenty redraws a second, for as long as
 * the app was open, on the app's DEFAULT tab, with nothing playing - the one
 * state a music player spends most of its life in.
 *
 * `live` is the produceState key, so the composable re-launches the sampler
 * whenever it flips; the sampler's own job is to return immediately when it
 * is false, after one resting row so the bars are drawn at rest rather than
 * left at whatever the last note was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpectrumIdleTest {
    private val loud = FloatArray(64) { 0.9f }

    @Test
    fun `with nothing playing the sampler publishes one resting row and returns`() =
        runTest {
            val rows = mutableListOf<FloatArray>()
            driveSpectrum(live = false, bands = { loud }, emit = { rows += it })
            // Returning at all is the assertion: the call above would never
            // come back if the loop ran, and runTest would fail the test by
            // timing out.
            assertEquals(1, rows.size)
            assertEquals(BARS, rows.single().size)
            assertTrue("an idle row must be at rest, not the last thing heard", rows.single().all { it == 0f })
        }

    @Test
    fun `an idle sampler does not sample the analyzer at all`() =
        runTest {
            var reads = 0
            driveSpectrum(
                live = false,
                bands = {
                    reads++
                    loud
                },
                emit = {},
            )
            assertEquals("the idle path still polls the analyzer", 0, reads)
        }

    @Test
    fun `while something is playing it keeps publishing on its own clock`() =
        runTest {
            val rows = mutableListOf<FloatArray>()
            // backgroundScope, because the live sampler never returns: runTest
            // cancels it at the end instead of waiting for it.
            backgroundScope.launch { driveSpectrum(live = true, bands = { loud }, emit = { rows += it }) }
            advanceTimeBy(SPECTRUM_TICK_MS * 10 + 1)
            assertTrue("the live sampler stopped publishing: ${rows.size} rows", rows.size >= 10)
            assertTrue("the bars never rose on a loud signal", rows.last().any { it > 0.5f })
            // Each row is its own array, which is exactly why the idle case
            // had to stop rather than publish zeros forever.
            assertEquals(rows.size, rows.distinctBy { System.identityHashCode(it) }.size)
        }

    @Test
    fun `the bars fall back slowly and rise at once`() =
        runTest {
            // Unchanged behaviour, pinned here because the loop moved: peaks
            // stay legible between samples.
            var bands = loud
            val rows = mutableListOf<FloatArray>()
            backgroundScope.launch { driveSpectrum(live = true, bands = { bands }, emit = { rows += it }) }
            advanceTimeBy(SPECTRUM_TICK_MS + 1)
            assertTrue("a loud first sample must land in full", rows.first().all { it > 0.5f })
            bands = FloatArray(64)
            advanceTimeBy(SPECTRUM_TICK_MS + 1)
            val afterOneQuietTick = rows.last()[0]
            assertTrue("silence cut the bars instantly", afterOneQuietTick > 0f)
            assertTrue("the bars did not fall at all", afterOneQuietTick < rows.first()[0])
        }
}
