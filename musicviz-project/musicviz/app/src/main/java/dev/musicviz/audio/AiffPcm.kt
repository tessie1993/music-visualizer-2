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
    /** SSND payload bytes: reads stop here so trailing chunks (MARK, APPL,
     *  ID3, annotations) are never decoded as audio noise. */
    private val dataBytes: Long,
) {
    private var framesRead = 0L
    private var bytesConsumed = 0L

    val durationUs: Long get() = if (sampleRate > 0) totalFrames * 1_000_000L / sampleRate else 0L

    val progress: Float get() = if (totalFrames > 0) (framesRead / totalFrames.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Reads up to [out].size samples (interleaved, all channels), converting
     * to 16-bit. Returns the number of shorts written, or -1 at end of data.
     */
    fun read(out: ShortArray): Int {
        val bytesPer = bitsPerSample / 8
        val remaining = dataBytes - bytesConsumed
        if (remaining < bytesPer) return -1
        val wantSamples =
            minOf((out.size - (out.size % channels)).toLong(), remaining / bytesPer).toInt()
        if (wantSamples <= 0) return -1
        val raw = ByteArray(wantSamples * bytesPer)
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
        bytesConsumed += (samples * bytesPer).toLong()
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
                // Chunk sizes are UNSIGNED 32-bit: a signed read goes negative
                // for large chunks, turning skips into no-ops and re-parsing
                // chunk bodies as garbage headers.
                val size = din.readInt().toLong() and 0xFFFFFFFFL
                when (String(id)) {
                    "COMM" -> {
                        if (size < 18) return null
                        channels = din.readShort().toInt()
                        frames = din.readInt().toLong() and 0xFFFFFFFFL
                        bits = din.readShort().toInt()
                        rate = readExtended80(din)
                        var consumed = 18L
                        if (size > consumed) {
                            // AIFC compression type: only uncompressed variants
                            // ("NONE"/"sowt"-less big-endian) are supported.
                            val comp = ByteArray(4).also { din.readFully(it) }
                            consumed += 4
                            if (String(comp) != "NONE") return null
                            skipFully(din, size - consumed + (size and 1L))
                        } else if (size and 1L == 1L) {
                            skipFully(din, 1L)
                        }
                    }
                    "SSND" -> {
                        val offset = din.readInt().toLong() and 0xFFFFFFFFL
                        din.readInt() // block size
                        if (offset > 0) skipFully(din, offset)
                        if (channels <= 0 || rate <= 0 || bits <= 0) return null
                        // Sample sizes that aren't byte multiples (legal in
                        // AIFF, e.g. 12-bit stored in 2 bytes) would silently
                        // decode as zeros with a wrong stride - reject.
                        if (bits != 8 && bits != 16 && bits != 24 && bits != 32) return null
                        // Bound the PCM read to the SSND payload; anything
                        // after it (MARK/APPL/ID3) is metadata, not audio.
                        val byChunk = if (size >= 8 + offset) size - 8 - offset else Long.MAX_VALUE
                        val byFrames =
                            if (frames > 0) frames * channels * (bits / 8) else Long.MAX_VALUE
                        val dataBytes = minOf(byChunk, byFrames)
                        if (dataBytes == Long.MAX_VALUE || dataBytes <= 0) return null
                        return AiffPcm(din, channels, rate, bits, frames, dataBytes)
                    }
                    else -> skipFully(din, size + (size and 1L))
                }
            }
        }

        /** Skips exactly [count] bytes; a short skip means a malformed file. */
        private fun skipFully(
            din: DataInputStream,
            count: Long,
        ) {
            var remaining = count
            while (remaining > 0) {
                val skipped = din.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (din.read() < 0) throw EOFException("truncated AIFF chunk")
                    remaining--
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
