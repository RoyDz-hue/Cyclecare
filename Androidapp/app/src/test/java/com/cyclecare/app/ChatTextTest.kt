package com.cyclecare.app

import com.cyclecare.app.ui.components.markdownLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTextTest {
    @Test
    fun plainTextPassesThrough() {
        assertEquals("hello friend", markdownLite("hello friend").text)
    }

    @Test
    fun boldMarkersBecomeStyledSpans() {
        val out = markdownLite("rest is **very** okay")
        assertEquals("rest is very okay", out.text)
        assertTrue(out.spanStyles.toString(), out.spanStyles.any { it.start == 8 && it.end == 12 })
    }

    @Test
    fun unbalancedMarkerIsKeptLiterally() {
        val out = markdownLite("half **open")
        assertEquals("half open", out.text)
    }
}
