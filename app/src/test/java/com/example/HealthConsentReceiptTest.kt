package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.AuthPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Explicit JVM preference fixture, not Android Keystore or physical-device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConsentReceiptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = AuthPreferences(context, context.getSharedPreferences("unit-health-${UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply { userId = "owner-a" }
    @Test fun localDecisionEpochIsNotOverwrittenByDifferentServerGeneration() {
        val p = prefs(); p.isCloudSyncAllowed = true; val local = p.healthConsentGeneration
        assertTrue(p.saveServerHealthConsentReceipt("owner-a", p.sessionGeneration, local, 73, true))
        assertEquals(local, p.healthConsentGeneration); assertEquals(73L, p.serverHealthConsentGeneration)
    }
    @Test fun pendingWithdrawalInvalidatesGrantAndRequiresAuthoritativeFalseReceipt() {
        val p = prefs(); p.isCloudSyncAllowed = true; val old = p.healthConsentGeneration; val session = p.sessionGeneration
        assertTrue(p.saveServerHealthConsentReceipt("owner-a", session, old, 73, true))
        val revoked = p.pauseHealthSyncFor("owner-a", session)!!
        assertTrue(revoked > old); assertFalse(p.isCloudSyncAllowed); assertTrue(p.hasPendingHealthSyncRevoke); assertNull(p.serverHealthConsentGeneration)
        assertFalse(p.saveServerHealthConsentReceipt("owner-a", session, old, 74, true))
        assertTrue(p.saveServerHealthConsentReceipt("owner-a", session, revoked, 75, false)); assertFalse(p.hasPendingHealthSyncRevoke)
    }
    @Test fun logoutReloginAndOwnerABARejectOldReceipts() {
        val p = prefs(); p.isCloudSyncAllowed = true; val local = p.healthConsentGeneration; val session = p.sessionGeneration
        p.userId = "owner-b"; p.userId = "owner-a"
        assertFalse(p.saveServerHealthConsentReceipt("owner-a", session, local, 73, true))
        assertNull(p.pauseHealthSyncFor("owner-a", session)); assertTrue(p.isCloudSyncAllowed)
    }
    @Test fun receiptAndOfflineRevokeSurvivePreferenceRecreationOnlyForTheirOwner() {
        val storage = context.getSharedPreferences("unit-health-restart-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val first = AuthPreferences(context, storage, true).apply { userId = "owner-a"; isCloudSyncAllowed = true }
        first.pauseHealthSyncFor("owner-a", first.sessionGeneration)
        val reopened = AuthPreferences(context, storage, true)
        assertTrue(reopened.hasPendingHealthSyncRevoke); assertNull(reopened.serverHealthConsentGeneration)
        reopened.userId = "owner-b"; assertFalse(reopened.hasPendingHealthSyncRevoke)
    }
}
