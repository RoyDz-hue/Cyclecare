package com.cyclecare.app.ui

import com.cyclecare.app.ui.screens.isCompletePin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPinTest {

    @Test
    fun partialPinsAreNeverComplete() {
        assertFalse(isCompletePin(""))
        assertFalse(isCompletePin("1"))
        assertFalse(isCompletePin("12"))
        assertFalse(isCompletePin("123"))
    }

    @Test
    fun exactlyFourDigitsAreComplete() {
        assertTrue(isCompletePin("0000"))
        assertTrue(isCompletePin("1234"))
    }

    @Test
    fun longerOrNonDigitInputIsNotComplete() {
        assertFalse(isCompletePin("12345"))
        assertFalse(isCompletePin("12a4"))
        assertFalse(isCompletePin(" 123"))
    }
}
