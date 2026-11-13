package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guard for the Supabase client-config bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the reply selects the endpoint every
 * Supabase service talks to, so a drifted, wrong-typed, or mixed pair is
 * rejected (null -> the caller keeps the Kotlin resolution) instead of
 * redirecting the client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseConfigDecodeTest {

    @Test
    fun resolvedPairAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"url":"https://apjqcocxtylsofgxhetb.supabase.co","apiKey":"sb_publishable_x"}}""")
        val d = CycleCareCoreBridge.decodeSupabaseClientSettings(reply)
        assertEquals("https://apjqcocxtylsofgxhetb.supabase.co", d!!.url)
        assertEquals("sb_publishable_x", d.apiKey)
    }

    @Test
    fun disabledPairAccepted() {
        // disabled() is the ("","") sentinel and must survive decoding.
        val reply = JSONObject("""{"ok":{"version":1,"url":"","apiKey":""}}""")
        val d = CycleCareCoreBridge.decodeSupabaseClientSettings(reply)
        assertEquals("", d!!.url)
        assertEquals("", d.apiKey)
    }

    @Test
    fun mixedPairRejected() {
        // No source shape produces one empty and one set field.
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":1,"url":"","apiKey":"sb_publishable_x"}}""")))
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":1,"url":"https://x.supabase.co","apiKey":""}}""")))
    }

    @Test
    fun wrongTypedFieldsRejected() {
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":1,"url":123,"apiKey":"k"}}""")))
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":1,"url":"u","apiKey":true}}""")))
    }

    @Test
    fun missingFieldsRejected() {
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":1,"url":"u"}}""")))
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun wrongVersionRejected() {
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"version":2,"url":"","apiKey":""}}""")))
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"ok":{"url":"","apiKey":""}}""")))
    }

    @Test
    fun missingOkRejected() {
        assertNull(CycleCareCoreBridge.decodeSupabaseClientSettings(JSONObject("""{"error":"x"}""")))
    }
}
