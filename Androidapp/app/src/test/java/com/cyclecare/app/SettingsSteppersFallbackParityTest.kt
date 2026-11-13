package com.cyclecare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NEW IMPLEMENTATION (recovery rebuild): pins the private Kotlin twins that
 * SettingsScreen falls back to when settings_steppers_v1 declines. Twin
 * bodies are the original expressions verbatim; expectations mirror the
 * JVM-oracle settings_core corpus rows (cycleLength and stepper sections).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSteppersFallbackParityTest {

    private val facade = Class.forName("com.cyclecare.app.ui.screens.SettingsScreenKt")

    private fun invoke0(name: String, arg: Any?): Any? {
        val paramType: Class<*> = if (arg == null || arg is String) String::class.java else Int::class.javaPrimitiveType!!
        val m = facade.getDeclaredMethod(name, paramType)
        m.isAccessible = true
        return m.invoke(null, arg)
    }

    @Test
    fun cycleLengthIsSetMirrorsCorpusRows() {
        val cases = listOf(
            "28 days" to true,
            "unknown" to false,
            "I don't know" to false,
            "I don’t know" to false,
            "It changes" to false,
            "  30 Days  " to true,
            "" to false,
            null to false,
            "UNKNOWN" to false,
            "my cycle changes a lot" to false,
        )
        for ((input, expected) in cases) {
            assertEquals("isSet for $input", expected, invoke0("kotlinCycleLengthIsSet", input))
        }
    }

    @Test
    fun stepperGuardsMirrorCorpusBoundaries() {
        assertNull(invoke0("kotlinDecreaseCycleLength", 21))
        assertEquals(21, invoke0("kotlinDecreaseCycleLength", 22))
        assertNull(invoke0("kotlinIncreaseCycleLength", 45))
        assertEquals(45, invoke0("kotlinIncreaseCycleLength", 44))
        assertNull(invoke0("kotlinDecreasePeriodDuration", 2))
        assertEquals(2, invoke0("kotlinDecreasePeriodDuration", 3))
        assertNull(invoke0("kotlinIncreasePeriodDuration", 10))
        assertEquals(10, invoke0("kotlinIncreasePeriodDuration", 9))
    }
}
