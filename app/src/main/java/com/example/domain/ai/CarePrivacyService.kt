package com.example.domain.ai

import com.example.data.supabase.SupabaseConfig
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
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun updateConsents(accessToken: String?, ai: Boolean, cloud: Boolean, image: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) return@withContext false
        try {
            val payload = JSONObject().put("p_ai_context_enabled", ai)
                .put("p_cloud_sync_enabled", cloud).put("p_image_generation_enabled", image)
            val request = Request.Builder().url("${SupabaseConfig.url}/rest/v1/rpc/set_my_privacy_consents")
                .header("apikey", SupabaseConfig.anonKey).header("Authorization", "Bearer ${accessToken.trim()}")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                body.length <= 8192 && runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    suspend fun deleteCloudHealthCopy(accessToken: String?): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) return@withContext false
        try {
            val request = Request.Builder().url("${SupabaseConfig.url}/rest/v1/rpc/delete_my_cyclecare_health_data")
                .header("apikey", SupabaseConfig.anonKey).header("Authorization", "Bearer ${accessToken.trim()}")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                body.length <= 8192 && runCatching { JSONObject(body).optBoolean("success", false) && JSONObject(body).optBoolean("health_data_deleted", false) }.getOrDefault(false)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

}
