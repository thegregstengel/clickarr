package net.clickarr.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock as KxClock
import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.database.HouseholdEntity
import net.clickarr.core.database.toModel
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.core.model.HouseholdState
import net.clickarr.core.scheduling.SCHEDULER_VERSION
import net.clickarr.core.secrets.SecretStore
import net.clickarr.household.client.HouseholdClient
import net.clickarr.household.client.HouseholdJoin
import net.clickarr.household.coordinator.Coordinator
import net.clickarr.household.coordinator.coordinatorRoutes
import net.clickarr.household.discovery.DeviceIdentity
import net.clickarr.household.discovery.DiscoveredCoordinator
import net.clickarr.household.discovery.HouseholdDiscovery
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.DEFAULT_PORT
import net.clickarr.household.protocol.Event
import net.clickarr.household.protocol.PROTOCOL_VERSION
import net.clickarr.household.protocol.PairCompleteResponse
import net.clickarr.household.protocol.Pairing
import okhttp3.OkHttpClient

/**
 * This install's place in a Clickarr Household (proposal 14, 15). One of three roles:
 *
 *  - None: channels are local; edits write the database directly.
 *  - Coordinator: runs the HTTP server, owns the canonical state, advertises on the LAN.
 *  - Member: keeps a cached copy, follows the coordinator's events, sends edits as commands.
 *
 * Transport is plain LAN HTTP in this build; the TLS acceptor lands once spike C has a verdict (ADR 0013).
 */
@Singleton
class HouseholdService @Inject constructor(
    @ApplicationContext context: Context,
    private val db: ClickarrDatabase,
    private val secrets: SecretStore,
    private val prefs: DevicePrefs,
    private val okHttp: OkHttpClient,
    @net.clickarr.di.ApplicationScope private val scope: CoroutineScope,
) {
    sealed interface Role {
        data object None : Role
        data class Coordinator(val port: Int, val pin: net.clickarr.household.coordinator.Coordinator.ActivePin?) : Role
        data class Member(val baseUrl: String, val connected: Boolean, val lastSync: Instant?) : Role
    }

    private val systemClock = Clock { KxClock.System.now() }
    private val store = RoomCoordinatorStore(db, secrets) { systemClock.now() }
    private val discovery = HouseholdDiscovery(context)

    private val _role = MutableStateFlow<Role>(Role.None)
    val role: StateFlow<Role> = _role.asStateFlow()

    val household: Flow<HouseholdEntity?> = db.household().observe()
    val devices: Flow<List<HouseholdDevice>> = db.household().observeDevices().map { list -> list.map { it.toModel() } }

    private var coordinator: Coordinator? = null
    private var server: EmbeddedServer<*, *>? = null
    private var tickJob: Job? = null
    private var memberJob: Job? = null
    private var client: HouseholdClient? = null

    private val selfId get() = DeviceId(prefs.deviceId)

    suspend fun start() {
        val h = db.household().get()
        when (h?.role) {
            RoomCoordinatorStore.ROLE_COORDINATOR -> startCoordinator()
            RoomCoordinatorStore.ROLE_MEMBER -> startMember(h)
            else -> _role.value = Role.None
        }
    }

    // ---- Coordinator ----

    suspend fun createHousehold(name: String): Outcome<Unit> {
        if (_role.value !is Role.None) return Outcome.Failure(ClickarrError.Invalid("Already in a household"))
        val now = systemClock.now()
        db.household().upsert(
            HouseholdEntity(
                householdId = UUID.randomUUID().toString(),
                name = name.ifBlank { "Home" },
                coordinatorDeviceId = selfId.value,
                createdAtEpochMs = now.toEpochMilliseconds(),
                role = RoomCoordinatorStore.ROLE_COORDINATOR,
                revision = 1,
                schedulerVersion = SCHEDULER_VERSION,
                coordinatorBaseUrl = null,
                coordinatorFingerprint = null,
                lastSyncEpochMs = now.toEpochMilliseconds(),
            ),
        )
        val self = net.clickarr.core.database.HouseholdDeviceEntity(
            selfId.value, prefs.deviceNameNow(), now.toEpochMilliseconds(), now.toEpochMilliseconds(),
        )
        db.household().replaceDevices(listOf(self))
        return startCoordinator()
    }

    private suspend fun startCoordinator(): Outcome<Unit> {
        val initial = store.loadState() ?: return Outcome.Failure(ClickarrError.Invalid("No household to coordinate"))
        Log.d(TAG) { "starting coordinator for ${initial.household.name} (rev ${initial.revision})" }
        val c = Coordinator(initial, store, systemClock, fingerprint = { DeviceIdentity.fingerprint() })
        c.start()
        coordinator = c
        var port = DEFAULT_PORT
        val started = withContext(Dispatchers.IO) {
            runCatching {
                embeddedServer(CIO, port = DEFAULT_PORT, host = "0.0.0.0") { coordinatorRoutes(c) }.also { it.start(wait = false) }
            }.recoverCatching {
                port = 0
                embeddedServer(CIO, port = 0, host = "0.0.0.0") { coordinatorRoutes(c) }.also { it.start(wait = false) }
            }
        }
        val s = started.getOrElse { return Outcome.Failure(ClickarrError.Unknown("Could not start the household server", it)) }
        server = s
        Log.d(TAG) { "server started" }
        HouseholdClockOffset.reset()
        // Show the role immediately; the exact port only matters when the default was taken.
        _role.value = Role.Coordinator(port, null)
        if (port == 0) {
            port = withTimeoutOrNull(CONNECTOR_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { s.engine.resolvedConnectors().firstOrNull()?.port }
            } ?: DEFAULT_PORT
            _role.value = Role.Coordinator(port, null)
        }
        Log.d(TAG) { "coordinator listening on $port" }
        val fingerprint = runCatching { DeviceIdentity.fingerprint() }.getOrElse {
            Log.w(TAG, it) { "device identity unavailable" }
            ""
        }
        discovery.advertise(
            HouseholdDiscovery.Advertisement(
                serviceName = "Clickarr ${prefs.deviceNameNow()}",
                port = port,
                householdId = initial.household.id.value,
                householdName = initial.household.name,
                deviceId = selfId.value,
                fingerprint = fingerprint,
                role = "coordinator",
            ),
        )
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1.minutes)
                c.tick()
                _role.update { r -> if (r is Role.Coordinator) r.copy(pin = r.pin?.takeIf { it.expiresAt > systemClock.now() }) else r }
            }
        }
        Log.i(TAG) { "coordinator up on $port" }
        return Outcome.Success(Unit)
    }

    fun beginAddDevice(): Coordinator.ActivePin? {
        val pin = coordinator?.beginAcceptingJoins() ?: return null
        _role.update { r -> if (r is Role.Coordinator) r.copy(pin = pin) else r }
        return pin
    }

    fun stopAddDevice() {
        coordinator?.stopAcceptingJoins()
        _role.update { r -> if (r is Role.Coordinator) r.copy(pin = null) else r }
    }

    suspend fun removeDevice(id: DeviceId): Outcome<Unit> = apply(Command.RemoveDevice(id)).map { }

    // ---- Member ----

    fun discover(): Flow<List<DiscoveredCoordinator>> = discovery.browse()

    suspend fun join(baseUrl: String, coordinatorFingerprint: String?, pin: String): Outcome<Unit> {
        if (_role.value !is Role.None) return Outcome.Failure(ClickarrError.Invalid("Already in a household"))
        val url = baseUrl.trimEnd('/')
        val paired = when (val r = pairWith(url, coordinatorFingerprint, pin)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        secrets.put(MEMBER_TOKEN, paired.deviceToken)
        store.saveState(paired.state)
        val now = systemClock.now().toEpochMilliseconds()
        db.household().get()?.let {
            db.household().upsert(
                it.copy(
                    role = RoomCoordinatorStore.ROLE_MEMBER,
                    coordinatorBaseUrl = url,
                    coordinatorFingerprint = paired.coordinatorFingerprint,
                    lastSyncEpochMs = now,
                ),
            )
        }
        db.household().get()?.let { startMember(it) }
        return Outcome.Success(Unit)
    }

    /** Probe the coordinator, check versions, and run the pairing exchange. */
    private suspend fun pairWith(url: String, coordinatorFingerprint: String?, pin: String): Outcome<PairCompleteResponse> {
        val probe = HouseholdClient(okHttp, url) { null }
        val info = when (val r = probe.info()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        if (info.protocolVersion > PROTOCOL_VERSION) return Outcome.Failure(ClickarrError.Unsupported("Update Clickarr on this TV to join"))
        return HouseholdJoin.join(probe, selfId, prefs.deviceNameNow(), DeviceIdentity.fingerprint(), coordinatorFingerprint ?: "", pin)
    }

    private suspend fun startMember(h: HouseholdEntity) {
        val url = h.coordinatorBaseUrl ?: return
        val c = HouseholdClient(okHttp, url) { kotlinx.coroutines.runBlocking { secrets.get(MEMBER_TOKEN) } }
        client = c
        _role.value = Role.Member(url, connected = false, lastSync = h.lastSyncEpochMs?.let(Instant::fromEpochMilliseconds))
        memberJob?.cancel()
        memberJob = scope.launch { memberLoop(c) }
    }

    private suspend fun memberLoop(c: HouseholdClient) {
        var backoff = 5.seconds
        while (true) {
            val ok = syncOnce(c)
            if (ok) {
                backoff = 5.seconds
                _role.update { r -> if (r is Role.Member) r.copy(connected = true, lastSync = systemClock.now()) else r }
                // Follow events until the socket drops, with a safety poll every five minutes.
                val watcher = scope.launch { while (true) { delay(5.minutes); syncOnce(c) } }
                c.events().catch { }.onEach { ev -> if (ev is Event.RevisionChanged) syncOnce(c) }.collect()
                watcher.cancel()
            }
            _role.update { r -> if (r is Role.Member) r.copy(connected = false) else r }
            delay(backoff)
            backoff = minOf(backoff * 2, 60.seconds)
        }
    }

    private suspend fun syncOnce(c: HouseholdClient): Boolean {
        val known = db.household().get()?.revision
        when (val info = c.info()) {
            is Outcome.Success -> {
                HouseholdClockOffset.observe(info.value.now.toEpochMilliseconds(), systemClock.now().toEpochMilliseconds())
            }
            is Outcome.Failure -> return false
        }
        return when (val r = c.state(known)) {
            is Outcome.Success -> {
                val fetch = r.value
                if (fetch is HouseholdClient.StateFetch.Fresh) applyRemote(fetch.state)
                _role.update { role -> if (role is Role.Member) role.copy(connected = true, lastSync = systemClock.now()) else role }
                true
            }
            is Outcome.Failure -> {
                Log.w(TAG) { "sync failed: ${r.error.message}" }
                false
            }
        }
    }

    private suspend fun applyRemote(state: HouseholdState) {
        if (state.schedulerVersion > SCHEDULER_VERSION) {
            Log.w(TAG) { "coordinator runs scheduler v${state.schedulerVersion}; this build has v$SCHEDULER_VERSION, not applying" }
            return
        }
        store.saveState(state)
    }

    suspend fun syncNow() {
        client?.let { syncOnce(it) }
    }

    // ---- Shared ----

    /**
     * Route a write through the household. Returns Success(true) when the household handled it,
     * Success(false) when this device is not in a household (caller writes locally), or the failure.
     */
    suspend fun apply(command: Command): Outcome<Boolean> = when (val r = _role.value) {
        is Role.None -> Outcome.Success(false)
        is Role.Coordinator -> coordinator?.apply(command, selfId)?.map { true } ?: Outcome.Success(false)
        is Role.Member -> {
            val c = client ?: return Outcome.Failure(ClickarrError.Unreachable("Not connected to the household"))
            when (val sent = c.send(command)) {
                is Outcome.Success -> {
                    syncOnce(c)
                    Outcome.Success(true)
                }
                is Outcome.Failure -> Outcome.Failure(offlineMessage(r, sent.error))
            }
        }
    }

    private fun offlineMessage(role: Role.Member, error: ClickarrError): ClickarrError {
        if (error !is ClickarrError.Unreachable) return error
        val host = role.baseUrl.removePrefix("http://")
        return ClickarrError.Unreachable("$host (household coordinator) is offline. You can still watch; changes need it online.")
    }

    suspend fun leave() {
        memberJob?.cancel(); memberJob = null
        tickJob?.cancel(); tickJob = null
        discovery.stopAdvertising()
        withContext(Dispatchers.IO) { server?.stop(200, 1000) }
        server = null
        coordinator = null
        client = null
        secrets.remove(MEMBER_TOKEN)
        db.household().clear()
        db.household().clearDevices()
        db.household().clearServers()
        HouseholdClockOffset.reset()
        _role.value = Role.None
    }

    companion object {
        private const val TAG = "Household"
        private const val CONNECTOR_TIMEOUT_MS = 5_000L
        private const val MEMBER_TOKEN = "household:member-token"
        val PIN_LIFETIME = Pairing.PIN_LIFETIME_SECONDS.seconds
    }
}
