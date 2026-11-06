package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage B legacy-dispatch registration tests (admitLegacyDispatch registry +
 * seal+reject-if-nonzero bounded seam). Tier: tier-agnostic host routing
 * evidence over plain temporary Room DBs (Robolectric; no SQLCipher/Keystore,
 * no admission). Proves repository registration/seal ordering ONLY - no native
 * claims, no production prefs-bound-session claims, no install wiring (the
 * trySeal/release seam has no production caller; Stage C is unapproved).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDispatchRegistrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()

    private fun open(): CycleCareDatabase {
        val name = "stage-b-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        return Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it }
    }

    @After fun cleanup() { handles.forEach { if (it.isOpen) it.close() }; names.forEach { context.deleteDatabase(it) } }

    private fun prefs(): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("stage-b-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { userId = "owner-a" }

    /** Same draft-DDL fixture as SettingsBoundPortBindingTest: applies the
     * UNREGISTERED RUST_FLOAT_METADATA_DRAFT tables by hand; registers nothing. */
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

    private fun ported(p: AuthPreferences, db: CycleCareDatabase): CycleRepository {
        applyDraftMetadata(db)
        val host = RustBoundRoomHost(p)
        val token = (host.publish(null, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        return CycleRepository(db, RustRepositoryPort(host, token))
    }

    private fun log(date: String, flow: String = "") = CycleDayLog(dateString = date, flowLevel = flow)

    @Test fun sealedGateRejectsSettingsCreatorWipeAndUnboundWriteWithZeroEffects() = runBlocking {
        val db = open()
        val repo = CycleRepository(db)
        assertTrue(repo.trySealLegacyDispatch())
        try {
            // settingsFor CREATEs+journals a default row: under the seal it must
            // fail closed and create nothing.
            assertThrows(OwnerTransitionException::class.java) { runBlocking { repo.getSettings() } }
            assertNull("rejected settings-creator must leave no row", db.cycleDao().getSettings())
            // deleteAllLogs registers before ANY pre-effect.
            assertThrows(OwnerTransitionException::class.java) { runBlocking { repo.deleteAllLogs() } }
            // Unbound legacy write path rejects too.
            assertThrows(OwnerTransitionException::class.java) { runBlocking { repo.saveLog(log("2026-10-05")) } }
            assertNull(db.cycleDao().getLogByDate("2026-10-05"))
        } finally { repo.releaseLegacyDispatchSeal() }
        // After release the same calls succeed - and getSettings proves itself a
        // write-producing read by materializing the default row.
        val settings = repo.getSettings()
        assertEquals(28, settings.averageCycleLength)
        assertNotNull("default settings row materialized by the read", db.cycleDao().getSettings())
        Unit
    }

    @Test fun admittedOperationWithNestedSettingsCreatorCompletesAndBalances() = runBlocking {
        val db = open()
        val repo = CycleRepository(db)
        // Flow level triggers the flow-anchor branch, which nests the
        // write-producing settingsFor INSIDE the admitted outer dispatch.
        repo.saveDayLogBound(log("2026-10-20", "MODERATE"))
        assertNotNull(db.cycleDao().getLogByDate("2026-10-20"))
        assertNotNull("nested settingsFor materialized defaults under the outer admission", db.cycleDao().getSettings())
        // Counts balanced: exactly-once release on both the outer and nested guards.
        assertTrue(repo.trySealLegacyDispatch())
        repo.releaseLegacyDispatchSeal()
        Unit
    }

    @Test fun staleCapturedTokenFailsAtDispatchAfterSeal() = runBlocking {
        val db = open()
        val repo = CycleRepository(db)
        val token = repo.beginOperation()
        assertTrue(repo.trySealLegacyDispatch())
        try {
            assertThrows(OwnerTransitionException::class.java) {
                runBlocking { repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28, token) }
            }
            assertNull(db.cycleDao().getSettings())
        } finally { repo.releaseLegacyDispatchSeal() }
        // The same captured token is honored again once the gate reopens:
        // staleness is enforced AT DISPATCH, not at capture.
        repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28, token)
        assertEquals(33, db.cycleDao().getSettings()?.averageCycleLength)
        Unit
    }

    @Test fun exceptionAfterRegistrationStillReleasesExactlyOnce() = runBlocking {
        val db = open()
        val repo = CycleRepository(db)
        repo.afterContextCaptured = { throw IllegalStateException("boom") }
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28) }
            }
        } finally { repo.afterContextCaptured = null }
        // The throw happened AFTER registration (seam order), so the finally had
        // to release: a leak would wedge the gate.
        assertTrue(repo.trySealLegacyDispatch())
        repo.releaseLegacyDispatchSeal()
        Unit
    }

    @Test fun crossThreadSealRejectsWhileInflightThenSucceedsAfterDrain() {
        val db = open()
        val repo = CycleRepository(db)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        repo.afterContextCaptured = { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
        try {
            runBlocking {
                val job = async(Dispatchers.IO) {
                    repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33), 28)
                }
                assertTrue("writer reached the registered section", entered.await(15, TimeUnit.SECONDS))
                assertFalse("seal must reject while a legacy dispatch is inflight (nonzero, WITHOUT sealing)", repo.trySealLegacyDispatch())
                release.countDown()
                job.await()
            }
        } finally { repo.afterContextCaptured = null; release.countDown() }
        runBlocking { assertEquals(33, db.cycleDao().getSettings()?.averageCycleLength) }
        assertTrue("after drain the seal engages", repo.trySealLegacyDispatch())
        repo.releaseLegacyDispatchSeal()
    }

    @Test fun nullTokenWithInstalledPortMintsIntentTimeTokenAndSucceeds() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db)
        // Stage A remainder: no explicit token -> beginOperation mints for the
        // CURRENT selected port; the write is served and returns that context.
        val update = repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 33, periodDuration = 6), 28)
        assertEquals("owner-a", update.context.owner)
        assertTrue(update.context.database === db)
        assertEquals(33, db.cycleDao().getSettings()?.averageCycleLength)
        Unit
    }
}
