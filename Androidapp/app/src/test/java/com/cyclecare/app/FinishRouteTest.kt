package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Route-sequence evidence for the onboarding Finish tap on the LIVE
 * production path: real CycleCareViewModel.completeOnboarding with no
 * publication admission installed and no Rust repository port - exactly the
 * production construction (MainActivity:131), so the Room write path is the
 * one under test, not the dormant port tier. Traces: preflight -> Room
 * transaction -> settings/profile rows -> uiState publication -> lock
 * semantics. Runs fully offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinishRouteTest {

    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }

    private val openDbs = java.util.concurrent.CopyOnWriteArrayList<CycleCareDatabase>()

    @After fun cleanup() {
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        Dispatchers.resetMain()
    }

    private class Harness(val vm: CycleCareViewModel, val db: CycleCareDatabase, val prefs: AuthPreferences)

    /** Poll until the profile row exists (completion landed) or ~3s elapse;
     * fixed delays are flaky against native PBKDF2/lib-load latency. */
    private suspend fun awaitProfile(h: Harness) {
        repeat(30) {
            if (h.db.onboardingDao().getProfile() != null) return
            delay(100)
        }
    }

    private fun freshViewModel(tag: String): Harness = runBlocking {
        val authPrefs = AuthPreferences(
            context,
            context.getSharedPreferences("finish-$tag-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            // NOTE: no clearSession() - the host harness must keep
            // sessionGeneration == 0. The production constructor binds
            // lifecyclePreferences so beginOperation mints real sessions;
            // host tests cannot (production DB is SQLCipher, unloadable on
            // the host), so CycleRepository(db) mints legacy session-0
            // tokens. Both sides agreeing on session 0 preserves every
            // preflight comparison; only the session NUMBER differs from
            // production. Labeled host-route fidelity limit.
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            accountMode = "ANONYMOUS"
            // Pre-onboarding fresh-install state: userId is null, matching the
            // repository's owner=null for a no-port repository; the Finish
            // preflight requires userId == captured owner.
            hasCompletedInitialAuth = true
        }
        val db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build().also(openDbs::add)
        val repository = CycleRepository(db)
        val vm = CycleCareViewModel(repository, authPrefs, SupabaseSyncService(db, authPrefs), SupabaseAuthService(authPrefs))
        delay(400)
        Harness(vm, db, authPrefs)
    }

    @Test
    fun finishTapOnLiveRoomPathWritesProfileProjectsSettingsAndLandsHome() = runBlocking {
        val h = freshViewModel("room")
        assertNull(h.db.onboardingDao().getProfile())
        val sessionAtTap = h.prefs.sessionGeneration
        var submissionLaunched = false
        var choiceApplied = false
        CycleCareViewModel.beforeOnboardingSubmissionLaunchForTests = { submissionLaunched = true }
        CycleCareViewModel.afterOnboardingChoiceForTests = { choiceApplied = true }
        h.vm.completeOnboarding(
            goals = listOf("🩷 Track my periods"),
            lastPeriodDate = LocalDate.parse("2026-10-05"),
            cycleLength = 28,
            cycleLengthOption = "28 days",
            periodDuration = 5,
            pin = "",
            enableLock = false,
        )
        awaitProfile(h)
        delay(300)
        // Profile row written with the submitted values.
        val profile = h.db.onboardingDao().getProfile()
        val ui0 = h.vm.uiState.value
        org.junit.Assert.assertNotNull(
            "completionMessage=" + ui0.onboardingCompletionMessage +
                " sessionAtTap=" + sessionAtTap + " sessionNow=" + h.prefs.sessionGeneration +
                " submissionLaunched=" + submissionLaunched + " choiceApplied=" + choiceApplied +
                " userIdNow=" + h.prefs.userId +
                " completing=" + ui0.isCompletingOnboarding +
                " showOnboarding=" + ui0.showOnboarding +
                " portState=" + h.vm.onboardingPortState,
            profile)
        kotlin.requireNotNull(profile)
        assertEquals(28, profile.cycleLengthDays)
        assertEquals(5, profile.periodDurationDays)
        assertTrue(profile.isCompleted)
        // Settings row projected from the profile (the 7-field projection).
        val settings = h.db.cycleDao().getSettings()!!
        assertTrue(settings.isOnboardingCompleted)
        assertEquals("2026-10-05", settings.lastPeriodStartDate)
        assertEquals(28, settings.averageCycleLength)
        assertEquals(5, settings.periodDuration)
        assertFalse(settings.isAppLockEnabled)
        // uiState publication: onboarding done, home shown, nothing locked.
        val ui = h.vm.uiState.value
        assertFalse(ui.showOnboarding)
        assertFalse(ui.isCompletingOnboarding)
        assertFalse(ui.isAppLocked)
        assertTrue(ui.settings.isOnboardingCompleted)
        // Honest live-path claim: no admission in production means the port
        // machinery never engaged on this route.
        assertEquals(CycleCareViewModel.OnboardingPortState.NONE, h.vm.onboardingPortState)
    }

    @Test
    fun finishWithPinAndLockEnablesLockForNextStartupWithoutLockingNow() = runBlocking {
        val h = freshViewModel("lock")
        h.vm.completeOnboarding(
            goals = listOf("🩷 Track my periods"),
            lastPeriodDate = LocalDate.parse("2026-10-05"),
            cycleLength = 28,
            cycleLengthOption = "28 days",
            periodDuration = 5,
            pin = "1234",
            enableLock = true,
        )
        awaitProfile(h)
        delay(300)
        val settings0 = h.db.cycleDao().getSettings()
        org.junit.Assert.assertNotNull(
            "completionMessage=" + h.vm.uiState.value.onboardingCompletionMessage +
                " completing=" + h.vm.uiState.value.isCompletingOnboarding,
            settings0)
        val settings = settings0!!
        assertTrue(settings.isAppLockEnabled)
        val ui = h.vm.uiState.value
        assertTrue(ui.isPinEnabled)
        // Actual product behavior: the lock applies at the next cold start,
        // not immediately after Finish, and nothing relocks while running.
        assertFalse(ui.isAppLocked)
        assertFalse(ui.showOnboarding)
    }
}
