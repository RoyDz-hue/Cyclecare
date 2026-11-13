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

    /** Mirrors the production builder: registered 9->10 migration AND the
     * onCreate callback running the same draft for fresh files. A fixture
     * without them would create v10 files MISSING the rust_* objects, which
     * the installer's schema gate exists to reject. */
    private fun mirrorBuilder(name: String) =
        Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
            })

    private fun open(): CycleCareDatabase {
        val name = "stage-c-install-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        return mirrorBuilder(name).build().also { handles += it }
    }

    /** GENUINE v9 file via the independent anchor class
     * (StageDV9AnchorDatabase: same entities, version=9) - never derived
     * from the current v10 generator. The schema witness suite asserts the
     * anchor hash/DDL equal the production bundle's; here we capture the
     * anchor hash and require the registered upgrade to preserve it. The
     * file is returned CLOSED and UNOPENED by any production Room instance,
     * so the installer path performs the actual registered upgrade itself. */
    private fun createGenuineV9File(name: String): String {
        names += name
        context.getDatabasePath(name).parentFile?.mkdirs()
        val anchor = Room.databaseBuilder(context, StageDV9AnchorDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val hash = anchor.openHelper.writableDatabase
            .query("SELECT identity_hash FROM room_master_table").use { c -> c.moveToNext(); c.getString(0) }
        anchor.close()
        return hash
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
        // Race SMOKE witness: the admission serializes the identity move
        // against the install's check+swap, so exactly two coherent outcomes
        // exist. This test accepts BOTH branches and asserts each is
        // internally coherent; it does not control which branch runs. It is
        // smoke coverage, NOT a deterministic post-check/pre-swap witness.
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
        // StateFlow collectors never run on the publication thread on THIS
        // dispatcher; the synchronous/Unconfined reentry case is covered by
        // synchronousObserverReentryDuringInstallPublicationIsDeferredNotInline.
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

    /** Publisher-phase markers recorded inside the reentrant callback at the
     * first post-install emission, proving WHERE the callback ran: on the
     * publisher thread, inside the admitted action drain, after the routing
     * swap (publication phase). combine+Unconfined delivery alone is not
     * proof of any of these; each is asserted explicitly. */
    private data class ReentryMarkers(
        val threadName: String,
        val onDrain: Boolean,
        val routingInstalled: Boolean,
        val ownerRead: String?,
    )

    /** Shared reentrancy probe: an Unconfined collector on the real
     * combine(activeDatabase, rustPortRevision) flow skips the initial
     * emission, then at the first post-install emission calls a RAW identity
     * setter and records the publisher-phase markers. [install] runs off
     * main on the pool (thread name prefix "stage-c-install-it"). Returns the
     * event log, the marker list, and [install]'s result. */
    private fun <T> reentryProbe(p: AuthPreferences, repo: CycleRepository, install: () -> T): Triple<List<String>, List<ReentryMarkers>, T> = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val markers = java.util.Collections.synchronizedList(mutableListOf<ReentryMarkers>())
        val reentered = CountDownLatch(1)
        val job = launch(Dispatchers.Unconfined) {
            repo.activeReadSource.collect {
                if (events.isEmpty()) { events += "initial"; return@collect }
                p.userId = "owner-b" // raw identity setter: the reentry under test
                markers += ReentryMarkers(
                    threadName = Thread.currentThread().name,
                    onDrain = p.publicationAdmission!!.isOnDrain(),
                    routingInstalled = repo.currentRustPortForOnboarding() != null,
                    ownerRead = p.userId,
                )
                events += "reentered"
                reentered.countDown()
            }
        }
        val result = offMain { install() }
        assertTrue("observer never reentered during publication", reentered.await(15, TimeUnit.SECONDS))
        job.cancel()
        assertEquals(listOf("initial", "reentered"), events)
        assertEquals("exactly one post-install emission", 1, markers.size)
        Triple(events, markers, result)
    }

    private fun assertOnPublisherStackInPublicationPhase(m: ReentryMarkers) {
        assertTrue("callback on the publisher thread, got ${m.threadName}", m.threadName.startsWith("stage-c-install-it"))
        assertTrue("callback inside the admitted action drain, not after it", m.onDrain)
        assertTrue("callback in the publication phase: routing already swapped", m.routingInstalled)
    }

    @Test fun synchronousObserverReentryDuringInstallPublicationIsDeferredNotInline() {
        // Deterministic reentrancy control for the installer's
        // admission.callbacks publication boundary: the demoted stack must
        // make the reentrant raw setter QUEUE behind the install job - never
        // mutate identity inline during the routing swap. Green ONLY under
        // the intended semantics: inline mutation reads owner-b inside the
        // callback; a lost mutation leaves owner-a after the drain; a
        // vacuous non-inline delivery fails the publisher-phase markers.
        val db = open(); val p = prefs("owner-a"); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installer = RustPortInstaller(repo, host, p, db, "owner-a")
        val (_, markers, outcome) = reentryProbe(p, repo) { installer.install() }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
        val m = markers.single()
        assertOnPublisherStackInPublicationPhase(m)
        assertEquals("no inline identity mutation during the swap", "owner-a", m.ownerRead)
        // Deferred, not lost: once the admission drains, the queued identity
        // move applies and the freshly installed owner-a routing is stale.
        p.publicationAdmission!!.awaitIdle().join()
        assertEquals("owner-b", p.userId)
        assertFalse("deferred identity move stales the installed routing",
            repo.currentRustPortForOnboarding()!!.isCurrent())
    }

    @Test fun negativeControlWithoutCallbacksBoundaryReentrantSetterMutatesInline() {
        // NEGATIVE CONTROL for the test above: the SAME probe, but
        // installRustPort runs inside the admission action WITHOUT the
        // callbacks demotion (direct internal call, seal held manually). The
        // reentrant setter inherits transition privilege and must mutate
        // INLINE: if this control could not detect inline mutation, the
        // positive test's deferral assertion would be vacuous.
        val db = open(); val p = prefs("owner-a"); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installWithoutBoundary: () -> Boolean = {
            val snap = p.rustOperationSnapshot()
            val token = (host.publish(null, db, snap) as RustBoundRoomHost.Result.Value).value
            p.mutatePublicationIdentityIfCurrent(snap) {
                if (repo.trySealLegacyDispatch()) {
                    try { repo.installRustPort(RustRepositoryPort(host, token)) }
                    finally { repo.releaseLegacyDispatchSeal() }
                }
            }
        }
        val (_, markers, committed) = reentryProbe(p, repo, installWithoutBoundary)
        assertTrue(committed)
        val m = markers.single()
        assertOnPublisherStackInPublicationPhase(m)
        assertEquals("inline mutation must be DETECTED without the boundary", "owner-b", m.ownerRead)
    }

    @Test fun genuineV9FileUpgradesThroughRegisteredPathDuringInstall() {
        // The install path itself forces the actual Room open (schema gate),
        // so the registered 9->10 upgrade runs THERE, not in fixture setup:
        // the resolver/installer see the post-upgrade shape on first touch.
        val name = "stage-c-install-${UUID.randomUUID()}"
        val hash = createGenuineV9File(name)
        val db = mirrorBuilder(name).build().also { handles += it }
        val p = prefs(); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val outcome = offMain { RustPortInstaller(repo, host, RustPortInstallResolver.Resolution.bind(p, p.rustOperationSnapshot(), db)).install() }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
        val s = db.openHelper.writableDatabase
        s.query("PRAGMA user_version").use { c -> c.moveToNext(); assertEquals(10L, c.getLong(0)) }
        s.query("SELECT identity_hash FROM room_master_table").use { c ->
            c.moveToNext(); assertEquals("upgrade preserved the Room identity hash", hash, c.getString(0)) }
        assertNotNull(repo.currentRustPortForOnboarding())
    }

    @Test fun identityMoveAfterBindingIsCaughtAfterTheActualOpen() {
        // ORDER witness (reviewer issue 3): bind the resolution while the
        // Room instance is still UNOPENED (lazy), move the identity, then
        // install. The installer forces the actual (slow) open at the schema
        // gate FIRST and re-verifies stillCurrent AFTER it, so the moved
        // identity is rejected post-open as StaleIdentity - never published.
        val db = open(); val p = prefs("owner-a"); val repo = CycleRepository(db)
        val host = RustBoundRoomHost(p)
        val installer = RustPortInstaller(repo, host, RustPortInstallResolver.Resolution.bind(p, p.rustOperationSnapshot(), db))
        offMain { p.userId = "owner-b" } // identity moves before the actual open
        assertEquals(RustPortInstaller.Outcome.StaleIdentity, offMain { installer.install() })
        assertNull(repo.currentRustPortForOnboarding())
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
