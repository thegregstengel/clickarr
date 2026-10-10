package net.clickarr.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProviderConnectionEntity::class,
        LibraryEntity::class,
        MediaItemEntity::class,
        ChannelEntity::class,
        LineupSnapshotEntity::class,
        LineupEntryEntity::class,
        FavoriteEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    // Schema export is enabled in Phase 4 (reproducible builds), together with migration tests.
    exportSchema = false,
)
abstract class ClickarrDatabase : RoomDatabase() {
    abstract fun providerConnections(): ProviderConnectionDao
    abstract fun libraries(): LibraryDao
    abstract fun mediaItems(): MediaItemDao
    abstract fun channels(): ChannelDao
    abstract fun lineups(): LineupDao
    abstract fun favorites(): FavoriteDao
    abstract fun syncState(): SyncStateDao

    companion object {
        const val NAME = "clickarr.db"

        fun build(context: Context, name: String = NAME): ClickarrDatabase =
            Room.databaseBuilder(context.applicationContext, ClickarrDatabase::class.java, name)
                // v1 is pre-release; once real users exist, migrations are mandatory and this goes away.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): ClickarrDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ClickarrDatabase::class.java).build()
    }
}
