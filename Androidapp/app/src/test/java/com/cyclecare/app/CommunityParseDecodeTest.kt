package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the community_parse_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the replies drive what the live circles
 * render and whether a posted reflection is echoed, so a drifted,
 * wrong-typed, or shaped-wrong reply is rejected (null -> the caller keeps
 * its verbatim Kotlin parse) instead of becoming UI content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityParseDecodeTest {

    @Test
    fun topicsAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"topics":[{"id":"t1","title":"Rest","description":"d"}]}}""")
        val topics = CycleCareCoreBridge.decodeCommunityTopics(reply)
        assertEquals(1, topics!!.size)
        assertEquals("t1", topics[0].id)
        assertEquals("Rest", topics[0].title)
    }

    @Test
    fun topicsEmptyAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"topics":[]}}""")
        assertEquals(0, CycleCareCoreBridge.decodeCommunityTopics(reply)!!.size)
    }

    @Test
    fun topicsNullMeansNativeParseFailedAndDeclines() {
        // The native-side parse failure verdict declines to the Kotlin parse.
        val reply = JSONObject("""{"ok":{"version":1,"topics":null}}""")
        assertNull(CycleCareCoreBridge.decodeCommunityTopics(reply))
    }

    @Test
    fun topicsWrongTypesRejected() {
        assertNull(CycleCareCoreBridge.decodeCommunityTopics(JSONObject("""{"ok":{"version":1,"topics":[{"id":42,"title":"x","description":"y"}]}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityTopics(JSONObject("""{"ok":{"version":1,"topics":{}}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityTopics(JSONObject("""{"ok":{"version":2,"topics":[]}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityTopics(JSONObject("""{"error":"x"}""")))
    }

    @Test
    fun messagesAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"messages":[{"content":"hi","senderAlias":"Member","personaId":null,"remoteId":"m1","timestampMs":1767225600000,"isSelf":true,"isFlagged":false}]}}""")
        val rows = CycleCareCoreBridge.decodeCommunityMessageRows(reply)
        assertEquals(1, rows!!.size)
        assertEquals("hi", rows[0].content)
        assertNull(rows[0].personaId)
        assertEquals("m1", rows[0].remoteId)
        assertEquals(1767225600000L, rows[0].timestampMs)
        assertTrue(rows[0].isSelf)
    }

    @Test
    fun messagesZeroTimestampAccepted() {
        // 0 mirrors the source's unparseable-date catch-all and must survive.
        val reply = JSONObject("""{"ok":{"version":1,"messages":[{"content":"hi","senderAlias":"Member","personaId":null,"remoteId":null,"timestampMs":0,"isSelf":false,"isFlagged":false}]}}""")
        assertEquals(0L, CycleCareCoreBridge.decodeCommunityMessageRows(reply)!![0].timestampMs)
    }

    @Test
    fun messagesWrongTypesRejected() {
        assertNull(CycleCareCoreBridge.decodeCommunityMessageRows(JSONObject("""{"ok":{"version":1,"messages":[{"content":"hi","senderAlias":"Member","personaId":null,"remoteId":null,"timestampMs":"0","isSelf":false,"isFlagged":false}]}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityMessageRows(JSONObject("""{"ok":{"version":1,"messages":[{"content":"hi","senderAlias":"Member","remoteId":null,"timestampMs":0,"isSelf":false,"isFlagged":false}]}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityMessageRows(JSONObject("""{"ok":{"version":1,"messages":[{"content":"hi","senderAlias":"Member","personaId":null,"remoteId":null,"timestampMs":0,"isSelf":"yes","isFlagged":false}]}}""")))
    }

    @Test
    fun postRowVerdictsAccepted() {
        val confirmed = CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"confirmed","remoteId":"r1","timestampMs":1767225600000}}"""))
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.CONFIRMED, confirmed!!.verdict)
        assertEquals("r1", confirmed.remoteId)
        assertEquals(1767225600000L, confirmed.timestampMs)
        val echo = CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"soft_echo"}}"""))
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.SOFT_ECHO, echo!!.verdict)
        val failed = CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"failed"}}"""))
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.FAILED, failed!!.verdict)
    }

    @Test
    fun postRowNullRemoteIdAccepted() {
        // blank/ missing remote id on a confirmed row is a real answer (None).
        val d = CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"confirmed","remoteId":null,"timestampMs":0}}"""))
        assertEquals(CycleCareCoreBridge.CommunityPostVerdict.CONFIRMED, d!!.verdict)
        assertNull(d.remoteId)
    }

    @Test
    fun postRowGarbageRejected() {
        assertNull(CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"maybe"}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"confirmed","timestampMs":"0"}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":1,"verdict":"confirmed","timestampMs":0}}""")))
        assertNull(CycleCareCoreBridge.decodeCommunityPostRow(JSONObject("""{"ok":{"version":2,"verdict":"failed"}}""")))
    }
}
