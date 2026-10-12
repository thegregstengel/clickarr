package net.clickarr

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.clickarr.core.database.ClickarrDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades databases written by released builds to the current schema and checks nothing is lost. The schema of
 * every version is committed under core/database/schemas (exported by Room, or by schemas.yml for versions that
 * shipped before export was on). A new schema version needs a migration here and a case below.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClickarrDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val migrations = arrayOf(ClickarrDatabase.MIGRATION_4_5, ClickarrDatabase.MIGRATION_5_6)

    @Test
    fun releaseSchema4KeepsServerAndChannelsThroughToCurrent() {
        helper.createDatabase(DB, 4).use { db ->
            db.insert("provider_connection", SQLiteDatabase.CONFLICT_FAIL, server())
            db.insert("channel", SQLiteDatabase.CONFLICT_FAIL, channel("c1", 10))
            db.insert("channel", SQLiteDatabase.CONFLICT_FAIL, channel("c2", 11))
        }
        helper.runMigrationsAndValidate(DB, CURRENT, true, *migrations).use { db ->
            db.query("SELECT COUNT(*) FROM provider_connection").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.query("SELECT number, name, season FROM channel ORDER BY number").use {
                it.moveToFirst()
                assertEquals(10, it.getInt(0))
                assertEquals("Channel c1", it.getString(1))
                assertNull(it.getString(2))
                it.moveToNext()
                assertEquals(11, it.getInt(0))
            }
        }
    }

    @Test
    fun schema6LetsTwoHolidayChannelsShareANumber() {
        helper.createDatabase(DB2, 5).use { db ->
            db.insert("channel", SQLiteDatabase.CONFLICT_FAIL, channel("xmas", 100).apply { put("season", "CHRISTMAS") })
        }
        helper.runMigrationsAndValidate(DB2, CURRENT, true, ClickarrDatabase.MIGRATION_5_6).use { db ->
            db.insert("channel", SQLiteDatabase.CONFLICT_FAIL, channel("spooky", 100).apply { put("season", "HALLOWEEN") })
            db.query("SELECT COUNT(*) FROM channel WHERE number = 100").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        }
    }

    private fun server() = ContentValues().apply {
        put("id", "plex-1")
        put("kind", "PLEX")
        put("serverIdentity", "abc123machine")
        put("name", "Media Room")
        put("baseUrl", "http://192.168.1.20:32400")
        put("altUrls", "[]")
    }

    private fun channel(id: String, number: Int) = ContentValues().apply {
        put("id", id)
        put("number", number)
        put("name", "Channel $id")
        put("source", """{"type":"explicit","items":[]}""")
        put("orderMode", "SEQUENTIAL")
        put("seed", 1L)
        put("lineupId", "lineup-$id")
        put("anchorEpochMs", 0L)
        put("enabled", 1)
        put("updatedAt", 0L)
    }

    private companion object {
        const val DB = "migration-test.db"
        const val DB2 = "migration-test-2.db"
        const val CURRENT = 6
    }
}
