package com.cyclecare.app.data.notification

import org.junit.Assert.assertEquals
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
    fun `fractional seconds keep millisecond precision for same-second ordering`() {
        val first = parseIsoTimestamp("2026-10-31T08:15:00.123456+00:00")
        val second = parseIsoTimestamp("2026-10-31T08:15:00.5Z")
        assertEquals(utcMillis("2026-10-31T08:15:00") + 123, first)
        assertEquals(utcMillis("2026-10-31T08:15:00") + 500, second)
        // The later-created row must sort after the earlier one.
        assertEquals(true, second > first)
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
    fun `impossible dates are rejected not rolled over`() {
        assertEquals(0L, parseIsoTimestamp("2026-13-01T08:15:00Z"))
        assertEquals(0L, parseIsoTimestamp("2026-02-30T08:15:00Z"))
        assertEquals(0L, parseIsoTimestamp("2026-10-31T25:15:00Z"))
    }

    @Test
    fun `trailing garbage and malformed input are rejected`() {
        assertEquals(0L, parseIsoTimestamp("2026-10-31T08:15:00Zjunk"))
        assertEquals(0L, parseIsoTimestamp("not-a-timestamp"))
        assertEquals(0L, parseIsoTimestamp("2026-10-31 08:15:00"))
    }

    @Test
    fun `null blank and invalid sort oldest instead of masquerading as fresh`() {
        assertEquals(0L, parseIsoTimestamp(null))
        assertEquals(0L, parseIsoTimestamp(""))
        assertEquals(0L, parseIsoTimestamp("   "))
        // 0 is before any real notification timestamp, so malformed rows sink.
        assertEquals(true, parseIsoTimestamp("garbage") < parseIsoTimestamp("2020-01-01T00:00:00Z"))
    }

    @Test
    fun `absent blank or unknown priority is never urgent`() {
        assertEquals("NORMAL", explicitPriorityOrNormal(null))
        assertEquals("NORMAL", explicitPriorityOrNormal(""))
        assertEquals("NORMAL", explicitPriorityOrNormal("   "))
        assertEquals("NORMAL", explicitPriorityOrNormal("CRITICAL"))
        assertEquals("NORMAL", explicitPriorityOrNormal("LOW"))
        assertEquals("NORMAL", explicitPriorityOrNormal("urgentish"))
    }

    @Test
    fun `explicit sender priority is preserved case-insensitively`() {
        assertEquals("URGENT", explicitPriorityOrNormal("urgent"))
        assertEquals("HIGH", explicitPriorityOrNormal("High"))
        assertEquals("NORMAL", explicitPriorityOrNormal("NORMAL"))
    }
}
