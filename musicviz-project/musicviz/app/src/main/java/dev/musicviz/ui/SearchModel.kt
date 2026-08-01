package dev.musicviz.ui

import dev.musicviz.analysis.SearchMatcher

/*
 * The search overlay's query logic, lifted out of the composable.
 *
 * SearchScreen used to build the candidate list, join the two track sources,
 * dedupe and filter inline across four `remember` blocks, which made the
 * merge rules (which source wins a duplicate uri, what each source matches
 * on) reachable only through a Compose test. They are ordinary data rules,
 * so they live here as pure functions instead.
 *
 * The 250 ms debounce stays in the composable for now: it is genuinely UI
 * timing, and moving it into a flow belongs with the feature-ViewModel step
 * rather than ahead of it.
 */

/** One merged track result row (device index or imported library). */
data class SearchTrackRow(
    val uri: String,
    val title: String,
    val subtitle: String,
    /** Text the query is matched against; source-specific, see [SearchModel]. */
    val fields: List<String>,
    val fromDevice: Boolean,
)

/** Everything the overlay renders for one query. */
data class SearchResults(
    val tracks: List<SearchTrackRow> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val presets: List<Preset> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && playlists.isEmpty() && presets.isEmpty()
}

object SearchModel {
    /**
     * Builds the merged result set for [query].
     *
     * Device rows and library rows are matched on deliberately different
     * fields — a device row knows its containing [DeviceTrack.folder], a
     * library row knows its [LibraryTrack.genre] — and the two are then
     * deduped by uri with the device row preferred, because that is the
     * entry whose metadata the system keeps up to date.
     *
     * A blank query yields no results rather than everything: the overlay
     * shows its "type to search" hint in that state.
     */
    fun search(
        query: String,
        deviceTracks: List<DeviceTrack>,
        libraryTracks: List<LibraryTrack>,
        playlists: List<MusicPlaylist>,
        presets: List<Preset>,
    ): SearchResults {
        val terms = SearchMatcher.terms(query)
        if (terms.isEmpty()) return SearchResults()
        return SearchResults(
            tracks = matchTracks(terms, deviceTracks, libraryTracks),
            playlists = playlists.filter { SearchMatcher.matches(terms, listOf(it.name)) },
            presets = presets.filter { SearchMatcher.matches(terms, listOf(it.name)) },
        )
    }

    private fun matchTracks(
        terms: List<String>,
        deviceTracks: List<DeviceTrack>,
        libraryTracks: List<LibraryTrack>,
    ): List<SearchTrackRow> {
        val candidates =
            deviceTracks.map { t ->
                SearchTrackRow(
                    uri = t.uri,
                    title = t.title,
                    subtitle = subtitleOf(t.artist, t.album),
                    fields = listOf(t.title, t.artist, t.album, t.folder),
                    fromDevice = true,
                )
            } +
                libraryTracks.map { t ->
                    SearchTrackRow(
                        uri = t.uri,
                        title = t.title,
                        subtitle = subtitleOf(t.artist, t.album),
                        fields = listOf(t.title, t.artist, t.album, t.genre),
                        fromDevice = false,
                    )
                }
        return SearchMatcher.filterTracks(
            terms = terms,
            items = candidates,
            uriOf = { it.uri },
            fieldsOf = { it.fields },
            preferred = { it.fromDevice },
        )
    }

    private fun subtitleOf(
        artist: String,
        album: String,
    ): String = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
}
