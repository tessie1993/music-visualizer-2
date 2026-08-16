package dev.musicviz.engine.audioandroid

import androidx.media3.common.C

/** A PCM sample layout the tap knows how to read, and its width in bytes. */
enum class PcmSampleWidth(
    val bytes: Int,
) {
    SIGNED_16(Short.SIZE_BYTES),
    FLOAT_32(Float.SIZE_BYTES),
}

/**
 * The decoded-output format the tap is currently converting.
 *
 * Immutable and published as one object rather than three loose fields,
 * because a reader that catches a half-updated rate/channel pair converts the
 * next buffer with one field from each format. MASTER_PLAN §5.2 asks for the
 * published snapshot to be immutable for the same reason.
 *
 * [generation] increments on every reconfiguration, which includes every seek
 * — media3 flushes the processor chain for both. That makes it §5.1's
 * discontinuity generation: sample counts either side of an increment belong
 * to different spans and must not be compared.
 */
data class PcmTapFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val generation: Int,
) {
    /**
     * [encoding] resolved once, here, rather than on every buffer — the slice's
     * "adapt formats outside the callback". Null for an encoding the tap cannot
     * read; those buffers are dropped rather than read at the wrong stride,
     * which would deliver noise that looks exactly like audio.
     *
     * Derived, so it stays out of the constructor and out of equality.
     */
    val sampleWidth: PcmSampleWidth? =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> PcmSampleWidth.SIGNED_16
            C.ENCODING_PCM_FLOAT -> PcmSampleWidth.FLOAT_32
            else -> null
        }
}
