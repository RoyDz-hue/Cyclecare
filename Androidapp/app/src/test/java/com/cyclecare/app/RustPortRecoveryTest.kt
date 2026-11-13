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
        val recovery = RustPortRecovery(scope, {
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
        val recovery = RustPortRecovery(scope, {
            runs.incrementAndGet(); RustPortTransitioner.Outcome.Current
        }, retryDelayMs = 0)
        recovery.request()
        awaitCondition { !recovery.recoveryInFlight }
        assertEquals(1, runs.get())
        assertEquals(RustPortTransitioner.Outcome.Current, recovery.lastOutcome)
    }

    @Test fun recoverableOutcomeRetriesUpToTheBoundThenStaysFailClosed() {
        val runs = AtomicInteger(0)
        val recovery = RustPortRecovery(scope, {
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
        val recovery = RustPortRecovery(scope, { transitioner.ensureCurrentRouting() }, maxRounds = 10, retryDelayMs = 100)
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
}
