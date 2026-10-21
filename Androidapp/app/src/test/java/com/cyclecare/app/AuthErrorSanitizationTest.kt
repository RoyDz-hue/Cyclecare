package com.cyclecare.app

import com.cyclecare.app.data.supabase.AuthErrorMessages
import com.cyclecare.app.data.supabase.AuthOperation
import com.cyclecare.app.data.supabase.SupabaseConfig
import com.cyclecare.app.data.supabase.UserFacingServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthErrorSanitizationTest {
    @Test
    fun invalidClientKeyTemplatesResolveToTheKnownPublicKey() {
        assertEquals(SupabaseConfig.DEFAULT_PUBLISHABLE_KEY, SupabaseConfig.resolvePublicApiKey(null))
        assertEquals(SupabaseConfig.DEFAULT_PUBLISHABLE_KEY, SupabaseConfig.resolvePublicApiKey("your-public-anon-key"))
        assertEquals(
            SupabaseConfig.DEFAULT_PUBLISHABLE_KEY,
            SupabaseConfig.resolvePublicApiKey("sb_publishable_REPLACE_WITH_CYCLECARE_PUBLISHABLE_KEY")
        )
    }

    @Test
    fun projectUrlAndKeyAreResolvedAsOneValidatedPair() {
        val current = SupabaseConfig.resolveClientSettings(SupabaseConfig.DEFAULT_URL, SupabaseConfig.DEFAULT_PUBLISHABLE_KEY)
        assertEquals(SupabaseConfig.DEFAULT_URL, current.url)
        assertEquals(SupabaseConfig.DEFAULT_PUBLISHABLE_KEY, current.apiKey)

        val stagingKey = "sb_publishable_staging_client_key_1234567890"
        val staging = SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", stagingKey)
        assertEquals("https://staging-project.supabase.co", staging.url)
        assertEquals(stagingKey, staging.apiKey)

        val invalidHost = SupabaseConfig.resolveClientSettings("https://example.invalid", stagingKey)
        assertFalse(invalidHost.isConfigured)
        assertEquals("", invalidHost.url)
        assertEquals("", invalidHost.apiKey)

        val rotatedKey = "sb_publishable_rotated_public_key_1234567890"
        val rotatedProduction = SupabaseConfig.resolveClientSettings(SupabaseConfig.DEFAULT_URL, rotatedKey)
        assertEquals(SupabaseConfig.DEFAULT_URL, rotatedProduction.url)
        assertEquals(rotatedKey, rotatedProduction.apiKey)

        val mismatch = SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", SupabaseConfig.DEFAULT_PUBLISHABLE_KEY)
        assertFalse(mismatch.isConfigured)
        assertEquals("", mismatch.url)

        val localWithProductionKey = SupabaseConfig.resolveClientSettings("http://127.0.0.1:54321", SupabaseConfig.DEFAULT_PUBLISHABLE_KEY)
        assertFalse(localWithProductionKey.isConfigured)
        val localKey = "sb_publishable_local_test_key_1234567890"
        val localWithLocalKey = SupabaseConfig.resolveClientSettings("http://127.0.0.1:54321", localKey)
        assertEquals("http://127.0.0.1:54321", localWithLocalKey.url)
        assertEquals(localKey, localWithLocalKey.apiKey)

        val noEnvironment = SupabaseConfig.resolveClientSettings(null, null)
        assertEquals(SupabaseConfig.DEFAULT_URL, noEnvironment.url)
        assertEquals(SupabaseConfig.DEFAULT_PUBLISHABLE_KEY, noEnvironment.apiKey)
    }

    @Test
    fun secretAndServiceRoleKeysAreDisabledWithoutLeakingTheirValue() {
        val secret = SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", "sb_secret_not-for-mobile_1234567890")
        assertFalse(secret.isConfigured)
        assertFalse(secret.apiKey.contains("not-for-mobile"))
        val serviceRole = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.signature"
        val rejected = SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", serviceRole)
        assertFalse(rejected.isConfigured)
        assertFalse(rejected.apiKey.contains("service_role"))
    }

    @Test
    fun legacyPublicKeysMustBeAnonymousAndRespectTheirDeclaredProject() {
        fun legacy(payload: String): String {
            val encoded = android.util.Base64.encodeToString(payload.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            return "eyJhbGciOiJIUzI1NiJ9.$encoded.signature"
        }
        val key = legacy("""{"role":"anon","ref":"staging-project"}""")
        assertTrue(SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", key).isConfigured)
        assertFalse(SupabaseConfig.resolveClientSettings(SupabaseConfig.DEFAULT_URL, key).isConfigured)
        for (payload in listOf("""{"role":"authenticated"}""", """{"role":"service_role"}""", """{}""")) {
            assertFalse(SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", legacy(payload)).isConfigured)
        }
        assertFalse(SupabaseConfig.resolveClientSettings("https://staging-project.supabase.co", "").isConfigured)
        assertFalse(SupabaseConfig.resolveClientSettings("", "sb_publishable_local_test_key_1234567890").isConfigured)
    }

    @Test
    fun supportedPublishableAndLegacyClientKeyFormatsAreAccepted() {
        val publishable = "sb_publishable_test_client_key_1234567890"
        val legacy = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.signature"
        assertEquals(publishable, SupabaseConfig.resolvePublicApiKey(publishable))
        assertEquals(legacy, SupabaseConfig.resolvePublicApiKey(legacy))
    }

    @Test
    fun invalidApiErrorsUseActivitySpecificFriendlyMessages() {
        val signup = AuthErrorMessages.forResponse("""{"message":"Invalid API key"}""", AuthOperation.SIGN_UP)
        val signin = AuthErrorMessages.forResponse("""{"message":"Invalid API key"}""", AuthOperation.SIGN_IN)
        assertEquals("We couldn't create your account right now. Please try again later.", signup)
        assertEquals("We couldn't sign you in right now. Please try again later.", signin)
        assertFalse(signup.contains("API", ignoreCase = true))
        assertFalse(signin.contains("key", ignoreCase = true))
    }

    @Test
    fun knownCredentialAndExpiredLinkErrorsRemainActionableWithoutBackendText() {
        assertEquals(
            "That email and password don't match. Check them and try again.",
            AuthErrorMessages.forResponse("invalid login credentials", AuthOperation.SIGN_IN)
        )
        val callback = AuthErrorMessages.forResponse("invalid token expired", AuthOperation.CALLBACK)
        assertEquals("This CycleCare link has expired or was already used. Request a new link and try again.", callback)
        assertFalse(callback.contains("invalid token", ignoreCase = true))
    }

    @Test
    fun serverMessagesWithImplementationDetailsUseTheSafeOperationFallback() {
        assertEquals(
            "Transfer couldn't be completed.",
            UserFacingServerMessage.sanitize("PostgREST database error: invalid API key", "Transfer couldn't be completed.")
        )
        assertEquals("The recipient could not be found.", UserFacingServerMessage.sanitize("The recipient could not be found.", "Try again."))
    }
}
