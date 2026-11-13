package com.cyclecare.app.ui

import com.cyclecare.app.ui.screens.sanitizeCoinAmountInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoinAmountInputTest {

    @Test
    fun plainIntegerAndDecimalAreAccepted() {
        assertEquals("50" to null, sanitizeCoinAmountInput("50"))
        assertEquals("12.5" to null, sanitizeCoinAmountInput("12.5"))
        assertEquals("12.500" to null, sanitizeCoinAmountInput("12.500"))
        assertEquals("10" to null, sanitizeCoinAmountInput(" 10 "))
    }

    @Test
    fun negativeInputIsKeptAsTypedWithRejectionNotStrippedToPositive() {
        val (text, rejection) = sanitizeCoinAmountInput("-1")
        assertEquals("-1", text)
        assertNotNull(rejection)
    }

    @Test
    fun multipleDotsAreKeptAsTypedWithRejection() {
        val (text, rejection) = sanitizeCoinAmountInput("1.2.3")
        assertEquals("1.2.3", text)
        assertNotNull(rejection)
    }

    @Test
    fun commaIsKeptAsTypedWithRejection() {
        val (text, rejection) = sanitizeCoinAmountInput("1,5")
        assertEquals("1,5", text)
        assertNotNull(rejection)
    }

    @Test
    fun pastedTextIsKeptAsTypedWithRejection() {
        val (text, rejection) = sanitizeCoinAmountInput("1a2")
        assertEquals("1a2", text)
        assertNotNull(rejection)
    }

    @Test
    fun leadingDotGetsSpecificFeedback() {
        val (text, rejection) = sanitizeCoinAmountInput(".5")
        assertEquals(".5", text)
        assertEquals("Start the amount with a digit, for example 0.5.", rejection)
    }

    @Test
    fun emptyInputIsAcceptedAsEmpty() {
        assertNull(sanitizeCoinAmountInput("").second)
        assertNull(sanitizeCoinAmountInput("   ").second)
    }
}
