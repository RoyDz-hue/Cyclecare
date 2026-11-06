package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.domain.safety.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Safety-triage route through the real SafetyEngine.evaluate funnel (called
 * from TrackScreen's candidate-log evaluation, HomeScreen, and
 * CycleCareViewModel). Whichever tier answers - the native bridge if
 * loadable, otherwise the Kotlin engine - must produce these exact triage
 * results; the test intentionally does not assert which tier executed. The
 * native port's matching answers are pinned by the rust core-ffi
 * evaluate_safety_contract dispatch tests. Clinical strings are asserted
 * verbatim because the UI shows them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyEngineRouteTest {

    @Test
    fun nullLogIsRoutineLow() {
        val ev = SafetyEngine.evaluate(null)
        assertEquals(SafetyLevel.LOW, ev.level)
        assertEquals("Routine health status", ev.title)
        assertEquals("No acute symptoms are recorded.", ev.message)
        assertFalse(ev.isRedFlag)
    }

    @Test
    fun faintingPlusHeavyIsUrgent() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20", flowLevel = "UNUSUALLY_HEAVY", symptomsCsv = "fainting"))
        assertEquals(SafetyLevel.URGENT, ev.level)
        assertEquals("Fainting and unusually heavy bleeding were recorded together.", ev.message)
        assertTrue(ev.isRedFlag)
    }

    @Test
    fun heavyPlusSeverePainIsUrgent() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20", flowLevel = "UNUSUALLY_HEAVY", painIntensity = 8))
        assertEquals(SafetyLevel.URGENT, ev.level)
        assertEquals("Unusually heavy bleeding and severe pain were recorded together.", ev.message)
    }

    @Test
    fun soresSeekProfessionalAdvice() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20", vaginalHealthCsv = "sores_lesions"))
        assertEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, ev.level)
        assertTrue(ev.isRedFlag)
    }

    @Test
    fun urinaryPairSeeksAdvice() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20", urinaryHealthCsv = "urinary_burning,urinary_urgency"))
        assertEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, ev.level)
    }

    @Test
    fun irritationIsReview() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20", vaginalHealthCsv = "vaginal_itching"))
        assertEquals(SafetyLevel.REVIEW, ev.level)
        assertFalse(ev.isRedFlag)
    }

    @Test
    fun plainSymptomIsMonitor() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20", symptomsCsv = "headache"))
        assertEquals(SafetyLevel.MONITOR, ev.level)
        assertEquals("Symptoms to monitor", ev.title)
    }

    @Test
    fun emptyLogIsLow() {
        val ev = SafetyEngine.evaluate(CycleDayLog("2026-10-20"))
        assertEquals(SafetyLevel.LOW, ev.level)
    }
}
