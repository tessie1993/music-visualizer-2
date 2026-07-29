package dev.musicviz

import dev.musicviz.analysis.AudioQualityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Headless checks for the Now Playing audio-quality readout model. */
class AudioQualityInfoTest {
    @Test
    fun classificationTable() {
        assertTrue(AudioQualityInfo.classify("audio/flac").lossless)
        assertEquals("FLAC", AudioQualityInfo.classify("audio/flac").codec)
        assertTrue(AudioQualityInfo.classify("audio/x-flac").lossless)
        assertTrue(AudioQualityInfo.classify("audio/alac").lossless)
        assertTrue(AudioQualityInfo.classify("audio/raw").lossless)
        assertTrue(AudioQualityInfo.classify("audio/wav").lossless)
        assertTrue(AudioQualityInfo.classify("audio/x-aiff").lossless)

        assertFalse(AudioQualityInfo.classify("audio/mpeg").lossless)
        assertEquals("MP3", AudioQualityInfo.classify("audio/mpeg").codec)
        assertFalse(AudioQualityInfo.classify("audio/mp4a-latm").lossless)
        assertEquals("AAC", AudioQualityInfo.classify("audio/mp4a-latm").codec)
        assertFalse(AudioQualityInfo.classify("audio/opus").lossless)
        assertEquals("Opus", AudioQualityInfo.classify("audio/opus").codec)
        assertFalse(AudioQualityInfo.classify("audio/vorbis").lossless)
        assertFalse(AudioQualityInfo.classify("audio/ogg").lossless)
        assertFalse(AudioQualityInfo.classify("audio/x-ms-wma").lossless)
    }

    @Test
    fun containerHintNamesRawPcm() {
        assertEquals("WAV", AudioQualityInfo.classify("audio/raw", container = "wav").codec)
        assertEquals("AIFF", AudioQualityInfo.classify("audio/raw", container = "aiff").codec)
        assertEquals("AIFF", AudioQualityInfo.classify("audio/raw", container = "aif").codec)
        assertEquals("PCM", AudioQualityInfo.classify("audio/raw").codec)
    }

    @Test
    fun nullMimeIsUnknownSafe() {
        val q = AudioQualityInfo.classify(null)
        assertEquals(AudioQualityInfo.UNKNOWN, q.codec)
        assertFalse(q.lossless)
        assertFalse(q.isBitPerfect)
        assertEquals("Unknown format", q.label())
        assertEquals("Source format unknown", q.explanation())
        // Fully-unknown numbers must not crash the formatting helpers.
        assertEquals("Unknown format", q.qualityLine())
    }

    @Test
    fun labelFormatting() {
        val flac =
            AudioQualityInfo.classify(
                "audio/flac",
                sourceSampleRateHz = 44100,
                sourceChannels = 2,
                bitDepth = 16,
            )
        assertEquals("FLAC · Lossless", flac.label())

        val mp3 = AudioQualityInfo.classify("audio/mpeg", bitrateBps = 320_000)
        assertEquals("MP3 · Lossy 320 kbps", mp3.label())

        val mp3NoRate = AudioQualityInfo.classify("audio/mpeg")
        assertEquals("MP3 · Lossy", mp3NoRate.label())
    }

    @Test
    fun qualityLineFormatting() {
        val same =
            AudioQualityInfo.classify(
                "audio/flac",
                sourceSampleRateHz = 44100,
                sourceChannels = 2,
                bitDepth = 16,
                outputSampleRateHz = 44100,
            )
        assertEquals("44.1 kHz · 16-bit · 2ch", same.qualityLine())

        val resampled = same.copy(outputSampleRateHz = 48000)
        assertEquals("44.1 kHz · 16-bit · 2ch → output 48 kHz", resampled.qualityLine())

        // Unknown bit depth (lossy) is hidden, never shown as 0-bit.
        val mp3 =
            AudioQualityInfo.classify(
                "audio/mpeg",
                sourceSampleRateHz = 44100,
                sourceChannels = 2,
            )
        assertEquals("44.1 kHz · 2ch", mp3.qualityLine())
    }

    @Test
    fun bitPerfectLogic() {
        val base =
            AudioQualityInfo.classify(
                "audio/flac",
                sourceSampleRateHz = 44100,
                sourceChannels = 2,
                bitDepth = 16,
                outputSampleRateHz = 44100,
                outputChannels = 2,
            )
        assertTrue(base.isBitPerfect)
        assertEquals("Playback path is bit-transparent; no resampling detected", base.explanation())

        // Resampled output is never bit-perfect.
        val resampled = base.copy(outputSampleRateHz = 48000)
        assertFalse(resampled.isBitPerfect)

        // Lossy sources are never bit-perfect, even without resampling.
        val mp3 =
            AudioQualityInfo.classify(
                "audio/mpeg",
                sourceSampleRateHz = 44100,
                outputSampleRateHz = 44100,
                bitrateBps = 192_000,
            )
        assertFalse(mp3.isBitPerfect)
        assertEquals("Source is lossy-compressed (MP3 at 192 kbps)", mp3.explanation())

        // Float output stays exact for <=24-bit integer sources (float32 has a
        // 24-bit mantissa) but not for 32-bit-integer sources.
        assertTrue(base.copy(outputFloat = true).isBitPerfect)
        assertTrue(base.copy(bitDepth = 24, outputFloat = true).isBitPerfect)
        assertFalse(base.copy(bitDepth = 32, outputFloat = true).isBitPerfect)

        // Channel-count change breaks bit-perfection; unknown counts pass.
        assertFalse(base.copy(outputChannels = 1).isBitPerfect)
        assertTrue(base.copy(outputChannels = 0).isBitPerfect)

        // No output report yet -> not claimed bit-perfect.
        assertFalse(base.copy(outputSampleRateHz = 0).isBitPerfect)
    }

    @Test
    fun kilohertzFormatting() {
        assertEquals("44.1 kHz", AudioQualityInfo.formatKhz(44100))
        assertEquals("48 kHz", AudioQualityInfo.formatKhz(48000))
        assertEquals("88.2 kHz", AudioQualityInfo.formatKhz(88200))
        assertEquals("192 kHz", AudioQualityInfo.formatKhz(192000))
    }
}
