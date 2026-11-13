package com.cyclecare.app.SupabaseAuth

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.supabase.AccessTokenResolution
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseSessionManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var preferences: AuthPreferences
    private val oldToken = "old.header.signature"
    private val refresh = "refresh-1"
    private val newToken = jwt("user-1", System.currentTimeMillis() / 1000 + 3600)
    private fun jwt(owner: String, expiry: Long): String {
        fun encode(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return encode("{\"alg\":\"HS256\"}") + "." + encode(JSONObject().put("sub", owner).put("exp", expiry).toString()) + ".signature"
    }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = AuthPreferences(context, context.getSharedPreferences("session-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
        preferences.userId = "user-1"
        preferences.accessToken = oldToken
        preferences.refreshToken = refresh
        preferences.sessionExpiresAtSeconds = 1L
        SupabaseSessionManager.setRefreshTransportForTests(SupabaseSessionManager.httpRefreshTransport(server.url("/").toString(), "public-test-key"))
    }
    @After fun cleanup() { SupabaseSessionManager.setRefreshTransportForTests(null); server.shutdown() }
    private fun validBody(token: String = newToken, id: String = "user-1") =
        JSONObject().put("access_token", token).put("refresh_token", "refresh-2").put("expires_in", 3600)
            .put("user", JSONObject().put("id", id).put("email", "a@b.test")).toString()

    @Test fun normalRotationPreservesLoginGeneration() = runBlocking {
        val generation = preferences.sessionGeneration
        server.enqueue(MockResponse().setBody(validBody()))
        assertEquals(AccessTokenResolution.Valid(newToken), SupabaseSessionManager.resolveAccessToken(preferences))
        assertEquals(newToken, preferences.accessToken)
        assertEquals("refresh-2", preferences.refreshToken)
        assertEquals(generation, preferences.sessionGeneration)
        assertEquals(refresh, JSONObject(server.takeRequest().body.readUtf8()).getString("refresh_token"))
    }

    @Test fun mismatchedReturnedUserIsRejectedWithoutWrites() = runBlocking {
        server.enqueue(MockResponse().setBody(validBody(id = "other")))
        assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.ReauthenticationRequired)
        assertEquals(oldToken, preferences.accessToken)
        assertEquals(refresh, preferences.refreshToken)
    }

    @Test fun redirectCannotForwardTheRefreshToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/else")))
        server.enqueue(MockResponse().setBody(validBody()))
        assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.TemporarilyUnavailable)
        assertEquals(1, server.requestCount)
        assertEquals(oldToken, preferences.accessToken)
    }

    @Test fun logoutReloginSameOwnerABAIsRejectedBeforeAnyWrite() = runBlocking {
        SupabaseSessionManager.setRefreshTransportForTests {
            preferences.clearSession()
            preferences.userId = "user-1"
            preferences.accessToken = "new-login-token"
            preferences.refreshToken = "new-refresh"
            SupabaseSessionManager.RefreshResponse(200, validBody())
        }
        assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.TemporarilyUnavailable)
        assertEquals("new-login-token", preferences.accessToken)
        assertEquals("new-refresh", preferences.refreshToken)
    }

    @Test fun malformedAndOversizedChunkedBodiesDoNotWrite() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json"))
        assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.TemporarilyUnavailable)
        server.enqueue(MockResponse().setChunkedBody("x".repeat(64 * 1024 + 1), 512))
        assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.TemporarilyUnavailable)
        assertEquals(oldToken, preferences.accessToken)
    }

    @Test fun expiredOrDifferentSubjectTokenCannotBePersisted() = runBlocking {
        for (token in listOf(jwt("user-1", 1L), jwt("other", System.currentTimeMillis() / 1000 + 3600), "not.a.jwt")) {
            server.enqueue(MockResponse().setBody(validBody(token)))
            assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.ReauthenticationRequired)
            assertEquals(oldToken, preferences.accessToken)
        }
    }

    @Test fun expiredReceiptCannotOverrideAValidTokenClaim() = runBlocking {
        val body = JSONObject(validBody()).put("expires_at", 1L)
        server.enqueue(MockResponse().setBody(body.toString()))
        assertTrue(SupabaseSessionManager.resolveAccessToken(preferences) is AccessTokenResolution.ReauthenticationRequired)
        assertEquals(oldToken, preferences.accessToken)
    }
}
