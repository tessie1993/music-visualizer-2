package dev.musicviz

import dev.musicviz.ui.PresetLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A preset link arrives through an exported ACTION_VIEW intent, so decode's
 * input is hostile by default. Gzip expands repetitive data ~1000:1, which
 * means a link of a few hundred KB can carry hundreds of MB - unbounded
 * inflation is an OOM crash any app on the device can trigger. Decode must
 * treat a bomb exactly like a corrupt link: null, no throw, no big allocation.
 */
class PresetLinkBombTest {
    /** JSON-shaped so a fixed decoder change can't dodge the test by sniffing shape. */
    private fun compressibleJson(padBytes: Int): String = "{\"name\":\"bomb\",\"pad\":\"" + "a".repeat(padBytes) + "\"}"

    @Test
    fun aGzipBombLinkIsRejectedAsNullNotAnOom() {
        // 64 MB of repeated text gzips to well under MAX_LINK_LENGTH-scale
        // payloads - small enough to arrive in an intent, 16x past the 4 MB
        // inflate cap. The bounded reader must stop at the cap, so this
        // returns null without ever materialising the 64 MB.
        val bomb = PresetLink.encode(compressibleJson(64 * 1024 * 1024))
        assertTrue("a bomb must be small on the wire to be a bomb", bomb.length < 200_000)
        assertNull(PresetLink.decode(bomb))
    }

    @Test
    fun aLinkJustUnderTheInflateCapStillDecodes() {
        // The cap is a safety bound, not a functional limit: content bigger
        // than any real preset but under 4 MB must still come through.
        val json = compressibleJson(1024 * 1024)
        assertEquals(json, PresetLink.decode(PresetLink.encode(json)))
    }

    @Test
    fun anAbsurdlyLongPayloadIsRejectedBeforeBase64Decoding() {
        // First gate: past 1 MB of Base64 there is no legitimate preset, so
        // the string is refused before it is copied into a byte array.
        assertNull(PresetLink.decode("musicviz://preset/" + "A".repeat(2 * 1024 * 1024)))
    }

    @Test
    fun anOrdinaryLinkStillRoundTrips() {
        val json = "{\"name\":\"Deep Water\",\"sceneId\":\"water\",\"attack\":0.62}"
        assertEquals(json, PresetLink.decode(PresetLink.encode(json)))
    }
}
