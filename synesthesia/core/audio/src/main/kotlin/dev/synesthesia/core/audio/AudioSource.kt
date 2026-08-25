package dev.synesthesia.core.audio

/** Single-writer PCM consumer. Called on the producer's thread; must not lock. */
fun interface PcmSink {
    fun write(pcm: FloatArray, frames: Int, channels: Int)
}

/**
 * Canonical ingest seam (blueprint §P1a): every source emits f32 interleaved
 * @48kHz mono-analysis-equivalent — the SOURCE owns resampling/downmix.
 * A format/rate change inside start() forces epoch bump via detach/attach.
 */
interface AudioSource {
    val id: String
    fun attach(sink: PcmSink)
    fun detach()
    fun start()
    fun stop()
}

/** Immutable analysis snapshot handed to the render path. */
class AudioFeatures(
    public val bands: FloatArray,
    public val rms: Float,
    public val onset: Float,
    public val beatPhase: Float,
    public val bpm: Float,
    public val sourceTimestampUs: Long,
)
