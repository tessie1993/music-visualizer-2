package dev.musicviz.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** One equalizer band as shown in the Settings UI. */
data class AudioFxBand(
    /** Human label for the band's center frequency, e.g. "60 Hz", "14 kHz". */
    val label: String,
    /** Current gain in millibels. */
    val levelMb: Int,
    /** Lowest supported gain in millibels (typically -1500). */
    val minMb: Int,
    /** Highest supported gain in millibels (typically +1500). */
    val maxMb: Int,
)

/** Snapshot of the whole audio-effects chain for the Settings UI. */
data class AudioFxState(
    /** False when the device rejected the effect constructors (or no session yet). */
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<AudioFxBand> = emptyList(),
    val presets: List<String> = emptyList(),
    /** Index into [presets]; -1 = custom band levels. */
    val presetIndex: Int = -1,
    /** Bass boost strength 0..1000. */
    val bassBoost: Int = 0,
    /** Loudness target gain in millibels 0..1000. */
    val loudness: Int = 0,
)

/** Pure formatting/serialization helpers, kept free of effect objects so the
 *  headless suite can exercise them. All math is locale-independent. */
object AudioFxFormat {
    /** Formats an Equalizer center frequency (millihertz) as "60 Hz"/"3.6 kHz"/"14 kHz". */
    fun freqLabel(milliHz: Int): String {
        val hz = milliHz / 1000
        if (hz < 1000) return "$hz Hz"
        val whole = hz / 1000
        val tenth = (hz % 1000) / 100
        return if (tenth == 0) "$whole kHz" else "$whole.$tenth kHz"
    }

    /** Formats a millibel gain as a signed dB label: "+3 dB", "-1.5 dB", "0 dB". */
    fun dbLabel(mB: Int): String {
        val abs = if (mB < 0) -mB else mB
        val whole = abs / 100
        val tenth = (abs % 100) / 10
        val num = if (tenth == 0) "$whole" else "$whole.$tenth"
        return when {
            mB > 0 -> "+$num dB"
            mB < 0 -> "-$num dB"
            else -> "0 dB"
        }
    }

    /** Serializes per-band millibel levels for SharedPreferences. */
    fun encodeBandLevels(levels: List<Int>): String = levels.joinToString(",")

    /** Inverse of [encodeBandLevels]; malformed entries are skipped, null/blank -> empty. */
    fun decodeBandLevels(csv: String?): List<Int> =
        csv
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
}

/**
 * Wraps the platform audio effects (Equalizer, BassBoost, LoudnessEnhancer)
 * attached to the player's audio session, with persistence in the
 * "musicviz-audiofx" prefs file.
 *
 * The audiofx constructors (and many of the calls) throw on plenty of devices
 * and on most emulators, so EVERY effect construction and call here is
 * defensive: a failure just leaves that effect null and [available] reports
 * false - the app must never crash because of the equalizer.
 *
 * Deliberately media3-free: it takes a plain Int audio session id via
 * [attach], and the ViewModel does the Player.Listener wiring
 * (onAudioSessionIdChanged). That keeps this file typecheckable by the
 * headless gate.
 */
class AudioFxController(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("musicviz-audiofx", Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    /** True once an Equalizer was successfully built for a real session. */
    val available: Boolean
        get() = equalizer != null

    /**
     * (Re)builds the effect chain for [sessionId] and restores the persisted
     * settings. 0 or negative ids (C.AUDIO_SESSION_ID_UNSET is 0 - the sink
     * has not initialized yet) just release any previous chain.
     */
    fun attach(sessionId: Int) {
        if (sessionId == this.sessionId && equalizer != null) return
        release()
        this.sessionId = sessionId
        if (sessionId <= 0) return
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        restore()
    }

    /** Releases all effects (player released or session changing). Idempotent. */
    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bassBoost = null
        loudness = null
        sessionId = 0
    }

    // ---- Public API (each call persists, so settings survive restarts) ----

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        applyEnabled(enabled)
    }

    val bandCount: Int
        get() = equalizer?.let { eq -> runCatching { eq.numberOfBands.toInt() }.getOrNull() } ?: 0

    /** Supported gain range in millibels (platform reports one range for all bands). */
    fun bandRange(band: Int): Pair<Int, Int> =
        equalizer
            ?.let { eq -> runCatching { eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() } }.getOrNull() }
            ?: (-1500 to 1500)

    /** Center frequency of [band] in millihertz. */
    fun bandCenterFreq(band: Int): Int = equalizer?.let { eq -> runCatching { eq.getCenterFreq(band.toShort()) }.getOrNull() } ?: 0

    /** Sets one band's gain (millibels) and switches to the custom "preset". */
    fun setBandLevel(
        band: Int,
        mB: Int,
    ) {
        val eq = equalizer ?: return
        val (lo, hi) = bandRange(band)
        runCatching { eq.setBandLevel(band.toShort(), mB.coerceIn(lo, hi).toShort()) }
        prefs
            .edit()
            .putInt(KEY_PRESET, -1)
            .putString(KEY_BANDS, AudioFxFormat.encodeBandLevels(currentBandLevels()))
            .apply()
    }

    val presetNames: List<String>
        get() =
            equalizer
                ?.let { eq ->
                    runCatching {
                        (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
                    }.getOrNull()
                }.orEmpty()

    fun usePreset(i: Int) {
        val eq = equalizer ?: return
        if (i !in presetNames.indices) return
        runCatching { eq.usePreset(i.toShort()) }
        // The preset's resulting levels are stored too, so a device that later
        // rejects usePreset (or a preset-index shift) still restores the sound.
        prefs
            .edit()
            .putInt(KEY_PRESET, i)
            .putString(KEY_BANDS, AudioFxFormat.encodeBandLevels(currentBandLevels()))
            .apply()
    }

    /** Bass boost strength, 0..1000. */
    fun setBassBoost(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_BASS, s).apply()
        runCatching { bassBoost?.setStrength(s.toShort()) }
    }

    /** Loudness target gain in millibels, 0..1000. */
    fun setLoudness(mB: Int) {
        val g = mB.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_LOUDNESS, g).apply()
        runCatching { loudness?.setTargetGain(g) }
    }

    /** Current chain + persisted settings as one UI state value. */
    fun snapshot(): AudioFxState {
        val base =
            AudioFxState(
                available = available,
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                presetIndex = prefs.getInt(KEY_PRESET, -1),
                bassBoost = prefs.getInt(KEY_BASS, 0),
                loudness = prefs.getInt(KEY_LOUDNESS, 0),
            )
        val eq = equalizer ?: return base
        val bands =
            runCatching {
                val range = eq.bandLevelRange
                (0 until eq.numberOfBands.toInt()).map { b ->
                    AudioFxBand(
                        label = AudioFxFormat.freqLabel(eq.getCenterFreq(b.toShort())),
                        levelMb = eq.getBandLevel(b.toShort()).toInt(),
                        minMb = range[0].toInt(),
                        maxMb = range[1].toInt(),
                    )
                }
            }.getOrDefault(emptyList())
        return base.copy(bands = bands, presets = presetNames)
    }

    // ---- Internals ----

    private fun currentBandLevels(): List<Int> =
        equalizer
            ?.let { eq ->
                runCatching {
                    (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }
                }.getOrNull()
            }.orEmpty()

    /** Reapplies everything persisted onto a freshly built chain. */
    private fun restore() {
        val eq = equalizer
        if (eq != null) {
            val preset = prefs.getInt(KEY_PRESET, -1)
            if (preset >= 0 && runCatching { eq.usePreset(preset.toShort()) }.isSuccess) {
                // Preset restored directly.
            } else {
                val levels = AudioFxFormat.decodeBandLevels(prefs.getString(KEY_BANDS, null))
                levels.forEachIndexed { band, mb ->
                    if (band < bandCount) {
                        val (lo, hi) = bandRange(band)
                        runCatching { eq.setBandLevel(band.toShort(), mb.coerceIn(lo, hi).toShort()) }
                    }
                }
            }
        }
        runCatching { bassBoost?.setStrength(prefs.getInt(KEY_BASS, 0).coerceIn(0, 1000).toShort()) }
        runCatching { loudness?.setTargetGain(prefs.getInt(KEY_LOUDNESS, 0).coerceIn(0, 1000)) }
        applyEnabled(prefs.getBoolean(KEY_ENABLED, false))
    }

    private fun applyEnabled(enabled: Boolean) {
        runCatching { equalizer?.enabled = enabled }
        runCatching { bassBoost?.enabled = enabled }
        runCatching { loudness?.enabled = enabled }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_BANDS = "band_levels"
        const val KEY_PRESET = "preset"
        const val KEY_BASS = "bass"
        const val KEY_LOUDNESS = "loudness"
    }
}
