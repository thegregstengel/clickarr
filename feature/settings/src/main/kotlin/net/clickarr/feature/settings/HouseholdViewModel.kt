package net.clickarr.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.clickarr.core.common.Outcome
import net.clickarr.core.database.HouseholdEntity
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.data.DevicePrefs
import net.clickarr.data.HouseholdService
import net.clickarr.household.discovery.DiscoveredCoordinator

@HiltViewModel
class HouseholdViewModel @Inject constructor(
    private val service: HouseholdService,
    prefs: DevicePrefs,
) : ViewModel() {
    val selfId: String = prefs.deviceId
    val role: StateFlow<HouseholdService.Role> = service.role
    val household: StateFlow<HouseholdEntity?> =
        service.household.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val devices: StateFlow<List<HouseholdDevice>> =
        service.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _discovered = MutableStateFlow<List<DiscoveredCoordinator>>(emptyList())
    val discovered: StateFlow<List<DiscoveredCoordinator>> = _discovered.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _joining = MutableStateFlow(false)
    val joining: StateFlow<Boolean> = _joining.asStateFlow()

    private var browseJob: Job? = null

    fun create(name: String) = act { service.createHousehold(name) }

    fun beginAddDevice() {
        if (service.beginAddDevice() == null) _message.value = "Could not start pairing."
    }

    fun stopAddDevice() = service.stopAddDevice()

    fun startJoining() {
        _joining.value = true
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            service.discover()
                .catch { _message.value = "Discovery is unavailable; enter the address manually." }
                .collect { _discovered.value = it }
        }
    }

    fun stopJoining() {
        _joining.value = false
        browseJob?.cancel()
        _discovered.value = emptyList()
    }

    fun join(baseUrl: String, fingerprint: String?, pin: String) = act {
        service.join(baseUrl, fingerprint, pin).also { if (it is Outcome.Success) stopJoining() }
    }

    fun removeDevice(id: DeviceId) = act { service.removeDevice(id) }

    fun syncNow() = viewModelScope.launch { service.syncNow() }

    fun leave() = viewModelScope.launch { service.leave() }

    private fun act(block: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            _message.value = when (val r = block()) {
                is Outcome.Success -> null
                is Outcome.Failure -> r.error.message
            }
        }
    }
}
