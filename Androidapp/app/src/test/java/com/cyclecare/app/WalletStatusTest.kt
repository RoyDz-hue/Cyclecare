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
import com.cyclecare.app.ui.viewmodel.WalletStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wallet display state must never render a failed or missing fetch as a
 * real zero balance, and must stay bound to the current identity. Runs fully
 * offline: no credential-less mode may attempt a fetch, and a signed-in
 * session with no network must settle to UNAVAILABLE, never a default zero. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletStatusTest {

    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    private fun prefs(tag: String): AuthPreferences =
        AuthPreferences(
            context,
            context.getSharedPreferences("wallet-$tag-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        )

    @Test fun localOnlyModeHasNoSessionWalletNeverFetches() = runBlocking {
        val authPrefs = prefs("local").apply {
            clearSession()
            isLocalOnlyMode = true
            hasCompletedInitialAuth = true
        }
        val database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val vm = CycleCareViewModel(CycleRepository(database), authPrefs, SupabaseSyncService(database, authPrefs), SupabaseAuthService(authPrefs))
            vm.refreshWalletNow(force = true)
            assertEquals(WalletStatus.NO_SESSION, vm.uiState.value.walletStatus)
            // The default profile must not be presented as a fetched balance.
            assertTrue(vm.uiState.value.economyProfile.coinBalance.signum() == 0)
        } finally {
            database.close()
        }
    }

    @Test fun delayedResponseFromOldOwnerNeverPopulatesNewOwnerCard() = runBlocking {
        val authPrefs = prefs("switch").apply {
            clearSession()
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            accountMode = "EMAIL"
            userId = "user-old-owner"
            hasCompletedInitialAuth = true
        }
        val database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val vm = CycleCareViewModel(CycleRepository(database), authPrefs, SupabaseSyncService(database, authPrefs), SupabaseAuthService(authPrefs))
            vm.refreshWalletNow(force = true)
            // Identity changes while the (offline, fast-failing) request is in
            // flight: the late result must not touch the new owner's card.
            authPrefs.clearSession()
            authPrefs.userId = "user-new-owner"
            var waitedMs = 0L
            while (waitedMs < 5_000) {
                delay(200)
                waitedMs += 200
                val status = vm.uiState.value.walletStatus
                if (status == WalletStatus.READY || status == WalletStatus.STALE || status == WalletStatus.UNAVAILABLE) break
            }
            val state = vm.uiState.value
            assertTrue("old-owner result must never publish READY on the new owner card",
                state.walletStatus != WalletStatus.READY)
            assertTrue(state.economyProfile.coinBalance.signum() == 0)
        } finally {
            database.close()
        }
    }

    @Test fun signedInFetchFailureSettlesUnavailableNeverFakeZero() = runBlocking {
        val authPrefs = prefs("signed").apply {
            clearSession()
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            accountMode = "EMAIL"
            userId = "user-wallet-1"
            hasCompletedInitialAuth = true
        }
        val database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val vm = CycleCareViewModel(CycleRepository(database), authPrefs, SupabaseSyncService(database, authPrefs), SupabaseAuthService(authPrefs))
            vm.refreshWalletNow(force = true)
            var status = vm.uiState.value.walletStatus
            var waitedMs = 0L
            while (status == WalletStatus.LOADING && waitedMs < 15_000) {
                delay(200)
                waitedMs += 200
                status = vm.uiState.value.walletStatus
            }
            // Offline with no previously fetched wallet for this owner:
            // UNAVAILABLE, and the displayed profile stays the untouched default.
            assertEquals(WalletStatus.UNAVAILABLE, status)
            assertTrue(vm.uiState.value.economyProfile.coinBalance.signum() == 0)
        } finally {
            database.close()
        }
    }
}
