package com.cyclecare.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-decision gate for a returning signed-in user whose local data is
 * incomplete: while the bounded cloud check is unsettled the shell must show
 * the checking splash, never the onboarding flow. Guards the production
 * branch order in CycleCareApp (isCheckingCloudProfile before showOnboarding)
 * against the repeated-onboarding regression.
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

    @Test fun unsettledCloudCheckRendersSplashNeverOnboarding() {
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
            // Mid-check window: the bounded cloud probe (up to 20s) has not
            // settled, and the initial state still carries showOnboarding=true.
            check(vm.uiState.value.isCheckingCloudProfile) { "expected unsettled checking state" }
            compose.setContent { CycleCareApp(vm) }
            compose.onNodeWithText("Checking for your saved profile", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("onboarding_screen").assertDoesNotExist()
        } finally {
            database.close()
        }
    }
}
