package net.clickarr.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.clickarr.data.DevicePrefs
import net.clickarr.data.DriveSync
import net.clickarr.data.HouseholdService

/** Settings, Sync: the mode choice (ADR 0020) and the Google Drive side of it. The LAN side is HouseholdViewModel. */
@HiltViewModel
class SyncViewModel @Inject constructor(private val service: HouseholdService, prefs: DevicePrefs) : ViewModel() {
    val role: StateFlow<HouseholdService.Role> = service.role
    val driveConfigured: Boolean = service.driveConfigured
    val syncMode: StateFlow<String> = prefs.syncMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicePrefs.SYNC_OFF)
    val driveStatus: StateFlow<DriveSync.Status> = service.drive.status

    fun chooseLocal() = viewModelScope.launch {
        if (service.role.value is HouseholdService.Role.Drive) service.leave()
    }

    fun chooseDrive() = viewModelScope.launch {
        if (service.role.value !is HouseholdService.Role.None) service.leave()
        service.startDrive()
    }

    fun chooseOff() = viewModelScope.launch { service.leave() }

    fun signIn() = service.drive.beginSignIn()

    fun syncNow() = viewModelScope.launch { service.drive.syncNow() }

    fun signOut() = viewModelScope.launch {
        service.drive.signOut()
        service.leave()
    }
}
