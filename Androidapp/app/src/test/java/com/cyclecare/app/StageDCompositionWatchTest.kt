package com.cyclecare.app

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.repository.CycleCareComposition
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.data.repository.RustPortTransitioner
import com.cyclecare.app.security.AuthPreferences
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Stage-D composition watch witnesses (review round 4/5). The production
 * watch/holder/recovery run through the construction seams (the host cannot
 * run MasterKey/SQLCipher construction); fixtures return UNATTACHED prefs
 * so the production Holder performs the exact-instance admission attach.
 *
 * 1. PEEK-GUARD: in a prefs-only process an identity transition must NOT
 *    initialize the health store and must NOT run any recovery pass. The
 *    repository seam is a DEFERRED provider, so this test would fail if the
 *    seam itself (or the watch) initialized the repository.
 * 2. SNAPSHOT-DEDUP / SELF-FEED with REAL admitted mutations: the fake pass
 *    performs prefs.mutatePublicationIdentityIfCurrent(snapshot){ no-op } -
 *    the exact admitted, identity-unchanging transition the real
 *    replacement commit runs - whose finally notifies every listener. A
 *    feeding watch would request again; the deduped watch must not. (The
 *    previous isSoundEnabled toggle never notified at all: raw prefs.edit.)
 * 3. REAL HELD LEASE: the actual RustPortTransitioner + repository commit
 *    lease through the production watch. Busy is observed while the lease
 *    holds (bounded retry, no spin), the stale port stays selected, and
 *    releasing the lease lets the next pass replace the port (Replaced)
 *    without any new identity event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDCompositionWatchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-watch-it").apply { isDaemon = true } }

    @After fun cleanup() {
        CycleCareComposition.resetForTests()
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
    }

    /** UNATTACHED on purpose: CycleCareComposition.current attaches the
     * admission (AuthPreferences enforces null-or-same-instance). */
    private fun makePrefs(tag: String): AuthPreferences = AuthPreferences(
        context,
        context.getSharedPreferences("stage-d-watch-$tag-${UUID.randomUUID()}", Context.MODE_PRIVATE),
        true
    ).apply {
        userId = "owner-a"
        accountMode = "PRIVATE"
        isCloudSyncAllowed = false
        isAiSharingAllowed = false
    }

    private fun newDb(): CycleCareDatabase =
        Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries()
            .addMigrations(com.cyclecare.app.data.local.rust.RUST_FLOAT_METADATA_DRAFT)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    com.cyclecare.app.data.local.rust.applyRustMetadataDraft(db)
                }
            })
            .build().also(openDbs::add)

    private fun pumpMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    /** Bounded wait that PUMPS the Robolectric main looper on every
     * iteration: the watch listener and every transition notification are
     * delivered as queued main jobs, so a wait that never pumps can stall
     * behind its own un-delivered notifications. */
    private fun awaitCondition(what: String, timeoutMs: Long = 15000, probe: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pumpMain()
            if (probe()) return@runBlocking
            delay(25)
        }
        fail("$what not observed within ${timeoutMs}ms")
    }

    @Test fun prefsOnlyProcessTransitionsNeverInitializeTheHealthStore() {
        CycleCareComposition.resetForTests()
        val prefs = makePrefs("peek")
        val passes = AtomicInteger(0)
        CycleCareComposition.authPreferencesFactoryForTests = { prefs }
        // Deferred repo provider: installing this seam must NOT initialize
        // anything; the pass lambda must never run either.
        CycleCareComposition.runTransitionFactoryForTests = { _, _, _ ->
            { passes.incrementAndGet(); RustPortTransitioner.Outcome.Current }
        }
        val holder = CycleCareComposition.current(context)
        assertNull("no repository before any entry needs one", holder.peekRepository())

        prefs.userId = "owner-b"
        pumpMain()
        Thread.sleep(300)

        assertNull("identity transition must NOT initialize the health store", holder.peekRepository())
        assertEquals("nothing installed means no recovery pass", 0, passes.get())
        assertEquals("nothing installed means nothing to recover", 0, holder.recovery!!.roundsRun)
    }

    @Test fun watchRequestsOnlyOnSnapshotChangeAndRealAdmittedNoIdentityMutationsDoNotRefed() {
        CycleCareComposition.resetForTests()
        val prefs = makePrefs("dedup")
        val repo = CycleRepository(newDb())
        val passes = AtomicInteger(0)
        CycleCareComposition.authPreferencesFactoryForTests = { prefs }
        CycleCareComposition.repositoryFactoryForTests = { _, _ -> repo }
        CycleCareComposition.runTransitionFactoryForTests = { _, _, _ ->
            {
                // The exact admitted, identity-UNCHANGING transition the
                // replacement commit runs: admission notifies every listener
                // in its finally, so this pass DOES notify the watch.
                prefs.mutatePublicationIdentityIfCurrent(prefs.rustOperationSnapshot()) { }
                passes.incrementAndGet()
                RustPortTransitioner.Outcome.Current
            }
        }
        val holder = CycleCareComposition.current(context)
        // The deferred seam does NOT initialize anything by itself: explicitly
        // initialize through the holder and confirm the SAME repository.
        assertSame("holder must expose the seam-provided repository", repo, holder.repository)
        assertNotNull(holder.peekRepository())

        // Identity change -> snapshot CHANGED -> exactly one recovery pass.
        prefs.userId = "owner-b"
        pumpMain()
        awaitCondition("first recovery pass") { passes.get() >= 1 }
        // Deliver the notification the pass's own admitted no-identity
        // mutation posted. A feeding watch would request again here.
        pumpMain()
        Thread.sleep(400)
        assertEquals("recovery-internal admitted no-identity mutation must NOT re-request", 1, passes.get())

        // The same admitted no-identity mutation from OUTSIDE also requests
        // nothing. It must run OFF-MAIN: forAndroid() sets cannotBlock on the
        // main looper and mutateResult REJECTS main callers (returns false,
        // no transition, no notification), so a main-thread call would be
        // vacuous coverage. The pool call genuinely admits and notifies.
        pool.submit(Callable {
            prefs.mutatePublicationIdentityIfCurrent(prefs.rustOperationSnapshot()) { }
        }).get(30, TimeUnit.SECONDS)
        pumpMain()
        Thread.sleep(400)
        assertEquals("external admitted no-identity mutation must NOT request", 1, passes.get())

        // A real identity change still requests.
        prefs.userId = "owner-c"
        pumpMain()
        awaitCondition("second recovery pass") { passes.get() >= 2 }
        assertEquals(2, passes.get())
        assertEquals(RustPortTransitioner.Outcome.Current, holder.recovery!!.lastOutcome)
    }

    @Test fun realHeldCommitLeaseBoundsRecoveryAndReleaseReplaces() {
        CycleCareComposition.resetForTests()
        val prefs = makePrefs("lease")
        val dbA = newDb()
        val repo = CycleRepository(dbA)
        val byOwner = mutableMapOf<String?, CycleCareDatabase>("owner-a" to dbA)
        // Factories FIRST, then the Holder attaches the admission; only then
        // may anything install (an install against unattached prefs fails
        // closed with rust_install_without_admission).
        CycleCareComposition.authPreferencesFactoryForTests = { prefs }
        CycleCareComposition.repositoryFactoryForTests = { _, _ -> repo }
        val transitioner = RustPortTransitioner(context, prefs, repo) { c, p ->
            RustPortInstallResolver(c, p) { _, owner -> byOwner.getOrPut(owner) { newDb() } }
        }
        CycleCareComposition.runTransitionFactoryForTests = { _, _, _ ->
            { transitioner.ensureCurrentRouting() }
        }
        val holder = CycleCareComposition.current(context)
        assertSame("holder must expose the seam-provided repository", repo, holder.repository)
        // Real install for owner-a (same machinery as the installer witness),
        // now under the Holder-owned admission.
        val installOutcome = pool.submit(Callable {
            RustPortInstaller(repo, RustBoundRoomHost(prefs),
                RustPortInstallResolver.Resolution.bind(prefs, prefs.rustOperationSnapshot(), dbA)).install()
        }).get(30, TimeUnit.SECONDS)
        assertEquals(RustPortInstaller.Outcome.Installed(1L), installOutcome)
        // The install's own notifications must not leave the watch mid-state
        // for the measured phase: drain, then re-baseline the watched snapshot
        // by letting the watch observe the post-install snapshot.
        pumpMain()
        awaitCondition("post-install watch drained") { holder.recovery!!.let { !it.recoveryInFlight } }

        // Hold the REAL repository commit lease, then move the owner through
        // the production watch.
        val lease = repo.holdRoutingLeaseForTests()
        pool.submit(Callable { prefs.userId = "owner-b" }).get(30, TimeUnit.SECONDS)
        pumpMain()

        // Busy is OBSERVED through the production watch while the lease
        // holds; the stale port stays selected; bounded delay means no spin.
        awaitCondition("Busy observed through the production watch") {
            holder.recovery!!.lastOutcome is RustPortTransitioner.Outcome.Busy
        }
        assertTrue("recovery in flight while the lease is held", holder.recovery!!.recoveryInFlight)
        assertTrue("stale port still selected while Busy", repo.currentRustPortForOnboarding()?.isCurrent() == false)
        val passesWhileHeld = holder.recovery!!.roundsRun
        Thread.sleep(500)
        assertEquals("no spinning while Busy (bounded retry delay)", passesWhileHeld, holder.recovery!!.roundsRun)

        // Release the REAL lease: the next pass replaces without any new
        // identity event - that is the whole point of the owned retry.
        lease.close()
        awaitCondition("Replaced after lease release") {
            holder.recovery!!.lastOutcome is RustPortTransitioner.Outcome.Replaced
        }
        awaitCondition("recovery settled") { !holder.recovery!!.recoveryInFlight }
        val port = repo.currentRustPortForOnboarding()!!
        assertTrue(port.isCurrent())
        assertEquals("owner-b", port.token.preference.operation.owner)
        assertTrue("rounds stay far under the hard budget", holder.recovery!!.roundsRun <= 5)
        pumpMain()
        Thread.sleep(300)
        assertEquals("settled recovery is not re-fed by its own commit notifications",
            RustPortTransitioner.Outcome.Replaced, holder.recovery!!.lastOutcome)
    }
}
