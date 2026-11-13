package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.economy.CommunityGuidesService
import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression pins for the Kotlin fallback twins of the community_parse_v1
 * wiring (NEW IMPLEMENTATION, 2026-11-13). These run the REAL AOSP org.json
 * classes at sdk 34 and document the actual behavior the native parity port
 * must match; reflection reaches the private twins while the production
 * call sites stay untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityParseFallbackParityTest {

    private lateinit var service: CommunityGuidesService

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        service = CommunityGuidesService(
            AuthPreferences(
                context,
                context.getSharedPreferences("community-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE),
                false
            )
        )
    }

    private fun topics(raw: String): List<*>? {
        val m = CommunityGuidesService::class.java.getDeclaredMethod("kotlinParseTopicRows", String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(service, raw) as List<*>?
    }

    private fun messages(raw: String, selfId: String?): List<CycleCareCoreBridge.CommunityMessageRow>? {
        val m = CommunityGuidesService::class.java.getDeclaredMethod("kotlinParseMessageRows", String::class.java, String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(service, raw, selfId) as List<CycleCareCoreBridge.CommunityMessageRow>?
    }

    private fun postRow(raw: String): CycleCareCoreBridge.CommunityPostRow {
        val m = CommunityGuidesService::class.java.getDeclaredMethod("kotlinPostRow", String::class.java)
        m.isAccessible = true
        return m.invoke(service, raw) as CycleCareCoreBridge.CommunityPostRow
    }

    @Test
    fun topicsRowSemantics() {
        val raw = """[
            {"id":"t1","title":"Rest","description":"  gentle  "},
            {"id":"","title":"No id"},
            {"id":"t2","title":"  "},
            "not-an-object",
            {"id":"t3","title":"Energy"},
            {"id":42,"title":"Numeric id"}
        ]"""
        val parsed = topics(raw)!!
        assertEquals(3, parsed.size)
        // AOSP optString stringifies a numeric id.
        assertEquals("42", (parsed[2] as CommunityGuidesService.ServerTopic).id)
        assertEquals("gentle", (parsed[0] as CommunityGuidesService.ServerTopic).description)
    }

    @Test
    fun topicsNonArrayAndMalformedAreNull() {
        // JSONArray("{}") throws (not an array) -> original catch.
        assertNull(topics("{}"))
        assertNull(topics("not json"))
        // An empty array parses: the caller maps empty to EMPTY, not failure.
        assertEquals(0, topics("[]")!!.size)
    }

    @Test
    fun topicsExplicitNullIdIsTheLiteralNull() {
        // AOSP optString of an explicit JSON null is the literal "null",
        // which is NOT empty, so the row is KEPT with id "null".
        val parsed = topics("""[{"id":null,"title":"T"}]""")!!
        assertEquals(1, parsed.size)
        assertEquals("null", (parsed[0] as CommunityGuidesService.ServerTopic).id)
    }

    @Test
    fun messagesRowSemantics() {
        val raw = """[
            {"id":"m1","user_id":"me","sender_alias":"","content":"  hello  ","persona_id":"","is_flagged":false},
            {"id":"m2","user_id":"other","sender_alias":" ","content":"guide words","persona_id":" LUNA ","is_flagged":"true"},
            {"id":"m3","user_id":"ME","sender_alias":"Case","content":"case matters","is_flagged":true},
            {"id":"m4","user_id":"x","sender_alias":"z","content":"   "}
        ]"""
        val rows = messages(raw, "me")!!
        assertEquals(3, rows.size) // blank-content row skipped
        assertTrue(rows[0].isSelf)
        assertEquals("Member", rows[0].senderAlias)
        // ifBlank (not isEmpty): a whitespace-only alias falls back too.
        assertEquals("Community Guide", rows[1].senderAlias)
        assertEquals("luna", rows[1].personaId)
        assertTrue(rows[1].isFlagged) // string "true" counts
        assertFalse(rows[2].isSelf) // "ME" != "me": case-sensitive Kotlin ==
    }

    @Test
    fun messagesContentTake500() {
        val long = "🌸".repeat(600) // 1200 UTF-16 units
        val rows = messages("""[{"id":"m","user_id":"u","content":"$long"}]""", null)!!
        assertEquals("🌸".repeat(250), rows[0].content) // take(500) units
    }

    @Test
    fun postRowBoundaries() {
        // JSONArray throw (malformed or valid non-array) -> soft echo.
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.SOFT_ECHO, postRow("not json").verdict)
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.SOFT_ECHO, postRow("{}").verdict)
        // optJSONObject(0) null (empty array / non-object first row) -> failed.
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.FAILED, postRow("[]").verdict)
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.FAILED, postRow("[42]").verdict)
    }

    @Test
    fun postRowConfirmedSemantics() {
        val blank = postRow("""[{"id":"  ","created_at":"junk"}]""")
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.CONFIRMED, blank.verdict)
        assertNull(blank.remoteId)
        assertEquals(0L, blank.timestampMs)
        // AOSP optString stringifies a numeric id.
        assertEquals("42", postRow("""[{"id":42}]""").remoteId)
        // AOSP optString of an explicit JSON null is the literal "null"
        // (labeled parity quirk).
        assertEquals("null", postRow("""[{"id":null}]""").remoteId)
        val dated = postRow("""[{"id":"m1","created_at":"2026-01-01T00:00:00Z"}]""")
        assertEquals("m1", dated.remoteId)
        assertEquals(1767225600000L, dated.timestampMs)
    }
}
