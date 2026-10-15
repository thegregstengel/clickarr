package net.clickarr.feature.channels

import kotlin.time.Duration
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.Collection
import net.clickarr.core.model.EpisodeRuns
import net.clickarr.core.model.Library
import net.clickarr.core.model.LibraryKind
import net.clickarr.core.model.MediaFilter
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.OrderingMode
import net.clickarr.core.model.Playlist
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.Show

/** How content is picked under a library on the Custom channel screen. */
enum class ChannelMode(val label: String, val hint: String) {
    LIBRARY("Whole library", "Everything in this library, with optional genre and decade filters."),
    SHOWS("Shows", "Pick one or more shows. Episodes play in aired order."),
    COLLECTION("Collections", "A collection from this library, in its order."),
    PLAYLIST("Playlists", "A playlist from your server, in its order."),
}

/** One title shown in the Whole library preview list. */
data class PreviewTitle(val title: String, val year: Int?, val genres: List<String>)

sealed interface EditorStep {
    data object Loading : EditorStep
    data class ChooseLibrary(val libraries: List<Library>) : EditorStep
    data class ChooseMode(val library: Library, val modes: List<ChannelMode>) : EditorStep
    data class PickShows(val library: Library, val shows: List<Show>, val selected: Set<MediaRef>) : EditorStep
    data class PickFilters(
        val library: Library,
        val genres: List<String>,
        val decades: List<Int>,
        val filter: MediaFilter,
        val titles: List<PreviewTitle>,
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

fun modesFor(library: Library): List<ChannelMode> = buildList {
    add(ChannelMode.LIBRARY)
    if (library.kind == LibraryKind.SHOWS) add(ChannelMode.SHOWS)
    add(ChannelMode.COLLECTION)
    add(ChannelMode.PLAYLIST)
}

fun EditorStep.libraryOrNull(): Library? = when (this) {
    is EditorStep.PickShows -> library
    is EditorStep.PickFilters -> library
    is EditorStep.PickCollection -> library
    is EditorStep.PickPlaylist -> library
    is EditorStep.ChooseMode -> library
    else -> null
}
