package dev.musicviz.playback

import kotlin.math.ln
import kotlin.random.Random

/**
 * One queue entry, reduced to what an ordering is allowed to look at.
 *
 * Each mode reads one field and ignores the rest, so a caller planning an album
 * shuffle fills in [album] and leaves the others at their defaults. [album]
 * should identify an album uniquely - two artists both having "Greatest Hits" is
 * the caller's problem to key around, not something this can guess.
 */
data class ShuffleTrack(
    val album: String = "",
    val artist: String = "",
    /** Relative likelihood of being drawn early; 0 or less never plays. */
    val weight: Double = 1.0,
)

/** How a queue is ordered before it is handed to the player. */
sealed interface ShuffleMode {
    /** The queue as built. */
    data object InOrder : ShuffleMode

    /** Every track equally likely - the player's own shuffle, reproducibly. */
    data object Tracks : ShuffleMode

    /** Albums in random order, each album played through in its own order. */
    data object Albums : ShuffleMode

    /** Shuffled, then spaced so one artist does not run. */
    data object Spread : ShuffleMode

    /** Shuffled, biased by [ShuffleTrack.weight]. */
    data object Weighted : ShuffleMode
}

/**
 * Queue orderings, computed before the queue reaches the player.
 *
 * ExoPlayer's shuffle is uniform over tracks and lives inside the player as a
 * `ShuffleOrder`, which makes it the wrong place for any of this. An album
 * shuffle written as a `ShuffleOrder` subclass has to fight the player for the
 * permutation; none of it can be shown to the user before they commit to it;
 * and `DefaultShuffleOrder` is not saved, so a restored queue silently reverts
 * to timeline order. Computed here, an ordering is a `List<Int>` that indexes
 * the queue - displayable, testable without a device, and reproducible from its
 * seed, which is what lets a saved queue come back in the order it was in.
 *
 * Every function is total: an empty queue plans to an empty order rather than
 * throwing, because these are called from UI gestures on a queue the player may
 * have emptied underneath them - the same rule [QueueOps] follows.
 */
object ShuffleModes {
    /**
     * The order [tracks] should play in under [mode], as indices into [tracks].
     *
     * The result is a permutation of the queue for every mode except
     * [ShuffleMode.Weighted], which omits tracks weighted out - so callers must
     * read the size of what comes back rather than assuming the queue's.
     */
    fun plan(
        mode: ShuffleMode,
        tracks: List<ShuffleTrack>,
        seed: Long,
    ): List<Int> =
        when (mode) {
            ShuffleMode.InOrder -> tracks.indices.toList()
            ShuffleMode.Tracks -> tracks.indices.shuffled(Random(seed))
            ShuffleMode.Albums -> byAlbum(tracks, Random(seed))
            ShuffleMode.Spread -> spreadArtists(tracks, Random(seed))
            ShuffleMode.Weighted -> byWeight(tracks, Random(seed))
        }

    /** Albums shuffled; `groupBy` leaves each album's tracks in queue order. */
    private fun byAlbum(
        tracks: List<ShuffleTrack>,
        random: Random,
    ): List<Int> {
        val albums = tracks.indices.groupBy { tracks[it].album }
        return albums.keys.shuffled(random).flatMap { albums.getValue(it) }
    }

    /**
     * A shuffle re-spaced so neighbours differ in artist wherever they can.
     *
     * Always take from the artist with the most tracks left that is not the one
     * just played. That is optimal, not just reasonable: it leaves adjacent
     * repeats only when some artist holds more than half the queue, and then
     * exactly `2*most - size - 1` of them, which is the arithmetic minimum. The
     * alternative for that case - refusing, or falling back to queue order -
     * would make "shuffle by artist" fail on the single-artist album it is most
     * likely to be pointed at.
     *
     * Ties break on the order artists first appear in the seeded shuffle, so the
     * whole thing reproduces from [random] alone.
     */
    private fun spreadArtists(
        tracks: List<ShuffleTrack>,
        random: Random,
    ): List<Int> {
        val remaining = LinkedHashMap<String, ArrayDeque<Int>>()
        for (i in tracks.indices.shuffled(random)) {
            remaining.getOrPut(tracks[i].artist) { ArrayDeque() }.addLast(i)
        }
        val order = ArrayList<Int>(tracks.size)
        var last: String? = null
        while (remaining.isNotEmpty()) {
            val artist = fullestOtherThan(remaining, last)
            val queue = remaining.getValue(artist)
            order += queue.removeFirst()
            if (queue.isEmpty()) remaining.remove(artist)
            last = artist
        }
        return order
    }

    /** The artist with the most left that is not [last], or [last] if alone. */
    private fun fullestOtherThan(
        remaining: Map<String, ArrayDeque<Int>>,
        last: String?,
    ): String =
        remaining.entries
            .filter { it.key != last }
            .maxByOrNull { it.value.size }
            ?.key
            ?: remaining.keys.first()

    /**
     * A weighted draw without replacement, by the exponential-race trick:
     * `ln(u)/w` for one uniform `u` per track, largest first. A track twice the
     * weight is twice as likely to precede any given other, which is the
     * property a caller weighting by "not played lately" actually wants.
     *
     * A weight that is zero, negative, or not a number is an exclusion. NaN is
     * not defensiveness: a weight is typically a ratio, and the play count it
     * divides by is zero for every track the user has never played.
     */
    private fun byWeight(
        tracks: List<ShuffleTrack>,
        random: Random,
    ): List<Int> =
        tracks.indices
            .filter { tracks[it].weight.isFinite() && tracks[it].weight > 0.0 }
            // 1 - nextDouble() lands in (0, 1], so the log is never -infinity.
            .map { it to ln(1.0 - random.nextDouble()) / tracks[it].weight }
            .sortedByDescending { (_, key) -> key }
            .map { (index, _) -> index }
}
