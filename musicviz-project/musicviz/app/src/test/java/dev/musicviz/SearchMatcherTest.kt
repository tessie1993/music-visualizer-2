package dev.musicviz

import dev.musicviz.analysis.SearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Headless checks for the search overlay's pure matching/dedup logic. */
class SearchMatcherTest {
    private data class Item(
        val uri: String,
        val title: String,
        val artist: String = "",
        val device: Boolean = false,
    )

    private fun filter(
        query: String,
        items: List<Item>,
        max: Int = SearchMatcher.TRACK_LIMIT,
    ) = SearchMatcher.filterTracks(
        terms = SearchMatcher.terms(query),
        items = items,
        max = max,
        uriOf = { it.uri },
        fieldsOf = { listOf(it.title, it.artist) },
        preferred = { it.device },
    )

    @Test
    fun termsSplitOnAnyWhitespaceAndDropBlanks() {
        assertEquals(listOf("daft", "punk"), SearchMatcher.terms("  daft \t punk\n"))
        assertEquals(emptyList<String>(), SearchMatcher.terms("   "))
        assertEquals(emptyList<String>(), SearchMatcher.terms(""))
    }

    @Test
    fun everyTermMustMatchSomeField() {
        val fields = listOf("Harder Better Faster Stronger", "Daft Punk")
        assertTrue(SearchMatcher.matches(listOf("harder", "punk"), fields))
        assertTrue(SearchMatcher.matches(listOf("faster"), fields))
        // One matching term is not enough when another term misses.
        assertFalse(SearchMatcher.matches(listOf("harder", "beatles"), fields))
        // No terms means no match (callers show the idle empty state instead).
        assertFalse(SearchMatcher.matches(emptyList(), fields))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(SearchMatcher.matches(listOf("PUNK"), listOf("daft punk")))
        assertTrue(SearchMatcher.matches(listOf("punk"), listOf("DAFT PUNK")))
        val hits = filter("AROUND", listOf(Item("u1", "Around the World")))
        assertEquals(1, hits.size)
    }

    @Test
    fun multiTermAndAcrossDifferentFields() {
        val items =
            listOf(
                Item("u1", "One More Time", "Daft Punk"),
                Item("u2", "One", "Metallica"),
            )
        assertEquals(listOf("u1"), filter("one punk", items).map { it.uri })
        assertEquals(listOf("u1", "u2"), filter("one", items).map { it.uri })
        assertEquals(emptyList<String>(), filter("one zeppelin", items).map { it.uri })
    }

    @Test
    fun dedupByUriPrefersDeviceRowsRegardlessOfOrder() {
        val imported = Item("shared", "Song", device = false)
        val device = Item("shared", "Song", device = true)
        // Device first: imported duplicate is dropped.
        assertEquals(listOf(device), filter("song", listOf(device, imported)))
        // Imported first: device row replaces it.
        assertEquals(listOf(device), filter("song", listOf(imported, device)))
        // Two non-preferred duplicates: first one wins.
        val a = Item("shared", "Song A copy 1")
        val b = Item("shared", "Song A copy 2")
        assertEquals(listOf(a), filter("song", listOf(a, b)))
    }

    @Test
    fun dedupKeepsEarlierListPositionWhenDeviceReplacesImported() {
        val items =
            listOf(
                Item("i1", "Song alpha", device = false),
                Item("i2", "Song beta", device = false),
                Item("i1", "Song alpha", device = true),
            )
        // The device row for i1 slots into i1's original (first) position.
        assertEquals(listOf("i1", "i2"), filter("song", items).map { it.uri })
        assertTrue(filter("song", items).first().device)
    }

    @Test
    fun capAppliesAfterDedup() {
        val dupes = (1..10).map { Item("same", "Track $it") }
        val distinct = (1..40).map { Item("u$it", "Track $it") }
        // 10 matching rows collapse to one uri: the cap must not count dupes.
        assertEquals(11, filter("track", dupes + distinct.take(10), max = 30).size)
        // More distinct matches than the cap: exactly max survive, in order.
        val capped = filter("track", distinct, max = 30)
        assertEquals(30, capped.size)
        assertEquals("u1", capped.first().uri)
        assertEquals("u30", capped.last().uri)
    }

    @Test
    fun emptyTermsMatchNothing() {
        val items = listOf(Item("u1", "Anything"))
        assertEquals(emptyList<Item>(), filter("", items))
        assertEquals(emptyList<Item>(), filter("   ", items))
    }
}
