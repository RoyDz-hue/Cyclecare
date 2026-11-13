package com.cyclecare.app.domain.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fake preference storage tests consent logic, NOT Android Keystore encryption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiProcessorConsentHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: AuthPreferences
    private lateinit var service: AiProcessorConsentService
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val fp = "a".repeat(64)
    private val revision = "cyclecare-ai-manifest-v3"
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AuthPreferences(context, context.getSharedPreferences("unit-consent-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
        prefs.userId = owner
        server = MockWebServer().also { it.start() }
        service = AiProcessorConsentService(prefs, server.url("/").toString())
    }
    @After fun close() { server.shutdown() }
    private fun manifest(eligible: Boolean = true, consentRevision: Int = 0) = AiProcessorManifest(3, owner, revision,
        listOf(AiProcessor("COHERE", "Cohere", "command", "policy-v1", fp,
            listOf(AiSubProcessor("cohere", "Cohere", "https://cohere.com/privacy")), eligible, if (eligible) "" else "Unreviewed", false, consentRevision)), false, 2)
    private fun manifestJson(eligible: Boolean = true, consentRevision: Int = 0): JSONObject = JSONObject()
        .put("version", 3).put("account_snapshot", owner).put("manifest_revision", revision)
        .put("hybrid", JSONObject().put("released", false).put("max_attempts", 2))
        .put("providers", JSONArray().put(JSONObject().put("id", "COHERE").put("display_name", "Cohere").put("model", "command")
            .put("policy_revision", "policy-v1").put("manifest_fingerprint", fp).put("eligible", eligible).put("unavailable_reason", "")
            .put("processors", JSONArray().put(JSONObject().put("id", "cohere").put("name", "Cohere").put("policy_url", "https://cohere.com/privacy")))
            .put("consent", JSONObject().put("enabled", false).put("revision", consentRevision))))
    private fun acknowledgement(enabled: Boolean, next: Int): MockResponse = MockResponse().setBody(JSONObject().put("version", 3)
        .put("account_snapshot", owner).put("provider_id", "COHERE").put("manifest_revision", revision)
        .put("receipt", JSONObject().put("enabled", enabled).put("revision", next).put("manifest_fingerprint", fp)).toString())

    @Test fun smallManifestIsReadWithoutRequiringAnEightKilobyteChunk() = runBlocking {
        server.enqueue(MockResponse().setBody(manifestJson().toString()))
        assertTrue(service.fetchManifest("fixture-token") is ConsentResult.Success)
        assertEquals("GET", server.takeRequest().method)
    }
    @Test fun unknownDuplicateAndInsecurePolicyManifestsAreRejected() = runBlocking {
        val unknown = manifestJson().also { it.getJSONArray("providers").getJSONObject(0).put("id", "POLLINATIONS") }
        val duplicate = manifestJson().also { it.getJSONArray("providers").put(it.getJSONArray("providers").getJSONObject(0)) }
        val insecure = manifestJson().also { it.getJSONArray("providers").getJSONObject(0).getJSONArray("processors").getJSONObject(0).put("policy_url", "http://example.invalid") }
        for (json in listOf(unknown, duplicate, insecure)) {
            server.enqueue(MockResponse().setBody(json.toString()))
            assertTrue(service.fetchManifest("fixture-token") is ConsentResult.Failure)
        }
    }
    @Test fun grantRequiresAuthoritativeMatchingReceiptAndSendsNoClientOwner() = runBlocking {
        server.enqueue(acknowledgement(true, 1))
        assertTrue(service.setConsent("fixture-token", manifest(), "COHERE", true) is ConsentResult.Success)
        assertEquals(1, prefs.processorReceipt("COHERE")?.revision)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(0, body.getInt("expected_revision")); assertFalse(body.has("user_id"))
        assertEquals("COHERE", body.getString("provider_id"))
    }
    @Test fun ineligibleProviderCanBeRevokedButEmptySuccessCannotClearThePendingRevoke() = runBlocking {
        prefs.saveProcessorReceipt(AiProcessorReceipt("COHERE", 1, fp))
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(service.setConsent("fixture-token", manifest(false, 1), "COHERE", false) is ConsentResult.Failure)
        assertNull(prefs.processorReceipt("COHERE")); assertTrue("COHERE" in prefs.pendingProcessorRevokes())
        server.enqueue(acknowledgement(false, 2))
        assertTrue(service.setConsent("fixture-token", manifest(false, 1), "COHERE", false) is ConsentResult.Success)
        assertFalse("COHERE" in prefs.pendingProcessorRevokes())
    }
    @Test fun wrongOwnerOrRevisionAcknowledgementNeverGrantsConsent() = runBlocking {
        server.enqueue(acknowledgement(true, 2))
        assertTrue(service.setConsent("fixture-token", manifest(), "COHERE", true) is ConsentResult.Failure)
        assertNull(prefs.processorReceipt("COHERE"))
        server.enqueue(MockResponse().setBody("{\"version\":3,\"account_snapshot\":\"other-owner\"}"))
        assertTrue(service.setConsent("fixture-token", manifest(), "COHERE", true) is ConsentResult.Failure)
        assertNull(prefs.processorReceipt("COHERE"))
    }
    private fun delayedGrant(mutation: () -> Unit) = runBlocking {
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); return acknowledgement(true, 1)
            }
        }
        val pending = async(Dispatchers.IO) { service.setConsent("fixture-token", manifest(), "COHERE", true) }
        try { assertTrue(started.await(5, TimeUnit.SECONDS)); mutation() } finally { finish.countDown() }
        assertTrue(pending.await() is ConsentResult.Failure)
        assertNull(prefs.processorReceipt("COHERE"))
    }
    @Test fun localRevocationWinsOverDelayedGrant() = delayedGrant { prefs.revokeProcessorReceipt("COHERE") }
    @Test fun accountABACannotRestoreAnOldGrant() = delayedGrant { prefs.userId = "other-owner"; prefs.userId = owner }
    @Test fun pendingRevocationFlushWorksAfterPolicyBecomesIneligible() = runBlocking {
        prefs.revokeProcessorReceipt("COHERE")
        server.enqueue(MockResponse().setBody(manifestJson(false, 4).toString()))
        server.enqueue(acknowledgement(false, 5))
        val results = service.flushPendingRevokes("fixture-token")
        assertEquals(1, results.size); assertTrue(results.single() is ConsentResult.Success)
        assertFalse("COHERE" in prefs.pendingProcessorRevokes())
    }
    @Test fun oversizedAndStalledResponseBodiesFailClosed() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(65537), 1024))
        assertTrue(service.fetchManifest("fixture-token") is ConsentResult.Failure)
        server.enqueue(MockResponse().setBody(manifestJson().toString()).setBodyDelay(2, TimeUnit.SECONDS))
        val short = AiProcessorConsentService(prefs, server.url("/").toString(), OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
        assertTrue(short.fetchManifest("fixture-token") is ConsentResult.Failure)
    }
}
