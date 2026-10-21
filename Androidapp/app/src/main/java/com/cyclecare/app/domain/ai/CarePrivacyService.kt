package com.cyclecare.app.domain.ai

import com.cyclecare.app.data.supabase.SupabaseConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only privacy choices are transmitted here, never health observations. Fail closed on legacy servers. */
object CarePrivacyService {
    private val rpc by lazy { PrivacyRpcClient(SupabaseConfig.url, SupabaseConfig.anonKey) }
    suspend fun updateConsents(accessToken: String?, ai: Boolean, cloud: Boolean, image: Boolean): Boolean =
        rpc.updateConsents(accessToken, ai, cloud, image)
    suspend fun deleteCloudHealthCopy(accessToken: String?): Boolean = rpc.deleteCloudHealthCopy(accessToken)
}

/** Constructor injection is an internal local-test seam, not a client-selectable server setting. */
internal class PrivacyRpcClient(
    private val baseUrl: String,
    private val anonKey: String,
    httpClient: OkHttpClient = OkHttpClient()
) {
    private val client = httpClient.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun updateConsents(accessToken: String?, ai: Boolean, cloud: Boolean, image: Boolean): Boolean {
        val body = JSONObject().put("p_ai_context_enabled", ai)
            .put("p_cloud_sync_enabled", cloud).put("p_image_generation_enabled", image)
        return post(accessToken, "set_my_privacy_consents", body)?.opt("success") == true
    }

    suspend fun deleteCloudHealthCopy(accessToken: String?): Boolean {
        val receipt = post(accessToken, "delete_my_cyclecare_health_data", JSONObject()) ?: return false
        return receipt.opt("success") == true && receipt.opt("health_data_deleted") == true
    }

    private suspend fun post(accessToken: String?, function: String, payload: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank() || baseUrl.isBlank() || anonKey.isBlank()) return@withContext null
        try {
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/rest/v1/rpc/$function")
                .header("apikey", anonKey).header("Authorization", "Bearer ${accessToken.trim()}")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                if (body.contentLength() > MAX_RESPONSE_BYTES) return@withContext null
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES.toLong() + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES) return@withContext null
                JSONObject(source.buffer.readByteArray().toString(Charsets.UTF_8))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 8 * 1024 }
}
