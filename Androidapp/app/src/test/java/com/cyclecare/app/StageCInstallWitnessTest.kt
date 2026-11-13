package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.OwnerTransitionException
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-C live-install WRITTEN witnesses for CycleRepository.installRustPort +
 * RustPortInstaller. UNEXECUTED until an authorized run produces XML. Tier:
 * tier-agnostic host routing evidence over plain temporary Room DBs
 * (Robolectric; no SQLCipher/Keystore, no native claims). Scope: seal/drain/
 * install/retry/observer semantics of the inert installer ONLY. Production
 * activation wiring (MainActivity warmup -> installer) remains separately
 * gated; nothing here selects a production repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageCInstallWitnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private val installerThreads = Executors.newCachedThreadPool { r -> Thread(r, "stage-c-install-it").apply { isDaemon = true } }

    private fun open(): CycleCareDatabase {
        val name = "stage-c-install-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        return Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it }
    }

    @After fun cleanup() { handles.forEach { if (it.isOpen) it.close() }; names.forEach { context.deleteDatabase(it) }; installerThreads.shutdownNow() }

    /** userId is set BEFORE the admission attaches (an attached admission
     * queues main-thread mutations instead of joining them). */
    private fun prefs(owner: String = "owner-a"): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("stage-c-install-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = owner; attachPublicationAdmission(IdentityPublicationAdmission.forAndroid()) }

    /** The installer refuses the main thread; witnesses honor that contract.
     * Identity mutations also run through here so an attached admission joins
     * them synchronously. */
    private fun <T> offMain(block: () -> T): T =
        installerThreads.submit(java.util.concurrent.Callable { block() }).get(30, TimeUnit.SECONDS)

    @Test fun happyPathInstallsAndClosesLegacyPermanently() {
        val db = open(); val p = prefs(); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val outcome = offMain { RustPortInstaller(repo, host, p).install(db) }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
        assertNotNull(repo.currentRustPortForOnboarding())
        try { repo.admitLegacyDispatch(); fail("legacy dispatch must stay rejected after install") }
        catch (expected: OwnerTransitionException) {}
    }

    @Test fun busyThenDrainThenSameInstallerRetryRepublishesAndSucceeds() {
        val db = open(); val p = prefs(); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installer = RustPortInstaller(repo, host, p)
        val guard = repo.admitLegacyDispatch()
        assertEquals(RustPortInstaller.Outcome.Busy, offMain { installer.install(db, maxAttempts = 2) })
        assertNull("contended install must not route", repo.currentRustPortForOnboarding())
        guard.close()
        // Same installer, later call: republishes from its owned token (epoch
        // advances), proving the retry never dies on a stale null expectation.
        assertEquals(RustPortInstaller.Outcome.Installed(2L), offMain { installer.install(db) })
        assertNotNull(repo.currentRustPortForOnboarding())
    }

    @Test fun identityMoveBetweenAttemptsRejectsRebindingAndFreshInstallerUsesNewOwnerDatabase() {
        val db = open(); val p = prefs("owner-a"); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installer = RustPortInstaller(repo, host, p)
        val guard = repo.admitLegacyDispatch()
        assertEquals(RustPortInstaller.Outcome.Busy, offMain { installer.install(db, maxAttempts = 1) })
        offMain { p.userId = "owner-b" } // identity moves while the installer owns a minted token
        guard.close()
        // The installer must NOT rebind owner-a's preopened database to the
        // new identity: the retry is refused as stale.
        assertEquals(RustPortInstaller.Outcome.StaleIdentity, offMain { installer.install(db) })
        assertNull(repo.currentRustPortForOnboarding())
        // Recovery: a fresh installer with the NEW owner's re-resolved
        // database installs under the fresh identity on THAT database.
        val db2 = open()
        val host2 = RustBoundRoomHost(p)
        val outcome = offMain { RustPortInstaller(repo, host2, p).install(db2) }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
        val port = repo.currentRustPortForOnboarding()!!
        assertEquals("owner-b", port.token.preference.operation.owner)
        assertTrue("routing uses the new owner's database, never the rebound old one", port.token.database === db2)
    }

    @Test fun secondInstallInvocationAfterSuccessReportsStaleIdentityWithoutDisturbingRouting() {
        val db = open(); val p = prefs(); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installer = RustPortInstaller(repo, host, p)
        assertEquals(RustPortInstaller.Outcome.Installed(1L), offMain { installer.install(db) })
        val installed = repo.currentRustPortForOnboarding()
        assertEquals(RustPortInstaller.Outcome.StaleIdentity, offMain { installer.install(db) })
        assertTrue("routing untouched by the redundant invocation", repo.currentRustPortForOnboarding() === installed)
    }

    @Test fun concurrentCompetingInstallersLeaveExactlyOneWinner() {
        val db = open(); val p = prefs(); val repo = CycleRepository(db)
        val hostA = RustBoundRoomHost(p); val hostB = RustBoundRoomHost(p)
        val a = RustPortInstaller(repo, hostA, p); val b = RustPortInstaller(repo, hostB, p)
        val gate = CountDownLatch(1)
        val fa = installerThreads.submit(java.util.concurrent.Callable { gate.await(); a.install(db) })
        val fb = installerThreads.submit(java.util.concurrent.Callable { gate.await(); b.install(db) })
        gate.countDown()
        val outcomes = listOf(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS))
        assertEquals("exactly one winner", 1, outcomes.count { it is RustPortInstaller.Outcome.Installed })
        val port = repo.currentRustPortForOnboarding()!!
        val winnerIsA = outcomes[0] is RustPortInstaller.Outcome.Installed
        assertTrue("routing belongs to the actual winner", (port.host === hostA) == winnerIsA)
    }

    @Test fun identityChangeAfterSealFailsClosedAndSealReleaseRestoresLegacy() {
        // Defense-in-depth witness for the installRustPort currency CHECK in
        // isolation (direct internal call without the installer's admission
        // exclusion). The end-to-end exclusion is the admission's contract.
        val db = open(); val p = prefs("owner-a"); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val token = (host.publish(null, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        assertTrue(repo.trySealLegacyDispatch())
        offMain { p.userId = "owner-b" } // deterministic identity change before the check
        try {
            repo.installRustPort(RustRepositoryPort(host, token))
            fail("stale port must not install")
        } catch (expected: IllegalStateException) {
            assertEquals("rust_port_not_current", expected.message)
        } finally {
            repo.releaseLegacyDispatchSeal()
        }
        assertNull(repo.currentRustPortForOnboarding())
        repo.admitLegacyDispatch().close() // legacy routing fully restored
    }

    @Test fun concurrentIdentityMoveDuringInstallYieldsCoherentOutcomeEitherWay() {
        // Race witness: the admission serializes the identity move against the
        // install's check+swap, so exactly two coherent outcomes exist. This
        // test accepts BOTH branches and asserts each is internally coherent;
        // it does not control which branch runs.
        val db = open(); val p = prefs("owner-a"); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installer = RustPortInstaller(repo, host, p)
        val gate = CountDownLatch(1)
        val installFuture = installerThreads.submit(java.util.concurrent.Callable { gate.await(); installer.install(db) })
        val moveFuture = installerThreads.submit(java.util.concurrent.Callable { gate.await(); p.userId = "owner-b" })
        gate.countDown()
        val outcome = installFuture.get(30, TimeUnit.SECONDS); moveFuture.get(30, TimeUnit.SECONDS)
        when (outcome) {
            is RustPortInstaller.Outcome.Installed -> {
                // Install won the admission race: routing is under owner-a and
                // the moved identity makes the port stale immediately after.
                val port = repo.currentRustPortForOnboarding()!!
                assertEquals("owner-a", port.token.preference.operation.owner)
                assertFalse("port is stale once the identity has moved", port.isCurrent())
            }
            RustPortInstaller.Outcome.StaleIdentity -> assertNull("no routing installed", repo.currentRustPortForOnboarding())
            else -> fail("unexpected outcome: $outcome")
        }
    }

    @Test fun observerChannelPublishesOnlyAfterRoutingInstalled() = runBlocking {
        val db = open(); val p = prefs(); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        // The initial-install host binds the SAME Room instance, so
        // activeDatabase never changes identity in this fixture; the revision
        // bump is the only post-install emission. Owner-transition database
        // swaps are replaceRustPort's path, outside this witness's scope.
        // StateFlow collectors never run on the publication thread here, so
        // reentrant replacement from a collector cannot interleave with the
        // swap; the replacingPort CAS is the structural reentrancy defense.
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<Boolean, Boolean>>())
        val firstReceived = CountDownLatch(1)
        val secondReceived = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            repo.activeReadSource.collect { value ->
                // (emitted the fixture database, routing installed at observation)
                seen += (value === db) to (repo.currentRustPortForOnboarding() != null)
                firstReceived.countDown()
                if (seen.size >= 2) secondReceived.countDown()
            }
        }
        assertTrue("collector observed the pre-install state", firstReceived.await(15, TimeUnit.SECONDS))
        assertEquals("initial emission: fixture database, no routing", true to false, seen.first())
        val outcome = offMain { RustPortInstaller(repo, host, p).install(db) }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
        assertTrue("revision emission arrived", secondReceived.await(15, TimeUnit.SECONDS))
        job.cancel()
        for ((sameDb, installed) in seen.drop(1)) {
            assertTrue("post-install emission keeps the bound database", sameDb)
            assertTrue("no observer emission precedes installed routing", installed)
        }
    }

    @Test fun installRefusesTheMainThread() {
        val db = open(); val p = prefs(); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        try {
            RustPortInstaller(repo, host, p).install(db)
            fail("install must refuse the main thread")
        } catch (expected: IllegalStateException) {
            assertEquals("rust_install_on_main_thread", expected.message)
        }
        assertNull(repo.currentRustPortForOnboarding())
    }
}
