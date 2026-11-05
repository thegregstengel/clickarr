package net.clickarr.feature.channels

import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.decadeSet

/** UI actions for the Library picker; kept as one ViewModel entry point for detekt limits. */
sealed interface LibraryUiAction {
    data object OpenGenres : LibraryUiAction
    data object OpenDecades : LibraryUiAction
    data object ClearFilters : LibraryUiAction
    data object ApplyFilters : LibraryUiAction
    data class ToggleTitle(val ref: MediaRef) : LibraryUiAction
    data class ToggleGenre(val genre: String) : LibraryUiAction
    data class ToggleDecade(val decade: Int) : LibraryUiAction
    data object Confirm : LibraryUiAction
}

/**
 * PickLibrary mutations kept out of [EditorViewModel] for detekt function limits.
 * [confirm] returns the source and suggested name, or null when nothing should happen.
 */
internal object EditorLibraryOps {
    fun apply(s: EditorStep.PickLibrary, action: LibraryUiAction): EditorStep.PickLibrary = when (action) {
        LibraryUiAction.OpenGenres -> openPane(s, LibraryPane.GENRES)
        LibraryUiAction.OpenDecades -> openPane(s, LibraryPane.DECADES)
        LibraryUiAction.ClearFilters -> clearFilters(s)
        LibraryUiAction.ApplyFilters -> applyPane(s)
        is LibraryUiAction.ToggleTitle -> toggleTitle(s, action.ref)
        is LibraryUiAction.ToggleGenre -> toggleDraftGenre(s, action.genre)
        is LibraryUiAction.ToggleDecade -> toggleDraftDecade(s, action.decade)
        LibraryUiAction.Confirm -> s
    }

    fun closePane(s: EditorStep.PickLibrary): EditorStep.PickLibrary =
        s.copy(pane = LibraryPane.MAIN, draftFilter = null)

    /** True when Confirm may run: any genre/decade filter, and/or at least one visible title selected. */
    fun canCreate(s: EditorStep.PickLibrary): Boolean {
        if (s.pane != LibraryPane.MAIN) return false
        if (hasFilter(s.filter)) return true
        return visibleSelected(s).isNotEmpty()
    }

    /**
     * Null when Confirm should stay gray (no filter and no titles), wrong pane, or selection
     * refs vanished. Visible selected titles win; otherwise a filter alone makes a library source.
     */
    fun confirm(s: EditorStep.PickLibrary): Pair<ProgrammingSource, String>? {
        if (!canCreate(s)) return null
        val picked = visibleSelected(s)
        if (picked.isNotEmpty()) {
            val name = if (picked.size == 1) {
                picked.single().title
            } else {
                "${picked.first().title} and ${picked.size - 1} more"
            }
            val source = if (s.library.kind == LibraryKind.SHOWS) {
                ProgrammingSource.Shows(picked.map { it.ref })
            } else {
                ProgrammingSource.Explicit(picked.map { it.ref })
            }
            return source to name
        }
        val parts = buildList {
            addAll(s.filter.decadeSet().sorted().map { "${it}s" })
            addAll(s.filter.genres)
        }
        return ProgrammingSource.Library(s.library.ref, s.filter) to parts.joinToString(" ")
    }

    fun hasFilter(filter: MediaFilter): Boolean =
        filter.genres.isNotEmpty() || filter.decadeSet().isNotEmpty()

    /** Selected titles that still match the current filter. */
    fun visibleSelected(s: EditorStep.PickLibrary): List<LibraryTitle> =
        s.titles.filter { it.ref in s.selected && titleMatches(it, s.filter) }

    /** Title rows use the same genre/decade rules as a library [MediaFilter]. */
    fun titleMatches(title: LibraryTitle, filter: MediaFilter): Boolean {
        if (filter.genres.isNotEmpty()) {
            val ok = title.genres.any { g -> filter.genres.any { it.equals(g, ignoreCase = true) } }
            if (!ok) return false
        }
        val decades = filter.decadeSet()
        if (decades.isNotEmpty()) {
            val year = title.year ?: return false
            if (decades.none { d -> year in d until d + 10 }) return false
        }
        return true
    }

    private fun openPane(s: EditorStep.PickLibrary, pane: LibraryPane): EditorStep.PickLibrary =
        if (s.pane != LibraryPane.MAIN) s else s.copy(pane = pane, draftFilter = s.filter)

    private fun clearFilters(s: EditorStep.PickLibrary): EditorStep.PickLibrary =
        if (s.pane != LibraryPane.MAIN) s else s.copy(filter = MediaFilter())

    private fun toggleTitle(s: EditorStep.PickLibrary, ref: MediaRef): EditorStep.PickLibrary {
        if (s.pane != LibraryPane.MAIN) return s
        return s.copy(selected = if (ref in s.selected) s.selected - ref else s.selected + ref)
    }

    private fun toggleDraftGenre(s: EditorStep.PickLibrary, genre: String): EditorStep.PickLibrary {
        if (s.pane != LibraryPane.GENRES) return s
        val draft = s.draftFilter ?: s.filter
        val g = draft.genres
        return s.copy(draftFilter = draft.copy(genres = if (genre in g) g - genre else g + genre))
    }

    private fun toggleDraftDecade(s: EditorStep.PickLibrary, decade: Int): EditorStep.PickLibrary {
        if (s.pane != LibraryPane.DECADES) return s
        val draft = s.draftFilter ?: s.filter
        val current = draft.decadeSet()
        val next = if (decade in current) current - decade else current + decade
        return s.copy(draftFilter = draft.copy(decadeStart = null, decades = next))
    }

    private fun applyPane(s: EditorStep.PickLibrary): EditorStep.PickLibrary =
        if (s.pane == LibraryPane.MAIN) s
        else s.copy(filter = s.draftFilter ?: s.filter, pane = LibraryPane.MAIN, draftFilter = null)
}
