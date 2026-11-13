package com.cyclecare.app

import android.util.Base64
import com.cyclecare.app.data.supabase.AccessTokenResolution
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression pins for the VERBATIM Kotlin fallback twin of the native
 * refresh-SHAPE segment (NEW IMPLEMENTATION wiring, 2026-11-13,
 * reviewer-corrected): the twin owns exactly the original non-clock
 * statements of resolveAccessToken's refresh handling - status mapping,
 * body bounds, parse, access/refresh/user shape, owner match, raw field
 * extraction. The clock-governed checks (validTokenForOwner, the
 * effective-refresh blank/length/ISO-control chain, the reportedExpiry
 * bounds) are NOT in this seam: they execute inline at the call site,
 * unchanged, at their original execution points, in BOTH the native and
 * fallback paths - so no clock behavior can diverge. Reflection reaches
 * the private twin and the private RefreshShape result; production callers
 * stay untouched. Written for execution on a build host; not executed in
 * this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRefreshShapeFallbackParityTest {

    private fun shape(code: Int, body: String?, owner: String): Any {
        val m = SupabaseSessionManager.javaClass.getDeclaredMethod(
            "refreshShapeKotlin", Int::class.java, String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(SupabaseSessionManager, code, body, owner)
    }

    private fun kind(result: Any): String = result.javaClass.simpleName

    private fun field(result: Any, name: String): Any? {
        val f = result.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(result)
    }

    private fun terminalResolution(result: Any): AccessTokenResolution =
        field(result, "resolution") as AccessTokenResolution

    private fun jwt(sub: String?, exp: Long?): String {
        val payload = JSONObject()
        if (sub != null) payload.put("sub", sub)
        if (exp != null) payload.put("exp", exp)
        val enc = { s: String -> Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim() }
        return enc("""{"alg":"none"}""") + "." + enc(payload.toString()) + "." + enc("sig")
    }

    private fun okBody(access: String, owner: String, extra: JSONObject.() -> Unit = {}): String {
        val body = JSONObject()
            .put("access_token", access)
            .put("expires_at", 1767225600L)
            .put("user", JSONObject().put("id", owner))
        body.extra()
        return body.toString()
    }

    @Test
    fun statusMapping() {
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(400, null, "o")))
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(401, null, "o")))
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(403, null, "o")))
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(shape(500, "x", "o")))
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(shape(199, "x", "o")))
    }

    @Test
    fun nullMalformedAndNonObjectBodyAreTemporary() {
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(shape(200, null, "o")))
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(shape(200, "{not json", "o")))
        // JSONObject("[1,2]") throws (non-object text) -> same catch-all.
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(shape(200, "[1,2]", "o")))
    }

    @Test
    fun oversizedBodyIsTemporary() {
        val big = " ".repeat(64 * 1024 + 1)
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(shape(200, big, "o")))
    }

    @Test
    fun shapeFailuresAreReauth() {
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(200, "{}", "o")))
        // refresh_token present but JSON null: opt as? String fails.
        val nullRt = okBody(jwt("owner", 1767225600L), "owner") { put("refresh_token", JSONObject.NULL) }
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(200, nullRt, "owner")))
        // user.id mismatch, and missing user entirely.
        val good = okBody(jwt("owner", 1767225600L), "owner")
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(200, good, "someoneelse")))
        val noUser = JSONObject().put("access_token", "x").put("expires_at", 1767225600L).toString()
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(shape(200, noUser, "owner")))
    }

    @Test
    fun keepOldMarkerAndRawExtraction() {
        val access = jwt("owner", 1767225600L)
        val r = shape(200, okBody(access, "owner"), "owner")
        assertEquals("Shape", kind(r))
        assertEquals(access, field(r, "renewedToken"))
        assertEquals(null, field(r, "rotatedRefresh")) // keep-old marker
        assertEquals(1767225600L, field(r, "expiresAt"))
        assertEquals(0L, field(r, "expiresIn")) // absent -> 0, bounds NOT applied in this seam
        assertEquals(null, field(r, "userEmail"))
        assertEquals(false, field(r, "anonymous"))
    }

    @Test
    fun rotationAndUserFieldsExtracted() {
        val access = jwt("owner", 1767225600L)
        val body = okBody(access, "owner") {
            put("refresh_token", "rotated123")
            remove("expires_at")
            put("expires_in", 3600)
            put("user", JSONObject().put("id", "owner").put("email", "a@b.c").put("is_anonymous", true))
        }
        val r = shape(200, body, "owner")
        assertEquals("Shape", kind(r))
        assertEquals("rotated123", field(r, "rotatedRefresh"))
        assertEquals(0L, field(r, "expiresAt"))
        assertEquals(3600L, field(r, "expiresIn"))
        assertEquals("a@b.c", field(r, "userEmail"))
        assertEquals(true, field(r, "anonymous"))
    }

    @Test
    fun orgJsonEdgeExtraction() {
        val access = jwt("owner", 1767225600L)
        // Fractional expires_at truncates like Java Number.toLong; blank
        // email -> keep-old marker; is_anonymous STRING "true" coerced by
        // org.json optBoolean - all pinned so the native port's
        // sync_codec/opt_number_long/opt_boolean mirror them exactly.
        val body = okBody(access, "owner") {
            put("expires_at", 1767225600.9)
            put("user", JSONObject().put("id", "owner").put("email", "  ").put("is_anonymous", "true"))
        }
        val r = shape(200, body, "owner")
        assertEquals("Shape", kind(r))
        assertEquals(1767225600L, field(r, "expiresAt"))
        assertEquals(null, field(r, "userEmail"))
        assertEquals(true, field(r, "anonymous"))
        // Negative expires_at is PRESERVED (the source's later >0 branch
        // decides, not this seam).
        val neg = shape(200, okBody(access, "owner") { put("expires_at", -5) }, "owner")
        assertEquals(-5L, field(neg, "expiresAt"))
    }
}
