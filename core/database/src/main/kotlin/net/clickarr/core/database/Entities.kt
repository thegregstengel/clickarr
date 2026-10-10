package net.clickarr.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema v1 (proposal section 7). Nested model values (artwork, versions, sources, icons) are
 * stored as JSON strings via kotlinx.serialization; everything the app queries on is a real column.
 * Credentials are never here; see core:secrets.
 */

@Entity(tableName = "provider_connection")
data class ProviderConnectionEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val serverIdentity: String,
    val name: String,
    val baseUrl: String,
    /** JSON array of alternative base URLs, best first. */
    val altUrls: String,
    val version: String?,
    val lastOkAt: Long?,
)

@Entity(
    tableName = "library",
    primaryKeys = ["providerId", "nativeId"],
)
data class LibraryEntity(
    val providerId: String,
    val nativeId: String,
    val name: String,
    val kind: String,
)

@Entity(
    tableName = "media_item",
    primaryKeys = ["providerId", "nativeId"],
    indices = [Index("providerId", "parentNativeId"), Index("providerId", "type")],
)
data class MediaItemEntity(
    val providerId: String,
    val nativeId: String,
    /** SHOW, SEASON, EPISODE, MOVIE */
    val type: String,
    val title: String,
    val year: Int?,
    val parentNativeId: String?,
    val grandparentNativeId: String?,
    val grandparentTitle: String?,
    val seasonIndex: Int?,
    val episodeIndex: Int?,
    val runtimeMs: Long?,
    /** JSON array of strings */
    val genres: String,
    val studio: String?,
    val network: String?,
    /** JSON Artwork */
    val artwork: String,
    /** JSON List<MediaVersion> */
    val versions: String,
    val seasonCount: Int?,
    val episodeCount: Int?,
    val fetchedAt: Long,
)

@Entity(
    tableName = "channel",
    indices = [Index(value = ["number"], unique = true)],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val number: Int,
    val name: String,
    /** JSON ChannelIcon? */
    val icon: String?,
    /** JSON ProgrammingSource */
    val source: String,
    val orderMode: String,
    val slotRoundingMs: Long?,
    val seed: Long,
    val runsMin: Int?,
    val runsMax: Int?,
    val lineupId: String,
    val anchorEpochMs: Long,
    val pendingLineupId: String?,
    val pendingAtEpochMs: Long?,
    val enabled: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "lineup_snapshot", indices = [Index("channelId")])
data class LineupSnapshotEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val createdAtEpochMs: Long,
    val contentHash: String,
    val entryCount: Int,
)

@Entity(
    tableName = "lineup_entry",
    primaryKeys = ["lineupId", "position"],
)
data class LineupEntryEntity(
    val lineupId: String,
    val position: Int,
    val providerId: String,
    val nativeId: String,
    val durationMs: Long,
    val title: String,
    val subtitle: String?,
    val groupKey: String?,
)

/** Single row (id = 1): this install's household membership. */
@Entity(tableName = "household")
data class HouseholdEntity(
    @PrimaryKey val id: Int = 1,
    val householdId: String,
    val name: String,
    val coordinatorDeviceId: String,
    val createdAtEpochMs: Long,
    /** NONE, COORDINATOR, MEMBER */
    val role: String,
    val revision: Long,
    val schedulerVersion: Int,
    /** Member only: where the coordinator was last reached. */
    val coordinatorBaseUrl: String?,
    val coordinatorFingerprint: String?,
    val lastSyncEpochMs: Long?,
)

@Entity(tableName = "household_device")
data class HouseholdDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val joinedAtEpochMs: Long,
    val lastSeenEpochMs: Long?,
)

@Entity(tableName = "household_server")
data class HouseholdServerEntity(
    @PrimaryKey val serverIdentity: String,
    val kind: String,
    val name: String,
    /** JSON array of URLs */
    val urls: String,
)

@Entity(tableName = "favorite")
data class FavoriteEntity(@PrimaryKey val channelId: String)

@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val key: String, val value: String)
