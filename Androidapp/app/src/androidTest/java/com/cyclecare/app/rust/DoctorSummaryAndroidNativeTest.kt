package com.cyclecare.app.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.learning.LearningEngine
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic/local-only JNI witnesses. Packaging this class is NOT runtime proof. */
@RunWith(AndroidJUnit4::class)
class DoctorSummaryAndroidNativeTest {
    @Test fun actualNativeVersionedContractExists() {
        val reply = CycleCareCoreBridge.call("doctor_summary_v1", JSONObject("{\"logs\":[]}"))
        assertNotNull("Fresh Android doctor_summary_v1 native required, Kotlin fallback is not evidence", reply)
        val ok = reply!!.getJSONObject("ok")
        assertEquals(1, ok.getInt("version"))
        assertEquals(0, ok.getJSONArray("questions").length())
    }

    @Test fun actualSharedCallerMatchesExactSavedEvidence() {
        actualNativeVersionedContractExists()
        val logs = listOf(
            CycleDayLog("+999999999-12-31", symptomsCsv="ßeta,𐐨case,\uE000", painIntensity=7, flowLevel="HEAVY"),
            CycleDayLog("+0999999999-12-31", symptomsCsv="wrong", painIntensity=10, flowLevel="UNUSUALLY_HEAVY"),
            CycleDayLog("not-a-date", symptomsCsv="wrong", painIntensity=10, flowLevel="HEAVY"))
        val expected = listOf(
            "I recorded Sseta on 1 saved date. Is there anything specific I should monitor or bring to a follow-up?",
            "I recorded 𐐨case on 1 saved date. Is there anything specific I should monitor or bring to a follow-up?",
            "I recorded \uE000 on 1 saved date. Is there anything specific I should monitor or bring to a follow-up?",
            "I recorded pain up to 7/10 on 1 saved date. What follow-up, if any, would you recommend?",
            "I recorded heavy flow on 1 saved date. What details would be useful to track or discuss?")
        // Nonempty evidence must actually return the exact native result BEFORE the
        // shared caller comparison. Empty handshake alone cannot detect fallback.
        val wireLogs = JSONArray()
        logs.forEach { log ->
            val symptoms = JSONArray()
            log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank).forEach { key ->
                val label = key.replace('_', ' ').trim()
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                symptoms.put(JSONObject().put("key", key).put("label", label))
            }
            wireLogs.put(JSONObject().put("date", log.dateString).put("symptoms", symptoms)
                .put("pain", log.painIntensity).put("flow", log.flowLevel))
        }
        val native = CycleCareCoreBridge.call("doctor_summary_v1", JSONObject().put("logs", wireLogs))
        assertNotNull("nonempty native evidence must not fall back", native)
        assertEquals(setOf("ok"), native!!.keys().asSequence().toSet())
        val ok = native.opt("ok")
        assertTrue(ok is JSONObject)
        ok as JSONObject
        assertEquals(setOf("version", "questions"), ok.keys().asSequence().toSet())
        assertTrue(ok.opt("version") is Int)
        assertEquals(1, ok.opt("version"))
        val questions = ok.opt("questions")
        assertTrue(questions is JSONArray)
        questions as JSONArray
        assertEquals(expected.size, questions.length())
        val nativeQuestions = (0 until questions.length()).map { i ->
            val q = questions.opt(i)
            assertTrue("native question must be a real string", q is String)
            q as String
        }
        assertEquals(expected, nativeQuestions)
        assertEquals(nativeQuestions, LearningEngine.generateDoctorSummary(CycleSettings(), logs))
        assertTrue(LearningEngine.generateClinicianReportText(CycleSettings(), logs)
            .endsWith(expected.mapIndexed { i,q -> "${i+1}. $q" }.joinToString("\n")))
    }

    @Test fun malformedNativeInputRefusesAndLoneSurrogateCallerFallsBack() {
        actualNativeVersionedContractExists()
        assertNull(CycleCareCoreBridge.call("doctor_summary_v1", JSONObject("{\"logs\":[{}]}")))
        val logs = listOf(CycleDayLog("2026-01-01", symptomsCsv="\uD800"))
        assertEquals(listOf("I recorded \uD800 on 1 saved date. Is there anything specific I should monitor or bring to a follow-up?"),
            LearningEngine.generateDoctorSummary(CycleSettings(), logs))
    }
}
