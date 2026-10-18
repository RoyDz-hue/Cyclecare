package com.example

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CycleCareDatabase
import com.example.data.repository.CycleRepository
import com.example.data.supabase.SupabaseAuthService
import com.example.data.supabase.SupabaseSyncService
import com.example.security.AuthPreferences
import com.example.ui.viewmodel.CycleCareViewModel
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
        prefs = AuthPreferences(context).apply { clearSession(); isLocalOnlyMode = true; isCloudSyncAllowed = false; isAiSharingAllowed = false }
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
}
