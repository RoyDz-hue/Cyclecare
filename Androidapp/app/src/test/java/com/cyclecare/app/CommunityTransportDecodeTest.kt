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
 * Strict-decode guards for the community transport bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the replies shape real server requests and
 * accept/reject verdicts, so a drifted, wrong-typed, or shaped-wrong reply
 * is rejected (null -> the caller keeps its Kotlin expression) instead of
 * altering a production request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityTransportDecodeTest {

    @Test
    fun pathAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"path":"/chat_topics?select=id"}}""")
        assertEquals("/chat_topics?select=id", CycleCareCoreBridge.communityPath(reply))
    }

    @Test
    fun pathWithoutLeadingSlashRejected() {
        // Every source path is restUrl-relative and starts with '/'.
        assertNull(CycleCareCoreBridge.communityPath(JSONObject("""{"ok":{"version":1,"path":"chat_topics"}}""")))
    }

    @Test
    fun pathWrongTypeRejected() {
        assertNull(CycleCareCoreBridge.communityPath(JSONObject("""{"ok":{"version":1,"path":42}}""")))
    }

    @Test
    fun pathWrongVersionRejected() {
        assertNull(CycleCareCoreBridge.communityPath(JSONObject("""{"ok":{"version":2,"path":"/x"}}""")))
        assertNull(CycleCareCoreBridge.communityPath(JSONObject("""{"error":"x"}""")))
        assertNull(CycleCareCoreBridge.communityPath(null))
    }

    @Test
    fun bodyAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"body":"{\"user_id\":\"u\"}"}}""")
        assertEquals("{\"user_id\":\"u\"}", CycleCareCoreBridge.communityBody(reply))
    }

    @Test
    fun bodyWrongTypeRejected() {
        assertNull(CycleCareCoreBridge.communityBody(JSONObject("""{"ok":{"version":1,"body":123}}""")))
    }

    @Test
    fun verdictAccepted() {
        assertTrue(CycleCareCoreBridge.communityVerdict(JSONObject("""{"ok":{"version":1,"verdict":true}}"""))!!)
        assertFalse(CycleCareCoreBridge.communityVerdict(JSONObject("""{"ok":{"version":1,"verdict":false}}"""))!!)
    }

    @Test
    fun verdictStringRejected() {
        // optBoolean would coerce this into a request/accept verdict.
        assertNull(CycleCareCoreBridge.communityVerdict(JSONObject("""{"ok":{"version":1,"verdict":"true"}}""")))
    }
}
