package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
import com.cyclecare.app.data.local.HealthSyncRevision
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.OwnerTransitionException
import com.cyclecare.app.data.supabase.HealthSyncV3Transport
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.data.supabase.SupabaseSyncWorker
import com.cyclecare.app.data.supabase.V3WireChange
import com.cyclecare.app.data.supabase.V3WireOperation
import com.cyclecare.app.data.supabase.V3WireReceipt
import com.cyclecare.app.security.AuthPreferences
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker/process-handle lifecycle witnesses. The process-published repository
 * handle (CycleRepository.processRepository) is the only binding that survives
 * a process death, so each lifecycle case below is driven through the worker's
 * own binding expression on a FRESH service. Tier: host (Robolectric; no
 * SQLCipher/Keystore, no network, no admission UI). The consent-true path of
 * doWork opens the encrypted store natively and stays owed to the device tier
 * (Stage-D precedent). Cases: (1) revoked-consent doWork ordering, (2) handle
 * publication/rebind, (3) stale-owner capture rejection, (4) an actual
 * in-flight legacy effect holding the admission guard keeps a concurrent
 * install seal Busy, (5) mid-attempt host replacement escapes stale with zero
 * rows, (6) mid-attempt transition plus transport failure escapes stale with
 * no retry row. The Stage-B seal seam has no production caller; the install
 * contract is residual Stage-C, so the Busy witness lives at the seal
 * (trySealLegacyDispatch), the gate an installer must pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerProcessHandleWitnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private val owner = "owner-a"
    private val server = 5L

    private fun open(singleTransactionThread: Boolean = false): CycleCareDatabase {
        val name = "wph-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        val builder = Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries()
        if (singleTransactionThread) builder.setTransactionExecutor(Executors.newSingleThreadExecutor())
        return builder.build().also { handles += it }
    }

    @After fun cleanup() {
        CycleRepository.processRepository.set(null)
        handles.forEach { if (it.isOpen) it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private fun syncPrefs(): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("wph-${UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply {
            userId = owner
            isCloudSyncAllowed = true
            check(saveServerHealthConsentReceipt(owner, sessionGeneration, healthConsentGeneration, server, true)) { "receipt_fixture_failed" }
        }

    private fun identity(p: AuthPreferences) = p.syncIdentitySnapshot()

    private fun validAck(op: V3WireOperation) = V3WireReceipt("applied", op.baseRevision + 1, null, "a".repeat(64), null, op.recordId, op.operationId, op.logicalRecordId)

    private class FakeTransport(
        val onApply: suspend (V3WireOperation) -> V3WireReceipt = { error("no apply scripted") },
    ) : HealthSyncV3Transport {
        override suspend fun apply(operation: V3WireOperation, accessToken: String) = onApply(operation)
        override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String) = emptyList<V3WireChange>()
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

    private fun seedPending(db: CycleCareDatabase, p: AuthPreferences, record: String): HealthSyncOutboxEntry {
        val entry = HealthSyncOutboxEntry(owner, UUID.randomUUID().toString(), record, "UPSERT", 0L, 1L, "{}", HealthSyncPayloadCodec.hash("{}"), identity(p).localConsentGeneration)
        runBlocking {
            db.healthSyncDao().putRevision(HealthSyncRevision(owner, record, 1L, entry.payloadHash, false, 1L, 0L))
            db.healthSyncDao().enqueue(entry)
        }
        return entry
    }

    /** The worker's exact binding expression, driven on a fresh service. */
    private fun bindThroughProcessHandle(service: SupabaseSyncService, repo: CycleRepository) {
        CycleRepository.processRepository.set(repo)
        CycleRepository.processRepository.get()?.let { service.bindSyncApplyRepository(it) }
    }

    @Test fun revokedConsentRunSkipsFactoryPingerAndStore() = runBlocking {
        // Written through the PRODUCTION constructor doWork itself uses, so the
        // consent bit lives in the exact store the worker will read. The
        // Keystore-backed file may instead fall back to the legacy plaintext
        // file on this tier (that fallback erases the user id on construction,
        // which itself leaves consent false); either way consent reads false
        // when doWork runs, and the assertions below pin the skip behavior.
        AuthPreferences(context).apply {
            userId = owner
            isCloudSyncAllowed = false
        }
        val probe = AuthPreferences(context)
        assertFalse("consent must read false in the store doWork reads (userId preserved=${probe.userId == owner})", probe.isCloudSyncAllowed)
        val factoryCalls = AtomicInteger(0)
        val pingerCalls = AtomicInteger(0)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                SupabaseSyncWorker(appContext, workerParameters, { _, _, _ -> factoryCalls.incrementAndGet(); error("service factory must not run for revoked consent") }, { pingerCalls.incrementAndGet(); true })
        }
        val worker = TestListenableWorkerBuilder<SupabaseSyncWorker>(context).setWorkerFactory(factory).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, factoryCalls.get())
        assertEquals(0, pingerCalls.get())
    }

    @Test fun processPublishedHandleIsWhatAFreshServiceBinds() {
        // The worker's exact binding expression: processRepository.get()?.let {
        // service.bindSyncApplyRepository(it) }.
        val db = open()
        val prefs = syncPrefs()
        val repo = CycleRepository(db) // no port: bound-but-unselected, consistent legacy
        val service = SupabaseSyncService(db, prefs)
        bindThroughProcessHandle(service, repo)
        // Bound once through the handle: a second bind is refused, and the
        // binding resolves consistent-legacy (null origin, no error).
        val id = identity(prefs)
        assertNull(service.captureSyncApplyBinding(db, owner, id.session, id.localConsentGeneration, id.serverConsentGeneration!!))
        try {
            service.bindSyncApplyRepository(repo)
            throw AssertionError("expected sync_apply_repository_bound")
        } catch (e: IllegalStateException) {
            assertEquals("sync_apply_repository_bound", e.message)
        }
    }

    @Test fun staleOwnerCaptureRejectedBeforeAnyAttempt() {
        // Owner drift AFTER install: the request still carries the pre-drift
        // owner, so captureSyncApplyOrigin's snapshot check must reject the
        // capture itself with OwnerTransitionException before any attempt runs.
        val db = open()
        val prefs = syncPrefs()
        val host = RustBoundRoomHost(prefs)
        val token = (host.publish(null, db, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val repo = CycleRepository(db, RustRepositoryPort(host, token))
        val service = SupabaseSyncService(db, prefs)
        bindThroughProcessHandle(service, repo)
        val id = identity(prefs)
        prefs.userId = "owner-b"
        expectThrows<OwnerTransitionException> {
            service.captureSyncApplyBinding(db, owner, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        }
        assertEquals(0, runBlocking { db.healthSyncDao().pending(owner).size })
    }

    @Test fun inFlightLegacyEffectKeepsConcurrentInstallBusy() {
        // An ACTUAL in-flight legacy effect: the ACK-apply effect admitted
        // through the production per-effect admission function (admitLegacyEffect
        // -> repository.admitLegacyDispatch) is queued inside Room's
        // withTransaction because this fixture database's transaction executor
        // is pinned to one thread and that thread is occupied by a blocker
        // transaction. The guard is therefore held BY THE EFFECT ITSELF while a
        // concurrent install seal must report Busy (false). No false pass is
        // possible: a seal that wins before admission seals the gate, the
        // effect's admission then converts to stale, and the completion
        // assertions fail.
        val db = open(singleTransactionThread = true)
        val prefs = syncPrefs()
        val repo = CycleRepository(db) // no port: legacy admission authority
        val service = SupabaseSyncService(db, prefs)
        bindThroughProcessHandle(service, repo)
        val id = identity(prefs)
        val entry = seedPending(db, prefs, "2026-10-07")
        val origin = service.captureSyncApplyBinding(db, owner, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)
        assertNull(origin)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = Thread {
            runBlocking {
                db.withTransaction {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }
        blocker.isDaemon = true
        blocker.start()
        assertTrue("blocker must hold the transaction thread", entered.await(5, TimeUnit.SECONDS))

        val ackReturning = CountDownLatch(1)
        var applied = -1
        var attemptFailure: Throwable? = null
        val attempt = Thread {
            try {
                runBlocking {
                    applied = service.executeV3OutboxAttempt(FakeTransport(onApply = { op ->
                        ackReturning.countDown()
                        validAck(op)
                    }), "tok", owner, id.session, id.localConsentGeneration, server, db, origin)
                }
            } catch (t: Throwable) { attemptFailure = t }
        }
        attempt.isDaemon = true
        attempt.start()
        assertTrue("ACK must return before the effect admits", ackReturning.await(5, TimeUnit.SECONDS))
        Thread.sleep(500) // admission follows the ACK return by microseconds
        assertFalse("install seal must stay Busy while the effect is in flight", repo.trySealLegacyDispatch())
        assertEquals(1, runBlocking { db.healthSyncDao().pending(owner).count { it.operationId == entry.operationId } })

        release.countDown()
        attempt.join(15_000)
        assertFalse("attempt must finish once the blocker releases", attempt.isAlive)
        blocker.join(15_000)
        assertNull("attempt must not fail: ${attemptFailure}", attemptFailure)
        assertEquals(1, applied)
        assertEquals(1L, runBlocking { db.healthSyncDao().revision(owner, "2026-10-07")!!.serverRevision })
        assertTrue(runBlocking { db.healthSyncDao().pending(owner).none { it.operationId == entry.operationId } })
        assertTrue("seal succeeds once the effect has drained", repo.trySealLegacyDispatch())
        repo.releaseLegacyDispatchSeal()
    }

    @Test fun hostReplacementMidAttemptEscapesStaleThroughProcessHandle() {
        // Mid-attempt transition (host replacement) before an ACK: the effect
        // types Stale, escapes the broad failure catch, and leaves zero rows
        // and zero retry state.
        val db = open()
        val prefs = syncPrefs()
        val host = RustBoundRoomHost(prefs)
        val token = (host.publish(null, db, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val repo = CycleRepository(db, RustRepositoryPort(host, token))
        val service = SupabaseSyncService(db, prefs)
        bindThroughProcessHandle(service, repo)
        val id = identity(prefs)
        val entry = seedPending(db, prefs, "2026-10-07")
        val fake = FakeTransport(onApply = { op ->
            val replacementDb = open()
            val replacementHost = RustBoundRoomHost(prefs)
            val replacementToken = (replacementHost.publish(null, replacementDb, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
            validAck(op)
        })
        expectThrows<CycleRepository.SyncApplyStaleException> {
            runBlocking { service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, db, service.captureSyncApplyBinding(db, owner, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)) }
        }
        val dao = db.healthSyncDao()
        runBlocking {
            assertEquals(0L, dao.revision(owner, "2026-10-07")!!.serverRevision)
            assertTrue(dao.pending(owner).any { it.operationId == entry.operationId && it.attempts == 0 && it.nextAttemptAt == 0L })
        }
    }

    @Test fun transitionMidAttemptTransportFailureEscapesStaleWithoutRetryRow() {
        // Mid-attempt transition plus a transport failure: the stale transition
        // still escapes the broad catch - no retry row is written for a stale
        // attempt.
        val db = open()
        val prefs = syncPrefs()
        val host = RustBoundRoomHost(prefs)
        val token = (host.publish(null, db, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val repo = CycleRepository(db, RustRepositoryPort(host, token))
        val service = SupabaseSyncService(db, prefs)
        bindThroughProcessHandle(service, repo)
        val id = identity(prefs)
        val entry = seedPending(db, prefs, "2026-10-07")
        val fake = FakeTransport(onApply = {
            val replacementDb = open()
            val replacementHost = RustBoundRoomHost(prefs)
            val replacementToken = (replacementHost.publish(null, replacementDb, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            repo.replaceRustPort(RustRepositoryPort(replacementHost, replacementToken))
            throw java.io.IOException("network down")
        })
        expectThrows<CycleRepository.SyncApplyStaleException> {
            runBlocking { service.executeV3OutboxAttempt(fake, "tok", owner, id.session, id.localConsentGeneration, server, db, service.captureSyncApplyBinding(db, owner, id.session, id.localConsentGeneration, id.serverConsentGeneration!!)) }
        }
        val row = runBlocking { db.healthSyncDao().pending(owner).single { it.operationId == entry.operationId } }
        assertEquals(0, row.attempts)
        assertEquals(0L, row.nextAttemptAt)
    }
}
