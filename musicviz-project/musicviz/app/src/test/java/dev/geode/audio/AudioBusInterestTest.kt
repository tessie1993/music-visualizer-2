package dev.geode.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The demand gate on the analysis worker: [AudioBus] counts its consumers
 * (the app's screen, a visible wallpaper) and fires [AudioBus.onInterestChanged]
 * only on the edges, which is what lets PlaybackSession start the 62 Hz
 * worker exactly while someone is watching and stop it the moment nobody is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioBusInterestTest {
    @Test
    fun `interest fires on edges only and hasConsumers tracks the count`() {
        // The bus is process-global; start from wherever previous tests left
        // the count by draining it through the public API is not possible, so
        // this test measures EDGES relative to its own additions.
        var edges = 0
        AudioBus.onInterestChanged = { edges++ }
        try {
            val before = AudioBus.hasConsumers
            AudioBus.addConsumer()
            assertTrue(AudioBus.hasConsumers)
            val afterFirst = edges
            AudioBus.addConsumer()
            assertEquals("a second consumer is not an edge", afterFirst, edges)
            AudioBus.removeConsumer()
            assertEquals("dropping to one is not an edge", afterFirst, edges)
            AudioBus.removeConsumer()
            assertEquals("this test's consumers are gone again", before, AudioBus.hasConsumers)
            if (!before) {
                assertTrue("0->1 must fire", afterFirst >= 1)
                assertTrue("1->0 must fire", edges > afterFirst)
            }
        } finally {
            AudioBus.onInterestChanged = null
        }
    }

    @Test
    fun `published features go stale rather than sticking forever`() {
        AudioBus.clear()
        assertFalse(AudioBus.isLive)
        AudioBus.publish(dev.geode.analysis.AudioFeatures.empty())
        assertTrue("a fresh publish is live", AudioBus.isLive)
        AudioBus.clear()
        assertFalse("cleared is not live", AudioBus.isLive)
    }
}
