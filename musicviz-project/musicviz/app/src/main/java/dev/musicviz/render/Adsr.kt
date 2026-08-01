package dev.musicviz.render

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.SceneParams

/** Which energy band gates an envelope's sustain. */
enum class EnvBand(
    val label: String,
) {
    BASS("Bass"),
    MID("Mid"),
    TREBLE("Treble"),
    RMS("Level"),
}

/**
 * One envelope's configuration. Per the design decision: the ATTACK is
 * triggered by detected beats, the SUSTAIN is driven by band energy - the
 * envelope holds while the chosen band stays above [gateThreshold] and
 * releases when it drops - and every part of that is adjustable.
 */
data class AdsrConfig(
    val enabled: Boolean = false,
    /** Multiple targets: Customize params or LFO rate/depth (LFO1-3). */
    val targets: List<LfoTarget> = emptyList(),
    val attack: Float = 0.05f,
    val decay: Float = 0.25f,
    val sustain: Float = 0.5f,
    val release: Float = 0.35f,
    val amount: Float = 0.5f,
    /** Band whose energy holds the sustain stage. */
    val band: EnvBand = EnvBand.BASS,
    /** Energy level (0..1) above which sustain holds; release below. */
    val gateThreshold: Float = 0.25f,
    /** false = hold at [sustain]; true = sustain tracks the band energy. */
    val sustainTrack: Boolean = false,
    /** Whether a new beat during sustain/release restarts the attack. */
    val retrigger: Boolean = true,
)

/**
 * Two ADSR envelopes: beat-triggered attack, energy-gated sustain. Outputs
 * (0..1, scaled by [AdsrConfig.amount]) modulate Customize params via the
 * same mapping the LFOs use, and can also drive LFO rate/depth through
 * [lfoOffsets] - feed those into [LfoEngine.tick] BEFORE applying params.
 */
class AdsrEngine {
    @Volatile
    var configs: List<AdsrConfig> = List(COUNT) { AdsrConfig() }

    private val level = FloatArray(COUNT)
    private val stage = IntArray(COUNT) // 0 idle, 1 attack, 2 decay, 3 sustain, 4 release
    private val out = FloatArray(COUNT)

    /**
     * Per-envelope attack ceiling, captured from the triggering beat's graded
     * impulse. A synth envelope triggered by a MIDI note peaks at that note's
     * VELOCITY, not always at full scale; these envelopes now do the same, so
     * a soft hit opens them part-way and only a real accent drives them to
     * the top. Attack RATE is scaled to match, keeping the user's attack time
     * the duration it says it is. 1 for legacy beat flags with no strength.
     */
    private val peak = FloatArray(COUNT) { 1f }

    fun tick(
        dt: Float,
        features: AudioFeatures,
    ): FloatArray {
        for (i in 0 until COUNT) {
            val c = configs.getOrNull(i)
            if (c == null || !c.enabled || c.targets.none { it != LfoTarget.NONE }) {
                level[i] = 0f
                stage[i] = 0
                peak[i] = 1f
                out[i] = 0f
                continue
            }
            val energy =
                when (c.band) {
                    EnvBand.BASS -> features.bass
                    EnvBand.MID -> features.mid
                    EnvBand.TREBLE -> features.treble
                    EnvBand.RMS -> features.rms
                }.coerceIn(0f, 1.5f)
            val gateOpen = energy >= c.gateThreshold
            // Hysteresis so sustain doesn't chatter right at the threshold.
            val gateHolds = energy >= c.gateThreshold * 0.85f
            // Attacks stay TEMPO-LOCKED (the tracker's beat, not every
            // transient) so the envelopes keep their rhythmic role; what the
            // beat's amplitude decides is how far the attack goes.
            if (features.beat && (c.retrigger || stage[i] == 0 || stage[i] == 4)) {
                val wasAttacking = stage[i] == 1
                stage[i] = 1
                // A retrigger mid-attack may only RAISE the ceiling, never
                // yank a rising envelope back down to a softer hit's peak.
                val hit = features.beatImpulse.coerceIn(0f, 1f)
                peak[i] = if (wasAttacking) maxOf(peak[i], hit) else maxOf(hit, level[i])
            }
            val ceiling = peak[i].coerceIn(0f, 1f)
            val sustainTarget =
                if (c.sustainTrack) {
                    (c.sustain * (energy / c.gateThreshold.coerceAtLeast(0.05f)).coerceIn(0f, 1f))
                } else {
                    c.sustain
                } * ceiling
            when (stage[i]) {
                1 -> {
                    // Rate scaled by the ceiling: the attack still TAKES
                    // c.attack seconds, it just travels a shorter distance.
                    level[i] += dt / c.attack.coerceAtLeast(0.005f) * ceiling
                    if (level[i] >= ceiling) {
                        level[i] = ceiling
                        stage[i] = 2
                    }
                }
                2 -> {
                    level[i] -= dt / c.decay.coerceAtLeast(0.005f) * (1f - sustainTarget).coerceAtLeast(0.05f)
                    if (level[i] <= sustainTarget) {
                        level[i] = sustainTarget
                        stage[i] = if (gateHolds) 3 else 4
                    }
                }
                3 -> {
                    // Sustain: held open by band energy, not by a fixed timer.
                    level[i] +=
                        (sustainTarget - level[i]) * (dt * 8f).coerceAtMost(1f)
                    if (!gateHolds) stage[i] = 4
                }
                4 -> {
                    level[i] -= dt / c.release.coerceAtLeast(0.005f)
                    if (gateOpen && level[i] > 0.01f) {
                        // Energy came back mid-release: reopen sustain.
                        stage[i] = 3
                    } else if (level[i] <= 0f) {
                        level[i] = 0f
                        stage[i] = 0
                    }
                }
            }
            out[i] = level[i].coerceIn(0f, 1f)
        }
        return out
    }

    companion object {
        const val COUNT = 2

        private fun isLfoTarget(t: LfoTarget): Boolean =
            t == LfoTarget.LFO1_RATE ||
                t == LfoTarget.LFO1_DEPTH ||
                t == LfoTarget.LFO2_RATE ||
                t == LfoTarget.LFO2_DEPTH ||
                t == LfoTarget.LFO3_RATE ||
                t == LfoTarget.LFO3_DEPTH

        /**
         * Rate/depth offsets the envelopes contribute to the LFOs; pass into
         * [LfoEngine.tick]. Rate scaled x4 like the LFO chain targets.
         */
        fun lfoOffsets(
            configs: List<AdsrConfig>,
            envs: FloatArray,
        ): Pair<FloatArray, FloatArray> {
            val rate = FloatArray(3)
            val depth = FloatArray(3)
            for (i in envs.indices) {
                val c = configs.getOrNull(i) ?: continue
                if (!c.enabled || envs[i] <= 0f) continue
                val v = envs[i] * c.amount
                for (t in c.targets) {
                    when (t) {
                        LfoTarget.LFO1_RATE -> rate[0] += v * 4f
                        LfoTarget.LFO1_DEPTH -> depth[0] += v
                        LfoTarget.LFO2_RATE -> rate[1] += v * 4f
                        LfoTarget.LFO2_DEPTH -> depth[1] += v
                        LfoTarget.LFO3_RATE -> rate[2] += v * 4f
                        LfoTarget.LFO3_DEPTH -> depth[2] += v
                        else -> {}
                    }
                }
            }
            return rate to depth
        }

        /** Applies param targets (LFO targets are handled via [lfoOffsets]). */
        fun apply(
            p: SceneParams,
            configs: List<AdsrConfig>,
            envs: FloatArray,
        ): SceneParams {
            var r = p
            for (i in envs.indices) {
                val c = configs.getOrNull(i) ?: continue
                if (!c.enabled || envs[i] <= 0f) continue
                for (t in c.targets) {
                    if (t == LfoTarget.NONE || isLfoTarget(t)) continue
                    val asLfo = LfoConfig(enabled = true, target = t)
                    r = LfoEngine.apply(r, listOf(asLfo), floatArrayOf(envs[i] * c.amount))
                }
            }
            return r
        }
    }
}
