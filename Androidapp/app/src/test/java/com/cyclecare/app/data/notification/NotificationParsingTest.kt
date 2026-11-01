package com.cyclecare.app.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NotificationParsingTest {

    private fun utcMillis(value: String): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.parse(value)!!.time
    }

    @Test
    fun `zulu timestamp parses as UTC`() {
        assertEquals(utcMillis("2026-10-31T08:15:00"), parseIsoTimestamp("2026-10-31T08:15:00Z"))
    }

    @Test
    fun `positive offset is honored not forced to UTC`() {
        // 10:00 at +03:00 is 07:00 UTC
        assertEquals(utcMillis("2026-10-31T07:00:00"), parseIsoTimestamp("2026-10-31T10:00:00+03:00"))
    }

    @Test
    fun `negative offset without colon is honored`() {
        // 10:00 at -05:00 is 15:00 UTC
        assertEquals(utcMillis("2026-10-31T15:00:00"), parseIsoTimestamp("2026-10-31T10:00:00-0500"))
    }

    @Test
    fun `fractional seconds are dropped`() {
        assertEquals(utcMillis("2026-10-31T08:15:00"), parseIsoTimestamp("2026-10-31T08:15:00.123456+00:00"))
    }

    @Test
    fun `bare timestamp is treated as server UTC regardless of device zone`() {
        val default = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi"))
            assertEquals(utcMillis("2026-10-31T08:15:00"), parseIsoTimestamp("2026-10-31T08:15:00"))
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun `invalid input falls back to now`() {
        val before = System.currentTimeMillis()
        val parsed = parseIsoTimestamp("not-a-timestamp")
        assertTrue(parsed in before..System.currentTimeMillis())
    }

    @Test
    fun `null and blank fall back to now`() {
        val before = System.currentTimeMillis()
        assertTrue(parseIsoTimestamp(null) >= before)
        assertTrue(parseIsoTimestamp("") >= before)
        assertTrue(parseIsoTimestamp("   ") >= before)
    }

    @Test
    fun `absent or blank priority is never urgent`() {
        assertEquals("NORMAL", explicitPriorityOrNormal(null))
        assertEquals("NORMAL", explicitPriorityOrNormal(""))
        assertEquals("NORMAL", explicitPriorityOrNormal("   "))
    }

    @Test
    fun `explicit sender priority is preserved case-insensitively`() {
        assertEquals("URGENT", explicitPriorityOrNormal("urgent"))
        assertEquals("HIGH", explicitPriorityOrNormal("High"))
        assertEquals("NORMAL", explicitPriorityOrNormal("NORMAL"))
    }
}
