package com.cyclecare.app

import android.util.Base64
import com.cyclecare.app.data.supabase.AccessTokenResolution
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression pins for the VERBATIM Kotlin fallback twin of the native
 * refresh-response validation (NEW IMPLEMENTATION wiring, 2026-11-13).
 * The twin is the original resolveAccessToken block extracted
 * statement-for-statement; these cases pin its actual outcomes so the
 * native path (session_token::validate_refresh_response, corpus-locked)
 * has a documented reference. Reflection reaches the private twin and the
 * private RefreshValidation result; production callers stay untouched.
 * Written for execution on a build host; not executed in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRefreshFallbackParityTest {

    private fun validate(code: Int, body: String?, owner: String, refreshToken: String): Any {
        val m = SupabaseSessionManager.javaClass.getDeclaredMethod(
            "validateRefreshResponseKotlin", Int::class.java, String::class.java, String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(SupabaseSessionManager, code, body, owner, refreshToken)
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
        val user = JSONObject().put("id", owner)
        val body = JSONObject()
            .put("access_token", access)
            .put("expires_at", System.currentTimeMillis() / 1000 + 3600)
            .put("user", user)
        body.extra()
        return body.toString()
    }

    @Test
    fun statusMapping() {
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(400, null, "o", "r")))
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(401, null, "o", "r")))
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(403, null, "o", "r")))
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(validate(500, "x", "o", "r")))
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(validate(199, "x", "o", "r")))
    }

    @Test
    fun nullMalformedAndNonObjectBodyAreTemporary() {
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(validate(200, null, "o", "r")))
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(validate(200, "{not json", "o", "r")))
        // JSONObject("[1,2]") throws (non-object text) -> same catch-all.
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(validate(200, "[1,2]", "o", "r")))
    }

    @Test
    fun oversizedBodyIsTemporary() {
        val big = " ".repeat(64 * 1024 + 1)
        assertEquals(AccessTokenResolution.TemporarilyUnavailable, terminalResolution(validate(200, big, "o", "r")))
    }

    @Test
    fun shapeFailuresAreReauth() {
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, "{}", "o", "r")))
        // refresh_token present but JSON null: opt as? String fails.
        val body = okBody(jwt("owner", System.currentTimeMillis() / 1000 + 7200), "owner") { put("refresh_token", JSONObject.NULL) }
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, body, "owner", "oldrefresh")))
        // user.id mismatch.
        val good = okBody(jwt("owner", System.currentTimeMillis() / 1000 + 7200), "owner")
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, good, "someoneelse", "oldrefresh")))
    }

    @Test
    fun oldRefreshStillValidatedInKeepOldCase() {
        val good = okBody(jwt("owner", System.currentTimeMillis() / 1000 + 7200), "owner")
        // ISO-control char in the KEPT old refresh -> Reauth (the source
        // checks the effective value regardless of origin).
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, good, "owner", "bad\u0007refresh")))
        val tooLong = "x".repeat(16 * 1024 + 1)
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, good, "owner", tooLong)))
    }

    @Test
    fun keepOldProceed() {
        val access = jwt("owner", System.currentTimeMillis() / 1000 + 7200)
        val r = validate(200, okBody(access, "owner"), "owner", "oldrefresh")
        assertEquals("Proceed", kind(r))
        assertEquals(access, field(r, "renewedToken"))
        assertEquals("oldrefresh", field(r, "rotatedRefresh"))
        // newExpiry = min(reported now+3600, jwt exp now+7200).
        val reported = System.currentTimeMillis() / 1000 + 3600
        val pinned = field(r, "newExpiry") as Long
        assertTrue(pinned in (reported - 2)..(reported + 2))
        assertEquals(null, field(r, "userEmail"))
        assertEquals(false, field(r, "anonymous"))
    }

    @Test
    fun rotationProceedWithEmailAndAnonymous() {
        val access = jwt("owner", System.currentTimeMillis() / 1000 + 7200)
        val body = okBody(access, "owner") {
            put("refresh_token", "rotated123")
            put("expires_in", 3600)
            remove("expires_at")
            put("user", JSONObject().put("id", "owner").put("email", "a@b.c").put("is_anonymous", true))
        }
        val r = validate(200, body, "owner", "oldrefresh")
        assertEquals("Proceed", kind(r))
        assertEquals("rotated123", field(r, "rotatedRefresh"))
        assertEquals("a@b.c", field(r, "userEmail"))
        assertEquals(true, field(r, "anonymous"))
        val expected = System.currentTimeMillis() / 1000 + 3600
        assertTrue((field(r, "newExpiry") as Long) in (expected - 2)..(expected + 2))
    }

    @Test
    fun blankEmailKeepsOldAndStringAnonymousCoerces() {
        // org.json optBoolean("is_anonymous") coerces the STRING "true" -
        // pinned so the native port's opt_boolean mirrors it exactly.
        val access = jwt("owner", System.currentTimeMillis() / 1000 + 7200)
        val body = okBody(access, "owner") {
            put("user", JSONObject().put("id", "owner").put("email", "  ").put("is_anonymous", "true"))
        }
        val r = validate(200, body, "owner", "oldrefresh")
        assertEquals("Proceed", kind(r))
        assertEquals(null, field(r, "userEmail"))
        assertEquals(true, field(r, "anonymous"))
    }

    @Test
    fun expiryBoundsRejected() {
        val access = jwt("owner", System.currentTimeMillis() / 1000 + 7200)
        val past = okBody(access, "owner") { put("expires_at", System.currentTimeMillis() / 1000 - 10) }
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, past, "owner", "r")))
        val far = okBody(access, "owner") { put("expires_at", System.currentTimeMillis() / 1000 + 604801) }
        assertEquals(AccessTokenResolution.ReauthenticationRequired, terminalResolution(validate(200, far, "owner", "r")))
    }
}
