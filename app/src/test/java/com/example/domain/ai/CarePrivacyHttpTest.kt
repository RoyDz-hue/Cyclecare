package com.example.domain.ai

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarePrivacyHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var rpc: PrivacyRpcClient
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        rpc = PrivacyRpcClient(server.url("/").toString(), "public-test-key")
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun missingSessionNeverSendsARequest() = runBlocking {
        assertFalse(rpc.deleteCloudHealthCopy(null))
        assertFalse(rpc.updateConsents(" ", false, false, false))
        assertEquals(0, server.requestCount)
    }

    @Test fun erasureRequiresExplicitStrictSuccessAndDeletedBooleans() = runBlocking {
        for (body in listOf("{}", "{\"success\":true}", "{\"success\":\"true\",\"health_data_deleted\":true}", "{\"success\":true,\"health_data_deleted\":\"true\"}")) {
            server.enqueue(MockResponse().setBody(body))
            assertFalse(rpc.deleteCloudHealthCopy("test-token"))
        }
        server.enqueue(MockResponse().setBody("{\"success\":true,\"health_data_deleted\":true}"))
        assertTrue(rpc.deleteCloudHealthCopy("test-token"))
        assertEquals(5, server.requestCount)
    }

    @Test fun deletionSendsNoHealthPayload() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"success\":true,\"health_data_deleted\":true}"))
        assertTrue(rpc.deleteCloudHealthCopy("test-token"))
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/delete_my_cyclecare_health_data", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test fun redirectsCannotForwardPrivacyOrBearerToAnotherEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
        server.enqueue(MockResponse().setBody("{\"success\":true,\"health_data_deleted\":true}"))
        assertFalse(rpc.deleteCloudHealthCopy("test-token"))
        assertEquals(1, server.requestCount)
    }

    @Test fun overlargeChunkedOrFixedBodiesFailClosed() = runBlocking {
        val body = "{\"success\":true,\"health_data_deleted\":true,\"padding\":\"" + "x".repeat(8192) + "\"}"
        server.enqueue(MockResponse().setBody(body))
        assertFalse(rpc.deleteCloudHealthCopy("test-token"))
        server.enqueue(MockResponse().setChunkedBody(body, 512))
        assertFalse(rpc.deleteCloudHealthCopy("test-token"))
    }

    @Test fun malformedOrUnsuccessfulResponseIsNotDeletionEvidence() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json"))
        assertFalse(rpc.deleteCloudHealthCopy("test-token"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"success\":true,\"health_data_deleted\":true}"))
        assertFalse(rpc.deleteCloudHealthCopy("test-token"))
    }

    @Test fun privacyUpdatesContainOnlyExplicitChoiceBooleans() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"success\":true}"))
        assertTrue(rpc.updateConsents("test-token", false, false, false))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(3, body.length())
        assertEquals(false, body.get("p_ai_context_enabled"))
        assertEquals(false, body.get("p_cloud_sync_enabled"))
        assertEquals(false, body.get("p_image_generation_enabled"))
    }
}
