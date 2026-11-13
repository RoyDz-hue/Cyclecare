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
 * Strict-decode guards for the data_export_pdf_layout_v1 bridge wiring
 * (NEW IMPLEMENTATION, 2026-11-13): the decoded op stream drives real
 * canvas draws of the user's health data, so a drifted, wrong-typed, or
 * shaped-wrong reply is rejected (null -> the caller keeps its verbatim
 * Kotlin twin). Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataExportPdfLayoutDecodeTest {

    @Test
    fun validOpsDecode() {
        val reply = JSONObject(
            """{"ok":{"version":1,"ops":[
                {"op":"text","text":"CycleCare - My Data Export","y":48,"paint":"title"},
                {"op":"page"},
                {"op":"text","text":"Notes: ok","y":78.0,"paint":"body"}
            ]}}""".trimIndent()
        )
        val ops = CycleCareCoreBridge.decodeDataExportPdfLayout(reply)!!
        assertEquals(3, ops.size)
        assertFalse(ops[0].isPageBreak)
        assertEquals("CycleCare - My Data Export", ops[0].text)
        assertEquals(48f, ops[0].y)
        assertEquals(CycleCareCoreBridge.PDF_PAINT_TITLE, ops[0].paint)
        assertTrue(ops[1].isPageBreak)
        assertEquals(78f, ops[2].y)
        assertEquals(CycleCareCoreBridge.PDF_PAINT_BODY, ops[2].paint)
    }

    @Test
    fun emptyOpsDecodes() {
        val reply = JSONObject("""{"ok":{"version":1,"ops":[]}}""")
        assertEquals(0, CycleCareCoreBridge.decodeDataExportPdfLayout(reply)!!.size)
    }

    @Test
    fun driftDeclines() {
        // unknown op
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":[{"op":"draw"}]}}""")))
        // unknown paint role - never guess a paint
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":[{"op":"text","text":"x","y":48,"paint":"TITLE"}]}}""")))
        // text missing
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":[{"op":"text","y":48,"paint":"body"}]}}""")))
        // text wrong type
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":[{"op":"text","text":1,"y":48,"paint":"body"}]}}""")))
        // y wrong type (string-coerced numbers never accepted)
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":[{"op":"text","text":"x","y":"48","paint":"body"}]}}""")))
        // op entry not an object
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":["text"]}}""")))
        // ops not an array
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1,"ops":{}}}""")))
        // ops missing
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":1}}""")))
        // version drift
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":2,"ops":[]}}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":{"version":"1","ops":[]}}""")))
        // ok missing / wrong type
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ops":[]}""")))
        assertNull(CycleCareCoreBridge.decodeDataExportPdfLayout(JSONObject("""{"ok":"ops"}""")))
    }
}
