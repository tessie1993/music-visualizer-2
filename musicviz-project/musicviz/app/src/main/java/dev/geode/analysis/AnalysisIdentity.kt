package dev.geode.analysis

/**
 * What "the analysis" currently IS, as a cache-key component.
 *
 * The cache's media fingerprint says which BYTES were analysed; it says
 * nothing about which ENGINE analysed them. When the analysis changes — the
 * V2 rewrite replaced the whole DSP graph — every cached timeline still
 * matches its file stamp and would be served with values no code in the
 * app produces any more. Folding this string into the key orphans those
 * entries the same way a re-tagged file always has: the old key is simply
 * never derived again, and the LRU ages the orphan out.
 *
 * [ALGORITHM_VERSION] is bumped BY HAND when analysis SEMANTICS change —
 * new nodes, retuned normalization, a different beat decision — the same
 * judgement call the corpus generator's version encodes. The configuration
 * half is derived, so a changed window or hop cannot be forgotten.
 */
object AnalysisIdentity {
    /**
     * 1 was the legacy FftProcessor chain; 2 is the ReactiveAnalyzer graph
     * (adaptive per-band normalization, SuperFlux onsets, resonator tempo).
     */
    const val ALGORITHM_VERSION: Int = 2

    val CURRENT: String =
        "alg=$ALGORITHM_VERSION" +
            "|fft=${AnalysisEngine.DEFAULT_FFT_SIZE}" +
            "|bands=${AnalysisEngine.DEFAULT_BAND_COUNT}" +
            "|liveHz=${AnalysisEngine.HOP_RATE_HZ}" +
            "|offlineHz=${OfflineAnalyzer.OFFLINE_HOP_RATE_HZ}"
}
