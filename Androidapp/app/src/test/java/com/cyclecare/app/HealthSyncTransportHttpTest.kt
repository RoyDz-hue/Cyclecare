package com.cyclecare.app

import com.cyclecare.app.data.supabase.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthSyncTransportHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpHealthSyncV3Transport
    private val record = "307f0423-b3a2-3450-bdb8-7dd988d09125"
    private val operation = "11111111-1111-4111-8111-111111111111"
    private fun op(kind: String = "UPSERT") = V3WireOperation(operation, record, 0, 1, kind,
        if (kind == "DELETE") null else JSONObject().put("kind", "UPSERT").put("logicalRecordId", "2026-10-19").put("value", JSONObject()), 73, "2026-10-19")
    private fun ack() = JSONObject().put("status", "applied").put("record_id", record).put("operation_id", operation)
        .put("logical_record_id", "2026-10-19").put("revision", 1).put("change_seq", 4).put("payload_hash", "a".repeat(64))
    @Before fun setup() { server = MockWebServer().also { it.start() }; transport = OkHttpHealthSyncV3Transport(server.url("/").toString(), "fixture-public") }
    @After fun close() { server.shutdown() }
    @Test fun actualWireSendsServerEpochAndLogicalIdentityWithoutClientHash() = runBlocking {
        server.enqueue(MockResponse().setBody(ack().toString()))
        assertEquals(1L, transport.apply(op(), "fixture-token").revision)
        val request = server.takeRequest(); val body = JSONObject(request.body.readUtf8())
        assertEquals("/rpc/apply_health_sync_operation_v3", request.path)
        assertEquals(73L, body.getLong("p_consent_generation")); assertEquals("2026-10-19", body.getString("p_logical_record_id"))
        assertFalse(body.has("p_payload_hash")); assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
    }
    @Test fun deleteHasExplicitIdentityButNoHistoricalHealthPayload() = runBlocking {
        server.enqueue(MockResponse().setBody(ack().toString())); transport.apply(op("DELETE"), "fixture-token")
        val body = JSONObject(server.takeRequest().body.readUtf8()); assertTrue(body.isNull("p_payload")); assertEquals("2026-10-19", body.getString("p_logical_record_id"))
    }
    @Test fun incompleteWrongAndCoercedAcknowledgementsNeverPass() = runBlocking {
        for (body in listOf(ack().apply { remove("operation_id") }, ack().put("record_id", "other"), ack().put("revision", 2), ack().put("revision", "1"), ack().put("payload_hash", "not-a-hash"))) {
            server.enqueue(MockResponse().setBody(body.toString()))
            assertTrue(runCatching { transport.apply(op(), "fixture-token") }.isFailure)
        }
    }
    @Test fun mismatchedAndMalformedConsentReceiptsAreRejected() = runBlocking {
        for (body in listOf("{}", "{\"enabled\":true,\"generation\":2}", "{\"enabled\":false,\"generation\":\"2\"}")) {
            server.enqueue(MockResponse().setBody(body)); assertTrue(runCatching { transport.setConsent(false, "fixture-token") }.isFailure)
        }
    }
    @Test fun changesRequireOrderedTypedCursorsAndIdentifiableTombstones() = runBlocking {
        fun row(seq: Any) = JSONObject().put("record_id", record).put("logical_record_id", "2026-10-19").put("revision", 2).put("change_seq", seq).put("tombstone", true).put("payload", JSONObject.NULL)
        server.enqueue(MockResponse().setBody("[${row(2)}]")); assertEquals("2026-10-19", transport.readChanges(0, 100, 73, "fixture-token").single().logicalRecordId)
        for (raw in listOf("[${row(2)},${row(2)}]", "[${row("2")}]", "[${row(2).apply { remove("logical_record_id") }}]", "{\"data\":[]}")) {
            server.enqueue(MockResponse().setBody(raw)); assertTrue(runCatching { transport.readChanges(0, 100, 73, "fixture-token") }.isFailure)
        }
    }
    @Test fun oversizedAndStalledBodiesAreBounded() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
        assertTrue(runCatching { transport.setConsent(false, "fixture-token") }.isFailure)
        val short = OkHttpHealthSyncV3Transport(server.url("/").toString(), "fixture-public", OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse().setBody("{\"enabled\":false,\"generation\":2}").setBodyDelay(1, TimeUnit.SECONDS))
        assertTrue(runCatching { short.setConsent(false, "fixture-token") }.isFailure)
    }
}
