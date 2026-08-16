package dev.musicviz.engine.audio

/**
 * One resolution of the analysis graph: a window length and the distance
 * between consecutive windows.
 *
 * MASTER_PLAN §5.3 runs four of these at once — a short one for transients, a
 * general one, and two long ones for pitch and harmony — because no single
 * window resolves both a kick's attack and a bass note's pitch.
 */
data class AnalysisBranch(
    val name: String,
    val windowFrames: Int,
    val hopFrames: Int,
) {
    init {
        require(windowFrames > 0 && windowFrames and (windowFrames - 1) == 0) {
            "$name: windowFrames must be a power of two, was $windowFrames"
        }
        require(hopFrames > 0) { "$name: hopFrames must be positive, was $hopFrames" }
        require(windowFrames % 2 == 0) { "$name: an odd window has no centre sample" }
    }

    companion object {
        /**
         * §5.3's starting stack at 48 kHz. The sizes are explicitly
         * "benchmarked parameters", not settled constants — what is settled is
         * that every hop divides or is divided by every other, which is what
         * lets [FrameGrid] give them a shared centre coordinate.
         */
        val TRANSIENT = AnalysisBranch("transient", windowFrames = 512, hopFrames = 256)
        val GENERAL = AnalysisBranch("general", windowFrames = 1024, hopFrames = 512)
        val PITCH = AnalysisBranch("pitch", windowFrames = 4096, hopFrames = 512)
        val HARMONY = AnalysisBranch("harmony", windowFrames = 8192, hopFrames = 512)

        val STACK = listOf(TRANSIENT, GENERAL, PITCH, HARMONY)
    }
}
