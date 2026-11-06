package com.cyclecare.app.data.supabase

import android.util.Base64
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val MAX_TOKEN_CHARS = 16 * 1024
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    internal data class RefreshResponse(val code: Int, val body: String?)
    internal fun interface RefreshTransport { fun execute(refreshToken: String): RefreshResponse }

    /** The same bounded, no-redirect HTTP path is used for production and local mock-server tests. */
    internal fun httpRefreshTransport(authUrl: String, anonKey: String): RefreshTransport = RefreshTransport { refreshToken ->
        val request = Request.Builder().url("${authUrl.trimEnd('/')}/token?grant_type=refresh_token")
            .header("apikey", anonKey).header("Content-Type", "application/json")
            .post(JSONObject().put("refresh_token", refreshToken).toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) return@RefreshTransport RefreshResponse(response.code, null)
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES.toLong() + 1)
            val raw = if (source.buffer.size > MAX_RESPONSE_BYTES) null else source.buffer.readByteArray().toString(Charsets.UTF_8)
            RefreshResponse(response.code, raw)
        }
    }

    private val productionTransport = RefreshTransport { refreshToken ->
        httpRefreshTransport(SupabaseConfig.authUrl, SupabaseConfig.anonKey).execute(refreshToken)
    }
    @Volatile private var refreshTransport: RefreshTransport = productionTransport

    /** Internal JVM fixture only; production cannot configure the endpoint through application UI. */
    internal fun setRefreshTransportForTests(transport: RefreshTransport?) { refreshTransport = transport ?: productionTransport }

    suspend fun validAccessToken(preferences: AuthPreferences): String? =
        (resolveAccessToken(preferences) as? AccessTokenResolution.Valid)?.token

    suspend fun resolveAccessToken(preferences: AuthPreferences): AccessTokenResolution {
        val identity=preferences.rustOperationSnapshot()
        val lifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(preferences.context,identity.operation.owner)
        val job=currentCoroutineContext()[kotlinx.coroutines.Job]
        val originCurrent={job?.isActive!=false&&(preferences.publicationAdmission==null||
            (preferences.rustOperationSnapshot()==identity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(preferences.context,identity.operation.owner)==lifecycle))}
        return withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            currentCoroutineContext().ensureActive()
            if(!originCurrent())return@withLock AccessTokenResolution.TemporarilyUnavailable
            if (!preferences.isSecureStorageAvailable) return@withLock AccessTokenResolution.ReauthenticationRequired
            val token = preferences.accessToken?.takeIf { it.isNotBlank() } ?: return@withLock AccessTokenResolution.Missing
            val owner = preferences.userId?.takeIf { it.isNotBlank() } ?: return@withLock AccessTokenResolution.ReauthenticationRequired
            val session = preferences.sessionGeneration
            val storedExpiry = preferences.sessionExpiresAtSeconds
            val expiry = if (storedExpiry > 0) storedExpiry else tokenExpiry(token)
            if (expiry > System.currentTimeMillis() / 1000 + 90) {
                return@withLock if (preferences.withCurrentSession(owner, session) { preferences.accessToken == token } == true)
                    AccessTokenResolution.Valid(token) else AccessTokenResolution.TemporarilyUnavailable
            }
            val refreshToken = preferences.refreshToken?.takeIf { it.isNotBlank() }
                ?: return@withLock AccessTokenResolution.ReauthenticationRequired
            try {
                val response = refreshTransport.execute(refreshToken)
                currentCoroutineContext().ensureActive()
                if(!originCurrent())return@withLock AccessTokenResolution.TemporarilyUnavailable
                if (response.code !in 200..299) {
                    return@withLock if (response.code in setOf(400, 401, 403)) AccessTokenResolution.ReauthenticationRequired
                        else AccessTokenResolution.TemporarilyUnavailable
                }
                val raw = response.body ?: return@withLock AccessTokenResolution.TemporarilyUnavailable
                if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return@withLock AccessTokenResolution.TemporarilyUnavailable
                val json = JSONObject(raw)
                val renewedToken = json.opt("access_token") as? String ?: return@withLock AccessTokenResolution.ReauthenticationRequired
                val rotatedRefresh = if (!json.has("refresh_token")) refreshToken else
                    json.opt("refresh_token") as? String ?: return@withLock AccessTokenResolution.ReauthenticationRequired
                val user = json.optJSONObject("user") ?: return@withLock AccessTokenResolution.ReauthenticationRequired
                if (user.opt("id") != owner || !validTokenForOwner(renewedToken, owner) ||
                    rotatedRefresh.isBlank() || rotatedRefresh.length > MAX_TOKEN_CHARS || rotatedRefresh.any { it.isISOControl() }) {
                    return@withLock AccessTokenResolution.ReauthenticationRequired
                }
                val now = System.currentTimeMillis() / 1000
                val expiresAt = (json.opt("expires_at") as? Number)?.toLong() ?: 0L
                val expiresIn = (json.opt("expires_in") as? Number)?.toLong() ?: 0L
                val reportedExpiry = when {
                    expiresAt > 0L -> expiresAt
                    expiresIn in 1..604800L -> now + expiresIn
                    else -> return@withLock AccessTokenResolution.ReauthenticationRequired
                }
                if (reportedExpiry <= now || reportedExpiry > now + 604800L) return@withLock AccessTokenResolution.ReauthenticationRequired
                val newExpiry = minOf(reportedExpiry, tokenExpiry(renewedToken))
                // Admission precedes the prefs monitor. All setters execute in one
                // guarded transition, never waiting for admission while holding prefs.
                var committed=false
                val admitted=preferences.mutatePublicationIdentityIfCurrent(identity,originCurrent){
                    if(!originCurrent())return@mutatePublicationIdentityIfCurrent
                    committed = preferences.withCurrentSession(owner, session) {
                    if (preferences.accessToken != token || preferences.refreshToken != refreshToken) return@withCurrentSession false
                    preferences.accessToken = renewedToken
                    preferences.refreshToken = rotatedRefresh
                    preferences.sessionExpiresAtSeconds = newExpiry
                    preferences.userEmail = (user.opt("email") as? String)?.ifBlank { null } ?: preferences.userEmail
                    preferences.accountMode = if (user.optBoolean("is_anonymous", false)) "ANONYMOUS" else "EMAIL"
                    true
                    } == true
                }
                currentCoroutineContext().ensureActive()
                if (!admitted||!committed) return@withLock AccessTokenResolution.TemporarilyUnavailable
                AccessTokenResolution.Valid(renewedToken)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { AccessTokenResolution.TemporarilyUnavailable }
            catch (_: Exception) { AccessTokenResolution.TemporarilyUnavailable }
        }
        }
    }

    /** Structural consistency only: authenticity is established by the fixed authenticated server, not by this parser. */
    private fun validTokenForOwner(token: String, owner: String): Boolean = try {
        val pieces = token.split('.')
        token.length in 1..MAX_TOKEN_CHARS && pieces.size == 3 &&
            pieces.all { it.isNotEmpty() && it.all { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-' } } &&
            JSONObject(String(Base64.decode(pieces[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)).opt("sub") == owner &&
            tokenExpiry(token) > System.currentTimeMillis() / 1000
    } catch (_: Exception) { false }

    private fun tokenExpiry(token: String): Long = try {
        val payload = token.split('.')[1]
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        (JSONObject(decoded).opt("exp") as? Number)?.toLong() ?: 0L
    } catch (_: Exception) { 0L }
}
