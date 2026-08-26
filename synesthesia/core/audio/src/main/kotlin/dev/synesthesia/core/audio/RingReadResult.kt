package dev.synesthesia.core.audio

sealed interface RingReadResult {
    data class Ok(
        val firstSample: Long,
        val sampleCount: Int,
        val epoch: Long,
    ) : RingReadResult

    data class Gap(
        val requested: Long,
        val oldestAvailable: Long,
    ) : RingReadResult

    data class Discontinuity(
        val cursorEpoch: Long,
        val currentEpoch: Long,
    ) : RingReadResult

    data object NotYetAvailable : RingReadResult
}
