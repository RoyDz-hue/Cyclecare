package com.cyclecare.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** On-device proof that the packaged native core loads under ART and answers
 * read-only contracts through JNI. No accounts, no sign-in, no network, no
 * stored user data. The baseline fixture's expected values were computed by
 * executing the REAL Kotlin PatternEngine.computeBaseline (host JVM, exact
 * production source) on the same fixture: average 29.0, median 29, min 28,
 * max 30, variability 1, regular, 5 days, 2 intervals - matching both the
 * independent hand calculation from source and the Rust core's host output. */
@RunWith(AndroidJUnit4::class)
class RustBridgeInstrumentedTest {

    @Test
    fun nativeLibraryLoadsAndAnswersOnDevice() {
        val reply = CycleCareCoreBridge.call("is_complete_pin", JSONObject().put("pin", "1234"))
        assertNotNull("libcyclecare_core must load under ART and answer JNI", reply)
        assertTrue(reply!!.optBoolean("ok"))
        val short = CycleCareCoreBridge.call("is_complete_pin", JSONObject().put("pin", "12"))
        assertNotNull(short)
        assertFalse(short!!.optBoolean("ok"))
    }

    @Test
    fun sanitizeCoinAmountRoundTripsThroughJni() {
        val accepted = CycleCareCoreBridge.call(
            "sanitize_coin_amount", JSONObject().put("raw", "12.5"))
        assertNotNull(accepted)
        val okAccepted = accepted!!.optJSONObject("ok")!!
        assertEquals("12.5", okAccepted.getString("text"))
        assertTrue(okAccepted.isNull("rejection"))

        val rejected = CycleCareCoreBridge.call(
            "sanitize_coin_amount", JSONObject().put("raw", ".5"))
        val okRejected = rejected!!.optJSONObject("ok")!!
        assertEquals(".5", okRejected.getString("text"))
        assertEquals("Start the amount with a digit, for example 0.5.",
            okRejected.getString("rejection"))
    }

    @Test
    fun computeBaselineMatchesRustGroundTruthForSyntheticLogs() {
        val settings = JSONObject()
            .put("averageCycleLength", 28)
            .put("periodDuration", 5)
            .put("lutealPhaseDays", 14)
            .put("lastPeriodStartDate", "2026-10-05")
        val logs = JSONArray()
            .put(JSONObject().put("dateString", "2026-10-05").put("flowLevel", "MODERATE").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-10-06").put("flowLevel", "LIGHT").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-09-05").put("flowLevel", "MODERATE").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-08-08").put("flowLevel", "HEAVY").put("symptomsCsv", ""))
            .put(JSONObject().put("dateString", "2026-10-20").put("flowLevel", "").put("symptomsCsv", "cramps"))
        val reply = CycleCareCoreBridge.call("compute_baseline",
            JSONObject().put("settings", settings).put("logs", logs))
        assertNotNull(reply)
        val ok = reply!!.optJSONObject("ok")!!
        assertEquals(29.0, ok.getDouble("averageCycleLength"), 0.0)
        assertEquals(29, ok.getInt("medianCycleLength"))
        assertEquals(28, ok.getInt("minCycleLength"))
        assertEquals(30, ok.getInt("maxCycleLength"))
        assertEquals(1, ok.getInt("variabilityDays"))
        assertTrue(ok.getBoolean("isRegular"))
        assertEquals(5, ok.getInt("totalLoggedDays"))
        assertEquals(2, ok.getInt("observedCycleIntervals"))
    }
}
