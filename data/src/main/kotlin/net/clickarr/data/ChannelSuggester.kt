package net.clickarr.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.Page
import net.clickarr.provider.api.PageOf

/**
 * "Create channels for me": reads the library once and proposes a short, practical set of channels,
 * the kind a person would make by hand given an afternoon. Collections first (someone curated them),
 * then genres and decades with enough material to fill a day, then a movies channel and playlists.
 * Capped at [MAX] so it is a starting lineup, not a flood; nothing is created until the viewer picks.
 */
@Singleton
class ChannelSuggester @Inject constructor(private val registry: ProviderRegistry, private val channels: ChannelRepository) {
    data class Suggestion(
        val name: String,
        val source: ProgrammingSource,
        val icon: ChannelIcon?,
        val order: OrderingMode,
        val slotRounding: Duration?,
        /** Why it is on the list, for the picker: "14 comedies", "Your Lord of the Rings collection". */
        val reason: String,
    )

    suspend fun suggest(): Outcome<List<Suggestion>> {
        val provider = registry.primary
            ?: return Outcome.Failure(net.clickarr.core.common.ClickarrError.Invalid("No server connected"))
        val libraries = when (val r = provider.libraries()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val existing = channels.all().map { it.name.lowercase() }.toSet()
        val out = ArrayList<Suggestion>()
        for (lib in libraries) {
            when (lib.kind) {
                LibraryKind.SHOWS -> out += showsLibrary(provider, lib)
                LibraryKind.MOVIES -> out += moviesLibrary(provider, lib)
                LibraryKind.OTHER -> Unit
            }
        }
        (provider.playlists() as? Outcome.Success)?.value?.take(MAX_PLAYLISTS)?.forEach { p ->
            out += Suggestion(p.name, ProgrammingSource.Playlist(p.ref), glyph("star"), OrderingMode.SEQUENTIAL, null, "Your playlist")
        }
        return Outcome.Success(out.filter { it.name.lowercase() !in existing }.distinctBy { it.name.lowercase() }.take(MAX))
    }

    private suspend fun showsLibrary(provider: MediaProvider, lib: Library): List<Suggestion> {
        val shows = allPages { provider.shows(lib.ref, it) } ?: return emptyList()
        val out = ArrayList<Suggestion>()
        out += collections(provider, lib)
        out += byGenre(lib, shows, minimum = MIN_SHOWS_PER_GENRE, unit = "shows", rounding = 30.minutes)
        out += byDecade(lib, shows, minimum = MIN_SHOWS_PER_DECADE, unit = "shows", rounding = 30.minutes)
        return out
    }

    private suspend fun moviesLibrary(provider: MediaProvider, lib: Library): List<Suggestion> {
        val movies = allPages { provider.movies(lib.ref, it) } ?: return emptyList()
        val out = ArrayList<Suggestion>()
        out += collections(provider, lib)
        if (movies.size >= MIN_MOVIES_FOR_ALL) {
            out += Suggestion(
                "${lib.name}, shuffled", ProgrammingSource.Library(lib.ref), glyph("clapperboard"), OrderingMode.SHUFFLE, null,
                "${movies.size} movies, back to back",
            )
        }
        out += byGenre(lib, movies, minimum = MIN_MOVIES_PER_GENRE, unit = "movies", rounding = null)
        out += byDecade(lib, movies, minimum = MIN_MOVIES_PER_DECADE, unit = "movies", rounding = null)
        return out
    }

    private suspend fun collections(provider: MediaProvider, lib: Library): List<Suggestion> =
        (provider.collections(lib.ref) as? Outcome.Success)?.value.orEmpty().take(MAX_COLLECTIONS).map { c ->
            Suggestion(
                c.name, ProgrammingSource.Collection(c.ref), glyph("film"), OrderingMode.SEQUENTIAL, null, "Your collection, in order",
            )
        }

    private fun byGenre(lib: Library, items: List<MediaItem>, minimum: Int, unit: String, rounding: Duration?): List<Suggestion> =
        items.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .filter { it.value >= minimum }
            .sortedByDescending { it.value }
            .take(MAX_GENRES)
            .map { (genre, count) ->
                val filter = MediaFilter(genres = setOf(genre))
                Suggestion(
                    genreName(genre, unit), ProgrammingSource.Library(lib.ref, filter), glyph(GENRE_GLYPHS[genre.lowercase()] ?: "tv"),
                    OrderingMode.SHUFFLE, rounding, "$count $unit tagged $genre",
                )
            }

    private fun byDecade(lib: Library, items: List<MediaItem>, minimum: Int, unit: String, rounding: Duration?): List<Suggestion> =
        items.mapNotNull { it.year }.map { it - it % DECADE }.groupingBy { it }.eachCount().entries
            .filter { it.value >= minimum }
            .sortedByDescending { it.value }
            .take(MAX_DECADES)
            .map { (decade, count) ->
                val name = "${decade}s ${unit.replaceFirstChar { it.uppercase() }}"
                Suggestion(
                    name, ProgrammingSource.Library(lib.ref, MediaFilter(decadeStart = decade)),
                    glyph("history"), OrderingMode.SHUFFLE, rounding, "$count $unit from the ${decade}s",
                )
            }

    private fun genreName(genre: String, unit: String): String = when (unit) {
        "movies" -> "$genre Movies"
        else -> "$genre TV"
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

    private fun glyph(name: String) = ChannelIcon.Glyph(name)

    companion object {
        const val MAX = 12
        private const val MAX_COLLECTIONS = 4
        private const val MAX_PLAYLISTS = 2
        private const val MAX_GENRES = 4
        private const val MAX_DECADES = 2
        private const val MIN_SHOWS_PER_GENRE = 3
        private const val MIN_SHOWS_PER_DECADE = 3
        private const val MIN_MOVIES_PER_GENRE = 8
        private const val MIN_MOVIES_PER_DECADE = 8
        private const val MIN_MOVIES_FOR_ALL = 10
        private const val MAX_ITEMS_SCANNED = 2_000
        private const val DECADE = 10
        private val GENRE_GLYPHS = mapOf(
            "comedy" to "laugh", "drama" to "drama", "horror" to "ghost", "science fiction" to "rocket", "sci-fi" to "rocket",
            "action" to "swords", "adventure" to "compass", "romance" to "heart", "animation" to "baby", "children" to "baby",
            "kids" to "baby", "family" to "baby", "documentary" to "video", "music" to "music", "sport" to "trophy", "sports" to "trophy",
            "reality" to "users", "crime" to "skull", "thriller" to "zap", "fantasy" to "wand-sparkles", "war" to "swords",
            "western" to "mountain", "history" to "history", "food" to "chef-hat", "cooking" to "chef-hat", "news" to "newspaper",
        )
    }
}
