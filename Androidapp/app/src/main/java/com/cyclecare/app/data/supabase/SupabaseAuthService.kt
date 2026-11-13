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
        // Native-first NON-CLOCK pre-guards (NEW IMPLEMENTATION wiring,
        // 2026-11-13): the secure-storage check and the signed-in guard,
        // in source order (both reads are side-effect-free). Both gates
        // throw IllegalStateException in the source; messages verbatim.
        val pre = com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleSignInPre(isSecureStorageAvailable, authPreferences.userId)
            ?: googleSignInPreKotlin()
        if (pre.kind == com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR)
            throw IllegalStateException(pre.message)
        val challenge = beginPkceFlow(AUTH_ACTION_GOOGLE,origin).challenge
        // Native-first URL assembly (URLEncoder + concatenation); the
        // challenge is Kotlin-generated and the verifier never crosses.
        return com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleAuthorizeUrl(authApiUrl, challenge, false)
            ?: googleAuthorizeUrlKotlin(challenge, false)
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
        // Native-first NON-CLOCK branch (NEW IMPLEMENTATION wiring,
        // 2026-11-13); the two reads are single captures passed through.
        val googleRoute = com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleResolve(
            authPreferences.accountMode, authPreferences.userId) ?: googleResolveKotlin()
        if (googleRoute == com.cyclecare.app.rust.CycleCareCoreBridge.GoogleResolveKind.LINK) {
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
        // Native-first NON-CLOCK gates (NEW IMPLEMENTATION wiring,
        // 2026-11-13), preserving the source's OBSERVABLE order: the
        // secure gate runs BEFORE the clock-governed token resolution (no
        // refresh network call when secure storage is unavailable), the
        // token-presence gate after it. The token itself never crosses.
        val secureGate = com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleLinkSecurePre(isSecureStorageAvailable)
            ?: googleLinkSecurePreKotlin()
        if (secureGate.kind == com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR)
            throw IllegalStateException(secureGate.message)
        val resolvedToken = SupabaseSessionManager.validAccessToken(authPreferences)
        val tokenGate = com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleLinkTokenPre(resolvedToken != null)
            ?: googleLinkTokenPreKotlin(resolvedToken != null)
        if (tokenGate.kind == com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR)
            throw IllegalStateException(tokenGate.message)
        val token = resolvedToken!!
        val flow = beginPkceFlow(AUTH_ACTION_GOOGLE_LINK,origin)
        val challenge = flow.challenge
        val current={originCurrent(origin)&&flow.matches(authPreferences)}
        // Native-first URL assembly (the identities path + skip flag).
        val authorizeUrl = com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleAuthorizeUrl(authApiUrl, challenge, true)
            ?: googleAuthorizeUrlKotlin(challenge, true)
        val request = Request.Builder()
            .url(authorizeUrl)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = client.newBuilder().followRedirects(false).build().newCall(request).execute()
        val body = response.body?.string().orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(current()){"stale_auth_attempt"}
        // Native-first NON-CLOCK response interpretation (NEW
        // IMPLEMENTATION wiring, 2026-11-13). ERROR_CLEAR carries the
        // source's clearPendingAuthFlow() commit + IllegalStateException;
        // PARSE_THROW reproduces the source's UNCAUGHT JSONObject(body)
        // throw (no commit, AOSP message, JSONException type).
        val linkDecision = com.cyclecare.app.rust.CycleCareCoreBridge.authGoogleLinkResponse(
            response.isSuccessful, body, response.header("Location"))
            ?: googleLinkResponseKotlin(response.isSuccessful, body, response.header("Location"))
        when (linkDecision.kind) {
            com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.ERROR_CLEAR -> {
                authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow()}
                throw IllegalStateException(linkDecision.message)
            }
            com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.PARSE_THROW ->
                throw org.json.JSONException(linkDecision.message)
            com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.URL -> {}
        }
        return linkDecision.url ?: throw IllegalStateException(AuthErrorMessages.fallback(AuthOperation.ACCOUNT_LINK))
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
            // Native-first NON-CLOCK interpretation (NEW IMPLEMENTATION
            // wiring, 2026-11-13); every commit stays at its original point.
            val decision = com.cyclecare.app.rust.CycleCareCoreBridge.authSignUpResponse(response.isSuccessful, responseBody, normalizedEmail)
                ?: interpretSignUpResponseKotlin(response.isSuccessful, responseBody, normalizedEmail)
            when (decision.kind) {
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.ERROR_CLEAR_PENDING -> {
                    if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true)})return@withContext staleAttempt()
                    return@withContext AuthResult.Error(decision.message)
                }
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.ERROR_PLAIN ->
                    return@withContext AuthResult.Error(decision.message)
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.VERIFY -> {
                    // Never retain an unverified session, even if the project is configured to issue one.
                    if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
                        authPreferences.pendingEmail = normalizedEmail
                        authPreferences.pendingAuthAction = AUTH_ACTION_SIGN_UP
                    })return@withContext staleAttempt()
                    AuthResult.VerificationRequired(decision.message)
                }
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.SUCCESS -> {
                    val writes = decision.session
                        ?: return@withContext AuthResult.Error("We couldn't create your account right now. Check your connection and try again.")
                    val decidedUser = decision.user
                        ?: return@withContext AuthResult.Error("We couldn't create your account right now. Check your connection and try again.")
                    if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true);applySessionWrites(writes)})return@withContext staleAttempt()
                    AuthResult.Success(SupabaseUser(id = decidedUser.id, email = decidedUser.email,
                        emailConfirmedAt = decidedUser.emailConfirmedAt, isAnonymous = decidedUser.anonymous),
                        decision.message)
                }
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
            // Native-first status split + corpus error mapping (NEW
            // IMPLEMENTATION wiring, 2026-11-13); commits and messages stay.
            val flowStatus = com.cyclecare.app.rust.CycleCareCoreBridge.authFlowStatus("MAGIC_LINK", response.isSuccessful, responseBody)
                ?: interpretFlowStatusKotlin(AuthOperation.MAGIC_LINK, response.isSuccessful, responseBody)
            if (flowStatus.error) {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true)})return@withContext staleAttempt()
                AuthResult.Error(flowStatus.message ?: AuthErrorMessages.forResponse(responseBody, AuthOperation.MAGIC_LINK))
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
            // Native-first status split + corpus error mapping (NEW
            // IMPLEMENTATION wiring, 2026-11-13); commits and messages stay.
            val flowStatus = com.cyclecare.app.rust.CycleCareCoreBridge.authFlowStatus("PASSWORD_RESET", response.isSuccessful, responseBody)
                ?: interpretFlowStatusKotlin(AuthOperation.PASSWORD_RESET, response.isSuccessful, responseBody)
            if (!flowStatus.error) {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
                    authPreferences.pendingAuthAction = AUTH_ACTION_PASSWORD_RESET
                    authPreferences.pendingEmail = normalizedEmail
                })return@withContext staleAttempt()
                AuthResult.VerificationRequired("Password reset instructions were sent to $normalizedEmail. Open the CycleCare link on this device.")
            } else {
                if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow(clearPendingEmail = true)})return@withContext staleAttempt()
                AuthResult.Error(flowStatus.message ?: AuthErrorMessages.forResponse(responseBody, AuthOperation.PASSWORD_RESET))
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
            // Native-first NON-CLOCK interpretation (NEW IMPLEMENTATION
            // wiring, 2026-11-13); the commit stays at its original point.
            val decision = com.cyclecare.app.rust.CycleCareCoreBridge.authGuestResponse(response.isSuccessful, responseBody)
                ?: interpretGuestResponseKotlin(response.isSuccessful, responseBody)
            when (decision.kind) {
                com.cyclecare.app.rust.CycleCareCoreBridge.GuestDecisionKind.ERROR ->
                    return@withContext AuthResult.Error(decision.message)
                com.cyclecare.app.rust.CycleCareCoreBridge.GuestDecisionKind.SUCCESS -> {
                    val sessionUserId = decision.userId
                        ?: return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.GUEST))
                    val writes = decision.session
                        ?: return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.GUEST))
                    val committed=commitOrigin(origin) {
                        applySessionWrites(writes)
                        authPreferences.userEmail = null
                    }
                    if(!committed)return@withContext staleAttempt()
                    AuthResult.Success(SupabaseUser(id = sessionUserId, isAnonymous = true), decision.message)
                }
            }
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
        if (callbackParameters(uri) == null) {
            return@withContext AuthResult.Error("This is not a valid CycleCare sign-in link.")
        }
        val parameters = callbackParameters(uri) ?: return@withContext AuthResult.Error("This is not a valid CycleCare sign-in link.")

        // Native-first NON-CLOCK parameter interpretation (NEW
        // IMPLEMENTATION wiring, 2026-11-13); the completeAuthCode delegate
        // and every commit stay at their original points.
        val paramsDecision = com.cyclecare.app.rust.CycleCareCoreBridge.authCbLinkParams(parameters)
            ?: interpretCbLinkParamsKotlin(parameters)
        val linkTokens = when (paramsDecision.kind) {
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.ERROR ->
                return@withContext AuthResult.Error(paramsDecision.message
                    ?: AuthErrorMessages.fallback(AuthOperation.CALLBACK))
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.CODE -> {
                val authCode = paramsDecision.code
                    ?: return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.CALLBACK))
                return@withContext completeAuthCode(authCode,callbackContext,callbackCurrent)
            }
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.TOKENS -> paramsDecision.tokens
                ?: return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.CALLBACK))
        }
        val accessToken = linkTokens.accessToken
        val refreshToken = linkTokens.refreshToken

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
            // Native-first NON-CLOCK /user interpretation (NEW
            // IMPLEMENTATION wiring, 2026-11-13); the prefs-derived inputs
            // are read at the source's original points and the commit (with
            // its expiry clock read) below is unchanged.
            val linkUserDecision = com.cyclecare.app.rust.CycleCareCoreBridge.authCbLinkUser(
                userResponse.isSuccessful, userBody,
                authPreferences.isPendingGuestEmailLink, authPreferences.userId,
                authPreferences.pendingEmail, authPreferences.pendingAuthAction, linkTokens.linkType)
                ?: interpretCbLinkUserKotlin(userResponse.isSuccessful, userBody, linkTokens.linkType)
            val linkUser = when (linkUserDecision.kind) {
                com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.ERROR ->
                    return@withContext AuthResult.Error(linkUserDecision.message
                        ?: AuthErrorMessages.fallback(AuthOperation.CALLBACK))
                com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.SUCCESS -> linkUserDecision.success
                    ?: return@withContext AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.CALLBACK))
            }
            val committed=authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent) {
            authPreferences.accessToken = accessToken
            authPreferences.refreshToken = refreshToken
            authPreferences.sessionExpiresAtSeconds = linkTokens.expiresAt?.toLongOrNull()
                ?: ((System.currentTimeMillis() / 1000) + (linkTokens.expiresIn?.toLongOrNull() ?: 3600L))
            authPreferences.userId = linkUser.userId
            authPreferences.userEmail = linkUser.email.ifBlank { null }
            authPreferences.accountMode = linkUser.accountMode
            authPreferences.isPendingGuestEmailLink = false
            authPreferences.pendingEmail = null
            clearPendingAuthFlow()
            authPreferences.needsPasswordSetup = linkUser.needsPasswordSetup
            }
            if(!committed)return@withContext AuthResult.Error("This sign-in attempt is no longer current. Please try again.")
            AuthResult.Success(
                SupabaseUser(id = linkUser.user.id, email = linkUser.user.email,
                    emailConfirmedAt = linkUser.user.emailConfirmedAt, isAnonymous = linkUser.user.anonymous),
                linkUser.message
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
            // Native-first NON-CLOCK interpretation (NEW IMPLEMENTATION
            // wiring, 2026-11-13). The userEmail fallback value is read for
            // the seam BEFORE the needsPasswordSetup commit, which does not
            // touch userEmail - the same value the source read after it.
            val decision = com.cyclecare.app.rust.CycleCareCoreBridge.authSetPasswordResponse(response.isSuccessful, body, authPreferences.userEmail.orEmpty())
                ?: interpretSetPasswordResponseKotlin(response.isSuccessful, body)
            when (decision.kind) {
                com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordDecisionKind.ERROR ->
                    return@withContext AuthResult.Error(decision.message)
                com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordDecisionKind.SUCCESS -> {
                    if(!commitOrigin(origin){authPreferences.needsPasswordSetup = false})return@withContext staleAttempt()
                    val decidedUser = decision.user
                        ?: return@withContext AuthResult.Error("Unable to connect. Please try again.")
                    AuthResult.Success(SupabaseUser(id = decidedUser.id, email = decidedUser.email,
                        emailConfirmedAt = decidedUser.emailConfirmedAt, isAnonymous = decidedUser.anonymous),
                        decision.message)
                }
            }
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
                // Single body read (the stream is consumable once), then
                // native-first interpretation (NEW IMPLEMENTATION wiring).
                val adminBody = response.body?.string()
                com.cyclecare.app.rust.CycleCareCoreBridge.authAdminResponse(response.isSuccessful, adminBody)
                    ?: adminResponseKotlin(response.isSuccessful, adminBody)
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
        // Native-first status split + corpus error mapping (NEW
        // IMPLEMENTATION wiring, 2026-11-13); commits and message stay.
        val flowStatus = com.cyclecare.app.rust.CycleCareCoreBridge.authFlowStatus("ACCOUNT_LINK", response.isSuccessful, responseBody)
            ?: interpretFlowStatusKotlin(AuthOperation.ACCOUNT_LINK, response.isSuccessful, responseBody)
        if (flowStatus.error) {
            if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){clearPendingAuthFlow()})return staleAttempt()
            return AuthResult.Error(flowStatus.message ?: AuthErrorMessages.forResponse(responseBody, AuthOperation.ACCOUNT_LINK))
        }
        if(!authPreferences.mutatePublicationIdentityIfCurrent(origin.identity,current){
            authPreferences.pendingEmail = email
            authPreferences.isPendingGuestEmailLink = true
            authPreferences.pendingAuthAction = AUTH_ACTION_GUEST_LINK
        })return staleAttempt()
        return AuthResult.VerificationRequired("A verification link was sent to $email. Open it on this device to attach this guest data to your account; you will not enter until verification completes.")
    }

    private suspend fun completeAuthCode(authCode: String, callbackContext:AuthPreferences.RustOperationSnapshot,callbackCurrent:()->Boolean): AuthResult {
        // Native-first NON-CLOCK pre-flight (NEW IMPLEMENTATION wiring,
        // 2026-11-13): verifier presence, the pendingAuthAction default,
        // linkingGuest and the already-signed-in gate. Each value is
        // captured once, in the source's original READ ORDER: verifier
        // (its first statement), then pendingAuthAction and
        // isPendingGuestEmailLink, then userId (the source read it after
        // the action default). The seam call, the request body and the
        // response seam use these captures - no prefs re-reads anywhere.
        val verifierCapture = authPreferences.googlePkceVerifier
        val pendingAuthAction = authPreferences.pendingAuthAction
        val pendingGuestEmailLink = authPreferences.isPendingGuestEmailLink
        val existingUserId = authPreferences.userId
        val pre = com.cyclecare.app.rust.CycleCareCoreBridge.authCbCodePre(
            verifierCapture != null,
            pendingAuthAction,
            pendingGuestEmailLink,
            existingUserId)
            ?: interpretCbCodePreKotlin()
        when (pre.kind) {
            com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreKind.ERROR ->
                return AuthResult.Error(pre.message ?: AuthErrorMessages.fallback(AuthOperation.CALLBACK))
            com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreKind.ERROR_CLEAR_FLOW -> {
                authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow()}
                return AuthResult.Error(pre.message ?: AuthErrorMessages.fallback(AuthOperation.CALLBACK))
            }
            com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreKind.OK -> {}
        }
        val action = pre.action ?: return AuthResult.Error(AuthErrorMessages.fallback(AuthOperation.CALLBACK))
        val linkingGuest = pre.linkingGuest
        val operation = operationForAction(action)
        // Unreachable when the pre decision worked (it returns the same
        // error first); the elvis guards the CAPTURED local, no re-read.
        // COMPILE RESTORE (2026-11-13): the pre-loss wiring redeclared
        // `val verifier = verifier ?:` (conflicting declarations). The
        // capture is renamed; the guarded non-null val keeps the source's
        // original name and the downstream code is unchanged.
        val verifier = verifierCapture
            ?: return AuthResult.Error(AuthErrorMessages.forResponse("expired code", AuthOperation.CALLBACK))
        return try {
            val request = authRequest("token?grant_type=pkce", JSONObject().apply {
                put("auth_code", authCode)
                put("code_verifier", verifier)
            })
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            // Native-first NON-CLOCK token-response interpretation (NEW
            // IMPLEMENTATION wiring, 2026-11-13); pendingEmail is read at
            // the source's original point and every commit below is
            // unchanged (saveSession's field writes via applySessionWrites,
            // its expiry clock read inside).
            val decision = com.cyclecare.app.rust.CycleCareCoreBridge.authCbCodeResponse(
                action, linkingGuest, existingUserId, authPreferences.pendingEmail,
                response.isSuccessful, body)
                ?: interpretCbCodeResponseKotlin(action, linkingGuest, existingUserId, response.isSuccessful, body)
            when (decision.kind) {
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR -> {
                    authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){clearPendingAuthFlow(clearPendingEmail = true)}
                    return AuthResult.Error(decision.message ?: AuthErrorMessages.fallback(operation))
                }
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_PLAIN ->
                    return AuthResult.Error(decision.message ?: AuthErrorMessages.fallback(operation))
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.SUCCESS -> {}
            }
            val session = decision.session ?: return AuthResult.Error(AuthErrorMessages.fallback(operation))
            val decidedUser = decision.user ?: return AuthResult.Error(AuthErrorMessages.fallback(operation))
            val committed=authPreferences.mutatePublicationIdentityIfCurrent(callbackContext,callbackCurrent){
                applySessionWrites(session)
                authPreferences.isPendingGuestEmailLink = false
                clearPendingAuthFlow(clearPendingEmail = true)
                authPreferences.needsPasswordSetup = decision.needsPasswordSetup
            }
            if(!committed)return AuthResult.Error("This sign-in attempt is no longer current. Please try again.")
            AuthResult.Success(
                SupabaseUser(id = decidedUser.id, email = decidedUser.email,
                    emailConfirmedAt = decidedUser.emailConfirmedAt, isAnonymous = decidedUser.anonymous),
                decision.message ?: AuthErrorMessages.fallback(operation))
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch (_: Exception) {
            AuthResult.Error(AuthErrorMessages.fallback(operation))
        }
    }

    private fun secureStorageUnavailable(): AuthResult.Error =
        AuthResult.Error("Secure credential storage is unavailable on this device. Sign-in, cloud sync, and app lock are disabled until device security is restored.")

    /** Native-first parseAuthCallbackParameters (NEW IMPLEMENTATION wiring,
     * 2026-11-13): the gate/absorb/conflict logic runs in the native core
     * (auth_uri::interpret_cb_parse_params) on the live Uri's accessor
     * values; android.net.Uri's own parsing stays here, and the verbatim
     * Kotlin twin below is the fallback when the native core is unavailable.
     * The source's double call in completeEmailLink is preserved (both
     * calls route through here). */
    private fun callbackParameters(uri: Uri): Map<String, String>? {
        val decision = com.cyclecare.app.rust.CycleCareCoreBridge.authCbParseParams(uri)
            ?: return parseAuthCallbackParameters(uri)
        return when (decision.kind) {
            com.cyclecare.app.rust.CycleCareCoreBridge.CbParseParamsKind.INVALID -> null
            com.cyclecare.app.rust.CycleCareCoreBridge.CbParseParamsKind.PARAMS ->
                linkedMapOf(*decision.pairs.toTypedArray())
        }
    }

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

    /** Verbatim Kotlin fallback of the native Google sign-in pre-guards
     * (NEW IMPLEMENTATION wiring, 2026-11-13): the original
     * createGoogleSignInUrl guard statements, as a decision. */
    private fun googleSignInPreKotlin(): com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision {
        if (!isSecureStorageAvailable)
            return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR,
                "Secure credential storage is unavailable. Sign-in is disabled until device security is restored.")
        if (!authPreferences.userId.isNullOrBlank())
            return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR,
                "To preserve this device's CycleCare data, sign out before using Google sign-in. Guest account linking through Google is not enabled yet.")
        return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.PROCEED, null)
    }

    /** Verbatim Kotlin fallback of the native Google link secure gate. */
    private fun googleLinkSecurePreKotlin(): com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision {
        if (!isSecureStorageAvailable)
            return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR,
                "Secure credential storage is unavailable. Linking is disabled until device security is restored.")
        return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.PROCEED, null)
    }

    /** Verbatim Kotlin fallback of the native Google link token gate. */
    private fun googleLinkTokenPreKotlin(tokenResolved: Boolean): com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision {
        if (!tokenResolved)
            return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.ERROR,
                "Your guest session needs a refresh. Reconnect and try again.")
        return com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.GooglePreKind.PROCEED, null)
    }

    /** Verbatim Kotlin fallback of the native resolveGoogleAuthUrl branch. */
    private fun googleResolveKotlin(): com.cyclecare.app.rust.CycleCareCoreBridge.GoogleResolveKind =
        if (authPreferences.accountMode == "ANONYMOUS" && !authPreferences.userId.isNullOrBlank())
            com.cyclecare.app.rust.CycleCareCoreBridge.GoogleResolveKind.LINK
        else com.cyclecare.app.rust.CycleCareCoreBridge.GoogleResolveKind.SIGN_IN

    /** Verbatim Kotlin fallback of the native authorize-URL assembly. */
    private fun googleAuthorizeUrlKotlin(challenge: String, linkFlow: Boolean): String {
        val encodedChallenge = URLEncoder.encode(challenge, StandardCharsets.UTF_8.name())
        return if (linkFlow) {
            "${authApiUrl.trimEnd('/')}/user/identities/authorize?provider=google" +
                "&redirect_to=${encodedRedirect()}" +
                "&code_challenge=$encodedChallenge&code_challenge_method=s256" +
                "&skip_http_redirect=true"
        } else {
            "$authApiUrl/authorize?provider=google" +
                "&redirect_to=${encodedRedirect()}" +
                "&code_challenge=$encodedChallenge&code_challenge_method=s256"
        }
    }

    /** Verbatim Kotlin fallback of the native Google link response
     * interpretation: the original statements (the JSONObject throw
     * propagates as PARSE_THROW, matching the uncaught source shape). */
    private fun googleLinkResponseKotlin(isSuccessful: Boolean, body: String, location: String?):
        com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseDecision {
        if (!isSuccessful)
            return com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.ERROR_CLEAR,
                AuthErrorMessages.forResponse(body, AuthOperation.ACCOUNT_LINK), null)
        val parsed = try {
            JSONObject(body).optString("url")
        } catch (e: org.json.JSONException) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.PARSE_THROW, e.message, null)
        }
        val resolved = parsed.ifBlank { location.orEmpty() }
        return if (resolved.isBlank()) {
            com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.ERROR_CLEAR,
                AuthErrorMessages.fallback(AuthOperation.ACCOUNT_LINK), null)
        } else {
            com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GoogleLinkResponseKind.URL, null, resolved)
        }
    }

    /** Verbatim Kotlin fallback of the native admin interpretation. */
    private fun adminResponseKotlin(isSuccessful: Boolean, body: String?): Boolean =
        isSuccessful && body?.trim().equals("true", ignoreCase = true)

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
        return com.cyclecare.app.rust.CycleCareCoreBridge.SessionAuthDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.AuthDecisionKind.SUCCESS,
            "Successfully signed in to CycleCare.",
            com.cyclecare.app.rust.CycleCareCoreBridge.AuthUserFields(read.id, read.email, read.emailConfirmedAt, read.isAnonymous),
            extractSessionWritesKotlin(json, "EMAIL"))
    }

    /** saveSession's RAW field extraction, expression for expression (its
     * user fallback is the WHOLE body), shared by every flow twin so the
     * fallback implementations stay ONE implementation like the native
     * side's extract_session_fields. Blank -> null here is the SET-null /
     * keep marker the call sites interpret, not a write. */
    private fun extractSessionWritesKotlin(json: JSONObject, mode: String): com.cyclecare.app.rust.CycleCareCoreBridge.SessionFieldWrites {
        val sessionUser = json.optJSONObject("user") ?: json
        // Source: json.optLong("expires_at", now/1000 + expiresIn). Hitting
        // optLong's DEFAULT branch (missing/uncoercible) is represented as
        // the null marker so the caller computes now+expiresIn at ITS OWN
        // commit point - the same value at the same clock point.
        val rawExpiresAt = when (val e = json.opt("expires_at")) {
            is Number -> e.toLong()
            // AOSP optLong -> JSON.toLong: (long) Double.parseDouble(value)
            // - the FULL Java grammar (trimmed whitespace, fractions
            // truncate, exponents and hex floats parse, NaN -> 0,
            // Infinity/overflow saturate, d/f suffixes parse), NOT
            // Long.parseLong/toLongOrNull. Only NumberFormatException hits
            // the default branch (the null marker here).
            is String -> try { java.lang.Double.parseDouble(e).toLong() } catch (ex: NumberFormatException) { null }
            else -> null
        }
        return com.cyclecare.app.rust.CycleCareCoreBridge.SessionFieldWrites(mode,
            sessionUser.optString("id").ifBlank { null },
            sessionUser.optString("email").ifBlank { null },
            json.optString("access_token").ifBlank { null },
            json.optString("refresh_token").ifBlank { null },
            rawExpiresAt,
            json.optLong("expires_in", 3600L))
    }

    /** Verbatim Kotlin fallback of the native sign-up interpretation (NEW
     * IMPLEMENTATION wiring, 2026-11-13). Note the source's parse catch-all
     * performs NO pending-flow cleanup (ErrorPlain), and its user fallback
     * is the WHOLE body - both mirrored here. */
    private fun interpretSignUpResponseKotlin(isSuccessful: Boolean, responseBody: String, normalizedEmail: String): com.cyclecare.app.rust.CycleCareCoreBridge.SignUpAuthDecision {
        if (!isSuccessful) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.SignUpAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.ERROR_CLEAR_PENDING,
                AuthErrorMessages.forResponse(responseBody, AuthOperation.SIGN_UP), null, null)
        }
        val json = try { JSONObject(responseBody) } catch (e: Exception) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.SignUpAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.ERROR_PLAIN,
                "We couldn't create your account right now. Check your connection and try again.", null, null)
        }
        val user = json.optJSONObject("user") ?: json
        val confirmed = emailIsConfirmed(user)
        val accessToken = json.optString("access_token")
        return if (accessToken.isNotBlank() && confirmed) {
            val read = readUser(user, normalizedEmail)
            com.cyclecare.app.rust.CycleCareCoreBridge.SignUpAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.SUCCESS,
                "Your verified CycleCare account is ready.",
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthUserFields(read.id, read.email, read.emailConfirmedAt, read.isAnonymous),
                extractSessionWritesKotlin(json, "EMAIL"))
        } else {
            com.cyclecare.app.rust.CycleCareCoreBridge.SignUpAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.SignUpDecisionKind.VERIFY,
                "Check $normalizedEmail for your CycleCare verification link. The app will open automatically after you verify; you can enter only then.", null, null)
        }
    }

    /** Verbatim Kotlin fallback of the native guest-session interpretation
     * (NEW IMPLEMENTATION wiring, 2026-11-13). No error path commits. */
    private fun interpretGuestResponseKotlin(isSuccessful: Boolean, responseBody: String): com.cyclecare.app.rust.CycleCareCoreBridge.GuestAuthDecision {
        if (!isSuccessful) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.GuestAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GuestDecisionKind.ERROR,
                AuthErrorMessages.forResponse(responseBody, AuthOperation.GUEST), null, null)
        }
        val json = try { JSONObject(responseBody) } catch (e: Exception) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.GuestAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GuestDecisionKind.ERROR,
                "A secure guest session needs an internet connection. Please retry when online.", null, null)
        }
        val user = json.optJSONObject("user") ?: json
        val userId = user.optString("id")
        val accessToken = json.optString("access_token")
        if (userId.isBlank() || accessToken.isBlank()) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.GuestAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.GuestDecisionKind.ERROR,
                AuthErrorMessages.fallback(AuthOperation.GUEST), null, null)
        }
        return com.cyclecare.app.rust.CycleCareCoreBridge.GuestAuthDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.GuestDecisionKind.SUCCESS,
            "Your private guest session is ready.", userId,
            extractSessionWritesKotlin(json, "ANONYMOUS"))
    }

    /** Verbatim Kotlin fallback of the native setPassword interpretation
     * (NEW IMPLEMENTATION wiring, 2026-11-13): the source's runCatching
     * parse yields the EMPTY object on any failure. */
    private fun interpretSetPasswordResponseKotlin(isSuccessful: Boolean, body: String): com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordAuthDecision {
        if (!isSuccessful) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordAuthDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordDecisionKind.ERROR,
                AuthErrorMessages.forResponse(body, AuthOperation.SET_PASSWORD), null)
        }
        val user = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        val read = readUser(user, authPreferences.userEmail.orEmpty())
        return com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordAuthDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.SetPasswordDecisionKind.SUCCESS,
            "Your password is set.",
            com.cyclecare.app.rust.CycleCareCoreBridge.AuthUserFields(read.id, read.email, read.emailConfirmedAt, read.isAnonymous))
    }

    /** Verbatim Kotlin fallback of the native status split for the
     * pending-flow operations (NEW IMPLEMENTATION wiring, 2026-11-13). */
    private fun interpretFlowStatusKotlin(operation: AuthOperation, isSuccessful: Boolean, responseBody: String): com.cyclecare.app.rust.CycleCareCoreBridge.FlowStatusDecision {
        return if (isSuccessful) {
            com.cyclecare.app.rust.CycleCareCoreBridge.FlowStatusDecision(false, null)
        } else {
            com.cyclecare.app.rust.CycleCareCoreBridge.FlowStatusDecision(true,
                AuthErrorMessages.forResponse(responseBody, operation))
        }
    }

    /** Verbatim Kotlin fallback of the native callback-parameter
     * interpretation (NEW IMPLEMENTATION wiring, 2026-11-13): the original
     * completeEmailLink pre-network branch, as a decision (the delegate and
     * commits stay at the call site). NOTE isBlank, not isEmpty, on the
     * code/token checks. */
    private fun interpretCbLinkParamsKotlin(parameters: Map<String, String>): com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsDecision {
        fun value(key: String): String? = parameters[key]
        val linkError = value("error_description") ?: value("error")
        if (!linkError.isNullOrBlank()) return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.ERROR,
            AuthErrorMessages.forResponse(linkError.replace('+', ' '), AuthOperation.CALLBACK), null, null)
        val authCode = value("code").orEmpty()
        if (authCode.isNotBlank()) {
            if (value("access_token") != null || value("refresh_token") != null) {
                return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsDecision(
                    com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.ERROR,
                    "This CycleCare sign-in link contains conflicting session data. Request a new link.", null, null)
            }
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.CODE, null, authCode, null)
        }
        val accessToken = value("access_token").orEmpty()
        val refreshToken = value("refresh_token").orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.ERROR,
            AuthErrorMessages.forResponse("expired link", AuthOperation.CALLBACK), null, null)
        return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkParamsKind.TOKENS, null, null,
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkTokens(accessToken, refreshToken,
                value("expires_at"), value("expires_in"), value("type")))
    }

    /** Verbatim Kotlin fallback of the native /user interpretation (NEW
     * IMPLEMENTATION wiring, 2026-11-13): the original completeEmailLink
     * post-network branch, reading authPreferences at the source's points.
     * The commit stays at the call site. */
    private fun interpretCbLinkUserKotlin(isSuccessful: Boolean, userBody: String, linkType: String?): com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision {
        if (!isSuccessful) return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.ERROR,
            AuthErrorMessages.forResponse(userBody, AuthOperation.CALLBACK), null)
        val user = try { JSONObject(userBody) } catch (e: Exception) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.ERROR,
                "We couldn't verify this CycleCare link right now. Check your connection and try again.", null)
        }
        if (user.optString("id").isBlank()) return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.ERROR,
            AuthErrorMessages.fallback(AuthOperation.CALLBACK), null)
        val isAnonymous = user.optBoolean("is_anonymous", false)
        if (!isAnonymous && !emailIsConfirmed(user)) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.ERROR,
                "Email verification is not complete yet. Open the newest link from your inbox.", null)
        }
        if (authPreferences.isPendingGuestEmailLink &&
            !authPreferences.userId.isNullOrBlank() && authPreferences.userId != user.optString("id")
        ) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.ERROR,
                "This link belongs to a different account. Request a new CycleCare link on this device.", null)
        }
        val linkedGuest = authPreferences.isPendingGuestEmailLink && !isAnonymous
        val passwordRecovery = linkType.equals("recovery", ignoreCase = true) || authPreferences.pendingAuthAction == AUTH_ACTION_PASSWORD_RESET
        val email = user.optString("email").ifBlank { authPreferences.pendingEmail.orEmpty() }
        val read = readUser(user, email)
        return com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserKind.SUCCESS, null,
            com.cyclecare.app.rust.CycleCareCoreBridge.CbLinkUserSuccess(
                user.optString("id"), email, isAnonymous, linkedGuest, passwordRecovery,
                linkedGuest || passwordRecovery,
                if (isAnonymous) "ANONYMOUS" else "EMAIL",
                when {
                    linkedGuest -> "Your guest data is now linked to your verified email. Set a password or use email sign-in."
                    passwordRecovery -> "Your reset link is verified. Set a new password to finish."
                    else -> "Your CycleCare email is verified and you are signed in."
                },
                com.cyclecare.app.rust.CycleCareCoreBridge.AuthUserFields(read.id, read.email, read.emailConfirmedAt, read.isAnonymous)))
    }

    /** Verbatim Kotlin fallback of the native completeAuthCode pre-flight
     * (NEW IMPLEMENTATION wiring, 2026-11-13). */
    private fun interpretCbCodePreKotlin(): com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreDecision {
        val verifier = authPreferences.googlePkceVerifier
            ?: return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreKind.ERROR,
                AuthErrorMessages.forResponse("expired code", AuthOperation.CALLBACK), null, false)
        val action = authPreferences.pendingAuthAction ?:
            if (authPreferences.isPendingGuestEmailLink) AUTH_ACTION_GUEST_LINK else AUTH_ACTION_GOOGLE
        val linkingGuest = (action == AUTH_ACTION_GUEST_LINK && authPreferences.isPendingGuestEmailLink) ||
            action == AUTH_ACTION_GOOGLE_LINK
        val existingUserId = authPreferences.userId
        if (!existingUserId.isNullOrBlank() && !linkingGuest) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreKind.ERROR_CLEAR_FLOW,
                "An account is already signed in on this device. Sign out before switching accounts.", null, false)
        }
        return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbCodePreKind.OK, null, action, linkingGuest)
    }

    /** Verbatim Kotlin fallback of the native completeAuthCode token-response
     * interpretation (NEW IMPLEMENTATION wiring, 2026-11-13). ERROR_CLEAR
     * carries the clearPendingAuthFlow(clearPendingEmail = true) commit;
     * ERROR_PLAIN has NO commit (the source's parse catch-all). */
    private fun interpretCbCodeResponseKotlin(action: String, linkingGuest: Boolean, existingUserId: String?, isSuccessful: Boolean, body: String): com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision {
        val operation = operationForAction(action)
        if (!isSuccessful) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR,
                AuthErrorMessages.forResponse(body, operation), null, false, null, null)
        }
        val json = try { JSONObject(body) } catch (e: Exception) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_PLAIN,
                AuthErrorMessages.fallback(operation), null, false, null, null)
        }
        val user = json.optJSONObject("user") ?: JSONObject()
        val userId = user.optString("id")
        if (json.optString("access_token").isBlank() || userId.isBlank()) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR,
                AuthErrorMessages.fallback(operation), null, false, null, null)
        }
        if (linkingGuest && !existingUserId.isNullOrBlank() && existingUserId != userId) {
            // A link callback must upgrade THIS account, never switch to another one.
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR,
                "This link belongs to a different account. Request a new CycleCare link on this device.", null, false, null, null)
        }
        if (!emailIsConfirmed(user)) {
            return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision(
                com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR,
                "Please verify your email using the CycleCare link before continuing.", null, false, null, null)
        }
        val email = user.optString("email").ifBlank { authPreferences.pendingEmail.orEmpty() }
        val read = readUser(user, email)
        return com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseDecision(
            com.cyclecare.app.rust.CycleCareCoreBridge.CbCodeResponseKind.SUCCESS,
            successMessageForAction(action), email,
            action == AUTH_ACTION_GUEST_LINK || action == AUTH_ACTION_PASSWORD_RESET,
            extractSessionWritesKotlin(json, "EMAIL"),
            com.cyclecare.app.rust.CycleCareCoreBridge.AuthUserFields(read.id, read.email, read.emailConfirmedAt, read.isAnonymous))
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
