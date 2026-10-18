package com.example.data.supabase

import android.util.Base64
import com.example.security.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface AccessTokenResolution {
    data class Valid(val token: String) : AccessTokenResolution
    data object Missing : AccessTokenResolution
    data object ReauthenticationRequired : AccessTokenResolution
    data object TemporarilyUnavailable : AccessTokenResolution
}

object SupabaseSessionManager {
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun validAccessToken(preferences: AuthPreferences): String? =
        (resolveAccessToken(preferences) as? AccessTokenResolution.Valid)?.token

    suspend fun resolveAccessToken(preferences: AuthPreferences): AccessTokenResolution = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            if (!preferences.isSecureStorageAvailable) {
                return@withLock AccessTokenResolution.ReauthenticationRequired
            }
            val token = preferences.accessToken?.takeIf { it.isNotBlank() }
                ?: return@withLock AccessTokenResolution.Missing
            val storedExpiry = preferences.sessionExpiresAtSeconds
            val jwtExpiry = tokenExpiry(token)
            val expiry = if (storedExpiry > 0) storedExpiry else jwtExpiry
            if (expiry > System.currentTimeMillis() / 1000 + 90) {
                return@withLock AccessTokenResolution.Valid(token)
            }

            val refreshToken = preferences.refreshToken?.takeIf { it.isNotBlank() }
                ?: return@withLock AccessTokenResolution.ReauthenticationRequired
            try {
                val body = JSONObject().put("refresh_token", refreshToken)
                val request = Request.Builder()
                    .url("${SupabaseConfig.authUrl}/token?grant_type=refresh_token")
                    .header("apikey", SupabaseConfig.anonKey)
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withLock if (response.code == 400 || response.code == 401 || response.code == 403) {
                            AccessTokenResolution.ReauthenticationRequired
                        } else {
                            AccessTokenResolution.TemporarilyUnavailable
                        }
                    }
                    val json = JSONObject(responseBody)
                    val renewedToken = json.optString("access_token")
                    if (renewedToken.isBlank()) return@withLock AccessTokenResolution.ReauthenticationRequired
                    preferences.accessToken = renewedToken
                    preferences.refreshToken = json.optString("refresh_token").ifBlank { refreshToken }
                    val expiresIn = json.optLong("expires_in", 3600L)
                    preferences.sessionExpiresAtSeconds = json.optLong("expires_at", System.currentTimeMillis() / 1000 + expiresIn)
                    val user = json.optJSONObject("user")
                    if (user != null) {
                        preferences.userId = user.optString("id").ifBlank { preferences.userId }
                        preferences.userEmail = user.optString("email").ifBlank { preferences.userEmail }
                        preferences.accountMode = if (user.optBoolean("is_anonymous", false)) "ANONYMOUS" else "EMAIL"
                    }
                    AccessTokenResolution.Valid(renewedToken)
                }
            } catch (_: IOException) {
                AccessTokenResolution.TemporarilyUnavailable
            } catch (_: Exception) {
                AccessTokenResolution.TemporarilyUnavailable
            }
        }
    }

    private fun tokenExpiry(token: String): Long {
        return try {
            val payload = token.split('.')[1]
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(decoded).optLong("exp", 0L)
        } catch (_: Exception) {
            0L
        }
    }
}
