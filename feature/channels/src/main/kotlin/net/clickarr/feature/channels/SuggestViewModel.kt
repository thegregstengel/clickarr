package net.clickarr.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clickarr.core.common.Outcome
import net.clickarr.data.ChannelRepository
import net.clickarr.data.ChannelSuggester

/** "Suggest channels": a short list from the library, the viewer ticks what they want, one tap creates them. */
@HiltViewModel
class SuggestViewModel @Inject constructor(
    private val suggester: ChannelSuggester,
    private val repository: ChannelRepository,
) : ViewModel() {
    sealed interface Step {
        data object Loading : Step
        data class Pick(val suggestions: List<ChannelSuggester.Suggestion>, val picked: Set<Int>) : Step
        data class Creating(val done: Int, val total: Int) : Step
        data class Done(val created: Int, val failed: List<String>) : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Loading)
    val step: StateFlow<Step> = _step.asStateFlow()

    init {
        viewModelScope.launch {
            _step.value = when (val r = suggester.suggest()) {
                is Outcome.Success -> Step.Pick(r.value, r.value.indices.toSet())
                is Outcome.Failure -> Step.Failed(r.error.message)
            }
        }
    }

    fun toggle(index: Int) {
        _step.update { s ->
            if (s !is Step.Pick) s else s.copy(picked = if (index in s.picked) s.picked - index else s.picked + index)
        }
    }

    fun createPicked() {
        val s = _step.value as? Step.Pick ?: return
        val chosen = s.suggestions.filterIndexed { i, _ -> i in s.picked }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val failed = ArrayList<String>()
            chosen.forEachIndexed { i, sug ->
                _step.value = Step.Creating(i, chosen.size)
                val number = repository.nextFreeNumber()
                val r = repository.create(number, sug.name, sug.source, sug.order, sug.slotRounding, sug.icon)
                if (r is Outcome.Failure) failed += "${sug.name}: ${r.error.message}"
            }
            _step.value = Step.Done(chosen.size - failed.size, failed)
        }
    }
}
