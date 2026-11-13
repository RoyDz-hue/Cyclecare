package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.supabase.AuthOperation
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Regression pins for the VERBATIM Kotlin fallback twins of the native
 * auth-flow response interpretations (NEW IMPLEMENTATION wiring,
 * 2026-11-13): sign-up, guest, set-password and the status-only flows.
 * Every commit and every clock read stays at the call sites, unchanged, in
 * BOTH paths. Reflection reaches the private twins; production callers
 * stay untouched. Written for execution on a build host; not executed in
 * this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthFlowsFallbackParityTest {

    private fun service(): SupabaseAuthService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(context, context.getSharedPreferences("flows-twin-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
        prefs.userEmail = "pref@x.y"
        return SupabaseAuthService(prefs)
    }

    private fun signUp(isSuccessful: Boolean, body: String, email: String): CycleCareCoreBridge.SignUpAuthDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretSignUpResponseKotlin", Boolean::class.java, String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service(), isSuccessful, body, email) as CycleCareCoreBridge.SignUpAuthDecision
    }

    private fun guest(isSuccessful: Boolean, body: String): CycleCareCoreBridge.GuestAuthDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretGuestResponseKotlin", Boolean::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service(), isSuccessful, body) as CycleCareCoreBridge.GuestAuthDecision
    }

    private fun setPassword(isSuccessful: Boolean, body: String): CycleCareCoreBridge.SetPasswordAuthDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretSetPasswordResponseKotlin", Boolean::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service(), isSuccessful, body) as CycleCareCoreBridge.SetPasswordAuthDecision
    }

    private fun flowStatus(operation: AuthOperation, isSuccessful: Boolean, body: String): CycleCareCoreBridge.FlowStatusDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretFlowStatusKotlin", AuthOperation::class.java, Boolean::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service(), operation, isSuccessful, body) as CycleCareCoreBridge.FlowStatusDecision
    }

    // ---- sign-up ----

    @Test
    fun signUpErrorCarriesClearPendingAndCorpusMessage() {
        val d = signUp(false, """{"error":"user_exists","error_description":"User already registered"}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.ERROR_CLEAR_PENDING, d.kind)
        assertEquals("An account with that email already exists. Sign in or reset your password.", d.message)
    }

    @Test
    fun signUpMalformedAndNonObjectArePlainCreateErrors() {
        // JSONObject(body) throws on both; the source catch-all performs NO
        // pending-flow cleanup on this path.
        for (body in listOf("{not json", "[1,2]")) {
            val d = signUp(true, body, "e@x.y")
            assertEquals(CycleCareCoreBridge.SignUpDecisionKind.ERROR_PLAIN, d.kind)
            assertEquals("We couldn't create your account right now. Check your connection and try again.", d.message)
        }
    }

    @Test
    fun signUpVerifySplits() {
        // Unconfirmed even with a token (never retain unverified), and
        // confirmed without a token.
        val unconfirmed = signUp(true, """{"access_token":"T1","user":{"id":"u1","email":"e@x.y"}}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.VERIFY, unconfirmed.kind)
        assertEquals("Check e@x.y for your CycleCare verification link. The app will open automatically after you verify; you can enter only then.", unconfirmed.message)
        val noToken = signUp(true, """{"user":{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.VERIFY, noToken.kind)
        // Whitespace-only token counts as absent (isBlank).
        val blankToken = signUp(true, """{"access_token":" ","user":{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.VERIFY, blankToken.kind)
    }

    @Test
    fun signUpUserFallbackIsWholeBody() {
        // UNLIKE signIn: no "user" key -> confirmation AND readUser AND the
        // session fields all read the top level.
        val d = signUp(true, """{"id":"u9","email":"top@x.y","email_confirmed_at":"x","access_token":"T1","refresh_token":"R2","expires_in":600}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.SUCCESS, d.kind)
        assertEquals("Your verified CycleCare account is ready.", d.message)
        assertEquals("u9", d.user!!.id)
        assertEquals("EMAIL", d.session!!.mode)
        assertEquals("u9", d.session!!.userId)
        assertEquals("top@x.y", d.session!!.userEmail)
        assertEquals("T1", d.session!!.accessToken)
        assertNull(d.session!!.expiresAt)
        assertEquals(600L, d.session!!.expiresIn)
    }

    // ---- guest ----

    @Test
    fun guestErrorMappingAndCatchAll() {
        val disabled = guest(false, """{"error":"anonymous sign-ins are disabled"}""")
        assertEquals(CycleCareCoreBridge.GuestDecisionKind.ERROR, disabled.kind)
        assertEquals("Guest access is temporarily unavailable. Try again later or sign in with email.", disabled.message)
        for (body in listOf("{not json", "[1,2]")) {
            val d = guest(true, body)
            assertEquals(CycleCareCoreBridge.GuestDecisionKind.ERROR, d.kind)
            assertEquals("A secure guest session needs an internet connection. Please retry when online.", d.message)
        }
    }

    @Test
    fun guestBlankIdOrTokenIsCorpusFallback() {
        assertEquals("Guest access is temporarily unavailable. Please try again later.",
            guest(true, """{"user":{"id":" "},"access_token":"G1"}""").message)
        assertEquals("Guest access is temporarily unavailable. Please try again later.",
            guest(true, """{"user":{"id":"anon1"},"access_token":""}""").message)
    }

    @Test
    fun guestSuccessCarriesAnonymousSession() {
        val d = guest(true, """{"id":"anon1","access_token":"G1","refresh_token":"G2"}""")
        assertEquals(CycleCareCoreBridge.GuestDecisionKind.SUCCESS, d.kind)
        assertEquals("anon1", d.userId)
        assertEquals("Your private guest session is ready.", d.message)
        assertEquals("ANONYMOUS", d.session!!.mode)
        assertEquals("anon1", d.session!!.userId)
        assertEquals("G2", d.session!!.refreshToken)
    }

    // ---- set password ----

    @Test
    fun setPasswordErrorMapping() {
        val d = setPassword(false, "{}")
        assertEquals(CycleCareCoreBridge.SetPasswordDecisionKind.ERROR, d.kind)
        assertEquals("Your password couldn't be updated right now. Please try again.", d.message)
    }

    @Test
    fun setPasswordMalformedAndNonObjectYieldEmptyUserWithPrefsEmailFallback() {
        // runCatching { JSONObject(body) }.getOrElse { JSONObject() }.
        for (body in listOf("{not json", "[1,2]")) {
            val d = setPassword(true, body)
            assertEquals(CycleCareCoreBridge.SetPasswordDecisionKind.SUCCESS, d.kind)
            assertEquals("", d.user!!.id)
            assertEquals("pref@x.y", d.user!!.email) // authPreferences.userEmail.orEmpty()
            assertEquals("Your password is set.", d.message)
        }
    }

    @Test
    fun setPasswordSuccessReadsUser() {
        val d = setPassword(true, """{"id":"u1","email":"n@x.y","email_confirmed_at":"x"}""")
        assertEquals("u1", d.user!!.id)
        assertEquals("n@x.y", d.user!!.email)
        assertEquals("x", d.user!!.emailConfirmedAt)
    }

    // ---- status-only flows ----

    @Test
    fun flowStatusSplitAndCorpusMapping() {
        assertFalse(flowStatus(AuthOperation.MAGIC_LINK, true, "").error)
        assertNull(flowStatus(AuthOperation.MAGIC_LINK, true, "").message)
        val magic = flowStatus(AuthOperation.MAGIC_LINK, false, "{}")
        assertTrue(magic.error)
        assertEquals("We couldn't send a sign-in link right now. Please try again later.", magic.message)
        val reset = flowStatus(AuthOperation.PASSWORD_RESET, false, "{}")
        assertEquals("We couldn't send password reset instructions right now. Please try again later.", reset.message)
        val link = flowStatus(AuthOperation.ACCOUNT_LINK, false, """{"error":"user_exists"}""")
        assertEquals("That email is already linked to another account. Sign out, then sign in to that account.", link.message)
    }
}
