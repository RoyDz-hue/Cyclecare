package com.cyclecare.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-decision gate for a returning signed-in user whose local data is
 * incomplete: from the unsettled cloud check through the settled retry state
 * the shell must never render the onboarding flow. Guards the production
 * branch order in CycleCareApp (hasCompletedInitialAuth, then
 * isCheckingCloudProfile splash, then cloudProfileCheckFailed retry, all
 * before showOnboarding) against the repeated-onboarding regression.
 *
 * The mid-check splash instant is timing-dependent (the offline probe can
 * settle before the first frame), so the test asserts onboarding absence at
 * every observable instant and pins the deterministic settled retry UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReopenSplashGateTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun checkingAndRetryStatesNeverRenderOnboarding() {
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("splash-gate-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            clearSession()
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            accountMode = "EMAIL"
            userId = "user-gate-1"
            hasCompletedInitialAuth = true
        }
        val database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val vm = CycleCareViewModel(CycleRepository(database), prefs, SupabaseSyncService(database, prefs), SupabaseAuthService(prefs))
            compose.setContent { CycleCareApp(vm) }
            // Earliest observable instant (unsettled check, settled retry, or
            // pre-load welcome): onboarding must not be on screen.
            compose.onNodeWithTag("onboarding_screen").assertDoesNotExist()
            // Deterministic settled state: offline probe fails to RETRY.
            compose.waitUntil(timeoutMillis = 30_000) {
                vm.uiState.value.cloudProfileCheckFailed
            }
            compose.waitForIdle()
            val settled = vm.uiState.value
            assertFalse("settled failed check must never route to onboarding", settled.showOnboarding)
            assertFalse(settled.isCheckingCloudProfile)
            assertTrue(settled.cloudProfileCheckFailed)
            compose.onNodeWithText("could not be checked yet", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("onboarding_screen").assertDoesNotExist()
            // Final retry stays on the retry surface, still no onboarding.
            compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("onboarding_screen").assertDoesNotExist()
        } finally {
            database.close()
        }
    }
}
