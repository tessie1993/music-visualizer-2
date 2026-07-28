package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidChoreography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless gate for the spawn/catch progression engine: anchors must move
 * smoothly (organic-motion property: nothing teleports), song progress must
 * actually reshape the layout (the feature the rebuild exists for), section
 * changes must glide rather than snap, and every output must stay inside the
 * sim-space domain for any aspect.
 */
class FluidChoreographyTest {
    private fun features(
        beat: Boolean = false,
        progress: Float = 0f,
        sectionIndex: Int = 0,
        bass: Float = 0.3f,
    ) = AudioFeatures(
        bands = FloatArray(16),
        waveform = FloatArray(64),
        rms = 0.4f,
        bass = bass,
        mid = 0.3f,
        treble = 0.1f,
        beat = beat,
        progress = progress,
        sectionIndex = sectionIndex,
    )

    @Test
    fun anchorsNeverTeleportUnderProgressAndSectionChanges() {
        for (path in 0 until FluidChoreography.PATH_LABELS.size) {
            val c =
                FluidChoreography().apply {
                    this.path = path
                    spawnCount = 4
                    catchCount = 2
                }
            val dt = 1f / 60f
            c.tick(features(), dt, 1.8f)
            var prev = c.spawns.take(4).map { it.x to it.y } + c.catches.take(2).map { it.x to it.y }
            var t = 0f
            for (frame in 1 until 600) {
                t += dt
                // Sweep progress fast (a whole track in 10 s) AND force a
                // section change mid-run - worst-case target jumps.
                c.tick(features(beat = frame % 30 == 0, progress = t / 10f, sectionIndex = frame / 200), dt, 1.8f)
                val cur = c.spawns.take(4).map { it.x to it.y } + c.catches.take(2).map { it.x to it.y }
                for (i in cur.indices) {
                    val dx = cur[i].first - prev[i].first
                    val dy = cur[i].second - prev[i].second
                    val step = sqrt(dx * dx + dy * dy)
                    assertTrue("path $path anchor $i jumped $step in one frame", step < 0.12f)
                }
                prev = cur
            }
        }
    }

    @Test
    fun songProgressReshapesTheLayout() {
        for (path in 0 until FluidChoreography.PATH_LABELS.size) {
            fun settledAt(progress: Float): List<Pair<Float, Float>> {
                val c =
                    FluidChoreography().apply {
                        this.path = path
                        spawnCount = 3
                        catchCount = 2
                    }
                // Long settle at fixed progress so anchors reach targets.
                repeat(600) { c.tick(features(progress = progress), 1f / 60f, 1.6f) }
                return c.spawns.take(3).map { it.x to it.y }
            }
            val early = settledAt(0.05f)
            val late = settledAt(0.85f)
            var moved = 0f
            for (i in early.indices) {
                moved += abs(late[i].first - early[i].first) + abs(late[i].second - early[i].second)
            }
            assertTrue(
                "path $path: progression did not move spawn points (moved=$moved)",
                moved > 0.2f,
            )
        }
    }

    @Test
    fun progressionAmountZeroFreezesTheJourney() {
        fun settled(progress: Float): List<Pair<Float, Float>> {
            val c =
                FluidChoreography().apply {
                    path = FluidChoreography.PATH_ORBIT
                    spawnCount = 3
                    progressionAmount = 0f
                    speed = 0f
                }
            repeat(600) { c.tick(features(progress = progress), 1f / 60f, 1.6f) }
            return c.spawns.take(3).map { it.x to it.y }
        }
        // With progression off (and no orbital speed) the layout must be
        // essentially identical at the start and end of the track: only the
        // slow internal orbit time (speed floor 0.4) still moves it, so the
        // tolerance is loose but far below the progression-on movement.
        val early = settled(0.05f)
        val late = settled(0.85f)
        for (i in early.indices) {
            val d = abs(late[i].first - early[i].first) + abs(late[i].second - early[i].second)
            assertTrue("anchor $i moved $d with progression off", d < 1.2f)
        }
    }

    @Test
    fun catchPointsSpiralInwardAsTheTrackCloses() {
        val c =
            FluidChoreography().apply {
                spawnCount = 2
                catchCount = 2
            }
        repeat(600) { c.tick(features(progress = 0.05f), 1f / 60f, 1.6f) }
        val earlyR = c.catches.take(2).map { sqrt(it.x * it.x + it.y * it.y) }
        repeat(600) { c.tick(features(progress = 0.98f), 1f / 60f, 1.6f) }
        val lateR = c.catches.take(2).map { sqrt(it.x * it.x + it.y * it.y) }
        for (i in 0 until 2) {
            assertTrue(
                "catch $i did not draw inward (${earlyR[i]} -> ${lateR[i]})",
                lateR[i] < earlyR[i],
            )
        }
    }

    @Test
    fun allAnchorsStayInsideTheDomainForAnyAspect() {
        for (aspect in floatArrayOf(0.5f, 1f, 1.78f, 2.4f)) {
            for (path in 0 until FluidChoreography.PATH_LABELS.size) {
                val c =
                    FluidChoreography().apply {
                        this.path = path
                        spawnCount = FluidChoreography.MAX_SPAWN
                        catchCount = FluidChoreography.MAX_CATCH
                    }
                var t = 0f
                repeat(900) { frame ->
                    t += 1f / 60f
                    c.tick(features(beat = frame % 17 == 0, progress = (t / 15f) % 1f, sectionIndex = frame / 300), 1f / 60f, aspect)
                }
                val bound = minOf(aspect, 1.6f) * 0.93f + 1e-3f
                (c.spawns + c.catches).forEach {
                    assertTrue("x ${it.x} out of [-$bound,$bound] (aspect $aspect)", abs(it.x) <= bound)
                    assertTrue("y ${it.y} out of domain", abs(it.y) <= 0.93f)
                }
            }
        }
    }

    @Test
    fun packedArraysZeroFillInactiveSlotsAndCarryConfig() {
        val c =
            FluidChoreography().apply {
                spawnCount = 2
                catchCount = 1
            }
        c.tick(features(beat = true, bass = 1f), 1f / 60f, 1f)
        val spawns = FloatArray(FluidChoreography.MAX_SPAWN * 4)
        val catches = FloatArray(FluidChoreography.MAX_CATCH * 4)
        c.packSpawns(spawns)
        c.packCatches(catches, pull = 1.5f, captureRadius = 0.11f)
        // Active slots carry weight + jitter.
        assertTrue(spawns[2] > 0f)
        assertTrue(spawns[3] > 0f)
        assertTrue(spawns[4 + 2] > 0f)
        // Inactive slots are fully zeroed (shader loops trust the count, but
        // stale data must never leak into a later larger count).
        for (i in 2 until FluidChoreography.MAX_SPAWN) {
            for (k in 0 until 4) assertEquals(0f, spawns[i * 4 + k], 0f)
        }
        assertEquals(0.11f, catches[3], 1e-6f)
        assertTrue("pull must scale with bass envelope", catches[2] > 0f)
        for (i in 1 until FluidChoreography.MAX_CATCH) {
            for (k in 0 until 4) assertEquals(0f, catches[i * 4 + k], 0f)
        }
    }

    @Test
    fun beatsAdvanceTheBloomFloretCounter() {
        val c =
            FluidChoreography().apply {
                path = FluidChoreography.PATH_BLOOM
                spawnCount = 1
                progressionAmount = 1f
            }
        c.tick(features(), 1f / 60f, 1f)
        val before = c.spawns[0].targetX to c.spawns[0].targetY
        repeat(3) { c.tick(features(beat = true), 1f / 60f, 1f) }
        val after = c.spawns[0].targetX to c.spawns[0].targetY
        assertTrue(
            "bloom target did not advance on beats",
            abs(after.first - before.first) + abs(after.second - before.second) > 1e-3f,
        )
        // Edge-detected: one sustained beat=true snapshot (the analysis hop
        // is slower than a 120 Hz display) is ONE beat, not one per frame.
        assertEquals(1, c.beatCount)
        c.tick(features(beat = false), 1f / 60f, 1f)
        c.tick(features(beat = true), 1f / 60f, 1f)
        assertEquals(2, c.beatCount)
    }
}
