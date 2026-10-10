package net.clickarr.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderConnectionDao {
    @Query("SELECT * FROM provider_connection ORDER BY name")
    fun observeAll(): Flow<List<ProviderConnectionEntity>>

    @Query("SELECT * FROM provider_connection")
    suspend fun all(): List<ProviderConnectionEntity>

    @Query("SELECT * FROM provider_connection WHERE id = :id")
    suspend fun byId(id: String): ProviderConnectionEntity?

    @Upsert
    suspend fun upsert(connection: ProviderConnectionEntity)

    @Query("DELETE FROM provider_connection WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE provider_connection SET lastOkAt = :at, version = :version WHERE id = :id")
    suspend fun markOk(id: String, at: Long, version: String?)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE providerId = :providerId ORDER BY name")
    fun observe(providerId: String): Flow<List<LibraryEntity>>

    @Transaction
    suspend fun replaceAll(providerId: String, libraries: List<LibraryEntity>) {
        deleteFor(providerId)
        insertAll(libraries)
    }

    @Query("DELETE FROM library WHERE providerId = :providerId")
    suspend fun deleteFor(providerId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(libraries: List<LibraryEntity>)
}

@Dao
interface MediaItemDao {
    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_item WHERE providerId = :providerId AND nativeId IN (:nativeIds)")
    suspend fun byIds(providerId: String, nativeIds: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE providerId = :providerId AND grandparentNativeId = :showId ORDER BY seasonIndex, episodeIndex")
    suspend fun episodesOf(providerId: String, showId: String): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE providerId = :providerId AND type = :type ORDER BY title")
    fun observeByType(providerId: String, type: String): Flow<List<MediaItemEntity>>

    @Query("DELETE FROM media_item WHERE providerId = :providerId")
    suspend fun deleteFor(providerId: String)

    @Query("DELETE FROM media_item WHERE fetchedAt < :olderThan")
    suspend fun evictOlderThan(olderThan: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel ORDER BY number")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel ORDER BY number")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: String): ChannelEntity?

    @Query("SELECT * FROM channel WHERE number = :number")
    suspend fun byNumber(number: Int): ChannelEntity?

    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Query("DELETE FROM channel WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun replaceAll(channels: List<ChannelEntity>) {
        deleteAll()
        insertAll(channels)
    }

    @Query("DELETE FROM channel")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)
}

@Dao
interface LineupDao {
    @Transaction
    suspend fun insert(snapshot: LineupSnapshotEntity, entries: List<LineupEntryEntity>) {
        insertSnapshot(snapshot)
        insertEntries(entries)
    }

    @Query("SELECT * FROM lineup_entry WHERE lineupId IN (:ids) ORDER BY lineupId, position")
    suspend fun entriesFor(ids: List<String>): List<LineupEntryEntity>

    @Query("DELETE FROM lineup_entry")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM lineup_snapshot")
    suspend fun deleteAllSnapshots()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: LineupSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<LineupEntryEntity>)

    @Query("SELECT * FROM lineup_snapshot WHERE id = :id")
    suspend fun snapshot(id: String): LineupSnapshotEntity?

    @Query("SELECT * FROM lineup_entry WHERE lineupId = :id ORDER BY position")
    suspend fun entries(id: String): List<LineupEntryEntity>

    @Query("SELECT * FROM lineup_snapshot")
    suspend fun allSnapshots(): List<LineupSnapshotEntity>

    @Transaction
    suspend fun delete(id: String) {
        deleteEntries(id)
        deleteSnapshot(id)
    }

    @Query("DELETE FROM lineup_entry WHERE lineupId = :id")
    suspend fun deleteEntries(id: String)

    @Query("DELETE FROM lineup_snapshot WHERE id = :id")
    suspend fun deleteSnapshot(id: String)

    /** Snapshots no channel references any more (current or pending). */
    @Query(
        "SELECT id FROM lineup_snapshot WHERE id NOT IN (SELECT lineupId FROM channel) " +
            "AND id NOT IN (SELECT pendingLineupId FROM channel WHERE pendingLineupId IS NOT NULL)",
    )
    suspend fun orphanIds(): List<String>
}

@Dao
interface FavoriteDao {
    @Query("SELECT channelId FROM favorite")
    fun observeAll(): Flow<List<String>>

    @Query("SELECT channelId FROM favorite")
    suspend fun all(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Delete
    suspend fun remove(favorite: FavoriteEntity)

    @Transaction
    suspend fun replaceAll(ids: List<String>) {
        clear()
        addAll(ids.map { FavoriteEntity(it) })
    }

    @Query("DELETE FROM favorite")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(favorites: List<FavoriteEntity>)
}

@Dao
interface HouseholdDao {
    @Query("SELECT * FROM household WHERE id = 1")
    suspend fun get(): HouseholdEntity?

    @Query("SELECT * FROM household WHERE id = 1")
    fun observe(): Flow<HouseholdEntity?>

    @Upsert
    suspend fun upsert(household: HouseholdEntity)

    @Query("DELETE FROM household")
    suspend fun clear()

    @Query("SELECT * FROM household_device ORDER BY joinedAtEpochMs")
    suspend fun devices(): List<HouseholdDeviceEntity>

    @Query("SELECT * FROM household_device ORDER BY joinedAtEpochMs")
    fun observeDevices(): Flow<List<HouseholdDeviceEntity>>

    @Transaction
    suspend fun replaceDevices(devices: List<HouseholdDeviceEntity>) {
        clearDevices()
        insertDevices(devices)
    }

    @Query("DELETE FROM household_device")
    suspend fun clearDevices()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevices(devices: List<HouseholdDeviceEntity>)

    @Query("SELECT * FROM household_server")
    suspend fun servers(): List<HouseholdServerEntity>

    @Transaction
    suspend fun replaceServers(servers: List<HouseholdServerEntity>) {
        clearServers()
        insertServers(servers)
    }

    @Query("DELETE FROM household_server")
    suspend fun clearServers()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<HouseholdServerEntity>)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: SyncStateEntity)
}
