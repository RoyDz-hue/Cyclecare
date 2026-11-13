package com.cyclecare.app.data.supabase

import android.net.Uri
import android.util.Base64
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

sealed class AuthResult {
    data class Success(val user: SupabaseUser, val message: String) : AuthResult()
    data class VerificationRequired(val message: String) : AuthResult()
    data class Error(val errorMessage: String) : AuthResult()
}

class SupabaseAuthService(
    private val authPreferences: AuthPreferences,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val authApiUrl: String = SupabaseConfig.authUrl,
    private val apiKey: String = SupabaseConfig.anonKey
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val isSecureStorageAvailable: Boolean get() = authPreferences.isSecureStorageAvailable

    private class RequestOrigin(val identity:AuthPreferences.RustOperationSnapshot,val lifecycle:com.cyclecare.app.data.local.OwnerDataGate.Snapshot,var job:kotlinx.coroutines.Job?=null)
    private fun requestOrigin():RequestOrigin{
        val identity=authPreferences.rustOperationSnapshot()
        return RequestOrigin(identity,com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,identity.operation.owner))
    }
    private fun originCurrent(origin:RequestOrigin)=(origin.job?.isActive!=false)&&(authPreferences.publicationAdmission==null||
        (authPreferences.rustOperationSnapshot()==origin.identity&&com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,origin.identity.operation.owner)==origin.lifecycle))
    private fun commitOrigin(origin:RequestOrigin,action:()->Unit)=authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,{originCurrent(origin)},action)
    private fun staleAttempt()=AuthResult.Error("This sign-in attempt is no longer current. Please try again.")

    companion object {
        const val AUTH_REDIRECT_URL = "cyclecare://auth/callback"
        private const val AUTH_ACTION_SIGN_UP = "SIGN_UP"
        private const val AUTH_ACTION_MAGIC_LINK = "MAGIC_LINK"
        private const val AUTH_ACTION_PASSWORD_RESET = "PASSWORD_RESET"
        private const val AUTH_ACTION_GUEST_LINK = "GUEST_LINK"
        private const val AUTH_ACTION_GOOGLE = "GOOGLE"
        private const val AUTH_ACTION_GOOGLE_LINK = "GOOGLE_LINK"
    }

    /** Build a Supabase Google OAuth URL using PKCE; existing local accounts must not be replaced. */
    fun createGoogleSignInUrl(): String = createGoogleSignInUrl(requestOrigin())
    private fun createGoogleSignInUrl(origin:RequestOrigin): String {
        check(isSecureStorageAvailable) { "Secure credential storage is unavailable. Sign-in is disabled until device security is restored." }
        if (!authPreferences.userId.isNullOrBlank()) {
            throw IllegalStateException("To preserve this device's CycleCare data, sign out before using Google sign-in. Guest account linking through Google is not enabled yet.")
        }
        val challenge = beginPkceFlow(AUTH_ACTION_GOOGLE,origin).challenge
        val encodedChallenge = URLEncoder.encode(challenge, StandardCharsets.UTF_8.name())
        return "$authApiUrl/authorize?provider=google" +
            "&redirect_to=${encodedRedirect()}" +
            "&code_challenge=$encodedChallenge&code_challenge_method=s256"
    }

    /**
     * Resolve the right Google URL for the current device state: guests get the
     * identity-link flow (keeps this account and its data), signed-out devices
     * get plain Google sign-in.
     */
    suspend fun resolveGoogleAuthUrl(): String {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        check(originCurrent(origin)){"stale_auth_attempt"}
        if (authPreferences.accountMode == "ANONYMOUS" && !authPreferences.userId.isNullOrBlank()) {
            createGoogleLinkUrl(origin)
        } else {
            createGoogleSignInUrl(origin)
        }
    }

    }

    /**
     * Supabase manual identity linking: asks the server for a Google authorize
     * URL bound to the CURRENT anonymous session, so completing the flow
     * upgrades the guest account instead of creating a new one.
     */
    private suspend fun createGoogleLinkUrl(origin:RequestOrigin): String {
        check(isSecureStorageAvailable) { "Secure credential storage is unavailable. Linking is disabled until device security is restored." }
        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: throw IllegalStateException("Your guest session needs a refresh. Reconnect and try again.")
        val flow = beginPkceFlow(AUTH_ACTION_GOOGLE_LINK,origin)
        val challenge = flow.challenge
        val current={originCurrent(origin)&&flow.matches(authPreferences)}
        val encodedChallenge = URLEncoder.encode(challenge, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("${authApiUrl.trimEnd('/')}/user/identities/authorize?provider=google" +
                "&redirect_to=${encodedRedirect()}" +
                "&code_challenge=$encodedChallenge&code_challenge_method=s256" +
                "&skip_http_redirect=true")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = client.newBuilder().followRedirects(false).build().newCall(request).execute()
        val body = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(current()){"stale_auth_attempt"}
        if (!response.isSuccessful) {
            authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow()}
            throw IllegalStateException(AuthErrorMessages.forResponse(body, AuthOperation.ACCOUNT_LINK))
        }
        val url = JSONObject(body).optString("url").ifBlank {
            response.header("Location").orEmpty()
        }
        if (url.isBlank()) {
            authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow()}
            throw IllegalStateException(AuthErrorMessages.fallback(AuthOperation.ACCOUNT_LINK))
        }
        return url
    }

    suspend fun signUp(email: String, password: String): AuthResult {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        if(!originCurrent(origin))return@withContext staleAttempt()
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@") || password.length < 8) {
            return@withContext AuthResult.Error("Enter a valid email and a password with at least 8 characters.")
        }
        try {
            val existingToken = SupabaseSessionManager.validAccessToken(authPreferences)
            if (!existingToken.isNullOrBlank()) {
                return@withContext if (authPreferences.accountMode == "ANONYMOUS") {
                    linkGuestAccount(normalizedEmail,origin)
                } else {
                    AuthResult.Error("You are already signed in. Sign out before creating a different account.")
                }
            }
            if (authPreferences.accountMode == "ANONYMOUS" &&
                !authPreferences.userId.isNullOrBlank() && !authPreferences.refreshToken.isNullOrBlank()
            ) {
                return@withContext AuthResult.Error("Your guest session could not be refreshed. Reconnect and retry to preserve this device's data.")
            }

            val body = JSONObject().apply {
                put("email", normalizedEmail)
                put("password", password)
                put("data", JSONObject().apply {
                    put("device_id", authPreferences.deviceId)
                    put("account_mode", "EMAIL")
                    put("app_name", "CycleCare")
                })
            }
            if(!originCurrent(origin))return@withContext staleAttempt()
            val flow=addPkceFields(body, AUTH_ACTION_SIGN_UP,origin)
            val current={originCurrent(origin)&&flow.matches(authPreferences)}
            val response = client.newCall(authRequest(
                path = "signup?redirect_to=${encodedRedirect()}",
                body = body
            )).execute()
            val responseBody = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(!current())return@withContext staleAttempt()
            if (!response.isSuccessful) {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true)})return@withContext staleAttempt()
                return@withContext AuthResult.Error(AuthErrorMessages.forResponse(responseBody, AuthOperation.SIGN_UP))
            }

            val json = JSONObject(responseBody)
            val user = json.optJSONObject("user") ?: json
            val confirmed = emailIsConfirmed(user)
            val accessToken = json.optString("access_token")
            if (accessToken.isNotBlank() && confirmed) {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true);saveSession(json, "EMAIL")})return@withContext staleAttempt()
                AuthResult.Success(readUser(user, normalizedEmail), "Your verified CycleCare account is ready.")
            } else {
                // Never retain an unverified session, even if the project is configured to issue one.
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
                    authPreferences.pendingEmail = normalizedEmail
                    authPreferences.pendingAuthAction = AUTH_ACTION_SIGN_UP
                })return@withContext staleAttempt()
                AuthResult.VerificationRequired("Check $normalizedEmail for your CycleCare verification link. The app will open automatically after you verify; you can enter only then.")
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("We couldn't create your account right now. Check your connection and try again.")
        }
    }
    }

    suspend fun signIn(email: String, password: String): AuthResult {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        if(!originCurrent(origin))return@withContext staleAttempt()
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        val normalizedEmail = email.trim().lowercase()
        try {
            val existingToken = SupabaseSessionManager.validAccessToken(authPreferences)
            if (existingToken.isNullOrBlank() && authPreferences.accountMode == "ANONYMOUS" &&
                !authPreferences.userId.isNullOrBlank() && !authPreferences.refreshToken.isNullOrBlank()
            ) {
                return@withContext AuthResult.Error("Your existing guest session could not be refreshed. Reconnect to preserve this device's cloud data before switching accounts.")
            }
            if (!existingToken.isNullOrBlank() && !authPreferences.userId.isNullOrBlank()) {
                return@withContext if (authPreferences.accountMode == "ANONYMOUS") {
                    AuthResult.Error("This device has a guest account. Use Create Account or Continue with Google to keep its progress, or sign out before switching accounts.")
                } else {
                    AuthResult.Error("You are already signed in. Sign out before switching accounts.")
                }
            }
            val body = JSONObject().apply {
                put("email", normalizedEmail)
                put("password", password)
            }
            val response = client.newCall(authRequest("token?grant_type=password", body)).execute()
            val responseBody = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(!originCurrent(origin))return@withContext staleAttempt()
            // Native-first response interpretation (NEW IMPLEMENTATION
            // wiring, 2026-11-13): NON-CLOCK decision only - error mapping,
            // parse, confirmation check, user read, RAW session-field
            // extraction. The clearSession/session WRITES stay inside
            // commitOrigin below and the expiry clock read stays inside
            // applySessionWrites' mutation - the original clock points.
            // A null reply keeps the verbatim Kotlin interpretation.
            val decision = com.cyclecare.app.rust.CycleCareCoreBridge.authSignInResponse(response.isSuccessful, responseBody, normalizedEmail)
                ?: interpretSignInResponseKotlin(response.isSuccessful, responseBody, normalizedEmail)
            when (decision.kind) {
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.ERROR ->
                    return@withContext AuthResult.Error(decision.message)
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.VERIFY -> {
                    if(!commitOrigin(origin){authPreferences.clearSession()})return@withContext staleAttempt()
                    return@withContext AuthResult.Error(decision.message)
                }
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.SUCCESS -> {
                    val writes = decision.session
                        ?: return@withContext AuthResult.Error("Unable to connect. Please check your network connection.")
                    val decidedUser = decision.user
                        ?: return@withContext AuthResult.Error("Unable to connect. Please check your network connection.")
                    if(!commitOrigin(origin){applySessionWrites(writes)})return@withContext staleAttempt()
                    AuthResult.Success(SupabaseUser(id = decidedUser.id, email = decidedUser.email,
                        emailConfirmedAt = decidedUser.emailConfirmedAt, isAnonymous = decidedUser.anonymous),
                        decision.message)
                }
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("Unable to connect. Please check your network connection.")
        }
    }
    }

    suspend fun sendMagicLink(email: String): AuthResult {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        if(!originCurrent(origin))return@withContext staleAttempt()
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            return@withContext AuthResult.Error("Enter your email address first.")
        }
        try {
            val existingToken = SupabaseSessionManager.validAccessToken(authPreferences)
            if (!existingToken.isNullOrBlank()) {
                return@withContext if (authPreferences.accountMode == "ANONYMOUS") {
                    linkGuestAccount(normalizedEmail,origin)
                } else {
                    AuthResult.Error("You are already signed in. Sign out before requesting another sign-in link.")
                }
            }
            if (authPreferences.accountMode == "ANONYMOUS" &&
                !authPreferences.userId.isNullOrBlank() && !authPreferences.refreshToken.isNullOrBlank()
            ) {
                return@withContext AuthResult.Error("Your guest session needs to reconnect before requesting a sign-in link.")
            }
            val body = JSONObject().apply {
                put("email", normalizedEmail)
                put("create_user", false)
            }
            if(!originCurrent(origin))return@withContext staleAttempt()
            val flow=addPkceFields(body, AUTH_ACTION_MAGIC_LINK,origin)
            val current={originCurrent(origin)&&flow.matches(authPreferences)}
            val response = client.newCall(authRequest("otp?redirect_to=${encodedRedirect()}", body)).execute()
            val responseBody = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(!current())return@withContext staleAttempt()
            if (!response.isSuccessful) {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true)})return@withContext staleAttempt()
                AuthResult.Error(AuthErrorMessages.forResponse(responseBody, AuthOperation.MAGIC_LINK))
            } else {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
                    authPreferences.pendingEmail = normalizedEmail
                    authPreferences.pendingAuthAction = AUTH_ACTION_MAGIC_LINK
                })return@withContext staleAttempt()
                AuthResult.VerificationRequired("A secure CycleCare sign-in link was sent to $normalizedEmail. Open it on this device to continue.")
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("Unable to connect. Please check your network connection.")
        }
    }
    }

    suspend fun sendPasswordReset(email: String): AuthResult {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        if(!originCurrent(origin))return@withContext staleAttempt()
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            return@withContext AuthResult.Error("Enter a valid email address.")
        }
        try {
            val body = JSONObject().put("email", normalizedEmail)
            if(!originCurrent(origin))return@withContext staleAttempt()
            val flow=addPkceFields(body, AUTH_ACTION_PASSWORD_RESET,origin)
            val current={originCurrent(origin)&&flow.matches(authPreferences)}
            val response = client.newCall(authRequest("recover?redirect_to=${encodedRedirect()}", body)).execute()
            val responseBody = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(!current())return@withContext staleAttempt()
            if (response.isSuccessful) {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
                    authPreferences.pendingAuthAction = AUTH_ACTION_PASSWORD_RESET
                    authPreferences.pendingEmail = normalizedEmail
                })return@withContext staleAttempt()
                AuthResult.VerificationRequired("Password reset instructions were sent to $normalizedEmail. Open the CycleCare link on this device.")
            } else {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true)})return@withContext staleAttempt()
                AuthResult.Error(AuthErrorMessages.forResponse(responseBody, AuthOperation.PASSWORD_RESET))
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("Unable to connect. Please check your network connection.")
        }
    }
    }

    /** Create a real Supabase anonymous user before allowing guest access. */
    suspend fun continueAsGuest(): AuthResult {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        if(!originCurrent(origin))return@withContext staleAttempt()
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        try {
            val current = SupabaseSessionManager.validAccessToken(authPreferences)
            if (authPreferences.accountMode != "ANONYMOUS") {
                return@withContext AuthResult.Error("This device is signed in to an email account. Sign out before starting a separate guest account.")
            }
            if (!current.isNullOrBlank() && !authPreferences.userId.isNullOrBlank()) {
                    return@withContext AuthResult.Success(
                        SupabaseUser(id = authPreferences.userId!!, isAnonymous = true),
                        "Your private guest session is ready."
                    )
            }
            if (authPreferences.accountMode == "ANONYMOUS" &&
                !authPreferences.userId.isNullOrBlank() && !authPreferences.refreshToken.isNullOrBlank()
            ) {
                return@withContext AuthResult.Error("Your existing guest session could not be refreshed. Reconnect to preserve this device's cloud data before continuing.")
            }
            val body = JSONObject().put("data", JSONObject().apply {
                put("device_id", authPreferences.deviceId)
                put("account_mode", "ANONYMOUS")
                put("app_name", "CycleCare")
            })
            val response = client.newCall(authRequest("signup", body)).execute()
            val responseBody = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(!originCurrent(origin))return@withContext staleAttempt()
            if (!response.isSuccessful) {
                return@withContext AuthResult.Error(AuthErrorMessages.forResponse(responseBody, AuthOperation.GUEST))
            }
            val json = JSONObject(responseBody)
            val user = json.optJSONObject("user") ?: json
            val userId = user.optString("id")
            val accessToken = json.optString("access_token")
            if (userId.isBlank() || accessToken.isBlank()) {
                return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.GUEST))
            }
            val committed=commitOrigin(origin) {
                saveSession(json, "ANONYMOUS")
                authPreferences.userEmail = null
            }
            if(!committed)return@withContext staleAttempt()
            AuthResult.Success(SupabaseUser(id = userId, isAnonymous = true), "Your private guest session is ready.")
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("A secure guest session needs an internet connection. Please retry when online.")
        }
    }
    }

    /** Handles the native URI returned by Supabase after email verification or magic-link auth. */
    suspend fun completeEmailLink(uri: Uri): AuthResult {
        val callbackJob=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val callbackContext=authPreferences.rustOperationSnapshot()
        val callbackFlow=authPreferences.googlePkceVerifier to authPreferences.pendingAuthAction
        val callbackLifecycle=com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,callbackContext.operation.owner)
        val callbackCurrent={callbackJob?.isActive!=false&&(authPreferences.publicationAdmission==null||
            (callbackFlow==(authPreferences.googlePkceVerifier to authPreferences.pendingAuthAction)&&
            callbackLifecycle==com.cyclecare.app.data.local.OwnerDataGate.snapshot(authPreferences.context,callbackContext.operation.owner)))}
        return withContext(Dispatchers.IO) {
        if(authPreferences.publicationAdmission!=null&&
            (authPreferences.rustOperationSnapshot()!=callbackContext||!callbackCurrent()))
            return@withContext AuthResult.Error("This sign-in attempt is no longer current. Please try again.")
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        if (parseAuthCallbackParameters(uri) == null) {
            return@withContext AuthResult.Error("This is not a valid CycleCare sign-in link.")
        }
        val parameters = parseAuthCallbackParameters(uri) ?: return@withContext AuthResult.Error("This is not a valid CycleCare sign-in link.")
        fun value(key: String): String? = parameters[key]

        val linkError = value("error_description") ?: value("error")
        if (!linkError.isNullOrBlank()) return@withContext AuthResult.Error(AuthErrorMessages.forResponse(linkError.replace('+', ' '), AuthOperation.CALLBACK))
        val authCode = value("code").orEmpty()
        if (authCode.isNotBlank()) {
            if (value("access_token") != null || value("refresh_token") != null) {
                return@withContext AuthResult.Error("This CycleCare sign-in link contains conflicting session data. Request a new link.")
            }
            return@withContext completeAuthCode(authCode,callbackContext,callbackCurrent)
        }
        val accessToken = value("access_token").orEmpty()
        val refreshToken = value("refresh_token").orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) return@withContext AuthResult.Error(AuthErrorMessages.forResponse("expired link", AuthOperation.CALLBACK))

        try {
            val userRequest = Request.Builder()
                .url("$authApiUrl/user")
                .header("apikey", apiKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val userResponse = client.newCall(userRequest).execute()
            val userBody = userResponse.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!userResponse.isSuccessful) return@withContext AuthResult.Error(AuthErrorMessages.forResponse(userBody, AuthOperation.CALLBACK))
            val user = JSONObject(userBody)
            if (user.optString("id").isBlank()) return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.CALLBACK))
            val isAnonymous = user.optBoolean("is_anonymous", false)
            if (!isAnonymous && !emailIsConfirmed(user)) {
                return@withContext AuthResult.Error("Email verification is not complete yet. Open the newest link from your inbox.")
            }
            if (authPreferences.isPendingGuestEmailLink &&
                !authPreferences.userId.isNullOrBlank() && authPreferences.userId != user.optString("id")
            ) {
                return@withContext AuthResult.Error("This link belongs to a different account. Request a new CycleCare link on this device.")
            }

            val linkedGuest = authPreferences.isPendingGuestEmailLink && !isAnonymous
            val passwordRecovery = value("type").equals("recovery", ignoreCase = true) || authPreferences.pendingAuthAction == AUTH_ACTION_PASSWORD_RESET
            val email = user.optString("email").ifBlank { authPreferences.pendingEmail.orEmpty() }
            val committed=authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent) {
            authPreferences.accessToken = accessToken
            authPreferences.refreshToken = refreshToken
            authPreferences.sessionExpiresAtSeconds = value("expires_at")?.toLongOrNull()
                ?: ((System.currentTimeMillis() / 1000) + (value("expires_in")?.toLongOrNull() ?: 3600L))
            authPreferences.userId = user.optString("id")
            authPreferences.userEmail = email.ifBlank { null }
            authPreferences.accountMode = if (isAnonymous) "ANONYMOUS" else "EMAIL"
            authPreferences.isPendingGuestEmailLink = false
            authPreferences.pendingEmail = null
            clearPendingAuthFlow()
            authPreferences.needsPasswordSetup = linkedGuest || passwordRecovery
            }
            if(!committed)return@withContext AuthResult.Error("This sign-in attempt is no longer current. Please try again.")
            AuthResult.Success(
                readUser(user, email),
                when {
                    linkedGuest -> "Your guest data is now linked to your verified email. Set a password or use email sign-in."
                    passwordRecovery -> "Your reset link is verified. Set a new password to finish."
                    else -> "Your CycleCare email is verified and you are signed in."
                }
            )
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("We couldn't verify this CycleCare link right now. Check your connection and try again.")
        }
    }

    }

    suspend fun setPassword(password: String): AuthResult {
        val origin=requestOrigin().also{it.job=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]}
        return withContext(Dispatchers.IO) {
        if(!originCurrent(origin))return@withContext staleAttempt()
        if (!isSecureStorageAvailable) return@withContext secureStorageUnavailable()
        if (password.length < 8) return@withContext AuthResult.Error("Use a password with at least 8 characters.")
        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext AuthResult.Error("Your verified session has expired. Sign in again to set a password.")
        try {
            val request = Request.Builder()
                .url("$authApiUrl/user")
                .header("apikey", apiKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .put(JSONObject().put("password", password).toString().toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if(!originCurrent(origin))return@withContext staleAttempt()
            if (!response.isSuccessful) return@withContext AuthResult.Error(AuthErrorMessages.forResponse(body, AuthOperation.SET_PASSWORD))
            if(!commitOrigin(origin){authPreferences.needsPasswordSetup = false})return@withContext staleAttempt()
            val user = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            AuthResult.Success(readUser(user, authPreferences.userEmail.orEmpty()), "Your password is set.")
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error("Unable to connect. Please try again.")
        }
    }
    }

    suspend fun isCycleCareAdmin(): Boolean = withContext(Dispatchers.IO) {
        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return@withContext false
        try {
            val request = Request.Builder()
                .url("${SupabaseConfig.restUrl}/rpc/is_cyclecare_admin")
                .header("apikey", apiKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string()?.trim().equals("true", ignoreCase = true)
            }
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            false
        }
    }

    fun signOut() {
        authPreferences.clearSession()
    }

    private suspend fun linkGuestAccount(email: String,origin:RequestOrigin): AuthResult {
        if(!originCurrent(origin))return staleAttempt()
        val token = SupabaseSessionManager.validAccessToken(authPreferences)
            ?: return AuthResult.Error("Your guest session expired. Continue as guest again, then link your email.")
        val body = JSONObject().put("email", email)
        if(!originCurrent(origin))return staleAttempt()
        val flow=addPkceFields(body, AUTH_ACTION_GUEST_LINK,origin)
        val current={originCurrent(origin)&&flow.matches(authPreferences)}
        val request = Request.Builder()
            .url("$authApiUrl/user?redirect_to=${encodedRedirect()}")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .put(body.toString().toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(!current())return staleAttempt()
        if (!response.isSuccessful) {
            if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow()})return staleAttempt()
            return AuthResult.Error(AuthErrorMessages.forResponse(responseBody, AuthOperation.ACCOUNT_LINK))
        }
        if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
            authPreferences.pendingEmail = email
            authPreferences.isPendingGuestEmailLink = true
            authPreferences.pendingAuthAction = AUTH_ACTION_GUEST_LINK
        })return staleAttempt()
        return AuthResult.VerificationRequired("A verification link was sent to $email. Open it on this device to attach this guest data to your account; you will not enter until verification completes.")
    }

    private suspend fun completeAuthCode(authCode: String, callbackContext:AuthPreferences.RustOperationSnapshot,callbackCurrent:()->Boolean): AuthResult {
        val verifier = authPreferences.googlePkceVerifier
            ?: return AuthResult.Error(AuthErrorMessages.forResponse("expired code", AuthOperation.CALLBACK))
        val action = authPreferences.pendingAuthAction ?:
            if (authPreferences.isPendingGuestEmailLink) AUTH_ACTION_GUEST_LINK else AUTH_ACTION_GOOGLE
        val operation = operationForAction(action)
        val linkingGuest = (action == AUTH_ACTION_GUEST_LINK && authPreferences.isPendingGuestEmailLink) ||
            action == AUTH_ACTION_GOOGLE_LINK
        val existingUserId = authPreferences.userId
        if (!existingUserId.isNullOrBlank() && !linkingGuest) {
            authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow()}
            return AuthResult.Error("An account is already signed in on this device. Sign out before switching accounts.")
        }
        return try {
            val request = authRequest("token?grant_type=pkce", JSONObject().apply {
                put("auth_code", authCode)
                put("code_verifier", verifier)
            })
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow(clearPendingEmail = true)}
                return AuthResult.Error(AuthErrorMessages.forResponse(body, operation))
            }
            val json = JSONObject(body)
            val user = json.optJSONObject("user") ?: JSONObject()
            val userId = user.optString("id")
            if (json.optString("access_token").isBlank() || userId.isBlank()) {
                authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow(clearPendingEmail = true)}
                return AuthResult.Error(AuthErrorMessages.fallback(operation))
            }
            if (linkingGuest && !existingUserId.isNullOrBlank() && existingUserId != userId) {
                // A link callback must upgrade THIS account, never switch to another one.
                authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow(clearPendingEmail = true)}
                return AuthResult.Error("This link belongs to a different account. Request a new CycleCare link on this device.")
            }
            if (!emailIsConfirmed(user)) {
                authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow(clearPendingEmail = true)}
                return AuthResult.Error("Please verify your email using the CycleCare link before continuing.")
            }
            val email = user.optString("email").ifBlank { authPreferences.pendingEmail.orEmpty() }
            val committed=authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){
                saveSession(json, "EMAIL")
                authPreferences.isPendingGuestEmailLink = false
                clearPendingAuthFlow(clearPendingEmail = true)
                authPreferences.needsPasswordSetup = action == AUTH_ACTION_GUEST_LINK || action == AUTH_ACTION_PASSWORD_RESET
            }
            if(!committed)return AuthResult.Error("This sign-in attempt is no longer current. Please try again.")
            AuthResult.Success(readUser(user, email), successMessageForAction(action))
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error(AuthErrorMessages.fallback(operation))
        }
    }

    private fun secureStorageUnavailable(): AuthResult.Error =
        AuthResult.Error("Secure credential storage is unavailable on this device. Sign-in, cloud sync, and app lock are disabled until device security is restored.")

    internal fun parseAuthCallbackParameters(uri: Uri): Map<String, String>? {
        if (uri.scheme != "cyclecare" || uri.host != "auth" || uri.path != "/callback" ||
            uri.userInfo != null || uri.port != -1
        ) return null

        val allowed = setOf(
            "code", "access_token", "refresh_token", "expires_at", "expires_in", "token_type",
            "type", "error", "error_description", "error_code", "error_uri", "provider_token",
            "provider_refresh_token", "state", "scope"
        )
        val result = linkedMapOf<String, String>()
        fun absorb(raw: String?): Boolean {
            if (raw.isNullOrEmpty()) return true
            for (part in raw.split('&')) {
                if (part.isEmpty()) return false
                val separator = part.indexOf('=')
                val key = Uri.decode(if (separator >= 0) part.substring(0, separator) else part)
                val value = Uri.decode(if (separator >= 0) part.substring(separator + 1) else "")
                if (key !in allowed || key in result) return false
                result[key] = value
            }
            return true
        }
        if (!absorb(uri.encodedQuery) || !absorb(uri.encodedFragment)) return null
        if (result.containsKey("code") && (result.containsKey("access_token") || result.containsKey("refresh_token"))) return null
        return result
    }

    /** Synthetic scheduling probe only. No production caller installs it. */
    internal var afterPkceCreatedForTests:(()->Unit)?=null
    private data class PkceFlow(val challenge:String,val verifier:String,val action:String){
        fun matches(preferences:AuthPreferences)=verifier==preferences.googlePkceVerifier&&action==preferences.pendingAuthAction
    }
    private fun beginPkceFlow(action: String,origin:RequestOrigin?=null): PkceFlow {
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challengeBytes = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.encodeToString(challengeBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val write={authPreferences.googlePkceVerifier = verifier;authPreferences.pendingAuthAction = action}
        if(origin==null)authPreferences.mutatePublicationIdentity(write)
        else check(commitOrigin(origin,write)){"stale_auth_attempt"}
        val flow=PkceFlow(challenge,verifier,action)
        afterPkceCreatedForTests?.invoke()
        return flow
    }

    private fun addPkceFields(body: JSONObject, action: String,origin:RequestOrigin?=null):PkceFlow {
        val flow = beginPkceFlow(action,origin)
        body.put("code_challenge", flow.challenge)
        body.put("code_challenge_method", "s256")
        return flow
    }

    private fun clearPendingAuthFlow(clearPendingEmail: Boolean = false) = authPreferences.mutatePublicationIdentity {
        authPreferences.googlePkceVerifier = null
        authPreferences.pendingAuthAction = null
        if (clearPendingEmail) {
            authPreferences.pendingEmail = null
            authPreferences.isPendingGuestEmailLink = false
        }
    }

    private fun operationForAction(action: String): AuthOperation = when (action) {
        AUTH_ACTION_SIGN_UP -> AuthOperation.SIGN_UP
        AUTH_ACTION_MAGIC_LINK -> AuthOperation.MAGIC_LINK
        AUTH_ACTION_PASSWORD_RESET -> AuthOperation.PASSWORD_RESET
        AUTH_ACTION_GUEST_LINK -> AuthOperation.ACCOUNT_LINK
        AUTH_ACTION_GOOGLE_LINK -> AuthOperation.ACCOUNT_LINK
        else -> AuthOperation.GOOGLE
    }

    private fun successMessageForAction(action: String): String = when (action) {
        AUTH_ACTION_SIGN_UP -> "Your email is verified and your CycleCare account is ready."
        AUTH_ACTION_MAGIC_LINK -> "You're signed in to CycleCare."
        AUTH_ACTION_PASSWORD_RESET -> "Your reset link is verified. Set a new password to finish."
        AUTH_ACTION_GUEST_LINK -> "Your guest data is now linked to your verified email. Set a password or use email sign-in."
        AUTH_ACTION_GOOGLE_LINK -> "Your Google account is linked. Your guest progress stays with this account."
        else -> "Your Google account is verified and signed in to CycleCare."
    }

    private fun authRequest(path: String, body: JSONObject): Request = Request.Builder()
        .url("${authApiUrl.trimEnd('/')}/$path")
        .header("apikey", apiKey)
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody(jsonMediaType))
        .build()

    private fun encodedRedirect(): String = URLEncoder.encode(AUTH_REDIRECT_URL, StandardCharsets.UTF_8.name())

    /** Verbatim Kotlin fallback of the native sign-in interpretation (NEW
     * IMPLEMENTATION wiring, 2026-11-13): the original signIn statements,
     * with the clearSession/saveSession WRITES replaced by their raw field
     * extraction (the writes themselves stay at the call site's commitOrigin
     * points in both paths) and the JSONObject parse throw mapped to the
     * SAME connect message the original catch-all produced. */
    private fun interpretSignInResponseKotlin(isSuccessful: Boolean, responseBody: String, normalizedEmail: String): com.cyclecare.app.rust.CycleCareCoreBridge.SessionAuthDecision {
        if (!isSuccessful) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.SessionAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.ERROR,
                AuthErrorMessages.forResponse(responseBody, AuthOperation.SIGN_IN), null, null)
        }
        val json = try { JSONObject(responseBody) } catch (e: Exception) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.SessionAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.ERROR,
                "Unable to connect. Please check your network connection.", null, null)
        }
        val user = json.optJSONObject("user") ?: JSONObject()
        if (!emailIsConfirmed(user)) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.SessionAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.VERIFY,
                "Please verify your email using the CycleCare link before signing in.", null, null)
        }
        val read = readUser(user, normalizedEmail)
        // saveSession's RAW field extraction, expression for expression
        // (note: its user fallback is the WHOLE body, not readUser's empty
        // object). Blank -> null here is the SET-null / keep marker the
        // call site interprets, not a write.
        val sessionUser = json.optJSONObject("user") ?: json
        // Source: json.optLong("expires_at", now/1000 + expiresIn). Hitting
        // optLong's DEFAULT branch (missing/uncoercible) is represented as
        // the null marker so the caller computes now+expiresIn at ITS OWN
        // commit point - the same value at the same clock point.
        val rawExpiresAt = when (val e = json.opt("expires_at")) {
            is Number -> e.toLong()
            is String -> e.toLongOrNull()
            else -> null
        }
        return com.cyclecare.app.rust.CycleCareCoreBridge.SessionAuthDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.SUCCESS,
            "Successfully signed in to CycleCare.",
            com.cyclecare.app.rust.CycleCareCoreBridge.AuthUserFields(read.id, read.email, read.emailConfirmedAt, read.isAnonymous),
            com.cyclecare.app.rust.CycleCareCoreBridge.SessionFieldWrites("EMAIL",
                sessionUser.optString("id").ifBlank { null },
                sessionUser.optString("email").ifBlank { null },
                json.optString("access_token").ifBlank { null },
                json.optString("refresh_token").ifBlank { null },
                rawExpiresAt,
                json.optLong("expires_in", 3600L)))
    }

    /** Applies native-decided session writes with EXACTLY saveSession's
     * assignment semantics: accountMode set; userId kept when null
     * (source: `.ifBlank { authPreferences.userId }` read at this point in
     * the mutation); userEmail/accessToken/refreshToken set to null when
     * null (source: `.ifBlank { null }`); the expiry default's clock read
     * happens HERE - the same point the original saveSession read it. */
    private fun applySessionWrites(w: com.cyclecare.app.rust.CycleCareCoreBridge.SessionFieldWrites) = authPreferences.mutatePublicationIdentity {
        authPreferences.accountMode = w.mode
        authPreferences.userId = w.userId ?: authPreferences.userId
        authPreferences.userEmail = w.userEmail
        authPreferences.accessToken = w.accessToken
        authPreferences.refreshToken = w.refreshToken
        authPreferences.sessionExpiresAtSeconds = w.expiresAt ?: (System.currentTimeMillis() / 1000 + w.expiresIn)
    }

    private fun saveSession(json: JSONObject, mode: String) = authPreferences.mutatePublicationIdentity {
        val user = json.optJSONObject("user") ?: json
        authPreferences.accountMode = mode
        authPreferences.userId = user.optString("id").ifBlank { authPreferences.userId }
        authPreferences.userEmail = user.optString("email").ifBlank { null }
        authPreferences.accessToken = json.optString("access_token").ifBlank { null }
        authPreferences.refreshToken = json.optString("refresh_token").ifBlank { null }
        val expiresIn = json.optLong("expires_in", 3600L)
        authPreferences.sessionExpiresAtSeconds = json.optLong("expires_at", System.currentTimeMillis() / 1000 + expiresIn)
    }

    private fun emailIsConfirmed(user: JSONObject): Boolean =
        !user.optString("email_confirmed_at").isNullOrBlank() ||
            !user.optString("confirmed_at").isNullOrBlank()

    private fun readUser(json: JSONObject, fallbackEmail: String? = null): SupabaseUser = SupabaseUser(
        id = json.optString("id"),
        email = json.optString("email").ifBlank { fallbackEmail },
        emailConfirmedAt = json.optString("email_confirmed_at").ifBlank { json.optString("confirmed_at").ifBlank { null } },
        isAnonymous = json.optBoolean("is_anonymous", false)
    )

}
