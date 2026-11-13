package com.cyclecare.app.domain.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareRequestPrivacySnapshotTest {
    private fun prefs(): AuthPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthPreferences(context, context.getSharedPreferences("ai-request-${UUID.randomUUID()}", 0), true).apply {
            userId = "owner-a"
            setProviderConsent("COHERE", true)
            saveProcessorReceipt(AiProcessorReceipt("COHERE", 1, "a".repeat(64)))
        }
    }
    @Test fun sameOwnerReloginCannotReuseOldQuestionOrResponseSnapshot() {
        val p = prefs(); val snapshot = CareRequestPrivacySnapshot.capture(p)
        p.userId = "owner-b"; p.userId = "owner-a"
        assertFalse(snapshot.isCurrent(p))
    }
    @Test fun withdrawalAndFreshReceiptCannotResurrectAnOldRequest() {
        val p = prefs(); val snapshot = CareRequestPrivacySnapshot.capture(p)
        p.isAiSharingAllowed = false
        p.setProviderConsent("COHERE", true)
        p.saveProcessorReceipt(AiProcessorReceipt("COHERE", 2, "a".repeat(64)))
        assertFalse(snapshot.isCurrent(p))
    }
    @Test fun healthDeletionPendingWithdrawalInvalidatesTheSnapshotEvenWhenSyncWasOff() {
        val p = prefs(); val snapshot = CareRequestPrivacySnapshot.capture(p)
        assertNotNull(p.pauseHealthSyncFor("owner-a", p.sessionGeneration))
        assertFalse(snapshot.isCurrent(p))
    }
    @Test fun validTokenRotationWithinTheSameLoginDoesNotInventAConsentChange() {
        val p = prefs(); val snapshot = CareRequestPrivacySnapshot.capture(p)
        p.accessToken = "new-access-token"
        assertTrue(snapshot.isCurrent(p))
    }
}
