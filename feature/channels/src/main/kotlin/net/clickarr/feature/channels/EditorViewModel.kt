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
import net.clickarr.core.model.ProgrammingSource
import net.clickarr.data.ChannelRepository
import net.clickarr.data.ProviderRegistry

/**
 * Custom channel wizard: pick a library, then a mode, then the content, then name/number/order.
 * With [load] it edits an existing channel instead: same details form, plus refresh, edit
 * selections, and delete. Edit Selections reuses the create-time picker and only updates the
 * in-editor draft until Save Changes.
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

    /**
     * When non-null, the Library / Collections / Playlists picker was opened from Edit Selections.
     * Apply writes into this draft and returns to Details; Cancel / Back restores it unchanged.
     */
    private var selectionsDraft: EditorDraft? = null

    /** True while the create-time picker is open from Edit Selections (Apply, not Create). */
    fun isEditingSelections(): Boolean = selectionsDraft != null

    /** The channel already holding [number] that [editing] could not share it with, or null when it is free. */
    fun numberOwner(number: Int, editing: Channel?): Channel? = numbers.value[number].orEmpty().firstOrNull { other ->
        other.id != editing?.id && !(editing?.holiday != null && other.holiday != null && editing.holiday != other.holiday)
    }

    fun startCreate() {
        selectionsDraft = null
        library = null
        mode = ChannelMode.LIBRARY
        goLibraries()
    }

    fun chooseLibrary(picked: Library) {
        library = picked
        _step.value = EditorStep.ChooseMode(picked, modesFor())
    }

    fun chooseMode(picked: ChannelMode) {
        mode = picked
        val lib = library ?: return
        openMode(lib, picked)
    }

    fun back() {
        when (val s = _step.value) {
            is EditorStep.ChooseMode -> goLibraries()
            is EditorStep.PickLibrary -> backFromLibrary(s)
            is EditorStep.PickCollection, is EditorStep.PickPlaylist -> backFromSourcePick(s)
            is EditorStep.Details -> backFromDetails(s)
            is EditorStep.Failed -> startCreate()
            else -> Unit
        }
    }

    private fun backFromLibrary(s: EditorStep.PickLibrary) {
        if (s.pane != LibraryPane.MAIN) {
            _step.value = EditorLibraryOps.closePane(s)
            return
        }
        if (selectionsDraft != null) {
            cancelSelections()
            return
        }
        _step.value = EditorStep.ChooseMode(library ?: s.library, modesFor())
    }

    private fun backFromSourcePick(s: EditorStep) {
        if (selectionsDraft != null) {
            cancelSelections()
            return
        }
        val lib = library ?: s.libraryOrNull() ?: return
        _step.value = EditorStep.ChooseMode(lib, modesFor())
    }

    private fun backFromDetails(s: EditorStep.Details) {
        if (s.draft.editing != null) return
        val lib = library ?: return goLibraries()
        openMode(lib, mode)
    }

    /** Library picker actions (filters, multi-select, confirm). See [LibraryUiAction]. */
    fun onLibrary(action: LibraryUiAction) {
        if (action is LibraryUiAction.Confirm) {
            val s = _step.value as? EditorStep.PickLibrary ?: return
            // Enabled when a filter is applied and/or at least one title is selected.
            val result = EditorLibraryOps.confirm(s) ?: return
            applySource(result.first, result.second)
            return
        }
        _step.update { s ->
            if (s is EditorStep.PickLibrary) EditorLibraryOps.apply(s, action) else s
        }
    }

    fun confirmSource(source: ProgrammingSource, name: String) = applySource(source, name)

    /** Open the details form for an existing channel. */
    fun load(id: ChannelId) {
        selectionsDraft = null
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

    /**
     * Reopens the create-time picker for this channel's source mode, with current filters and
     * titles (or collection/playlist) pre-checked. Holiday channels have no Edit Selections.
     */
    fun editSelections() {
        val details = _step.value as? EditorStep.Details ?: return
        val draft = details.draft
        if (draft.editing?.holiday != null) return
        selectionsDraft = draft
        mode = modeFor(draft.source) ?: run {
            selectionsDraft = null
            updateDraft { it.copy(problem = "This channel's content cannot be edited here") }
            return
        }
        _step.value = EditorStep.Loading
        viewModelScope.launch {
            when (val r = loader.pickerForEdit(draft.source)) {
                is Outcome.Success -> {
                    library = r.value.libraryOrNull()
                    _step.value = r.value
                }
                is Outcome.Failure -> {
                    selectionsDraft = null
                    _step.value = EditorStep.Details(draft.copy(problem = r.error.message))
                }
            }
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
        val sourceChanged = d.editing != null && d.source != d.editing.source
        _step.value = EditorStep.Saving(d.name, building = d.editing == null || sourceChanged)
        val name = d.name.ifBlank { d.suggestedName }
        viewModelScope.launch {
            val r = d.editing?.let { c -> saveEdit(c, d, name, sourceChanged) }
                ?: repository.create(d.number, name, d.source, d.order, d.rounding, d.icon, d.runs)
            _step.value = when (r) {
                is Outcome.Success -> EditorStep.Saved(r.value.name, r.value.number)
                is Outcome.Failure -> EditorStep.Failed(r.error.message)
            }
        }
    }

    private suspend fun saveEdit(
        c: Channel,
        d: EditorDraft,
        name: String,
        sourceChanged: Boolean,
    ): Outcome<Channel> {
        val updated = c.copy(
            name = name,
            number = d.number,
            order = d.order,
            slotRounding = d.rounding,
            icon = d.icon,
            runs = d.runs,
            source = d.source,
        )
        return when (val u = repository.update(updated)) {
            is Outcome.Failure -> u
            is Outcome.Success -> if (sourceChanged) {
                repository.refreshLineup(c.id, applyNow = false)
            } else {
                u
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
                ChannelMode.COLLECTION -> loader.collections(lib)
                ChannelMode.LIBRARY -> loader.library(lib)
                ChannelMode.PLAYLIST -> loader.playlists(lib)
            }
            _step.value = when (result) {
                is Outcome.Success -> result.value
                is Outcome.Failure -> EditorStep.Failed(result.error.message)
            }
        }
    }

    /** Create flow builds a new Details draft; Edit Selections only patches source on the open draft. */
    private fun applySource(source: ProgrammingSource, suggestedName: String) {
        val editing = selectionsDraft
        if (editing != null) {
            selectionsDraft = null
            _step.value = EditorStep.Details(editing.copy(source = source, problem = null))
            return
        }
        viewModelScope.launch {
            val number = repository.nextFreeNumber()
            _step.value = EditorStep.Details(EditorDraft(source, suggestedName, suggestedName, number))
        }
    }

    private fun cancelSelections() {
        val draft = selectionsDraft ?: return
        selectionsDraft = null
        _step.value = EditorStep.Details(draft)
    }
}
