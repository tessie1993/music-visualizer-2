package dev.geode

import dev.geode.data.Preset
import dev.geode.data.PresetStore
import dev.geode.render.scene.SceneParams
import dev.geode.ui.PresetLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gate for shareable preset links.
 *
 * The app has no account, no server and no network permission, so a shared
 * preset has to BE the preset - carried inside the link. That makes two things
 * load-bearing: the round trip must be exact (a preset that arrives subtly
 * different is worse than one that fails to arrive), and the link has to
 * survive the journey through a chat app, which means short, URL-safe, and
 * tolerant of the whitespace and quote markers a paste drags along with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetLinkTest {
    private fun preset(params: SceneParams = SceneParams.DEFAULT) =
        Preset(
            name = "Deep Water",
            sceneId = "water",
            attack = 0.62f,
            decay = 0.14f,
            customShader = null,
            params = params,
        )

    @Test
    fun aLinkRoundTripsThePresetExactly() {
        val original =
            preset(
                SceneParams.DEFAULT.copy(
                    speed = 1.8f,
                    waterLiquid = 0.4f,
                    palette = 7,
                    kaleidoscope = true,
                    paletteBaseOverride = 0.31f,
                    customPaletteId = "mine",
                ),
            )
        val link = PresetLink.encode(PresetStore.toJson(original))
        val back = PresetStore.fromJson(PresetLink.decode(link)!!)
        assertEquals(original.name, back.name)
        assertEquals(original.sceneId, back.sceneId)
        assertEquals(original.attack, back.attack, 1e-4f)
        assertEquals(original.params, back.params)
    }

    @Test
    fun aLinkIsShortEnoughToPasteIntoAMessage() {
        val link = PresetLink.encode(PresetStore.toJson(preset()))
        assertTrue("a preset link is $link.length chars", link.length < 2_000)
        assertTrue(PresetLink.isPresetLink(link))
    }

    @Test
    fun theLinkBodyIsUrlSafe() {
        // Chat apps and browsers mangle '+' and '/' in what they treat as a
        // URL; the payload has to survive being treated as one.
        val link = PresetLink.encode(PresetStore.toJson(preset(SceneParams.DEFAULT.copy(speed = 2.7182818f))))
        val payload = link.removePrefix("geode://preset/")
        assertTrue("payload is not URL-safe: $payload", payload.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun aLinkIsFoundInsideThePastedMessageAroundIt() {
        // Nobody pastes the link alone: it arrives with a sender name, a quote
        // marker, or a trailing newline.
        val link = PresetLink.encode(PresetStore.toJson(preset()))
        val pasted = "sam: check this out\n$link\nnice right?"
        assertEquals(link, PresetLink.findIn(pasted))
        assertEquals("Deep Water", PresetStore.fromJson(PresetLink.decode(PresetLink.findIn(pasted)!!)!!).name)
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        val link = PresetLink.encode(PresetStore.toJson(preset()))
        assertTrue(PresetLink.decode("  $link  \n") != null)
    }

    @Test
    fun textThatIsNotALinkDecodesToNothingRatherThanThrowing() {
        // A paste button is pressed on whatever happens to be in the clipboard,
        // so failure is an ordinary outcome and must not be an exception.
        assertNull(PresetLink.decode(""))
        assertNull(PresetLink.decode("hello"))
        assertNull(PresetLink.decode("https://example.com/preset/abc"))
        assertNull(PresetLink.findIn("nothing to see"))
        assertFalse(PresetLink.isPresetLink("geodezz://preset/x"))
    }

    @Test
    fun aTruncatedLinkFailsCleanly() {
        // What a chat app that wraps long "URLs" actually does to one.
        val link = PresetLink.encode(PresetStore.toJson(preset()))
        assertNull(PresetLink.decode(link.substring(0, link.length / 2)))
        assertNull(PresetLink.decode(link + "!!!"))
    }

    @Test
    fun aPresetCarryingAShaderIsRecognisedAsTooLongForAMessage() {
        // Past the cap the caller shares the .json instead; the cap only earns
        // its place if a realistic shader preset actually exceeds it.
        // Pseudo-random so gzip cannot crush it: a real edited shader is
        // varied text, and 600 identical lines compress to almost nothing,
        // which would test the compressor rather than the cap.
        val rng = kotlin.random.Random(4)
        val shader = String(CharArray(12_000) { 'a' + rng.nextInt(26) })
        val link = PresetLink.encode(PresetStore.toJson(preset().copy(customShader = shader)))
        assertTrue("a big shader preset should exceed the link cap", link.length > PresetLink.MAX_LINK_LENGTH)
        // …and it must still be decodable, since the file path carries the
        // same JSON that the link would have.
        assertEquals(shader, PresetStore.fromJson(PresetLink.decode(link)!!).customShader)
    }
}
