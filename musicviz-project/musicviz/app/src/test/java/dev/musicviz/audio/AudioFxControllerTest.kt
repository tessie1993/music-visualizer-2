package dev.musicviz.audio

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The attach/release state machine of [AudioFxController], and in particular
 * the distinction the Settings screen needs: `attached` (a real audio session
 * exists) versus `available` (the device actually granted an Equalizer).
 *
 * The two used to be one flag, and the UI told a capable device "Not
 * supported on this device" whenever nothing had played yet - ExoPlayer's
 * session id is UNSET until the audio sink first initializes, so a fresh
 * launch always started in that state.
 *
 * What cannot be pinned here: whether the effect constructors succeed. On
 * Robolectric they behave like they do on the many real devices that reject
 * them - which is exactly why every assertion below holds regardless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioFxControllerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `no session yet reads as not attached, never as a verdict on support`() {
        val fx = AudioFxController(ctx)
        assertFalse(fx.attached)
        assertFalse(fx.available)
        val state = fx.snapshot()
        assertFalse("snapshot must carry the no-session-yet state", state.attached)
        assertFalse(state.available)
    }

    @Test
    fun `attaching the unset session id is a no-op, not an attachment`() {
        val fx = AudioFxController(ctx)
        // 0 is C.AUDIO_SESSION_ID_UNSET: the sink has not initialized yet.
        fx.attach(0)
        assertFalse(fx.attached)
        assertFalse(fx.snapshot().attached)
    }

    @Test
    fun `a real session id reports attached even where the device refuses the effects`() {
        val fx = AudioFxController(ctx)
        fx.attach(1234)
        assertTrue("a positive session id must read as attached", fx.attached)
        assertTrue(fx.snapshot().attached)
        // Only now may available==false be read as "not supported".
        fx.release()
        assertFalse("release must drop the attachment", fx.attached)
        assertFalse(fx.snapshot().attached)
    }

    @Test
    fun `reattaching a new session keeps attached true`() {
        val fx = AudioFxController(ctx)
        fx.attach(1234)
        fx.attach(5678)
        assertTrue(fx.attached)
        fx.attach(0)
        assertFalse("the sink resetting to UNSET must read as detached again", fx.attached)
    }

    @Test
    fun `settings persist and survive into a fresh controller with no effects at all`() {
        val fx = AudioFxController(ctx)
        fx.setEnabled(true)
        fx.setBassBoost(300)
        fx.setLoudness(200)
        val state = AudioFxController(ctx).snapshot()
        assertTrue(state.enabled)
        assertEquals(300, state.bassBoost)
        assertEquals(200, state.loudness)
    }
}
