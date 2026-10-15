package net.clickarr.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.Library
import net.clickarr.core.model.MediaRef
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.core.model.decadeSet
import net.clickarr.data.ChannelRepository
import net.clickarr.data.ProviderRegistry

/**
 * Custom channel wizard: pick a library, then a mode, then the content, then name/number/order.
 * With [load] it edits an existing channel instead: same details form, plus refresh and delete.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val registry: ProviderRegistry,
) : ViewModel() {
    private val loader = EditorLoader { registry.primary }
    private val _step = MutableStateFlow<EditorStep>(EditorStep.Loading)
    val step: StateFlow<EditorStep> = _step.asStateFlow()

    /** Every channel's number and name, so the form can say "12 is already Sitcoms" before anyone presses Save. */
    val numbers: StateFlow<Map<Int, List<Channel>>> =
        repository.channels.map { list -> list.groupBy { it.number } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private var mode: ChannelMode = ChannelMode.LIBRARY
    private var library: Library? = null

    /** The channel already holding [number] that [editing] could not share it with, or null when it is free. */
    fun numberOwner(number: Int, editing: Channel?): Channel? = numbers.value[number].orEmpty().firstOrNull { other ->
        other.id != editing?.id && !(editing?.holiday != null && other.holiday != null && editing.holiday != other.holiday)
    }

    fun startCreate() {
        library = null
        mode = ChannelMode.LIBRARY
        goLibraries()
    }

    fun chooseLibrary(picked: Library) {
        library = picked
        _step.value = EditorStep.ChooseMode(picked, modesFor(picked))
    }

    fun chooseMode(picked: ChannelMode) {
        mode = picked
        val lib = library ?: return
        openMode(lib, picked)
    }

    fun back() {
        when (val s = _step.value) {
            is EditorStep.ChooseMode -> goLibraries()
            is EditorStep.PickShows, is EditorStep.PickFilters, is EditorStep.PickCollection, is EditorStep.PickPlaylist -> {
                val lib = library ?: s.libraryOrNull() ?: return
                _step.value = EditorStep.ChooseMode(lib, modesFor(lib))
            }
            is EditorStep.Details -> if (s.draft.editing == null) {
                val lib = library ?: return goLibraries()
                openMode(lib, mode)
            }
            is EditorStep.Failed -> startCreate()
            else -> Unit
        }
    }

    fun toggleShow(ref: MediaRef) {
        _step.update { s ->
            if (s !is EditorStep.PickShows) return@update s
            s.copy(selected = if (ref in s.selected) s.selected - ref else s.selected + ref)
        }
    }

    fun toggleGenre(genre: String?) {
        _step.update { s ->
            if (s !is EditorStep.PickFilters) return@update s
            if (genre == null) return@update s.copy(filter = s.filter.copy(genres = emptySet()))
            val g = s.filter.genres
            s.copy(filter = s.filter.copy(genres = if (genre in g) g - genre else g + genre))
        }
    }

    fun toggleDecade(decade: Int?) {
        _step.update { s ->
            if (s !is EditorStep.PickFilters) return@update s
            if (decade == null) {
                return@update s.copy(filter = s.filter.copy(decadeStart = null, decades = emptySet()))
            }
            val current = s.filter.decadeSet()
            val next = if (decade in current) current - decade else current + decade
            s.copy(filter = s.filter.copy(decadeStart = null, decades = next))
        }
    }

    fun confirmShows() {
        val s = _step.value as? EditorStep.PickShows ?: return
        if (s.selected.isEmpty()) return
        val picked = s.shows.filter { it.ref in s.selected }
        val name = if (picked.size == 1) picked.single().title else "${picked.first().title} and ${picked.size - 1} more"
        toDetails(ProgrammingSource.Shows(picked.map { it.ref }), name)
    }

    fun confirmFilters() {
        val s = _step.value as? EditorStep.PickFilters ?: return
        val parts = buildList {
            addAll(s.filter.decadeSet().sorted().map { "${it}s" })
            addAll(s.filter.genres)
            if (isEmpty()) add(s.library.name)
        }
        toDetails(ProgrammingSource.Library(s.library.ref, s.filter), parts.joinToString(" "))
    }

    fun confirmSource(source: ProgrammingSource, name: String) = toDetails(source, name)

    /** Open the details form for an existing channel. */
    fun load(id: ChannelId) {
        _step.value = EditorStep.Loading
        viewModelScope.launch {
            val c = repository.byId(id) ?: run {
                _step.value = EditorStep.Failed("That channel no longer exists")
                return@launch
            }
            _step.value = EditorStep.Details(
                EditorDraft(c.source, c.name, c.name, c.number, c.order, c.slotRounding, c.icon, c.runs, editing = c),
            )
        }
    }

    fun act(action: EditAction) {
        val c = (_step.value as? EditorStep.Details)?.draft?.editing ?: return
        _step.value = EditorStep.Saving(c.name, building = action == EditAction.REFRESH_LINEUP)
        viewModelScope.launch {
            _step.value = when (action) {
                EditAction.DELETE -> runCatching { repository.delete(c.id) }
                    .fold({ EditorStep.Closed }, { EditorStep.Failed(it.message ?: "Could not delete the channel") })
                EditAction.REFRESH_LINEUP -> when (val r = repository.refreshLineup(c.id, applyNow = false)) {
                    is Outcome.Success -> EditorStep.Saved(r.value.name, r.value.number)
                    is Outcome.Failure -> EditorStep.Failed(r.error.message)
                }
            }
        }
    }

    fun updateDraft(transform: (EditorDraft) -> EditorDraft) {
        _step.update { s -> if (s is EditorStep.Details) s.copy(draft = transform(s.draft)) else s }
    }

    fun save() {
        val s = _step.value as? EditorStep.Details ?: return
        val d = s.draft
        numberOwner(d.number, d.editing)?.let { owner ->
            updateDraft { it.copy(problem = "Channel ${d.number} is already ${owner.name}. Pick a free number.") }
            return
        }
        _step.value = EditorStep.Saving(d.name, building = d.editing == null)
        val name = d.name.ifBlank { d.suggestedName }
        viewModelScope.launch {
            val r = d.editing?.let { c ->
                repository.update(
                    c.copy(
                        name = name, number = d.number, order = d.order, slotRounding = d.rounding, icon = d.icon, runs = d.runs,
                    ),
                )
            } ?: repository.create(d.number, name, d.source, d.order, d.rounding, d.icon, d.runs)
            _step.value = when (r) {
                is Outcome.Success -> EditorStep.Saved(r.value.name, r.value.number)
                is Outcome.Failure -> EditorStep.Failed(r.error.message)
            }
        }
    }

    private fun goLibraries() {
        _step.value = EditorStep.Loading
        viewModelScope.launch {
            when (val r = loader.libraries()) {
                is Outcome.Success -> if (r.value.isEmpty()) {
                    _step.value = EditorStep.Failed("No libraries on this server")
                } else {
                    _step.value = EditorStep.ChooseLibrary(r.value)
                }
                is Outcome.Failure -> _step.value = EditorStep.Failed(r.error.message)
            }
        }
    }

    private fun openMode(lib: Library, picked: ChannelMode) {
        _step.value = EditorStep.Loading
        viewModelScope.launch {
            val result = when (picked) {
                ChannelMode.SHOWS -> loader.shows(lib)
                ChannelMode.COLLECTION -> loader.collections(lib)
                ChannelMode.LIBRARY -> loader.filters(lib)
                ChannelMode.PLAYLIST -> loader.playlists(lib)
            }
            _step.value = when (result) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> EditorStep.Failed(result.error.message)
            }
        }
    }

    private fun toDetails(source: ProgrammingSource, suggestedName: String) {
        viewModelScope.launch {
            val number = repository.nextFreeNumber()
            _step.value = EditorStep.Details(EditorDraft(source, suggestedName, suggestedName, number))
        }
    }
}
