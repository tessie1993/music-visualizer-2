package dev.musicviz

import dev.musicviz.audio.AiffExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class AiffParserTest {
    @Test
    fun extendedFloat80ParsesCommonSampleRates() {
        val r44100 = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        val r48000 = byteArrayOf(0x40, 0x0E, 0xBB.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0)
        val r96000 = byteArrayOf(0x40, 0x0F, 0xBB.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0)
        assertEquals(44100, AiffExtractor.parseExtendedFloat80(r44100, 0))
        assertEquals(48000, AiffExtractor.parseExtendedFloat80(r48000, 0))
        assertEquals(96000, AiffExtractor.parseExtendedFloat80(r96000, 0))
    }

    @Test
    fun commChunkParsesAiffAndSowt() {
        fun comm(
            bits: Int,
            compression: Int?,
        ): ByteArray {
            val base =
                byteArrayOf(0, 2) +
                    byteArrayOf(0, 0, 0x10, 0) +
                    byteArrayOf(0, bits.toByte()) +
                    byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
            return if (compression == null) {
                base
            } else {
                base +
                    byteArrayOf(
                        (compression ushr 24).toByte(),
                        (compression ushr 16).toByte(),
                        (compression ushr 8).toByte(),
                        compression.toByte(),
                    )
            }
        }

        val aiff = AiffExtractor.parseComm(comm(16, null), isAifc = false)
        assertEquals(2, aiff.channels)
        assertEquals(44100, aiff.sampleRate)
        assertEquals(16, aiff.bitsPerSample)
        assertEquals(androidx.media3.common.C.ENCODING_PCM_16BIT_BIG_ENDIAN, aiff.pcmEncoding)

        val sowt = AiffExtractor.parseComm(comm(16, 0x736F7774), isAifc = true)
        assertEquals(androidx.media3.common.C.ENCODING_PCM_16BIT, sowt.pcmEncoding)
    }
}
