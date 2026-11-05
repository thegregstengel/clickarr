package net.clickarr.feature.channels

import net.clickarr.core.common.Outcome
import net.clickarr.core.common.titleSortKey
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.Movie
import net.clickarr.core.model.Show
import net.clickarr.provider.api.MediaProvider
import net.clickarr.provider.api.allPages

/** Loads library content for the Custom channel wizard. Kept out of the ViewModel for detekt limits. */
internal class EditorLoader(private val provider: () -> MediaProvider?) {
    suspend fun libraries(): Outcome<List<Library>> {
        val p = provider() ?: return noServer()
        return when (val r = p.libraries()) {
            is Outcome.Success -> Outcome.Success(
                r.value.filter { it.kind != LibraryKind.OTHER }.sortedBy { titleSortKey(it.name) },
            )
            is Outcome.Failure -> r
        }
    }

    suspend fun collections(lib: Library): Outcome<EditorStep.PickCollection> {
        val p = provider() ?: return noServer()
        return when (val r = p.collections(lib.ref)) {
            is Outcome.Success -> Outcome.Success(
                EditorStep.PickCollection(lib, r.value.sortedBy { titleSortKey(it.name) }),
            )
            is Outcome.Failure -> Outcome.Failure(r.error)
        }
    }

    suspend fun playlists(lib: Library): Outcome<EditorStep.PickPlaylist> {
        val p = provider() ?: return noServer()
        return when (val r = p.playlists()) {
            is Outcome.Success -> Outcome.Success(
                EditorStep.PickPlaylist(lib, r.value.sortedBy { titleSortKey(it.name) }),
            )
            is Outcome.Failure -> Outcome.Failure(r.error)
        }
    }

    suspend fun library(lib: Library): Outcome<EditorStep.PickLibrary> {
        val p = provider() ?: return noServer()
        val rows = when (val loaded = loadTitles(p, lib)) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> return Outcome.Failure(loaded.error)
        }
        val genres = rows.flatMap { it.genres }.distinct().sortedBy(::titleSortKey)
        val decades = rows.mapNotNull { it.year }.map { it - it % 10 }.distinct().sorted()
        return Outcome.Success(EditorStep.PickLibrary(lib, genres, decades, MediaFilter(), rows))
    }

    /** Every page of movies or shows, so Library is not stuck on the first container page. */
    private suspend fun loadTitles(p: MediaProvider, lib: Library): Outcome<List<LibraryTitle>> {
        val outcome: Outcome<List<LibraryTitle>> = when (lib.kind) {
            LibraryKind.MOVIES -> allPages { p.movies(lib.ref, it) }.map { movies -> movies.map(::fromMovie) }
            else -> allPages { p.shows(lib.ref, it) }.map { shows -> shows.map(::fromShow) }
        }
        return when (outcome) {
            is Outcome.Success -> Outcome.Success(outcome.value.sortedBy { titleSortKey(it.title) })
            is Outcome.Failure -> outcome
        }
    }

    private fun fromMovie(m: Movie) = LibraryTitle(m.ref, m.title, m.year, m.genres)

    private fun fromShow(s: Show) = LibraryTitle(
        s.ref,
        s.title,
        s.year,
        s.genres,
        detail = "${s.episodeCount} episodes",
    )

    private fun <T> noServer(): Outcome<T> =
        Outcome.Failure(net.clickarr.core.common.ClickarrError.Invalid("No server connected"))
}
