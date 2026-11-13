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
                // Native-first validation (NEW IMPLEMENTATION wiring,
                // 2026-11-13): the PURE decision segment only - transport,
                // refreshMutex, publication-identity admission and the
                // compare-and-set commit below stay Kotlin-side. nowSecs is
                // captured at block entry for the native path; the Kotlin
                // fallback reads the clock at its original mid-block
                // position (same instant to second granularity; only a
                // token whose exp equals a crossed second boundary could
                // shift, behavior class unchanged). A null native reply
                // keeps the verbatim Kotlin block, statement for statement.
                val refreshValidation = com.cyclecare.app.rust.CycleCareCoreBridge.sessionRefreshValidation(
                    response.code, response.body, owner, refreshToken, System.currentTimeMillis() / 1000)?.let {
                    when (it.kind) {
                        com.cyclecare.app.rust.CycleCareCoreBridge.SessionRefreshValidation.Kind.VALID ->
                            RefreshValidation.Proceed(
                                it.accessToken ?: return@withLock AccessTokenResolution.TemporarilyUnavailable,
                                it.rotatedRefresh ?: refreshToken, it.expiry, it.userEmail, it.anonymous)
                        com.cyclecare.app.rust.CycleCareCoreBridge.SessionRefreshValidation.Kind.REAUTH ->
                            RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
                        com.cyclecare.app.rust.CycleCareCoreBridge.SessionRefreshValidation.Kind.UNAVAILABLE ->
                            RefreshValidation.Terminal(AccessTokenResolution.TemporarilyUnavailable)
                    }
                } ?: validateRefreshResponseKotlin(response.code, response.body, owner, refreshToken)
                val renewedToken: String
                val rotatedRefresh: String
                val newExpiry: Long
                val refreshedEmail: String?
                val refreshedAnonymous: Boolean
                when (refreshValidation) {
                    is RefreshValidation.Terminal -> return@withLock refreshValidation.resolution
                    is RefreshValidation.Proceed -> {
                        renewedToken = refreshValidation.renewedToken
                        rotatedRefresh = refreshValidation.rotatedRefresh
                        newExpiry = refreshValidation.newExpiry
                        refreshedEmail = refreshValidation.userEmail
                        refreshedAnonymous = refreshValidation.anonymous
                    }
                }
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
                    preferences.userEmail = refreshedEmail ?: preferences.userEmail
                    preferences.accountMode = if (refreshedAnonymous) "ANONYMOUS" else "EMAIL"
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

    /** Result of the refresh-response validation segment: Proceed carries
     * the validated fields for the guarded commit (rotatedRefresh is the
     * EFFECTIVE refresh token - the old one when the response did not
     * rotate; userEmail null = keep the stored value); Terminal maps the
     * source's early returns. */
    private sealed interface RefreshValidation {
        data class Proceed(
            val renewedToken: String,
            val rotatedRefresh: String,
            val newExpiry: Long,
            val userEmail: String?,
            val anonymous: Boolean,
        ) : RefreshValidation
        data class Terminal(val resolution: AccessTokenResolution) : RefreshValidation
    }

    /** Verbatim Kotlin fallback of the native refresh-response validation
     * (NEW IMPLEMENTATION wiring, 2026-11-13): the original resolveAccessToken
     * statements, with each return@withLock mapped to Terminal and the
     * JSONObject(raw) parse failure - which the original code reached its
     * outer catch (_: Exception) for - mapped to the SAME
     * TemporarilyUnavailable outcome it produced there. validTokenForOwner
     * and tokenExpiry keep their own (native-first) catch-alls and cannot
     * throw from here, so no reachable exception path is altered. */
    private fun validateRefreshResponseKotlin(code: Int, body: String?, owner: String, refreshToken: String): RefreshValidation {
        if (code !in 200..299) {
            return RefreshValidation.Terminal(if (code in setOf(400, 401, 403)) AccessTokenResolution.ReauthenticationRequired
                else AccessTokenResolution.TemporarilyUnavailable)
        }
        val raw = body ?: return RefreshValidation.Terminal(AccessTokenResolution.TemporarilyUnavailable)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) return RefreshValidation.Terminal(AccessTokenResolution.TemporarilyUnavailable)
        val json = try { JSONObject(raw) } catch (e: Exception) { return RefreshValidation.Terminal(AccessTokenResolution.TemporarilyUnavailable) }
        val renewedToken = json.opt("access_token") as? String ?: return RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
        val rotatedRefresh = if (!json.has("refresh_token")) refreshToken else
            json.opt("refresh_token") as? String ?: return RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
        val user = json.optJSONObject("user") ?: return RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
        if (user.opt("id") != owner || !validTokenForOwner(renewedToken, owner) ||
            rotatedRefresh.isBlank() || rotatedRefresh.length > MAX_TOKEN_CHARS || rotatedRefresh.any { it.isISOControl() }) {
            return RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
        }
        val now = System.currentTimeMillis() / 1000
        val expiresAt = (json.opt("expires_at") as? Number)?.toLong() ?: 0L
        val expiresIn = (json.opt("expires_in") as? Number)?.toLong() ?: 0L
        val reportedExpiry = when {
            expiresAt > 0L -> expiresAt
            expiresIn in 1..604800L -> now + expiresIn
            else -> return RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
        }
        if (reportedExpiry <= now || reportedExpiry > now + 604800L) return RefreshValidation.Terminal(AccessTokenResolution.ReauthenticationRequired)
        val newExpiry = minOf(reportedExpiry, tokenExpiry(renewedToken))
        return RefreshValidation.Proceed(renewedToken, rotatedRefresh, newExpiry,
            (user.opt("email") as? String)?.ifBlank { null },
            user.optBoolean("is_anonymous", false))
    }

    /** Structural consistency only: authenticity is established by the fixed authenticated server, not by this parser. */
    private fun validTokenForOwner(token: String, owner: String): Boolean {
        // Native-first: core session_token parity port of this exact check
        // (NEW IMPLEMENTATION wiring, 2026-11-13). A null reply (native
        // unavailable/malformed) keeps the Kotlin check below, byte for byte.
        com.cyclecare.app.rust.CycleCareCoreBridge.sessionTokenValidForOwner(token, owner, System.currentTimeMillis() / 1000)?.let { return it }
        return validTokenForOwnerKotlin(token, owner)
    }

    private fun validTokenForOwnerKotlin(token: String, owner: String): Boolean = try {
        val pieces = token.split('.')
        token.length in 1..MAX_TOKEN_CHARS && pieces.size == 3 &&
            pieces.all { it.isNotEmpty() && it.all { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-' } } &&
            JSONObject(String(Base64.decode(pieces[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)).opt("sub") == owner &&
            tokenExpiryKotlin(token) > System.currentTimeMillis() / 1000
    } catch (_: Exception) { false }

    private fun tokenExpiry(token: String): Long {
        // Native-first: core session_token parity port of this exact parse
        // (NEW IMPLEMENTATION wiring, 2026-11-13). A null reply (native
        // unavailable/malformed) keeps the Kotlin parse below, byte for byte.
        com.cyclecare.app.rust.CycleCareCoreBridge.sessionTokenExpiry(token)?.let { return it }
        return tokenExpiryKotlin(token)
    }

    private fun tokenExpiryKotlin(token: String): Long = try {
        val payload = token.split('.')[1]
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        (JSONObject(decoded).opt("exp") as? Number)?.toLong() ?: 0L
    } catch (_: Exception) { 0L }
}
