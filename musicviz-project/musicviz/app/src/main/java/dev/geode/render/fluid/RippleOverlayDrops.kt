package dev.geode.render.fluid

import dev.geode.analysis.AudioFeatures

internal class RippleOverlayDrops {
    companion object {
        const val BEAT_DROPS = 2

        const val SPARKLE_INTERVAL = 6

        const val SPARKLE_THRESHOLD = 0.5f
    }

    private var frame = 0
    private var dropIndex = 0
    private var prevBeat = false

    fun reset() {
        frame = 0
        dropIndex = 0
        prevBeat = false
    }

    fun tick(
        features: AudioFeatures,
        aspect: Float,
        queue: (Float, Float, Float, Float) -> Unit,
    ) {
        frame++
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge) {
            val amp = (0.22f + 0.4f * features.bass.coerceIn(0f, 1.5f)) * features.beatImpulse
            repeat(BEAT_DROPS) {
                val (x, y) = RippleMath.overlayDropPosition(dropIndex++, aspect)
                queue(x, y, 0.055f, amp)
            }
        }
        if (features.treble > SPARKLE_THRESHOLD && frame % SPARKLE_INTERVAL == 0) {
            val (x, y) = RippleMath.overlayDropPosition(dropIndex++, aspect)
            queue(x, y, 0.03f, 0.1f * features.treble.coerceAtMost(2f))
        }
    }
}
