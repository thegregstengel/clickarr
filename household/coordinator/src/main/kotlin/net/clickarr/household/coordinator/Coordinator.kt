package net.clickarr.household.coordinator

import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Clock
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdDevice
import net.clickarr.core.model.HouseholdState
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.Event
import net.clickarr.household.protocol.HouseholdReducer
import net.clickarr.household.protocol.InfoResponse
import net.clickarr.household.protocol.PairCompleteRequest
import net.clickarr.household.protocol.PairCompleteResponse
import net.clickarr.household.protocol.PairStartRequest
import net.clickarr.household.protocol.PairStartResponse
import net.clickarr.household.protocol.Pairing

/**
 * The household's single writer (ADR 0011). Holds the canonical state, applies commands through the
 * reducer, runs the pairing state machine (proposal 13.4), and authenticates members by bearer token.
 * Transport-agnostic: CoordinatorRoutes exposes it over HTTP.
 */
class Coordinator(
    initial: HouseholdState,
    private val store: CoordinatorStore,
    private val clock: Clock,
    /** This device's certificate fingerprint, from the TLS identity. */
    private val fingerprint: () -> String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val reducer = HouseholdReducer()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<HouseholdState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var tokens: MutableMap<String, PairedDevice> = HashMap()

    /** Pairing is only possible while the user has opened the "Add device" screen. */
    private var activePin: ActivePin? = null
    private val sessions = HashMap<String, PairSession>()

    data class ActivePin(val pin: String, val expiresAt: Instant)

    private class PairSession(
        val deviceId: DeviceId,
        val deviceName: String,
        val joinerFingerprint: String,
        val nonce: String,
        val expiresAt: Instant,
        var attempts: Int = 0,
    )

    suspend fun start() {
        tokens = store.loadTokens().toMutableMap()
        store.loadState()?.let { _state.value = it }
    }

    fun info(): InfoResponse = state.value.let { s ->
        InfoResponse(
            schedulerVersion = s.schedulerVersion,
            householdId = s.household.id,
            householdName = s.household.name,
            coordinator = s.household.coordinator,
            revision = s.revision,
            now = clock.now(),
            acceptingJoins = activePin?.let { it.expiresAt > clock.now() } == true,
            fingerprint = fingerprint(),
        )
    }

    fun authenticate(bearer: String?): DeviceId? = bearer?.let { tokens[it]?.deviceId }

    /** Apply a command on behalf of [from] (a paired device or the coordinator itself). */
    suspend fun apply(command: Command, from: DeviceId): Outcome<HouseholdState> = mutex.withLock {
        val now = clock.now()
        when (val result = reducer.apply(_state.value, command, now)) {
            is Outcome.Success -> {
                val touched = result.value.copy(devices = result.value.devices.map { d -> if (d.id == from) d.copy(lastSeen = now) else d })
                commit(touched)
                if (command is Command.RemoveDevice) {
                    store.removeTokensFor(command.deviceId)
                    tokens.entries.removeIf { it.value.deviceId == command.deviceId }
                }
                Outcome.Success(touched)
            }
            is Outcome.Failure -> result
        }
    }

    /** Called periodically and on each read so pending lineups cut over on time without a command. */
    suspend fun tick(): HouseholdState = mutex.withLock {
        val current = _state.value
        val next = reducer.applyCutovers(current, clock.now())
        if (next != current) commit(next.copy(revision = current.revision + 1))
        _state.value
    }

    suspend fun markSeen(deviceId: DeviceId) = mutex.withLock {
        val now = clock.now()
        val s = _state.value
        if (s.devices.any { it.id == deviceId }) {
            _state.value = s.copy(devices = s.devices.map { d -> if (d.id == deviceId) d.copy(lastSeen = now) else d })
        }
    }

    // Pairing

    /** User opened "Add device": mint a PIN for two minutes and return it for display. */
    fun beginAcceptingJoins(): ActivePin {
        val pin = ActivePin(Pairing.randomPin(random), clock.now() + Pairing.PIN_LIFETIME_SECONDS.seconds)
        activePin = pin
        sessions.clear()
        return pin
    }

    fun stopAcceptingJoins() {
        activePin = null
        sessions.clear()
    }

    fun pairStart(req: PairStartRequest): Outcome<PairStartResponse> {
        val pin = activePin?.takeIf { it.expiresAt > clock.now() }
            ?: return Outcome.Failure(ClickarrError.Unauthorized("This household is not accepting new devices right now"))
        val sessionId = UUID.randomUUID().toString()
        val session = PairSession(req.deviceId, req.deviceName, req.certFingerprint, Pairing.randomNonce(random), pin.expiresAt)
        sessions[sessionId] = session
        Log.i(TAG) { "pairing started for ${req.deviceName}" }
        return Outcome.Success(PairStartResponse(sessionId, session.nonce, session.expiresAt))
    }

    suspend fun pairComplete(req: PairCompleteRequest): Outcome<PairCompleteResponse> {
        val session = when (val check = validatePairing(req)) {
            is Outcome.Success -> check.value
            is Outcome.Failure -> return check
        }
        // Success: one PIN pairs one device.
        sessions.remove(req.sessionId)
        activePin = null
        val token = Pairing.randomToken(random)
        val device = PairedDevice(session.deviceId, session.joinerFingerprint)
        store.saveToken(token, device)
        tokens[token] = device
        val now = clock.now()
        mutex.withLock {
            val s = _state.value
            val devices = s.devices.filter { it.id != session.deviceId } + HouseholdDevice(session.deviceId, session.deviceName, now, now)
            commit(s.copy(revision = s.revision + 1, devices = devices))
        }
        Log.i(TAG) { "paired ${session.deviceName}" }
        return Outcome.Success(PairCompleteResponse(state.value.household.id, token, fingerprint(), state.value))
    }

    /** Window open, session known and fresh, proof correct (with the three-attempt rule). */
    private fun validatePairing(req: PairCompleteRequest): Outcome<PairSession> {
        val now = clock.now()
        val pin = activePin?.takeIf { it.expiresAt > now }
        val session = sessions[req.sessionId]
        val failure: String? = when {
            pin == null -> "Pairing window closed"
            session == null -> "Unknown pairing session"
            session.expiresAt <= now -> "Pairing session expired".also { sessions.remove(req.sessionId) }
            proofMatches(pin.pin, session, req) -> null
            else -> wrongPin(req.sessionId, session)
        }
        return if (failure == null) Outcome.Success(session!!) else Outcome.Failure(ClickarrError.Unauthorized(failure))
    }

    private fun proofMatches(pin: String, session: PairSession, req: PairCompleteRequest): Boolean {
        val expected = Pairing.proof(pin, session.nonce, req.sessionId, session.joinerFingerprint, fingerprint())
        return Pairing.verify(expected, req.proof)
    }

    private fun wrongPin(sessionId: String, session: PairSession): String {
        session.attempts++
        if (session.attempts < Pairing.MAX_ATTEMPTS) return "Wrong PIN"
        sessions.remove(sessionId)
        activePin = null
        Log.w(TAG) { "pairing aborted after ${Pairing.MAX_ATTEMPTS} wrong PINs" }
        return "Too many wrong PINs; start again from the other TV"
    }

    private suspend fun commit(next: HouseholdState) {
        _state.value = next
        store.saveState(next)
        _events.tryEmit(Event.RevisionChanged(next.revision))
    }

    companion object {
        private const val TAG = "Coordinator"
    }
}
