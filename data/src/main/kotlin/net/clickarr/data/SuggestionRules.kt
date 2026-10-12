package net.clickarr.data

import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.Collection
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaItem
import net.clickarr.core.model.Movie
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.Show
import net.clickarr.data.ChannelSuggester.Suggestion

/** What the suggester read from the server, library by library. */
data class LibraryScan(
    val shows: Map<Library, List<Show>>,
    val movies: Map<Library, List<Movie>>,
    val collections: List<Pair<Library, Collection>>,
    val playlists: List<Playlist>,
) {
    val titleCount: Int get() = shows.values.sumOf { it.size } + movies.values.sumOf { it.size }
}

/**
 * The rules behind "Suggest channels": what a person with an afternoon would make from this library.
 * Rerun channels for the shows with the most episodes, the collections someone bothered to curate,
 * the genres and decades with enough to fill a day, one shuffled movie channel, and a playlist. Every
 * suggestion plays back to back; padded slots are a choice, not a default. Thresholds scale down for a
 * small library, which gets what it has rather than nothing. Pure, so it is unit tested directly.
 */
object SuggestionRules {
    /** Minimum sizes before a grouping is worth a channel. */
    @Suppress("LongParameterList")
    data class Thresholds(
        val rerunEpisodes: Int,
        val collectionItems: Int,
        val genreShows: Int,
        val genreMovies: Int,
        val decadeShows: Int,
        val decadeMovies: Int,
        val allMovies: Int,
        val playlistItems: Int,
    )

    val NORMAL = Thresholds(
        rerunEpisodes = 12, collectionItems = 2, genreShows = 5, genreMovies = 12,
        decadeShows = 6, decadeMovies = 15, allMovies = 20, playlistItems = 3,
    )
    val SMALL = Thresholds(
        rerunEpisodes = 2, collectionItems = 2, genreShows = 2, genreMovies = 2,
        decadeShows = 2, decadeMovies = 2, allMovies = 3, playlistItems = 2,
    )

    /** A grouping of genre tags that reads as one channel: "Cartoons" is Animation plus Children plus Kids. */
    data class Theme(val name: String, val tags: Set<String>, val glyph: String)

    fun plan(scan: LibraryScan, existing: Set<String> = emptySet()): List<Suggestion> {
        val t = if (scan.titleCount < SMALL_LIBRARY) SMALL else NORMAL
        val out = ArrayList<Suggestion>()
        out += reruns(scan, t).take(MAX_RERUNS)
        out += collections(scan, t).take(MAX_COLLECTIONS)
        out += themes(scan, t).take(MAX_THEMES)
        out += decades(scan, t).take(MAX_DECADES)
        out += movieChannels(scan, t).take(1)
        out += playlists(scan, t).take(MAX_PLAYLISTS)
        return out.filter { it.name.lowercase() !in existing }.distinctBy { it.name.lowercase() }.take(ChannelSuggester.MAX)
    }

    /** One show, all its episodes, start to finish: the rerun channel, and the most asked-for kind. */
    fun reruns(scan: LibraryScan, t: Thresholds): List<Suggestion> =
        scan.shows.values.flatten()
            .filter { it.episodeCount >= t.rerunEpisodes }
            .sortedByDescending { it.episodeCount }
            .map { show ->
                Suggestion(
                    show.title, ProgrammingSource.Shows(listOf(show.ref)), glyph(glyphFor(show.genres, "tv")),
                    OrderingMode.SEQUENTIAL, null, "${show.episodeCount} episodes, start to finish",
                )
            }

    fun collections(scan: LibraryScan, t: Thresholds): List<Suggestion> =
        scan.collections
            .filter { (_, c) -> (c.itemCount ?: Int.MAX_VALUE) >= t.collectionItems }
            .sortedByDescending { (_, c) -> c.itemCount ?: 0 }
            .map { (lib, c) ->
                val unit = if (lib.kind == LibraryKind.MOVIES) "movies" else "shows"
                val count = c.itemCount?.let { "$it $unit, " } ?: ""
                Suggestion(
                    c.name, ProgrammingSource.Collection(c.ref), glyph(if (unit == "movies") "film" else "tv"),
                    OrderingMode.SEQUENTIAL, null, "Your collection: ${count}in order",
                )
            }

    /** Genre themes with enough titles, strongest first across every library. */
    fun themes(scan: LibraryScan, t: Thresholds): List<Suggestion> {
        val scored = ArrayList<Pair<Int, Suggestion>>()
        for ((lib, shows) in scan.shows) {
            for (theme in SHOW_THEMES) {
                val n = countTagged(shows, theme.tags)
                if (n >= t.genreShows) scored += n to themed(lib, theme, shows, n, "shows")
            }
        }
        for ((lib, movies) in scan.movies) {
            for (theme in MOVIE_THEMES) {
                val n = countTagged(movies, theme.tags)
                if (n >= t.genreMovies) scored += n to themed(lib, theme, movies, n, "movies")
            }
        }
        return scored.sortedByDescending { it.first }.map { it.second }
    }

    fun decades(scan: LibraryScan, t: Thresholds): List<Suggestion> {
        val scored = ArrayList<Pair<Int, Suggestion>>()
        for ((lib, shows) in scan.shows) scored += byDecade(lib, shows, t.decadeShows, "Shows", "shows")
        for ((lib, movies) in scan.movies) scored += byDecade(lib, movies, t.decadeMovies, "Movies", "movies")
        return scored.sortedByDescending { it.first }.map { it.second }
    }

    fun movieChannels(scan: LibraryScan, t: Thresholds): List<Suggestion> =
        scan.movies.entries
            .filter { (_, movies) -> movies.size >= t.allMovies }
            .sortedByDescending { (_, movies) -> movies.size }
            .map { (lib, movies) ->
                val name = if (scan.movies.size > 1) "${lib.name} Channel" else "Movie Channel"
                Suggestion(
                    name, ProgrammingSource.Library(lib.ref), glyph("clapperboard"), OrderingMode.SHUFFLE, null,
                    "All ${movies.size} movies, shuffled",
                )
            }

    fun playlists(scan: LibraryScan, t: Thresholds): List<Suggestion> =
        scan.playlists
            .filter { (it.itemCount ?: Int.MAX_VALUE) >= t.playlistItems }
            .sortedByDescending { it.itemCount ?: 0 }
            .map { p ->
                val count = p.itemCount?.let { ", $it items" } ?: ""
                Suggestion(p.name, ProgrammingSource.Playlist(p.ref), glyph("star"), OrderingMode.SEQUENTIAL, null, "Your playlist$count")
            }

    /** The filter carries the tags as this library spells them, so the editor shows "Sci-Fi", not a guess. */
    private fun themed(lib: Library, theme: Theme, items: List<MediaItem>, n: Int, unit: String): Suggestion {
        val tags = items.flatMap { it.genres }.filter { it.lowercase() in theme.tags }.toSet()
        return Suggestion(
            theme.name, ProgrammingSource.Library(lib.ref, MediaFilter(genres = tags)), glyph(theme.glyph),
            OrderingMode.SHUFFLE, null, "$n $unit, shuffled", runs = runsFor(unit),
        )
    }

    private fun byDecade(
        lib: Library,
        items: List<MediaItem>,
        minimum: Int,
        label: String,
        unit: String,
    ): List<Pair<Int, Suggestion>> =
        items.mapNotNull { it.year }.map { it - it % DECADE }.groupingBy { it }.eachCount().entries
            .filter { it.value >= minimum }
            .map { (decade, count) ->
                val suggestion = Suggestion(
                    "${decadeName(decade)} $label", ProgrammingSource.Library(lib.ref, MediaFilter(decadeStart = decade)),
                    glyph("history"), OrderingMode.SHUFFLE, null, "$count $unit from the ${decade}s", runs = runsFor(unit),
                )
                count to suggestion
            }

    /** 1980 reads as "80s"; 2000 and later keep the century ("2000s", "2010s"). */
    fun decadeName(decade: Int): String = if (decade < CENTURY_2000) "${decade % CENTURY}s" else "${decade}s"

    private fun countTagged(items: List<MediaItem>, tags: Set<String>): Int =
        items.count { item -> item.genres.any { it.lowercase() in tags } }

    /** Shuffled shows play two or three episodes in a row (the "Episodes in a row" setting); movies one at a time. */
    private fun runsFor(unit: String): EpisodeRuns? = if (unit == "shows") EpisodeRuns(2, SHOW_RUN_MAX) else null

    private fun glyphFor(genres: List<String>, fallback: String): String =
        genres.firstNotNullOfOrNull { GENRE_GLYPHS[it.lowercase()] } ?: fallback

    private fun glyph(name: String) = ChannelIcon.Glyph(name)

    const val SMALL_LIBRARY = 40
    private const val MAX_RERUNS = 4
    private const val MAX_COLLECTIONS = 3
    private const val MAX_THEMES = 3
    private const val MAX_DECADES = 2
    private const val MAX_PLAYLISTS = 1
    private const val DECADE = 10
    private const val CENTURY = 100
    private const val CENTURY_2000 = 2000
    private const val SHOW_RUN_MAX = 3

    val SHOW_THEMES = listOf(
        Theme("Sitcoms", setOf("comedy"), "laugh"),
        Theme("Cartoons", setOf("animation", "children", "kids", "family"), "baby"),
        Theme("Dramas", setOf("drama"), "drama"),
        Theme("Crime TV", setOf("crime", "mystery"), "skull"),
        Theme("Sci-Fi TV", setOf("science fiction", "sci-fi", "sci-fi & fantasy", "fantasy"), "rocket"),
        Theme("Documentaries", setOf("documentary"), "video"),
        Theme("Reality TV", setOf("reality", "reality tv"), "users"),
        Theme("Action TV", setOf("action", "adventure", "action & adventure"), "swords"),
        Theme("Cooking Shows", setOf("food", "cooking"), "chef-hat"),
        Theme("Horror TV", setOf("horror", "thriller"), "ghost"),
    )

    val MOVIE_THEMES = listOf(
        Theme("Comedy Movies", setOf("comedy"), "laugh"),
        Theme("Action Movies", setOf("action", "adventure"), "swords"),
        Theme("Sci-Fi Movies", setOf("science fiction", "sci-fi"), "rocket"),
        Theme("Fantasy Movies", setOf("fantasy"), "wand-sparkles"),
        Theme("Horror Movies", setOf("horror"), "ghost"),
        Theme("Thrillers", setOf("thriller", "mystery", "crime"), "zap"),
        Theme("Drama Movies", setOf("drama"), "drama"),
        Theme("Family Movies", setOf("animation", "family", "children", "kids"), "baby"),
        Theme("Romance Movies", setOf("romance"), "heart"),
        Theme("Documentary Films", setOf("documentary"), "video"),
        Theme("Westerns", setOf("western"), "mountain"),
        Theme("War Movies", setOf("war"), "swords"),
    )

    private val GENRE_GLYPHS = mapOf(
        "comedy" to "laugh", "drama" to "drama", "horror" to "ghost", "science fiction" to "rocket", "sci-fi" to "rocket",
        "action" to "swords", "adventure" to "compass", "romance" to "heart", "animation" to "baby", "children" to "baby",
        "kids" to "baby", "family" to "baby", "documentary" to "video", "music" to "music", "sport" to "trophy", "sports" to "trophy",
        "reality" to "users", "crime" to "skull", "thriller" to "zap", "fantasy" to "wand-sparkles", "war" to "swords",
        "western" to "mountain", "history" to "history", "food" to "chef-hat", "cooking" to "chef-hat", "news" to "newspaper",
    )
}
