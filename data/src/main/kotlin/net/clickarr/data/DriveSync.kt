package net.clickarr.data

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.database.ClickarrDatabase
import net.clickarr.core.database.SyncStateEntity
import net.clickarr.core.model.HouseholdState
import net.clickarr.core.model.ProviderKind
import net.clickarr.core.model.ServerLocation
import net.clickarr.household.coordinator.CoordinatorStore
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.HouseholdReducer
import net.clickarr.household.protocol.ProtocolJson

/**
 * Google Drive as the household store (ADR 0020). The same `HouseholdState` document, the same commands
 * through the same reducer; Drive holds one file in the app folder with the revision in its properties.
 * Writes apply locally, are queued, and are pushed; if another device pushed first, its state is taken
 * and the queued commands replayed on top before pushing again. Reads happen on start, after writes,
 * and every [POLL]. Before applying a remote state the Plex server identity has to match.
 */
class DriveSync(
    private val auth: GoogleDeviceAuth,
    private val drive: DriveAppData,
    private val store: CoordinatorStore,
    private val db: ClickarrDatabase,
    private val registry: ProviderRegistry,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object SignedOut : Status
        data class Linking(val userCode: String, val url: String) : Status
        data class SignedIn(val email: String?, val syncing: Boolean, val lastSync: Instant?, val message: String?) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.SignedOut)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val reducer = HouseholdReducer()
    private val lock = Mutex()
    private var pollJob: Job? = null
    private var linkJob: Job? = null

    suspend fun start() {
        if (!auth.isSignedIn()) {
            _status.value = Status.SignedOut
            return
        }
        _status.value = Status.SignedIn(auth.email(), syncing = false, lastSync = null, message = null)
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                syncNow()
                delay(POLL)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        linkJob?.cancel()
    }

    /** Shows a code; polls Google until the viewer approves on a phone, then syncs. */
    fun beginSignIn() {
        linkJob?.cancel()
        linkJob = scope.launch {
            val code = when (val r = auth.begin()) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> {
                    _status.value = Status.SignedIn(null, false, null, r.error.message)
                    _status.value = Status.SignedOut
                    return@launch
                }
            }
            _status.value = Status.Linking(code.userCode, code.verificationUrl)
            var interval = code.intervalSec.seconds
            val deadline = clock.now() + code.expiresInSec.seconds
            while (clock.now() < deadline) {
                delay(interval)
                when (val p = auth.poll(code)) {
                    GoogleDeviceAuth.Poll.Pending -> Unit
                    GoogleDeviceAuth.Poll.SlowDown -> interval += SLOW_DOWN_STEP
                    GoogleDeviceAuth.Poll.Authorized -> {
                        auth.rememberEmail()
                        start()
                        return@launch
                    }
                    is GoogleDeviceAuth.Poll.Denied -> {
                        Log.w(TAG) { "google sign-in denied: ${p.reason}" }
                        _status.value = Status.SignedOut
                        return@launch
                    }
                }
            }
            _status.value = Status.SignedOut
        }
    }

    suspend fun signOut() {
        stop()
        auth.signOut()
        _status.value = Status.SignedOut
    }

    /** A local write: apply through the reducer, queue the command, push. */
    suspend fun apply(command: Command): Outcome<Unit> = lock.withLock {
        val local = store.loadState() ?: return Outcome.Failure(ClickarrError.Invalid("Nothing to sync yet"))
        val next = when (val r = reducer.apply(local, command, clock.now())) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        store.saveState(next)
        setPending(pending() + command)
        Outcome.Success(Unit)
    }.also { if (it is Outcome.Success) scope.launch { syncNow() } }

    suspend fun syncNow() {
        val signedIn = _status.value as? Status.SignedIn ?: return
        _status.value = signedIn.copy(syncing = true)
        val result = lock.withLock {
            runCatching { reconcile() }.getOrElse { Outcome.Failure(ClickarrError.Unknown(it.message ?: "sync failed")) }
        }
        _status.update { s ->
            if (s !is Status.SignedIn) s else when (result) {
                is Outcome.Success -> s.copy(syncing = false, lastSync = clock.now(), message = null)
                is Outcome.Failure -> s.copy(syncing = false, message = result.error.message)
            }
        }
    }

    private suspend fun reconcile(): Outcome<Unit> {
        val loaded = store.loadState() ?: return Outcome.Success(Unit)
        val local = registerServer(loaded)
        val base = db.syncState().get(KEY_BASE)?.toLongOrNull() ?: 0L
        return when (val found = drive.find(FILE_NAME)) {
            is Outcome.Failure -> found
            is Outcome.Success -> {
                val remote = found.value
                when {
                    remote == null -> push(local, fileId = null)
                    (remote.revision ?: 0L) <= base ->
                        if (local.revision > base || pending().isNotEmpty()) push(local, remote.id) else Outcome.Success(Unit)
                    else -> pullAndMerge(remote)
                }
            }
        }
    }

    /** Another device wrote first: take its state, replay what this one queued, and push the result. */
    private suspend fun pullAndMerge(remote: DriveAppData.File): Outcome<Unit> {
        val text = when (val r = drive.download(remote.id)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return r
        }
        val theirs = runCatching { ProtocolJson.decodeFromString(HouseholdState.serializer(), text) }
            .getOrElse { return Outcome.Failure(ClickarrError.Invalid("The sync file on Drive could not be read")) }
        sameServer(theirs)?.let { return Outcome.Failure(it) }
        val merged = pending().fold(theirs) { acc, cmd -> (reducer.apply(acc, cmd, clock.now()) as? Outcome.Success)?.value ?: acc }
        store.saveState(merged)
        return push(merged, remote.id)
    }

    private suspend fun push(state: HouseholdState, fileId: String?): Outcome<Unit> {
        val body = ProtocolJson.encodeToString(HouseholdState.serializer(), state)
        val r = if (fileId == null) drive.create(FILE_NAME, body, state.revision) else drive.update(fileId, body, state.revision)
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                db.syncState().put(SyncStateEntity(KEY_BASE, state.revision.toString()))
                setPending(emptyList())
                Outcome.Success(Unit)
            }
        }
    }

    /** The file belongs to one Plex server; a state for another server would reference items this one lacks. */
    private fun sameServer(theirs: HouseholdState): ClickarrError? {
        val mine = registry.primary?.server ?: return null
        val listed = theirs.servers.filter { it.kind == ProviderKind.PLEX }
        if (listed.isEmpty() || listed.any { it.serverIdentity == mine.serverIdentity }) return null
        val other = listed.first().name
        return ClickarrError.Invalid("This Drive backup belongs to Plex server $other; this TV is signed into ${mine.name}. Not applied.")
    }

    /** Record this TV's Plex server in the state so other devices can check they match. */
    private suspend fun registerServer(state: HouseholdState): HouseholdState {
        val mine = registry.primary?.server ?: return state
        if (state.servers.any { it.serverIdentity == mine.serverIdentity }) return state
        val cmd = Command.RegisterServer(ServerLocation(ProviderKind.PLEX, mine.serverIdentity, mine.name, listOf(mine.baseUrl)))
        val next = (reducer.apply(state, cmd, clock.now()) as? Outcome.Success)?.value ?: return state
        store.saveState(next)
        return next
    }

    private suspend fun pending(): List<Command> = db.syncState().get(KEY_PENDING)
        ?.let { runCatching { ProtocolJson.decodeFromString(ListSerializer(Command.serializer()), it) }.getOrNull() }
        .orEmpty()

    private suspend fun setPending(commands: List<Command>) {
        db.syncState().put(SyncStateEntity(KEY_PENDING, ProtocolJson.encodeToString(ListSerializer(Command.serializer()), commands)))
    }

    companion object {
        private const val TAG = "DriveSync"
        const val FILE_NAME = "household-state.json"
        private const val KEY_BASE = "drive:base"
        private const val KEY_PENDING = "drive:pending"
        private val POLL = 15.minutes
        private val SLOW_DOWN_STEP = 5.seconds
    }
}
