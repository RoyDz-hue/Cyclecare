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
 * Strict-decode guards for the economy_catalog_find_item_v1 bridge wiring
 * (NEW IMPLEMENTATION, 2026-11-13): the decoded id selects the displayed
 * economy item, so a drifted, wrong-typed, or shaped-wrong reply is
 * rejected (null -> the caller keeps its verbatim Kotlin twin, whose own
 * null is the source's no-match result). Written, NOT executed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EconomyCatalogFindItemDecodeTest {

    @Test
    fun matchDecodes() {
        val reply = JSONObject("""{"ok":{"version":1,"match":"artifact_bloom"}}""")
        assertEquals("artifact_bloom", CycleCareCoreBridge.decodeEconomyCatalogFindItem(reply))
    }

    @Test
    fun noMatchVerdictDeclines() {
        // The no-match verdict declines: the twin's identical lookup yields
        // the same no-match (the inc22 decline-then-twin semantics).
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"ok":{"version":1,"match":null}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun driftDeclines() {
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"ok":{"version":1,"match":42}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"ok":{"version":2,"match":"artifact_bloom"}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"ok":{"version":"1","match":"artifact_bloom"}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"match":"artifact_bloom"}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyCatalogFindItem(JSONObject("""{"ok":"artifact_bloom"}""")))
    }
}
