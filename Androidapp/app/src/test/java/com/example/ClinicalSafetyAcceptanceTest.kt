package com.example

import com.example.data.local.CycleDayLog
import com.example.data.model.SafetyLevel
import com.example.domain.safety.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engineering acceptance regressions, not clinical validation or clinician sign-off.
 * Thresholds and wording require review by a qualified clinical reviewer.
 */
class ClinicalSafetyAcceptanceTest {
    @Test
    fun faintingAndVeryHeavyBleedingAreUrgentBeforeAnyNetworkPath() {
        val result = SafetyEngine.evaluateInput("I fainted and have very heavy bleeding")

        assertEquals(SafetyLevel.URGENT, result.level)
        assertTrue(result.isRedFlag)
        assertTrue(result.actionAdvice.contains("urgent", ignoreCase = true))
    }

    @Test
    fun heavyBleedingAndSeverePainAreUrgent() {
        val result = SafetyEngine.evaluateInput("Heavy bleeding and severe pain are happening")

        assertEquals(SafetyLevel.URGENT, result.level)
        assertTrue(result.isRedFlag)
        assertTrue(result.actionAdvice.contains("cannot assess", ignoreCase = true))
    }

    @Test
    fun faintingAloneRemainsUrgent() {
        val result = SafetyEngine.evaluateInput("I passed out")

        assertEquals(SafetyLevel.URGENT, result.level)
        assertTrue(result.isRedFlag)
    }

    @Test
    fun severePainAloneRequestsProfessionalAdviceWithoutDiagnosis() {
        val result = SafetyEngine.evaluateInput("I have severe pelvic pain")

        assertEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, result.level)
        assertFalse(result.title.contains("diagnos", ignoreCase = true))
    }

    @Test
    fun soresAndUrinaryClusterRequestAssessment() {
        val sores = SafetyEngine.evaluateInput("I have new sores")
        val urinary = SafetyEngine.evaluateInput("Burning when I pee and I am urgent to pee")

        assertEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, sores.level)
        assertEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, urinary.level)
    }

    @Test
    fun negationDoesNotPromoteAFalseRedFlag() {
        val result = SafetyEngine.evaluateInput("No fainting and no sores, just mild cramps")

        assertNotEquals(SafetyLevel.URGENT, result.level)
        assertNotEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, result.level)
    }

    @Test
    fun savedUrgencyCannotBeSuppressedByNewLowerSeverityTextAndNoWriteOccurs() {
        val log = CycleDayLog("2026-10-18", symptomsCsv = "fainting", notes = "")

        val result = SafetyEngine.evaluateInput("I have mild cramps", log)

        assertEquals(SafetyLevel.URGENT, result.level)
        assertEquals("", log.notes)
    }

    @Test
    fun plainEnglishShortPhraseIsHandledLocallyWithoutAnAiClaim() {
        val result = SafetyEngine.evaluateInput("I fainted and soaking pads")

        assertEquals(SafetyLevel.URGENT, result.level)
        assertFalse(result.title.contains("AI", ignoreCase = true))
    }
}
