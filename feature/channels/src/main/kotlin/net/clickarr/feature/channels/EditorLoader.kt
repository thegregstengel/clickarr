package net.clickarr.feature.channels

import net.clickarr.core.common.Outcome
import net.clickarr.core.common.titleSortKey
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.provider.api.MediaProvider

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

    suspend fun shows(lib: Library): Outcome<EditorStep.PickShows> {
        val p = provider() ?: return noServer()
        return when (val r = p.shows(lib.ref)) {
            is Outcome.Success -> Outcome.Success(
                EditorStep.PickShows(lib, r.value.items.sortedBy { titleSortKey(it.title) }, emptySet()),
            )
            is Outcome.Failure -> Outcome.Failure(r.error)
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

    suspend fun filters(lib: Library): Outcome<EditorStep.PickFilters> {
        val p = provider() ?: return noServer()
        val rows = when (lib.kind) {
            LibraryKind.MOVIES -> when (val r = p.movies(lib.ref)) {
                is Outcome.Success -> r.value.items.map { PreviewTitle(it.title, it.year, it.genres) }
                is Outcome.Failure -> return Outcome.Failure(r.error)
            }
            else -> when (val r = p.shows(lib.ref)) {
                is Outcome.Success -> r.value.items.map { PreviewTitle(it.title, it.year, it.genres) }
                is Outcome.Failure -> return Outcome.Failure(r.error)
            }
        }.sortedBy { titleSortKey(it.title) }
        val genres = rows.flatMap { it.genres }.distinct().sortedBy(::titleSortKey)
        val decades = rows.mapNotNull { it.year }.map { it - it % 10 }.distinct().sorted()
        return Outcome.Success(EditorStep.PickFilters(lib, genres, decades, MediaFilter(), rows))
    }

    private fun <T> noServer(): Outcome<T> =
        Outcome.Failure(net.clickarr.core.common.ClickarrError.Invalid("No server connected"))
}
