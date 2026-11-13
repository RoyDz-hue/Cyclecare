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
 * Strict-decode guards for the data_export_json_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded string is the user's personal
 * data export, so a drifted, wrong-typed, or shaped-wrong reply is
 * rejected (null -> the caller keeps its verbatim Kotlin twin).
 * Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataExportJsonDecodeTest {

    @Test
    fun validReplyDecodes() {
        val reply = JSONObject("""{"ok":{"version":1,"json":"{}"}}""")
        assertEquals("{}", CycleCareCoreBridge.decodeDataExportJson(reply))
    }

    @Test
    fun driftDeclines() {
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"ok":{"version":1,"json":{}}}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"ok":{"version":1,"json":null}}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"ok":{"version":2,"json":"{}"}}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"ok":{"version":"1","json":"{}"}}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"json":"{}"}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportJson(JSONObject("""{"ok":"{}"}""")))
    }
}
