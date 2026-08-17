package dev.geode.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pin on the cache key derivation ([AnalysisCache.cacheKey]).
 *
 * Historical bug: the on-disk analysis cache was keyed by a SHA-1 of the URI
 * string alone. Replace the file behind an unchanged URI - re-download a
 * track, re-export a mix, overwrite `Mix.wav` - and the cache kept serving
 * the OLD audio's timeline: its beat grid, sections and key were replayed
 * over the new audio in playback intelligence and in video export. Folding
 * the source's size and mtime into the key makes any content change a clean
 * cache miss (the stale entry is simply never addressed again and ages out
 * of the LRU).
 */
class AnalysisCacheKeyTest {
    @Test
    fun `same inputs derive the same key`() {
        assertEquals(
            AnalysisCache.cacheKey("content://media/audio/1", 1234L, 99_000L),
            AnalysisCache.cacheKey("content://media/audio/1", 1234L, 99_000L),
        )
    }

    @Test
    fun `a size change under the same uri changes the key`() {
        assertNotEquals(
            AnalysisCache.cacheKey("content://media/audio/1", 1234L, 99_000L),
            AnalysisCache.cacheKey("content://media/audio/1", 5678L, 99_000L),
        )
    }

    @Test
    fun `an mtime change under the same uri and size changes the key`() {
        assertNotEquals(
            AnalysisCache.cacheKey("content://media/audio/1", 1234L, 99_000L),
            AnalysisCache.cacheKey("content://media/audio/1", 1234L, 100_000L),
        )
    }

    @Test
    fun `different uris never share a key even with equal stamps`() {
        assertNotEquals(
            AnalysisCache.cacheKey("content://media/audio/1", 0L, 0L),
            AnalysisCache.cacheKey("content://media/audio/2", 0L, 0L),
        )
    }

    @Test
    fun `the stamp cannot be smuggled across fields`() {
        // "10|1" vs "1|01"-style collisions: the separator keeps size and
        // mtime from concatenating into the same digest input.
        assertNotEquals(
            AnalysisCache.cacheKey("u", 11L, 1L),
            AnalysisCache.cacheKey("u", 1L, 11L),
        )
    }

    @Test
    fun `the key is a valid filename`() {
        val key = AnalysisCache.cacheKey("content://com.provider/tree/A%20B/doc/x.flac", 42L, 7L)
        assertEquals(40, key.length) // SHA-1 hex
        assertTrue(key.all { it in "0123456789abcdef" })
    }
}
