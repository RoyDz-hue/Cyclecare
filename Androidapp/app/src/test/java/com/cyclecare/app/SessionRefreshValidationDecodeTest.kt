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
 * Strict-decode guards for the session refresh-validation bridge wiring
 * (NEW IMPLEMENTATION, 2026-11-13): this reply gates whether a renewed
 * access token and possibly a rotated refresh token are committed into
 * AuthPreferences, so any drifted, coerced, or defaulted field is rejected
 * (null -> the caller keeps its verbatim Kotlin validation). Written for
 * execution on a build host; not executed in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRefreshValidationDecodeTest {

    private fun validBody(): JSONObject = JSONObject("""{"ok":{"version":1,"verdict":"valid","accessToken":"newacc","rotatedRefresh":"rot1","expiry":1767225600,"userEmail":"a@b.c","anonymous":false}}""")

    @Test
    fun terminalKindsAccepted() {
        assertEquals(CycleCareCoreBridge.SessionRefreshValidation.Kind.REAUTH,
            CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":{"version":1,"verdict":"reauth"}}"""))!!.kind)
        assertEquals(CycleCareCoreBridge.SessionRefreshValidation.Kind.UNAVAILABLE,
            CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":{"version":1,"verdict":"unavailable"}}"""))!!.kind)
    }

    @Test
    fun validFullPayloadAccepted() {
        val v = CycleCareCoreBridge.decodeSessionRefreshValidation(validBody())!!
        assertEquals("newacc", v.accessToken)
        assertEquals("rot1", v.rotatedRefresh)
        assertEquals(1767225600L, v.expiry)
        assertEquals("a@b.c", v.userEmail)
        assertFalse(v.anonymous)
    }

    @Test
    fun validKeepOldNullsAccepted() {
        // rotatedRefresh/userEmail JSON null = keep the current values.
        val v = CycleCareCoreBridge.decodeSessionRefreshValidation(
            JSONObject("""{"ok":{"version":1,"verdict":"valid","accessToken":"newacc","rotatedRefresh":null,"expiry":1767225600,"userEmail":null,"anonymous":true}}"""))!!
        assertNull(v.rotatedRefresh)
        assertNull(v.userEmail)
        assertTrue(v.anonymous)
    }

    @Test
    fun unknownOrCoercedVerdictRejected() {
        // Case-sensitive kinds; no coercion of non-strings.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":{"version":1,"verdict":"VALID"}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":{"version":1,"verdict":true}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun accessTokenEmptyOrWrongTypeRejected() {
        // A drifted empty/coerced credential must never reach the commit.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("accessToken", "")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("accessToken", 42)))
    }

    @Test
    fun rotatedRefreshEmptyOrWrongTypeRejected() {
        // null is the ONLY non-string allowed (keep-old marker).
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("rotatedRefresh", "")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("rotatedRefresh", 42)))
    }

    @Test
    fun expiryShapeRejected() {
        // Genuine positive integers only: 0, negative, fractional, string.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("expiry", 0)))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("expiry", -5)))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("expiry", 1.5)))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("expiry", "1767225600")))
    }

    @Test
    fun userEmailEmptyOrWrongTypeRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("userEmail", "")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("userEmail", 42)))
    }

    @Test
    fun anonymousStringRejected() {
        // optBoolean would coerce "true" into an account-mode decision.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("anonymous", "true")))
    }

    @Test
    fun envelopeDriftRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":{"version":2,"verdict":"reauth"}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"error":"x"}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshValidation(JSONObject("""{"ok":"nope"}""")))
    }

    @Test
    fun extraKeysTolerated() {
        // Forward compatibility: unknown keys do not invalidate known ones.
        assertEquals("newacc", CycleCareCoreBridge.decodeSessionRefreshValidation(validBody().put("future", 1))!!.accessToken)
    }
}
