package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.OwnerTransitionException
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * PREFS-BACKED rejection cases: a repository built with a real AuthPreferences, so
 * requireTokenFresh is active. (OwnerBoundSnapshotTest uses a no-prefs repository and
 * deliberately writes with an older token; that is NOT evidence of production protection.)
 * Uses the production constructor, which opens CycleCareDatabase.getDatabase; whether
 * that works under Robolectric here is unverified. Unrun. Pre-check only: it proves
 * nothing about a change landing after the check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsBackedTokenRejectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = AuthPreferences(context, context.getSharedPreferences("token-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
    private fun update(repo: CycleRepository, token: CycleRepository.OperationToken) = runBlocking {
        repo.updateSettingsBound(CycleSettings(id = 1), 28, token)
    }

    @Test fun freshTokenIsAccepted() {
        val p = prefs(); p.userId = "owner-a"
        val repo = CycleRepository(context, p)
        val token = repo.beginOperation()
        update(repo, token)
    }

    @Test fun tokenIsRejectedAfterLogout() {
        val p = prefs(); p.userId = "owner-a"
        val repo = CycleRepository(context, p)
        val token = repo.beginOperation()
        p.clearSession()
        assertThrows(OwnerTransitionException::class.java) { update(repo, token) }
    }

    @Test fun tokenIsRejectedAfterConsentChange() {
        val p = prefs(); p.userId = "owner-a"
        val repo = CycleRepository(context, p)
        val token = repo.beginOperation()
        p.isCloudSyncAllowed = true
        assertThrows(OwnerTransitionException::class.java) { update(repo, token) }
    }

    @Test fun tokenIsRejectedAfterSwitchAwayAndBack() {
        val p = prefs(); p.userId = "owner-a"
        val repo = CycleRepository(context, p)
        val token = repo.beginOperation()
        p.userId = "owner-b"
        p.userId = "owner-a"
        assertThrows(OwnerTransitionException::class.java) { update(repo, token) }
    }
}
