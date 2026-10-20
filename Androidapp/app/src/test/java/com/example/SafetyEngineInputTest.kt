package com.example

import com.example.data.model.SafetyLevel
import com.example.domain.safety.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyEngineInputTest {
    @Test
    fun newInputIsEvaluatedLocallyWithoutAStoredLog() {
        val result = SafetyEngine.evaluateInput("I fainted and have very heavy bleeding")
        assertEquals(SafetyLevel.URGENT, result.level)
        assertEquals(true, result.isRedFlag)
    }

    @Test
    fun explicitNegationDoesNotCreateAFaintingFlag() {
        val result = SafetyEngine.evaluateInput("No fainting and no sores, just mild cramps")
        assertNotEquals(SafetyLevel.URGENT, result.level)
        assertNotEquals(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, result.level)
    }

    @Test
    fun multipleObservationsAreCombinedOnlyWhenEachIsPositive() {
        val result = SafetyEngine.evaluateInput("Heavy bleeding and severe pain are happening")
        assertEquals(SafetyLevel.URGENT, result.level)
    }
    @Test fun savedIdentifiersNormalizeCaseSpacingAndFlow() {
        val log = com.example.data.local.CycleDayLog("2026-10-18", flowLevel = " unusually_heavy ", symptomsCsv = " FAINTING , headache ")
        assertEquals(SafetyLevel.URGENT, SafetyEngine.evaluate(log).level)
    }
    @Test fun newLowerSeverityNeverSuppressesSavedUrgency() {
        val log = com.example.data.local.CycleDayLog("2026-10-18", symptomsCsv = "fainting")
        assertEquals(SafetyLevel.URGENT, SafetyEngine.evaluateInput("I have new sores", log).level)
    }
    @Test fun negationStopsAtConjunctionBeforePositiveUrgentObservation() {
        assertEquals(SafetyLevel.URGENT, SafetyEngine.evaluateInput("No sores but I fainted and I have very heavy bleeding").level)
    }
    @Test fun savedAndNewObservationsAreAssessedTogetherWithoutAWrite() {
        val log = com.example.data.local.CycleDayLog("2026-10-18", flowLevel = "UNUSUALLY_HEAVY")
        assertEquals(SafetyLevel.URGENT, SafetyEngine.evaluateInput("I have severe pain", log).level)
        assertEquals("", log.notes)
    }

    @Test fun unrecognisedTextNeverClaimsANegativeSafetyScreen() {
        for (input in listOf("Tengo sangrado muy abundante", "Ninatokwa na damu nyingi", "أشعر بألم", "我很不舒服", "Something feels wrong", "???")) {
            val result = SafetyEngine.evaluateInput(input)
            assertEquals(input, SafetyLevel.REVIEW, result.level)
            assertEquals("Safety assessment limited", result.title)
            assertTrue(result.message.contains("does not mean symptoms are safe"))
            assertTrue(result.actionAdvice.contains("interpreter"))
            assertFalse("Unrecognised input is not itself a diagnosed emergency", result.isRedFlag)
        }
    }

    @Test fun explicitlyNegatedInputStillDisclosesLimitsWithoutInventingAnUrgentFlag() {
        val result = SafetyEngine.evaluateInput("No fainting and no sores")
        assertEquals(SafetyLevel.REVIEW, result.level)
        assertFalse(result.isRedFlag)
    }

    @Test fun mixedLanguageCannotSuppressRecognisedUrgency() {
        assertEquals(SafetyLevel.URGENT, SafetyEngine.evaluateInput("Ninaumwa, I fainted and have very heavy bleeding").level)
    }

    @Test fun unsupportedTextPreservesSavedUrgentFindingsAndDoesNotWrite() {
        val log = com.example.data.local.CycleDayLog("2026-10-19", symptomsCsv = "fainting", notes = "unchanged")
        assertEquals(SafetyLevel.URGENT, SafetyEngine.evaluateInput("Tengo sangrado muy abundante", log).level)
        assertEquals("unchanged", log.notes)
    }

    @Test fun limitedAssessmentKeepsExistingNonUrgentObservationVisible() {
        val log = com.example.data.local.CycleDayLog("2026-10-19", symptomsCsv = "headache")
        val result = SafetyEngine.evaluateInput("Something else feels wrong", log)
        assertEquals(SafetyLevel.REVIEW, result.level)
        assertTrue(result.message.contains("Symptoms were recorded"))
        assertTrue(result.message.contains("does not mean symptoms are safe"))
    }

    @Test fun blankInputRetainsExistingLogEvaluation() {
        assertEquals(SafetyEngine.evaluate(null), SafetyEngine.evaluateInput("  "))
    }
}
