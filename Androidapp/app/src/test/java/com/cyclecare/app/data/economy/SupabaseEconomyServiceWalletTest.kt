package com.cyclecare.app.data.economy

import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
class SupabaseEconomyServiceWalletTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: AuthPreferences
    private val owner = "11111111-1111-4111-8111-111111111111"
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = AuthPreferences(context, context.getSharedPreferences("wallet-${UUID.randomUUID()}", 0), true).apply {
            userId = owner
            accessToken = "a.eyJleHAiOjQxMDAwMDAwMDB9.signature"
            sessionExpiresAtSeconds = 4100000000L
        }
    }
    @After fun cleanup() { server.shutdown() }
    private fun service(client: OkHttpClient = OkHttpClient()) =
        SupabaseEconomyService(prefs, client, server.url("/").toString().removeSuffix("/"), "fixture-key")
    private fun wallet() = JSONObject().put("user_id", owner).put("status", "ACTIVE").put("coin_balance", 4L)
        .put("lifetime_earned", 4L).put("lifetime_spent", 0L).put("tier", "FREE").put("rank", "LUMEN")
    private fun enqueueWallet(economy: JSONObject = wallet(), profile: JSONObject = JSONObject().put("user_id", owner).put("status", "ACTIVE")) {
        server.enqueue(MockResponse().setBody("{\"success\":true}"))
        server.enqueue(MockResponse().setBody(JSONArray().put(economy).toString()))
        server.enqueue(MockResponse().setBody(profile.toString()))
    }

    @Test fun remotePresenceRejectsUnknownOwnerAndErrors() = runBlocking {
        server.enqueue(MockResponse().setBody(JSONObject().put("user_id",owner).put("exists",true).toString()))
        assertEquals(true,service().hasRemoteProfile())
        server.enqueue(MockResponse().setBody(JSONObject().put("user_id","someone-else").put("exists",false).toString()))
        assertNull(service().hasRemoteProfile())
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(service().hasRemoteProfile())
    }
    @Test fun normalWalletRequiresBothOwnerRowsAndExactBalance() = runBlocking {
        enqueueWallet()
        val result = service().fetchVerifiedProfile(owner)
        assertEquals(java.math.BigDecimal("4"), (result as EconomyResult.Success).data.coinBalance)
        assertEquals(owner, result.data.userId)
    }
    @Test fun identityMismatchFailsClosed() = runBlocking {
        enqueueWallet(wallet().put("user_id", "22222222-2222-4222-8222-222222222222"))
        assertTrue(service().fetchVerifiedProfile(owner) is EconomyResult.Error)
    }
    @Test fun missingProfileStatusFailsClosed() = runBlocking {
        enqueueWallet(profile = JSONObject().put("user_id", owner))
        assertTrue(service().fetchVerifiedProfile(owner) is EconomyResult.Error)
    }
    @Test fun malformedAndMissingCountersAreNotCoercedIntoZeroBalance() = runBlocking {
        for (value in listOf("4", "invalid", -1)) {
            server.enqueue(MockResponse().setBody("{\"success\":true}"))
            server.enqueue(MockResponse().setBody(JSONArray().put(wallet().put("coin_balance", value)).toString()))
            assertTrue(service().fetchVerifiedProfile(owner) is EconomyResult.Error)
        }
        val missing = wallet().apply { remove("coin_balance") }
        server.enqueue(MockResponse().setBody("{\"success\":true}"))
        server.enqueue(MockResponse().setBody(JSONArray().put(missing).toString()))
        assertTrue(service().fetchVerifiedProfile(owner) is EconomyResult.Error)
    }
    @Test fun decimalBalanceIsAcceptedExactly() = runBlocking {
        enqueueWallet(wallet().put("coin_balance", 1.5).put("lifetime_earned", 10.25))
        val result = service().fetchVerifiedProfile(owner)
        assertEquals(java.math.BigDecimal("1.5"), (result as EconomyResult.Success).data.coinBalance)
        assertEquals(java.math.BigDecimal("10.25"), result.data.lifetimeEarned)
    }
    @Test fun oversizedResponseFailsClosed() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(70_000), 512))
        assertTrue(service().fetchVerifiedProfile(owner) is EconomyResult.Error)
    }
    @Test fun redirectsNeverForwardTheWalletSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
        server.enqueue(MockResponse().setBody("{\"success\":true}"))
        assertTrue(service().fetchVerifiedProfile(owner) is EconomyResult.Error)
        assertEquals(1, server.requestCount)
    }
    @Test fun networkFailureCannotReturnAnOfflineSuccess() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("local fixture failure") }.build()
        assertTrue(service(client).fetchVerifiedProfile(owner) is EconomyResult.Error)
        assertEquals(0, server.requestCount)
    }
    @Test fun sameOwnerSessionABARejectsWalletAndRestrictionWrites() = runBlocking {
        enqueueWallet()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.encodedPath.contains("economy_profiles")) {
                prefs.clearSession(); prefs.userId = owner
            }
            response
        }.build()
        assertTrue(service(client).fetchVerifiedProfile(owner) is EconomyResult.Error)
    }
    @Test fun expiredProfileSuspensionCannotLiftIndefiniteEconomySuspension() = runBlocking {
        enqueueWallet(wallet().put("status", "SUSPENDED"), JSONObject().put("user_id", owner).put("status", "SUSPENDED").put("suspended_until", "2000-01-01T00:00:00Z"))
        val result = service().fetchVerifiedProfile(owner) as EconomyResult.Success
        assertEquals("SUSPENDED", result.data.status)
        assertNull(result.data.suspendedUntil)
        assertEquals("SUSPENDED", prefs.accountStatus)
    }
    @Test fun mostPersistentRestrictionWinsAcrossBothSources() = runBlocking {
        enqueueWallet(wallet().put("status", "SUSPENDED").put("suspended_until", "2100-01-01T00:00:00Z"),
            JSONObject().put("user_id", owner).put("status", "SUSPENDED").put("suspended_until", "2090-01-01T00:00:00Z"))
        val result = service().fetchVerifiedProfile(owner) as EconomyResult.Success
        assertEquals("2100-01-01T00:00:00Z", result.data.suspendedUntil)
    }
}
