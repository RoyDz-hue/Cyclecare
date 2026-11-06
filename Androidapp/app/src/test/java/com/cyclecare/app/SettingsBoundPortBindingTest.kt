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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage A binding-parity tests for CycleRepository.updateSettingsBound's
 * selected-port branch (boundPort + requireExactTokenPort + commitLease).
 *
 * Tier: tier-agnostic host routing evidence. The port is a real
 * RustBoundRoomHost over a plain temporary Room database (not SQLCipher/
 * Keystore, no admission installed). These tests prove the REPOSITORY routing
 * and lease behavior; they do not prove native decided the writes (the
 * profile-sync decision inside the host falls back or uses loaded candidate
 * bytes with on-disk provenance only), and they say nothing about the
 * production SQLCipher/prefs-bound-session path, which stays dormant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBoundPortBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()

    private fun open(): CycleCareDatabase {
        val name = "stage-a-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        return Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it; applyDraftMetadata(it) }
    }

    /** The host write path needs the RUST_FLOAT_METADATA_DRAFT auxiliary tables.
     * That migration is deliberately UNREGISTERED source preparation (DB stays
     * version 9), so this fixture applies its exact DDL by hand. This does NOT
     * register or validate the draft migration and changes no production source. */
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
        AuthPreferences(context, context.getSharedPreferences("stage-a-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = "owner-a" }

    private data class Ported(val repo: CycleRepository, val host: RustBoundRoomHost, val port: RustRepositoryPort, val db: CycleCareDatabase)

    private fun ported(p: AuthPreferences, db: CycleCareDatabase): Ported {
        val host = RustBoundRoomHost(p)
        val captured = p.rustOperationSnapshot()
        val token = (host.publish(null, db, captured) as RustBoundRoomHost.Result.Value).value
        val port = RustRepositoryPort(host, token)
        return Ported(CycleRepository(db, port), host, port, db)
    }

    /** Replacement candidate on a SEPARATE host so a rejected replacement never
     * disturbs the incumbent host's active token. */
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

    @Test fun currentMintedTokenSucceedsAndReturnsCapturedContext() = runBlocking {
        val p = prefs(); val db = open()
        db.onboardingDao().insertOrUpdateProfile(profile("minted"))
        val (repo, _, _, _) = ported(p, db)
        val token = repo.beginOperation()
        val update = repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33, periodDuration = 6), 28, token)
        assertTrue("result context must be the token's CAPTURED instance, not a post-commit fresh read", update.context === token.context)
        assertEquals(33, db.cycleDao().getSettings()?.averageCycleLength)
        assertEquals(33, db.onboardingDao().getProfile()?.cycleLengthDays)
        assertEquals("33 days", db.onboardingDao().getProfile()?.cycleLengthOption)
        assertEquals("minted", db.onboardingDao().getProfile()?.selectedGoalsCsv)
        Unit
    }

    @Test fun explicitLegacyTokenFailsClosedAndWritesNothing() = runBlocking {
        val p = prefs(); val db = open()
        db.onboardingDao().insertOrUpdateProfile(profile("legacy"))
        val repo = ported(p, db).repo
        // A legacy token names the same identity/session but was never minted
        // for the selected port. It must be rejected, never upgraded.
        val legacy = CycleRepository.OperationToken.legacy(repo.currentContext(), 0L, 0L)
        assertThrows(OwnerTransitionException::class.java) {
            runBlocking { repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28, legacy) }
        }
        assertNull(db.cycleDao().getSettings())
        assertEquals(28, db.onboardingDao().getProfile()?.cycleLengthDays)
        Unit
    }

    @Test fun sameIdentitySupersededPortTokenFailsClosedAndWritesNothing() = runBlocking {
        val p = prefs(); val db = open()
        db.onboardingDao().insertOrUpdateProfile(profile("superseded"))
        val (repo, host, _, _) = ported(p, db)
        val staleToken = repo.beginOperation()
        // Same identity, same database: the host publishes a successor token and
        // the repository swaps to a port bound to it. The older minted token
        // must not be served by the new port.
        val successor = (host.publish(repoPortToken(repo), db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        repo.replaceRustPort(RustRepositoryPort(host, successor))
        assertThrows(OwnerTransitionException::class.java) {
            runBlocking { repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28, staleToken) }
        }
        assertNull(db.cycleDao().getSettings())
        assertEquals(28, db.onboardingDao().getProfile()?.cycleLengthDays)
        Unit
    }

    private fun repoPortToken(repo: CycleRepository): RustBoundRoomHost.Token =
        repo.currentRustPortForOnboarding()!!.token

    @Test fun replacementAfterSelectionBeforeDispatchFailsClosed() = runBlocking {
        val p = prefs(); val db = open()
        db.onboardingDao().insertOrUpdateProfile(profile("race"))
        val repo = ported(p, db).repo
        val token = repo.beginOperation()
        val (portB, dbB) = replacementPort(p)
        repo.beforeBoundPortDispatchForTests = { repo.replaceRustPort(portB) }
        try {
            assertThrows(RustRepositoryPort.Failure::class.java) {
                runBlocking { repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28, token) }
            }
        } finally { repo.beforeBoundPortDispatchForTests = null }
        // Fail-closed: the commit lease refused dispatch under the swapped port;
        // neither database received the write.
        assertNull(db.cycleDao().getSettings())
        assertNull(dbB.cycleDao().getSettings())
        assertEquals(28, db.onboardingDao().getProfile()?.cycleLengthDays)
        Unit
    }

    @Test fun replacementDuringAdmittedCommitIsRejectedAndOriginalPortRetained() = runBlocking {
        val p = prefs(); val db = open()
        db.onboardingDao().insertOrUpdateProfile(profile("inflight"))
        val (repo, hostA, _, _) = ported(p, db)
        val token = repo.beginOperation()
        val (portC, dbC) = replacementPort(p)
        hostA.beforeGuardedTransactionForTests = { repo.replaceRustPort(portC) }
        try {
            assertThrows(RustRepositoryPort.Failure::class.java) {
                runBlocking { repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28, token) }
            }
        } finally { hostA.beforeGuardedTransactionForTests = null }
        // The routing lease rejected the mid-commit replacement; the original
        // port is still the routed authority and can serve a fresh minted token.
        assertNull(dbC.cycleDao().getSettings())
        val fresh = repo.beginOperation()
        val update = repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 35), 28, fresh)
        assertTrue(update.context === fresh.context)
        assertEquals(35, db.cycleDao().getSettings()?.averageCycleLength)
        Unit
    }
}
