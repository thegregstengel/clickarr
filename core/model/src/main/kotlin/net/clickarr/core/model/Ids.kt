package net.clickarr.core.model

import kotlinx.serialization.Serializable

/** A configured media server connection on this install or in the household. */
@Serializable
@JvmInline
value class ProviderId(val value: String)

/** The server's own identifier for an item: Plex ratingKey, Jellyfin or Emby item GUID. */
@Serializable
@JvmInline
value class NativeItemId(val value: String)

/** Globally unique reference to media inside Clickarr: which server, which item. */
@Serializable
data class MediaRef(val provider: ProviderId, val id: NativeItemId)

@Serializable
@JvmInline
value class ChannelId(val value: String)

@Serializable
@JvmInline
value class LineupSnapshotId(val value: String)

@Serializable
@JvmInline
value class DeviceId(val value: String)

@Serializable
@JvmInline
value class HouseholdId(val value: String)
