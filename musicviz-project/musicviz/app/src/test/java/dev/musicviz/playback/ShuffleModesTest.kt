package dev.musicviz.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * Orderings computed before the queue reaches the player.
 *
 * ExoPlayer's shuffle is uniform over tracks and lives inside the player as a
 * `ShuffleOrder`, so an album shuffle written that way has to fight the player
 * for the permutation and still cannot be shown to the user before they commit
 * to it. Computed here instead, an ordering is a `List<Int>` - testable without
 * a device, displayable before `setMediaItems`, and saveable, which is the one
 * thing `DefaultShuffleOrder` does not do.
 *
 * Every mode is seeded, because "shuffle" that cannot be reproduced cannot be
 * tested and cannot be restored with a saved queue.
 */
class ShuffleModesTest {
    private fun tracks(
        albums: List<String> = emptyList(),
        artists: List<String> = emptyList(),
        weights: List<Double> = emptyList(),
        count: Int = max(albums.size, max(artists.size, weights.size)),
    ): List<ShuffleTrack> =
        List(count) { i ->
            ShuffleTrack(
                album = albums.getOrElse(i) { "" },
                artist = artists.getOrElse(i) { "" },
                weight = weights.getOrElse(i) { 1.0 },
            )
        }

    private fun isPermutationOf(
        order: List<Int>,
        count: Int,
    ): Boolean = order.sorted() == (0 until count).toList()

    @Test
    fun `in order returns the queue as built`() {
        assertEquals(
            listOf(0, 1, 2, 3),
            ShuffleModes.plan(ShuffleMode.InOrder, tracks(count = 4), seed = 7L),
        )
    }

    @Test
    fun `a track shuffle is a permutation and nothing else`() {
        val order = ShuffleModes.plan(ShuffleMode.Tracks, tracks(count = 50), seed = 7L)
        assertTrue("dropped or duplicated a track", isPermutationOf(order, 50))
    }

    @Test
    fun `the same seed gives the same shuffle and a different one does not`() {
        // A saved queue restores its order by replaying the seed, so this is a
        // stored contract, not a test convenience.
        val a = ShuffleModes.plan(ShuffleMode.Tracks, tracks(count = 30), seed = 1L)
        val b = ShuffleModes.plan(ShuffleMode.Tracks, tracks(count = 30), seed = 1L)
        val c = ShuffleModes.plan(ShuffleMode.Tracks, tracks(count = 30), seed = 2L)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `an album shuffle keeps each album in its own order`() {
        val albums = listOf("A", "A", "A", "B", "B", "C")
        val order = ShuffleModes.plan(ShuffleMode.Albums, tracks(albums = albums), seed = 3L)
        assertTrue(isPermutationOf(order, albums.size))
        for (album in albums.distinct()) {
            val expected = albums.indices.filter { albums[it] == album }
            assertEquals("album $album was reordered internally", expected, order.filter { albums[it] == album })
        }
    }

    @Test
    fun `an album shuffle never interleaves two albums`() {
        val albums = listOf("A", "A", "A", "B", "B", "C")
        val played = ShuffleModes.plan(ShuffleMode.Albums, tracks(albums = albums), seed = 3L).map { albums[it] }
        // Each album's name appears as one unbroken run.
        assertEquals(albums.distinct().size, played.zipWithNext().count { (a, b) -> a != b } + 1)
    }

    @Test
    fun `an album shuffle actually reorders the albums`() {
        val albums = List(12) { "album${it / 2}" }
        val order = ShuffleModes.plan(ShuffleMode.Albums, tracks(albums = albums), seed = 5L)
        assertNotEquals(albums.indices.toList(), order)
    }

    @Test
    fun `a spread shuffle does not play the same artist twice in a row`() {
        val artists = listOf("a", "a", "a", "b", "b", "c", "c", "d")
        val played = ShuffleModes.plan(ShuffleMode.Spread, tracks(artists = artists), seed = 11L).map { artists[it] }
        assertEquals("adjacent tracks share an artist", 0, played.zipWithNext().count { (x, y) -> x == y })
    }

    @Test
    fun `a spread shuffle repeats as little as the counts allow`() {
        // Four of five tracks are one artist, so two adjacent pairs are
        // unavoidable: max(0, 2*4 - 5 - 1). Anything more is a bad interleave,
        // and throwing or falling back to the input would both be worse.
        val artists = listOf("a", "a", "a", "a", "b")
        val played = ShuffleModes.plan(ShuffleMode.Spread, tracks(artists = artists), seed = 11L).map { artists[it] }
        assertEquals(2, played.zipWithNext().count { (x, y) -> x == y })
    }

    @Test
    fun `a spread shuffle is still a permutation`() {
        val artists = List(40) { "artist${it % 6}" }
        val order = ShuffleModes.plan(ShuffleMode.Spread, tracks(artists = artists), seed = 13L)
        assertTrue(isPermutationOf(order, artists.size))
    }

    @Test
    fun `one artist for everything degrades to a plain shuffle`() {
        val artists = List(8) { "only" }
        val order = ShuffleModes.plan(ShuffleMode.Spread, tracks(artists = artists), seed = 13L)
        assertTrue(isPermutationOf(order, 8))
    }

    @Test
    fun `a weighted shuffle drops anything weighted out`() {
        val weights = listOf(1.0, 0.0, 1.0, -3.0, Double.NaN, 1.0)
        val order = ShuffleModes.plan(ShuffleMode.Weighted, tracks(weights = weights), seed = 17L)
        // Zero means "never", and so must a negative or a NaN arrived at by
        // dividing by a play count of zero.
        assertEquals(listOf(0, 2, 5), order.sorted())
    }

    @Test
    fun `a weighted shuffle with equal weights is a permutation`() {
        val order = ShuffleModes.plan(ShuffleMode.Weighted, tracks(weights = List(20) { 2.0 }), seed = 19L)
        assertTrue(isPermutationOf(order, 20))
    }

    @Test
    fun `weight moves a track earlier`() {
        // Averaged over fixed seeds, so this asserts the bias without asserting
        // any single draw: track 0 is 50x, track 1 is 1x, the rest are 1x.
        val weights = listOf(50.0) + List(9) { 1.0 }
        val ranks =
            (1L..200L).map { seed ->
                val order = ShuffleModes.plan(ShuffleMode.Weighted, tracks(weights = weights), seed = seed)
                order.indexOf(0) to order.indexOf(1)
            }
        val heavy = ranks.sumOf { it.first } / ranks.size.toDouble()
        val light = ranks.sumOf { it.second } / ranks.size.toDouble()
        assertTrue("a 50x weight did not come earlier on average ($heavy vs $light)", heavy < light)
    }

    @Test
    fun `every mode survives an empty queue`() {
        for (mode in listOf(ShuffleMode.InOrder, ShuffleMode.Tracks, ShuffleMode.Albums, ShuffleMode.Spread, ShuffleMode.Weighted)) {
            assertEquals("$mode on an empty queue", emptyList<Int>(), ShuffleModes.plan(mode, emptyList(), seed = 1L))
        }
    }

    @Test
    fun `every mode survives a single track`() {
        for (mode in listOf(ShuffleMode.InOrder, ShuffleMode.Tracks, ShuffleMode.Albums, ShuffleMode.Spread, ShuffleMode.Weighted)) {
            assertEquals("$mode on one track", listOf(0), ShuffleModes.plan(mode, tracks(count = 1), seed = 1L))
        }
    }
}
