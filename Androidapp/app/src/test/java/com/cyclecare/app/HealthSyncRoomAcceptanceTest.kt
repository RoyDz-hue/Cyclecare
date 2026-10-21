package com.cyclecare.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Plain temporary JVM databases test Room migrations, not production SQLCipher/Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthSyncRoomAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private fun open(name: String): CycleCareDatabase {
        context.getDatabasePath(name).parentFile?.mkdirs()
        return Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().addMigrations(CycleCareDatabase.createMigration5To6(), CycleCareDatabase.createMigration6To7(), CycleCareDatabase.createMigration7To8())
            .build().also { handles += it }
    }
    private fun name() = "room-sync-unit-${UUID.randomUUID()}".also { names += it }
    private fun entry(owner: String = "owner-a", revision: Long = 1) = HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(),
        "2026-10-19", "UPSERT", 0, revision, "{\"date\":\"2026-10-19\"}", "a".repeat(64), 7)
    @After fun cleanup() { handles.forEach { if (it.isOpen) it.close() }; names.forEach { context.deleteDatabase(it) } }

    @Test fun fiveToSevenMigrationPreservesHealthRowsAndValidatesActualIndexes() = runBlocking {
        val file = name(); val initial = open(file)
        initial.cycleDao().insertOrUpdateLog(CycleDayLog("2026-10-19", notes = "migration fixture"))
        initial.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            // Base health tables are unchanged from v5. Remove only the newly introduced tables.
            listOf("health_sync_conflicts", "health_sync_outbox", "health_sync_revisions", "health_sync_cursors")
                .forEach { old.execSQL("DROP TABLE $it") }
            old.execSQL("PRAGMA user_version=5")
            old.execSQL("UPDATE room_master_table SET identity_hash='v5-migration-fixture'")
        }
        val migrated = open(file)
        assertEquals("migration fixture", migrated.cycleDao().getLogByDate("2026-10-19")?.notes)
        assertEquals(8, migrated.openHelper.writableDatabase.version)
        assertTrue(migrated.healthSyncDao().pending("owner-a").isEmpty())
        assertTrue(migrated.healthSyncDao().conflicts("owner-a").isEmpty())
    }
    @Test fun restartPreservesOutboxAndWrongOwnerOrRevisionCannotAcknowledgeIt() = runBlocking {
        val file = name(); val first = open(file); val a = entry(); val b = entry("owner-b")
        first.healthSyncDao().enqueue(a); first.healthSyncDao().enqueue(b); first.close()
        val dao = open(file).healthSyncDao()
        assertEquals(a, dao.pending("owner-a").single())
        dao.acknowledgeMatched("owner-b", a.operationId, a.recordId, a.revision)
        dao.acknowledgeMatched("owner-a", a.operationId, a.recordId, a.revision + 1)
        assertEquals(1, dao.pending("owner-a").size)
        dao.acknowledgeMatched("owner-a", a.operationId, a.recordId, a.revision)
        assertTrue(dao.pending("owner-a").isEmpty()); assertEquals(b, dao.pending("owner-b").single())
    }
    @Test fun failedHealthAndOutboxTransactionLeavesNeitherHalfCommitted() = runBlocking {
        val db = open(name())
        try {
            db.withTransaction {
                db.cycleDao().insertOrUpdateLog(CycleDayLog("2026-10-19", notes = "must roll back"))
                db.healthSyncDao().enqueue(entry())
                error("intentional unit-test rollback")
            }
            fail("transaction did not throw")
        } catch (expected: IllegalStateException) { assertEquals("intentional unit-test rollback", expected.message) }
        assertNull(db.cycleDao().getLogByDate("2026-10-19")); assertTrue(db.healthSyncDao().pending("owner-a").isEmpty())
    }
    @Test fun erasureRemovesConflictPayloadsOnlyForTheRequestedOwner() = runBlocking {
        val db = open(name()); val dao = db.healthSyncDao()
        val a = HealthSyncConflictEntity("owner-a", "2026-10-19", "op-a", 1, "local-private", "UPSERT", 2, "remote-private", false, 1)
        dao.putConflict(a); dao.putConflict(a.copy(ownerId = "owner-b"))
        dao.eraseConflicts("owner-a")
        assertTrue(dao.conflicts("owner-a").isEmpty()); assertEquals(1, dao.conflicts("owner-b").size)
    }
    private suspend fun seedConflict(db: CycleCareDatabase): com.cyclecare.app.data.repository.CycleRepository {
        val log = CycleDayLog("2026-10-19", notes = "local reviewed value")
        val payload = HealthSyncPayloadCodec.logPayload(log); val remote = HealthSyncPayloadCodec.logPayload(log.copy(notes = "remote reviewed value"))
        val op = entry(revision = 10).copy(baseRevision = 1, payloadJson = payload, payloadHash = HealthSyncPayloadCodec.hash(payload))
        db.cycleDao().insertOrUpdateLog(log)
        db.healthSyncDao().putRevision(HealthSyncRevision("owner-a", op.recordId, 10, op.payloadHash, false, 1, 1))
        db.healthSyncDao().enqueue(op)
        db.healthSyncDao().putConflict(HealthSyncConflictEntity("owner-a", op.recordId, op.operationId, 10, payload, "UPSERT", 3, remote, false, 1))
        return com.cyclecare.app.data.repository.CycleRepository(db).apply { activateDatabaseForTest(db, "owner-a") }
    }
    @Test fun keepMineUsesIndependentServerBaseAndMonotonicLocalSequence() = runBlocking {
        val db = open(name()); val repository = seedConflict(db)
        repository.retainLocalConflict("2026-10-19")
        val pending = db.healthSyncDao().pending("owner-a").single()
        assertEquals(11L, pending.revision); assertEquals(3L, pending.baseRevision)
        assertEquals(3L, db.healthSyncDao().revision("owner-a", pending.recordId)?.serverRevision)
        assertTrue(db.healthSyncDao().conflicts("owner-a").isEmpty())
    }
    @Test fun staleReviewCannotOverwriteOrRemoveANewerLocalEdit() = runBlocking {
        val db = open(name()); val repository = seedConflict(db)
        repository.saveLog(CycleDayLog("2026-10-19", notes = "newer unreviewed edit"))
        repository.acceptRemoteConflict("2026-10-19"); repository.retainLocalConflict("2026-10-19")
        assertEquals("newer unreviewed edit", db.cycleDao().getLogByDate("2026-10-19")?.notes)
        assertEquals(2, db.healthSyncDao().pending("owner-a").size)
        assertEquals(1, db.healthSyncDao().conflicts("owner-a").size)
    }
    @Test fun reviewedRemoteValueClearsOnlyReviewedPendingMutations() = runBlocking {
        val db = open(name()); val repository = seedConflict(db)
        repository.acceptRemoteConflict("2026-10-19")
        assertEquals("remote reviewed value", db.cycleDao().getLogByDate("2026-10-19")?.notes)
        assertEquals(11L, db.healthSyncDao().revision("owner-a", "2026-10-19")?.revision)
        assertEquals(3L, db.healthSyncDao().revision("owner-a", "2026-10-19")?.serverRevision)
        assertTrue(db.healthSyncDao().pending("owner-a").isEmpty())
    }
    @Test fun acceptingRemoteSettingsDeletionCannotDisableTheDeviceLock() = runBlocking {
        val db = open(name()); val dao = db.healthSyncDao()
        val settings = CycleSettings(isAppLockEnabled = true, lockTimeoutMinutes = 0, averageCycleLength = 30)
        val payload = HealthSyncPayloadCodec.settingsPayload(settings)
        db.cycleDao().saveSettings(settings)
        dao.putRevision(HealthSyncRevision("owner-a", "settings", 10, HealthSyncPayloadCodec.hash(payload), false, 1, 1))
        dao.putConflict(HealthSyncConflictEntity("owner-a", "settings", "op", 10, payload, "UPSERT", 2, null, true, 1))
        val repository = com.cyclecare.app.data.repository.CycleRepository(db).apply { activateDatabaseForTest(db, "owner-a") }
        assertTrue(runCatching { repository.retainLocalConflict("settings") }.isFailure)
        repository.acceptRemoteConflict("settings")
        assertTrue(db.cycleDao().getSettings()!!.isAppLockEnabled)
        assertEquals(28, db.cycleDao().getSettings()!!.averageCycleLength)
    }
    @Test fun repositoryWipeRemovesAllOwnerReplayPayloadsInOneTransaction() = runBlocking {
        val db = open(name()); val repository = seedConflict(db); val dao = db.healthSyncDao()
        val other = dao.conflicts("owner-a").single().copy(ownerId = "owner-b")
        dao.putConflict(other); dao.putCursor(HealthSyncCursor("owner-a", 42))
        repository.deleteAllLogs()
        assertTrue(db.cycleDao().getAllLogsList().isEmpty()); assertNull(db.onboardingDao().getProfile())
        assertTrue(dao.pending("owner-a").isEmpty()); assertTrue(dao.conflicts("owner-a").isEmpty())
        assertNull(dao.cursor("owner-a")); assertNull(dao.revision("owner-a", "2026-10-19"))
        assertEquals(other, dao.conflicts("owner-b").single())
    }
}
