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
 * Strict-decode guards for the something_sheet_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): decoded values drive the sheet's chips,
 * parse gates and preview card, so a drifted reply (wrong version, wrong
 * type, wrong table shape) is rejected (null -> the caller keeps its
 * verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SomethingSheetDecodeTest {

    @Test
    fun validRepliesDecode() {
        assertTrue(
            CycleCareCoreBridge.decodeSheetBool(JSONObject("""{"ok":{"version":1,"parse":true}}"""), "parse")!!
        )
        assertEquals(
            "Cramps, Bloating",
            CycleCareCoreBridge.decodeSheetString(JSONObject("""{"ok":{"version":1,"text":"Cramps, Bloating"}}"""), "text")
        )
        assertEquals(
            listOf("a", "b"),
            CycleCareCoreBridge.decodeSheetSubOptions(JSONObject("""{"ok":{"version":1,"subs":["a","b"]}}"""))
        )
    }

    @Test
    fun emptySubOptionsIsValid() {
        // Unknown domain -> [] in both the port and the Kotlin when-chain.
        assertEquals(
            emptyList<String>(),
            CycleCareCoreBridge.decodeSheetSubOptions(JSONObject("""{"ok":{"version":1,"subs":[]}}"""))
        )
    }

    @Test
    fun tableShapeIsStrict() {
        // Quick chips: the port's actual shape is exactly 10 entries.
        assertNull(CycleCareCoreBridge.decodeSheetQuickChips(JSONObject("""{"ok":{"version":1,"chips":["a","b"]}}""")))
        // Domain chips: exactly 5 entries, non-empty domain and subs.
        assertNull(CycleCareCoreBridge.decodeSheetDomainChips(JSONObject("""{"ok":{"version":1,"domains":[]}}""")))
        assertNull(CycleCareCoreBridge.decodeSheetDomainChips(JSONObject(
            """{"ok":{"version":1,"domains":[{"domain":"Physical","subs":[]},{"domain":"Mood","subs":["a"]},{"domain":"Energy & Sleep","subs":["a"]},{"domain":"Pelvic","subs":["a"]},{"domain":"Stomach","subs":["a"]}]}}"""
        )))
    }

    @Test
    fun declinesWrongTypesAndVersionDrift() {
        assertNull(CycleCareCoreBridge.decodeSheetBool(JSONObject("""{"ok":{"version":1,"parse":"true"}}"""), "parse"))
        assertNull(CycleCareCoreBridge.decodeSheetString(JSONObject("""{"ok":{"version":1,"text":5}}"""), "text"))
        assertNull(CycleCareCoreBridge.decodeSheetBool(JSONObject("""{"ok":{"version":2,"parse":true}}"""), "parse"))
        assertNull(CycleCareCoreBridge.decodeSheetBool(JSONObject("""{"ok":{"version":"1","parse":true}}"""), "parse"))
        assertNull(CycleCareCoreBridge.decodeSheetBool(JSONObject("""{"ok":{}}"""), "parse"))
        assertNull(CycleCareCoreBridge.decodeSheetBool(JSONObject("""{"error":"native unavailable"}"""), "parse"))
        // subs field missing entirely is a decline, not an empty list.
        assertNull(CycleCareCoreBridge.decodeSheetSubOptions(JSONObject("""{"ok":{"version":1}}""")))
        // Empty string elements are not in the port's tables.
        assertNull(CycleCareCoreBridge.decodeSheetSubOptions(JSONObject("""{"ok":{"version":1,"subs":[""]}}""")))
    }
}
