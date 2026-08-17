package dev.geode.export

/**
 * How much longer a render has to go.
 *
 * Both progress surfaces were a bare percent bar on an operation that runs for
 * minutes, so the user could not decide whether to wait or come back later —
 * and before renders survived backgrounding, coming back later killed them.
 * Now that they survive, the question is worth answering properly.
 *
 * ## Why a windowed rate rather than elapsed / progress
 *
 * The naive estimate — scale the elapsed time by the remaining fraction — is
 * badly wrong for this pipeline, because a render is not uniform: it transcodes
 * audio first (the opening 20%, roughly linear and fast), then renders frames
 * (the remaining 80%, much slower per unit of progress). An estimate taken
 * across that boundary predicts a finish several times too early and then
 * visibly slides backwards, which reads as a stuck render.
 *
 * Measuring the rate over a recent window instead means the estimate follows
 * whichever phase is actually running. It is deliberately slow to react at the
 * start — an ETA that flickers between "2 minutes" and "40 minutes" in the
 * first seconds is worse than none — so it reports nothing until it has both a
 * window's worth of samples and meaningful progress.
 */
class RenderEta(
    private val windowSeconds: Float = 12f,
    private val minProgress: Float = 0.02f,
) {
    private var firstSampleMs = 0L
    private var firstSampleProgress = 0f
    private var latestMs = 0L
    private var latestProgress = 0f
    private var started = false

    /**
     * Feeds a progress reading taken at [atMs] (any monotonic clock).
     *
     * @return whole seconds remaining, or null while the estimate would be a
     *   guess. Callers show the bare progress bar until this turns non-null.
     */
    fun sample(
        progress: Float,
        atMs: Long,
    ): Long? {
        val clamped = progress.coerceIn(0f, 1f)
        if (!started) {
            started = true
            firstSampleMs = atMs
            firstSampleProgress = clamped
            latestMs = atMs
            latestProgress = clamped
            return null
        }
        // Progress going backwards means a new render on a reused instance;
        // anchoring to it beats reporting an estimate from the previous one.
        if (clamped < latestProgress) {
            firstSampleMs = atMs
            firstSampleProgress = clamped
        }
        latestMs = atMs
        latestProgress = clamped

        val windowMs = (windowSeconds * 1000).toLong()
        // Slide the anchor forward so the rate reflects the current phase
        // rather than the whole run.
        if (atMs - firstSampleMs > windowMs * 2) {
            val carried = (latestProgress - firstSampleProgress) * windowMs / (atMs - firstSampleMs).toFloat()
            firstSampleMs = atMs - windowMs
            firstSampleProgress = latestProgress - carried
        }

        val elapsedMs = atMs - firstSampleMs
        val gained = latestProgress - firstSampleProgress
        if (elapsedMs < windowMs || gained < minProgress) return null
        val remaining = 1f - latestProgress
        if (remaining <= 0f) return 0L
        val msPerUnit = elapsedMs / gained
        return (remaining * msPerUnit / 1000f).toLong().coerceAtLeast(0L)
    }

    /** Forgets the run, so a reused instance does not carry an old rate. */
    fun reset() {
        started = false
        firstSampleMs = 0
        firstSampleProgress = 0f
        latestMs = 0
        latestProgress = 0f
    }

    companion object {
        /**
         * "about 2 min left" — rounded, because a render's remaining time is an
         * estimate and second-precision on it claims an accuracy it does not
         * have.
         */
        fun describe(secondsRemaining: Long): String =
            when {
                secondsRemaining < 30 -> "almost done"
                secondsRemaining < 90 -> "about a minute left"
                secondsRemaining < 3600 -> "about ${(secondsRemaining + 30) / 60} min left"
                else -> "about ${(secondsRemaining + 1800) / 3600} h left"
            }
    }
}
