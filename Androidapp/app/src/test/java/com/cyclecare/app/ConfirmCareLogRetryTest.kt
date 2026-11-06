package com.cyclecare.app

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.rust.RustBoundRoomHost
import com.cyclecare.app.data.local.rust.RustRepositoryPort
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * confirmCareLog mints its intent token BEFORE the consent gate consumes its
 * pending. A stale mint (selected port whose host token is no longer current)
 * must leave pendingCareProposal AND the gate's internal pending intact, so
 * the identical confirmation can be retried and then succeeds. Proves
 * pending/gate retryability only; host write path is the real native planner
 * warmed via its designated hook over a plain Room DB with the UNREGISTERED
 * draft DDL applied by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmCareLogRetryTest {
    private lateinit var database: CycleCareDatabase
    private lateinit var repository: CycleRepository
    private lateinit var prefs: AuthPreferences
    private lateinit var vm: CycleCareViewModel

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        prefs = AuthPreferences(context, context.getSharedPreferences("retry-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { clearSession(); isLocalOnlyMode = true; isCloudSyncAllowed = false; isAiSharingAllowed = false }
        database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java).allowMainThreadQueries().build()
        // UNREGISTERED RUST_FLOAT_METADATA_DRAFT DDL applied by hand (fixture
        // only; registers and validates nothing).
        database.openHelper.writableDatabase.let { sql ->
            com.cyclecare.app.data.local.rust.RustEnrollmentStage.schema(sql)
            com.cyclecare.app.data.local.rust.RustEnrollmentStage.senderSchema(sql)
            sql.execSQL("CREATE TABLE IF NOT EXISTS rust_float_bits(dateString TEXT NOT NULL PRIMARY KEY,normalizedHash TEXT NOT NULL,bbtBits INTEGER NOT NULL,sleepBits INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_update AFTER UPDATE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString OR dateString=NEW.dateString; END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_delete AFTER DELETE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString; END")
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_insert AFTER INSERT ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=NEW.dateString; END")
        }
        com.cyclecare.app.data.local.rust.RustTrackNative.warmUp()
        repository = CycleRepository(database)
        vm = CycleCareViewModel(repository, prefs, SupabaseSyncService(database, prefs), SupabaseAuthService(prefs))
        delay(150)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); database.close(); Dispatchers.resetMain() }
    private suspend fun responseReady() { withTimeout(10000) { while (vm.uiState.value.isCareLoading) delay(20) } }

    @Test fun staleMintLeavesPendingAndGateRetryable() = runBlocking {
        // Ported repository from construction (replaceRustPort requires an
        // installed port); the port is CURRENT at install time.
        val host = RustBoundRoomHost(prefs)
        val oldToken = (host.publish(null, database, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val portedRepository = CycleRepository(database, RustRepositoryPort(host, oldToken))
        val vm2 = CycleCareViewModel(portedRepository, prefs, SupabaseSyncService(database, prefs), SupabaseAuthService(prefs))
        try {
            delay(150)
            vm2.consultCare("I have cramps and I feel tired")
            withTimeout(10000) { while (vm2.uiState.value.isCareLoading) delay(20) }
            val date = vm2.uiState.value.currentDate.toString()
            assertNotNull(vm2.uiState.value.pendingCareLog)
            assertNull(portedRepository.getLogForDate(date))

            // Make the selected port's host token stale: the host publishes a
            // successor, so beginOperation()'s isCurrent check throws.
            val successor = (host.publish(oldToken, database, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            vm2.confirmCareLog()
            delay(120)
            assertTrue("pending proposal retained for retry", vm2.uiState.value.pendingCareLog != null)
            // Reads through the stale port fail closed by design; assert on the DAO.
            assertNull("nothing saved by the rejected attempt", database.cycleDao().getLogByDate(date))

            // Retry under the CURRENT port: the intact gate pending confirms and saves.
            portedRepository.replaceRustPort(RustRepositoryPort(host, successor))
            vm2.confirmCareLog()
            withTimeout(5000) { while (portedRepository.getLogForDate(date) == null) delay(20) }
            assertTrue(portedRepository.getLogForDate(date)!!.symptomsCsv.contains("cramps"))
        } finally { vm2.viewModelScope.cancel() }
        Unit
    }
}
