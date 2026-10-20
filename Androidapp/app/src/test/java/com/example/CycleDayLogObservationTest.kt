package com.example

import com.example.data.local.CycleDayLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleDayLogObservationTest {
    @Test
    fun newLogHasNoReportedObservations() {
        val emptyLog = CycleDayLog(dateString = "2026-10-16")

        assertFalse(emptyLog.hasRecordedObservations())
    }

    @Test
    fun explicitSelectionsCountAsObservations() {
        val selectedMood = CycleDayLog(dateString = "2026-10-16", moodLevel = "GOOD")
        val explicitlyNoFlow = CycleDayLog(dateString = "2026-10-16", flowLevel = "NONE")

        assertTrue(selectedMood.hasRecordedObservations())
        assertTrue(explicitlyNoFlow.hasRecordedObservations())
    }
}
