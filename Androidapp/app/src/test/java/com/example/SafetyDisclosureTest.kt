package com.example

import com.example.ui.screens.SAFETY_LIMITATION_NOTICE
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyDisclosureTest {
    @Test
    fun automatedSafetyLimitsAndLanguageCoverageRemainExplicit() {
        assertTrue(SAFETY_LIMITATION_NOTICE.contains("can miss important symptoms"))
        assertTrue(SAFETY_LIMITATION_NOTICE.contains("languages other than English"))
        assertTrue(SAFETY_LIMITATION_NOTICE.contains("No warning does not mean you are safe"))
        assertTrue(SAFETY_LIMITATION_NOTICE.contains("do not wait for an app or AI response"))
    }
}
