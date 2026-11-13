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
 * Stale intent-token minting retains consent until routing actually changes.
 * A real successor-port revision resets private state, so an old confirmation
 * must not cross that boundary. Only a newly reviewed proposal may save.
 * Uses two VMs/shared prefs+DB and the real current native port; draft DDL is
 * fixture-only, unregistered, and does not authorize production enrollment.
 * History: original d74ac1b same-proposal-after-replacement expectation predates
 * a24ce6a/ec98e2a real revision-reset semantics. Failure/control traces retained
 * in care-merge evidence; production invalidation is deliberately unchanged.
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
    @After fun cleanup() { CycleCareViewModel.afterRustRevisionObservationForTests = null; vm.viewModelScope.cancel(); database.close(); Dispatchers.resetMain() }
    private suspend fun responseReady() { withTimeout(10000) { while (vm.uiState.value.isCareLoading) delay(20) } }

    private fun receiptCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rust_commit_receipts").use { it.moveToFirst(); it.getInt(0) }
    private fun rowCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM cycle_day_logs").use { it.moveToFirst(); it.getInt(0) }
    private fun proposal(target: CycleCareViewModel): com.cyclecare.app.domain.ai.ProposedCareLog? =
        CycleCareViewModel::class.java.getDeclaredField("pendingCareProposal").let { it.isAccessible=true; it.get(target) as com.cyclecare.app.domain.ai.ProposedCareLog? }
    private fun gatePending(target: CycleCareViewModel): com.cyclecare.app.domain.ai.ProposedCareLog? {
        val gate=CycleCareViewModel::class.java.getDeclaredField("careLogConsentGate").let { it.isAccessible=true; it.get(target) }
        return com.cyclecare.app.domain.ai.CareLogConsentGate::class.java.getDeclaredField("pending").let { it.isAccessible=true; it.get(gate) as com.cyclecare.app.domain.ai.ProposedCareLog? }
    }

    @Test fun staleMintRetainsPendingButReplacementRequiresFreshConsentAndSavesExactlyOnce() = runBlocking {
        // Ported repository from construction (replaceRustPort requires an
        // installed port); the port is CURRENT at install time.
        val host = RustBoundRoomHost(prefs)
        val oldToken = (host.publish(null, database, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
        val portedRepository = CycleRepository(database, RustRepositoryPort(host, oldToken))
        val baselineObserved=CompletableDeferred<CycleCareViewModel>()
        val revisionObserved=CompletableDeferred<CycleCareViewModel>()
        CycleCareViewModel.afterRustRevisionObservationForTests={target,revision,invalidated->
            if(revision==0L&&!invalidated)baselineObserved.complete(target)
            if(revision==1L&&invalidated)revisionObserved.complete(target)
        }
        val vm2 = CycleCareViewModel(portedRepository, prefs, SupabaseSyncService(database, prefs), SupabaseAuthService(prefs))
        try {
            assertSame(vm2, withTimeout(5000){baselineObserved.await()})
            vm2.consultCare("I have cramps and I feel tired")
            withTimeout(10000) { while (vm2.uiState.value.isCareLoading) delay(20) }
            val date = vm2.uiState.value.currentDate.toString()
            assertNotNull(vm2.uiState.value.pendingCareLog)
            assertNull(portedRepository.getLogForDate(date))
            val originalProposal=checkNotNull(proposal(vm2))
            assertEquals(originalProposal, gatePending(vm2))
            val epochBefore=vm2.identityResetEpochForTests
            val receiptsBefore=receiptCount()
            assertEquals(0,rowCount())

            // Make the selected port's host token stale: the host publishes a
            // successor, so beginOperation()'s isCurrent check throws.
            val successor = (host.publish(oldToken, database, prefs.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value
            vm2.confirmCareLog()
            delay(120)
            assertEquals(originalProposal.interpretation, vm2.uiState.value.pendingCareLog)
            assertEquals(originalProposal,proposal(vm2))
            assertEquals(originalProposal,gatePending(vm2))
            assertEquals(receiptsBefore,receiptCount())
            // Reads through the stale port fail closed by design; assert on the DAO.
            assertNull("nothing saved by the rejected attempt", database.cycleDao().getLogByDate(date))

            // Replacement publishes a real revision; do not suppress its reset.
            val currentPort=RustRepositoryPort(host, successor)
            portedRepository.replaceRustPort(currentPort)
            assertSame(vm2,withTimeout(5000){revisionObserved.await()})
            withTimeout(5000){while(vm2.identityResetEpochForTests<=epochBefore)delay(20)}
            // Join the startup selected by that reset, not an arbitrary delay.
            val startup=CycleCareViewModel::class.java.getDeclaredField("startupJob").let { it.isAccessible=true; it.get(vm2) as Job }
            withTimeout(5000){startup.join()}
            assertTrue(startup.isCompleted)
            assertSame(currentPort,portedRepository.currentRustPortForOnboarding())
            assertTrue(currentPort.isCurrent())
            assertNull(proposal(vm2))
            assertNull(gatePending(vm2))
            assertNull(vm2.uiState.value.pendingCareLog)
            assertTrue(vm2.uiState.value.careChatHistory.isEmpty())
            vm2.confirmCareLog() // Old confirmation is not transferable consent.
            assertNull(portedRepository.getLogForDate(date))
            assertEquals(0,rowCount())
            assertEquals(receiptsBefore,receiptCount())

            // Fresh reviewed proposal, under settled CURRENT routing, may save.
            vm2.consultCare("I have cramps and I feel tired")
            withTimeout(10000){while(vm2.uiState.value.isCareLoading)delay(20)}
            val fresh=checkNotNull(proposal(vm2))
            assertNotEquals(originalProposal.id,fresh.id)
            assertEquals(fresh.interpretation,vm2.uiState.value.pendingCareLog)
            assertEquals(fresh,gatePending(vm2))
            val expected=com.cyclecare.app.domain.ai.CareConversationPolicy.mergeKotlin(
                com.cyclecare.app.data.local.CycleDayLog(dateString=fresh.date),fresh.interpretation)
            assertNull(portedRepository.getLogForDate(fresh.date))
            vm2.confirmCareLog()
            withTimeout(5000) { while (portedRepository.getLogForDate(fresh.date) == null) delay(20) }
            assertEquals(expected,portedRepository.getLogForDate(fresh.date))
            assertEquals(expected,database.cycleDao().getLogByDate(fresh.date))
            assertEquals(1,rowCount())
            assertEquals(receiptsBefore+1,receiptCount())
            assertNull(proposal(vm2));assertNull(gatePending(vm2))
            vm2.confirmCareLog()
            assertEquals(expected,portedRepository.getLogForDate(fresh.date))
            assertEquals(1,rowCount())
            assertEquals(receiptsBefore+1,receiptCount())
            assertSame(currentPort,portedRepository.currentRustPortForOnboarding())
        } finally { CycleCareViewModel.afterRustRevisionObservationForTests=null; vm2.viewModelScope.cancel() }
        Unit
    }
}
