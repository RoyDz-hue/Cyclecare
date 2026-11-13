package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the auth-flows response bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): sign-up, guest, set-password and the
 * status-only flows (magic link, password reset, account link). Drifted,
 * coerced, or defaulted fields are rejected (null -> the caller keeps its
 * verbatim Kotlin twin). The shared user/session payload strictness is
 * pinned by SessionAuthDecodeTest; these pin each op's envelope, verdicts
 * and per-op mode/userId requirements. Written for execution on a build
 * host; not executed in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthFlowsDecodeTest {

    private fun session(mode: String) = JSONObject()
        .put("mode", mode)
        .put("userId", "u1")
        .put("userEmail", "e@x.y")
        .put("accessToken", "AT")
        .put("refreshToken", "RT")
        .put("expiresAt", 1767225600L)
        .put("expiresIn", 3600)

    private fun user() = JSONObject()
        .put("id", "u1")
        .put("email", "e@x.y")
        .put("emailConfirmedAt", "x")
        .put("anonymous", false)

    // ---- sign-up ----

    private fun signUpReply(verdict: String, mutate: JSONObject.() -> Unit = {}): JSONObject {
        val ok = JSONObject().put("version", 1).put("verdict", verdict).put("message", "m")
        ok.mutate()
        return JSONObject().put("ok", ok)
    }

    @Test
    fun signUpVerdictsAccepted() {
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.ERROR_CLEAR_PENDING,
            CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("error_clear_pending"))!!.kind)
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.ERROR_PLAIN,
            CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("error"))!!.kind)
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.VERIFY,
            CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("verify"))!!.kind)
    }

    @Test
    fun signUpSuccessRequiresUserAndEmailModeSession() {
        val ok = CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("success") {
            put("user", user()); put("session", session("EMAIL"))
        })!!
        assertEquals(CycleCareCoreBridge.SignUpDecisionKind.SUCCESS, ok.kind)
        assertEquals("u1", ok.user!!.id)
        assertEquals("EMAIL", ok.session!!.mode)
        assertNull(CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("success") { put("user", user()) }))
        assertNull(CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("success") { put("session", session("EMAIL")) }))
        // An ANONYMOUS-mode session on the sign-up wire would misroute writes.
        assertNull(CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("success") {
            put("user", user()); put("session", session("ANONYMOUS"))
        }))
    }

    @Test
    fun signUpEnvelopeDriftRejected() {
        assertNull(CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("maybe")))
        assertNull(CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("error") { put("version", 2) }))
        assertNull(CycleCareCoreBridge.decodeSignUpAuthDecision(signUpReply("error") { put("message", "") }))
    }

    // ---- guest ----

    private fun guestReply(verdict: String, mutate: JSONObject.() -> Unit = {}): JSONObject {
        val ok = JSONObject().put("version", 1).put("verdict", verdict).put("message", "m")
        ok.mutate()
        return JSONObject().put("ok", ok)
    }

    @Test
    fun guestSuccessRequiresNonemptyUserIdAndAnonymousModeSession() {
        val ok = CycleCareCoreBridge.decodeGuestAuthDecision(guestReply("success") {
            put("userId", "anon1"); put("session", session("ANONYMOUS"))
        })!!
        assertEquals(CycleCareCoreBridge.GuestDecisionKind.SUCCESS, ok.kind)
        assertEquals("anon1", ok.userId)
        assertEquals("ANONYMOUS", ok.session!!.mode)
        // Blank/missing userId and wrong session mode are drift.
        assertNull(CycleCareCoreBridge.decodeGuestAuthDecision(guestReply("success") {
            put("userId", ""); put("session", session("ANONYMOUS"))
        }))
        assertNull(CycleCareCoreBridge.decodeGuestAuthDecision(guestReply("success") {
            put("session", session("ANONYMOUS"))
        }))
        assertNull(CycleCareCoreBridge.decodeGuestAuthDecision(guestReply("success") {
            put("userId", "anon1"); put("session", session("EMAIL"))
        }))
    }

    @Test
    fun guestErrorVerdictAccepted() {
        val d = CycleCareCoreBridge.decodeGuestAuthDecision(guestReply("error"))!!
        assertEquals(CycleCareCoreBridge.GuestDecisionKind.ERROR, d.kind)
        assertNull(d.userId); assertNull(d.session)
    }

    // ---- set password ----

    @Test
    fun setPasswordSuccessRequiresUser() {
        val ok = CycleCareCoreBridge.decodeSetPasswordAuthDecision(
            JSONObject().put("ok", JSONObject().put("version", 1).put("verdict", "success").put("message", "m").put("user", user())))!!
        assertEquals(CycleCareCoreBridge.SetPasswordDecisionKind.SUCCESS, ok.kind)
        assertEquals("u1", ok.user!!.id)
        assertNull(CycleCareCoreBridge.decodeSetPasswordAuthDecision(
            JSONObject().put("ok", JSONObject().put("version", 1).put("verdict", "success").put("message", "m"))))
        val err = CycleCareCoreBridge.decodeSetPasswordAuthDecision(
            JSONObject().put("ok", JSONObject().put("version", 1).put("verdict", "error").put("message", "m")))!!
        assertEquals(CycleCareCoreBridge.SetPasswordDecisionKind.ERROR, err.kind)
        assertNull(err.user)
    }

    // ---- flow status ----

    @Test
    fun flowStatusOkNeedsNoMessageErrorNeedsNonemptyMessage() {
        val proceed = CycleCareCoreBridge.decodeFlowStatusDecision(
            JSONObject("""{"ok":{"version":1,"verdict":"ok"}}"""))!!
        assertFalse(proceed.error)
        assertNull(proceed.message)
        val err = CycleCareCoreBridge.decodeFlowStatusDecision(
            JSONObject("""{"ok":{"version":1,"verdict":"error","message":"m"}}"""))!!
        assertTrue(err.error)
        assertEquals("m", err.message)
        assertNull(CycleCareCoreBridge.decodeFlowStatusDecision(
            JSONObject("""{"ok":{"version":1,"verdict":"error"}}""")))
        assertNull(CycleCareCoreBridge.decodeFlowStatusDecision(
            JSONObject("""{"ok":{"version":1,"verdict":"error","message":""}}""")))
        assertNull(CycleCareCoreBridge.decodeFlowStatusDecision(
            JSONObject("""{"ok":{"version":2,"verdict":"ok"}}""")))
        assertNull(CycleCareCoreBridge.decodeFlowStatusDecision(
            JSONObject("""{"ok":{"version":1,"verdict":"maybe"}}""")))
    }
}
