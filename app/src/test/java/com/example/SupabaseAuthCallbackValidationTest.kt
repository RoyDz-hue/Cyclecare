package com.example

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.supabase.SupabaseAuthService
import com.example.security.AuthPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseAuthCallbackValidationTest {
    private val preferences = AuthPreferences(ApplicationProvider.getApplicationContext())
    private val service = SupabaseAuthService(preferences)

    @Test
    fun acceptsExactCallbackAndKnownParameters() {
        val parsed = service.parseAuthCallbackParameters(
            Uri.parse("cyclecare://auth/callback?code=abc123&state=xyz")
        )

        assertNotNull(parsed)
        assertEquals("abc123", parsed?.get("code"))
        assertEquals("xyz", parsed?.get("state"))
    }

    @Test
    fun rejectsCallbackPathPrefixAndAuthoritySpoofing() {
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://auth/callback/extra?code=x")))
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://auth/callback.evil?code=x")))
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://user@auth/callback?code=x")))
    }

    @Test
    fun rejectsDuplicateUnknownAndConflictingParameters() {
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://auth/callback?code=one&code=two")))
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://auth/callback?code=one&unexpected=value")))
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://auth/callback?code=one#access_token=two")))
        assertNull(service.parseAuthCallbackParameters(Uri.parse("cyclecare://auth/callback?=empty")))
    }

    @Test
    fun fallbackStorageDoesNotPersistSessionSecrets() {
        if (!preferences.isSecureStorageAvailable) {
            preferences.accessToken = "must-not-persist"
            preferences.refreshToken = "must-not-persist"
            preferences.googlePkceVerifier = "must-not-persist"
            preferences.pin = "1234"

            assertNull(preferences.accessToken)
            assertNull(preferences.refreshToken)
            assertNull(preferences.googlePkceVerifier)
            assertNull(preferences.pin)
            assertTrue(!preferences.isAuthenticated)
        }
    }
}
