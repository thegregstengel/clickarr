package net.clickarr.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.clickarr.core.common.Clock
import net.clickarr.core.model.Airing
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.scheduling.ScheduleStrategy
import net.clickarr.data.ChannelRepository
import net.clickarr.data.DevicePrefs

/** The Channels tab: the lineup, and a preview of what the focused channel is airing right now. */
@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val strategy: ScheduleStrategy,
    private val clock: Clock,
    private val prefs: DevicePrefs,
) : ViewModel() {
    val channels: StateFlow<List<Channel>> =
        repository.channels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<Set<ChannelId>> =
        repository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleFavorite(id: ChannelId) {
        viewModelScope.launch { repository.setFavorite(id, id !in favorites.value) }
    }

    data class Preview(val channel: Channel, val now: Airing?, val next: Airing?)

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    fun focus(channel: Channel) {
        viewModelScope.launch {
            val lineup = repository.lineup(channel.lineup)
            val now = lineup?.let { strategy.airingAt(channel, it, clock.now()) }
            val next = if (lineup != null && now != null) strategy.next(channel, lineup, now) else null
            _preview.value = Preview(channel, now, next)
        }
    }

    /** Make [channel] the one the player opens on, then run [then] (navigate to the player). */
    fun tune(channel: Channel, then: () -> Unit) {
        viewModelScope.launch {
            prefs.setLastChannelId(channel.id.value)
            then()
        }
    }
}
