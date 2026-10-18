package com.example

import com.example.data.model.SafetyLevel
import com.example.domain.safety.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

}
