package net.clickarr.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.Collection
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.Movie
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.Show
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf

/**
 * "Create channels for me": reads the library once and hands what it found to [SuggestionRules], which
 * proposes a short, practical starting lineup. Capped at [MAX] so it is a lineup, not a flood; nothing is
 * created until the viewer picks.
 */
@Singleton
class ChannelSuggester @Inject constructor(private val registry: ProviderRegistry, private val channels: ChannelRepository) {
    data class Suggestion(
        val name: String,
        val source: ProgrammingSource,
        val icon: ChannelIcon?,
        val order: OrderingMode,
        val slotRounding: Duration?,
        /** Why it is on the list, for the picker: "212 episodes, start to finish", "Your collection: 6 movies, in order". */
        val reason: String,
        val runs: EpisodeRuns? = null,
    )

    suspend fun suggest(): Outcome<List<Suggestion>> {
        val provider = registry.primary ?: return Outcome.Failure(ClickarrError.Invalid("No server connected"))
        val libraries = when (val r = provider.libraries()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val scan = scan(provider, libraries)
        val existing = channels.all().map { it.name.lowercase() }.toSet()
        return Outcome.Success(SuggestionRules.plan(scan, existing))
    }

    private suspend fun scan(provider: MediaProvider, libraries: List<Library>): LibraryScan {
        val shows = LinkedHashMap<Library, List<Show>>()
        val movies = LinkedHashMap<Library, List<Movie>>()
        val collections = ArrayList<Pair<Library, Collection>>()
        for (lib in libraries) {
            when (lib.kind) {
                LibraryKind.SHOWS -> shows[lib] = allPages { provider.shows(lib.ref, it) }.orEmpty()
                LibraryKind.MOVIES -> movies[lib] = allPages { provider.movies(lib.ref, it) }.orEmpty()
                LibraryKind.OTHER -> continue
            }
            (provider.collections(lib.ref) as? Outcome.Success)?.value?.forEach { collections += lib to it }
        }
        val playlists = (provider.playlists() as? Outcome.Success)?.value.orEmpty()
        return LibraryScan(shows, movies, collections, playlists)
    }

    private suspend fun <T> allPages(fetch: suspend (Page) -> Outcome<PageOf<T>>): List<T>? {
        val all = ArrayList<T>()
        var page = Page.first()
        while (true) {
            val r = fetch(page) as? Outcome.Success ?: return if (all.isEmpty()) null else all
            all += r.value.items
            if (!r.value.hasMore || all.size >= MAX_ITEMS_SCANNED) return all
            page = page.next()
        }
    }

    companion object {
        const val MAX = 12
        private const val MAX_ITEMS_SCANNED = 2_000
    }
}
