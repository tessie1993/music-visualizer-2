package dev.musicviz.audio

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Minimal streaming AIFF/AIFF-C (uncompressed) PCM reader. The platform
 * MediaExtractor/MediaCodec stack has no AIFF support, so playback uses the
 * Media3 [AiffExtractor]; this reader covers the OTHER two decode paths -
 * offline analysis and export transcoding - by reading COMM (channels, bit
 * depth, 80-bit extended sample rate) and streaming SSND big-endian PCM as
 * 16-bit little-endian shorts.
 */
class AiffPcm private constructor(
    private val input: DataInputStream,
    val channels: Int,
    val sampleRate: Int,
    private val bitsPerSample: Int,
    val totalFrames: Long,
) {
    private var framesRead = 0L

    val durationUs: Long get() = if (sampleRate > 0) totalFrames * 1_000_000L / sampleRate else 0L

    val progress: Float get() = if (totalFrames > 0) (framesRead / totalFrames.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Reads up to [out].size samples (interleaved, all channels), converting
     * to 16-bit. Returns the number of shorts written, or -1 at end of data.
     */
    fun read(out: ShortArray): Int {
        val bytesPer = bitsPerSample / 8
        var maxSamples = out.size - (out.size % channels)
        if (totalFrames > 0) {
            val remaining = (totalFrames - framesRead) * channels
            if (remaining <= 0) return -1
            if (remaining < maxSamples) maxSamples = remaining.toInt()
        }
        val raw = ByteArray(maxSamples * bytesPer)
        var got = 0
        try {
            while (got < raw.size) {
                val n = input.read(raw, got, raw.size - got)
                if (n < 0) break
                got += n
            }
        } catch (_: EOFException) {
        }
        val samples = got / bytesPer
        if (samples == 0) return -1
        for (i in 0 until samples) {
            val o = i * bytesPer
            out[i] =
                when (bitsPerSample) {
                    8 -> ((raw[o].toInt()) shl 8).toShort()
                    16 -> (((raw[o].toInt() and 0xFF) shl 8) or (raw[o + 1].toInt() and 0xFF)).toShort()
                    24 -> (((raw[o].toInt() and 0xFF) shl 8) or (raw[o + 1].toInt() and 0xFF)).toShort()
                    32 -> (((raw[o].toInt() and 0xFF) shl 8) or (raw[o + 1].toInt() and 0xFF)).toShort()
                    else -> 0
                }
        }
        framesRead += samples / channels
        return samples
    }

    fun close() {
        runCatching { input.close() }
    }

    companion object {
        /** Opens [uri] as AIFF, or returns null if it isn't one. */
        fun open(
            context: Context,
            uri: Uri,
        ): AiffPcm? {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            return runCatching { parse(stream) }.getOrNull().also { if (it == null) runCatching { stream.close() } }
        }

        private fun parse(rawStream: InputStream): AiffPcm? {
            val din = DataInputStream(rawStream.buffered(1 shl 16))
            val form = ByteArray(4).also { din.readFully(it) }
            if (String(form) != "FORM") return null
            din.readInt() // form size
            val kind = ByteArray(4).also { din.readFully(it) }
            val isAiff = String(kind) == "AIFF" || String(kind) == "AIFC"
            if (!isAiff) return null

            var channels = 0
            var bits = 0
            var rate = 0
            var frames = 0L
            while (true) {
                val id = ByteArray(4)
                try {
                    din.readFully(id)
                } catch (_: EOFException) {
                    return null
                }
                val size = din.readInt()
                when (String(id)) {
                    "COMM" -> {
                        channels = din.readShort().toInt()
                        frames = din.readInt().toLong() and 0xFFFFFFFFL
                        bits = din.readShort().toInt()
                        rate = readExtended80(din)
                        var consumed = 18
                        if (size > consumed) {
                            // AIFC compression type: only uncompressed variants
                            // ("NONE"/"sowt"-less big-endian) are supported.
                            val comp = ByteArray(4).also { din.readFully(it) }
                            consumed += 4
                            if (String(comp) != "NONE") return null
                            din.skipBytes(size - consumed + (size and 1))
                        } else if (size and 1 == 1) {
                            din.skipBytes(1)
                        }
                    }
                    "SSND" -> {
                        val offset = din.readInt()
                        din.readInt() // block size
                        if (offset > 0) din.skipBytes(offset)
                        if (channels <= 0 || rate <= 0 || bits <= 0) return null
                        return AiffPcm(din, channels, rate, bits, frames)
                    }
                    else -> din.skipBytes(size + (size and 1))
                }
            }
        }

        /** 80-bit IEEE-754 extended float -> integer sample rate. */
        private fun readExtended80(din: DataInputStream): Int {
            val exponent = din.readShort().toInt() and 0x7FFF
            val mantissaHigh = din.readInt().toLong() and 0xFFFFFFFFL
            din.readInt() // mantissa low: irrelevant at audio rates
            if (exponent == 0 && mantissaHigh == 0L) return 0
            val shift = 63 - (exponent - 16383)
            if (shift < 0 || shift > 63) return 0
            return ((mantissaHigh shl 32) ushr shift).toInt()
        }
    }
}
