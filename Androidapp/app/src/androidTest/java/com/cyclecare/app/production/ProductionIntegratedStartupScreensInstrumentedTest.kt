package com.cyclecare.app.production

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import com.cyclecare.app.security.StartupRouteDiagnostics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.MainActivity
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.repository.CycleCareComposition
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE acceptance of the integrated production startup route: the REAL
 * MainActivity launches for a synthetic local-only owner, the REAL Rust port
 * installs (FAIL CLOSED: missing/stale install fails the test), onboarding
 * completes via the already-existing Activity ViewModel's real completion
 * intent (OnboardingScreen callback-equivalent, not six-step form automation), the
 * REAL home screen renders, a REAL tap opens the day-log overlay (asserted by
 * its own semantic tag), and a port-routed write reads back through the same
 * repository flow the ViewModel consumes.
 *
 * Isolation: a process-wide fail-closed ProxySelector denies ALL network
 * (token-bearing or anonymous) and counts attempts; the class drains it to
 * ZERO. Grounded route audit: the home + day-log overlay path does not open
 * VaultEconomyScreen (the only RemoteCoinPackages/RemoteLocalPricing refresh
 * site) nor GuidedChatsScreen (the other anonymous community-pulse site);
 * the daily-sync work scheduled by MainActivity is cancelled and CONFIRMED
 * terminal immediately after launch and again at cleanup, so no real worker
 * can fire a real ping. Synthetic data only; screenshots fail closed on any
 * missing/empty artifact and their absolute paths are logged for retrieval.
 */
@RunWith(AndroidJUnit4::class)
class ProductionIntegratedStartupScreensInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var scenario: ActivityScenario<MainActivity>? = null
    private val ownedOwners = mutableListOf<String>()

    @Before
    fun baselineAndBarriers() {
        ProductionTestControls.cancelAndConfirmNoUnfinishedSyncWork(context)
        ProductionTestControls.installNetworkDeny()
        val prefs = CycleCareComposition.current(context).authPreferences
        ProductionTestControls.assertCleanBaseline(prefs)
    }

    @After
    fun resetToDefaults() {
        CycleCareViewModel.afterCompletionPublicationForTests = null
        com.cyclecare.app.security.StartupRouteDiagnostics.stop()
        val failures = mutableListOf<String>()
        try {
            scenario?.close()
        } catch (t: Throwable) {
            failures += "scenario close: $t"
        }
        try {
            val prefs = CycleCareComposition.current(context).authPreferences
            for (owner in ownedOwners) {
                runCatching { ProductionTestControls.resetOwnedIdentity(prefs, owner) }
                    .onFailure { failures += "reset $owner: $it" }
            }
            runCatching { CycleCareComposition.resetForTests() }
                .onFailure { failures += "composition reset: $it" }
            for (owner in ownedOwners) {
                runCatching { ProductionTestControls.deleteOwnedDatabase(context, owner) }
                    .onFailure { failures += "delete $owner: $it" }
            }
            ownedOwners.clear()
            val prefsAfter = CycleCareComposition.current(context).authPreferences
            ProductionTestControls.assertCleanBaseline(prefsAfter)
            check(prefsAfter.sessionExpiresAtSeconds == 0L) { "post-cleanup expiry must be clear" }
        } catch (t: Throwable) {
            failures += "cleanup: $t"
        }
        runCatching { ProductionTestControls.cancelAndConfirmNoUnfinishedSyncWork(context) }
            .onFailure { failures += "work barrier: $it" }
        val attempts = runCatching { ProductionTestControls.uninstallNetworkDenyAndDrainAttempts() }
            .getOrElse { failures += "network deny uninstall: $it"; -1 }
        assertTrue("cleanup failed: $failures", failures.isEmpty())
        assertEquals(
            "no network attempt may escape the startup/screens route (vault/guided screens never opened)",
            0,
            attempts
        )
    }

    @Test
    fun startup_installsPortFailClosed_homeRenders_writeReadsBack_interactionWorks() {
        val prefs = CycleCareComposition.current(context).authPreferences
        val owner = "synthetic-prod-startup-${UUID.randomUUID()}"
        ownedOwners += owner
        prefs.userId = owner
        prefs.isLocalOnlyMode = true

        scenario = ActivityScenario.launch(MainActivity::class.java)
        // MainActivity schedules the daily sync on launch: confirm it cannot
        // run before continuing.
        ProductionTestControls.cancelAndConfirmNoUnfinishedSyncWork(context)
        val repository = CycleCareComposition.current(context).repository

        awaitCurrentPort(owner, timeoutMs = 90_000)
        ProductionTestControls.saveScreenshot(context, "stage-g-home-after-install")

        // Retrieve ONLY the Activity's already-created default-key instance.
        // A missing entry must fail, never create a second test ViewModel.
        val noCreateFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                error("Activity ViewModel missing: test must never construct a replacement")
        }
        val completionPublished = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var actualViewModel: CycleCareViewModel
        scenario!!.onActivity { activity ->
            actualViewModel = ViewModelProvider(activity, noCreateFactory)[CycleCareViewModel::class.java]
        }

        // The real OnboardingScreen.onComplete callback calls this exact
        // method. This is callback-equivalent, NOT six-step form automation.
        // A direct repository write omits the ViewModel's route publication.
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                StartupRouteDiagnostics.hasEvent(StartupRouteDiagnostics.Event.ACTIVITY_VM, actualViewModel) &&
                    actualViewModel.uiState.value.hasCompletedInitialAuth &&
                    actualViewModel.uiState.value.showOnboarding
            }
            CycleCareViewModel.afterCompletionPublicationForTests = { completedVm ->
                if (completedVm === actualViewModel) completionPublished.set(true)
            }
            scenario!!.onActivity { activity ->
                val same = ViewModelProvider(activity, noCreateFactory)[CycleCareViewModel::class.java]
                check(same === actualViewModel) { "Activity ViewModel identity changed" }
                actualViewModel.completeOnboarding(
                    goals = emptyList(),
                    lastPeriodDate = LocalDate.now().minusDays(10),
                    cycleLength = 28,
                    cycleLengthOption = "28",
                    periodDuration = 5,
                    pin = "",
                    enableLock = false,
                    isCloudSyncAllowed = false
                )
            }
            compose.waitUntil(timeoutMillis = 30_000) {
                val state = actualViewModel.uiState.value
                val receipt = actualViewModel.uiReadForTests("finish")?.record
                val finalized = actualViewModel.onboardingStatusFinalization
                completionPublished.get() && receipt?.effectCompleted == true &&
                    receipt.status == CycleCareViewModel.UiReadStatus.PAINTED &&
                    receipt.waitEnd == CycleCareViewModel.UiWaitEnd.RETURNED &&
                    finalized?.isDone == true && !finalized.isCompletedExceptionally &&
                    actualViewModel.onboardingStatusFinalizationFailure == null &&
                    state.settings.isOnboardingCompleted && state.onboardingProfile?.isCompleted == true &&
                    state.hasCompletedInitialAuth && !state.showOnboarding && !state.isCompletingOnboarding &&
                    state.onboardingCompletionMessage == null && !state.localReadFailed
            }
            val storedCompletion = runBlocking { repository.getStoredSnapshot() }
            assertTrue("actual stored completed settings/profile", storedCompletion.settings.isOnboardingCompleted && storedCompletion.profile?.isCompleted == true)
            assertTrue("same real current owner port after completion", repository.installedRustPort()?.isCurrent() == true)

            // The REAL home screen renders for the synthetic owner.
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithTag("home_screen_content", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            // One diagnostic dump only, no continuous log/file persistence.
            com.cyclecare.app.security.StartupRouteDiagnostics.dumpOnce().forEach{android.util.Log.i("StageGStartupReceipt",it)}
            throw failure
        }
        compose.onNodeWithTag("home_screen_content", useUnmergedTree = true).assertIsDisplayed()
        ProductionTestControls.saveScreenshot(context, "stage-g-home-rendered")

        // A PORT-ROUTED write must read back through the repository's active
        // read source - bounded discriminating predicate, not a single-shot
        // flow read.
        val today = LocalDate.now().toString()
        runBlocking {
            repository.saveDayLogBound(CycleDayLog(dateString = today, flowLevel = "MODERATE"))
        }
        ProductionTestControls.awaitUntil(15_000, "port-routed write visible through repository.allLogs") {
            val logs = runBlocking { repository.allLogs.first() }
            if (logs.any { it.dateString == today && it.flowLevel == "MODERATE" }) Unit else null
        }
        assertTrue(
            "routing must stay current after the write",
            repository.installedRustPort()?.isCurrent() == true
        )

        // REAL interaction: the home day-log entry opens the day-log overlay,
        // proven by the overlay's own semantic element appearing.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag("home_daily_log_entry", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("home_daily_log_entry", useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag("daily_log_back_button", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("daily_log_back_button", useUnmergedTree = true).assertIsDisplayed()
        val stable = actualViewModel.uiState.value
        assertTrue("completion route remains stable through real Home interaction", stable.hasCompletedInitialAuth && !stable.showOnboarding && !stable.isCompletingOnboarding && stable.settings.isOnboardingCompleted && stable.onboardingProfile?.isCompleted == true)
        ProductionTestControls.saveScreenshot(context, "stage-g-daily-log-overlay")
    }

    @Test
    fun ownerMove_transitionsPort_oldOwnerDataNotVisible() {
        val prefs = CycleCareComposition.current(context).authPreferences
        val ownerA = "synthetic-prod-move-a-${UUID.randomUUID()}"
        ownedOwners += ownerA
        prefs.userId = ownerA
        prefs.isLocalOnlyMode = true

        scenario = ActivityScenario.launch(MainActivity::class.java)
        ProductionTestControls.cancelAndConfirmNoUnfinishedSyncWork(context)
        val repository = CycleCareComposition.current(context).repository
        awaitCurrentPort(ownerA, timeoutMs = 90_000)

        runBlocking {
            repository.completeOnboardingWithProfile(OnboardingProfile(accountMode = "PRIVATE"))
            repository.saveDayLogBound(
                CycleDayLog(dateString = LocalDate.now().minusDays(3).toString(), flowLevel = "HEAVY")
            )
        }
        val revisionBeforeMove = repository.currentRustPortRevision()

        // REAL owner move through the production prefs publication path: the
        // composition's transition watch must replace the port.
        val ownerB = "synthetic-prod-move-b-${UUID.randomUUID()}"
        ownedOwners += ownerB
        prefs.userId = ownerB

        awaitCurrentPort(ownerB, timeoutMs = 90_000)
        assertTrue(
            "the owner move must advance the rust port revision",
            repository.currentRustPortRevision() > revisionBeforeMove
        )
        ProductionTestControls.awaitUntil(15_000, "owner A records not visible after the move") {
            val logs = runBlocking { repository.allLogs.first() }
            if (logs.none { it.flowLevel == "HEAVY" }) Unit else null
        }
        ProductionTestControls.saveScreenshot(context, "stage-g-after-owner-move")
    }

    private fun awaitCurrentPort(owner: String, timeoutMs: Long) {
        val repository = CycleCareComposition.current(context).repository
        ProductionTestControls.awaitUntil(timeoutMs, "current rust port for $owner", stepMs = 500) {
            val port = repository.installedRustPort()
            if (port != null && port.isCurrent() &&
                port.token.preference.operation.owner == owner
            ) Unit else null
        }
    }
}
