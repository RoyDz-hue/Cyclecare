package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loaded-contract evidence for the native sync_profile_on_settings branch
 * (M10): calls CycleCareCoreBridge.syncProfileOnSettings DIRECTLY (no Kotlin
 * computation in this path - the bridge returns null on native
 * unavailability or strict-decode failure, and assumeNotNull skips cleanly
 * in that case). A non-null reply proves the loaded host cdylib executed the
 * sync_profile_on_settings arm. Expected values mirror the pinned dispatch
 * contract-matrix cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsProfileSyncNativeJniTest {

    @Test
    fun nativeChangedAverageRecomputesOption() {
        val r = CycleCareCoreBridge.syncProfileOnSettings("28 days", 30, 28)
        assumeNotNull(r)
        r!!
        assertTrue(r.writeProfile)
        assertEquals("30 days", r.cycleLengthOption)
    }

    @Test
    fun nativeNoProfileMeansNoWrite() {
        val r = CycleCareCoreBridge.syncProfileOnSettings(null, 30, 28)
        assumeNotNull(r)
        r!!
        assertFalse(r.writeProfile)
        assertNull(r.cycleLengthOption)
    }

    @Test
    fun decodeRejectsDriftedEcho() {
        // writeProfile must equal the submitted hasProfile: a drifted reply
        // is rejected (null -> caller falls back), never persisted.
        val drifted = JSONObject().put("ok", JSONObject()
            .put("writeProfile", false).put("cycleLengthOption", JSONObject.NULL))
        assertNull(CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(drifted, true, "30 days"))
        // Wrong-typed fields rejected.
        val wrongType = JSONObject().put("ok", JSONObject()
            .put("writeProfile", "true").put("cycleLengthOption", "30 days"))
        assertNull(CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(wrongType, true, "30 days"))
        // writeProfile=true requires a real String option.
        val missingOpt = JSONObject().put("ok", JSONObject()
            .put("writeProfile", true).put("cycleLengthOption", JSONObject.NULL))
        assertNull(CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(missingOpt, true, "30 days"))
        // writeProfile=false tolerates absent or JSON null option only.
        val stray = JSONObject().put("ok", JSONObject()
            .put("writeProfile", false).put("cycleLengthOption", "30 days"))
        assertNull(CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(stray, false, null))
    }

    @Test
    fun decodeRejectsDriftedOption() {
        // M10 reviewer guard: the persisted option must EQUAL the original
        // Kotlin expression, not just be any String.
        // Changed average (expected "30 days"): a preserved-looking "28 days"
        // or any other value is drift.
        for (bad in listOf("28 days", "29 days", "", "30  days", "30 Days")) {
            val reply = JSONObject().put("ok", JSONObject()
                .put("writeProfile", true).put("cycleLengthOption", bad))
            assertNull("expected rejection of <$bad>",
                CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(reply, true, "30 days"))
        }
        // Unchanged average (expected existing verbatim "21 days"): a
        // recomputed-looking "28 days" is drift.
        val recomputed = JSONObject().put("ok", JSONObject()
            .put("writeProfile", true).put("cycleLengthOption", "28 days"))
        assertNull(CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(recomputed, true, "21 days"))
        // Exact expected value admitted.
        val exact = JSONObject().put("ok", JSONObject()
            .put("writeProfile", true).put("cycleLengthOption", "30 days"))
        val admitted = CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(exact, true, "30 days")
        assertTrue(admitted!!.writeProfile)
        assertEquals("30 days", admitted.cycleLengthOption)
        val exactKept = JSONObject().put("ok", JSONObject()
            .put("writeProfile", true).put("cycleLengthOption", "21 days"))
        assertEquals("21 days",
            CycleCareCoreBridge.decodeSyncProfileOnSettingsReply(exactKept, true, "21 days")!!.cycleLengthOption)
    }
}
