package dev.geode

import dev.geode.analysis.AudioFeatures
import dev.geode.render.AdsrConfig
import dev.geode.render.AdsrEngine
import dev.geode.render.LfoConfig
import dev.geode.render.LfoEngine
import dev.geode.render.LfoTarget
import dev.geode.render.LfoWave
import dev.geode.render.fluid.FluidEmitters
import dev.geode.render.fluid.FluidHue
import dev.geode.render.fluid.FluidSim
import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The draw path stopped allocating; it must not have stopped agreeing with
 * itself. Every entry point that grew a reuse-the-buffer form is pinned here
 * against the allocating form it was split out of, value for value - a
 * refactor that changes a colour by 1e-7 is a refactor that changed the look.
 *
 * The second thing these pin is the LIFETIME each reused buffer depends on:
 * a shared buffer is only safe while its contents are dead by the time the
 * next frame fills it, and "the caller drained it already" is an assumption
 * worth a test rather than a comment.
 */
class HotPathReuseTest {
    private fun features(
        beat: Boolean = false,
        bass: Float = 0.4f,
        mid: Float = 0.3f,
        treble: Float = 0.05f,
    ) = AudioFeatures(
        bands = FloatArray(16) { it / 16f },
        waveform = FloatArray(64),
        rms = 0.4f,
        bass = bass,
        mid = mid,
        treble = treble,
        beat = beat,
    )

    private fun emitters(seed: Int) =
        FluidEmitters(Random(seed)).apply {
            stirrers = 3
            beatSplats = 4
            sparkle = true
            bassPump = true
        }

    private fun assertSameSplat(
        expected: FluidSim.Splat,
        actual: FluidSim.Splat,
        where: String,
    ) {
        assertEquals("$where prevX", expected.prevX, actual.prevX, 0f)
        assertEquals("$where prevY", expected.prevY, actual.prevY, 0f)
        assertEquals("$where curX", expected.curX, actual.curX, 0f)
        assertEquals("$where curY", expected.curY, actual.curY, 0f)
        assertEquals("$where radius", expected.radius, actual.radius, 0f)
        assertEquals("$where velX", expected.velX, actual.velX, 0f)
        assertEquals("$where velY", expected.velY, actual.velY, 0f)
        assertEquals("$where r", expected.r, actual.r, 0f)
        assertEquals("$where g", expected.g, actual.g, 0f)
        assertEquals("$where b", expected.b, actual.b, 0f)
    }

    // ---- FluidHue -------------------------------------------------------

    @Test
    fun hsvIntoAnArrayIsTheSameConversionAsTheTripleForm() {
        // The out-param form is what every splat now goes through. Exact
        // equality, not a tolerance: it is meant to BE the same arithmetic.
        val out = FloatArray(3)
        var checked = 0
        for (hi in -40..140) {
            val h = hi / 100f
            for (si in 0..10) {
                val s = si / 10f
                for (vi in 0..4) {
                    val v = vi / 4f
                    val expected = FluidHue.hsv(h, s, v)
                    FluidHue.hsv(h, s, v, out)
                    assertEquals("r at h=$h s=$s v=$v", expected.first, out[0], 0f)
                    assertEquals("g at h=$h s=$s v=$v", expected.second, out[1], 0f)
                    assertEquals("b at h=$h s=$s v=$v", expected.third, out[2], 0f)
                    checked++
                }
            }
        }
        assertTrue("the sweep must actually cover the wheel", checked > 5_000)
    }

    @Test
    fun rgbIntoAnArrayIsTheSameConversionAsTheTripleForm() {
        val out = FloatArray(3)
        for (hi in -20..120) {
            val h = hi / 100f
            for (si in -2..12) {
                val s = si / 10f
                val expected = FluidHue.rgb(h, s)
                FluidHue.rgb(h, s, out)
                assertEquals("r at h=$h s=$s", expected.first, out[0], 0f)
                assertEquals("g at h=$h s=$s", expected.second, out[1], 0f)
                assertEquals("b at h=$h s=$s", expected.third, out[2], 0f)
            }
        }
    }

    @Test
    fun hsvIsPinnedAtTheSixCorners() {
        // Independent of the implementation: full-saturation, full-value HSV
        // has a known answer at each sextant boundary. If a future rewrite of
        // the conversion agrees with itself but not with colour theory, this
        // is what says so.
        val out = FloatArray(3)
        val corners =
            listOf(
                0f to Triple(1f, 0f, 0f),
                1f / 6f to Triple(1f, 1f, 0f),
                2f / 6f to Triple(0f, 1f, 0f),
                3f / 6f to Triple(0f, 1f, 1f),
                4f / 6f to Triple(0f, 0f, 1f),
                5f / 6f to Triple(1f, 0f, 1f),
            )
        for ((hue, expected) in corners) {
            FluidHue.hsv(hue, 1f, 1f, out)
            assertEquals("r at $hue", expected.first, out[0], 1e-5f)
            assertEquals("g at $hue", expected.second, out[1], 1e-5f)
            assertEquals("b at $hue", expected.third, out[2], 1e-5f)
        }
    }

    // ---- FluidEmitters --------------------------------------------------

    @Test
    fun tickingIntoACallerListEmitsExactlyWhatTheAllocatingFormEmits() {
        // Both emitters see the same seed and the same frames, so any
        // difference is the reuse work: the shared band array, the anchor
        // reported through fields instead of a Pair, the dye written into a
        // scratch array instead of a Triple, the in-place budget trim.
        val allocating = emitters(11)
        val reusing = emitters(11)
        val into = ArrayList<FluidSim.Splat>()
        val dt = 1f / 60f
        var totalSplats = 0
        for (frame in 0 until 90) {
            val f = features(beat = frame % 12 == 0, treble = if (frame % 7 == 0) 0.9f else 0.05f)
            val expected = allocating.tick(f, dt, 1.6f, 0.23f, 0.61f)
            reusing.tick(f, dt, 1.6f, 0.23f, 0.61f, into)
            assertEquals("frame $frame splat count", expected.size, into.size)
            for (i in expected.indices) assertSameSplat(expected[i], into[i], "frame $frame splat $i")
            assertEquals("frame $frame beatEnv", allocating.beatEnv, reusing.beatEnv, 0f)
            assertEquals("frame $frame bassEnv", allocating.bassEnv, reusing.bassEnv, 0f)
            totalSplats += into.size
        }
        assertTrue("the run must actually emit splats", totalSplats > 100)
    }

    @Test
    fun turningTheStirrersOffChangesNothingButTheStirrers() {
        // The band array moved behind an early-out, so the case that skips it
        // has to keep emitting exactly what it did: beat splats and sparkle.
        val withStirrers = emitters(5).apply { stirrers = 0 }
        val reference = emitters(5).apply { stirrers = 0 }
        val into = ArrayList<FluidSim.Splat>()
        val dt = 1f / 60f
        for (frame in 0 until 40) {
            val f = features(beat = frame % 8 == 0)
            val expected = reference.tick(f, dt, 1.4f, 0.1f, 0.5f)
            withStirrers.tick(f, dt, 1.4f, 0.1f, 0.5f, into)
            assertEquals("frame $frame splat count", expected.size, into.size)
            for (i in expected.indices) assertSameSplat(expected[i], into[i], "frame $frame splat $i")
        }
    }

    @Test
    fun theCallerListIsClearedEveryFrameAndStaysInsideTheBudget() {
        // The whole reason the caller may keep one list: it is emptied before
        // it is refilled, so the frames never accumulate. If that ever
        // stopped being true the sim would be handed a growing backlog of
        // stale splats rather than this frame's.
        val e =
            FluidEmitters(Random(3)).apply {
                stirrers = 4
                beatSplats = 8
                sparkle = true
                bassPump = true
            }
        val into = ArrayList<FluidSim.Splat>()
        val dt = 1f / 60f
        var seen = 0
        for (frame in 0 until 120) {
            e.tick(features(beat = frame % 2 == 0, bass = 0.95f, treble = 0.9f), dt, 1.6f, 0f, 1f, into)
            assertTrue("frame $frame emitted ${into.size} splats, over the per-frame budget", into.size <= 16)
            seen = maxOf(seen, into.size)
        }
        assertTrue("the budget path was never exercised", seen == 16)
    }

    @Test
    fun theAllocatingFormStillHandsBackASnapshotTheCallerMayKeep() {
        // FluidEmittersTest and FluidHueTest both hold one tick's result
        // across later ticks. That contract is why the reuse lives in an
        // overload instead of replacing the original.
        val e = emitters(9)
        val dt = 1f / 60f
        e.tick(features(beat = true), dt, 1.6f, 0f, 1f)
        val first = e.tick(features(beat = true), dt, 1.6f, 0f, 1f)
        val firstColor = Triple(first[0].r, first[0].g, first[0].b)
        repeat(30) { e.tick(features(), dt, 1.6f, 0f, 1f) }
        val later = e.tick(features(beat = true), dt, 1.6f, 0f, 1f)
        assertNotEquals("the allocating form must not alias its own output", first, later)
        assertEquals("the kept snapshot was mutated", firstColor.first, first[0].r, 0f)
        assertEquals("the kept snapshot was mutated", firstColor.second, first[0].g, 0f)
        assertEquals("the kept snapshot was mutated", firstColor.third, first[0].b, 0f)
    }

    // ---- LfoEngine / AdsrEngine -----------------------------------------

    private fun lfoEngine() =
        LfoEngine().apply {
            configs =
                listOf(
                    LfoConfig(enabled = true, target = LfoTarget.LFO2_RATE, wave = LfoWave.SINE, rateHz = 0.7f, depth = 0.4f),
                    LfoConfig(enabled = true, target = LfoTarget.BRIGHTNESS, wave = LfoWave.TRIANGLE, rateHz = 1.3f, depth = 0.5f),
                    LfoConfig(enabled = true, target = LfoTarget.ZOOM, wave = LfoWave.SAW, rateHz = 2.1f, depth = 0.25f),
                )
        }

    @Test
    fun tickDoesNotWriteBackIntoTheCallersOffsetArrays() {
        // The renderer now hands the SAME two arrays to lfoOffsets and tick
        // every frame. That is only safe because tick copies before it
        // accumulates its chain targets, which `copyOf(3)` used to do with an
        // allocation and the scratch fields have to keep doing without one.
        val e = lfoEngine()
        val rate = floatArrayOf(0.25f, -0.5f, 0.75f)
        val depth = floatArrayOf(0.1f, 0.2f, 0.3f)
        val rateBefore = rate.copyOf()
        val depthBefore = depth.copyOf()
        repeat(20) { e.tick(1f / 60f, 120f, rate, depth) }
        assertEquals("tick wrote back into the caller's rate offsets", rateBefore.toList(), rate.toList())
        assertEquals("tick wrote back into the caller's depth offsets", depthBefore.toList(), depth.toList())
    }

    @Test
    fun tickWithShortOrAbsentOffsetArraysBehavesLikeZeroes() {
        // `copyOf(3)` padded a short array with zeroes and truncated a long
        // one; the hand-rolled fill has to do the same or a caller passing a
        // 2-long array starts reading off the end.
        val dt = 1f / 60f
        val none = lfoEngine()
        val short = lfoEngine()
        val long = lfoEngine()
        repeat(20) {
            val a = none.tick(dt, 120f).copyOf()
            val b = short.tick(dt, 120f, FloatArray(0), FloatArray(0)).copyOf()
            val c = long.tick(dt, 120f, FloatArray(5), FloatArray(5)).copyOf()
            assertEquals(a.toList(), b.toList())
            assertEquals(a.toList(), c.toList())
        }
    }

    @Test
    fun tickReturnsTheEngineOwnScratchLikeTheEnvelopesAlreadyDo() {
        // Stated as a test because it is a contract, not an accident: the
        // returned array is this frame's view, so a caller that wants to keep
        // values must copy them. `AdsrEngine.tick` has always worked this way.
        val e = lfoEngine()
        val first = e.tick(1f / 60f, 120f)
        val second = e.tick(1f / 60f, 120f)
        assertSame("LfoEngine.tick must not allocate a fresh array per frame", first, second)
    }

    @Test
    fun offsetsIntoCallerArraysMatchTheAllocatingForm() {
        val configs =
            listOf(
                AdsrConfig(
                    enabled = true,
                    targets = listOf(LfoTarget.LFO1_RATE, LfoTarget.LFO3_DEPTH, LfoTarget.BRIGHTNESS),
                    amount = 0.6f,
                ),
                AdsrConfig(enabled = true, targets = listOf(LfoTarget.LFO2_RATE, LfoTarget.LFO2_DEPTH), amount = 0.35f),
            )
        val rate = FloatArray(3)
        val depth = FloatArray(3)
        for (step in 0..20) {
            val envs = floatArrayOf(step / 20f, 1f - step / 20f)
            val (expectedRate, expectedDepth) = AdsrEngine.lfoOffsets(configs, envs)
            AdsrEngine.lfoOffsets(configs, envs, rate, depth)
            assertEquals("rate at step $step", expectedRate.toList(), rate.toList())
            assertEquals("depth at step $step", expectedDepth.toList(), depth.toList())
        }
        // Accumulators, so the out-param form has to zero them first: run it
        // once more with an all-idle config and the arrays must come back
        // empty rather than carrying the previous frame's offsets.
        AdsrEngine.lfoOffsets(configs, floatArrayOf(0f, 0f), rate, depth)
        assertEquals(List(3) { 0f }, rate.toList())
        assertEquals(List(3) { 0f }, depth.toList())
    }

    @Test
    fun envelopeTargetsLandExactlyWhereTheOldLfoRouteLandedThem() {
        // `AdsrEngine.apply` used to describe each target to `LfoEngine.apply`
        // through a throwaway LfoConfig/listOf/floatArrayOf. Routing straight
        // to the shared table has to hit the same field with the same clamp,
        // including when two envelopes stack on one target - where clamping
        // per step is the behaviour, not an implementation detail.
        val configs =
            listOf(
                AdsrConfig(enabled = true, targets = listOf(LfoTarget.BRIGHTNESS, LfoTarget.ZOOM), amount = 0.8f),
                AdsrConfig(enabled = true, targets = listOf(LfoTarget.BRIGHTNESS, LfoTarget.FLUID_CURL), amount = 0.5f),
            )
        for (step in 1..20) {
            val envs = floatArrayOf(step / 20f, 1f - (step - 1) / 20f)
            var expected = SceneParams.DEFAULT
            for (i in envs.indices) {
                val c = configs[i]
                if (envs[i] <= 0f) continue
                for (t in c.targets) {
                    val asLfo = listOf(LfoConfig(enabled = true, target = t))
                    expected = LfoEngine.apply(expected, asLfo, floatArrayOf(envs[i] * c.amount))
                }
            }
            val actual = AdsrEngine.apply(SceneParams.DEFAULT, configs, envs)
            assertEquals("brightness at step $step", expected.brightness, actual.brightness, 0f)
            assertEquals("zoom at step $step", expected.zoom, actual.zoom, 0f)
            assertEquals("fluidCurl at step $step", expected.fluidCurl, actual.fluidCurl, 0f)
        }
    }

    @Test
    fun theModulatedParamsAreStillAFreshObjectEveryFrame() {
        // The one allocation deliberately left in the draw path. The renderer
        // keeps this object as lastFinalParams, hands it to the scene, and
        // freezes it into outgoingParams for the length of a transition - so
        // it has to be a snapshot, not a view onto next frame's values, and
        // the params it was derived from must be untouched.
        val brighter = listOf(LfoConfig(enabled = true, target = LfoTarget.BRIGHTNESS))
        val before = SceneParams.DEFAULT.brightness
        val a = LfoEngine.apply(SceneParams.DEFAULT, brighter, floatArrayOf(0.3f))
        val b = LfoEngine.apply(SceneParams.DEFAULT, brighter, floatArrayOf(-0.3f))
        assertEquals("the modulation itself moved", before + 0.3f, a.brightness, 1e-6f)
        assertEquals("the modulation itself moved", before - 0.3f, b.brightness, 1e-6f)
        assertNotEquals("a modulated SceneParams must not be shared scratch", a, b)
        assertEquals("the source params were mutated in place", before, SceneParams.DEFAULT.brightness, 0f)
    }
}
