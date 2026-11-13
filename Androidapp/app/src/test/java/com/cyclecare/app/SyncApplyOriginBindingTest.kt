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
import com.cyclecare.app.data.local.rust.RustRemoteRows
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.OwnerTransitionException
import com.cyclecare.app.security.AuthPreferences
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B1 slice: repository-bound exact-origin capture/dispatch for the four DORMANT
 * sync apply routes (ack/conflict/retry/page) plus typed host sync stale.
 * Tier: tier-agnostic host routing evidence over plain temporary Room DBs
 * (Robolectric; no SQLCipher/Keystore, no admission, no network, NO transport
 * loop). Proves capture/dispatch routing, lease and outcome-mapping behavior
 * ONLY: production loop wiring, shared catch semantics and Worker/registry
 * scope are deferred and nothing here claims end-to-end sync. The release
 * gate (conflictSafeProtocolReleased) stays false; no production caller
 * reaches these routes. All "zero" claims are scoped to the named repository
 * DB ROWS, never an atomic prefs+DB claim. Cancellation after a synchronous
 * commit may lose the waiter; no zero-commit promise under cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncApplyOriginBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private val owner = "owner-a"
    private val server = 5L

    private fun open(): CycleCareDatabase {
        val name = "b1-${UUID.randomUUID()}".also { names += it }
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

    init {
        // The production wiring gap is real and reported separately: nothing in
        // app sources calls warmUp, so the Track planner is never loaded. Tests
        // warm the REAL native planner here (its designated hook) so the host
        // Track path can execute at all.
        com.cyclecare.app.data.local.rust.RustTrackNative.warmUp()
    }

    private fun syncPrefs(): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("b1-${UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply {
            userId = owner
            isCloudSyncAllowed = true
            check(saveServerHealthConsentReceipt(owner, sessionGeneration, healthConsentGeneration, server, true)) { "receipt_fixture_failed" }
        }

    private data class Ported(val repo: CycleRepository, val host: RustBoundRoomHost, val port: RustRepositoryPort, val db: CycleCareDatabase, val prefs: AuthPreferences)

    private fun ported(p: AuthPreferences = syncPrefs()): Ported {
        val db = open()
        val host = RustBoundRoomHost(p)
        val token = (host.publish(null, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val port = RustRepositoryPort(host, token)
        return Ported(CycleRepository(db, port), host, port, db, p)
    }

    private fun identity(p: AuthPreferences) = p.syncIdentitySnapshot()

    private fun capture(f: Ported): CycleRepository.SyncApplyOrigin {
        val id = identity(f.prefs)
        return f.repo.captureSyncApplyOrigin(f.prefs, f.db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)!!
    }

    private fun seedPending(f: Ported, record: String): HealthSyncOutboxEntry {
        val entry = HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), record, "UPSERT", 0L, 1L, "{}", HealthSyncPayloadCodec.hash("{}"), identity(f.prefs).localConsentGeneration)
        runBlocking {
            f.db.healthSyncDao().putRevision(HealthSyncRevision(owner, record, 1L, entry.payloadHash, false, 1L, 0L))
            f.db.healthSyncDao().enqueue(entry)
        }
        return entry
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

    @Test fun noPortSelectedReturnsNullOrigin() {
        val db = open()
        val prefs = syncPrefs()
        val repo = CycleRepository(db)
        val id = identity(prefs)
        assertNull(repo.captureSyncApplyOrigin(prefs, db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!))
    }

    @Test fun captureBindsExactPortTokenDatabaseAndRequestIdentity() {
        val f = ported()
        val id = identity(f.prefs)
        val origin = f.repo.captureSyncApplyOrigin(f.prefs, f.db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        assertNotNull(origin)
        origin!!
        assertEquals(owner, origin.owner)
        assertEquals(id.session, origin.session)
        assertEquals(id.localConsentGeneration, origin.localConsentGeneration)
        assertEquals(server, origin.serverConsentGeneration)
        assertTrue(origin.database === f.db)
        assertTrue(f.port.token.database === f.db)
        assertTrue(origin.token.isMintedFor(f.port))
    }

    @Test fun captureFailsClosedWhenServicePrefsAreNotTheHostPrefs() {
        val f = ported()
        val other = syncPrefs() // identical values, DIFFERENT instance
        val id = identity(other)
        expectThrows<OwnerTransitionException> {
            f.repo.captureSyncApplyOrigin(other, f.db, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        }
    }

    @Test fun captureFailsClosedOnEachRequestIdentityDrift() {
        val f = ported()
        val id = identity(f.prefs)
        expectThrows<OwnerTransitionException> { f.repo.captureSyncApplyOrigin(f.prefs, f.db, "owner-b", id.session, id.localConsentGeneration, id.serverConsentGeneration!!) }
        expectThrows<OwnerTransitionException> { f.repo.captureSyncApplyOrigin(f.prefs, f.db, id.owner!!, id.session + 1, id.localConsentGeneration, id.serverConsentGeneration!!) }
        expectThrows<OwnerTransitionException> { f.repo.captureSyncApplyOrigin(f.prefs, f.db, id.owner!!, id.session, id.localConsentGeneration + 1, id.serverConsentGeneration!!) }
        expectThrows<OwnerTransitionException> { f.repo.captureSyncApplyOrigin(f.prefs, f.db, id.owner!!, id.session, id.localConsentGeneration, server + 1) }
    }

    @Test fun captureFailsClosedWhenDatabaseInstanceDiffers() {
        val f = ported()
        val other = open()
        val id = identity(f.prefs)
        expectThrows<OwnerTransitionException> { f.repo.captureSyncApplyOrigin(f.prefs, other, id.owner!!, id.session, id.localConsentGeneration, id.serverConsentGeneration!!) }
    }

    @Test fun dispatchAckAppliesActualRows() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val outcome = f.repo.dispatchAcknowledgeSync(origin, f.db, entry, 1L)
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(true), outcome)
        val dao = f.db.healthSyncDao()
        assertEquals(1L, dao.revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(dao.pending(owner).none { it.operationId == entry.operationId })
    }

    @Test fun dispatchAckNoChangeWhenEntryNoLongerMatches() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val outcome = f.repo.dispatchAcknowledgeSync(origin, f.db, entry.copy(payloadHash = "other"), 1L)
        assertEquals(CycleRepository.SyncApplyOutcome.NoChange, outcome)
        val dao = f.db.healthSyncDao()
        assertEquals(0L, dao.revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(dao.pending(owner).any { it.operationId == entry.operationId })
    }

    @Test fun dispatchConflictWritesActualRow() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val outcome = f.repo.dispatchConflictSync(origin, f.db, entry, 7L, "{}", false, 11L)
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(true), outcome)
        val conflicts = f.db.healthSyncDao().conflicts(owner)
        assertEquals(1, conflicts.size)
        assertEquals(7L, conflicts[0].remoteRevision)
        assertEquals("2026-10-07", conflicts[0].recordId)
    }

    @Test fun dispatchConflictNoChangeWhenEntryNoLongerMatches() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val outcome = f.repo.dispatchConflictSync(origin, f.db, entry.copy(payloadHash = "other"), 7L, "{}", false, 11L)
        assertEquals(CycleRepository.SyncApplyOutcome.NoChange, outcome)
        assertTrue(f.db.healthSyncDao().conflicts(owner).isEmpty())
    }

    @Test fun dispatchRetryUpdatesActualRow() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val outcome = f.repo.dispatchRetrySync(origin, f.db, entry, 99L, "Backup needs another try")
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(true), outcome)
        val row = f.db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId }
        assertEquals(1, row.attempts)
        assertEquals(99L, row.nextAttemptAt)
        assertEquals("Backup needs another try", row.lastError)
    }

    @Test fun dispatchRetryNoChangeWhenAttemptsRaced() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        f.db.healthSyncDao().retry(owner, entry.operationId, 50L, "first")
        val origin = capture(f)
        val outcome = f.repo.dispatchRetrySync(origin, f.db, entry, 99L, "second")
        assertEquals(CycleRepository.SyncApplyOutcome.NoChange, outcome)
        val row = f.db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId }
        assertEquals(1, row.attempts)
        assertEquals(50L, row.nextAttemptAt)
    }

    @Test fun dispatchPageAppliesActualRowsAndCursor() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val payload = HealthSyncPayloadCodec.logPayload(CycleDayLog(dateString = "2026-10-07", moodLevel = "calm"))
        val origin = capture(f)
        val outcome = f.repo.dispatchApplyRemotePage(origin, f.db, listOf(RustRemoteRows.Change("2026-10-07", 3L, 1L, payload, false)), 11L)
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(1), outcome)
        val dao = f.db.healthSyncDao()
        assertEquals("calm", f.db.cycleDao().getLogByDate("2026-10-07")?.moodLevel)
        val revision = dao.revision(owner, "2026-10-07")!!
        assertEquals(1L, revision.revision)
        assertEquals(3L, revision.serverRevision)
        assertEquals(1L, dao.cursor(owner)?.changeSeq)
    }

    @Test fun hostReturnsTypedStaleNotStorageWhenServerGenerationChanged() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val id = identity(f.prefs)
        check(f.prefs.saveServerHealthConsentReceipt(owner, id.session, id.localConsentGeneration, server + 1, true))
        val payload = HealthSyncPayloadCodec.logPayload(CycleDayLog(dateString = "2026-10-08", moodLevel = "calm"))
        val calls = listOf<(RustBoundRoomHost, RustBoundRoomHost.Token) -> RustBoundRoomHost.Result<*>>(
            { h, t -> h.acknowledgeSync(t, server, entry, 1L) },
            { h, t -> h.conflictSync(t, server, entry, 7L, "{}", false, 11L) },
            { h, t -> h.retrySync(t, server, entry, 99L, "x") },
            { h, t -> h.applyRemotePage(t, server, listOf(RustRemoteRows.Change("2026-10-08", 3L, 1L, payload, false)), 11L) },
        )
        calls.forEach { call ->
            assertTrue("typed Stale, never Storage", call(f.host, f.port.token) === RustBoundRoomHost.Result.Stale)
        }
        // Row-scoped zero: the server-generation rejection left every named row untouched.
        val dao = f.db.healthSyncDao()
        assertEquals(0L, dao.revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(dao.pending(owner).any { it.operationId == entry.operationId && it.attempts == 0 })
        assertTrue(dao.conflicts(owner).isEmpty())
        assertNull(f.db.cycleDao().getLogByDate("2026-10-08"))
        assertNull(dao.cursor(owner))
    }

    @Test fun dispatchStaleWhenServerGenerationChangesAfterCapture() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val id = identity(f.prefs)
        check(f.prefs.saveServerHealthConsentReceipt(owner, id.session, id.localConsentGeneration, server + 1, true))
        var called = false
        val outcome = f.repo.dispatchSyncApply(origin, f.db) { _, _ -> called = true; f.host.acknowledgeSync(f.port.token, server, entry, 1L) }
        assertEquals(CycleRepository.SyncApplyOutcome.Stale, outcome)
        assertFalse("host never invoked on stale identity", called)
        assertEquals(0L, f.db.healthSyncDao().revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(f.db.healthSyncDao().pending(owner).any { it.operationId == entry.operationId })
    }

    @Test fun dispatchStaleWhenOwnerChangesAfterCapture() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        f.prefs.userId = "owner-b"
        var called = false
        val outcome = f.repo.dispatchSyncApply(origin, f.db) { _, _ -> called = true; f.host.acknowledgeSync(f.port.token, server, entry, 1L) }
        assertEquals(CycleRepository.SyncApplyOutcome.Stale, outcome)
        assertFalse("host never invoked on stale identity", called)
        assertTrue(f.db.healthSyncDao().pending(owner).any { it.operationId == entry.operationId })
    }

    @Test fun dispatchStaleWhenPortSupersededAfterCapture() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val entry = seedPending(f, "2026-10-07")
        val origin = capture(f)
        val replacementDb = open()
        val replacementHost = RustBoundRoomHost(f.prefs)
        val replacementToken = (replacementHost.publish(null, replacementDb, f.prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        f.repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
        // The routing LEASE is the final gate: the old host token still owns its
        // own host, so the host call is reached, but lease admission fails under
        // routingLock and the transaction returns Stale before any SQL.
        val outcome = f.repo.dispatchSyncApply(origin, f.db) { _, _ -> f.host.acknowledgeSync(f.port.token, server, entry, 1L) }
        assertEquals(CycleRepository.SyncApplyOutcome.Stale, outcome)
        assertEquals(0L, f.db.healthSyncDao().revision(owner, "2026-10-07")!!.serverRevision)
        assertTrue(f.db.healthSyncDao().pending(owner).any { it.operationId == entry.operationId && it.attempts == 0 })
    }

    @Test fun leaseReleasedAcrossSequentialDispatches() = runBlocking(Dispatchers.IO) {
        val f = ported()
        val first = seedPending(f, "2026-10-07")
        val second = seedPending(f, "2026-10-08")
        val o1 = capture(f)
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(true), f.repo.dispatchSyncApply(o1, f.db) { h, t -> h.acknowledgeSync(t, server, first, 1L) })
        val o2 = capture(f)
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(true), f.repo.dispatchSyncApply(o2, f.db) { h, t -> h.acknowledgeSync(t, server, second, 1L) })
        assertTrue(f.db.healthSyncDao().pending(owner).isEmpty())
        // Proof of release: replaceRustPort rejects while any routing lease is
        // held ("rust_replacement_during_commit"), so this succeeding after
        // both dispatches shows each lease was released exactly once.
        val replacementDb = open()
        val replacementHost = RustBoundRoomHost(f.prefs)
        val replacementToken = (replacementHost.publish(null, replacementDb, f.prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        f.repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
    }

    @Test fun pureOutcomeMapping() {
        val f = ported()
        val origin = capture(f)
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(true), f.repo.dispatchSyncApply(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Value(true) })
        assertEquals(CycleRepository.SyncApplyOutcome.NoChange, f.repo.dispatchSyncApply(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Value(false) })
        assertEquals(CycleRepository.SyncApplyOutcome.Applied(7), f.repo.dispatchSyncApply(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Value(7) })
        assertEquals(CycleRepository.SyncApplyOutcome.Stale, f.repo.dispatchSyncApply<Boolean>(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Stale })
    }

    @Test fun storageCorruptRejectedOverflowSurfaceAsFailure() {
        val f = ported()
        val origin = capture(f)
        expectThrows<RustRepositoryPort.Failure> { f.repo.dispatchSyncApply<Boolean>(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Storage }; Unit }
        expectThrows<RustRepositoryPort.Failure> { f.repo.dispatchSyncApply<Boolean>(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Corrupt }; Unit }
        expectThrows<RustRepositoryPort.Failure> { f.repo.dispatchSyncApply<Boolean>(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Rejected("x") }; Unit }
        expectThrows<RustRepositoryPort.Failure> { f.repo.dispatchSyncApply<Boolean>(origin, f.db) { _, _ -> RustBoundRoomHost.Result.Overflow }; Unit }
    }

    @Test fun cancellationPropagatesThroughDispatch() {
        val f = ported()
        val origin = capture(f)
        expectThrows<CancellationException> {
            f.repo.dispatchSyncApply<Boolean>(origin, f.db) { _, _ -> throw CancellationException("test") }
            Unit
        }
    }

    @Test fun dispatchRejectsOriginIssuedByAnotherRepository() {
        val f = ported()
        val origin = capture(f)
        val other = ported(f.prefs)
        expectThrows<OwnerTransitionException> {
            other.repo.dispatchSyncApply<Boolean>(origin, other.db) { _, _ -> RustBoundRoomHost.Result.Value(true) }
            Unit
        }
    }
}
