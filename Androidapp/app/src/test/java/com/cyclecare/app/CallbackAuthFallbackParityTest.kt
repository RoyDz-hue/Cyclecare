package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.security.AuthPreferences
import org.json.JSONObject
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
 * auth-callback wiring (NEW IMPLEMENTATION wiring, 2026-11-13):
 * completeEmailLink's parameter interpretation and /user interpretation,
 * completeAuthCode's pre-flight and token-response interpretation. Every
 * commit and the expiry clock read stay at the call sites - NOT in these
 * seams. Reflection reaches the private twins; production callers stay
 * untouched. Written for execution on a build host; not executed in this
 * environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallbackAuthFallbackParityTest {

    private fun service(prefs: AuthPreferences): SupabaseAuthService = SupabaseAuthService(prefs)

    private fun freshPrefs(): AuthPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthPreferences(context, context.getSharedPreferences("cb-twin-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
    }

    private fun paramsTwin(prefs: AuthPreferences, parameters: Map<String, String>): CycleCareCoreBridge.CbLinkParamsDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod("interpretCbLinkParamsKotlin", Map::class.java)
        m.isAccessible = true
        return m.invoke(service(prefs), parameters) as CycleCareCoreBridge.CbLinkParamsDecision
    }

    private fun linkUserTwin(prefs: AuthPreferences, isSuccessful: Boolean, userBody: String, linkType: String?): CycleCareCoreBridge.CbLinkUserDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretCbLinkUserKotlin", Boolean::class.java, String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service(prefs), isSuccessful, userBody, linkType) as CycleCareCoreBridge.CbLinkUserDecision
    }

    private fun codePreTwin(prefs: AuthPreferences): CycleCareCoreBridge.CbCodePreDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod("interpretCbCodePreKotlin")
        m.isAccessible = true
        return m.invoke(service(prefs)) as CycleCareCoreBridge.CbCodePreDecision
    }

    private fun codeResponseTwin(prefs: AuthPreferences, action: String, linkingGuest: Boolean,
        existingUserId: String?, isSuccessful: Boolean, body: String): CycleCareCoreBridge.CbCodeResponseDecision {
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretCbCodeResponseKotlin", String::class.java, Boolean::class.java, String::class.java,
            Boolean::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service(prefs), action, linkingGuest, existingUserId, isSuccessful, body) as CycleCareCoreBridge.CbCodeResponseDecision
    }

    @Test
    fun paramsBlankErrorDescriptionDoesNotFallThrough() {
        // "error_description" PRESENT but blank: the source's ?: picks it,
        // isNullOrBlank skips the branch - no mapping of the "error" key.
        val d = paramsTwin(freshPrefs(), mapOf("error_description" to " ", "error" to "access_denied"))
        assertEquals(CycleCareCoreBridge.CbLinkParamsKind.ERROR, d.kind)
        assertEquals(AuthErrorMessagesFallbacks.expiredLink(), d.message)
    }

    @Test
    fun paramsCodeConflictAndDelegate() {
        val conflict = paramsTwin(freshPrefs(), mapOf("code" to "abc", "access_token" to "t"))
        assertEquals(CycleCareCoreBridge.CbLinkParamsKind.ERROR, conflict.kind)
        assertEquals("This CycleCare sign-in link contains conflicting session data. Request a new link.", conflict.message)
        val code = paramsTwin(freshPrefs(), mapOf("code" to "abc"))
        assertEquals(CycleCareCoreBridge.CbLinkParamsKind.CODE, code.kind)
        assertEquals("abc", code.code)
    }

    @Test
    fun paramsBlankTokensAreExpiredLink() {
        // isBlank, NOT isEmpty: a whitespace-only token takes the error path.
        val d = paramsTwin(freshPrefs(), mapOf("access_token" to " ", "refresh_token" to "r"))
        assertEquals(CycleCareCoreBridge.CbLinkParamsKind.ERROR, d.kind)
        assertEquals(AuthErrorMessagesFallbacks.expiredLink(), d.message)
    }

    @Test
    fun paramsTokensCarryRawStrings() {
        val d = paramsTwin(freshPrefs(), mapOf(
            "access_token" to "a", "refresh_token" to "r",
            "expires_at" to "1700000000x", "type" to "recovery"))
        assertEquals(CycleCareCoreBridge.CbLinkParamsKind.TOKENS, d.kind)
        assertEquals("1700000000x", d.tokens!!.expiresAt) // NOT parsed in the seam
        assertNull(d.tokens!!.expiresIn)
        assertEquals("recovery", d.tokens!!.linkType)
    }

    @Test
    fun linkUserBranchesMatchSource() {
        // Parse failure -> the catch-all message.
        assertEquals("We couldn't verify this CycleCare link right now. Check your connection and try again.",
            linkUserTwin(freshPrefs(), true, "{nope", null).message)
        // Non-object 2xx body: JSONObject ctor throws -> same message.
        assertEquals("We couldn't verify this CycleCare link right now. Check your connection and try again.",
            linkUserTwin(freshPrefs(), true, "[1]", null).message)
        // Unconfirmed non-anonymous.
        assertEquals("Email verification is not complete yet. Open the newest link from your inbox.",
            linkUserTwin(freshPrefs(), true, """{"id":"u"}""", null).message)
        // Anonymous SKIPS the confirmation check.
        val anon = linkUserTwin(freshPrefs(), true, """{"id":"u","is_anonymous":true}""", null)
        assertEquals(CycleCareCoreBridge.CbLinkUserKind.SUCCESS, anon.kind)
        assertEquals("ANONYMOUS", anon.success!!.accountMode)
        assertFalse(anon.success!!.needsPasswordSetup)
        assertEquals("Your CycleCare email is verified and you are signed in.", anon.success!!.message)
    }

    @Test
    fun linkUserRecoveryCaseFoldMatchesKotlin() {
        // ASCII mixed case IS a recovery link (real Kotlin equals ignoreCase).
        val upper = linkUserTwin(freshPrefs(), true, """{"id":"u","email_confirmed_at":"x"}""", "RECOVERY")
        assertEquals(CycleCareCoreBridge.CbLinkUserKind.SUCCESS, upper.kind)
        assertTrue(upper.success!!.passwordRecovery)
        assertTrue(upper.success!!.needsPasswordSetup)
        assertEquals("Your reset link is verified. Set a new password to finish.", upper.success!!.message)
        // The Unicode long s folds to S ("RECOVERS"), so "recoverſ" is NOT
        // recovery - the pin the native comparator must match.
        val longS = linkUserTwin(freshPrefs(), true, """{"id":"u","email_confirmed_at":"x"}""", "recoverſ")
        assertEquals(CycleCareCoreBridge.CbLinkUserKind.SUCCESS, longS.kind)
        assertFalse(longS.success!!.passwordRecovery)
        assertFalse(longS.success!!.needsPasswordSetup)
    }

    @Test
    fun linkUserPendingGuestMismatchAndLink() {
        val mismatchPrefs = freshPrefs().apply {
            isPendingGuestEmailLink = true
            userId = "u1"
        }
        assertEquals("This link belongs to a different account. Request a new CycleCare link on this device.",
            linkUserTwin(mismatchPrefs, true, """{"id":"u2","email_confirmed_at":"x"}""", null).message)
        val linkPrefs = freshPrefs().apply {
            isPendingGuestEmailLink = true
            userId = "u1"
        }
        val d = linkUserTwin(linkPrefs, true, """{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}""", null)
        assertTrue(d.success!!.linkedGuest)
        assertTrue(d.success!!.needsPasswordSetup)
        assertEquals("Your guest data is now linked to your verified email. Set a password or use email sign-in.", d.success!!.message)
    }

    @Test
    fun linkUserEmailFallsBackToPendingEmail() {
        val prefs = freshPrefs().apply { pendingEmail = "p@x.y" }
        val d = linkUserTwin(prefs, true, """{"id":"u","email_confirmed_at":"x"}""", null)
        assertEquals("p@x.y", d.success!!.email)
        assertEquals("p@x.y", d.success!!.user.email)
    }

    @Test
    fun codePreBranchesMatchSource() {
        // No verifier -> expired code, no commit.
        assertEquals(CycleCareCoreBridge.CbCodePreKind.ERROR, codePreTwin(freshPrefs()).kind)
        // Action default: guest-link pending vs plain Google.
        val guestPrefs = freshPrefs().apply {
            googlePkceVerifier = "v"
            isPendingGuestEmailLink = true
        }
        val guest = codePreTwin(guestPrefs)
        assertEquals(CycleCareCoreBridge.CbCodePreKind.OK, guest.kind)
        assertEquals("GUEST_LINK", guest.action)
        assertTrue(guest.linkingGuest)
        val googlePrefs = freshPrefs().apply { googlePkceVerifier = "v" }
        val google = codePreTwin(googlePrefs)
        assertEquals("GOOGLE", google.action)
        assertFalse(google.linkingGuest)
        // Already-signed-in gate (not linking).
        val signedInPrefs = freshPrefs().apply {
            googlePkceVerifier = "v"
            pendingAuthAction = "MAGIC_LINK"
            userId = "u1"
        }
        val gated = codePreTwin(signedInPrefs)
        assertEquals(CycleCareCoreBridge.CbCodePreKind.ERROR_CLEAR_FLOW, gated.kind)
        assertEquals("An account is already signed in on this device. Sign out before switching accounts.", gated.message)
    }

    @Test
    fun codeResponseCommitDistinctionAndSuccess() {
        // Parse failure: ERROR_PLAIN = NO commit (the source catch-all);
        // status failure: ERROR_CLEAR = the clearPendingAuthFlow(true) commit.
        assertEquals(CycleCareCoreBridge.CbCodeResponseKind.ERROR_PLAIN,
            codeResponseTwin(freshPrefs(), "MAGIC_LINK", false, null, true, "{nope").kind)
        assertEquals(CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR,
            codeResponseTwin(freshPrefs(), "MAGIC_LINK", false, null, false, "x").kind)
        // Unconfirmed: clear + verify message.
        val unconfirmed = codeResponseTwin(freshPrefs(), "MAGIC_LINK", false, null, true,
            """{"access_token":"t","user":{"id":"u"}}""")
        assertEquals(CycleCareCoreBridge.CbCodeResponseKind.ERROR_CLEAR, unconfirmed.kind)
        assertEquals("Please verify your email using the CycleCare link before continuing.", unconfirmed.message)
        // Success: saveSession's extraction (AOSP optLong grammar on the
        // string expires_at), needsPasswordSetup per action.
        val prefs = freshPrefs().apply { pendingEmail = "p@x.y" }
        val ok = codeResponseTwin(prefs, "GUEST_LINK", true, "u1", true,
            """{"access_token":"t","refresh_token":"r","expires_at":"3600.9","expires_in":1800,"user":{"id":"u1","email_confirmed_at":"x"}}""")
        assertEquals(CycleCareCoreBridge.CbCodeResponseKind.SUCCESS, ok.kind)
        assertEquals(3600L, ok.session!!.expiresAt) // "3600.9" -> (long) 3600.9
        assertEquals(1800L, ok.session!!.expiresIn)
        assertEquals("p@x.y", ok.email)
        assertTrue(ok.needsPasswordSetup)
        val magic = codeResponseTwin(freshPrefs(), "MAGIC_LINK", false, null, true,
            """{"access_token":"t","user":{"id":"u","email_confirmed_at":"x"}}""")
        assertFalse(magic.needsPasswordSetup)
        assertEquals("You're signed in to CycleCare.", magic.message)
    }

    @Test
    fun decodersRejectMalformedReplies() {
        // Missing/unknown verdict -> null (the caller keeps its Kotlin twin).
        assertNull(CycleCareCoreBridge.decodeCbLinkParamsDecision(JSONObject().put("ok", JSONObject().put("version", 1))))
        assertNull(CycleCareCoreBridge.decodeCbLinkUserDecision(JSONObject().put("ok", JSONObject().put("version", 1).put("verdict", "success"))))
        assertNull(CycleCareCoreBridge.decodeCbCodePreDecision(JSONObject().put("ok", JSONObject().put("version", 1).put("verdict", "ok").put("linkingGuest", true))))
        assertNull(CycleCareCoreBridge.decodeCbCodeResponseDecision(JSONObject().put("ok", JSONObject().put("version", 2).put("verdict", "error").put("message", "m"))))
        // A blank access token in a tokens verdict is drift -> null.
        assertNull(CycleCareCoreBridge.decodeCbLinkParamsDecision(JSONObject().put("ok", JSONObject()
            .put("version", 1).put("verdict", "tokens")
            .put("accessToken", "").put("refreshToken", "r"))))
    }
}

/** Corpus-mapped expectations resolved the way the production mapping does,
 * so these pins track the corpus instead of duplicating its strings. */
private object AuthErrorMessagesFallbacks {
    fun expiredLink(): String =
        com.cyclecare.app.data.supabase.AuthErrorMessages.forResponse("expired link",
            com.cyclecare.app.data.supabase.AuthOperation.CALLBACK)
}
