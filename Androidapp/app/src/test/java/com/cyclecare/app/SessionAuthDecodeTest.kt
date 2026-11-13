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
 * Strict-decode guards for the sign-in response-interpretation bridge
 * wiring (NEW IMPLEMENTATION, 2026-11-13): this reply carries the native
 * NON-CLOCK decision (error mapping, confirmation check, user read, RAW
 * session-field extraction) whose writes the caller then commits at its
 * original points, so any drifted, coerced, or defaulted field is rejected
 * (null -> the caller keeps its verbatim Kotlin interpretation). Written
 * for execution on a build host; not executed in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionAuthDecodeTest {

    private fun okReply(mutate: JSONObject.() -> Unit = {}): JSONObject {
        val ok = JSONObject()
            .put("version", 1)
            .put("verdict", "success")
            .put("message", "Successfully signed in to CycleCare.")
            .put("user", JSONObject()
                .put("id", "u1")
                .put("email", "e@x.y")
                .put("emailConfirmedAt", "2026-01-01T00:00:00Z")
                .put("anonymous", false))
            .put("session", JSONObject()
                .put("mode", "EMAIL")
                .put("userId", "u1")
                .put("userEmail", "e@x.y")
                .put("accessToken", "AT")
                .put("refreshToken", "RT")
                .put("expiresAt", 1767225600L)
                .put("expiresIn", 3600))
        ok.mutate()
        return JSONObject().put("ok", ok)
    }

    private fun decode(json: JSONObject) = CycleCareCoreBridge.decodeSessionAuthDecision(json)

    @Test
    fun successFullPayloadAccepted() {
        val d = decode(okReply())!!
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.SUCCESS, d.kind)
        assertEquals("Successfully signed in to CycleCare.", d.message)
        assertEquals("u1", d.user!!.id)
        assertEquals("e@x.y", d.user!!.email)
        assertEquals("2026-01-01T00:00:00Z", d.user!!.emailConfirmedAt)
        assertFalse(d.user!!.anonymous)
        assertEquals("EMAIL", d.session!!.mode)
        assertEquals("u1", d.session!!.userId)
        assertEquals("AT", d.session!!.accessToken)
        assertEquals("RT", d.session!!.refreshToken)
        assertEquals(1767225600L, d.session!!.expiresAt)
        assertEquals(3600L, d.session!!.expiresIn)
    }

    @Test
    fun successNullFieldsAcceptedAsMarkers() {
        // Null userId = KEEP current; null userEmail/tokens = SET null;
        // null expiresAt = caller computes now+expiresIn at its own clock
        // point. These are decided-write markers, not defaults.
        val d = decode(okReply {
            getJSONObject("session")
                .put("userId", JSONObject.NULL)
                .put("userEmail", JSONObject.NULL)
                .put("accessToken", JSONObject.NULL)
                .put("refreshToken", JSONObject.NULL)
                .put("expiresAt", JSONObject.NULL)
        })!!
        assertNull(d.session!!.userId)
        assertNull(d.session!!.userEmail)
        assertNull(d.session!!.accessToken)
        assertNull(d.session!!.refreshToken)
        assertNull(d.session!!.expiresAt)
        assertEquals(3600L, d.session!!.expiresIn)
    }

    @Test
    fun errorAndVerifyVerdictsAccepted() {
        val err = decode(JSONObject("""{"ok":{"version":1,"verdict":"error","message":"That email and password don't match. Check them and try again."}}"""))!!
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.ERROR, err.kind)
        assertTrue(err.message.isNotEmpty())
        assertNull(err.user); assertNull(err.session)
        val ver = decode(JSONObject("""{"ok":{"version":1,"verdict":"verify","message":"Please verify your email using the CycleCare link before signing in."}}"""))!!
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.VERIFY, ver.kind)
    }

    @Test
    fun wrongVersionRejected() {
        assertNull(decode(okReply { put("version", 2) }))
        assertNull(decode(okReply { put("version", "1") }))
    }

    @Test
    fun unknownOrMissingVerdictRejected() {
        assertNull(decode(okReply { put("verdict", "maybe") }))
        assertNull(decode(okReply { remove("verdict") }))
    }

    @Test
    fun emptyOrMissingMessageRejected() {
        assertNull(decode(okReply { put("message", "") }))
        assertNull(decode(okReply { put("message", JSONObject.NULL) }))
        assertNull(decode(JSONObject("""{"ok":{"version":1,"verdict":"error","message":""}}""")))
    }

    @Test
    fun successWithoutUserOrSessionRejected() {
        assertNull(decode(okReply { remove("user") }))
        assertNull(decode(okReply { remove("session") }))
    }

    @Test
    fun driftedUserFieldsRejected() {
        assertNull(decode(okReply { getJSONObject("user").put("id", JSONObject.NULL) }))
        assertNull(decode(okReply { getJSONObject("user").put("id", 42) }))
        assertNull(decode(okReply { getJSONObject("user").put("anonymous", "false") }))
        assertNull(decode(okReply { getJSONObject("user").put("email", 42) }))
    }

    @Test
    fun blankTokenFieldsRejectedNeverCoercedToMarkers() {
        // A blank string from the native side is a defect, not a keep/set
        // marker: the marker mapping happens natively, so a blank reaching
        // the bridge is rejected, never silently turned into null.
        assertNull(decode(okReply { getJSONObject("session").put("accessToken", "") }))
        assertNull(decode(okReply { getJSONObject("session").put("userId", "") }))
    }

    @Test
    fun wrongSessionModeRejected() {
        // This op only decides sign-in; a guest-mode session on the wire
        // would misroute the caller's writes.
        assertNull(decode(okReply { getJSONObject("session").put("mode", "ANONYMOUS") }))
    }

    @Test
    fun driftedExpiryFieldsRejected() {
        assertNull(decode(okReply { getJSONObject("session").put("expiresAt", "1767225600") }))
        assertNull(decode(okReply { getJSONObject("session").put("expiresIn", "3600") }))
        assertNull(decode(okReply { getJSONObject("session").put("expiresIn", true) }))
    }
}
