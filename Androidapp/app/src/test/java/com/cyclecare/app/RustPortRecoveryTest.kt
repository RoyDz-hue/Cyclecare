package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.data.repository.RustPortRecovery
import com.cyclecare.app.data.repository.RustPortTransitioner
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-D recovery-coordinator witnesses: recovery is OWNED and OBSERVABLE,
 * requests coalesce, recoverable outcomes retry without a new identity
 * event, and a Busy pass (commit lease held) recovers once the lease
 * releases. Tier: host routing evidence (Robolectric; no native claims).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustPortRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-recovery-it").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())

    @After fun cleanup() {
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
    }

    private fun awaitCondition(timeoutMs: Long = 15000, probe: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return@runBlocking
            delay(25)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    @Test fun requestsCoalesceToOneFollowUpPass() {
        val runs = AtomicInteger(0)
        val gate = CountDownLatch(1)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet()
            gate.await(15, TimeUnit.SECONDS)
            RustPortTransitioner.Outcome.Replaced
        }, retryDelayMs = 0)
        recovery.request()
        // First pass is blocked mid-transition; two more requests arrive.
        recovery.request(); recovery.request()
        gate.countDown()
        awaitCondition { !recovery.recoveryInFlight }
        assertEquals("three requests -> exactly two passes (one coalesced follow-up)", 2, runs.get())
        assertEquals(2, recovery.roundsRun)
        assertEquals(RustPortTransitioner.Outcome.Replaced, recovery.lastOutcome)
    }

    @Test fun terminalOutcomeStopsWithoutRetry() {
        val runs = AtomicInteger(0)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet(); RustPortTransitioner.Outcome.Current
        }, retryDelayMs = 0)
        recovery.request()
        awaitCondition { !recovery.recoveryInFlight }
        assertEquals(1, runs.get())
        assertEquals(RustPortTransitioner.Outcome.Current, recovery.lastOutcome)
    }

    @Test fun recoverableOutcomeRetriesUpToTheBoundThenStaysFailClosed() {
        val runs = AtomicInteger(0)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet(); RustPortTransitioner.Outcome.Busy
        }, maxRounds = 3, retryDelayMs = 0)
        recovery.request()
        awaitCondition { !recovery.recoveryInFlight }
        assertEquals("bounded retries, not an unbounded spin", 3, runs.get())
        assertEquals("final Busy is recorded, not dropped", RustPortTransitioner.Outcome.Busy, recovery.lastOutcome)
        // A later request starts a fresh bounded round.
        recovery.request()
        awaitCondition { recovery.roundsRun >= 6 }
    }

    @Test fun busyUnderHeldCommitLeaseRecoversToReplacedAfterRelease() {
        val p = AuthPreferences(context, context.getSharedPreferences("stage-d-recovery-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = "owner-a"; attachPublicationAdmission(IdentityPublicationAdmission.forAndroid()) }
        fun newDb() = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
            })
            .build().also(openDbs::add)
        val dbA = newDb()
        val repo = CycleRepository(dbA)
        val byOwner = mutableMapOf<String?, CycleCareDatabase>("owner-a" to dbA)
        val installOutcome = pool.submit(java.util.concurrent.Callable {
            RustPortInstaller(repo, RustBoundRoomHost(p),
                RustPortInstallResolver.Resolution.bind(p, p.rustOperationSnapshot(), dbA)).install()
        }).get(30, TimeUnit.SECONDS)
        assertEquals(RustPortInstaller.Outcome.Installed(1L), installOutcome)
        pool.submit(java.util.concurrent.Callable { p.userId = "owner-b" }).get(30, TimeUnit.SECONDS)
        assertFalse(repo.currentRustPortForOnboarding()!!.isCurrent())
        val transitioner = RustPortTransitioner(context, p, repo) { c, prefs ->
            RustPortInstallResolver(c, prefs) { _, owner -> byOwner.getOrPut(owner) { newDb() } }
        }
        val recovery = RustPortRecovery(scope, runTransition = { transitioner.ensureCurrentRouting() }, maxRounds = 10, retryDelayMs = 100)
        val lease = repo.holdRoutingLeaseForTests()
        recovery.request()
        // The Busy outcome is OBSERVED, not dropped: recorded while the lease holds.
        awaitCondition { recovery.lastOutcome is RustPortTransitioner.Outcome.Busy }
        assertTrue("stale port still selected while Busy", repo.currentRustPortForOnboarding()?.isCurrent() == false)
        // Release the commit lease: recovery picks it up WITHOUT any new
        // identity event - that is the whole point of the owned retry.
        lease.close()
        awaitCondition { recovery.lastOutcome is RustPortTransitioner.Outcome.Replaced }
        awaitCondition { !recovery.recoveryInFlight }
        val port = repo.currentRustPortForOnboarding()!!
        assertTrue(port.isCurrent())
        assertEquals("owner-b", port.token.preference.operation.owner)
        assertTrue("recovery took at least the Busy pass plus the Replaced pass", recovery.roundsRun >= 2)
    }

    @Test fun cancellationBeforeTheBodyStartsDoesNotStrandOwnership() {
        // Generation-safe finalization: a job cancelled BEFORE its body
        // starts never enters run()s decision lock, so only the
        // invokeOnCompletion handler can release inFlight/pending. Without
        // it every later request would set pending forever (round-4 race).
        val single = Executors.newSingleThreadExecutor { r -> Thread(r, "stage-d-recovery-single").apply { isDaemon = true } }
        val blocker = CountDownLatch(1)
        val occupy = CountDownLatch(1)
        single.execute { occupy.countDown(); blocker.await(15, TimeUnit.SECONDS) }
        assertTrue(occupy.await(15, TimeUnit.SECONDS))
        val singleScope = CoroutineScope(SupervisorJob() + single.asCoroutineDispatcher())
        val runs = AtomicInteger(0)
        val recovery = RustPortRecovery(singleScope, runTransition = {
            runs.incrementAndGet(); RustPortTransitioner.Outcome.Current
        }, retryDelayMs = 0)
        recovery.request()
        recovery.close() // cancelled while still queued behind the blocker
        blocker.countDown()
        awaitCondition { !recovery.recoveryInFlight }
        assertEquals("cancelled-before-start body never ran", 0, runs.get())
        single.shutdownNow()
    }

    @Test fun closeAndJoinActuallyWaitsForAnInFlightPass() {
        // JOIN witness (stage-G publication repair): with a pass BLOCKED
        // mid-transition, closeAndJoinForTests must not return - the old
        // launch-outside-lock protocol could report quiesced on a null job
        // read while the pass was running.
        val runs = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet()
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            RustPortTransitioner.Outcome.Replaced
        }, retryDelayMs = 0)
        recovery.request()
        assertTrue("pass must enter its transition", entered.await(10, TimeUnit.SECONDS))
        val joinResult = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val joiner = Thread {
            joinResult.set(runBlocking { recovery.closeAndJoinForTests(10_000) })
        }
        joiner.start()
        Thread.sleep(500)
        assertNull(
            "closeAndJoinForTests returned while the in-flight pass was still blocked (early-true race)",
            joinResult.get()
        )
        release.countDown()
        joiner.join(15_000)
        assertEquals(java.lang.Boolean.TRUE, joinResult.get())
        assertEquals(1, runs.get())
        awaitCondition { !recovery.recoveryInFlight }
    }

    @Test fun closeAndJoinTimeoutRetainsOwnershipAndRefusesNewRequests() {
        // TIMEOUT witness (stage-G retained-ownership repair): a bounded
        // miss must report NOT quiesced, must refuse new requests (closed
        // sticks), and a later retry must join the SAME owned pass - never
        // a fresh true on dropped ownership.
        val runs = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet()
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            RustPortTransitioner.Outcome.Replaced
        }, retryDelayMs = 0)
        recovery.request()
        assertTrue("pass must enter its transition", entered.await(10, TimeUnit.SECONDS))
        assertFalse(
            "a blocked pass must NOT report quiesced within a small bound",
            runBlocking { recovery.closeAndJoinForTests(200) }
        )
        recovery.request()
        Thread.sleep(300)
        assertEquals("no new pass may launch after close", 1, runs.get())
        release.countDown()
        assertTrue(
            "the retry must join the same owned pass once it unwinds",
            runBlocking { recovery.closeAndJoinForTests(10_000) }
        )
        assertEquals(1, runs.get())
        awaitCondition { !recovery.recoveryInFlight }
    }

    @Test fun requestPublishesItsJobBeforeTheCoroutineCanStart() {
        // PUBLICATION witness (stage-G LAZY launch repair): request() stores
        // the job under the state lock BEFORE the coroutine can start, so an
        // immediate close() cancels the SAME handle (never a null or
        // superseded read) and the coordinator reaches a terminal state.
        val runs = AtomicInteger(0)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet(); RustPortTransitioner.Outcome.Current
        }, retryDelayMs = 0)
        recovery.request()
        recovery.close()
        assertTrue(
            "the stored job must be the launched one: cancel+join reaches terminal",
            runBlocking { recovery.closeAndJoinForTests(10_000) }
        )
        awaitCondition { !recovery.recoveryInFlight }
        // The pass may legitimately run zero times (cancel before start) or
        // once (started before cancel) - but never more.
        assertTrue("at most the one requested pass may run, got ${runs.get()}", runs.get() <= 1)
        recovery.request()
        Thread.sleep(300)
        assertTrue("a closed coordinator must refuse new requests", runs.get() <= 1)
    }

    @Test fun closedRecoveryRefusesFurtherRequests() {
        val runs = AtomicInteger(0)
        val recovery = RustPortRecovery(scope, runTransition = {
            runs.incrementAndGet(); RustPortTransitioner.Outcome.Current
        }, retryDelayMs = 0)
        recovery.close()
        recovery.request()
        Thread.sleep(300)
        assertEquals("closed coordinator runs nothing", 0, runs.get())
        assertFalse(recovery.recoveryInFlight)
    }
}
