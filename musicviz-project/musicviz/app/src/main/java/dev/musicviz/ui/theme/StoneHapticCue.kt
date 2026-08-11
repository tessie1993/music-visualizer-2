package dev.musicviz.ui.theme

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.getSystemService

/**
 * The crystal packs' haptic cues, from their `tokens/haptics.json`.
 *
 * Unlike colour and motion, this file is byte-identical in every pack, so the
 * mapping is a property of the product rather than of a stone and lives here
 * as a constant instead of on [ThemePack] - the same reasoning that keeps icon
 * geometry out of the per-pack catalog.
 *
 * Each cue names an Android feedback constant plus a vibration pattern to fall
 * back to. Every one of those constants arrived after this app's minSdk (26),
 * so on older devices the pattern is the ordinary path, not an edge case.
 *
 * The packs' rule for all of these: "Haptic and sound start together; internal
 * light begins within 16 ms."
 */
enum class StoneHapticCue(
    private val minApi: Int,
    private val constant: Int,
    private val fallbackMs: LongArray,
) {
    /** Any stone control taking a press. */
    TAP(minApi = 27, constant = HapticFeedbackConstants.TEXT_HANDLE_MOVE, fallbackMs = longArrayOf(8)),

    /** A committed, affirmative action. */
    CONFIRM(minApi = 30, constant = HapticFeedbackConstants.CONFIRM, fallbackMs = longArrayOf(12, 34, 8)),

    /** A refusal or a destructive confirmation. */
    DESTRUCTIVE(minApi = 30, constant = HapticFeedbackConstants.REJECT, fallbackMs = longArrayOf(18, 28, 18)),

    /** A slider crossing one of its steps. */
    SLIDER_TICK(minApi = 34, constant = HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, fallbackMs = longArrayOf(4)),
    ;

    internal fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= minApi

    internal fun platformConstant(): Int = constant

    /**
     * The pack pattern as a waveform. The packs write it as on/off durations
     * starting with ON, and [VibrationEffect.createWaveform] alternates
     * starting with OFF, so a zero-length silence leads.
     */
    internal fun waveform(): VibrationEffect = VibrationEffect.createWaveform(longArrayOf(0) + fallbackMs, -1)
}

/**
 * Plays [cue] through the platform where the device is new enough to know it,
 * and through the pack's own pattern where it is not.
 *
 * View feedback respects the user's system haptics setting on its own; the
 * fallback path needs `VIBRATE`, which the manifest declares.
 */
fun View.performStoneHaptic(cue: StoneHapticCue) {
    if (cue.isAvailable()) {
        performHapticFeedback(cue.platformConstant())
        return
    }
    val vibrator = context.getSystemService<Vibrator>() ?: return
    if (vibrator.hasVibrator()) vibrator.vibrate(cue.waveform())
}
