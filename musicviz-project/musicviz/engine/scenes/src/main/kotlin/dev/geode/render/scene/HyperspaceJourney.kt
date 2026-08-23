package dev.geode.render.scene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HyperspaceJourney {
    var immersion: Float = 0f
        private set

    var actPosition: Float = 0f
        private set

    var act: Int = 0
        private set

    private var heldSeconds = 0f
    private var cyclePhase = 0f

    fun reset() {
        immersion = 0f
        actPosition = 0f
        act = 0
        heldSeconds = 0f
        cyclePhase = 0f
    }

    /**
     * Walks the journey one frame.
     *
     * [energy] is the only thing that moves it on Music: the track's play position used to
     * force a floor under the immersion so a long track drifted deeper whatever it sounded
     * like, which made the journey a function of the clock rather than of the music, and did
     * nothing at all on live input. Loud passages take it deeper now, quiet ones bring it back.
     */
    fun advance(
        dt: Float,
        energy: Float,
        mode: Int,
        holdAct: Int,
        cycleSeconds: Float,
        pace: Float,
    ) {
        val last = HyperspaceMath.ACTS.size - 1
        val step = dt * max(pace, 0f)
        val goal: Float =
            when (mode) {
                HyperspaceMath.JOURNEY_HOLD -> holdAct.coerceIn(0, last).toFloat()
                HyperspaceMath.JOURNEY_CYCLE -> {
                    val per = max(cycleSeconds, 2f)
                    val slots = max(2 * last, 1)
                    cyclePhase = (cyclePhase + step / per) % slots.toFloat()
                    val slot = cyclePhase.toInt().coerceIn(0, slots - 1)
                    (last - abs(last - slot)).toFloat()
                }
                else -> {
                    val drive = energy.coerceIn(0f, 1f) - HyperspaceMath.IMMERSION_PIVOT
                    val rate =
                        if (drive >= 0f) {
                            drive / (1f - HyperspaceMath.IMMERSION_PIVOT) / HyperspaceMath.RISE_SECONDS
                        } else {
                            drive / HyperspaceMath.IMMERSION_PIVOT / HyperspaceMath.FALL_SECONDS
                        }
                    immersion = (immersion + rate * step).coerceIn(0f, 1f)
                    immersion * last
                }
            }
        actPosition += (goal - actPosition) * HyperspaceMath.smoothing(step, HyperspaceMath.ACT_GLIDE_SECONDS)
        actPosition = actPosition.coerceIn(0f, last.toFloat())
        heldSeconds = min(heldSeconds + dt, 3600f)
        val rounded = Math.round(actPosition).coerceIn(0, last)
        if (rounded != act && heldSeconds >= HyperspaceMath.MIN_ACT_SECONDS) {
            act = rounded
            heldSeconds = 0f
        }
    }

    fun profile(): HyperspaceMath.ActProfile = HyperspaceMath.profileAt(actPosition)
}
