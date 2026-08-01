package dev.musicviz

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.analysis.AnalysisCache
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.FeatureExtractor
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.TimelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Guards the analysis cache's disk format and, since v2, its beat contract.
 *
 * Historical bug: the cache stored the *decided* beat flags, keyed only by a
 * SHA-1 of the track URI. The offline analyzer never received the user's beat
 * sensitivity either, so every cached timeline - and therefore every video
 * export and every section-driven decision - was pinned to the shipped
 * defaults (2.5 sigma / 333 ms) whatever the sliders said, and a later
 * settings change could never reach an already-analysed track.
 *
 * v2 stores the raw onset curve instead and decides the beats at read time,
 * so one entry serves every setting. These tests pin that down: the same
 * settings must survive a cache roundtrip unchanged, different settings must
 * change the beats read back from an *unchanged* file, and v1 entries (which
 * carry no onset curve and cannot be re-thresholded) must be discarded rather
 * than misread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalysisCacheTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val defaultSigma = FeatureExtractor.SIGMA_DEFAULT
    private val defaultInterval = FeatureExtractor.INTERVAL_MS_DEFAULT

    private fun timeline(): FeatureTimeline {
        val frames =
            (0 until 5).map { i ->
                TimelineFrame(
                    timeMs = i * 17L,
                    features =
                        AudioFeatures(
                            bands = FloatArray(8) { (it + i) / 10f },
                            waveform = FloatArray(16) { kotlin.math.sin(it * 0.3f + i) * 0.8f },
                            rms = 0.4f + i * 0.01f,
                            bass = 0.5f,
                            mid = 0.3f,
                            treble = 0.1f,
                            onset = 0.2f,
                            beat = i % 2 == 0,
                            bpm = 124f,
                            centroid = 1800f,
                        ),
                )
            }
        return FeatureTimeline(frames, hopMs = 17L, key = "A minor")
    }

    /**
     * A slow, sparse track: a kick every second with softer transients every
     * 250 ms in between - the material whose spurious flashes the sensitivity
     * sliders exist to suppress. Run through a real [FeatureExtractor] so the
     * frames carry a real onset curve.
     */
    private fun analyzedTimeline(
        sigma: Float,
        intervalMs: Float,
    ): FeatureTimeline {
        val extractor = FeatureExtractor(64, hopRateHz = 60f)
        extractor.beatThresholdSigma = sigma
        extractor.beatMinIntervalMs = intervalMs
        val waveform = FloatArray(128)
        val frames = ArrayList<TimelineFrame>(900)
        for (frame in 0 until 900) {
            val kick = frame % 60 == 0
            val minor = !kick && frame % 15 == 0
            val bands =
                FloatArray(64) { i ->
                    when {
                        i >= 16 -> 0.05f
                        kick -> 0.65f
                        minor -> 0.40f
                        else -> 0.05f
                    }
                }
            frames += TimelineFrame(frame * 1000L / 60L, extractor.extract(bands, waveform, 44100))
        }
        return FeatureTimeline(frames, hopMs = 16L, key = "A minor", hopRateHz = 60f)
    }

    private fun beatsOf(t: FeatureTimeline): List<Boolean> = t.frames.map { it.features.beat }

    private fun cacheFile(uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return File(File(ctx.filesDir, "analysis"), digest.joinToString("") { "%02x".format(it) } + ".mvac")
    }

    @Test
    fun roundtripPreservesFramesKeyAndBpm() {
        val uri = Uri.parse("content://media/audio/1234")
        AnalysisCache.clear(ctx)
        assertNull(AnalysisCache.load(ctx, uri, defaultSigma, defaultInterval))
        val t = timeline()
        AnalysisCache.save(ctx, uri, t)
        val back = AnalysisCache.load(ctx, uri, defaultSigma, defaultInterval)!!
        assertEquals(t.frames.size, back.frames.size)
        assertEquals(t.hopMs, back.hopMs)
        assertEquals("A minor", back.key)
        assertEquals(124f, back.bpm, 0.01f)
        for (i in t.frames.indices) {
            val a = t.frames[i].features
            val b = back.frames[i].features
            assertEquals(t.frames[i].timeMs, back.frames[i].timeMs)
            // A synthesised timeline carries no onset curve, so the stored
            // flags stand: re-deciding from all-zero flux must not erase them.
            assertEquals(a.beat, b.beat)
            assertEquals(a.rms, b.rms, 1e-4f)
            for (j in a.bands.indices) assertEquals(a.bands[j], b.bands[j], 1f / 4096f)
            for (j in a.waveform.indices) assertEquals(a.waveform[j], b.waveform[j], 1f / 8192f)
        }
    }

    @Test
    fun evictionCapsEntryCount() {
        AnalysisCache.clear(ctx)
        val t = timeline()
        for (i in 0 until 20) {
            AnalysisCache.save(ctx, Uri.parse("content://media/audio/$i"), t)
        }
        assertTrue("count=${AnalysisCache.entryCount(ctx)}", AnalysisCache.entryCount(ctx) <= 15)
    }

    @Test
    fun cacheRoundtripKeepsTheLiveBeatsAtTheSameSettings() {
        val uri = Uri.parse("content://media/audio/slow-1")
        AnalysisCache.clear(ctx)
        val live = analyzedTimeline(FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)
        AnalysisCache.save(ctx, uri, live)
        val back = AnalysisCache.load(ctx, uri, FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)!!
        assertTrue("expected some beats", beatsOf(live).any { it })
        assertEquals(beatsOf(live), beatsOf(back))
        // The stored onset curve is what makes that possible; it is written at
        // full precision, so the replayed gate sees identical numbers.
        assertTrue("flux must survive the roundtrip", back.frames.any { it.features.flux > 0f })
        for (i in live.frames.indices) {
            assertEquals(live.frames[i].features.flux, back.frames[i].features.flux, 0f)
        }
    }

    @Test
    fun sensitivityChangeReachesAnUnchangedCacheEntry() {
        val uri = Uri.parse("content://media/audio/slow-2")
        AnalysisCache.clear(ctx)
        // Analysed at the defaults...
        val analysed = analyzedTimeline(FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_DEFAULT)
        AnalysisCache.save(ctx, uri, analysed)

        val atDefault =
            AnalysisCache.load(ctx, uri, FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_DEFAULT)!!
        val atSlow = AnalysisCache.load(ctx, uri, FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)!!

        assertEquals(beatsOf(analysed), beatsOf(atDefault))
        assertNotEquals(beatsOf(atDefault), beatsOf(atSlow))
        val defaultCount = beatsOf(atDefault).count { it }
        val slowCount = beatsOf(atSlow).count { it }
        assertTrue("slow preset should flash less, got $slowCount vs $defaultCount", slowCount < defaultCount)
        // ...and no re-analysis was needed: still the one entry on disk.
        assertEquals(1, AnalysisCache.entryCount(ctx))
        assertTrue(cacheFile(uri).exists())
    }

    /**
     * A v2 header whose lengths are garbage - the shape a crash or a full disk
     * leaves behind - must be refused on the header alone, before those
     * lengths reach `FloatArray(...)`.
     *
     * Note what this test can and cannot see: [AnalysisCache.load] wraps the
     * read in runCatching, so an entry with an absurd `bandCount` ends up null
     * either way. What the header check changes is that the doomed allocation
     * is never attempted - and that is invisible from here.
     */
    @Test
    fun garbageHeaderLengthsAreRejected() {
        AnalysisCache.clear(ctx)
        for ((i, lengths) in listOf(1_000_000_000 to 16, 8 to 1_000_000_000, -1 to 16, 8 to -1).withIndex()) {
            val uri = Uri.parse("content://media/audio/corrupt-$i")
            val f = cacheFile(uri)
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { d ->
                d.writeInt(0x4D564143)
                d.writeInt(2)
                d.writeLong(16L)
                d.writeFloat(60f)
                d.writeUTF("A minor")
                d.writeInt(1)
                d.writeInt(lengths.first)
                d.writeInt(lengths.second)
                // Truncated here, exactly as an interrupted write leaves it.
            }
            assertNull("bandCount/waveSize $lengths", AnalysisCache.load(ctx, uri, defaultSigma, defaultInterval))
            assertFalse("damaged entry should be deleted", f.exists())
        }
    }

    @Test
    fun v1EntriesAreDiscardedNotMisread() {
        val uri = Uri.parse("content://media/audio/legacy")
        AnalysisCache.clear(ctx)
        val f = cacheFile(uri)
        f.parentFile?.mkdirs()
        // Exactly the v1 layout: no hopRateHz header, no per-frame flux.
        DataOutputStream(f.outputStream().buffered()).use { d ->
            d.writeInt(0x4D564143)
            d.writeInt(1)
            d.writeLong(17L)
            d.writeUTF("A minor")
            d.writeInt(1)
            d.writeInt(8)
            d.writeInt(16)
            d.writeLong(0L)
            repeat(8) { d.writeShort(100) }
            repeat(16) { d.writeShort(200) }
            repeat(7) { d.writeFloat(0.5f) }
            d.writeByte(1)
        }
        assertTrue(f.exists())
        assertNull(AnalysisCache.load(ctx, uri, defaultSigma, defaultInterval))
        assertFalse("stale entry should be deleted, not left to fail forever", f.exists())
    }
}
