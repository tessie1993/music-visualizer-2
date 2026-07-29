package dev.musicviz.analysis

/**
 * Snapshot of the playback audio path for the Now Playing quality readout:
 * what the source track is (codec, sample rate, bit depth, bitrate) and what
 * the decoder actually hands to the device (the tap's output format).
 *
 * Pure Kotlin on purpose - no android/androidx/media3 types - so the headless
 * gate typechecks and tests it. The caller (PlayerViewModel) translates
 * Media3's Format/C constants into the plain ints taken by [classify].
 *
 * Unknown values are 0 (rates/channels/bitrate/bit depth) or "" (container);
 * formatting helpers hide unknown fields instead of printing zeros.
 */
data class AudioQualityInfo(
    val codec: String,
    val container: String,
    val lossless: Boolean,
    val sourceSampleRateHz: Int,
    val sourceChannels: Int,
    val bitDepth: Int,
    val bitrateBps: Int,
    val outputSampleRateHz: Int,
    val outputChannels: Int,
    val outputFloat: Boolean,
) {
    /**
     * True when what reaches the device is the source signal, sample for
     * sample. Reasoning: the tap is a read-only TeeAudioProcessor sitting at
     * the end of ExoPlayer's audio pipeline, so the output format it reports
     * IS the format the device receives - the app adds no lossy step. That
     * makes the check simple: a lossless codec, decoded at the source sample
     * rate (no resampler engaged) with the channel layout intact, is
     * bit-transparent. Float output is NOT a concern for sources up to 24-bit:
     * float32's 24-bit mantissa represents 16/24-bit integer PCM exactly; only
     * a 32-bit-integer source pushed through float conversion can lose bits.
     */
    val isBitPerfect: Boolean
        get() =
            lossless &&
                sourceSampleRateHz > 0 &&
                sourceSampleRateHz == outputSampleRateHz &&
                (outputChannels == 0 || sourceChannels == 0 || outputChannels == sourceChannels) &&
                !(outputFloat && bitDepth > 24)

    /** Short badge text, e.g. "FLAC · Lossless" or "MP3 · Lossy 320 kbps". */
    fun label(): String {
        if (codec == UNKNOWN) return "Unknown format"
        val kind =
            when {
                lossless -> "Lossless"
                bitrateBps > 0 -> "Lossy ${bitrateBps / 1000} kbps"
                else -> "Lossy"
            }
        return "$codec · $kind"
    }

    /** One-line summary, e.g. "44.1 kHz · 16-bit · 2ch → output 48 kHz". */
    fun qualityLine(): String {
        val parts = mutableListOf<String>()
        if (sourceSampleRateHz > 0) parts += formatKhz(sourceSampleRateHz)
        if (bitDepth > 0) parts += "$bitDepth-bit"
        if (sourceChannels > 0) parts += "${sourceChannels}ch"
        var line = if (parts.isEmpty()) label() else parts.joinToString(" · ")
        if (sourceSampleRateHz > 0 && outputSampleRateHz > 0 && outputSampleRateHz != sourceSampleRateHz) {
            line += " → output ${formatKhz(outputSampleRateHz)}"
        }
        return line
    }

    /** One-line verdict for the expanded detail card. */
    fun explanation(): String =
        when {
            isBitPerfect -> "Playback path is bit-transparent; no resampling detected"
            lossless && sourceSampleRateHz > 0 && outputSampleRateHz > 0 && outputSampleRateHz != sourceSampleRateHz ->
                "Lossless source resampled for output: " +
                    "${formatKhz(sourceSampleRateHz)} → ${formatKhz(outputSampleRateHz)}"
            lossless && outputSampleRateHz > 0 -> "Lossless source; decoded output differs from the source format"
            lossless -> "Lossless source; decoded output format not reported yet"
            codec == UNKNOWN -> "Source format unknown"
            else -> {
                val rate = if (bitrateBps > 0) " at ${bitrateBps / 1000} kbps" else ""
                "Source is lossy-compressed ($codec$rate)"
            }
        }

    companion object {
        const val UNKNOWN = "Unknown"

        /** 44100 -> "44.1 kHz", 48000 -> "48 kHz". */
        fun formatKhz(rateHz: Int): String {
            if (rateHz % 1000 == 0) return "${rateHz / 1000} kHz"
            val tenths = (rateHz + 50) / 100
            return "${tenths / 10}.${tenths % 10} kHz"
        }

        /**
         * Maps a source MIME type (plus a container hint from the file
         * extension) to codec name and losslessness. Null/unrecognized mimes
         * classify as [UNKNOWN] and never claim lossless.
         */
        fun classify(
            mime: String?,
            container: String = "",
            sourceSampleRateHz: Int = 0,
            sourceChannels: Int = 0,
            bitDepth: Int = 0,
            bitrateBps: Int = 0,
            outputSampleRateHz: Int = 0,
            outputChannels: Int = 0,
            outputFloat: Boolean = false,
        ): AudioQualityInfo {
            val m = mime?.lowercase().orEmpty()
            val ext = container.lowercase()
            val codec: String
            val lossless: Boolean
            when {
                "flac" in m -> {
                    codec = "FLAC"
                    lossless = true
                }
                "alac" in m -> {
                    codec = "ALAC"
                    lossless = true
                }
                "aiff" in m || "aifc" in m -> {
                    codec = "AIFF"
                    lossless = true
                }
                // Media3 decodes AIFF/WAV to audio/raw; the container hint
                // (uri extension) picks the friendlier name.
                m == "audio/raw" || "wav" in m || "pcm" in m -> {
                    codec =
                        when (ext) {
                            "aiff", "aif", "aifc" -> "AIFF"
                            "wav", "wave" -> "WAV"
                            "flac" -> "FLAC"
                            else -> "PCM"
                        }
                    lossless = true
                }
                "mpeg" in m || "mp3" in m -> {
                    codec = "MP3"
                    lossless = false
                }
                "aac" in m || "mp4a" in m -> {
                    codec = "AAC"
                    lossless = false
                }
                "opus" in m -> {
                    codec = "Opus"
                    lossless = false
                }
                "vorbis" in m -> {
                    codec = "Vorbis"
                    lossless = false
                }
                "ogg" in m -> {
                    codec = "OGG"
                    lossless = false
                }
                "wma" in m -> {
                    codec = "WMA"
                    lossless = false
                }
                else -> {
                    codec = UNKNOWN
                    lossless = false
                }
            }
            return AudioQualityInfo(
                codec = codec,
                container = ext,
                lossless = lossless,
                sourceSampleRateHz = sourceSampleRateHz.coerceAtLeast(0),
                sourceChannels = sourceChannels.coerceAtLeast(0),
                bitDepth = bitDepth.coerceAtLeast(0),
                bitrateBps = bitrateBps.coerceAtLeast(0),
                outputSampleRateHz = outputSampleRateHz.coerceAtLeast(0),
                outputChannels = outputChannels.coerceAtLeast(0),
                outputFloat = outputFloat,
            )
        }
    }
}
