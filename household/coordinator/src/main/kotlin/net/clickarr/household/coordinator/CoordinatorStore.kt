package net.clickarr.household.coordinator

import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdState

/** Persistence the coordinator needs. The app backs it with Room and the SecretStore; tests use memory. */
interface CoordinatorStore {
    suspend fun loadState(): HouseholdState?

    suspend fun saveState(state: HouseholdState)

    /** Paired devices: token -> (device id, certificate fingerprint). Tokens are secrets. */
    suspend fun loadTokens(): Map<String, PairedDevice>

    suspend fun saveToken(token: String, device: PairedDevice)

    suspend fun removeTokensFor(deviceId: DeviceId)
}

data class PairedDevice(val deviceId: DeviceId, val certFingerprint: String)

class InMemoryCoordinatorStore(initial: HouseholdState? = null) : CoordinatorStore {
    var state: HouseholdState? = initial
        private set
    val tokens = LinkedHashMap<String, PairedDevice>()

    override suspend fun loadState(): HouseholdState? = state

    override suspend fun saveState(state: HouseholdState) {
        this.state = state
    }

    override suspend fun loadTokens(): Map<String, PairedDevice> = tokens.toMap()

    override suspend fun saveToken(token: String, device: PairedDevice) {
        tokens[token] = device
    }

    override suspend fun removeTokensFor(deviceId: DeviceId) {
        tokens.entries.removeIf { it.value.deviceId == deviceId }
    }
}
