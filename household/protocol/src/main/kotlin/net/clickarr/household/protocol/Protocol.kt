package net.clickarr.household.protocol

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.clickarr.core.model.Channel
import net.clickarr.core.model.ChannelId
import net.clickarr.core.model.DeviceId
import net.clickarr.core.model.HouseholdId
import net.clickarr.core.model.HouseholdState
import net.clickarr.core.model.LineupSnapshot
import net.clickarr.core.model.ServerLocation

/**
 * Household protocol v1 (proposal section 14). Unknown JSON fields are ignored and new fields are
 * optional, so additions never break older members; the major version changes only on incompatible change.
 */
const val PROTOCOL_VERSION: Int = 1

/** Default coordinator port; the NSD record carries the real one (proposal 12.2). */
const val DEFAULT_PORT: Int = 47831

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

// GET /v1/info

@Serializable
data class InfoResponse(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val schedulerVersion: Int,
    val householdId: HouseholdId,
    val householdName: String,
    val coordinator: DeviceId,
    val revision: Long,
    /** Coordinator clock, so members can compute their offset (proposal 9.5). */
    val now: Instant,
    /** Whether the coordinator is currently accepting pairing attempts (user turned it on in Settings). */
    val acceptingJoins: Boolean = false,
)

// Pairing (proposal 13.4)

@Serializable
data class PairStartRequest(val deviceId: DeviceId, val deviceName: String, val certFingerprint: String)

@Serializable
data class PairStartResponse(val sessionId: String, val nonce: String, val expiresAt: Instant)

@Serializable
data class PairCompleteRequest(val sessionId: String, val proof: String)

@Serializable
data class PairCompleteResponse(
    val householdId: HouseholdId,
    val deviceToken: String,
    val coordinatorFingerprint: String,
    val state: HouseholdState,
)

// Commands: POST /v1/commands. The coordinator is the only writer (ADR 0011).

@Serializable
sealed interface Command {
    @Serializable
    @SerialName("createChannel")
    data class CreateChannel(val channel: Channel, val lineup: LineupSnapshot) : Command

    @Serializable
    @SerialName("updateChannel")
    data class UpdateChannel(val channel: Channel, val lineup: LineupSnapshot? = null) : Command

    @Serializable
    @SerialName("deleteChannel")
    data class DeleteChannel(val channelId: ChannelId) : Command

    @Serializable
    @SerialName("setFavorite")
    data class SetFavorite(val channelId: ChannelId, val favorite: Boolean) : Command

    @Serializable
    @SerialName("renameHousehold")
    data class RenameHousehold(val name: String) : Command

    @Serializable
    @SerialName("renameDevice")
    data class RenameDevice(val deviceId: DeviceId, val name: String) : Command

    @Serializable
    @SerialName("registerServer")
    data class RegisterServer(val server: ServerLocation) : Command

    @Serializable
    @SerialName("removeDevice")
    data class RemoveDevice(val deviceId: DeviceId) : Command
}

@Serializable
data class CommandResponse(val revision: Long)

@Serializable
data class ErrorResponse(val code: String, val message: String) {
    companion object {
        const val UNAUTHORIZED = "unauthorized"
        const val INVALID = "invalid"
        const val CONFLICT = "conflict"
        const val VERSION = "version"
        const val NOT_FOUND = "not_found"
    }
}

// Events: WebSocket /v1/events

@Serializable
sealed interface Event {
    @Serializable
    @SerialName("revision")
    data class RevisionChanged(val revision: Long) : Event

    @Serializable
    @SerialName("ping")
    data class Ping(val now: Instant) : Event
}
