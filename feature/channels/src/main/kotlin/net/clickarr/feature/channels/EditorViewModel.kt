package net.clickarr.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelIcon
import net.clickarr.core.model.ChannelId
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
import net.clickarr.data.ChannelRepository
import net.clickarr.data.ProviderRegistry
import net.clickarr.provider.api.MediaProvider

/**
 * Channel creation wizard. Steps: kind of source, pick the content, then name/number/order/rounding.
 * Everything comes from the connected Plex server through the provider contract. With [load] it edits
 * an existing channel instead: same details form, plus refresh and delete.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val registry: ProviderRegistry,
) : ViewModel() {
    enum class Kind(val label: String, val hint: String) {
        SHOWS("Shows", "Pick one or more shows. Episodes play in aired order."),
        LIBRARY("Whole library", "Everything in a library, with optional decade and genre filters."),
        COLLECTION("Collection", "A Plex collection, in its order."),
        PLAYLIST("Playlist", "A Plex playlist, in its order."),
    }

    sealed interface Step {
        data object ChooseKind : Step
        data class ChooseLibrary(val libraries: List<Library>) : Step
        data class PickShows(val library: Library, val shows: List<Show>, val selected: Set<MediaRef>) : Step
        data class PickFilters(val library: Library, val genres: List<String>, val decades: List<Int>, val filter: MediaFilter) : Step
        data class PickCollection(val library: Library, val collections: List<Collection>) : Step
        data class PickPlaylist(val playlists: List<Playlist>) : Step
        data class Details(val draft: Draft) : Step
        data class Saving(val name: String, val building: Boolean = true) : Step
        data class Saved(val name: String, val number: Int) : Step
        data class Failed(val message: String) : Step
        data object Loading : Step
        /** The channel is gone (deleted); leave the editor. */
        data object Closed : Step
    }

    enum class EditAction { REFRESH_LINEUP, DELETE }

    data class Draft(
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

    private val _step = MutableStateFlow<Step>(Step.ChooseKind)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** Every channel's number and name, so the form can say "12 is already Sitcoms" before anyone presses Save. */
    val numbers: StateFlow<Map<Int, Channel>> =
        repository.channels.map { list -> list.associateBy { it.number } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The channel already holding [number], unless it is the one being edited. */
    fun numberOwner(number: Int, editing: Channel?): Channel? = numbers.value[number]?.takeIf { it.id != editing?.id }

    private var kind: Kind = Kind.SHOWS
    private val provider: MediaProvider? get() = registry.primary

    fun choose(kind: Kind) {
        this.kind = kind
        _step.value = Step.Loading
        viewModelScope.launch {
            val p = provider ?: return@launch fail("No server connected")
            when (kind) {
                Kind.PLAYLIST -> when (val r = p.playlists()) {
                    is Outcome.Success -> _step.value = Step.PickPlaylist(r.value)
                    is Outcome.Failure -> fail(r.error.message)
                }
                else -> when (val r = p.libraries()) {
                    is Outcome.Success -> {
                        val wanted = when (kind) {
                            Kind.SHOWS -> r.value.filter { it.kind == LibraryKind.SHOWS }
                            else -> r.value.filter { it.kind != LibraryKind.OTHER }
                        }
                        if (wanted.size == 1) chooseLibrary(wanted.single()) else _step.value = Step.ChooseLibrary(wanted)
                    }
                    is Outcome.Failure -> fail(r.error.message)
                }
            }
        }
    }

    fun chooseLibrary(library: Library) {
        _step.value = Step.Loading
        viewModelScope.launch {
            val p = provider ?: return@launch fail("No server connected")
            when (kind) {
                Kind.SHOWS -> when (val r = p.shows(library)) {
                    is Outcome.Success -> _step.value = Step.PickShows(library, r.value.items.sortedBy { it.title }, emptySet())
                    is Outcome.Failure -> fail(r.error.message)
                }
                Kind.COLLECTION -> when (val r = p.collections(library.ref)) {
                    is Outcome.Success -> _step.value = Step.PickCollection(library, r.value)
                    is Outcome.Failure -> fail(r.error.message)
                }
                Kind.LIBRARY -> loadFilters(library)
                Kind.PLAYLIST -> Unit
            }
        }
    }

    private suspend fun MediaProvider.shows(library: Library) = shows(library.ref)

    private suspend fun loadFilters(library: Library) {
        val p = provider ?: return fail("No server connected")
        val items: List<Pair<List<String>, Int?>> = when (library.kind) {
            LibraryKind.MOVIES -> when (val r = p.movies(library.ref)) {
                is Outcome.Success -> r.value.items.map { it.genres to it.year }
                is Outcome.Failure -> return fail(r.error.message)
            }
            else -> when (val r = p.shows(library.ref)) {
                is Outcome.Success -> r.value.items.map { it.genres to it.year }
                is Outcome.Failure -> return fail(r.error.message)
            }
        }
        val genres = items.flatMap { it.first }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val decades = items.mapNotNull { it.second }.map { it - it % 10 }.distinct().sorted()
        _step.value = Step.PickFilters(library, genres, decades, MediaFilter())
    }

    fun toggleShow(ref: MediaRef) {
        _step.update { s ->
            if (s !is Step.PickShows) return@update s
            s.copy(selected = if (ref in s.selected) s.selected - ref else s.selected + ref)
        }
    }

    fun toggleGenre(genre: String) {
        _step.update { s ->
            if (s !is Step.PickFilters) return@update s
            val g = s.filter.genres
            s.copy(filter = s.filter.copy(genres = if (genre in g) g - genre else g + genre))
        }
    }

    fun setDecade(decade: Int?) {
        _step.update { s -> if (s is Step.PickFilters) s.copy(filter = s.filter.copy(decadeStart = decade)) else s }
    }

    fun confirmShows() {
        val s = _step.value as? Step.PickShows ?: return
        if (s.selected.isEmpty()) return
        val picked = s.shows.filter { it.ref in s.selected }
        val name = if (picked.size == 1) picked.single().title else "${picked.first().title} and ${picked.size - 1} more"
        toDetails(ProgrammingSource.Shows(picked.map { it.ref }), name)
    }

    fun confirmFilters() {
        val s = _step.value as? Step.PickFilters ?: return
        val parts = buildList {
            s.filter.decadeStart?.let { add("${it}s") }
            addAll(s.filter.genres)
            if (isEmpty()) add(s.library.name)
        }
        toDetails(ProgrammingSource.Library(s.library.ref, s.filter), parts.joinToString(" "))
    }

    fun confirmCollection(c: Collection) = toDetails(ProgrammingSource.Collection(c.ref), c.name)

    fun confirmPlaylist(p: Playlist) = toDetails(ProgrammingSource.Playlist(p.ref), p.name)

    private fun toDetails(source: ProgrammingSource, suggestedName: String) {
        viewModelScope.launch {
            val number = repository.nextFreeNumber()
            _step.value = Step.Details(Draft(source, suggestedName, suggestedName, number))
        }
    }

    /** Open the details form for an existing channel. */
    fun load(id: ChannelId) {
        _step.value = Step.Loading
        viewModelScope.launch {
            val c = repository.byId(id) ?: return@launch fail("That channel no longer exists")
            _step.value = Step.Details(Draft(c.source, c.name, c.name, c.number, c.order, c.slotRounding, c.icon, c.runs, editing = c))
        }
    }

    fun act(action: EditAction) {
        val c = (_step.value as? Step.Details)?.draft?.editing ?: return
        _step.value = Step.Saving(c.name, building = action == EditAction.REFRESH_LINEUP)
        viewModelScope.launch {
            _step.value = when (action) {
                EditAction.DELETE -> runCatching { repository.delete(c.id) }
                    .fold({ Step.Closed }, { Step.Failed(it.message ?: "Could not delete the channel") })
                EditAction.REFRESH_LINEUP -> when (val r = repository.refreshLineup(c.id, applyNow = false)) {
                    is Outcome.Success -> Step.Saved(r.value.name, r.value.number)
                    is Outcome.Failure -> Step.Failed(r.error.message)
                }
            }
        }
    }

    fun updateDraft(transform: (Draft) -> Draft) {
        _step.update { s -> if (s is Step.Details) s.copy(draft = transform(s.draft)) else s }
    }

    fun save() {
        val s = _step.value as? Step.Details ?: return
        val d = s.draft
        numberOwner(d.number, d.editing)?.let { owner ->
            updateDraft { it.copy(problem = "Channel ${d.number} is already ${owner.name}. Pick a free number.") }
            return
        }
        _step.value = Step.Saving(d.name, building = d.editing == null)
        val name = d.name.ifBlank { d.suggestedName }
        viewModelScope.launch {
            val r = d.editing?.let { c ->
                repository.update(
                    c.copy(name = name, number = d.number, order = d.order, slotRounding = d.rounding, icon = d.icon, runs = d.runs),
                )
            } ?: repository.create(d.number, name, d.source, d.order, d.rounding, d.icon, d.runs)
            _step.value = when (r) {
                is Outcome.Success -> Step.Saved(r.value.name, r.value.number)
                is Outcome.Failure -> Step.Failed(r.error.message)
            }
        }
    }

    fun restart() {
        _step.value = Step.ChooseKind
    }

    private fun fail(message: String) {
        _step.value = Step.Failed(message)
    }
}
