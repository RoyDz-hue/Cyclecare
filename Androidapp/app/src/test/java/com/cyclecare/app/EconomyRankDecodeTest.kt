package com.cyclecare.app

import com.cyclecare.app.data.economy.IdentityRank
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the economy_rank_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded rank lands on the user's
 * profile, so a drifted, wrong-typed, or shaped-wrong reply is rejected
 * (null -> the caller keeps its verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EconomyRankDecodeTest {

    @Test
    fun knownRanksDecode() {
        for (rank in IdentityRank.entries) {
            val reply = JSONObject("""{"ok":{"version":1,"rank":"${rank.name}"}}""")
            assertEquals(rank.name, rank, CycleCareCoreBridge.decodeEconomyRank(reply))
        }
    }

    @Test
    fun driftDeclines() {
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":1,"rank":"lumen"}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":1,"rank":"MYTHIC"}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":1,"rank":1}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":1,"rank":null}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":2,"rank":"LUMEN"}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":{"version":"1","rank":"LUMEN"}}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"rank":"LUMEN"}""")))
        assertNull(CycleCareCoreBridge.decodeEconomyRank(JSONObject("""{"ok":"LUMEN"}""")))
    }
}
