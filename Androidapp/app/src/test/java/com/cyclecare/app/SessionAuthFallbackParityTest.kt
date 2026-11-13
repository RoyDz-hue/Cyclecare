package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.supabase.SupabaseAuthService
import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Regression pins for the VERBATIM Kotlin fallback twin of the native
 * sign-in response interpretation (NEW IMPLEMENTATION wiring, 2026-11-13):
 * the twin owns exactly the original signIn interpretation statements -
 * status/error mapping, JSONObject parse, confirmation check, readUser,
 * saveSession's RAW field extraction. The clearSession/session WRITES and
 * the expiry clock read are NOT in this seam: they execute inside
 * commitOrigin at the call site, unchanged, at their original execution
 * points, in BOTH the native and fallback paths. Reflection reaches the
 * private twin; production callers stay untouched. Written for execution
 * on a build host; not executed in this environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionAuthFallbackParityTest {

    private fun interpret(isSuccessful: Boolean, body: String, email: String): CycleCareCoreBridge.SessionAuthDecision {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(context, context.getSharedPreferences("signin-twin-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
        val service = SupabaseAuthService(prefs)
        val m = SupabaseAuthService::class.java.getDeclaredMethod(
            "interpretSignInResponseKotlin", Boolean::class.java, String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(service, isSuccessful, body, email) as CycleCareCoreBridge.SessionAuthDecision
    }

    @Test
    fun errorBodyMapsThroughCorpus() {
        val d = interpret(false, """{"error":"invalid_grant","error_description":"Invalid login credentials"}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.ERROR, d.kind)
        assertEquals("That email and password don't match. Check them and try again.", d.message)
        assertNull(d.user); assertNull(d.session)
    }

    @Test
    fun malformedAndNonObjectBodiesAreConnectErrors() {
        // JSONObject("{not json") and JSONObject("[1,2]") both throw -> the
        // original catch-all's connect message.
        for (body in listOf("{not json", "[1,2]")) {
            val d = interpret(true, body, "e@x.y")
            assertEquals(CycleCareCoreBridge.AuthDecisionKind.ERROR, d.kind)
            assertEquals("Unable to connect. Please check your network connection.", d.message)
        }
    }

    @Test
    fun unconfirmedEmailIsVerify() {
        val d = interpret(true, """{"access_token":"AT","user":{"id":"u1","email":"e@x.y"}}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.VERIFY, d.kind)
        assertEquals("Please verify your email using the CycleCare link before signing in.", d.message)
    }

    @Test
    fun successCarriesRawSessionMarkers() {
        val d = interpret(true, """{"access_token":"AT","refresh_token":"RT","expires_in":600,"user":{"id":"u1","email":"e@x.y","email_confirmed_at":"x"}}""", "e@x.y")
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.SUCCESS, d.kind)
        assertEquals("Successfully signed in to CycleCare.", d.message)
        assertEquals("u1", d.user!!.id)
        assertEquals("e@x.y", d.user!!.email)
        assertEquals("EMAIL", d.session!!.mode)
        assertEquals("u1", d.session!!.userId)
        assertEquals("e@x.y", d.session!!.userEmail)
        assertEquals("AT", d.session!!.accessToken)
        assertEquals("RT", d.session!!.refreshToken)
        assertNull(d.session!!.expiresAt) // absent -> caller's clock point
        assertEquals(600L, d.session!!.expiresIn)
    }

    @Test
    fun blankFieldsBecomeMarkersNotWrites() {
        // saveSession's `.ifBlank { null }`: blank tokens -> SET-null
        // markers, blank user id -> KEEP marker. Extraction only; the
        // actual writes happen at the call site's commit point.
        val d = interpret(true, """{"access_token":"","refresh_token":"","user":{"id":"","email":"","email_confirmed_at":"x"}}""", "fb@x.y")
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.SUCCESS, d.kind)
        assertNull(d.session!!.userId)
        assertNull(d.session!!.userEmail)
        assertNull(d.session!!.accessToken)
        assertNull(d.session!!.refreshToken)
    }

    @Test
    fun saveSessionUserFallbackIsWholeBodyNotEmptyObject() {
        // readUser falls back to an EMPTY object (confirmation check sees
        // it unconfirmed) but saveSession falls back to the WHOLE body for
        // its field extraction. A body with no "user" key and a top-level
        // email_confirmed_at: confirmed via... the empty-object check sees
        // no confirmation, so VERIFY - pinning the two fallbacks apart.
        val unconfirmed = interpret(true, """{"id":"u9","email":"top@x.y","access_token":"AT"}""", "fb@x.y")
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.VERIFY, unconfirmed.kind)
        // And with a real confirmed user object, saveSession still reads
        // the TOP level when "user" is absent - pinned via the Rust twin;
        // here pin the object path's raw extraction.
        val confirmed = interpret(true, """{"access_token":"AT","user":{"id":"u9","email":"top@x.y","email_confirmed_at":"x"}}""", "fb@x.y")
        assertEquals(CycleCareCoreBridge.AuthDecisionKind.SUCCESS, confirmed.kind)
        assertEquals("u9", confirmed.session!!.userId)
        assertEquals("top@x.y", confirmed.session!!.userEmail)
    }

    @Test
    fun expiryRawExtractionMatchesSource() {
        // expires_at present as number -> kept verbatim; as parseable
        // string -> coerced (asLongOrNull); junk string -> null marker;
        // expires_in absent -> 3600 (optLong fallback).
        val num = interpret(true, """{"expires_at":1767225600,"user":{"id":"u","email_confirmed_at":"x"}}""", "e@x.y")
        assertEquals(1767225600L, num.session!!.expiresAt)
        assertEquals(3600L, num.session!!.expiresIn)
        val str = interpret(true, """{"expires_at":"1767225600","expires_in":"120","user":{"id":"u","email_confirmed_at":"x"}}""", "e@x.y")
        assertEquals(1767225600L, str.session!!.expiresAt)
        assertEquals(120L, str.session!!.expiresIn) // optLong coerces numeric strings
        val junk = interpret(true, """{"expires_at":"junk","expires_in":"junk","user":{"id":"u","email_confirmed_at":"x"}}""", "e@x.y")
        assertNull(junk.session!!.expiresAt)
        assertEquals(3600L, junk.session!!.expiresIn) // optLong junk -> fallback
    }

    @Test
    fun expiryStringCoercionIsAospParseDoubleNotParseLong() {
        // AOSP optLong -> JSON.toLong: (long) Double.parseDouble(value) -
        // the full Java grammar: fractions truncate, whitespace trims,
        // exponents and hex floats parse, NaN -> 0, Infinity/overflow
        // saturate, d/f suffixes parse. Only NumberFormatException reaches
        // the default branch (the null marker).
        fun expiresAt(raw: String) = interpret(true, """{"expires_at":"$raw","user":{"id":"u","email_confirmed_at":"x"}}""", "e@x.y").session!!.expiresAt
        assertEquals(3600L, expiresAt("3600.9"))
        assertEquals(3600L, expiresAt(" 3600 "))
        assertEquals(1000L, expiresAt("1e3"))
        assertEquals(0L, expiresAt("NaN"))
        assertEquals(Long.MAX_VALUE, expiresAt("Infinity"))
        assertEquals(Long.MIN_VALUE, expiresAt("-Infinity"))
        assertEquals(8L, expiresAt("0x1p3"))
        assertEquals(7L, expiresAt("7d"))
        assertNull(expiresAt("0xff")) // hex float REQUIRES a p-exponent
        val expIn = interpret(true, """{"expires_in":"1e1","user":{"id":"u","email_confirmed_at":"x"}}""", "e@x.y").session!!.expiresIn
        assertEquals(10L, expIn)
    }
}
