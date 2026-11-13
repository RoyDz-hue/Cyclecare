package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.HealthSyncConflictEntity
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
import com.cyclecare.app.data.local.HealthSyncRevision
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
 * B2-nonwipe binding-parity tests for the selected-port branches of saveLog,
 * deleteLog, retainLocalConflict, acceptRemoteConflict (live) and
 * saveOnboardingProfile/saveSettings (dormant, no production callers).
 * Tier: tier-agnostic host routing evidence over plain temporary Room DBs
 * (Robolectric; no SQLCipher/Keystore, no admission). Proves repository
 * routing/lease behavior ONLY. An entry-minted token + exact lease blocks a
 * write whose context was replaced after the user's intent; it does NOT prove
 * captured-read freshness, UI freshness, or any concurrent same-port
 * read-merge-write claim. All "zero" claims below are scoped to repository DB
 * ROWS (log row, revision row, conflict row as named per test), never an
 * atomic prefs+DB claim. Cancellation after a synchronous commit may lose the
 * waiter; no zero-commit promise under cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnboundPortBindingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()

    private fun open(): CycleCareDatabase {
        val name = "b2-${UUID.randomUUID()}".also { names += it }
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

    init {
        // The production wiring gap is real and reported separately: nothing in
        // app sources calls warmUp, so the Track planner is never loaded. Tests
        // warm the REAL native planner here (its designated hook) so the host
        // Track path can execute at all.
        com.cyclecare.app.data.local.rust.RustTrackNative.warmUp()
    }

    private fun prefs(): AuthPreferences =
        AuthPreferences(context, context.getSharedPreferences("b2-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
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

    private val owner = "owner-a"
    private val date = "2026-10-07"
    private fun log(mood: String) = CycleDayLog(dateString = date, moodLevel = mood)

    /** Every API under test as one uniform write call. */
    private class WriteApi(val name: String, val call: suspend (CycleRepository, CycleRepository.OperationToken?) -> Unit) {
        override fun toString() = name
    }
    private val SAVE_LOG = WriteApi("SAVE_LOG") { repo, token -> if (token == null) repo.saveLog(log("minted")) else repo.saveLog(log("minted"), token) }
    private val DELETE_LOG = WriteApi("DELETE_LOG") { repo, token -> if (token == null) repo.deleteLog(date) else repo.deleteLog(date, token) }
    private val RETAIN_CONFLICT = WriteApi("RETAIN_CONFLICT") { repo, token -> if (token == null) repo.retainLocalConflict(date) else repo.retainLocalConflict(date, token) }
    private val ACCEPT_CONFLICT = WriteApi("ACCEPT_CONFLICT") { repo, token -> if (token == null) repo.acceptRemoteConflict(date) else repo.acceptRemoteConflict(date, token) }
    private val apis = listOf(SAVE_LOG, DELETE_LOG, RETAIN_CONFLICT, ACCEPT_CONFLICT)

    /** Zero-claim probe per API: the write left no rows behind. */
    private suspend fun assertZero(api: WriteApi, db: CycleCareDatabase) {
        when (api) {
            SAVE_LOG -> {
                assertNull("row-scoped zero: no log row", db.cycleDao().getLogByDate(date))
                assertNull("row-scoped zero: no revision row", db.healthSyncDao().revision(owner, date))
            }
            DELETE_LOG -> {
                val row = db.cycleDao().getLogByDate(date)
                assertEquals("row-scoped zero: seeded row untouched", "seed", row?.moodLevel)
                val rev = db.healthSyncDao().revision(owner, date)!!
                assertEquals("row-scoped zero: revision not bumped", 1L, rev.revision)
                assertFalse("row-scoped zero: no tombstone", rev.tombstone)
            }
            RETAIN_CONFLICT, ACCEPT_CONFLICT -> {
                val conflict = db.healthSyncDao().conflicts(owner).firstOrNull { it.recordId == date }
                assertNotNull("row-scoped zero: conflict still pending", conflict)
                val rev = db.healthSyncDao().revision(owner, date)
                if (api == RETAIN_CONFLICT) assertEquals("row-scoped zero: revision not bumped", 3L, rev?.revision)
                else assertNull("row-scoped zero: no revision row", rev)
            }
        }
    }

    private suspend fun seedFor(api: WriteApi, db: CycleCareDatabase) {
        when (api) {
            SAVE_LOG -> Unit
            DELETE_LOG -> {
                db.cycleDao().insertOrUpdateLog(log("seed"))
                db.healthSyncDao().putRevision(HealthSyncRevision(owner, date, 1L, HealthSyncPayloadCodec.hash(HealthSyncPayloadCodec.logPayload(log("seed"))), false, 1L, 0L))
            }
            RETAIN_CONFLICT -> {
                val payload = HealthSyncPayloadCodec.logPayload(log("local"))
                db.healthSyncDao().putRevision(HealthSyncRevision(owner, date, 3L, HealthSyncPayloadCodec.hash(payload), false, 1L, 2L))
                db.healthSyncDao().putConflict(HealthSyncConflictEntity(owner, date, "op-c1", 3L, payload, "UPSERT", 2L, HealthSyncPayloadCodec.logPayload(log("remote")), false, 1L))
            }
            ACCEPT_CONFLICT -> {
                db.healthSyncDao().putConflict(HealthSyncConflictEntity(owner, date, "op-c2", 0L, null, "UPSERT", 5L, HealthSyncPayloadCodec.logPayload(log("remote")), false, 1L))
            }
        }
    }

    /** Replacement candidate DB: the blocked write landed nothing at all. */
    private suspend fun assertNoWriteLanded(db: CycleCareDatabase) {
        assertNull("row-scoped zero: no log row on replacement DB", db.cycleDao().getLogByDate(date))
        assertNull("row-scoped zero: no revision row on replacement DB", db.healthSyncDao().revision(owner, date))
    }

    // ---- Minted success (4) ----
    @Test fun mintedTokenSucceedsAllFourLiveApis() = runBlocking {
        for (api in apis) {
            val p = prefs(); val db = open()
            val repo = ported(p, db).repo
            seedFor(api, db)
            api.call(repo, repo.beginOperation())
            when (api) {
                SAVE_LOG -> assertEquals("minted", db.cycleDao().getLogByDate(date)?.moodLevel)
                DELETE_LOG -> {
                    assertNull(db.cycleDao().getLogByDate(date))
                    assertTrue("tombstone revision written", db.healthSyncDao().revision(owner, date)!!.tombstone)
                }
                RETAIN_CONFLICT -> {
                    assertTrue("conflict resolved", db.healthSyncDao().conflicts(owner).none { it.recordId == date })
                    assertEquals(4L, db.healthSyncDao().revision(owner, date)!!.revision)
                }
                ACCEPT_CONFLICT -> {
                    assertEquals("remote", db.cycleDao().getLogByDate(date)?.moodLevel)
                    assertEquals(5L, db.healthSyncDao().revision(owner, date)!!.serverRevision)
                }
            }
        }
        Unit
    }

    // ---- Explicit legacy token fails closed (4) ----
    @Test fun explicitLegacyTokenFailsClosedRowScopedZeroAllFour() = runBlocking {
        for (api in apis) {
            val p = prefs(); val db = open()
            val repo = ported(p, db).repo
            seedFor(api, db)
            val legacy = CycleRepository.OperationToken.legacy(repo.currentContext(), 0L, 0L)
            assertThrows("$api", OwnerTransitionException::class.java) {
                runBlocking { api.call(repo, legacy) }
            }
            assertZero(api, db)
        }
        Unit
    }

    // ---- Superseded port token fails closed (4) ----
    @Test fun supersededPortTokenFailsClosedRowScopedZeroAllFour() = runBlocking {
        for (api in apis) {
            val p = prefs(); val db = open()
            val (repo, host, _, _) = ported(p, db)
            seedFor(api, db)
            val staleToken = repo.beginOperation()
            val successor = (host.publish(repo.currentRustPortForOnboarding()!!.token, db, p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            repo.replaceRustPort(RustRepositoryPort(host, successor))
            assertThrows("$api", OwnerTransitionException::class.java) {
                runBlocking { api.call(repo, staleToken) }
            }
            assertZero(api, db)
        }
        Unit
    }

    // ---- Replacement after selection, before dispatch: lease refuses (4) ----
    @Test fun replacementAfterSelectionBeforeDispatchFailsClosedAllFour() = runBlocking {
        for (api in apis) {
            val p = prefs(); val db = open()
            val repo = ported(p, db).repo
            seedFor(api, db)
            val token = repo.beginOperation()
            val (portB, dbB) = replacementPort(p)
            repo.beforeBoundPortDispatchForTests = { repo.replaceRustPort(portB) }
            try {
                assertThrows("$api", RustRepositoryPort.Failure::class.java) {
                    runBlocking { api.call(repo, token) }
                }
            } finally { repo.beforeBoundPortDispatchForTests = null }
            assertZero(api, db)
            assertNoWriteLanded(dbB)
        }
        Unit
    }

    // ---- Invocation-mint semantics (default parameter): pinned, NOT "unchanged" ----
    @Test fun nullTokenInvocationMintSucceedsAllFour() = runBlocking {
        // The default parameter mints at INVOCATION time on the currently
        // selected port. This is deliberately NOT user-intent-time binding:
        // production callers pass explicit entry-minted tokens instead.
        for (api in apis) {
            val p = prefs(); val db = open()
            val repo = ported(p, db).repo
            seedFor(api, db)
            api.call(repo, null)
            when (api) {
                SAVE_LOG -> assertEquals("minted", db.cycleDao().getLogByDate(date)?.moodLevel)
                DELETE_LOG -> assertNull(db.cycleDao().getLogByDate(date))
                RETAIN_CONFLICT -> assertTrue(db.healthSyncDao().conflicts(owner).none { it.recordId == date })
                ACCEPT_CONFLICT -> assertEquals("remote", db.cycleDao().getLogByDate(date)?.moodLevel)
            }
        }
        Unit
    }

    // ---- Dormant APIs: wiring proof only (2) ----
    @Test fun dormantSaveOnboardingProfileMintedTokenSucceeds() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db).repo
        repo.saveOnboardingProfile(OnboardingProfile(
            id = 1, isCompleted = true, completedAtTimestamp = 1L, isAgeVerified = true, isTermsAgreed = true,
            accountMode = "PRIVATE", selectedGoalsCsv = "dormant", lastPeriodDateString = "2026-10-05",
            approximatePeriodChoice = "EXACT", cycleLengthOption = "28", cycleLengthDays = 28, periodDurationDays = 5
        ), repo.beginOperation())
        assertEquals("dormant", db.onboardingDao().getProfile()?.selectedGoalsCsv)
        Unit
    }

    @Test fun dormantSaveSettingsMintedTokenSucceeds() = runBlocking {
        val p = prefs(); val db = open()
        val repo = ported(p, db).repo
        repo.saveSettings(CycleSettings(id = 1, lutealPhaseDays = 17), repo.beginOperation())
        assertEquals(17, db.cycleDao().getSettings()?.lutealPhaseDays)
        Unit
    }
}
