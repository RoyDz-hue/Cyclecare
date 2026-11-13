package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.learning.LearningEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Required real host JNI, never a skip/fallback-only parity pass. Not Android proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoctorSummaryNativeParityTest {
    private val settings = CycleSettings()
    private fun label(s: String) = s.replace('_', ' ').trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    private fun parity(logs: List<CycleDayLog>) {
        val native = CycleCareCoreBridge.doctorSummary(logs, ::label)
        assertNotNull("real doctor_summary_v1 JNI required", native)
        val expected = LearningEngine.generateDoctorSummaryKotlin(settings, logs)
        assertEquals(expected, native)
        assertEquals(expected, LearningEngine.generateDoctorSummary(settings, logs))
    }
    @Test fun emptyAndInvalidDates() {
        parity(emptyList())
        parity(listOf(CycleDayLog("bad", symptomsCsv="pain", painIntensity=10, flowLevel="HEAVY")))
        parity(listOf(CycleDayLog("2026-02-29", symptomsCsv="pain")))
    }
    @Test fun thresholdCaseOrderingDedupAndFirstDateWins() {
        parity(listOf(
            CycleDayLog("2026-11-03", symptomsCsv="z,z, a, b, c", painIntensity=6, flowLevel="heavy"),
            CycleDayLog("2026-11-01", symptomsCsv="z,a", painIntensity=7, flowLevel="HEAVY"),
            CycleDayLog("2026-11-01", symptomsCsv="wrong", painIntensity=10, flowLevel="UNUSUALLY_HEAVY"),
            CycleDayLog("2026-11-02", symptomsCsv="c,b", painIntensity=Int.MAX_VALUE, flowLevel="UNUSUALLY_HEAVY")))
        parity(listOf(CycleDayLog("2026-11-01", painIntensity=7, flowLevel="HEAVY")))
    }
    @Test fun fullJavaDateGrammarAndEquivalentDateDedup() {
        val dates = listOf("+999999999-12-31", "-999999999-01-01", "0000-02-29", "-0000-01-01",
            "+10000-01-01", "+0010000-01-01", "10000-01-01", "2026-1-01", "2026-01-1",
            "+2026-01-01", "2026-01-01 ", "-0001-01-01", "2026-04-31", "2024-02-29")
        for (date in dates) parity(listOf(CycleDayLog(date, symptomsCsv="cramps", painIntensity=7, flowLevel="HEAVY")))
        parity(dates.mapIndexed { i, d -> CycleDayLog(d, symptomsCsv="s$i", painIntensity=i, flowLevel="HEAVY") })
    }
    @Test fun exactUnicodeTitlecaseWhitespaceUtf16TieAndOutputDistinct() {
        val symptoms = listOf("ßeta", "ǳone", "ﬃx", "ıtest", "écho", "𐐨case", "🌸", "\uE000",
            "\u00a0a_b\u2007", "a b", "a_b", "\u0085x", "\u001c", "\u3000", "_lower")
        for (s in symptoms) parity(listOf(CycleDayLog("2026-01-01", symptomsCsv=s)))
        parity(listOf(CycleDayLog("2026-01-01", symptomsCsv="\uE000,🌸,𐐨case,a,b")))
        parity(listOf(CycleDayLog("2026-01-01", symptomsCsv="a_b,a b,A b")))
    }
    @Test fun generatedDeterministicCorpusAndSettingsUnused() {
        val random = java.util.Random(1907)
        val symptoms = listOf("", "cramps, fatigue", "z,a,a,b,c", "ß,ǳ,🌸,\uE000", "\u00a0x\u00a0,x")
        repeat(100) {
            val logs = (0 until random.nextInt(14)).map {
                CycleDayLog(if (random.nextInt(8)==0) "bad" else "2026-01-%02d".format(1+random.nextInt(8)),
                    symptomsCsv=symptoms[random.nextInt(symptoms.size)], painIntensity=random.nextInt(14)-2,
                    flowLevel=listOf("HEAVY","UNUSUALLY_HEAVY","heavy", "NONE")[random.nextInt(4)])
            }
            parity(logs)
            assertEquals(LearningEngine.generateDoctorSummary(settings, logs),
                LearningEngine.generateDoctorSummary(settings.copy(averageCycleLength=Int.MIN_VALUE), logs))
        }
    }
    @Test fun loneSurrogatesKeepExactKotlinFallback() {
        for (s in listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800")) {
            val logs = listOf(CycleDayLog("2026-01-01", symptomsCsv=s))
            assertNull(CycleCareCoreBridge.doctorSummary(logs, ::label))
            assertEquals(LearningEngine.generateDoctorSummaryKotlin(settings, logs),
                LearningEngine.generateDoctorSummary(settings, logs))
        }
    }
    @Test fun malformedRepliesCannotBecomeEmptySuccess() {
        val bad = listOf("{}", "{\"ok\":null}", "{\"ok\":[]}",
            "{\"ok\":{\"version\":\"1\",\"questions\":[]}}",
            "{\"ok\":{\"version\":1.0,\"questions\":[]}}",
            "{\"ok\":{\"version\":1,\"questions\":[1]}}",
            "{\"ok\":{\"version\":1,\"questions\":[null]}}",
            "{\"ok\":{\"version\":1,\"questions\":[\"\"]}}",
            "{\"ok\":{\"version\":1,\"questions\":[\"x\",\"x\"]}}",
            "{\"ok\":{\"version\":1,\"questions\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}}",
            "{\"ok\":{\"version\":1,\"questions\":[],\"extra\":0}}",
            "{\"ok\":{\"version\":1,\"questions\":[]},\"error\":\"bad\"}")
        bad.forEach { assertNull(it, CycleCareCoreBridge.decodeDoctorSummaryReply(JSONObject(it))) }
        assertEquals(emptyList<String>(), CycleCareCoreBridge.decodeDoctorSummaryReply(JSONObject("{\"ok\":{\"version\":1,\"questions\":[]}}")))
    }
    @Test fun strictNativeInputSchema() {
        for (body in listOf("{}", "{\"logs\":null}", "{\"logs\":[{}]}",
            "{\"logs\":[{\"date\":\"2026-01-01\",\"symptoms\":[],\"pain\":7.0,\"flow\":\"HEAVY\"}]}",
            "{\"logs\":[{\"date\":\"2026-01-01\",\"symptoms\":[],\"pain\":2147483648,\"flow\":\"HEAVY\"}]}",
            "{\"logs\":[],\"extra\":true}")) {
            assertNull(body, CycleCareCoreBridge.call("doctor_summary_v1", JSONObject(body)))
        }
        assertNotNull(CycleCareCoreBridge.call("doctor_summary_v1", JSONObject("{\"logs\":[]}")))
    }
}
