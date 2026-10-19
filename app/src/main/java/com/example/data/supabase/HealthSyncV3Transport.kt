package com.example.data.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class V3WireOperation(val operationId: String, val recordId: String, val baseRevision: Long, val revision: Long, val kind: String, val payload: JSONObject?, val consentGeneration: Long, val logicalRecordId: String? = null)
data class V3WireReceipt(val status: String, val revision: Long?, val changeSeq: Long?, val payloadHash: String?, val reason: String?, val recordId: String? = null, val operationId: String? = null, val logicalRecordId: String? = null, val remotePayload: JSONObject? = null, val remoteTombstone: Boolean = false)
data class V3WireChange(val recordId: String, val revision: Long, val changeSeq: Long, val payload: JSONObject?, val tombstone: Boolean, val payloadHash: String?, val logicalRecordId: String? = null)
data class V3ConsentReceipt(val enabled: Boolean, val generation: Long, val reason: String? = null)
interface HealthSyncV3Transport {
    suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt
    suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String): List<V3WireChange> = throw UnsupportedOperationException("Explicit V3 transport required")
    suspend fun setConsent(enabled: Boolean, accessToken: String): V3ConsentReceipt = throw UnsupportedOperationException("Explicit V3 transport required")
}

class OkHttpHealthSyncV3Transport(private val baseUrl: String, private val anonKey: String, client: OkHttpClient = OkHttpClient()) : HealthSyncV3Transport {
    private val client = client.newBuilder().callTimeout(minOf(20_000, client.callTimeoutMillis.takeIf { it > 0 } ?: 20_000).toLong(), TimeUnit.MILLISECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val maxResponseBytes = 1_048_576
    private fun JSONObject.text(key: String): String = (get(key) as? String)?.takeIf { it.isNotBlank() } ?: error("v3_invalid_$key")
    private fun JSONObject.optionalText(key: String): String? = if (!has(key) || isNull(key)) null else get(key) as? String ?: error("v3_invalid_$key")
    private fun JSONObject.integer(key: String, minimum: Long = 0): Long {
        val value = get(key); require(value is Int || value is Long) { "v3_invalid_$key" }
        return (value as Number).toLong().also { require(it >= minimum) { "v3_invalid_$key" } }
    }
    private fun JSONObject.optionalInteger(key: String): Long? = if (!has(key) || isNull(key)) null else integer(key)
    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: error("v3_invalid_$key")
    private fun JSONObject.optionalPayload(): JSONObject? = if (!has("payload") || isNull("payload")) null else get("payload") as? JSONObject ?: error("v3_invalid_payload")
    private suspend fun post(path: String, body: JSONObject, accessToken: String): String = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "v3_token_required" }
        val serialized = body.toString(); require(serialized.toByteArray(Charsets.UTF_8).size <= 65536) { "v3_request_too_large" }
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).header("apikey", anonKey).header("Authorization", "Bearer $accessToken").post(serialized.toRequestBody(mediaType)).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "v3_RPC_HTTP_${response.code}" }
            val bodyResponse = response.body ?: error("v3_empty_response")
            require(bodyResponse.contentLength() <= maxResponseBytes) { "v3_response_too_large" }
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            bodyResponse.byteStream().use { stream ->
                while (true) {
                    val n = stream.read(buffer, 0, minOf(buffer.size, maxResponseBytes + 1 - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n); require(out.size() <= maxResponseBytes) { "v3_response_too_large" }
                }
            }
            out.toString(Charsets.UTF_8.name())
        }
    }
    override suspend fun apply(operation: V3WireOperation, accessToken: String): V3WireReceipt {
        val logicalId = operation.logicalRecordId ?: operation.payload?.optString("logicalRecordId")?.takeIf { it.isNotBlank() }
        require(!logicalId.isNullOrBlank()) { "v3_logical_identity_required" }
        val body = JSONObject().put("p_operation_id", operation.operationId).put("p_record_id", operation.recordId)
            .put("p_base_revision", operation.baseRevision).put("p_revision", operation.revision).put("p_kind", operation.kind)
            .put("p_payload", operation.payload ?: JSONObject.NULL).put("p_consent_generation", operation.consentGeneration).put("p_logical_record_id", logicalId)
        val r = JSONObject(post("/rpc/apply_health_sync_operation_v3", body, accessToken)); val status = r.text("status")
        require(status in setOf("applied", "duplicate", "conflict", "rejected")) { "v3_invalid_status" }
        val tombstone = if (r.has("tombstone")) r.boolean("tombstone") else false
        if (status in setOf("applied", "duplicate")) {
            require(r.text("record_id") == operation.recordId && r.text("operation_id") == operation.operationId && r.integer("revision", 1) == operation.revision && r.text("logical_record_id") == logicalId) { "v3_ack_identity_mismatch" }
            require(Regex("^[a-f0-9]{64}$").matches(r.text("payload_hash"))) { "v3_ack_hash_invalid" }
        }
        return V3WireReceipt(status, r.optionalInteger("revision"), r.optionalInteger("change_seq"), r.optionalText("payload_hash"), r.optionalText("reason"), r.optionalText("record_id"), r.optionalText("operation_id"), r.optionalText("logical_record_id"), r.optionalPayload(), tombstone)
    }
    override suspend fun readChanges(afterSeq: Long, limit: Int, consentGeneration: Long, accessToken: String): List<V3WireChange> {
        require(afterSeq >= 0 && consentGeneration > 0); val requested = limit.coerceIn(1, 100)
        val raw = post("/rpc/read_health_sync_changes", JSONObject().put("p_after_seq", afterSeq).put("p_limit", requested).put("p_consent_generation", consentGeneration), accessToken)
        val array = JSONArray(raw); require(array.length() <= requested) { "v3_changes_too_many" }
        var cursor = afterSeq
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i); val seq = o.integer("change_seq", 1); require(seq > cursor) { "v3_changes_not_ordered" }; cursor = seq
            val tombstone = o.boolean("tombstone"); val payload = o.optionalPayload()
            require(if (tombstone) payload == null else payload != null) { "v3_invalid_tombstone_payload" }
            V3WireChange(o.text("record_id"), o.integer("revision", 1), seq, payload, tombstone, o.optionalText("payload_hash"), o.text("logical_record_id"))
        }
    }
    override suspend fun setConsent(enabled: Boolean, accessToken: String): V3ConsentReceipt {
        val r = JSONObject(post("/rpc/set_health_sync_consent", JSONObject().put("p_enabled", enabled), accessToken))
        val actual = r.boolean("enabled"); require(actual == enabled) { "v3_consent_mismatch" }
        return V3ConsentReceipt(actual, r.integer("generation", 1), r.optionalText("reason"))
    }
}
