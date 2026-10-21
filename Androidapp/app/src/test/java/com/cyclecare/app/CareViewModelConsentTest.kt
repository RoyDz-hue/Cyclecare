package com.cyclecare.app

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareViewModelConsentTest {
    private lateinit var database: CycleCareDatabase
    private lateinit var repository: CycleRepository
    private lateinit var prefs: AuthPreferences
    private lateinit var vm: CycleCareViewModel

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric does not run AndroidX Startup; exercise real cancellation
        // without pretending a missing test initializer is a production failure.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        prefs = AuthPreferences(context, context.getSharedPreferences("viewmodel-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE), true)
            .apply { clearSession(); isLocalOnlyMode = true; isCloudSyncAllowed = false; isAiSharingAllowed = false }
        database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java).allowMainThreadQueries().build()
        repository = CycleRepository(database)
        vm = CycleCareViewModel(repository, prefs, SupabaseSyncService(database, prefs), SupabaseAuthService(prefs))
        delay(150)
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); database.close(); Dispatchers.resetMain() }
    private suspend fun responseReady() { withTimeout(10000) { while (vm.uiState.value.isCareLoading) delay(20) } }

    @Test fun personalChatDoesNotSaveOrAwardAnythingUntilConfirmedAndCannotConfirmTwice() = runBlocking {
        val balance = vm.uiState.value.economyProfile.coinBalance
        vm.consultCare("I have cramps and I feel tired")
        responseReady()
        val date = vm.uiState.value.currentDate.toString()
        assertNull(repository.getLogForDate(date))
        assertNotNull(vm.uiState.value.pendingCareLog)
        assertEquals(balance, vm.uiState.value.economyProfile.coinBalance)
        vm.confirmCareLog()
        withTimeout(5000) { while (repository.getLogForDate(date) == null) delay(20) }
        assertTrue(repository.getLogForDate(date)!!.symptomsCsv.contains("cramps"))
        val saved = repository.getLogForDate(date)
        vm.confirmCareLog()
        delay(80)
        assertEquals(saved, repository.getLogForDate(date))
        assertEquals(balance, vm.uiState.value.economyProfile.coinBalance)
    }
    @Test fun cancellingDraftLeavesNoStoredHealthData() = runBlocking {
        vm.consultCare("I have cramps")
        responseReady()
        vm.cancelCareLog()
        vm.confirmCareLog()
        delay(100)
        assertNull(repository.getLogForDate(vm.uiState.value.currentDate.toString()))
        assertNull(vm.uiState.value.pendingCareLog)
    }
    @Test fun urgentNewInputIsShownLocallyWithAiOffAndWithoutSaving() = runBlocking {
        vm.consultCare("I fainted and I have very heavy bleeding")
        responseReady()
        assertTrue(vm.uiState.value.safetyEvaluation.isRedFlag)
        assertTrue(vm.uiState.value.careResponse!!.escalateText.isNotBlank())
        assertNull(repository.getLogForDate(vm.uiState.value.currentDate.toString()))
        assertFalse(prefs.isAiSharingAllowed)
    }
    @Test fun localOnlyEntryDoesNotCreateIdentityOrEnableSharing() = runBlocking {
        vm.startLocalOnly()
        delay(100)
        assertTrue(prefs.isLocalOnlyMode)
        assertTrue(vm.uiState.value.hasCompletedInitialAuth)
        assertTrue(vm.uiState.value.showOnboarding)
        assertNull(prefs.userId)
        assertNull(prefs.accessToken)
        assertFalse(prefs.isCloudSyncAllowed)
        assertFalse(prefs.isAiSharingAllowed)
    }

    @Test fun cloudDeletionPersistsWithdrawalEvenWhenSyncWasAlreadyOffAndServerUnavailable() = runBlocking {
        prefs.userId = "11111111-1111-4111-8111-111111111111"
        prefs.isLocalOnlyMode = false
        assertFalse(prefs.hasPendingHealthSyncRevoke)
        val log = com.cyclecare.app.data.local.CycleDayLog("2026-10-19", notes = "retain on device")
        repository.saveLog(log)
        // No access or refresh token: the deletion helper must fail locally, not contact a backend.
        vm.deleteCloudHealthCopy()
        assertFalse(prefs.isCloudSyncAllowed)
        assertTrue(prefs.hasPendingHealthSyncRevoke)
        withTimeout(5000) { while (vm.uiState.value.isDeletingCloudHealth) delay(20) }
        assertTrue(vm.uiState.value.cloudHealthDeletionMessage!!.contains("not confirmed"))
        assertEquals(log, repository.getLogForDate(log.dateString))
        assertTrue(prefs.hasPendingHealthSyncRevoke)
    }

    @Test fun sameOwnerReloginCannotInheritPrivateConversationOrDraft() = runBlocking {
        prefs.userId = "11111111-1111-4111-8111-111111111111"
        vm.consultCare("I have cramps")
        responseReady()
        assertEquals(1, vm.uiState.value.careChatHistory.size)
        assertNotNull(vm.uiState.value.pendingCareLog)
        prefs.clearSession()
        prefs.userId = "11111111-1111-4111-8111-111111111111"
        vm.consultCare("Hello")
        responseReady()
        assertEquals("Hello", vm.uiState.value.careChatHistory.single().userQuery)
        assertNull(vm.uiState.value.pendingCareLog)
    }
}
