package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
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

    @Test fun failedDurableCommitCannotAuthorizeTheNextDeletionStep() {
        val storage = context.getSharedPreferences("unit-health-commit-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        var failCommit = false
        val guarded = object : android.content.SharedPreferences by storage {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = storage.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = apply { editor.putBoolean(key, value) }
                    override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = apply { editor.putLong(key, value) }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor = apply { editor.remove(key) }
                    override fun commit(): Boolean = if (failCommit) false else editor.commit()
                }
            }
        }
        val p = AuthPreferences(context, guarded, true).apply { userId = "owner-a"; isCloudSyncAllowed = true }
        failCommit = true
        assertNull(p.pauseHealthSyncFor("owner-a", p.sessionGeneration))
    }
}
