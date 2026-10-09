package net.clickarr.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Household(
    val id: HouseholdId,
    val name: String,
    val coordinator: DeviceId,
    val createdAt: Instant,
)

@Serializable
data class HouseholdDevice(
    val id: DeviceId,
    val name: String,
    val joinedAt: Instant,
    val lastSeen: Instant? = null,
)

/** Where a media server lives. Never carries credentials (ADR 0014). */
@Serializable
data class ServerLocation(
    val kind: ProviderKind,
    val serverIdentity: String,
    val name: String,
    val urls: List<String>,
)

/** The single synchronized document (ADR 0011). */
@Serializable
data class HouseholdState(
    val revision: Long,
    val schedulerVersion: Int,
    val household: Household,
    val devices: List<HouseholdDevice>,
    val servers: List<ServerLocation>,
    val channels: List<Channel>,
    val lineups: List<LineupSnapshot>,
    val favorites: Set<ChannelId>,
)
