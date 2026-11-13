package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.RustPortInstallResolver
import com.cyclecare.app.data.repository.RustPortInstaller
import com.cyclecare.app.data.repository.RustPortTransitioner
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-D connected-cutover witnesses for RustPortTransitioner: an installed
 * port stale after an owner/session move must be REPLACED through the real
 * admitted resolve/publish/replace protocol - never treated as success by
 * presence, never left to park the process until death. Safe refusal is
 * witnessed too: a mid-transition identity move aborts with the stale port
 * still selected (fail-closed). Tier: host routing evidence over plain
 * in-memory Room fixtures via the resolver provider seam (Robolectric; no
 * SQLCipher/Keystore, no native claims).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDTransitionWitnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-transition-it").apply { isDaemon = true } }

    @After fun cleanup() {
        RustPortInstallResolver.beforeActualOpenForTests = null
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
    }

    private fun prefs(owner: String = "owner-a"): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("stage-d-transition-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = owner; attachPublicationAdmission(IdentityPublicationAdmission.forAndroid()) }

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

    /** Fixture namespacing: one opened database per owner identity, standing
     * in for CycleCareDatabase.getDatabase owner namespacing. */
    private class FixtureNamespaces {
        val byOwner = mutableMapOf<String?, CycleCareDatabase>()
        fun dbFor(owner: String?, newDb: () -> CycleCareDatabase): CycleCareDatabase =
            byOwner.getOrPut(owner) { newDb() }
    }

    private fun transitioner(
        context: Context, prefs: AuthPreferences, repo: CycleRepository, ns: FixtureNamespaces,
    ) = RustPortTransitioner(context, prefs, repo) { c, p ->
        RustPortInstallResolver(c, p) { _, owner -> ns.dbFor(owner) { newDb() } }
    }

    private fun <T> offMain(block: () -> T): T =
        pool.submit(java.util.concurrent.Callable { block() }).get(30, TimeUnit.SECONDS)

    private fun install(h: Triple<CycleRepository, AuthPreferences, CycleCareDatabase>) {
        val outcome = offMain {
            RustPortInstaller(h.first, RustBoundRoomHost(h.second),
                RustPortInstallResolver.Resolution.bind(h.second, h.second.rustOperationSnapshot(), h.third)).install()
        }
        assertEquals(RustPortInstaller.Outcome.Installed(1L), outcome)
    }

    @Test fun noInstalledPortIsNotInstalledNotSuccess() {
        val p = prefs(); val repo = CycleRepository(newDb()); val ns = FixtureNamespaces()
        assertEquals(RustPortTransitioner.Outcome.NotInstalled,
            offMain { transitioner(context, p, repo, ns).ensureCurrentRouting() })
    }

    @Test fun currentPortIsLeftUntouched() {
        val p = prefs(); val db = newDb(); val repo = CycleRepository(db); val ns = FixtureNamespaces()
        install(Triple(repo, p, db))
        val before = repo.currentRustPortForOnboarding()
        assertEquals(RustPortTransitioner.Outcome.Current,
            offMain { transitioner(context, p, repo, ns).ensureCurrentRouting() })
        assertTrue("no replacement for a current port", repo.currentRustPortForOnboarding() === before)
    }

    @Test fun ownerMoveAfterInstallReplacesWithFreshlyResolvedRouting() {
        val p = prefs("owner-a"); val dbA = newDb(); val repo = CycleRepository(dbA); val ns = FixtureNamespaces()
        ns.byOwner["owner-a"] = dbA
        install(Triple(repo, p, dbA))
        offMain { p.userId = "owner-b" }
        assertFalse("installed owner-a port is stale after the move", repo.currentRustPortForOnboarding()!!.isCurrent())
        assertEquals(RustPortTransitioner.Outcome.Replaced,
            offMain { transitioner(context, p, repo, ns).ensureCurrentRouting() })
        val port = repo.currentRustPortForOnboarding()!!
        assertTrue("replaced port is current", port.isCurrent())
        assertEquals("owner-b", port.token.preference.operation.owner)
        assertTrue("routing moved to owner-b's database", port.token.database === ns.byOwner["owner-b"])
        assertTrue("never rebound to the old database", port.token.database !== dbA)
    }

    @Test fun sessionMoveAfterInstallIsAlsoRecovered() {
        val p = prefs("owner-a"); val dbA = newDb(); val repo = CycleRepository(dbA); val ns = FixtureNamespaces()
        ns.byOwner["owner-a"] = dbA
        install(Triple(repo, p, dbA))
        offMain { p.clearSession() } // session generation moves, owner stays
        assertFalse(repo.currentRustPortForOnboarding()!!.isCurrent())
        assertEquals(RustPortTransitioner.Outcome.Replaced,
            offMain { transitioner(context, p, repo, ns).ensureCurrentRouting() })
        assertTrue(repo.currentRustPortForOnboarding()!!.isCurrent())
    }

    @Test fun identityMoveDuringTransitionAbortsFailClosedAndLaterRetryRecovers() {
        val p = prefs("owner-a"); val dbA = newDb(); val repo = CycleRepository(dbA); val ns = FixtureNamespaces()
        ns.byOwner["owner-a"] = dbA
        install(Triple(repo, p, dbA))
        offMain { p.userId = "owner-b" }
        // Move identity AGAIN inside the resolver's opener barrier: the
        // post-open re-verification must catch it and refuse the binding.
        RustPortInstallResolver.beforeActualOpenForTests = { p.userId = "owner-c" }
        assertEquals(RustPortTransitioner.Outcome.StaleIdentity,
            offMain { transitioner(context, p, repo, ns).ensureCurrentRouting() })
        RustPortInstallResolver.beforeActualOpenForTests = null
        val stale = repo.currentRustPortForOnboarding()!!
        assertEquals("nothing rebound: the stale owner-a port stays selected (safe refusal)",
            "owner-a", stale.token.preference.operation.owner)
        assertFalse(stale.isCurrent())
        // A later pass with a settled identity recovers to owner-c.
        assertEquals(RustPortTransitioner.Outcome.Replaced,
            offMain { transitioner(context, p, repo, ns).ensureCurrentRouting() })
        assertEquals("owner-c", repo.currentRustPortForOnboarding()!!.token.preference.operation.owner)
    }
}
