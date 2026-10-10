package net.clickarr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.clickarr.data.ChannelRepository
import net.clickarr.data.DevicePrefs
import net.clickarr.data.HouseholdService
import net.clickarr.data.ProviderRegistry

/** App-level state: are providers loaded, is a server connected, are there channels. Drives the start route. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val household: HouseholdService,
    channels: ChannelRepository,
    prefs: DevicePrefs,
) : ViewModel() {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val hasServer: StateFlow<Boolean> = registry.providers.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val channelCount: StateFlow<Int> = channels.channels.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val uiScale: StateFlow<Float> = prefs.uiScale.stateIn(viewModelScope, SharingStarted.Eagerly, DevicePrefs.DEFAULT_UI_SCALE)
    val theme: StateFlow<String> = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, DevicePrefs.DEFAULT_THEME)

    /** Whether any channel existed when the app came up; decides the first screen. */
    private val _hadChannelsAtStart = MutableStateFlow(false)
    val hadChannelsAtStart: StateFlow<Boolean> = _hadChannelsAtStart.asStateFlow()

    init {
        viewModelScope.launch {
            registry.load()
            _hadChannelsAtStart.value = channels.channels.first().isNotEmpty()
            _ready.value = true
            household.start()
        }
    }
}
