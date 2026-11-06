package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
import com.cyclecare.app.data.local.HealthSyncRevision
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.HealthSyncV3Transport
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.V3WireChange
import com.cyclecare.app.data.supabase.V3WireOperation
import com.cyclecare.app.data.supabase.V3WireReceipt
import com.cyclecare.app.security.AuthPreferences
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B1 loop integration: the outbox/pull attempt BODIES driven with a scripted
 * in-process transport. Tier: tier-agnostic host routing evidence over plain
 * temporary Room DBs (Robolectric; no SQLCipher/Keystore, no admission, no
 * network). The release gate stays FALSE: the public entries are not exercised
 * here and no end-to-end sync, delivery guarantee, backoff guarantee or
 * zero-commit claim is made. Worker/catch behavior outside these bodies is
 * unchanged. All "zero" claims are scoped to the named repository DB ROWS.
 * Cancellation after a synchronous commit may lose the waiter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncApplyLoopBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private val owner = "owner-a"
    private val server = 5L

    private fun open(): CycleCareDatabase {
        val name = "b1l-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        return Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it; applyDraftMetadata(it) }
    }

    /** Applies the UNREGISTERED RUST_FLOAT_METADATA_DRAFT DDL by hand (fixture
     * only; registers and validates nothing). */
    private fun applyDraftMetadata(db: CycleCareDatabase) {
        val sql = db.openHelper.writableDatabase
        com.cyclecare.app.data.local.rust.RustEnrollmentStage.schema(sql)
        com.cyclecare.app.data.local.rust.RustEnrollmentStage.senderSchema(sql)
        sql.execSQL("CREATE TABLE IF NOT EXISTS rust_float_bits(dateString TEXT NOT NULL PRIMARY KEY,normalizedHash TEXT NOT NULL,bbtBits INTEGER NOT NULL,sleepBits INTEGER NOT NULL)")
        sql.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_update AFTER UPDATE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString OR dateString=NEW.dateString; END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_delete AFTER DELETE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString; END")
        sql.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_insert AFTER INSERT ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=NEW.dateString; END")
    }

    @After fun cleanup() { handles.forEach { if (it.isOpen) it.close() }; names.forEach { context.deleteDatabase(it) } }

    init { com.cyclecare.app.data.local.rust.RustTrackNative.warmUp() }

    private fun syncPrefs(): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("b1l-${UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply {
            userId = owner
            isCloudSyncAllowed = true
            check(saveServerHealthConsentReceipt(owner, sessionGeneration, healthConsentGeneration, server, true)) { "receipt_fixture_failed" }
        }

    private data class Bound(val repo: CycleRepository, val host: RustBoundRoomHost, val port: RustRepositoryPort, val db: CycleCareDatabase, val prefs: AuthPreferences, val service: SupabaseSyncService)

    private fun bound(p: AuthPreferences = syncPrefs()): Bound {
        val db = open()
        val host = RustBoundRoomHost(p)
        val token = (host.publish(null, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val port = RustRepositoryPort(host, token)
        val repo = CycleRepository(db, port)
        val service = SupabaseSyncService(db, p)
        service.bindSyncApplyRepository(repo)
        return Bound(repo, host, port, db, p, service)
    }

    private fun identity(p: AuthPreferences) = p.syncIdentitySnapshot()

    private fun capture(b: Bound): CycleRepository.SyncApplyOrigin {
        val id = identity(b.prefs)
        return b.service.captureSyncApplyBinding(b.db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)!!
    }

    private fun seedPending(b: Bound, record: String): HealthSyncOutboxEntry {
        val entry = HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), record, "UPSERT", 0L, 1L, "{}", HealthSyncPayloadCodec.hash("{}"), identity(b.prefs).localConsentGeneration)
        runBlocking {
            b.db.healthSyncDao().putRevision(HealthSyncRevision(owner, record, 1L, entry.payloadHash, false, 1L, 0L))
            b.db.healthSyncDao().enqueue(entry)
        }
        return entry
    }

    private fun wireId(logical: String) = UUID.nameUUIDFromBytes("cyclecare-v3:$owner:$logical".toByteArray(Charsets.UTF_8)).toString()

    private fun validAck(op: V3WireOperation) = V3WireReceipt("applied", op.baseRevision + 1, null, "a".repeat(64), null, op.recordId, op.operationId, op.logicalRecordId)

    private class FakeTransport(
        val onApply: suspend (V3WireOperation) -> V3WireReceipt = { error("no apply scripted") },
        val onReadChanges: suspend (Long) -> List<V3WireChange> = { emptyList() },
    ) : HealthSyncV3Transport {
        override suspend fun apply(operation: V3WireOperation, accessToken: String) = onApply(operation)
        override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String) = onReadChanges(afterSeq)
    }

    private inline fun <reified T : Throwable> expectThrows(crossinline block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("expected ${T::class.java.simpleName}, got ${t.javaClass.simpleName}: ${t.message}")
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing thrown")
    }

    @Test fun ackRoundAppliesActualRows() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val applied = b.service.executeV3OutboxAttempt(FakeTransport(onApply = { validAck(it) }), "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b))
        assertEquals(1, applied)
        val dao = b.db.healthSyncDao()
        assertEquals(1L, dao.revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(dao.pending(owner).none { it.operationId == entry.operationId })
    }

    @Test fun legacyPathUnchangedWhenNothingSelected() = runBlocking(Dispatchers.IO) {
        val db = open()
        val prefs = syncPrefs()
        val service = SupabaseSyncService(db, prefs)
        val entry = HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), "2026-10-07", "UPSERT", 0L, 1L, "{}", HealthSyncPayloadCodec.hash("{}"), identity(prefs).localConsentGeneration)
        db.healthSyncDao().putRevision(HealthSyncRevision(owner, "2026-10-07", 1L, entry.payloadHash, false, 1L, 0L))
        db.healthSyncDao().enqueue(entry)
        val id = identity(prefs)
        val origin = service.captureSyncApplyBinding(db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        assertNull(origin)
        val applied = service.executeV3OutboxAttempt(FakeTransport(onApply = { validAck(it) }), "tok", owner, id.session, id.localConsentGeneration, server, db, null)
        assertEquals(1, applied)
        assertEquals(1L, db.healthSyncDao().revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(db.healthSyncDao().pending(owner).isEmpty())
    }

    @Test fun selectedServicePortWithoutBindingFailsClosed() {
        val db = open()
        val prefs = syncPrefs()
        val host = RustBoundRoomHost(prefs)
        val token = (host.publish(null, db, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val service = SupabaseSyncService(db, prefs)
        service.attachRustPort(RustRepositoryPort(host, token))
        val id = identity(prefs)
        try {
            service.captureSyncApplyBinding(db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        } catch (e: IllegalStateException) {
            assertEquals("sync_apply_requires_repo_binding", e.message)
            return
        }
        throw AssertionError("expected repo-binding requirement")
    }

    @Test fun boundRepoWithoutPortPlusServiceVarRejects() {
        val b = bound()
        val id = identity(b.prefs)
        try {
            SupabaseSyncService(b.db, b.prefs).apply {
                attachRustPort(b.port)
                bindSyncApplyRepository(CycleRepository(b.db)) // bound repo WITHOUT a rust port
            }.captureSyncApplyBinding(b.db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        } catch (e: IllegalStateException) {
            assertEquals("sync_apply_selection_inconsistent", e.message)
            return
        }
        throw AssertionError("expected selection-inconsistency rejection")
    }

    @Test fun separateHostDuringAwaitEscapesStaleWithZeroRows() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val fake = FakeTransport(onApply = { op ->
            val replacementDb = open()
            val replacementHost = RustBoundRoomHost(b.prefs)
            val replacementToken = (replacementHost.publish(null, replacementDb, b.prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            b.repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
            validAck(op)
        })
        expectThrows<CycleRepository.SyncApplyStaleException> {
            runBlocking(Dispatchers.IO) { b.service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b)) }
        }
        // Row-scoped zero: the stale ACK applied nothing and scheduled no retry.
        val dao = b.db.healthSyncDao()
        assertEquals(0L, dao.revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(dao.pending(owner).any { it.operationId == entry.operationId && it.attempts == 0 && it.nextAttemptAt == 0L })
    }

    @Test fun staleRetryEscapesCatchWithoutRetryRow() {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val fake = FakeTransport(onApply = {
            val replacementDb = open()
            val replacementHost = RustBoundRoomHost(b.prefs)
            val replacementToken = (replacementHost.publish(null, replacementDb, b.prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            b.repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
            throw java.io.IOException("boom")
        })
        expectThrows<CycleRepository.SyncApplyStaleException> {
            runBlocking(Dispatchers.IO) { b.service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b)) }
        }
        // Row-scoped zero: the retry was never scheduled against a stale attempt.
        val row = runBlocking { b.db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId } }
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt)
    }

    @Test fun pullStaleStopsWithoutCursorOrCount() {
        val b = bound()
        val payload = JSONObject().put("kind", "UPSERT").put("logicalRecordId", "2026-10-07").put("value", JSONObject(HealthSyncPayloadCodec.logPayload(CycleDayLog(dateString = "2026-10-07", moodLevel = "calm"))))
        val change = V3WireChange(wireId("2026-10-07"), 3L, 1L, payload, false, null, "2026-10-07")
        val id = identity(b.prefs)
        val fake = FakeTransport(onReadChanges = {
            val replacementDb = open()
            val replacementHost = RustBoundRoomHost(b.prefs)
            val replacementToken = (replacementHost.publish(null, replacementDb, b.prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            b.repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
            listOf(change)
        })
        expectThrows<CycleRepository.SyncApplyStaleException> {
            runBlocking(Dispatchers.IO) { b.service.pullAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b)) }
        }
        // Row-scoped zero: no cursor write, no applied row.
        val dao = b.db.healthSyncDao()
        assertNull(runBlocking { dao.cursor(owner) })
        assertNull(runBlocking { b.db.cycleDao().getLogByDate("2026-10-07") })
    }

    @Test fun appliedZeroPageAdvancesCursor() = runBlocking(Dispatchers.IO) {
        val b = bound()
        b.db.healthSyncDao().putRevision(HealthSyncRevision(owner, "2026-10-07", 4L, "h", false, 1L, 3L))
        val payload = JSONObject().put("kind", "UPSERT").put("logicalRecordId", "2026-10-07").put("value", JSONObject(HealthSyncPayloadCodec.logPayload(CycleDayLog(dateString = "2026-10-07", moodLevel = "calm"))))
        val change = V3WireChange(wireId("2026-10-07"), 1L, 1L, payload, false, null, "2026-10-07")
        val id = identity(b.prefs)
        val count = b.service.pullAttempt(FakeTransport(onReadChanges = { listOf(change) }), "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b))
        assertEquals(0, count)
        assertEquals(1L, b.db.healthSyncDao().cursor(owner)?.changeSeq)
        assertEquals(3L, b.db.healthSyncDao().revision(owner, "2026-10-07")!!.serverRevision)
    }

    @Test fun noChangeAckIsNotCounted() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val fake = FakeTransport(onApply = { op ->
            b.db.healthSyncDao().acknowledgeMatched(owner, op.operationId, "2026-10-07", 1L)
            validAck(op)
        })
        val applied = b.service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b))
        assertEquals(0, applied)
        assertTrue(b.db.healthSyncDao().pending(owner).none { it.operationId == entry.operationId })
    }

    @Test fun cancelCheckpointAfterNonCooperativeReturn() = runBlocking {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val fake = FakeTransport(onApply = { op ->
            kotlin.coroutines.coroutineContext[Job]?.cancel()
            validAck(op)
        })
        val result = runCatching {
            async(Dispatchers.IO) {
                b.service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b))
            }.await()
        }
        assertTrue("expected CancellationException, got ${result.exceptionOrNull()}", result.exceptionOrNull() is CancellationException)
        // Row-scoped zero: no retry scheduled, no acknowledgement applied.
        val row = runBlocking { b.db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId } }
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt)
        assertEquals(0L, runBlocking { b.db.healthSyncDao().revision(owner, "2026-10-07") }!!.serverRevision)
    }

    @Test fun cancelThenThrowEscapesWithoutRetryRow() = runBlocking {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val fake = FakeTransport(onApply = {
            kotlin.coroutines.coroutineContext[Job]?.cancel()
            throw java.io.IOException("boom after cancel")
        })
        val result = runCatching {
            async(Dispatchers.IO) {
                b.service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b))
            }.await()
        }
        assertTrue("expected CancellationException, got ${result.exceptionOrNull()}", result.exceptionOrNull() is CancellationException)
        // Row-scoped zero: cancellation wins over retry scheduling.
        val row = runBlocking { b.db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId } }
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt)
    }

    @Test fun outcomeEmptyIsComplete() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val id = identity(b.prefs)
        val result = b.service.publishBackupOutcome(b.db, owner, id.session, id.localConsentGeneration, server, 2, 1)
        assertTrue(result.isSuccess)
        assertEquals("Backup complete (2 sent, 1 received).", result.message)
        assertFalse(result.shouldRetry)
        assertTrue(b.prefs.lastSyncSuccess)
        assertEquals("Backup complete (2 sent, 1 received).", b.prefs.lastSyncMessage)
        assertTrue(b.prefs.lastSyncTimestamp > 0)
    }

    @Test fun outcomeDelayedCappedPendingIsIncomplete() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val id = identity(b.prefs)
        // Delayed AND attempt-capped: still undelivered, still counted.
        b.db.healthSyncDao().enqueue(HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), "2026-10-07", "UPSERT", 0L, 1L, "{}", HealthSyncPayloadCodec.hash("{}"), id.localConsentGeneration, attempts = 8, nextAttemptAt = System.currentTimeMillis() + 3_600_000L))
        val result = b.service.publishBackupOutcome(b.db, owner, id.session, id.localConsentGeneration, server, 0, 0)
        assertFalse(result.isSuccess)
        assertEquals("Backup isn't complete. Some records are still waiting to be sent.", result.message)
        assertTrue(result.shouldRetry)
        assertFalse(b.prefs.lastSyncSuccess)
        assertEquals("Backup isn't complete. Some records are still waiting to be sent.", b.prefs.lastSyncMessage)
    }

    @Test fun outcomeConflictTakesPrecedenceOverPending() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val id = identity(b.prefs)
        b.db.healthSyncDao().enqueue(HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), "2026-10-07", "UPSERT", 0L, 1L, "{}", HealthSyncPayloadCodec.hash("{}"), id.localConsentGeneration))
        b.db.healthSyncDao().putConflict(com.cyclecare.app.data.local.HealthSyncConflictEntity(owner, "2026-10-07", UUID.randomUUID().toString(), 1L, "{}", "UPSERT", 2L, "{}", false, System.currentTimeMillis()))
        val result = b.service.publishBackupOutcome(b.db, owner, id.session, id.localConsentGeneration, server, 0, 0)
        assertFalse(result.isSuccess)
        assertEquals("1 saved record(s) need your choice in Settings; nothing was overwritten.", result.message)
        assertFalse(result.shouldRetry)
        assertFalse(b.prefs.lastSyncSuccess)
    }

    @Test fun outcomeIdentityDriftPublishesNothing() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val id = identity(b.prefs)
        // Identity moved on AFTER the attempt's scope was captured: the
        // publication guard must refuse and the shared status stays untouched.
        b.prefs.userId = "owner-b"
        val result = b.service.publishBackupOutcome(b.db, owner, id.session, id.localConsentGeneration, server, 0, 0)
        assertFalse(result.isSuccess)
        assertEquals("Backup stopped because the account or your choices changed.", result.message)
        assertFalse(result.shouldRetry)
        assertEquals("Never synced", b.prefs.lastSyncMessage)
        assertEquals(0L, b.prefs.lastSyncTimestamp)
        assertFalse(b.prefs.lastSyncSuccess)
    }

    @Test fun transportFailureKeepsBoundedRetryParity() = runBlocking(Dispatchers.IO) {
        val b = bound()
        val entry = seedPending(b, "2026-10-07")
        val id = identity(b.prefs)
        val applied = b.service.executeV3OutboxAttempt(FakeTransport(onApply = { throw java.io.IOException("boom") }), "tok", owner, id.session, id.localConsentGeneration, server, b.db, capture(b))
        assertEquals(0, applied)
        val row = b.db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId }
        assertEquals(1, row.attempts)
        assertTrue(row.nextAttemptAt > 0)
    }
}
