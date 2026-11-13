package com.cyclecare.app

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import com.cyclecare.app.ui.viewmodel.CycleCareViewModel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Stage-D signOut whole-action admission witness. The previous raw body let
 * isLocalOnlyMode/hasCompletedInitialAuth QUEUE behind a held transition
 * while resetPrivateConversation, cancelSync, clearSession and the _uiState
 * publication ran IMMEDIATELY - incoherent immediate reads mid-sign-out.
 * The wrapped action must publish NOTHING until the held transition drains.
 * This witness discriminates: an unwrapped body fails the held assertions.
 *
 * Dispatch control (review rounds 3-4): the hold runs via
 * admission.transition INLINE on a pool thread - never transitionMain,
 * whose posted body would sit on the un-pumped Robolectric main looper
 * while the test thread blocks in held.await (deadlock). Drain uses a
 * BOUNDED pump-until-observed loop - never an unbounded awaitIdle().join()
 * from the test main thread, which can deadlock when a queued body is only
 * posted after the pump. A posted-barrier confirms signOut's transitionMain
 * post actually landed before the held assertions run. The FULL pre-hold
 * uiState is captured after draining every pre-hold notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDSignOutAdmissionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-signout-it").apply { isDaemon = true } }
    /** Shared-suite hygiene: every constructed ViewModel is put here and
     * CLEARED in cleanup, so viewModelScope collectors/jobs cannot leak
     * into later tests of a shared run. */
    private val vmStore = androidx.lifecycle.ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { WorkManager.getInstance(context) } catch (e: Exception) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    @After fun cleanup() {
        vmStore.clear()
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
        Dispatchers.resetMain()
    }

    /** Bounded drain: pump the main looper until the condition is OBSERVED.
     * Never joins an unbounded future from the test main thread. */
    private fun pumpUntil(what: String, timeoutMs: Long = 15000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("$what not observed within ${timeoutMs}ms")
    }

    @Test fun signOutPublishesNothingWhileATransitionIsHeldAndEverythingAfterDrain() {
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("stage-d-signout-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            userId = "owner-a"
            accountMode = "PRIVATE"
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            attachPublicationAdmission(IdentityPublicationAdmission.forAndroid())
        }
        val db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build().also(openDbs::add)
        val repo = CycleRepository(db)
        val vm = CycleCareViewModel(repo, prefs, SupabaseSyncService(db, prefs), SupabaseAuthService(prefs))
        vmStore.put("main", vm)
        vm.onAuthCompleted(false)
        assertEquals("PRIVATE", vm.uiState.value.authMode)
        val admission = prefs.publicationAdmission!!

        // Re-establish the local-only baseline onAuthCompleted cleared.
        prefs.isLocalOnlyMode = true
        prefs.hasCompletedInitialAuth = true
        assertTrue("baseline local-only re-established", prefs.isLocalOnlyMode)
        assertTrue("baseline initial-auth re-established", prefs.hasCompletedInitialAuth)
        // Drain every transition the baseline setters and onAuthCompleted
        // ran BEFORE capturing the baseline and holding: capture the
        // admission's idle future ONCE, bounded-pump the main looper until
        // the future completes, then surface any failure it carries. A
        // literal-true condition here would be the old one-shot race in
        // disguise.
        val preHoldIdle = admission.awaitIdle()
        pumpUntil("pre-hold admission drained") { preHoldIdle.isDone }
        preHoldIdle.get(0, TimeUnit.SECONDS) // throws if the drained work failed
        val baseline = vm.uiState.value
        assertEquals("PRIVATE", baseline.authMode)
        assertTrue(!baseline.showOnboarding)

        // Hold an admitted transition INLINE on a pool thread.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        pool.execute { admission.transition { held.countDown(); release.await(15, TimeUnit.SECONDS) } }
        assertTrue("held transition must be admitted", held.await(15, TimeUnit.SECONDS))

        // signOut during the hold: the VM posts the whole action via
        // transitionMain. The posted-barrier confirms the post landed in
        // the main queue before the held assertions run; the looper is
        // NEVER pumped while held (a queued body would block the test
        // thread on the admission lock).
        val posted = CountDownLatch(1)
        pool.execute { vm.signOut(); posted.countDown() }
        assertTrue("signOut's transitionMain post landed", posted.await(15, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals("no publication of ANY uiState field while held", baseline, vm.uiState.value)
        assertTrue("no immediate localOnly flip while held", prefs.isLocalOnlyMode)
        assertTrue("no immediate initial-auth flip while held", prefs.hasCompletedInitialAuth)

        // Drain: release the hold, then BOUNDED pump until the whole action
        // is observed - never an unbounded join from the test main thread.
        release.countDown()
        pumpUntil("signOut published after drain") {
            vm.uiState.value.authMode == "ANONYMOUS" &&
                !prefs.isLocalOnlyMode &&
                !prefs.hasCompletedInitialAuth &&
                vm.uiState.value.showOnboarding
        }
        assertEquals("ANONYMOUS", vm.uiState.value.authMode)
        assertFalse(prefs.isLocalOnlyMode)
        assertFalse(prefs.hasCompletedInitialAuth)
        assertTrue(vm.uiState.value.showOnboarding)
    }
}
