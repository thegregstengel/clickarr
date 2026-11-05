package net.clickarr.feature.channels

import kotlin.time.Duration
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.Collection
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.Library
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource

/** How content is picked under a library on the Custom channel screen. */
enum class ChannelMode(val label: String, val hint: String) {
    LIBRARY(
        "Library",
        "Filter and/or pick titles from this library. 0-9 / A-Z jump.",
    ),
    COLLECTION("Collections", "A collection from this library, in its order."),
    PLAYLIST("Playlists", "A playlist from your server, in its order."),
}

/** One title in the Library picker list. */
data class LibraryTitle(
    val ref: MediaRef,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    /** Extra line under the title, e.g. episode count for a show. */
    val detail: String? = null,
)

/** Sub-screen on the Library picker: main list, or a filter multi-select. */
enum class LibraryPane { MAIN, GENRES, DECADES }

sealed interface EditorStep {
    data object Loading : EditorStep
    data class ChooseLibrary(val libraries: List<Library>) : EditorStep
    data class ChooseMode(val library: Library, val modes: List<ChannelMode>) : EditorStep
    /**
     * Library content step. [pane] is MAIN for the title list, or GENRES / DECADES for filter
     * multi-select with Apply. [draftFilter] holds in-progress edits on those panes until Apply.
     */
    data class PickLibrary(
        val library: Library,
        val genres: List<String>,
        val decades: List<Int>,
        val filter: MediaFilter,
        val titles: List<LibraryTitle>,
        val selected: Set<MediaRef> = emptySet(),
        val pane: LibraryPane = LibraryPane.MAIN,
        val draftFilter: MediaFilter? = null,
    ) : EditorStep
    data class PickCollection(val library: Library, val collections: List<Collection>) : EditorStep
    data class PickPlaylist(val library: Library, val playlists: List<Playlist>) : EditorStep
    data class Details(val draft: EditorDraft) : EditorStep
    data class Saving(val name: String, val building: Boolean = true) : EditorStep
    data class Saved(val name: String, val number: Int) : EditorStep
    data class Failed(val message: String) : EditorStep
    /** The channel is gone (deleted); leave the editor. */
    data object Closed : EditorStep
}

data class EditorDraft(
    val source: ProgrammingSource,
    val suggestedName: String,
    val name: String,
    val number: Int,
    val order: OrderingMode = OrderingMode.SEQUENTIAL,
    val rounding: Duration? = null,
    val icon: ChannelIcon? = null,
    val runs: EpisodeRuns? = null,
    /** Set when editing an existing channel. */
    val editing: Channel? = null,
    /** Why the last Save did not go through, shown by the form. */
    val problem: String? = null,
)

enum class EditAction { REFRESH_LINEUP, DELETE }

fun modesFor(library: Library): List<ChannelMode> = listOf(
    ChannelMode.LIBRARY,
    ChannelMode.COLLECTION,
    ChannelMode.PLAYLIST,
)

fun EditorStep.libraryOrNull(): Library? = when (this) {
    is EditorStep.PickLibrary -> library
    is EditorStep.PickCollection -> library
    is EditorStep.PickPlaylist -> library
    is EditorStep.ChooseMode -> library
    else -> null
}
