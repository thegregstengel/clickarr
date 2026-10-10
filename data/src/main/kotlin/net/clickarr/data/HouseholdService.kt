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
import net.clickarr.household.coordinator.TlsFrontDoor
import net.clickarr.household.client.pinnedTo
import net.clickarr.household.client.TrustOnFirstUse
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
 * Transport is TLS from the device certificate (ADR 0013): a TlsFrontDoor on the LAN port in front of a
 * loopback-only engine. Members pin the coordinator's certificate fingerprint on first use. Plain HTTP is
 * the fallback only when the device cannot start TLS, and it is advertised as such.
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
        data class Coordinator(
            val port: Int,
            val pin: net.clickarr.household.coordinator.Coordinator.ActivePin?,
            val tls: Boolean = true,
        ) : Role
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
    private var frontDoor: TlsFrontDoor? = null
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
        val listening = startListening(c) ?: return Outcome.Failure(ClickarrError.Unknown("Could not start the household server"))
        server = listening.server
        frontDoor = listening.door
        val port = listening.port
        HouseholdClockOffset.reset()
        _role.value = Role.Coordinator(port, null, listening.tls)
        Log.d(TAG) { "coordinator listening on $port, tls=${listening.tls}" }
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
                tls = listening.tls,
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

    private class Listening(val server: EmbeddedServer<*, *>, val door: TlsFrontDoor?, val port: Int, val tls: Boolean)

    /**
     * TLS on the LAN port in front of a loopback-only engine. Plain HTTP on the LAN port only if the device
     * cannot start TLS at all, which spike C is meant to rule out; the role and the advertisement say which.
     */
    private suspend fun startListening(c: Coordinator): Listening? {
        val secure = runCatching {
            val engine = startServer(c, "127.0.0.1", 0)
            val backendPort = enginePort(engine) ?: run { engine.stop(0, 0); error("engine port unknown") }
            val ssl = TlsFrontDoor.sslContext(DeviceIdentity.keyStore().also { DeviceIdentity.certificate() }, DeviceIdentity.ALIAS, null)
            val door = TlsFrontDoor(ssl, backendPort)
            val port = runCatching { door.start(DEFAULT_PORT) }.getOrElse { door.start(0) }
            Listening(engine, door, port, tls = true)
        }.onFailure { Log.w(TAG, it) { "TLS front door failed; falling back to plain HTTP on the LAN (insecure)" } }.getOrNull()
        if (secure != null) return secure
        return runCatching {
            val engine = runCatching { startServer(c, "0.0.0.0", DEFAULT_PORT) }.getOrElse { startServer(c, "0.0.0.0", 0) }
            Listening(engine, null, enginePort(engine) ?: DEFAULT_PORT, tls = false)
        }.onFailure { Log.w(TAG, it) { "household server failed to start" } }.getOrNull()
    }

    private suspend fun enginePort(engine: EmbeddedServer<*, *>): Int? = withTimeoutOrNull(CONNECTOR_TIMEOUT_MS) {
        withContext(Dispatchers.IO) { engine.engine.resolvedConnectors().firstOrNull()?.port }
    }

    /** Suspending start so no thread blocks inside a coroutine; returns once the engine is accepting. */
    private suspend fun startServer(c: Coordinator, host: String, port: Int): EmbeddedServer<*, *> {
        val s = embeddedServer(CIO, port = port, host = host) { coordinatorRoutes(c) }
        Log.d(TAG) { "starting engine on $host:$port" }
        withContext(Dispatchers.IO) { s.startSuspend(wait = false) }
        Log.d(TAG) { "engine up" }
        return s
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
        val typed = baseUrl.trim().trimEnd('/')
        // A typed address has no scheme: try TLS first, then plain HTTP for a coordinator that could not start it.
        val candidates = if ("://" in typed) listOf(typed) else listOf("https://$typed", "http://$typed")
        val p = when (val r = pairAny(candidates, coordinatorFingerprint, pin)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val paired = p.response
        Log.i(TAG) { "paired with ${paired.householdId.value} (rev ${paired.state.revision}) at ${p.url}" }
        secrets.put(MEMBER_TOKEN, paired.deviceToken)
        store.saveState(paired.state)
        Log.d(TAG) { "member state saved" }
        val now = systemClock.now().toEpochMilliseconds()
        db.household().get()?.let {
            db.household().upsert(
                it.copy(
                    role = RoomCoordinatorStore.ROLE_MEMBER,
                    coordinatorBaseUrl = p.url,
                    coordinatorFingerprint = p.fingerprint,
                    lastSyncEpochMs = now,
                ),
            )
        }
        db.household().get()?.let { startMember(it) }
        Log.i(TAG) { "joined as member; role=${_role.value::class.simpleName}" }
        return Outcome.Success(Unit)
    }

    private class Paired(val response: PairCompleteResponse, val url: String, val fingerprint: String)

    /** Tries each address in turn, moving on only when the coordinator was unreachable there. */
    private suspend fun pairAny(urls: List<String>, coordinatorFingerprint: String?, pin: String): Outcome<Paired> {
        var last: Outcome<Paired> = Outcome.Failure(ClickarrError.Unreachable("No address to try"))
        for (url in urls) {
            last = pairWith(url, coordinatorFingerprint, pin)
            if (last is Outcome.Success || (last as Outcome.Failure).error !is ClickarrError.Unreachable) break
        }
        return last
    }

    /**
     * Probe the coordinator, check versions, and run the pairing exchange. Over TLS the certificate seen on the
     * wire is the coordinator's identity and the pairing proof binds it; a device in the middle presenting its
     * own certificate cannot finish pairing. Over plain HTTP the only identity is what the coordinator states.
     */
    private suspend fun pairWith(url: String, coordinatorFingerprint: String?, pin: String): Outcome<Paired> {
        val advertised = coordinatorFingerprint?.takeIf { it.isNotBlank() }
        val trust = TrustOnFirstUse(advertised)
        val probe = HouseholdClient(okHttp.pinnedTo(trust), url) { null }
        val info = when (val r = probe.info()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        if (info.protocolVersion > PROTOCOL_VERSION) return Outcome.Failure(ClickarrError.Unsupported("Update Clickarr on this TV to join"))
        val expected = trust.observed ?: advertised ?: info.fingerprint
        if (advertised != null && info.fingerprint.isNotBlank() && advertised != info.fingerprint) {
            return Outcome.Failure(ClickarrError.Unauthorized("That TV's identity does not match what was advertised"))
        }
        return HouseholdJoin.join(probe, selfId, prefs.deviceNameNow(), DeviceIdentity.fingerprint(), expected, pin)
            .map { Paired(it, url, expected) }
    }

    private suspend fun startMember(h: HouseholdEntity) {
        val url = h.coordinatorBaseUrl ?: return
        val pinned = okHttp.pinnedTo(TrustOnFirstUse(h.coordinatorFingerprint?.takeIf { it.isNotBlank() }))
        val c = HouseholdClient(pinned, url) { kotlinx.coroutines.runBlocking { secrets.get(MEMBER_TOKEN) } }
        client = c
        _role.value = Role.Member(url, connected = false, lastSync = h.lastSyncEpochMs?.let(Instant::fromEpochMilliseconds))
        Log.d(TAG) { "member of ${h.name} via $url" }
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
            is Outcome.Failure -> {
                Log.w(TAG) { "sync: info failed: ${info.error.message}" }
                return false
            }
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
        frontDoor?.stop()
        frontDoor = null
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
