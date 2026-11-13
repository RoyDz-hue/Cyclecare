package com.cyclecare.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NEW IMPLEMENTATION (recovery rebuild): pins the private Kotlin twins that
 * SomethingDifferentSheet falls back to when something_sheet_v1 declines.
 * Each twin body is the original sheet expression verbatim; expectations
 * mirror the something_sheet JVM-oracle corpus rows (fixtures/
 * something_sheet/jvm_expected.txt), including the QUIRK that the
 * domain->suboptions table exists twice as separate literals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SomethingSheetFallbackParityTest {

    private val facade = Class.forName("com.cyclecare.app.ui.screens.SomethingDifferentSheetKt")

    @Suppress("UNCHECKED_CAST")
    private fun <T> twin(name: String, vararg args: Any?): T {
        val m = facade.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(null, *args) as T
    }

    @Test
    fun tablesMirrorCorpus() {
        val chips: List<String> = twin("kotlinSheetQuickChips")
        assertEquals(
            "Cramps|Fatigue|Headache|Mood shift|Stomach pain|Bloating|Nausea|Restless sleep|Spotting|Just feel off",
            chips.joinToString("|")
        )
        val domains: List<Pair<String, List<String>>> = twin("kotlinSheetDomainChips")
        assertEquals(
            listOf(
                "Physical=Cramps,Headache,Back pain,Breast tenderness",
                "Mood=Anxious,Irritable,Tearful,Low motivation",
                "Energy & Sleep=Heavy fatigue,Brain fog,Restless sleep",
                "Pelvic=Discharge change,Spotting,Deep pressure",
                "Stomach=Bloating,Nausea,Appetite surge"
            ),
            domains.map { (d, subs) -> "$d=${subs.joinToString(",")}" }
        )
        // The second, separate literal (when-chain): same rows today, plus
        // the else -> [] branch.
        for (d in listOf("Physical", "Mood", "Energy & Sleep", "Pelvic", "Stomach", "Unknown", "")) {
            val subs: List<String> = twin("kotlinSheetSubOptions", d)
            val expected = domains.firstOrNull { it.first == d }?.second ?: emptyList()
            assertEquals("$d", expected, subs)
        }
    }

    @Test
    fun appendAndGatesMirrorCorpus() {
        assertEquals("Cramps", twin<String>("kotlinSheetAppendSensation", "", "Cramps"))
        assertEquals("Nausea", twin<String>("kotlinSheetAppendSensation", "  ", "Nausea"))
        assertEquals("Headache, Bloating", twin<String>("kotlinSheetAppendSensation", "Headache", "Bloating"))
        assertEquals("a, b, Spotting", twin<String>("kotlinSheetAppendSensation", "a, b", "Spotting"))
        assertEquals(false, twin<Boolean>("kotlinSheetCardVisible", false, 1))
        assertEquals(true, twin<Boolean>("kotlinSheetCardVisible", true, 1))
        assertEquals(false, twin<Boolean>("kotlinSheetCardVisible", true, 0))
    }

    @Test
    fun labelsMirrorCorpus() {
        assertEquals("• Cramps", twin<String>("kotlinSheetSubItemLabel", "Cramps"))
        assertEquals("• Cramps (severe)", twin<String>("kotlinSheetItemLine", "Cramps", "severe"))
        assertEquals("💭 How long?", twin<String>("kotlinSheetFollowUp", "How long?"))
        assertEquals(true, twin<Boolean>("kotlinSheetShouldParse", "x"))
        assertEquals(false, twin<Boolean>("kotlinSheetShouldParse", "   "))
    }
}
