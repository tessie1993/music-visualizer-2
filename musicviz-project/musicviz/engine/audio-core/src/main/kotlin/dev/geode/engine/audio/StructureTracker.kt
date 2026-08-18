package dev.geode.engine.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Structural evidence over the analysis stream: novelty, section boundaries,
 * and the EXPERIMENTAL buildup/drop/arrival trio.
 *
 * Novelty is the causal form of the distance the offline
 * `FeatureTimeline.detectSections` measures: a fast and a slow EMA of the
 * band profile, and how far apart they sit — large exactly while the music
 * is BECOMING something else. A section boundary fires when normalized
 * novelty stands above both an absolute floor and its own trailing
 * statistics, once per crossing (hysteresis re-arms below [SECTION_REARM])
 * and never more often than [SECTION_REFRACTORY_SECONDS].
 *
 * Buildup, drop and arrival are heuristics over energy and onset density,
 * validated against constructed scenarios rather than a labeled corpus of
 * real arrangements — which is why the plan marks their ABI slots
 * EXPERIMENTAL and why each is deliberately conservative: refractory-gated,
 * evidence-thresholded, and silent when unsure. Buildup is a sustained rise
 * (fast energy EMA standing above slow). A drop is that buildup, recently, a
 * brief dip, then a slam above the running level. An arrival is energy
 * returning after at least [ARRIVAL_QUIET_SECONDS] of near-quiet — the
 * breakdown ending, distinct from a drop because nothing built up first.
 *
 * Deterministic and ordered: [step] must see every analysis hop, in order.
 * Allocates nothing per frame.
 */
class StructureTracker(
    private val bandCount: Int,
    private val hopRateHz: Float,
) {
    init {
        require(bandCount > 0) { "bandCount must be positive, was $bandCount" }
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
    }

    private val dt = 1f / hopRateHz

    private fun poleFor(seconds: Float) = 1f - exp(-1f / (seconds * hopRateHz))

    private val fastPole = poleFor(0.5f)
    private val slowPole = poleFor(8f)
    private val statsPole = poleFor(10f)
    private val fastEnergyPole = poleFor(1f)
    private val slowEnergyPole = poleFor(6f)
    private val noveltySmoothPole = poleFor(0.25f)

    private val fast = FloatArray(bandCount)
    private val slow = FloatArray(bandCount)
    private var noveltyPeak = NOVELTY_PEAK_FLOOR
    private var noveltyMean = 0f
    private var noveltyDev = 0f
    private var sectionArmed = true
    private var sinceSection = Float.MAX_VALUE
    private var fastEnergy = 0f
    private var slowEnergy = 0f
    private var energySeeded = false
    private var buildupMemory = 0f
    private var sinceDip = Float.MAX_VALUE
    private var dipSeconds = 0f
    private var sinceDrop = Float.MAX_VALUE
    private var quietSeconds = 0f
    private var arrivalArmed = false
    private var warmupSeconds = 0f

    /** How much the band profile is changing right now, 0..1. */
    var novelty: Float = 0f
        private set

    /** Whether this hop crossed a section boundary. */
    var sectionBoundary: Boolean = false
        private set

    /** Boundaries fired since construction or [reset]. */
    var sectionCount: Int = 0
        private set

    /** EXPERIMENTAL: sustained rise in energy and onset density, 0..1. */
    var buildup: Float = 0f
        private set

    /** EXPERIMENTAL: whether this hop is a drop — buildup, dip, slam. */
    var drop: Boolean = false
        private set

    /** EXPERIMENTAL: whether this hop is energy returning after a long quiet. */
    var arrival: Boolean = false
        private set

    /**
     * Feeds one hop. [bands] are the analyzer's normalized 0..1 band levels;
     * [rms] and [onset] its instantaneous level and continuous onset
     * strength, both 0..1.
     */
    fun step(
        bands: FloatArray,
        rms: Float,
        onset: Float,
    ) {
        require(bands.size == bandCount) { "expected $bandCount bands, got ${bands.size}" }
        warmupSeconds += dt
        sinceSection += dt
        sinceDip += dt
        sinceDrop += dt

        // ---- novelty and sections ----------------------------------------
        var distance = 0.0
        for (b in 0 until bandCount) {
            val v = bands[b]
            fast[b] += (v - fast[b]) * fastPole
            slow[b] += (v - slow[b]) * slowPole
            val d = fast[b] - slow[b]
            distance += d.toDouble() * d
        }
        val raw = sqrt(distance / bandCount).toFloat()
        noveltyPeak = max(raw, max(noveltyPeak * PEAK_DECAY, NOVELTY_PEAK_FLOOR))
        val normalized = (raw / noveltyPeak).coerceIn(0f, 1f)
        novelty += (normalized - novelty) * noveltySmoothPole

        noveltyMean += (novelty - noveltyMean) * statsPole
        noveltyDev += (kotlin.math.abs(novelty - noveltyMean) - noveltyDev) * statsPole
        val threshold = max(SECTION_FLOOR, noveltyMean + 2f * noveltyDev)
        sectionBoundary = false
        if (sectionArmed && warmupSeconds > WARMUP_SECONDS &&
            sinceSection > SECTION_REFRACTORY_SECONDS && novelty > threshold
        ) {
            sectionBoundary = true
            sectionCount++
            sectionArmed = false
            sinceSection = 0f
        } else if (!sectionArmed && novelty < SECTION_REARM) {
            sectionArmed = true
        }

        // ---- buildup ------------------------------------------------------
        val energy = 0.5f * rms + 0.5f * onset
        if (!energySeeded) {
            // Both averages start AT the music, or the fast one converging
            // ahead of the slow one reads every track's first seconds as a
            // riser. Buildup measures rise, never warmup.
            energySeeded = true
            fastEnergy = energy
            slowEnergy = energy
        }
        fastEnergy += (energy - fastEnergy) * fastEnergyPole
        slowEnergy += (energy - slowEnergy) * slowEnergyPole
        buildup = ((fastEnergy - slowEnergy) / BUILDUP_SCALE).coerceIn(0f, 1f)
        buildupMemory = max(buildup, buildupMemory * exp(-dt / BUILDUP_MEMORY_SECONDS))

        // ---- the dip the drop lands out of --------------------------------
        if (rms < slowEnergy * DIP_FRACTION) {
            dipSeconds += dt
            if (dipSeconds >= MIN_DIP_SECONDS) sinceDip = 0f
        } else {
            dipSeconds = 0f
        }

        // ---- drop ---------------------------------------------------------
        drop = false
        if (warmupSeconds > WARMUP_SECONDS &&
            sinceDrop > DROP_REFRACTORY_SECONDS &&
            buildupMemory > DROP_BUILDUP &&
            sinceDip < DROP_DIP_WINDOW_SECONDS &&
            rms > DROP_SLAM_LEVEL &&
            rms > slowEnergy + DROP_SLAM_MARGIN
        ) {
            drop = true
            sinceDrop = 0f
            // Consumed: one buildup earns one drop.
            buildupMemory = 0f
        }

        // ---- arrival ------------------------------------------------------
        arrival = false
        if (rms < ARRIVAL_QUIET_LEVEL) {
            quietSeconds += dt
            if (quietSeconds >= ARRIVAL_QUIET_SECONDS) arrivalArmed = true
        } else {
            if (arrivalArmed && rms > ARRIVAL_RECOVERY_LEVEL && warmupSeconds > WARMUP_SECONDS) {
                arrival = true
                arrivalArmed = false
            }
            quietSeconds = 0f
        }
    }

    /** Forgets one piece of audio; call on a track change or a seek. */
    fun reset() {
        fast.fill(0f)
        slow.fill(0f)
        noveltyPeak = NOVELTY_PEAK_FLOOR
        noveltyMean = 0f
        noveltyDev = 0f
        sectionArmed = true
        sinceSection = Float.MAX_VALUE
        fastEnergy = 0f
        slowEnergy = 0f
        energySeeded = false
        buildupMemory = 0f
        sinceDip = Float.MAX_VALUE
        dipSeconds = 0f
        sinceDrop = Float.MAX_VALUE
        quietSeconds = 0f
        arrivalArmed = false
        warmupSeconds = 0f
        novelty = 0f
        sectionBoundary = false
        sectionCount = 0
        buildup = 0f
        drop = false
        arrival = false
    }

    companion object {
        /** No structural claim before this much audio has been heard. */
        const val WARMUP_SECONDS = 5f

        /** Normalized novelty a section must clear whatever the statistics say. */
        const val SECTION_FLOOR = 0.5f

        /** Novelty below which the section detector re-arms. */
        const val SECTION_REARM = 0.35f

        const val SECTION_REFRACTORY_SECONDS = 8f

        /** Per-hop decay of the novelty normalizer's reference peak (~60 s). */
        const val PEAK_DECAY = 0.9997f

        /** Floor under the reference peak, so noise is not amplified to 1. */
        const val NOVELTY_PEAK_FLOOR = 0.05f

        /** Fast-over-slow energy rise that reads as full buildup. */
        const val BUILDUP_SCALE = 0.3f

        /** How long a finished buildup can still claim its drop. */
        const val BUILDUP_MEMORY_SECONDS = 3f

        const val DROP_BUILDUP = 0.4f
        const val DROP_DIP_WINDOW_SECONDS = 1f
        const val DROP_SLAM_LEVEL = 0.75f
        const val DROP_SLAM_MARGIN = 0.15f
        const val DROP_REFRACTORY_SECONDS = 4f

        /** RMS under this fraction of the running level reads as a dip. */
        const val DIP_FRACTION = 0.35f

        const val MIN_DIP_SECONDS = 0.1f

        const val ARRIVAL_QUIET_LEVEL = 0.15f
        const val ARRIVAL_QUIET_SECONDS = 2f
        const val ARRIVAL_RECOVERY_LEVEL = 0.4f
    }
}
