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
 * Strict-decode guards for the transaction_history_status_v1 bridge wiring
 * (NEW IMPLEMENTATION, 2026-11-13): the decoded status feeds the payment
 * polling vocabulary, so a drifted, wrong-typed, or shaped-wrong reply is
 * rejected (null -> the caller keeps its verbatim Kotlin twin) instead of
 * becoming a payment status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransactionHistoryStatusDecodeTest {

    @Test
    fun knownStatusesDecode() {
        for (status in listOf(
            "success", "pending", "failed", "cancelled", "canceled",
            "expired", "refunded", "reversed", "ambiguous",
        )) {
            val reply = JSONObject("""{"ok":{"version":1,"status":"$status"}}""")
            assertEquals(status, status, CycleCareCoreBridge.decodeTransactionHistoryStatus(reply))
        }
    }

    @Test
    fun unknownOrMissingStatusDeclines() {
        // Not in the historyStatus output vocabulary.
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":1,"status":"COMPLETED"}}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":1,"status":"not_found"}}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":1,"status":""}}""")))
        // Wrong type / missing.
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":1,"status":1}}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":1,"status":null}}""")))
    }

    @Test
    fun wrongShapeDeclines() {
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"status":"success"}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":"success"}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":2,"status":"success"}}""")))
        assertNull(CycleCareCoreBridge.decodeTransactionHistoryStatus(JSONObject("""{"ok":{"version":"1","status":"success"}}""")))
    }
}
