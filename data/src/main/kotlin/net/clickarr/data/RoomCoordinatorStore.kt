package net.clickarr.data

import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.database.HouseholdEntity
import net.clickarr.core.database.toEntities
import net.clickarr.core.database.toEntity
import net.clickarr.core.database.toLineup
import net.clickarr.core.database.toModel
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.Household
import net.clickarr.core.model.HouseholdId
import net.clickarr.core.model.HouseholdState
import net.clickarr.core.secrets.SecretStore
import net.clickarr.household.coordinator.CoordinatorStore
import net.clickarr.household.coordinator.PairedDevice

/**
 * The household document mapped onto the local database. Channels, lineups and favorites are the same
 * tables the player reads, so applying a synced state is one transaction and the UI follows through
 * Room's flows. Device tokens live in the SecretStore as one encrypted JSON map.
 */
class RoomCoordinatorStore(
    private val db: ClickarrDatabase,
    private val secrets: SecretStore,
    private val now: () -> Instant,
) : CoordinatorStore {
    private val json = Json
    private val tokenMap = MapSerializer(String.serializer(), String.serializer())

    override suspend fun loadState(): HouseholdState? {
        val h = db.household().get() ?: return null
        return assemble(h)
    }

    suspend fun assemble(h: HouseholdEntity): HouseholdState {
        val channels = db.channels().all().map { it.toModel() }
        val snapshots = db.lineups().allSnapshots()
        val entries = db.lineups().entriesFor(snapshots.map { it.id }).groupBy { it.lineupId }
        return HouseholdState(
            revision = h.revision,
            schedulerVersion = h.schedulerVersion,
            household = Household(
                HouseholdId(h.householdId), h.name, DeviceId(h.coordinatorDeviceId), Instant.fromEpochMilliseconds(h.createdAtEpochMs),
            ),
            devices = db.household().devices().map { it.toModel() },
            servers = db.household().servers().map { it.toModel() },
            channels = channels,
            lineups = snapshots.map { toLineup(it, entries[it.id].orEmpty()) },
            favorites = db.favorites().all().map { net.clickarr.core.model.ChannelId(it) }.toSet(),
        )
    }

    override suspend fun saveState(state: HouseholdState) = db.withTransaction {
        val existing = db.household().get()
        db.household().upsert(
            HouseholdEntity(
                householdId = state.household.id.value,
                name = state.household.name,
                coordinatorDeviceId = state.household.coordinator.value,
                createdAtEpochMs = state.household.createdAt.toEpochMilliseconds(),
                role = existing?.role ?: ROLE_NONE,
                revision = state.revision,
                schedulerVersion = state.schedulerVersion,
                coordinatorBaseUrl = existing?.coordinatorBaseUrl,
                coordinatorFingerprint = existing?.coordinatorFingerprint,
                lastSyncEpochMs = now().toEpochMilliseconds(),
            ),
        )
        db.household().replaceDevices(state.devices.map { it.toEntity() })
        db.household().replaceServers(state.servers.map { it.toEntity() })
        db.lineups().deleteAllEntries()
        db.lineups().deleteAllSnapshots()
        state.lineups.forEach { l ->
            val (snap, entries) = l.toEntities()
            db.lineups().insert(snap, entries)
        }
        val t = now()
        db.channels().replaceAll(state.channels.map { it.toEntity(t) })
        db.favorites().replaceAll(state.favorites.map { it.value })
    }

    override suspend fun loadTokens(): Map<String, PairedDevice> {
        val raw = secrets.get(TOKENS_KEY) ?: return emptyMap()
        return json.decodeFromString(tokenMap, raw).mapValues { (_, v) ->
            val (id, fp) = v.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            PairedDevice(DeviceId(id), fp)
        }
    }

    override suspend fun saveToken(token: String, device: PairedDevice) {
        val all = loadTokens().mapValues { it.value.packed() } + (token to device.packed())
        secrets.put(TOKENS_KEY, json.encodeToString(tokenMap, all))
    }

    override suspend fun removeTokensFor(deviceId: DeviceId) {
        val kept = loadTokens().filterValues { it.deviceId != deviceId }.mapValues { it.value.packed() }
        secrets.put(TOKENS_KEY, json.encodeToString(tokenMap, kept))
    }

    private fun PairedDevice.packed() = "${deviceId.value}|$certFingerprint"

    companion object {
        const val ROLE_NONE = "NONE"
        const val ROLE_COORDINATOR = "COORDINATOR"
        const val ROLE_MEMBER = "MEMBER"
        private const val TOKENS_KEY = "household:tokens"
    }
}

private suspend fun <T> ClickarrDatabase.withTransaction(block: suspend () -> T): T = androidx.room.withTransaction(this, block)
