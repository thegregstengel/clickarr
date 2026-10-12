package net.clickarr.data

import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.Movie
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.SeasonWindow
import net.clickarr.data.ChannelSuggester.Suggestion

/**
 * Seasonal movie channels from what is already in the library: Halloween horror in October, and Christmas movies
 * from late November to New Year. Christmas films are found by Plex's holiday genre or by a title that is plainly
 * a Christmas film; there is no tag to add. Both shuffle and play back to back, and only appear in their season.
 */
object SeasonalSuggestions {
    fun of(scan: LibraryScan, t: SuggestionRules.Thresholds): List<Suggestion> = listOfNotNull(halloween(scan, t), christmas(scan, t))

    private fun halloween(scan: LibraryScan, t: SuggestionRules.Thresholds): Suggestion? {
        val (lib, horror) = scan.movies.entries
            .map { (lib, movies) -> lib to movies.filter { m -> m.genres.any { it.equals("horror", ignoreCase = true) } } }
            .maxByOrNull { it.second.size } ?: return null
        if (horror.size < t.genreMovies) return null
        val tags = horror.flatMap { it.genres }.filter { it.equals("horror", ignoreCase = true) }.toSet()
        return Suggestion(
            "Halloween Horror", ProgrammingSource.Library(lib.ref, MediaFilter(genres = tags)), ChannelIcon.Glyph("ghost"),
            OrderingMode.SHUFFLE, null, "${horror.size} horror movies, shuffled, in October only",
            season = SeasonWindow.HALLOWEEN,
        )
    }

    private fun christmas(scan: LibraryScan, t: SuggestionRules.Thresholds): Suggestion? {
        val films = scan.movies.values.flatten().filter(::isChristmas)
        if (films.size < minOf(t.genreMovies, CHRISTMAS_MINIMUM)) return null
        return Suggestion(
            "Christmas Movies", ProgrammingSource.Explicit(films.map { it.ref }), ChannelIcon.Glyph("tree-pine"),
            OrderingMode.SHUFFLE, null, "${films.size} Christmas movies, shuffled, Nov 25 to Dec 31",
            season = SeasonWindow.CHRISTMAS,
        )
    }

    fun isChristmas(m: Movie): Boolean {
        if (m.genres.any { g -> HOLIDAY_GENRES.any { it.equals(g, ignoreCase = true) } }) return true
        val title = m.title.lowercase()
        return CHRISTMAS_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(title) } || title in CHRISTMAS_TITLES
    }

    private const val CHRISTMAS_MINIMUM = 5
    private val HOLIDAY_GENRES = setOf("holiday", "christmas")
    private val CHRISTMAS_WORDS = listOf(
        "christmas", "xmas", "x-mas", "santa", "santa claus", "grinch", "scrooge", "nutcracker", "noel", "krampus",
        "rudolph", "frosty", "jingle", "reindeer", "polar express", "home alone", "wonderful life", "miracle on 34th street",
    )
    private val CHRISTMAS_TITLES = setOf("elf", "the holiday", "love actually")
}
