package com.cyclecare.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.supabase.AuthResult
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseAuthRequestContractTest {
    private lateinit var server: MockWebServer
    private lateinit var preferences: AuthPreferences
    private lateinit var service: SupabaseAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        preferences = AuthPreferences(ApplicationProvider.getApplicationContext()).also {
            // Robolectric cannot provide Android Keystore; bypass only this test fixture's storage flag.
            ReflectionHelpers.setField(it, "isSecureStorageAvailable", true)
            it.clearSession()
        }
        service = SupabaseAuthService(
            authPreferences = preferences,
            client = OkHttpClient(),
            authApiUrl = server.url("/auth/v1").toString().trimEnd('/'),
            apiKey = TEST_PUBLISHABLE_KEY
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        preferences.clearSession()
    }

    @Test
    fun signupUsesValidAuthEndpointAndPkceChallenge() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"user":{"id":"email-user","email":"person@example.com"}}"""
        ))

        val result = service.signUp("person@example.com", "secure-pass-123")
        val request = takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertTrue(result is AuthResult.VerificationRequired)
        assertTrue(request.path.orEmpty().startsWith("/auth/v1/signup?redirect_to="))
        assertEquals(TEST_PUBLISHABLE_KEY, request.getHeader("apikey"))
        assertEquals("person@example.com", body.getString("email"))
        assertTrue(body.getString("code_challenge").isNotBlank())
        assertEquals("s256", body.getString("code_challenge_method"))
        assertTrue(preferences.googlePkceVerifier?.isNotBlank() == true)
        assertEquals("SIGN_UP", preferences.pendingAuthAction)
    }

    @Test
    fun passwordlessEmailRequestUsesOtpContractAndDisablesAccountCreation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = service.sendMagicLink("person@example.com")
        val request = takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertTrue(result is AuthResult.VerificationRequired)
        assertTrue(request.path.orEmpty().startsWith("/auth/v1/otp?redirect_to="))
        assertEquals(false, body.getBoolean("create_user"))
        assertTrue(body.getString("code_challenge").isNotBlank())
        assertEquals("s256", body.getString("code_challenge_method"))
        assertEquals("MAGIC_LINK", preferences.pendingAuthAction)
    }

    @Test
    fun guestEntryUsesAnonymousSignupContractAndNeverSendsPlaceholderKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"guest-user","is_anonymous":true}}"""
        ))

        val result = service.continueAsGuest()
        val request = takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertTrue(result is AuthResult.Success)
        assertEquals("/auth/v1/signup", request.path)
        assertEquals(TEST_PUBLISHABLE_KEY, request.getHeader("apikey"))
        assertFalse(body.has("email"))
        assertFalse(body.has("password"))
        assertEquals("ANONYMOUS", body.getJSONObject("data").getString("account_mode"))
    }

    @Test
    fun recoveryRequestCarriesPkceAndRemembersRecoveryIntent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = service.sendPasswordReset("person@example.com")
        val request = takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertTrue(result is AuthResult.VerificationRequired)
        assertTrue(request.path.orEmpty().startsWith("/auth/v1/recover?redirect_to="))
        assertTrue(body.getString("code_challenge").isNotBlank())
        assertEquals("s256", body.getString("code_challenge_method"))
        assertEquals("PASSWORD_RESET", preferences.pendingAuthAction)
    }

    @Test
    fun emailCodeCallbackExchangesSavedVerifierAndShowsEmailSpecificSuccess() = runBlocking {
        preferences.googlePkceVerifier = "saved-email-verifier"
        preferences.pendingAuthAction = "MAGIC_LINK"
        preferences.pendingEmail = "person@example.com"
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"email-user","email":"person@example.com","email_confirmed_at":"2026-10-16T00:00:00Z"}}"""
        ))

        val result = service.completeEmailLink(Uri.parse("cyclecare://auth/callback?code=one-time-code"))
        val request = takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertTrue(result is AuthResult.Success)
        assertEquals("You're signed in to CycleCare.", (result as AuthResult.Success).message)
        assertEquals("/auth/v1/token?grant_type=pkce", request.path)
        assertEquals("one-time-code", body.getString("auth_code"))
        assertEquals("saved-email-verifier", body.getString("code_verifier"))
        assertEquals(null, preferences.googlePkceVerifier)
        assertEquals(null, preferences.pendingAuthAction)
    }

    @Test
    fun resetCodeCallbackResumesPasswordRecoveryInsteadOfGoogleSignIn() = runBlocking {
        preferences.googlePkceVerifier = "saved-reset-verifier"
        preferences.pendingAuthAction = "PASSWORD_RESET"
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"email-user","email":"person@example.com","email_confirmed_at":"2026-10-16T00:00:00Z"}}"""
        ))

        val result = service.completeEmailLink(Uri.parse("cyclecare://auth/callback?code=reset-code"))
        takeRequest()

        assertTrue(result is AuthResult.Success)
        assertEquals("Your reset link is verified. Set a new password to finish.", (result as AuthResult.Success).message)
        assertTrue(preferences.needsPasswordSetup)
        assertNotNull(preferences.accessToken)
    }

    private fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected the auth request to be sent.")

    private companion object {
        const val TEST_PUBLISHABLE_KEY = "sb_publishable_test_client_key_1234567890"
    }
}
