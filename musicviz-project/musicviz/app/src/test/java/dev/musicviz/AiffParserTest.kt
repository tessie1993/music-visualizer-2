package dev.musicviz

import dev.musicviz.audio.AiffExtractor
import dev.musicviz.audio.AiffPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

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

    @Test
    fun aiffPcmDecodesSowtSameAsBigEndian() {
        val samples = shortArrayOf(0x1234, -2, 257, Short.MIN_VALUE, Short.MAX_VALUE, 0)

        fun fourcc(s: String) = s.toByteArray(Charsets.US_ASCII)

        fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        fun int16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

        fun file(
            kind: String,
            compression: String?,
            pcm: ByteArray,
        ): ByteArray {
            val rate44100 = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
            val comm = int16(1) + int32(samples.size) + int16(16) + rate44100 + (compression?.let(::fourcc) ?: ByteArray(0))
            val ssnd = int32(0) + int32(0) + pcm
            val body =
                fourcc(kind) +
                    fourcc("COMM") + int32(comm.size) + comm +
                    fourcc("SSND") + int32(ssnd.size) + ssnd
            return fourcc("FORM") + int32(body.size) + body
        }

        val bigEndian = ByteArray(samples.size * 2)
        val littleEndian = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bigEndian[i * 2] = (s.toInt() ushr 8).toByte()
            bigEndian[i * 2 + 1] = s.toByte()
            littleEndian[i * 2] = s.toByte()
            littleEndian[i * 2 + 1] = (s.toInt() ushr 8).toByte()
        }

        fun decode(bytes: ByteArray): ShortArray {
            val pcm = AiffPcm.parse(ByteArrayInputStream(bytes))
            assertNotNull(pcm)
            val out = ShortArray(samples.size)
            assertEquals(samples.size, pcm!!.read(out))
            assertEquals(1, pcm.channels)
            assertEquals(44100, pcm.sampleRate)
            pcm.close()
            return out
        }

        assertArrayEquals(samples, decode(file("AIFF", null, bigEndian)))
        assertArrayEquals(samples, decode(file("AIFC", "NONE", bigEndian)))
        assertArrayEquals(samples, decode(file("AIFC", "sowt", littleEndian)))
        // Genuinely compressed AIFC must still be rejected, matching AiffExtractor.
        assertNull(AiffPcm.parse(ByteArrayInputStream(file("AIFC", "ima4", littleEndian))))
    }
}
