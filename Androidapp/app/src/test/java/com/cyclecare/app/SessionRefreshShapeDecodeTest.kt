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
 * Strict-decode guards for the session refresh-SHAPE bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13, reviewer-corrected): this reply carries the
 * non-clock shape fields that the caller's original clock-governed checks
 * then act on, so any drifted, coerced, or defaulted field is rejected
 * (null -> the caller keeps its verbatim Kotlin shape handling). Written
 * for execution on a build host; not executed in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRefreshShapeDecodeTest {

    private fun shapeBody(): JSONObject = JSONObject("""{"ok":{"version":1,"verdict":"shape","accessToken":"newacc","rotatedRefresh":"rot1","expiresAt":1767225600,"expiresIn":0,"userEmail":"a@b.c","anonymous":false}}""")

    @Test
    fun terminalKindsAccepted() {
        assertEquals(CycleCareCoreBridge.SessionRefreshShape.Kind.REAUTH,
            CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":{"version":1,"verdict":"reauth"}}"""))!!.kind)
        assertEquals(CycleCareCoreBridge.SessionRefreshShape.Kind.UNAVAILABLE,
            CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":{"version":1,"verdict":"unavailable"}}"""))!!.kind)
    }

    @Test
    fun shapeFullPayloadAccepted() {
        val v = CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody())!!
        assertEquals("newacc", v.accessToken)
        assertEquals("rot1", v.rotatedRefresh)
        assertEquals(1767225600L, v.expiresAt)
        assertEquals(0L, v.expiresIn)
        assertEquals("a@b.c", v.userEmail)
        assertFalse(v.anonymous)
    }

    @Test
    fun shapeKeepOldNullsAccepted() {
        val v = CycleCareCoreBridge.decodeSessionRefreshShape(
            JSONObject("""{"ok":{"version":1,"verdict":"shape","accessToken":"newacc","rotatedRefresh":null,"expiresAt":1767225600,"expiresIn":3600,"userEmail":null,"anonymous":true}}"""))!!
        assertNull(v.rotatedRefresh)
        assertNull(v.userEmail)
        assertTrue(v.anonymous)
        assertEquals(3600L, v.expiresIn)
    }

    @Test
    fun emptyAccessTokenAcceptedAsSourceShape() {
        // The source's stage accepts opt("access_token") as? String even
        // when empty; the ORIGINAL validTokenForOwner check rejects it at
        // its own execution point. Rejecting it here would change WHICH
        // check fires, so the decoder passes it through.
        assertEquals("", CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("accessToken", ""))!!.accessToken)
        // But a non-string is drift.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("accessToken", 42)))
    }

    @Test
    fun rotatedRefreshStringOrNullOnly() {
        // Empty string has a source shape (the later isBlank check owns it).
        assertEquals("", CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("rotatedRefresh", ""))!!.rotatedRefresh)
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("rotatedRefresh", 42)))
    }

    @Test
    fun expiryFieldsAreRawIntegers() {
        // 0 and negatives have source shapes (the source's later branches
        // decide); fractional and string forms are drift.
        assertEquals(-5L, CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("expiresAt", -5))!!.expiresAt)
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("expiresAt", 1.5)))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("expiresAt", "1767225600")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("expiresIn", true)))
    }

    @Test
    fun userEmailNullOrNonemptyString() {
        // The port never emits a blank email (blank -> keep-old marker), so
        // an empty string here is drift; null is the keep-old marker.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("userEmail", "")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("userEmail", 42)))
    }

    @Test
    fun anonymousStringRejected() {
        // optBoolean would coerce "true" into an account-mode decision.
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("anonymous", "true")))
    }

    @Test
    fun unknownOrCoercedVerdictRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":{"version":1,"verdict":"SHAPE"}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":{"version":1,"verdict":true}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun envelopeDriftRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":{"version":2,"verdict":"reauth"}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"error":"x"}""")))
        assertNull(CycleCareCoreBridge.decodeSessionRefreshShape(JSONObject("""{"ok":"nope"}""")))
    }

    @Test
    fun extraKeysTolerated() {
        assertEquals("newacc", CycleCareCoreBridge.decodeSessionRefreshShape(shapeBody().put("future", 1))!!.accessToken)
    }
}
