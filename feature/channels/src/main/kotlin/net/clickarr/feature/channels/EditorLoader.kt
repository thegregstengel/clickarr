package net.clickarr.feature.channels

import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.common.titleSortKey
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.Movie
import net.clickarr.core.model.ProgrammingSource
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

    suspend fun collections(
        lib: Library,
        selected: MediaRef? = null,
    ): Outcome<EditorStep.PickCollection> {
        val p = provider() ?: return noServer()
        return when (val r = p.collections(lib.ref)) {
            is Outcome.Success -> Outcome.Success(
                EditorStep.PickCollection(lib, r.value.sortedBy { titleSortKey(it.name) }, selected),
            )
            is Outcome.Failure -> Outcome.Failure(r.error)
        }
    }

    suspend fun playlists(
        lib: Library,
        selected: MediaRef? = null,
    ): Outcome<EditorStep.PickPlaylist> {
        val p = provider() ?: return noServer()
        return when (val r = p.playlists()) {
            is Outcome.Success -> Outcome.Success(
                EditorStep.PickPlaylist(lib, r.value.sortedBy { titleSortKey(it.name) }, selected),
            )
            is Outcome.Failure -> Outcome.Failure(r.error)
        }
    }

    suspend fun library(
        lib: Library,
        filter: MediaFilter = MediaFilter(),
        selected: Set<MediaRef> = emptySet(),
    ): Outcome<EditorStep.PickLibrary> {
        val p = provider() ?: return noServer()
        val rows = when (val loaded = loadTitles(p, lib)) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> return Outcome.Failure(loaded.error)
        }
        val genres = rows.flatMap { it.genres }.distinct().sortedBy(::titleSortKey)
        val decades = rows.mapNotNull { it.year }.map { it - it % 10 }.distinct().sorted()
        return Outcome.Success(EditorStep.PickLibrary(lib, genres, decades, filter, rows, selected))
    }

    /**
     * Opens the create-time picker for [source], with filters/titles/collection/playlist prefilled.
     * Resolves the parent library when the source does not store one.
     */
    suspend fun pickerForEdit(source: ProgrammingSource): Outcome<EditorStep> {
        val mode = modeFor(source)
            ?: return Outcome.Failure(ClickarrError.Invalid("This channel's content cannot be edited here"))
        val lib = when (val r = resolveLibrary(source)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        return when (mode) {
            ChannelMode.LIBRARY -> {
                val (filter, selected) = libraryPrefill(source)
                library(lib, filter, selected)
            }
            ChannelMode.COLLECTION -> collections(lib, (source as ProgrammingSource.Collection).ref)
            ChannelMode.PLAYLIST -> playlists(lib, (source as ProgrammingSource.Playlist).ref)
        }
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

    private suspend fun resolveLibrary(source: ProgrammingSource): Outcome<Library> {
        val libs = when (val r = libraries()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        if (libs.isEmpty()) return Outcome.Failure(ClickarrError.Invalid("No libraries on this server"))
        return when (source) {
            is ProgrammingSource.Library -> libs.firstOrNull { it.ref == source.library }?.let { Outcome.Success(it) }
                ?: Outcome.Failure(ClickarrError.NotFound("That library is no longer on this server"))
            is ProgrammingSource.Shows -> findLibraryWithRefs(libs.filter { it.kind == LibraryKind.SHOWS }, source.shows)
            is ProgrammingSource.Explicit -> findLibraryWithRefs(libs.filter { it.kind == LibraryKind.MOVIES }, source.items)
            is ProgrammingSource.Collection -> findLibraryForCollection(libs, source.ref)
            is ProgrammingSource.Playlist -> Outcome.Success(libs.first())
            is ProgrammingSource.Union -> Outcome.Failure(
                ClickarrError.Invalid("This channel's content cannot be edited here"),
            )
        }
    }

    private suspend fun findLibraryWithRefs(libs: List<Library>, refs: List<MediaRef>): Outcome<Library> {
        if (libs.isEmpty()) {
            return Outcome.Failure(ClickarrError.NotFound("No matching library on this server"))
        }
        val p = provider()
        val want = refs.toSet()
        val match = when {
            refs.isEmpty() || p == null -> null
            else -> scanLibsForTitles(p, libs, want)
        }
        return when {
            p == null && refs.isNotEmpty() -> noServer()
            match is Outcome.Failure -> match
            match is Outcome.Success -> match
            // Titles may have been removed; still open a picker so the user can reselect.
            else -> Outcome.Success(libs.first())
        }
    }

    private suspend fun scanLibsForTitles(
        p: MediaProvider,
        libs: List<Library>,
        want: Set<MediaRef>,
    ): Outcome<Library>? {
        for (lib in libs) {
            when (val loaded = loadTitles(p, lib)) {
                is Outcome.Success -> if (loaded.value.any { it.ref in want }) {
                    return Outcome.Success(lib)
                }
                is Outcome.Failure -> return Outcome.Failure(loaded.error)
            }
        }
        return null
    }

    private suspend fun findLibraryForCollection(libs: List<Library>, ref: MediaRef): Outcome<Library> {
        val p = provider() ?: return noServer()
        val match = scanLibsForCollection(p, libs, ref)
        return when (match) {
            is Outcome.Failure -> match
            is Outcome.Success -> match
            null -> Outcome.Success(libs.first())
        }
    }

    private suspend fun scanLibsForCollection(
        p: MediaProvider,
        libs: List<Library>,
        ref: MediaRef,
    ): Outcome<Library>? {
        for (lib in libs) {
            when (val r = p.collections(lib.ref)) {
                is Outcome.Success -> if (r.value.any { it.ref == ref }) return Outcome.Success(lib)
                is Outcome.Failure -> return Outcome.Failure(r.error)
            }
        }
        return null
    }

    private fun libraryPrefill(source: ProgrammingSource): Pair<MediaFilter, Set<MediaRef>> = when (source) {
        is ProgrammingSource.Library -> source.filter to emptySet()
        is ProgrammingSource.Shows -> MediaFilter() to source.shows.toSet()
        is ProgrammingSource.Explicit -> MediaFilter() to source.items.toSet()
        else -> MediaFilter() to emptySet()
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
        Outcome.Failure(ClickarrError.Invalid("No server connected"))
}
