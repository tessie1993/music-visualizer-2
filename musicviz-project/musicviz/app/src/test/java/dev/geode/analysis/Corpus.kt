package dev.geode.analysis

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The oracle corpus, as the Kotlin side sees it.
 *
 * Written by `tools/oracle/generate_corpus.py` from librosa, which is ORACLE
 * tier: it produces these expectations and never enters the runtime. The
 * manifest is the only description of the bytes - there is no header to
 * disagree with - so [Fixture.verifyChecksum] is what keeps the two in step.
 */
object Corpus {
    private val dir = File(dev.geode.ParamSurface.moduleRoot, "app/src/test/resources/corpus")

    private val manifest: JSONObject by lazy {
        JSONObject(File(dir, "manifest.json").readText())
    }

    val generatorVersion: Int get() = manifest.getInt("generatorVersion")

    /** Agreement required per feature, decided by the generator and stored with it. */
    fun tolerance(feature: String): Double = manifest.getJSONObject("tolerances").getDouble(feature)

    fun libraryVersion(name: String): String = manifest.getJSONObject("libraries").getString(name)

    val fixtures: List<Fixture> by lazy {
        val array = manifest.getJSONArray("fixtures")
        (0 until array.length()).map { Fixture(array.getJSONObject(it)) }
    }

    fun named(name: String): Fixture = fixtures.first { it.name == name }

    class Fixture(
        private val json: JSONObject,
    ) {
        val name: String get() = json.getString("name")
        val sampleRateHz: Int get() = json.getInt("sampleRateHz")
        val channels: Int get() = json.getInt("channels")
        val frames: Int get() = json.getInt("frames")

        private val bytes: ByteArray by lazy { File(dir, json.getString("file")).readBytes() }

        /** Interleaved float, dequantised exactly as `PcmTap` does. */
        val interleaved: FloatArray by lazy {
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }

        /** Planar, one array per channel. */
        fun planar(): Array<FloatArray> = Array(channels) { c -> FloatArray(frames) { i -> interleaved[i * channels + c] } }

        /** The mono downmix: the mean over the source's channels, as the app takes it. */
        fun mono(): FloatArray =
            FloatArray(frames) { i ->
                var acc = 0f
                for (c in 0 until channels) acc += interleaved[i * channels + c]
                acc / channels
            }

        /** (L - R) / 2; all zeros for a mono fixture. */
        fun side(): FloatArray =
            FloatArray(frames) { i ->
                if (channels >= 2) (interleaved[i * channels] - interleaved[i * channels + 1]) * 0.5f else 0f
            }

        fun expected(feature: String): Double = json.getJSONObject("expected").getDouble(feature)

        /** Per-frame descriptor expectations, and the STFT they were computed over. */
        fun perFrame(): JSONObject = json.getJSONObject("perFrame")

        fun has(feature: String): Boolean = json.getJSONObject("expected").has(feature)

        /** The file's digest against the manifest's, and its length against the frame count. */
        fun checksumMatches(): Boolean {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) } == json.getString("sha256")
        }

        fun declaredByteLength(): Int = frames * channels * Short.SIZE_BYTES

        fun actualByteLength(): Int = bytes.size
    }
}
