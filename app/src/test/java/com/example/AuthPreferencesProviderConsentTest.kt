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

/** Injected preferences test account-scoped logic, not real Keystore availability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthPreferencesProviderConsentTest {
    private fun preferences(): AuthPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthPreferences(context, context.getSharedPreferences("consent-state-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
    }
    @Test fun consentIsExplicitRevisionedAndBoundToTheCurrentAccount() {
        val prefs = preferences(); prefs.userId = "consent-owner-a"
        prefs.setProviderConsent("GEMINI", true)
        assertTrue(prefs.providerConsent("GEMINI").granted)
        assertEquals(AuthPreferences.AI_PROCESSOR_CONSENT_REVISION, prefs.providerConsent("GEMINI").revision)
        prefs.userId = "consent-owner-b"
        assertFalse(prefs.providerConsent("GEMINI").granted)
        prefs.setProviderConsent("GEMINI", false)
        prefs.userId = "consent-owner-a"
        assertTrue("A different account must not modify this account's preferences", prefs.providerConsent("GEMINI").granted)
        prefs.setProviderConsent("GEMINI", false)
        assertFalse(prefs.providerConsent("GEMINI").granted)
    }
    @Test fun unknownProcessorsAndRevocationAreFailClosed() {
        val prefs = preferences(); prefs.userId = "consent-owner-c"
        prefs.setProviderConsent("POLLINATIONS", true)
        assertFalse(prefs.providerConsent("POLLINATIONS").granted)
        prefs.setProviderConsent("COHERE", true)
        prefs.revokeAllProviderConsents()
        assertTrue(prefs.providerConsents().values.none { it.granted })
    }
}
