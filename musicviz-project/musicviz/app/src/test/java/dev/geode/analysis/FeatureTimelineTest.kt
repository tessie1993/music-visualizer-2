package dev.geode.analysis

import dev.geode.engine.audio.DrumChannels
import dev.geode.engine.audio.PulseReplay
import dev.geode.engine.audio.SuperFlux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers timeline lookup/sectioning plus [FeatureTimeline.withBeatSensitivity].
 *
 * The latter guards a design bug: the analysis cache used to store the decided
 * beat flags, so the beat grid an export ran on was frozen at whatever
 * sensitivity the track happened to be analysed under (in practice always the
 * shipped defaults, since the offline analyzer never received the setting).
 * The timeline now carries the raw onset curve and re-decides on demand.
 */
class FeatureTimelineTest {
    private fun frame(
        timeMs: Long,
        level: Float,
        bandCount: Int = 64,
    ): TimelineFrame = TimelineFrame(timeMs, AudioFeatures(FloatArray(bandCount) { level }, FloatArray(128), rms = level))

    /**
     * A slow, sparse track: a kick every second with softer transients every
     * 250 ms in between — the material whose spurious flashes the sensitivity
     * sliders exist to suppress. The onset curve is produced by the shipped
     * [SuperFlux] and decided by the shipped [PulseReplay], so the frames carry
     * a real curve rather than a hand-drawn one.
     */
    private fun analyzedFrames(
        sensitivity: Float,
        intervalMs: Float,
    ): List<TimelineFrame> {
        val flux = SuperFlux(64)
        val perFrameBands = ArrayList<FloatArray>(900)
        val fluxCurve = FloatArray(900)
        val rmsCurve = FloatArray(900)
        for (i in 0 until 900) {
            val kick = i % 60 == 0
            val minor = !kick && i % 15 == 0
            val bands =
                FloatArray(64) { b ->
                    when {
                        b >= 16 -> 0.05f
                        kick -> 0.65f
                        minor -> 0.40f
                        else -> 0.05f
                    }
                }
            perFrameBands += bands
            fluxCurve[i] = flux.next(bands)
            var acc = 0f
            for (v in bands) acc += v
            rmsCurve[i] = acc / bands.size
        }
        val pulse = PulseReplay.decide(fluxCurve, rmsCurve, 60f, sensitivity, intervalMs)
        return (0 until 900).map { i ->
            TimelineFrame(
                i * 1000L / 60L,
                AudioFeatures(
                    bands = perFrameBands[i],
                    waveform = FloatArray(128),
                    rms = rmsCurve[i],
                    flux = fluxCurve[i],
                    beat = pulse.beat[i],
                    beatStrength = pulse.strength[i],
                    transient = pulse.transient[i],
                    beatPhase = pulse.phase[i],
                    pulseConfidence = pulse.confidence[i],
                    macroEnergy = pulse.energy[i],
                ),
            )
        }
    }

    private fun analyzed(
        sigma: Float,
        intervalMs: Float,
    ): FeatureTimeline = FeatureTimeline(analyzedFrames(sigma, intervalMs), hopMs = 16L, key = "A minor", hopRateHz = 60f)

    private fun beats(t: FeatureTimeline): List<Boolean> = t.frames.map { it.features.beat }

    /**
     * A 60 Hz timeline whose beat flag is ONE frame wide - what the offline
     * analyzer produces, since `BeatGate.accept` is true for a single frame
     * per onset. Beats land on odd and even indices alike (every 11th frame),
     * which is what a 30 fps export - every SECOND timeline frame - misses.
     */
    private fun beatTimeline(
        count: Int = 600,
        everyN: Int = 11,
    ): FeatureTimeline {
        val frames =
            (0 until count).map { i ->
                val onBeat = i % everyN == 0
                val level = i / count.toFloat()
                TimelineFrame(
                    i * 1000L / 60L,
                    AudioFeatures(
                        bands = FloatArray(64) { level },
                        waveform = FloatArray(128),
                        rms = level,
                        onset = if (onBeat) 1f else 0.1f,
                        beat = onBeat,
                        bpm = 120f,
                        centroid = level,
                        flux = if (onBeat) 1f else 0.1f,
                    ),
                )
            }
        return FeatureTimeline(frames, hopMs = 16L, hopRateHz = 60f)
    }

    /**
     * The features an export at [fps] observes, sampled exactly the way
     * `VideoExporter`'s frame loop does. [spanned] = false is the plain
     * nearest-frame lookup it used to do, kept as the regression witness.
     */
    private fun exportFrames(
        timeline: FeatureTimeline,
        fps: Int,
        spanned: Boolean = true,
    ): List<AudioFeatures> {
        val total = (timeline.durationMs * fps / 1000L).toInt() + 1
        return (0 until total).map { frame ->
            val timeMs = frame * 1000L / fps
            val nextMs = (frame + 1) * 1000L / fps
            timeline.progressionAt(timeMs, emptyList(), if (spanned) nextMs - timeMs else 0L)
        }
    }

    @Test
    fun `a 30 fps export observes every beat a 60 fps export does`() {
        val timeline = beatTimeline()
        val expected = timeline.frames.count { it.features.beat }
        assertTrue("the fixture must carry beats", expected > 20)
        assertEquals("a 60 fps export sees the whole 60 Hz timeline", expected, exportFrames(timeline, 60).count { it.beat })
        // Every rate the exporter can pick (it clamps to 24..60): each beat
        // observed, and observed exactly ONCE - consecutive spans tile the
        // timeline, so nothing is double-counted either.
        for (fps in listOf(24, 25, 30, 40, 48, 50)) {
            assertEquals(
                "an export at $fps fps must observe every beat exactly once",
                expected,
                exportFrames(timeline, fps).count { it.beat },
            )
        }
    }

    @Test
    fun `nearest-frame sampling is what dropped the beats`() {
        // Regression witness for the defect: an export frame sampled the ONE
        // nearest timeline frame, so at 30 fps (every other frame) roughly
        // half the one-frame-wide beat flags were never seen and the video
        // pulsed on the rest. 60 fps was always fine, which is why it hid.
        val timeline = beatTimeline()
        val expected = timeline.frames.count { it.features.beat }
        val nearestAt30 = exportFrames(timeline, 30, spanned = false).count { it.beat }
        assertTrue("nearest-frame sampling should have missed beats, saw $nearestAt30 of $expected", nearestAt30 < expected)
        assertTrue("...roughly half of them", nearestAt30 < expected * 3 / 4)
        assertEquals(expected, exportFrames(timeline, 60, spanned = false).count { it.beat })
    }

    @Test
    fun `a 60 fps export is unchanged by the span lookup`() {
        // At 60 fps an exported frame covers exactly one timeline frame, so
        // the spanned lookup must return the very same features - no copies,
        // no re-derived values, byte-identical output.
        val timeline = beatTimeline()
        assertEquals(exportFrames(timeline, 60, spanned = false), exportFrames(timeline, 60))
    }

    @Test
    fun `the span only folds in impulses, never the continuous features`() {
        val timeline = beatTimeline()
        val spanned = exportFrames(timeline, 30)
        val nearest = exportFrames(timeline, 30, spanned = false)
        assertEquals(nearest.size, spanned.size)
        for (i in spanned.indices) {
            // Levels stay POINT-SAMPLED at the nearest frame: averaging them
            // over the span would low-pass every exported clip (and averaging
            // a waveform cancels its phase), which is a character change.
            assertSame("bands at frame $i", nearest[i].bands, spanned[i].bands)
            assertSame("waveform at frame $i", nearest[i].waveform, spanned[i].waveform)
            assertEquals("rms at frame $i", nearest[i].rms, spanned[i].rms, 0f)
            assertEquals("centroid at frame $i", nearest[i].centroid, spanned[i].centroid, 0f)
            assertEquals("bpm at frame $i", nearest[i].bpm, spanned[i].bpm, 0f)
            assertEquals("progress at frame $i", nearest[i].progress, spanned[i].progress, 0f)
            // Impulses are peak-held, so a folded-in beat keeps the onset
            // strength it was decided from instead of a neighbour's.
            assertTrue("onset at frame $i", spanned[i].onset >= nearest[i].onset)
            assertTrue("a beat frame must carry its onset peak", !spanned[i].beat || spanned[i].onset >= 1f)
        }
        assertTrue("the span must recover beats", spanned.count { it.beat } > nearest.count { it.beat })
    }

    @Test
    fun `a spanned lookup never reads outside the timeline`() {
        val timeline = beatTimeline(count = 8, everyN = 3)
        val first = timeline.frames.first().features
        val last = timeline.frames.last().features
        // Before the first frame and far past the last, with spans that dwarf
        // the whole track - both ends clamp instead of indexing out of range.
        assertEquals(first.rms, timeline.featuresAt(-5_000L, 100L).rms, 0f)
        assertEquals(first.rms, timeline.featuresAt(-5_000L, 1_000_000L).rms, 0f)
        assertTrue("a span over the whole track sees its beats", timeline.featuresAt(0L, 1_000_000L).beat)
        assertEquals(last.rms, timeline.featuresAt(999_999L, 1_000L).rms, 0f)
        // Degenerate timelines: one frame (no spacing) and none at all.
        val one = FeatureTimeline(listOf(timeline.frames.first()), hopMs = 16L)
        assertEquals(first, one.featuresAt(0L, 100L))
        assertFalse(FeatureTimeline(emptyList(), hopMs = 16L).featuresAt(0L, 100L).beat)
    }

    @Test
    fun `featuresAt returns nearest frame`() {
        val frames = (0 until 100).map { frame(it * 16L, it / 100f) }
        val timeline = FeatureTimeline(frames, hopMs = 16)
        assertEquals(frames[50].features.rms, timeline.featuresAt(50 * 16L).rms, 1e-6f)
        assertEquals(frames[99].features.rms, timeline.featuresAt(999_999L).rms, 1e-6f)
    }

    @Test
    fun `detects a section boundary at a spectral change`() {
        // 40s of quiet then 40s of loud at 60 fps.
        val frames = ArrayList<TimelineFrame>()
        for (i in 0 until 4800) {
            val level = if (i < 2400) 0.1f else 0.9f
            frames += frame(i * 16L, level)
        }
        val timeline = FeatureTimeline(frames, hopMs = 16)
        val sections = timeline.detectSections()
        assertTrue("expected at least one boundary", sections.isNotEmpty())
        val boundary = sections.first()
        val expected = 2400 * 16L
        assertTrue(
            "boundary $boundary should be near $expected",
            kotlin.math.abs(boundary - expected) < 3000,
        )
    }

    @Test
    fun `re-deciding at the analysed settings is a no-op`() {
        val t = analyzed(BeatTuning.SLOW_SENSITIVITY, BeatTuning.SLOW_INTERVAL_MS)
        val same = t.withBeatSensitivity(BeatTuning.SLOW_SENSITIVITY, BeatTuning.SLOW_INTERVAL_MS)
        assertTrue("expected some beats to compare", beats(t).any { it })
        assertEquals(beats(t), beats(same))
    }

    @Test
    fun `re-deciding at a stricter setting drops beats without re-analysis`() {
        val t = analyzed(BeatTuning.SENSITIVITY_DEFAULT, BeatTuning.INTERVAL_MS_DEFAULT)
        val strict = t.withBeatSensitivity(BeatTuning.SLOW_SENSITIVITY, BeatTuning.SLOW_INTERVAL_MS)
        val before = beats(t).count { it }
        val after = beats(strict).count { it }
        assertTrue("stricter settings should flash less, got $after vs $before", after < before)
        assertTrue("but should keep the real kicks, got $after", after > 0)
        // Everything else is untouched - only the beat flags are re-decided.
        assertEquals(t.frames.size, strict.frames.size)
        assertEquals(t.hopMs, strict.hopMs)
        assertEquals(t.key, strict.key)
        assertEquals(t.bpm, strict.bpm, 1e-6f)
        for (i in t.frames.indices) {
            assertEquals(t.frames[i].timeMs, strict.frames[i].timeMs)
            assertEquals(t.frames[i].features.flux, strict.frames[i].features.flux, 0f)
            assertEquals(t.frames[i].features.rms, strict.frames[i].features.rms, 0f)
        }
        // The source timeline is immutable; a re-decide never mutates it.
        assertEquals(before, beats(t).count { it })
    }

    @Test
    fun `a timeline with no onset curve keeps its beats`() {
        // Synthesised (and pre-v2) timelines have flux = 0 everywhere;
        // re-deciding from zeros would silently erase every beat.
        val frames =
            (0 until 10).map {
                TimelineFrame(it * 16L, AudioFeatures(FloatArray(64), FloatArray(128), beat = it % 3 == 0))
            }
        val t = FeatureTimeline(frames, hopMs = 16)
        val same = t.withBeatSensitivity(BeatTuning.SENSITIVITY_MAX, BeatTuning.INTERVAL_MS_MAX)
        assertEquals(beats(t), beats(same))
        assertEquals(4, beats(same).count { it })
    }

    @Test
    fun `suggester maps characteristics to scene CATEGORIES`() {
        // v2 scores an affinity table instead of four rules, so these pin the
        // intent (fast+loud lands percussive, quiet+dark lands ambient, ...)
        // rather than one exact id - retuning a profile inside the same
        // character must not fail the build; changing the character must.
        val percussive = setOf(SceneSuggester.SCENE_MYCELIUM)
        val ambient =
            setOf(
                dev.geode.render.scene.SceneIds.AURORA,
                dev.geode.render.scene.SceneIds.WATER,
            )
        val bright = setOf(dev.geode.render.scene.SceneIds.TUNNEL, dev.geode.render.scene.SceneIds.HYPERSPACE)
        assertTrue(
            SceneSuggester.suggest(bpm = 140f, energy = 0.4f, centroid = 0.3f, pulseConfidence = 0.8f) in percussive,
        )
        assertTrue(SceneSuggester.suggest(bpm = 80f, energy = 0.05f, centroid = 0.2f) in ambient)
        assertTrue(SceneSuggester.suggest(bpm = 120f, energy = 0.3f, centroid = 0.7f) in bright)
        // Harmonic material with a confident chroma should not land on a
        // percussive scene, whatever else it lands on.
        assertTrue(
            SceneSuggester.suggest(bpm = 100f, energy = 0.2f, centroid = 0.3f, chromaConfidence = 0.9f) !in percussive,
        )
    }

    @Test
    fun `suggester scores every declared affinity and fit falls off outside the range`() {
        // Every candidate must be reachable in principle: its own centre
        // scores it at least as well as any single fixed input could.
        for (a in SceneSuggester.AFFINITIES) {
            val centreBpm = (a.tempoBpm.start + a.tempoBpm.endInclusive) / 2f
            val centreEnergy = (a.energy.start + a.energy.endInclusive) / 2f
            val centreBright = (a.brightness.start + a.brightness.endInclusive) / 2f
            val self = SceneSuggester.score(a, centreBpm, centreEnergy, centreBright, 0f, 0f, 0f)
            assertTrue("affinity for ${a.sceneId} scores itself $self at its own centre", self >= 3f)
        }
        assertEquals(1f, SceneSuggester.fit(0.5f, 0f..1f), 1e-6f)
        assertEquals(0f, SceneSuggester.fit(2.5f, 0f..1f), 1e-6f)
        assertTrue(SceneSuggester.fit(1.2f, 0f..1f) in 0.7f..0.9f)
    }

    // ---- drum-channel replay ----------------------------------------------

    /**
     * A cache entry stores bands but not the three onset channels, so loading
     * one has to recompute them. This pins that the replay produces the same
     * values the live path would - if it did not, an analysed track and an
     * unanalysed one would drive the visuals differently.
     */
    @Test
    fun `withDrumChannels reproduces the live path exactly`() {
        val bandCount = 64
        val hop = 60f
        val rate = 48_000

        fun bandsAt(i: Int): FloatArray =
            FloatArray(bandCount) { b ->
                // A kick every 24 frames, a hat every 6, so both channels fire.
                val kick = if (i % 24 == 0 && b in 2..10) 1.4f else 0f
                val hat = if (i % 6 == 0 && b in 48..60) 0.9f else 0f
                kick + hat
            }

        val live = DrumChannels(bandCount, hop, rate)
        val expected = ArrayList<Triple<Float, Float, Float>>()
        val frames = ArrayList<TimelineFrame>()
        for (i in 0 until 400) {
            val b = bandsAt(i)
            live.step(b)
            expected += Triple(live.kick, live.snare, live.hat)
            // Stored WITHOUT the channels, exactly as AnalysisCache.load rebuilds it.
            frames += TimelineFrame((i * 1000L / 60L), AudioFeatures(bands = b, waveform = FloatArray(0)))
        }

        val replayed = FeatureTimeline(frames, 17L, "k", hop).withDrumChannels(rate)
        for (i in 0 until 400) {
            val f = replayed.frames[i].features
            assertEquals("kick @$i", expected[i].first, f.kick, 0f)
            assertEquals("snare @$i", expected[i].second, f.snare, 0f)
            assertEquals("hat @$i", expected[i].third, f.hat, 0f)
        }
        assertTrue("the replay produced no hits at all", expected.any { it.first > 0f } && expected.any { it.third > 0f })
    }

    @Test
    fun `withDrumChannels leaves a bandless timeline alone`() {
        val frames = listOf(TimelineFrame(0L, AudioFeatures(bands = FloatArray(0), waveform = FloatArray(0))))
        val t = FeatureTimeline(frames, 17L, "k", 60f)
        assertSame(t, t.withDrumChannels())
    }
}
