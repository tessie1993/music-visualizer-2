package dev.geode

import dev.geode.export.ClipEdit
import dev.geode.export.ClipLook
import dev.geode.export.ExportRatio
import dev.geode.export.StudioClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Studio's edit model: the arithmetic behind the trim readout, the "would
 * this export change anything" check, and the rule that a look writes ordinary
 * slider values rather than becoming a mode.
 *
 * Deliberately no Media3 objects here. [ClipEdit.videoEffects] needs a GL
 * context to be worth asserting about; everything in this file is the part
 * that decides WHAT to build, which is where the arithmetic mistakes live.
 */
class ClipEditTest {
    private val minute = 60_000L

    @Test
    fun `an untrimmed edit keeps the whole clip`() {
        assertEquals(minute, ClipEdit().trimmedMs(minute))
    }

    @Test
    fun `an out-point of zero means the end`() {
        // Stored as 0 rather than as the duration so a clip whose length is
        // re-read later is not trimmed by a stale number.
        assertEquals(50_000L, ClipEdit(startMs = 10_000L).trimmedMs(minute))
    }

    @Test
    fun `an out-point past the end is clamped, not trusted`() {
        assertEquals(minute, ClipEdit(endMs = 10 * minute).trimmedMs(minute))
    }

    @Test
    fun `an inverted trim keeps nothing rather than a negative length`() {
        assertEquals(0L, ClipEdit(startMs = 40_000L, endMs = 10_000L).trimmedMs(minute))
    }

    @Test
    fun `speed changes the output length, not the trim`() {
        val edit = ClipEdit(startMs = 0L, endMs = 30_000L, speed = 2f)
        assertEquals(30_000L, edit.trimmedMs(minute))
        assertEquals(15_000L, edit.outputMs(minute))
        assertEquals(60_000L, edit.copy(speed = 0.5f).outputMs(minute))
    }

    @Test
    fun `an untouched edit is an identity, and any one change is not`() {
        assertTrue(ClipEdit().isIdentity(minute))
        // An out-point AT the duration is still no trim.
        assertTrue(ClipEdit(endMs = minute).isIdentity(minute))
        assertFalse(ClipEdit(startMs = 1L).isIdentity(minute))
        assertFalse(ClipEdit(contrast = 0.1f).isIdentity(minute))
        assertFalse(ClipEdit(speed = 1.5f).isIdentity(minute))
        assertFalse(ClipEdit(ratio = ExportRatio.R9_16).isIdentity(minute))
        assertFalse(ClipEdit(mute = true).isIdentity(minute))
        assertFalse(ClipEdit(caption = "hi").isIdentity(minute))
        assertFalse(ClipEdit(monochrome = true).isIdentity(minute))
    }

    @Test
    fun `a look writes the grade and leaves everything else alone`() {
        val edited = ClipEdit(startMs = 5_000L, speed = 2f, caption = "keep me", mute = true)
        val punched = ClipLook.PUNCH.applyTo(edited)
        assertEquals(5_000L, punched.startMs)
        assertEquals(2f, punched.speed, 0f)
        assertEquals("keep me", punched.caption)
        assertTrue(punched.mute)
        assertTrue(punched.contrast > 0f)
        assertTrue(punched.saturation > 0f)
    }

    @Test
    fun `As shot puts the grade back to neutral`() {
        val graded = ClipLook.NEON.applyTo(ClipEdit())
        assertFalse(graded.isIdentity(minute))
        val neutral = ClipLook.NONE.applyTo(graded)
        assertTrue(neutral.isIdentity(minute))
    }

    @Test
    fun `Mono and Invert are exclusive of each other`() {
        val mono = ClipLook.MONO.applyTo(ClipEdit())
        assertTrue(mono.monochrome)
        assertFalse(mono.invert)
        val inverted = ClipLook.INVERT.applyTo(mono)
        assertFalse(inverted.monochrome)
        assertTrue(inverted.invert)
    }

    @Test
    fun `the trim reaches Media3 as a clipping configuration`() {
        val clipping = ClipEdit(startMs = 3_000L, endMs = 9_000L).clipping()
        assertEquals(3_000L, clipping.startPositionMs)
        assertEquals(9_000L, clipping.endPositionMs)
    }

    @Test
    fun `a clip summarises only what it knows`() {
        assertEquals(
            "1080×1920 · 0:24 · 18 MB",
            StudioClip("u", "n", 24_000L, 18L * 1024 * 1024, 1080, 1920).summary(),
        )
        // A picked document has no size or dimensions from MediaStore; the
        // summary must not read "0×0 · 0 MB".
        assertEquals("0:24", StudioClip("u", "n", 24_000L, 0L).summary())
    }
}
