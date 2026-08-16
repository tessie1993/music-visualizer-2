package dev.musicviz.engine.audio

/**
 * Where captured PCM frames land, stated as an interface so the thing that
 * captures them does not know what stores them.
 *
 * The whole reason the tap can move module at all: `:engine:audio-android`
 * needs a destination, and the destination is `:app`'s legacy `PcmRingBuffer`
 * today and [SampleRing] once the analyzer is migrated. Both satisfy this
 * signature exactly, so the swap is a wiring change and not a rewrite of the
 * capture path.
 *
 * Called from the audio callback: implementations allocate nothing, take no
 * lock, and return promptly.
 */
fun interface PcmSink {
    /**
     * Writes [frameCount] frames of [sourceChannelCount]-channel interleaved
     * audio from the front of [interleaved].
     */
    fun write(
        interleaved: FloatArray,
        frameCount: Int,
        sourceChannelCount: Int,
    )
}
