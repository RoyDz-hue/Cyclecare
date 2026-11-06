package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.OwnerTransitionException
import com.cyclecare.app.security.AuthPreferences
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B3 binding-parity tests for CycleRepository.completeOnboardingWithProfile's
 * selected-port branch (boundPort + requireExactTokenPort + commitLease +
 * captured context). Tier: tier-agnostic host routing evidence over plain
 * temporary Room DBs (Robolectric; no SQLCipher/Keystore, no admission).
 * Proves repository routing/lease behavior ONLY. Finish PIN/consent prefs
 * happen BEFORE the repository call and deliberately persist on DB failure:
 * any "zero" claim below is scoped to repository DB ROWS, never an atomic
 * PIN+Finish claim. Cancellation after a synchronous commit may lose the
 * waiter; these tests make no zero-commit promise under cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinishPortBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()

    private fun open(): CycleCareDatabase {
        val name = "b3-${UUID.randomUUID()}".also { names += it }
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

    private fun prefs(): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("b3-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = "owner-a" }

    private data class Ported(val repo: CycleRepository, val host: RustBoundRoomHost, val port: RustRepositoryPort, val db: CycleCareDatabase)

    private fun ported(p: AuthPreferences, db: CycleCareDatabase): Ported {
        val host = RustBoundRoomHost(p)
        val token = (host.publish(null, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val port = RustRepositoryPort(host, token)
        return Ported(CycleRepository(db, port), host, port, db)
    }

    /** Replacement candidate on a SEPARATE host so a rejected/blocked
     * replacement never disturbs the incumbent host's active token. */
    private fun replacementPort(p: AuthPreferences): Pair<RustRepositoryPort, CycleCareDatabase> {
        val db = open()
        val host = RustBoundRoomHost(p)
        val token = (host.publish(null, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        return RustRepositoryPort(host, token) to db
    }

    private fun profile(goals: String) = OnboardingProfile(
        id = 1, isCompleted = true, completedAtTimestamp = 1L, isAgeVerified = true, isTermsAgreed = true,
        accountMode = "PRIVATE", selectedGoalsCsv = goals, lastPeriodDateString = "2026-10-05",
        approximatePeriodChoice = "EXACT", cycleLengthOption = "28", cycleLengthDays = 28, periodDurationDays = 5
    )

    @Test fun mintedPostChoiceTokenSucceedsReturnsCapturedContextAndProjectsSevenFields() = runBlocking {
        val p = prefs(); val db = open()
        db.cycleDao().saveSettings(CycleSettings(id = 1, lutealPhaseDays = 16))
        val (repo, _, _, _) = ported(p, db)
        val token = repo.beginOperation()
        val result = repo.completeOnboardingWithProfile(profile("minted"), enableLock = true, token = token)
        assertTrue("CAPTURED context instance, not a post-commit fresh read", result.context === token.context)
        val settings = db.cycleDao().getSettings()!!
        assertTrue(settings.isOnboardingCompleted)
        assertEquals("minted", settings.selectedGoalsCsv)
        assertEquals("2026-10-05", settings.lastPeriodStartDate)
        assertEquals(28, settings.averageCycleLength)
        assertEquals(5, settings.periodDuration)
        assertEquals(false, settings.remindFertile)
        assertTrue(settings.isAppLockEnabled)
        assertEquals("unrelated pre-existing fields preserved by the projection", 16, settings.lutealPhaseDays)
        assertEquals("minted", db.onboardingDao().getProfile()?.selectedGoalsCsv)
        // Seed policy RETAINED: same-port-current commit seeds the cache.
        val seeded = repo.getStoredPeriodDates()!!
        assertEquals(java.time.LocalDate.parse("2026-10-05"), seeded.period_start_date)
        assertEquals(java.time.LocalDate.parse("2026-10-09"), seeded.period_end_date)
        Unit
    }

    @Test fun nullTokenWithInstalledPortMintsAndSucceeds() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db).repo
        val result = repo.completeOnboardingWithProfile(profile("null-token"), enableLock = false)
        assertEquals("owner-a", result.context.owner)
        assertTrue(result.context.database === db)
        assertTrue(db.cycleDao().getSettings()!!.isOnboardingCompleted)
        assertNotNull(repo.getStoredPeriodDates())
        Unit
    }

    @Test fun postCommitPreCacheSeparateHostReplacementLeavesCacheCleared() = runBlocking {
        val p = prefs(); val db = open()
        val (repo, _, _, _) = ported(p, db)
        val token = repo.beginOperation()
        val (portB, _) = replacementPort(p)
        // Deterministic post-commit/pre-seed replacement on a SEPARATE host: the
        // old host token REMAINS current (isCurrent()==true), so only the
        // routingLock-guarded selection+context check can stop the reseed.
        repo.afterPortCommitBeforeCacheSeedForTests = { repo.replaceRustPort(portB) }
        try {
            repo.completeOnboardingWithProfile(profile("raced"), token = token)
        } finally { repo.afterPortCommitBeforeCacheSeedForTests = null }
        assertEquals("commit landed on the original database", "raced", db.onboardingDao().getProfile()?.selectedGoalsCsv)
        assertNull("replacement cleared the cache; the stale continuation must NOT reseed old dates", repo.getStoredPeriodDates())
        Unit
    }

    @Test fun explicitLegacyTokenFailsClosedRowScopedZero() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db).repo
        val legacy = CycleRepository.OperationToken.legacy(repo.currentContext(), 0L, 0L)
        assertThrows(OwnerTransitionException::class.java) {
            runBlocking { repo.completeOnboardingWithProfile(profile("legacy"), token = legacy) }
        }
        // Row-scoped zero: no profile row, no settings row (PIN prefs are
        // upstream of the repository by design and out of this claim).
        assertNull(db.onboardingDao().getProfile())
        assertNull(db.cycleDao().getSettings())
        Unit
    }

    @Test fun preChoiceTokenFailsClosedAfterStateChange() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db).repo
        val token = repo.beginOperation()
        // The choice lands after the token was minted: the port's own guard
        // (snapshot vs live prefs) no longer vouches for it.
        p.clearSession()
        assertThrows(OwnerTransitionException::class.java) {
            runBlocking { repo.completeOnboardingWithProfile(profile("prechoice"), token = token) }
        }
        assertNull(db.onboardingDao().getProfile())
        assertNull(db.cycleDao().getSettings())
        Unit
    }

    @Test fun sameIdentitySupersededPortTokenFailsClosedRowScopedZero() = runBlocking {
        val p = prefs(); val db = open()
        val (repo, host, _, _) = ported(p, db)
        val staleToken = repo.beginOperation()
        val successor = (host.publish(repo.currentRustPortForOnboarding()!!.token, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        repo.replaceRustPort(RustRepositoryPort(host, successor))
        assertThrows(OwnerTransitionException::class.java) {
            runBlocking { repo.completeOnboardingWithProfile(profile("superseded"), token = staleToken) }
        }
        assertNull(db.onboardingDao().getProfile())
        assertNull(db.cycleDao().getSettings())
        Unit
    }

    @Test fun replacementAfterSelectionBeforeDispatchFailsClosed() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db).repo
        val token = repo.beginOperation()
        val (portB, dbB) = replacementPort(p)
        repo.beforeBoundPortDispatchForTests = { repo.replaceRustPort(portB) }
        try {
            assertThrows(RustRepositoryPort.Failure::class.java) {
                runBlocking { repo.completeOnboardingWithProfile(profile("race"), token = token) }
            }
        } finally { repo.beforeBoundPortDispatchForTests = null }
        // The commit lease refused dispatch under the swapped port; row-scoped
        // zero on BOTH databases.
        assertNull(db.onboardingDao().getProfile())
        assertNull(db.cycleDao().getSettings())
        assertNull(dbB.onboardingDao().getProfile())
        assertNull(dbB.cycleDao().getSettings())
        Unit
    }

    @Test fun reentrantReplacementDuringAdmittedCommitRejectedOriginalRetained() = runBlocking {
        val p = prefs(); val db = open()
        db.onboardingDao().insertOrUpdateProfile(profile("old"))
        val (repo, hostA, _, _) = ported(p, db)
        val token = repo.beginOperation()
        val (portC, dbC) = replacementPort(p)
        val profileBefore = db.onboardingDao().getProfile()
        // The probe fires inside the admitted lease, PRE-SQL (admitted pre-
        // transaction, per the M11/M12 verdict wording).
        hostA.beforeGuardedTransactionForTests = { repo.replaceRustPort(portC) }
        try {
            assertThrows(RustRepositoryPort.Failure::class.java) {
                runBlocking { repo.completeOnboardingWithProfile(profile("inflight"), token = token) }
            }
        } finally { hostA.beforeGuardedTransactionForTests = null }
        assertNull("replacement's database untouched (row-scoped)", dbC.onboardingDao().getProfile())
        assertEquals("full-row rollback equality, not just presence checks", profileBefore, db.onboardingDao().getProfile())
        assertNull(db.cycleDao().getSettings())
        assertNull("no cache seed from the failed commit", repo.getStoredPeriodDates())
        // Same-thread reentrant replacement is REJECTED by the lease count; the
        // original port is still routed and serves a fresh minted token.
        val fresh = repo.beginOperation()
        repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 35), 28, fresh)
        assertEquals(35, db.cycleDao().getSettings()?.averageCycleLength)
        Unit
    }

    @Test fun crossThreadReplacementBlocksThenSucceedsAfterCommit() = runBlocking {
        val p = prefs(); val db = open()
        val (repo, hostA, _, _) = ported(p, db)
        val token = repo.beginOperation()
        val (portC, dbC) = replacementPort(p)
        val done = CountDownLatch(1)
        hostA.beforeGuardedTransactionForTests = {
            // Competitor-start witness: the replacement thread is live and at the
            // replaceRustPort call before the commit's SQL runs (the probe waits
            // only for thread START, never for replacement completion - the
            // commit must not wait on the competitor while the lease is held).
            // The BLOCK itself is inferred from the monitor structure, not
            // observed.
            val competitorLive = CountDownLatch(1)
            thread(start = true) { competitorLive.countDown(); try { repo.replaceRustPort(portC) } finally { done.countDown() } }
            assertTrue(competitorLive.await(15, TimeUnit.SECONDS))
        }
        try {
            repo.completeOnboardingWithProfile(profile("committed"), enableLock = false, token = token)
        } finally { hostA.beforeGuardedTransactionForTests = null }
        assertTrue("replacement completed after the commit released the monitors", done.await(15, TimeUnit.SECONDS))
        // Commit landed fully on the ORIGINAL port's database...
        assertEquals("committed", db.onboardingDao().getProfile()?.selectedGoalsCsv)
        assertTrue(db.cycleDao().getSettings()!!.isOnboardingCompleted)
        assertNull("replacement database has no commit rows (row-scoped)", dbC.onboardingDao().getProfile())
        // ...and routing moved to the replacement afterwards.
        val fresh = repo.beginOperation()
        repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 35), 28, fresh)
        assertEquals(35, dbC.cycleDao().getSettings()?.averageCycleLength)
        Unit
    }
}
