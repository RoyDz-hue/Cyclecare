package com.cyclecare.app

import android.util.Base64
import com.cyclecare.app.data.supabase.SupabaseSessionManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression pins for the Kotlin fallback twins of the session-token
 * bridge wiring (NEW IMPLEMENTATION, 2026-11-13): these document the
 * ACTUAL Kotlin stdlib behavior the native parity port must match, derived
 * from the pinned Kotlin 2.2.10 stdlib source (Strings.kt split + the
 * DelimitedRangesSequence range walk), not assumed from Java String.split:
 * Char-delimiter split KEEPS trailing empty pieces ("a.b." -> [a, b, ""]),
 * so a trailing-dot token reaches the 3-piece check with an empty final
 * piece and fails it there - the native port's identical shape is
 * deliberate parity, not a change. Reflection reaches the private twins;
 * the production wrappers stay untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTokenFallbackParityTest {

    private fun validForOwner(token: String, owner: String): Boolean {
        val m = SupabaseSessionManager.javaClass.getDeclaredMethod("validTokenForOwnerKotlin", String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(SupabaseSessionManager, token, owner) as Boolean
    }

    private fun expiry(token: String): Long {
        val m = SupabaseSessionManager.javaClass.getDeclaredMethod("tokenExpiryKotlin", String::class.java)
        m.isAccessible = true
        return m.invoke(SupabaseSessionManager, token) as Long
    }

    private fun jwt(sub: String?, exp: Long?): String {
        val payload = JSONObject()
        if (sub != null) payload.put("sub", sub)
        if (exp != null) payload.put("exp", exp)
        val enc = { s: String -> Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim() }
        return enc("""{"alg":"none"}""") + "." + enc(payload.toString()) + "." + enc("sig")
    }

    @Test
    fun trailingDotTokenRejectedViaEmptyPiece() {
        // Kotlin KEEPS the trailing empty piece: "a.b." splits to FOUR
        // pieces [a, b, ""], failing the 3-piece shape check and rejecting.
        // (Java regex split would DROP the empty tail, keep 3 pieces, and
        // could ACCEPT the trailing-dot token - so this is security-relevant
        // parity, and the Rust port's identical shape is deliberate.)
        assertFalse(validForOwner(jwt("owner", System.currentTimeMillis() / 1000 + 3600) + ".", "owner"))
    }

    @Test
    fun emptyLeadingPieceRejectedViaEmptyPieceCheck() {
        // ".pay.sig" splits to THREE pieces with an empty leading piece;
        // rejection comes from the empty-piece check, not the shape check.
        val t = jwt("owner", System.currentTimeMillis() / 1000 + 3600)
        assertFalse(validForOwner(t.substring(t.indexOf('.')), "owner"))
    }

    @Test
    fun loneDotRejected() {
        // "." splits to ["", ""] (2 pieces), failing the 3-piece shape.
        assertFalse(validForOwner(".", "owner"))
        assertEquals(0L, expiry("."))
    }

    @Test
    fun noDotRejected() {
        assertFalse(validForOwner("abc", "owner"))
        assertEquals(0L, expiry("abc"))
    }

    @Test
    fun trailingDotLeavesExpiryReadUnchanged() {
        // expiry reads only piece index 1; the trailing empty piece from
        // "t." does not alter that read, so expiry returns the same value.
        // Pins the ACTUAL fallback outcome; validForOwner still rejects
        // "t." above, so the two twins stay independently safe.
        val t = jwt("owner", 1767225600L)
        assertEquals(1767225600L, expiry(t))
        assertEquals(1767225600L, expiry(t + "."))
    }

    @Test
    fun validTokenAccepted() {
        val t = jwt("owner", System.currentTimeMillis() / 1000 + 3600)
        assertTrue(validForOwner(t, "owner"))
    }

    @Test
    fun wrongOwnerRejected() {
        val t = jwt("someone-else", System.currentTimeMillis() / 1000 + 3600)
        assertFalse(validForOwner(t, "owner"))
    }

    @Test
    fun expiredTokenRejected() {
        val t = jwt("owner", System.currentTimeMillis() / 1000 - 3600)
        assertFalse(validForOwner(t, "owner"))
    }

    @Test
    fun nonBase64PayloadRejected() {
        assertFalse(validForOwner("a.!notbase64!.c", "owner"))
        assertEquals(0L, expiry("a.!notbase64!.c"))
    }

    @Test
    fun missingExpYieldsZero() {
        assertEquals(0L, expiry(jwt("owner", null)))
    }
}
