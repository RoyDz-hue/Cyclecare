package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cycle-screen anchor route through the real CycleCareViewModel
 * updateCalculationsAdmitted funnel (the main cycle screen render path).
 * Whichever tier computes the anchor slice - the native bridge if loadable,
 * otherwise the Kotlin computation - the published uiState must match the
 * Kotlin policy exactly. The native port's matching answers are pinned by
 * the rust core-ffi cycle_screen_state dispatch tests. Runs fully offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CycleScreenAnchorRouteTest {

    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    private fun anchoredViewModel(
        settings: CycleSettings,
        profile: OnboardingProfile
    ): CycleCareViewModel = runBlocking {
        val authPrefs = AuthPreferences(
            context,
            context.getSharedPreferences("anchor-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            clearSession()
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            accountMode = "ANONYMOUS"
            userId = "guest-anchor"
            hasCompletedInitialAuth = true
        }
        val db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        db.cycleDao().saveSettings(settings)
        db.onboardingDao().insertOrUpdateProfile(profile)
        val repository = CycleRepository(db)
        CycleCareViewModel(repository, authPrefs, SupabaseSyncService(db, authPrefs), SupabaseAuthService(authPrefs))
            .also { delay(400) }
    }

    @Test
    fun anchoredStandardCyclePublishesCountdowns() = runBlocking {
        val today = LocalDate.now()
        val start = today.minusDays(10) // day of cycle 11 of 28
        val vm = anchoredViewModel(
            CycleSettings(lastPeriodStartDate = start.toString(), averageCycleLength = 28, periodDuration = 5, lutealPhaseDays = 14),
            OnboardingProfile(approximatePeriodChoice = "exact", cycleLengthOption = "28 days", lastPeriodDateString = start.toString(), cycleLengthDays = 28, periodDurationDays = 5)
        )
        val state = vm.uiState.value
        assertTrue(state.hasCycleAnchor)
        assertFalse(state.cycleStartIsApproximate)
        assertEquals(18, state.daysUntilNextPeriod)
        assertEquals(3, state.daysUntilOvulation)
    }

    @Test
    fun unknownCycleLengthChoiceDropsAnchor() = runBlocking {
        val today = LocalDate.now()
        val start = today.minusDays(10)
        val vm = anchoredViewModel(
            CycleSettings(lastPeriodStartDate = start.toString(), averageCycleLength = 28, periodDuration = 5, lutealPhaseDays = 14),
            OnboardingProfile(approximatePeriodChoice = "exact", cycleLengthOption = "unknown", lastPeriodDateString = start.toString())
        )
        val state = vm.uiState.value
        assertFalse(state.hasCycleAnchor)
        assertEquals(0, state.daysUntilNextPeriod)
        assertEquals(0, state.daysUntilOvulation)
    }

    @Test
    fun approximateDateChoiceKeepsAnchorMarkedApproximate() = runBlocking {
        val today = LocalDate.now()
        val start = today.minusDays(10)
        val vm = anchoredViewModel(
            CycleSettings(lastPeriodStartDate = start.toString(), averageCycleLength = 28, periodDuration = 5, lutealPhaseDays = 14),
            OnboardingProfile(approximatePeriodChoice = "about a week ago", cycleLengthOption = "28 days", lastPeriodDateString = start.toString())
        )
        val state = vm.uiState.value
        assertTrue(state.hasCycleAnchor)
        assertTrue(state.cycleStartIsApproximate)
    }
}
