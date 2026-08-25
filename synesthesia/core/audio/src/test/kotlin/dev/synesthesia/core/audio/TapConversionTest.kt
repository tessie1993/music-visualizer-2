package dev.synesthesia.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class TapConversionTest {

    @Test
    fun `s16 stereo downmixes to mono f32`() {
        val bytes = byteArrayOf(
            0x00, 0x10, 0x00, 0x20, // frame 1: L=4096, R=8192
            0x00, 0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte(), // frame 2: L=-4096, R=-1
        )
        val r = TapConversion.s16ToMonoF32(bytes, 0, bytes.size, channels = 2)
        assertEquals(2, r.frames)
        assertEquals((4096f + 8192f) / 2f / 32768f, r.mono[0], 1e-6f)
        assertEquals((-4096f + -1f) / 2f / 32768f, r.mono[1], 1e-6f)
    }

    @Test
    fun `mono passthrough keeps sign`() {
        val bytes = byteArrayOf(0x01.toByte(), 0x80.toByte()) // -32767
        val r = TapConversion.s16ToMonoF32(bytes, 0, 2, channels = 1)
        assertEquals(-32767f / 32768f, r.mono[0], 1e-6f)
    }
}
