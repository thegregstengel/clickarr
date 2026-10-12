package net.clickarr.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        HouseholdEntity::class,
        HouseholdDeviceEntity::class,
        HouseholdServerEntity::class,
    ],
    version = 6,
    // Exported to core/database/schemas; MigrationTest upgrades each committed version to the current one.
    exportSchema = true,
)
abstract class ClickarrDatabase : RoomDatabase() {
    abstract fun providerConnections(): ProviderConnectionDao
    abstract fun libraries(): LibraryDao
    abstract fun mediaItems(): MediaItemDao
    abstract fun channels(): ChannelDao
    abstract fun lineups(): LineupDao
    abstract fun favorites(): FavoriteDao
    abstract fun household(): HouseholdDao
    abstract fun syncState(): SyncStateDao

    companion object {
        const val NAME = "clickarr.db"

        fun build(context: Context, name: String = NAME): ClickarrDatabase =
            Room.databaseBuilder(context.applicationContext, ClickarrDatabase::class.java, name)
                // Released installs are at 4 or later and keep their data through real migrations from here on.
                // Only pre-release schemas (1 to 3) are rebuilt from scratch.
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
                .build()

        /** Schema 5: a channel can belong to a holiday (the column is named season) (SeasonWindow), shown only between those dates. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channel ADD COLUMN season TEXT")
            }
        }

        /** Schema 6: channel numbers are no longer unique, so holiday channels can share one. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_channel_number")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_number ON channel(number)")
            }
        }

        fun inMemory(context: Context): ClickarrDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ClickarrDatabase::class.java).build()
    }
}
