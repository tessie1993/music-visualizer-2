package dev.musicviz

import dev.musicviz.render.scene.Bloom
import dev.musicviz.render.scene.BloomBank
import dev.musicviz.render.scene.HyperspaceCamera
import dev.musicviz.render.scene.HyperspaceJourney
import dev.musicviz.render.scene.HyperspaceMath
import dev.musicviz.render.scene.SpectralSummary
import dev.musicviz.render.scene.VisualStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pins the HYPERSPACE rework: the per-substyle Lipschitz table, the
 * decoupled spin/orbit/breath channels, the multi-body retire, the wrapped
 * clocks, the spectral summary and the journey's confidence/progress wiring.
 *
 * Each of these is the kind of defect that renders SOMETHING - holes that
 * read as shimmer, an orbit that happens to stand still, a wallpaper that
 * pops once every two hours - so each is asserted here instead of eyeballed.
 */
class HyperspaceReworkTest {
    private val eps = 1e-4f

    // ---- the Lipschitz table and the catalog -------------------------------

    /**
     * styleBody() deforms the domain BEFORE the estimator, so the estimate
     * bounds distance in the deformed frame and can overestimate marched
     * space by the deform's Jacobian norm. Every style therefore carries a
     * bound >= 1 in the catalog, and the shader divides by it; a style with
     * a deform and a bound of exactly 1 would march holes into its bodies,
     * and a bound over ~4 would spend the whole step budget creeping.
     */
    @Test
    fun every_substyle_has_a_sane_lipschitz_bound() {
        val styles = VisualStyleCatalog.hyperspace
        assertEquals(11, styles.size)
        for (s in styles) {
            assertTrue("${s.id} lipschitz ${s.lipschitz} < 1", s.lipschitz >= 1f)
            assertTrue("${s.id} lipschitz ${s.lipschitz} is not finite", s.lipschitz.isFinite())
            assertTrue("${s.id} lipschitz ${s.lipschitz} would stall the march", s.lipschitz <= 4f)
        }
        // The styles documented as deforming hardest carry a real bound.
        assertTrue(styles.first { it.id == "hyper_caduceus" }.lipschitz >= 2f)
        assertTrue(styles.first { it.id == "hyper_polytope" }.lipschitz >= 1.9f)
        // And the shader actually divides by the bound.
        assertTrue(
            "hyperspace_frag.glsl no longer divides the estimate by uLipschitz",
            shader.contains("/ max(uLipschitz, 1.0)"),
        )
    }

    @Test
    fun the_substyle_catalog_constants_stay_in_range() {
        for (s in VisualStyleCatalog.hyperspace) {
            assertTrue("${s.id} kaleidoFolds", s.kaleidoFolds in 0..16)
            assertTrue("${s.id} signatureFloor", s.signatureFloor in 0f..1f)
            assertTrue("${s.id} tintHue", s.tintHue in 0f..1f)
            assertTrue("${s.id} tintSat", s.tintSat in 0f..1f)
            assertTrue("${s.id} tintAmount", s.tintAmount in 0f..1f)
            assertTrue("${s.id} phaseRate", s.phaseRate >= 0f && s.phaseRate <= 1f)
            assertTrue("${s.id} phaseBassRate", s.phaseBassRate >= 0f && s.phaseBassRate <= 1f)
            assertTrue("${s.id} driftScale", s.driftScale in 0.1f..3f)
            s.forcedSpecies?.let {
                assertTrue(
                    "${s.id} forcedSpecies $it does not name a species (1..${HyperspaceMath.SPECIES.size})",
                    it in 1..HyperspaceMath.SPECIES.size,
                )
            }
        }
        // Colour identity is per-substyle: every accent that is actually
        // blended sits at its own hue offset.
        val tinted = VisualStyleCatalog.hyperspace.filter { it.tintAmount > 0f }
        assertEquals(10, tinted.size)
        assertEquals(tinted.size, tinted.map { it.tintHue }.distinct().size)
    }

    /** The act that opens must open for the substyle folds too. */
    @Test
    fun breakthrough_releases_the_substyle_fold() {
        for (p in HyperspaceMath.ACT_PROFILES) {
            assertTrue("styleMirror ${p.styleMirror} out of range", p.styleMirror in 0f..1f)
        }
        assertEquals(1f, HyperspaceMath.ACT_PROFILES.first().styleMirror, 0f)
        assertEquals(0f, HyperspaceMath.ACT_PROFILES.last().styleMirror, 0f)
        // Interpolation carries the release smoothly into the last act.
        assertEquals(0.5f, HyperspaceMath.profileAt(3.5f).styleMirror, 1e-3f)
    }

    // ---- decoupled motion ---------------------------------------------------

    @Test
    fun body_spin_at_zero_no_longer_freezes_orbit_or_breath() {
        val b = Bloom()
        b.spawn(Random(3), HyperspaceMath.Species.CORAL, lifetime = 30f, spread = 2f, sizeScale = 0.5f)
        val rot0 = FloatArray(9)
        b.writeRotation(rot0, 0)
        val c0 = b.centre.copyOf()
        val breath0 = b.breath
        repeat(secondsAsFrames(3f)) { b.advance(FRAME, motion = 1f, orbitScale = 1f, spinScale = 0f) }
        assertTrue("orbit froze with spin at 0", dist(c0, b.centre) > 1e-3f)
        assertTrue("breath froze with spin at 0", b.breath > breath0 + 0.05f)
        val rot1 = FloatArray(9)
        b.writeRotation(rot1, 0)
        for (i in 0 until 9) assertEquals("spin 0 still rotated the body", rot0[i], rot1[i], eps)
    }

    @Test
    fun orbit_at_zero_parks_the_centre_but_not_the_spin() {
        val b = Bloom()
        b.spawn(Random(5), HyperspaceMath.Species.GASKET, lifetime = 30f, spread = 2f, sizeScale = 0.5f)
        val rot0 = FloatArray(9)
        b.writeRotation(rot0, 0)
        val c0 = b.centre.copyOf()
        repeat(secondsAsFrames(3f)) { b.advance(FRAME, motion = 1f, orbitScale = 0f, spinScale = 1f) }
        assertEquals("orbit 0 moved the centre", 0f, dist(c0, b.centre), eps)
        val rot1 = FloatArray(9)
        b.writeRotation(rot1, 0)
        var diff = 0f
        for (i in 0 until 9) diff += abs(rot0[i] - rot1[i])
        assertTrue("spin froze with orbit at 0", diff > 1e-3f)
    }

    // ---- the retire loop ----------------------------------------------------

    /**
     * When an act drops from eight bodies to two, the whole excess dissolves
     * inside ONE retire window. The old loop re-picked the same oldest body
     * (retire keeps it oldest) and bailed, so the thinning took one natural
     * death at a time - about ten seconds for a change the act asked for now.
     */
    @Test
    fun the_bank_retires_the_whole_excess_in_one_window() {
        val bank = BloomBank(Random(61))
        repeat(secondsAsFrames(40f)) {
            bank.advance(FRAME, 8, 1f, null, 300f, 2f, 0.5f, 1f, 1f)
        }
        assertEquals(8, bank.aliveCount)
        // One retire window (1.6 s) plus settling frames, nothing like 10 s.
        repeat(secondsAsFrames(2.5f)) {
            bank.advance(FRAME, 2, 0f, null, 300f, 2f, 0.5f, 1f, 1f)
        }
        assertEquals("the excess did not dissolve within one window", 2, bank.aliveCount)
    }

    // ---- wrapped clocks -------------------------------------------------------

    @Test
    fun the_wrap_period_is_a_whole_number_of_turns() {
        // 1000 turns of 2*pi: every multiplier in this family is a multiple
        // of 0.001, so k * period is a whole number of turns for all of them.
        assertEquals(1000.0 * 2.0 * PI, HyperspaceMath.TIME_WRAP_SECONDS.toDouble(), 0.01)
        assertTrue("a wrap under an hour would be pointless churn", HyperspaceMath.TIME_WRAP_SECONDS >= 3600f)
    }

    @Test
    fun the_scene_clock_is_wrapped() {
        assertTrue(
            "HyperspaceScene no longer wraps its uTime clock - it will drift into float32 mush",
            sceneSource.contains("time = (time + dt) % HyperspaceMath.TIME_WRAP_SECONDS"),
        )
    }

    /**
     * Every sine multiplier of uTime in the shader must be a multiple of
     * 0.001, or the wrap lands mid-cycle and the image pops once every ~105
     * minutes. floor()-seeded noise reseeds at the wrap by design, but the
     * three-decimals rule holds for those literals too, so ALL of them are
     * checked without parsing context.
     */
    @Test
    fun every_shader_multiplier_of_the_clock_survives_the_wrap() {
        val hits =
            Regex("""uTime\s*\*\s*([0-9]+(?:\.[0-9]+)?)""").findAll(shader).map { it.groupValues[1] }.toList() +
                Regex("""([0-9]+(?:\.[0-9]+)?)\s*\*\s*uTime""").findAll(shader).map { it.groupValues[1] }.toList()
        assertTrue("no uTime multipliers found - the audit regex went stale", hits.isNotEmpty())
        for (lit in hits) {
            val turns = lit.toDouble() * 1000.0
            assertTrue(
                "uTime * $lit is not a multiple of 0.001: it jumps at the ${HyperspaceMath.TIME_WRAP_SECONDS}s wrap",
                abs(turns - Math.round(turns)) < 1e-3,
            )
        }
        // uStylePhase wraps at 1, so its consumers multiply by WHOLE numbers.
        for (m in Regex("""uStylePhase\s*\*\s*([0-9]+(?:\.[0-9]+)?)""").findAll(shader)) {
            val v = m.groupValues[1].toDouble()
            assertTrue(
                "uStylePhase * ${m.groupValues[1]} is not whole: it jumps when the phase wraps at 1",
                abs(v - Math.round(v)) < 1e-6,
            )
        }
    }

    @Test
    fun the_camera_crosses_its_wrap_without_a_jump() {
        val cam = HyperspaceCamera()
        // Land just short of the wrap, then walk across it frame by frame.
        cam.advance(HyperspaceMath.TIME_WRAP_SECONDS - 0.25f, distance = 5.5f, drift = 1f)
        val prev = FloatArray(3)
        repeat(secondsAsFrames(1f)) {
            prev[0] = cam.position[0]
            prev[1] = cam.position[1]
            prev[2] = cam.position[2]
            cam.advance(FRAME, distance = 5.5f, drift = 1f)
            assertTrue(
                "camera teleported ${dist(prev, cam.position)} units crossing the wrap",
                dist(prev, cam.position) < 0.06f,
            )
        }
    }

    // ---- the audio wiring -----------------------------------------------------

    @Test
    fun the_slew_limiter_bounds_value_and_rate() {
        var v = 0f
        v = HyperspaceMath.slewLimit(v, 1f, FRAME, 2.2f, 1.1f)
        assertEquals("rise rate not honoured", 2.2f * FRAME, v, eps)
        // A wild target is clamped before the rate limit.
        v = HyperspaceMath.slewLimit(v, 99f, FRAME, 2.2f, 1.1f)
        assertTrue(v <= 2f * 2.2f * FRAME + eps)
        var w = 1f
        w = HyperspaceMath.slewLimit(w, 0f, FRAME, 2.2f, 1.1f)
        assertEquals("fall rate not honoured", 1f - 1.1f * FRAME, w, eps)
        // Long runs stay inside 0..1 whatever is fed in.
        var x = 0f
        val rng = Random(9)
        repeat(10_000) {
            x = HyperspaceMath.slewLimit(x, rng.nextFloat() * 4f - 1f, FRAME, 2.2f, 1.1f)
            assertTrue(x in 0f..1f)
        }
    }

    @Test
    fun the_beat_gate_fades_with_confidence_but_never_mutes() {
        assertEquals(HyperspaceMath.BEAT_GATE_FLOOR, HyperspaceMath.beatGate(0f), eps)
        assertEquals(1f, HyperspaceMath.beatGate(1f), eps)
        var prev = 0f
        var c = 0f
        while (c <= 1f) {
            val g = HyperspaceMath.beatGate(c)
            assertTrue("gate left its band at $c", g in HyperspaceMath.BEAT_GATE_FLOOR..1f)
            assertTrue("gate is not monotone at $c", g >= prev - eps)
            prev = g
            c += 0.02f
        }
        // A legacy full-strength impulse through the floor still clears the
        // bank's spawn threshold (0.18), so tracker-less audio keeps spawning.
        assertTrue(HyperspaceMath.BEAT_GATE_FLOOR * 1f > 0.18f)
    }

    @Test
    fun track_progress_walks_a_quiet_track_out_of_threshold() {
        val far = HyperspaceJourney()
        repeat(secondsAsFrames(60f)) {
            far.advance(FRAME, energy = 0f, mode = HyperspaceMath.JOURNEY_MUSIC, holdAct = 0, cycleSeconds = 30f, pace = 1f, progress = 0.9f)
        }
        assertTrue("a nearly-finished quiet track is still parked in THRESHOLD", far.actPosition > 0.8f)
        // Conservative: the floor may not push past the second act.
        assertTrue("the progress floor overrode the music", far.actPosition < 1.6f)

        // Unknown progress (the documented 0) changes nothing.
        val unknown = HyperspaceJourney()
        repeat(secondsAsFrames(60f)) {
            unknown.advance(FRAME, 0f, HyperspaceMath.JOURNEY_MUSIC, 0, 30f, 1f)
        }
        assertEquals(0f, unknown.actPosition, 1e-3f)
        assertEquals(0, unknown.act)
    }

    // ---- the spectrum summary ---------------------------------------------------

    @Test
    fun the_band_summary_folds_the_spectrum_and_survives_garbage() {
        val out = FloatArray(SpectralSummary.SIZE)
        // 64 bands, energy only in the first bucket's slice.
        val bands = FloatArray(64) { if (it < 4) 1f else 0f }
        SpectralSummary.summarize(bands, out)
        assertEquals(1f, out[0], eps)
        for (i in 1 until out.size) assertEquals("bucket $i leaked", 0f, out[i], eps)

        // Empty input (synthesised features) zeroes rather than staling.
        SpectralSummary.summarize(FloatArray(0), out)
        for (v in out) assertEquals(0f, v, 0f)

        // Fewer bands than buckets still fills every bucket.
        SpectralSummary.summarize(FloatArray(4) { 0.5f }, out)
        for (v in out) assertEquals(0.5f, v, eps)

        // And the smoothed levels are bounded whatever is fed in.
        val summary = SpectralSummary()
        val wild = FloatArray(64) { 1e9f }
        repeat(600) { summary.advance(wild, FRAME) }
        for (v in summary.levels) {
            assertTrue("level $v escaped its ceiling", v.isFinite() && v in 0f..SpectralSummary.LEVEL_CEILING)
        }
        // Attack is faster than release: one loud frame registers strongly...
        val s2 = SpectralSummary()
        s2.advance(FloatArray(64) { 1f }, FRAME)
        val afterHit = s2.levels[0]
        assertTrue("attack too slow: $afterHit", afterHit > 0.2f)
        // ...and decays over frames rather than snapping off.
        s2.advance(FloatArray(64), FRAME)
        assertTrue("release snapped to ${s2.levels[0]}", s2.levels[0] > afterHit * 0.7f)
    }

    // ---- helpers ---------------------------------------------------------------

    private companion object {
        const val FRAME = 1f / 60f
    }

    private fun secondsAsFrames(seconds: Float): Int = (seconds / FRAME).toInt()

    private fun dist(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private val shader: String by lazy { repoFile("app/src/main/res/raw/hyperspace_frag.glsl") }
    private val sceneSource: String by lazy { repoFile("app/src/main/java/dev/musicviz/render/scene/HyperspaceScene.kt") }

    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
