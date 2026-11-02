package com.cyclecare.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spike S3 evidence: opens the REAL Room database (entities + migrations 4..9,
 * framework SQLite - no encryption, SQLCipher codec is exercised separately)
 * and dumps the exact DDL Room executed (sqlite_master), per-table PRAGMA
 * table_info/index_list, and the identity hash Room wrote. This dump - not
 * any hand transcription - is the schema authority for the Rust fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpikeRoomSchemaDumpTest {

    @Test
    fun dumpEffectiveSchema() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File("build/spike").apply { mkdirs() }
        val dbFile = File(dir, "room-dump.db")
        dbFile.delete()
        val db = Room.databaseBuilder(context, CycleCareDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                CycleCareDatabase.createMigration4To5(context),
                CycleCareDatabase.createMigration5To6(),
                CycleCareDatabase.createMigration6To7(),
                CycleCareDatabase.createMigration7To8(),
                CycleCareDatabase.createMigration8To9()
            )
            .allowMainThreadQueries()
            .build()
        // Force the database open so Room creates tables and writes the hash.
        db.openHelper.writableDatabase.query("SELECT count(*) FROM cycle_day_logs").close()

        val out = StringBuilder()
        val master = db.openHelper.writableDatabase.query(
            "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'android_%' ORDER BY type, name"
        )
        master.use { c ->
            while (c.moveToNext()) {
                out.append("MASTER\t").append(c.getString(0)).append('\t')
                    .append(c.getString(1)).append('\t').append(c.getString(2)).append('\n')
            }
        }
        val hash = db.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table")
        hash.use { c -> if (c.moveToFirst()) out.append("IDENTITY_HASH\t").append(c.getString(0)).append('\n') }

        val tables = listOf(
            "cycle_day_logs", "cycle_settings", "onboarding_profiles",
            "health_sync_revisions", "health_sync_outbox", "health_sync_cursors", "health_sync_conflicts"
        )
        for (table in tables) {
            db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { c ->
                while (c.moveToNext()) {
                    out.append("COL\t").append(table).append('\t')
                        .append(c.getString(1)).append('\t').append(c.getString(2)).append('\t')
                        .append(c.getInt(3)).append('\t').append(c.getString(4) ?: "<null>").append('\t')
                        .append(c.getInt(5)).append('\n')
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA index_list($table)").use { c ->
                while (c.moveToNext()) {
                    out.append("INDEX\t").append(table).append('\t').append(c.getString(1)).append('\n')
                }
            }
        }
        db.close()
        val dumpFile = File(dir, "room-schema-dump.txt")
        dumpFile.writeText(out.toString())
        assertTrue("dump must contain all 7 tables", tables.all { out.contains("MASTER\ttable\t$it\t") })
        println("SCHEMA_DUMP_WRITTEN ${dumpFile.absolutePath}")
    }
}
