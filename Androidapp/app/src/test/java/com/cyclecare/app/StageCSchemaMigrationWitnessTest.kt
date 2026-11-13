package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.HealthSyncConflictEntity
import com.cyclecare.app.data.local.HealthSyncCursor
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
import com.cyclecare.app.data.local.HealthSyncRevision
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-C schema witness for the REGISTERED 9 -> 10 chain
 * (RUST_FLOAT_METADATA_DRAFT in addMigrations + the production onCreate
 * callback running the same applyRustMetadataDraft). Tier: tier-agnostic
 * host DDL/upgrade evidence over plain temporary Room files (Robolectric;
 * no SQLCipher/Keystore). The v9 fixtures are GENUINE v9 files: created by
 * Room itself, then stripped of exactly the draft object set with
 * user_version rewound to 9. That is byte-schema-identical to a historical
 * v9 file because the 9->10 draft adds ONLY non-entity rust_* objects; the
 * identical-entities premise is ASSERTED at runtime (entity DDL equality
 * plus an unchanged Room identity hash), never assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageCSchemaMigrationWitnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private val rawHandles = mutableListOf<FrameworkSQLiteDatabase>()

    /** Mirrors the production builder: registered 9->10 migration AND the
     * onCreate callback running the same draft for fresh files. */
    private fun mirrorBuilder(name: String) =
        Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
            })

    private fun open(name: String = "stage-c-schema-${UUID.randomUUID()}"): CycleCareDatabase {
        names += name
        context.getDatabasePath(name).parentFile?.mkdirs()
        return mirrorBuilder(name).build().also { handles += it }
    }

    @After fun cleanup() {
        handles.forEach { if (it.isOpen) it.close() }
        rawHandles.forEach { if (it.isOpen) it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private fun sql(db: CycleCareDatabase): SupportSQLiteDatabase = db.openHelper.writableDatabase

    /** Raw framework connection over a FILE, bypassing Room entirely: the
     * only way to hold a v9 file open now that Room requires a 9->10 path. */
    private fun raw(name: String): FrameworkSQLiteDatabase =
        FrameworkSQLiteDatabase(android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )).also { rawHandles += it }

    private fun scalar(db: SupportSQLiteDatabase, query: String): Long =
        db.query(query).use { c -> c.moveToNext(); c.getLong(0) }

    private fun strings(db: SupportSQLiteDatabase, query: String): List<String> =
        db.query(query).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList() }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> =
        strings(db, "SELECT name FROM sqlite_master WHERE type='table'").toSet()

    private fun triggerNames(db: SupportSQLiteDatabase): Set<String> =
        strings(db, "SELECT name FROM sqlite_master WHERE type='trigger'").toSet()

    private fun identityHash(db: SupportSQLiteDatabase): String =
        db.query("SELECT identity_hash FROM room_master_table").use { c -> c.moveToNext(); c.getString(0) }

    /** name -> stored sql for every non-rust schema object. */
    private fun entityShape(db: SupportSQLiteDatabase): Map<String, String> =
        db.query("SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'rust_%'").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toMap()
        }

    private val draftTables = listOf(
        "rust_domain_epoch", "rust_enrollments", "rust_enrollment_rows",
        "rust_enrollment_receipts", "rust_float_bits", "rust_commit_receipts",
    )
    private val draftTriggers = listOf(
        "rust_epoch_cycle_day_logs_insert", "rust_epoch_cycle_day_logs_update", "rust_epoch_cycle_day_logs_delete",
        "rust_epoch_cycle_settings_insert", "rust_epoch_cycle_settings_update", "rust_epoch_cycle_settings_delete",
        "rust_epoch_onboarding_profiles_insert", "rust_epoch_onboarding_profiles_update", "rust_epoch_onboarding_profiles_delete",
        "rust_float_update", "rust_float_delete", "rust_float_insert",
    )

    /** Creates a GENUINE v9 file: a real Room-created file with the draft
     * objects stripped and user_version rewound to 9. Asserts the premise
     * that makes it genuine: entity DDL identical to a fresh v10 file and
     * the SAME Room identity hash (Room fingerprints entities, not the
     * version). Returns the identity hash the upgrade must preserve. */
    private fun createGenuineV9File(name: String, populate: Boolean = false): String = runBlocking {
        names += name
        context.getDatabasePath(name).parentFile?.mkdirs()
        val built = mirrorBuilder(name).build()
        val s = sql(built)
        val hash = identityHash(s)
        val freshShape = entityShape(s)
        if (populate) populateV9(built)
        built.close()
        val r = raw(name)
        for (t in draftTriggers) r.execSQL("DROP TRIGGER IF EXISTS $t")
        for (t in draftTables) r.execSQL("DROP TABLE IF EXISTS $t")
        r.execSQL("PRAGMA user_version=9")
        // Premise asserted, not assumed: after removing exactly the draft
        // object set, the remaining schema is byte-identical to what the
        // same Room schema bundle created WITH the draft objects present.
        assertEquals("entity DDL identical between v9 fixture and v10 create", freshShape, entityShape(r))
        assertEquals("identity hash survives the strip untouched", hash, identityHash(r))
        assertEquals(9L, scalar(r, "PRAGMA user_version"))
        assertTrue(tableNames(r).none { it.startsWith("rust_") })
        assertTrue(triggerNames(r).none { it.startsWith("rust_") })
        r.close(); rawHandles.remove(r)
        hash
    }

    /** Marker rows in every v9 entity table, written through the real DAOs. */
    private fun populateV9(db: CycleCareDatabase) = runBlocking {
        db.cycleDao().insertOrUpdateLog(CycleDayLog(dateString = "2026-10-01", flowLevel = "LIGHT", bbtCelsius = 36.5f, sleepHours = 7.5f, notes = "marker-note", painIntensity = 2))
        db.cycleDao().insertOrUpdateLog(CycleDayLog(dateString = "2026-10-02", flowLevel = "MEDIUM"))
        db.cycleDao().saveSettings(CycleSettings(id = 1, isOnboardingCompleted = true, lastPeriodStartDate = "2026-09-28", averageCycleLength = 31, periodDuration = 6))
        db.onboardingDao().insertOrUpdateProfile(OnboardingProfile(id = 1, isCompleted = true, accountMode = "PRIVATE", lastPeriodDateString = "2026-09-28", lastPeriodEndDateString = "2026-10-02", cycleLengthDays = 31))
        db.healthSyncDao().putRevision(HealthSyncRevision(ownerId = "owner-a", recordId = "rec-1", revision = 3L, payloadHash = "hash-1", tombstone = false, updatedAt = 111L, serverRevision = 2L))
        db.healthSyncDao().enqueue(HealthSyncOutboxEntry(ownerId = "owner-a", operationId = "op-1", recordId = "rec-1", kind = "UPSERT", baseRevision = 2L, revision = 3L, payloadJson = "{}", payloadHash = "hash-1", consentGeneration = 1L, attempts = 1, nextAttemptAt = 0L, lastError = null, serverReceipt = "rcpt-1", serverRevision = 7L))
        db.healthSyncDao().putConflict(HealthSyncConflictEntity(ownerId = "owner-a", recordId = "rec-2", operationId = "op-2", localRevision = 4L, localPayloadJson = "{}", localKind = "UPSERT", remoteRevision = 5L, remotePayloadJson = "{}", remoteTombstone = false, createdAt = 222L))
        db.healthSyncDao().putCursor(HealthSyncCursor(ownerId = "owner-a", changeSeq = 42L))
    }

    private fun dumpTable(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("SELECT * FROM $table").use { c ->
            generateSequence {
                if (c.moveToNext()) (0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "<null>" else c.getString(i) } else null
            }.toList()
        }

    private fun v9Snapshot(db: SupportSQLiteDatabase): Map<String, List<String>> =
        listOf("cycle_day_logs", "cycle_settings", "onboarding_profiles", "health_sync_revisions",
            "health_sync_outbox", "health_sync_conflicts", "health_sync_cursors")
            .associateWith { dumpTable(db, it) }

    private fun migrateInTransaction(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            RUST_FLOAT_METADATA_DRAFT.migrate(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private val expectedTables = mapOf(
        "rust_domain_epoch" to listOf("id", "value"),
        "rust_enrollments" to listOf("namespace", "operation", "binding", "epoch", "state", "nextRecord", "recordCount", "nextSend"),
        "rust_enrollment_rows" to listOf("namespace", "operation", "record", "payload", "physical", "revision", "serverRevision", "child", "attempts", "nextAttempt"),
        "rust_enrollment_receipts" to listOf("namespace", "operation", "record", "child", "serverRevision", "receiptHash", "adopted"),
        "rust_float_bits" to listOf("dateString", "normalizedHash", "bbtBits", "sleepBits"),
        "rust_commit_receipts" to listOf("namespace", "operationId", "intentDigest", "version", "resultJson"),
    )
    private val expectedTriggers = setOf(
        "rust_epoch_cycle_day_logs_insert", "rust_epoch_cycle_day_logs_update", "rust_epoch_cycle_day_logs_delete",
        "rust_epoch_cycle_settings_insert", "rust_epoch_cycle_settings_update", "rust_epoch_cycle_settings_delete",
        "rust_epoch_onboarding_profiles_insert", "rust_epoch_onboarding_profiles_update", "rust_epoch_onboarding_profiles_delete",
        "rust_float_update", "rust_float_delete", "rust_float_insert",
    )

    @Test fun genuineV9FileUpgradesThroughRegisteredPathWithRowsAndIdentityHashPreserved() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        val hash = createGenuineV9File(name, populate = true)
        val before = raw(name).let { r -> v9Snapshot(r).also { r.close(); rawHandles.remove(r) } }
        // Room's own machinery: user_version 9 < 10 runs the REGISTERED
        // migration inside the open, then validates against the same
        // identity hash (identical entities make the hash version-stable).
        val db = open(name)
        val s = sql(db)
        assertEquals("registered path bumped user_version", 10L, scalar(s, "PRAGMA user_version"))
        assertEquals("identity hash unchanged across the upgrade", hash, identityHash(s))
        assertEquals("every v9 row survived the registered upgrade", before, v9Snapshot(s))
        for ((table, cols) in expectedTables) assertEquals("shape of $table", cols, columns(s, table))
        assertTrue("all 12 triggers present", triggerNames(s).containsAll(expectedTriggers))
        assertEquals(1L, scalar(s, "SELECT COUNT(*) FROM rust_domain_epoch"))
    }

    @Test fun freshCreateRunsTheSameDraftThroughTheProductionCallback() {
        val db = open()
        val s = sql(db)
        assertEquals(10L, scalar(s, "PRAGMA user_version"))
        for ((table, cols) in expectedTables) assertEquals("shape of $table", cols, columns(s, table))
        assertTrue("all 12 triggers present", triggerNames(s).containsAll(expectedTriggers))
    }

    @Test fun unregisteredOpenOfGenuineV9FileFailsClosed() {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name)
        // Without the registered migration Room refuses the open: the draft
        // can never be skipped silently by a misconfigured builder.
        val bare = Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it }
        val thrown = try { bare.openHelper.writableDatabase; null } catch (t: Throwable) { t }
        assertNotNull("Room must refuse a 9->10 open with no registered migration", thrown)
    }

    @Test fun populatedV9RowsSurviveAndMetadataShapeIsExactAcrossReopen() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name, populate = true)
        val r = raw(name)
        val before = v9Snapshot(r)
        migrateInTransaction(r)
        // Preservation is observed, not argued: full-row dumps of every
        // populated v9 table are identical after the migration ran.
        assertEquals(before, v9Snapshot(r))
        for ((table, cols) in expectedTables) assertEquals("shape of $table", cols, columns(r, table))
        assertTrue("all 12 triggers present", triggerNames(r).containsAll(expectedTriggers))
        // Manual migrate() does not bump user_version; the registered
        // Room-open path owns that bump.
        assertEquals(9L, scalar(r, "PRAGMA user_version"))
        // sqlite_master stores the CREATE statement with IF NOT EXISTS
        // stripped (observed SQLite normalization); compare the stored form.
        val triggerSql = strings(r, "SELECT sql FROM sqlite_master WHERE type='trigger' AND name='rust_float_insert'").single()
        assertEquals("CREATE TRIGGER rust_float_insert AFTER INSERT ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=NEW.dateString; END", triggerSql)
        // Durability across close/reopen of the same file.
        r.close(); rawHandles.remove(r)
        val reopened = raw(name)
        assertEquals(before, v9Snapshot(reopened))
        for ((table, cols) in expectedTables) assertEquals("shape of $table after reopen", cols, columns(reopened, table))
    }

    @Test fun triggersInvalidateFloatBitsOnInsertUpdateDateKeyChangeAndDelete() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name, populate = true)
        val r = raw(name)
        migrateInTransaction(r)
        // Complete what the registered path would do so Room can reopen the
        // file for real-DAO write witnesses below.
        r.execSQL("PRAGMA user_version=10")
        r.close(); rawHandles.remove(r)
        val db = Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it }
        val s = sql(db)
        fun seedBits(date: String) = s.execSQL("INSERT OR REPLACE INTO rust_float_bits VALUES('$date','h',1,2)")
        fun bits(date: String) = scalar(s, "SELECT COUNT(*) FROM rust_float_bits WHERE dateString='$date'")

        seedBits("2026-11-01"); seedBits("2026-11-02")
        db.cycleDao().insertOrUpdateLog(CycleDayLog(dateString = "2026-11-01", flowLevel = "LIGHT"))
        assertEquals("insert invalidates", 0L, bits("2026-11-01"))
        assertEquals("unrelated date untouched", 1L, bits("2026-11-02"))

        seedBits("2026-11-01")
        db.cycleDao().insertOrUpdateLog(CycleDayLog(dateString = "2026-11-01", flowLevel = "HEAVY"))
        assertEquals("update invalidates", 0L, bits("2026-11-01"))

        seedBits("2026-11-01"); seedBits("2026-11-03")
        s.execSQL("UPDATE cycle_day_logs SET dateString='2026-11-03' WHERE dateString='2026-11-01'")
        assertEquals("date-key change invalidates old key", 0L, bits("2026-11-01"))
        assertEquals("date-key change invalidates new key", 0L, bits("2026-11-03"))

        // AFTER DELETE is a per-row trigger: the target domain row must
        // actually exist. 2026-11-03 does (renamed from 2026-11-01 above).
        assertEquals("delete target row exists", 1L, scalar(s, "SELECT COUNT(*) FROM cycle_day_logs WHERE dateString='2026-11-03'"))
        seedBits("2026-11-03")
        s.execSQL("DELETE FROM cycle_day_logs WHERE dateString='2026-11-03'")
        assertEquals("delete invalidates", 0L, bits("2026-11-03"))
        assertEquals("delete target row gone", 0L, scalar(s, "SELECT COUNT(*) FROM cycle_day_logs WHERE dateString='2026-11-03'"))
        // Zero-row control: no deleted row, no trigger fire, cache untouched.
        seedBits("2026-11-02")
        s.execSQL("DELETE FROM cycle_day_logs WHERE dateString='2026-11-02'")
        assertEquals("zero-row delete leaves bits untouched", 1L, bits("2026-11-02"))
        assertEquals("epoch counter advanced by writes", true, scalar(s, "SELECT value FROM rust_domain_epoch WHERE id=1") > 0L)
    }

    @Test fun rolledBackMigrationLeavesNoTraceAndRerunConverges() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name, populate = true)
        val r = raw(name)
        val before = v9Snapshot(r)
        // Simulated crash: migration transaction ends without success.
        r.beginTransaction()
        RUST_FLOAT_METADATA_DRAFT.migrate(r)
        r.endTransaction()
        assertEquals(before, v9Snapshot(r))
        assertTrue("no rust table persists after rollback", tableNames(r).none { it.startsWith("rust_") })
        assertTrue("no rust trigger persists after rollback", triggerNames(r).none { it.startsWith("rust_") })
        // Convergent rerun: applies cleanly, then a second application is a
        // verified no-op (IF NOT EXISTS + exact-shape check both hold).
        migrateInTransaction(r)
        migrateInTransaction(r)
        assertEquals(before, v9Snapshot(r))
        for ((table, cols) in expectedTables) assertEquals(cols, columns(r, table))
        assertEquals(1L, scalar(r, "SELECT COUNT(*) FROM rust_domain_epoch"))
    }

    @Test fun preexistingWrongKeyMetadataFailsClosed() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name, populate = true)
        val r = raw(name)
        val before = v9Snapshot(r)
        // Same column names, wrong key: dateString is not the PRIMARY KEY.
        r.execSQL("CREATE TABLE rust_float_bits(dateString TEXT NOT NULL,normalizedHash TEXT NOT NULL,bbtBits INTEGER NOT NULL,sleepBits INTEGER NOT NULL)")
        val thrown = try { migrateInTransaction(r); null } catch (t: Throwable) { t }
        assertTrue("wrong-key collision rejected, got $thrown", thrown is IllegalStateException && (thrown.message ?: "").contains("rust_metadata_shape_mismatch"))
        assertEquals(before, v9Snapshot(r))
    }

    @Test fun preexistingWrongBodyTriggerFailsClosed() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name, populate = true)
        val r = raw(name)
        val before = v9Snapshot(r)
        // Same trigger name, different body.
        r.execSQL("CREATE TRIGGER rust_float_insert AFTER INSERT ON cycle_day_logs BEGIN SELECT 1; END")
        val thrown = try { migrateInTransaction(r); null } catch (t: Throwable) { t }
        assertTrue("wrong-body trigger rejected, got $thrown", thrown is IllegalStateException && (thrown.message ?: "").contains("rust_metadata_trigger_mismatch"))
        assertEquals(before, v9Snapshot(r))
    }

    @Test fun preexistingIncompatibleMetadataFailsClosedWithDataUntouched() = runBlocking {
        val name = "stage-c-schema-${UUID.randomUUID()}"
        createGenuineV9File(name, populate = true)
        val r = raw(name)
        val before = v9Snapshot(r)
        // Only fixtures/corruption can pre-create these names: no production
        // code writes rust_* tables (verified by source grep).
        r.execSQL("CREATE TABLE rust_float_bits(dateString TEXT NOT NULL PRIMARY KEY,normalizedHash TEXT NOT NULL)")
        val thrown = try {
            migrateInTransaction(r); null
        } catch (t: Throwable) { t }
        assertTrue("migration rejects incompatible preexisting metadata, got $thrown", thrown is IllegalStateException && (thrown.message ?: "").contains("rust_metadata_shape_mismatch"))
        assertEquals("v9 rows untouched by rejected migration", before, v9Snapshot(r))
        assertEquals("only the preexisting table remains", setOf("rust_float_bits"), tableNames(r).filter { it.startsWith("rust_") }.toSet())
        assertTrue(triggerNames(r).none { it.startsWith("rust_") })
    }
}
