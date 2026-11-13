package com.cyclecare.app

import android.content.Context
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
import org.robolectric.annotation.Config

/**
 * Stage-D signOut whole-action admission witness. The previous raw body let
 * isLocalOnlyMode/hasCompletedInitialAuth QUEUE behind a held transition
 * while resetPrivateConversation, cancelSync, clearSession and the _uiState
 * publication ran IMMEDIATELY - incoherent immediate reads mid-sign-out.
 * The wrapped action must publish NOTHING until the held transition drains.
 * This witness discriminates: an unwrapped body fails the held assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDSignOutAdmissionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openDbs = mutableListOf<CycleCareDatabase>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stage-d-signout-it").apply { isDaemon = true } }

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { WorkManager.getInstance(context) } catch (e: Exception) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    @After fun cleanup() {
        openDbs.forEach { if (it.isOpen) it.close() }
        openDbs.clear()
        pool.shutdownNow()
        Dispatchers.resetMain()
    }

    @Test fun signOutPublishesNothingWhileATransitionIsHeldAndEverythingAfterDrain() {
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("stage-d-signout-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            userId = "owner-a"
            accountMode = "PRIVATE"
            isLocalOnlyMode = true
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            hasCompletedInitialAuth = true
            attachPublicationAdmission(IdentityPublicationAdmission.forAndroid())
        }
        val db = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build().also(openDbs::add)
        val repo = CycleRepository(db)
        val vm = CycleCareViewModel(repo, prefs, SupabaseSyncService(db, prefs), SupabaseAuthService(prefs))
        vm.onAuthCompleted(false)
        assertEquals("PRIVATE", vm.uiState.value.authMode)
        val admission = prefs.publicationAdmission!!

        // Hold an admitted transition on a pool thread.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        pool.execute { admission.transitionMain { held.countDown(); release.await(15, TimeUnit.SECONDS) } }
        assertTrue(held.await(15, TimeUnit.SECONDS))

        // signOut during the hold: NO part of the action may publish.
        val done = CountDownLatch(1)
        pool.execute { vm.signOut(); done.countDown() }
        Thread.sleep(300)
        assertEquals("no immediate authMode publication while held", "PRIVATE", vm.uiState.value.authMode)
        assertTrue("no immediate localOnly flip while held", prefs.isLocalOnlyMode)
        assertTrue("no immediate initial-auth flip while held", prefs.hasCompletedInitialAuth)
        assertTrue("no immediate onboarding publication while held", !vm.uiState.value.showOnboarding)

        // Drain: the WHOLE action then lands coherently.
        release.countDown()
        admission.awaitIdle().join()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals("ANONYMOUS", vm.uiState.value.authMode)
        assertFalse(prefs.isLocalOnlyMode)
        assertFalse(prefs.hasCompletedInitialAuth)
        assertTrue(vm.uiState.value.showOnboarding)
    }
}
