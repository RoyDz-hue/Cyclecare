package com.example

import com.example.data.supabase.AuthErrorMessages
import com.example.data.supabase.AuthOperation
import com.example.data.supabase.SupabaseConfig
import com.example.data.supabase.UserFacingServerMessage
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

        val unrelated = SupabaseConfig.resolveClientSettings("https://another-project.supabase.co", SupabaseConfig.DEFAULT_PUBLISHABLE_KEY)
        assertEquals(SupabaseConfig.DEFAULT_URL, unrelated.url)
        assertEquals(SupabaseConfig.DEFAULT_PUBLISHABLE_KEY, unrelated.apiKey)

        val placeholder = SupabaseConfig.resolveClientSettings("https://your-project.supabase.co", "sb_publishable_test_key_12345678901234567890")
        assertEquals(SupabaseConfig.DEFAULT_URL, placeholder.url)
        assertEquals(SupabaseConfig.DEFAULT_PUBLISHABLE_KEY, placeholder.apiKey)

        val localWithProductionKey = SupabaseConfig.resolveClientSettings("http://127.0.0.1:54321", SupabaseConfig.DEFAULT_PUBLISHABLE_KEY)
        assertEquals(SupabaseConfig.DEFAULT_URL, localWithProductionKey.url)

        val localWithLocalKey = SupabaseConfig.resolveClientSettings("http://127.0.0.1:54321", "sb_publishable_local_test_key_1234567890")
        assertEquals("http://127.0.0.1:54321", localWithLocalKey.url)
        assertEquals("sb_publishable_local_test_key_1234567890", localWithLocalKey.apiKey)
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
