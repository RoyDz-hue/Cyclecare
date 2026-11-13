package com.cyclecare.app.data.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NEW IMPLEMENTATION (recovery rebuild): pins the private Kotlin twins that
 * SupabaseNotificationService falls back to when notification_parse_v1
 * declines. The twin bodies are the original parseIsoTimestamp /
 * explicitPriorityOrNormal expressions verbatim; these pins ground them in
 * JVM semantics, mirroring the cases the Rust notifications corpus pins
 * against the same Kotlin behavior (NotificationParsingTest), plus the
 * strict-parsing rejects the conservative FFI declines assume.
 * Expected timestamp constants are proleptic-Gregorian epoch arithmetic
 * (1970 epoch, UTC), equal to java.util time for these modern dates.
 */
class NotificationParseFallbackParityTest {

    private val facade = Class.forName("com.cyclecare.app.data.notification.SupabaseNotificationServiceKt")

    private fun twinTimestamp(iso: String?): Long {
        val m = facade.getDeclaredMethod("kotlinParseIsoTimestamp", String::class.java)
        m.isAccessible = true
        return m.invoke(null, iso) as Long
    }

    private fun twinPriority(raw: String?): String {
        val m = facade.getDeclaredMethod("kotlinExplicitPriorityOrNormal", String::class.java)
        m.isAccessible = true
        return m.invoke(null, raw) as String
    }

    @Test
    fun zuluTimestampParsesAsUtc() {
        assertEquals(1793434500000L, twinTimestamp("2026-10-31T08:15:00Z"))
    }

    @Test
    fun positiveOffsetIsHonoredNotForcedToUtc() {
        assertEquals(1793430000000L, twinTimestamp("2026-10-31T10:00:00+03:00"))
    }

    @Test
    fun negativeOffsetWithoutColonIsHonored() {
        assertEquals(1793458800000L, twinTimestamp("2026-10-31T10:00:00-0500"))
    }

    @Test
    fun fractionalSecondsKeepMillisecondPrecision() {
        assertEquals(1793434500123L, twinTimestamp("2026-10-31T08:15:00.123456+00:00"))
        assertEquals(1793434500500L, twinTimestamp("2026-10-31T08:15:00.5Z"))
    }

    @Test
    fun bareTimestampIsServerUtc() {
        assertEquals(1793434500000L, twinTimestamp("2026-10-31T08:15:00"))
    }

    @Test
    fun unparseableInputSortsOldestAsZero() {
        assertEquals(0L, twinTimestamp("not a timestamp"))
        assertEquals(0L, twinTimestamp(""))
        assertEquals(0L, twinTimestamp(null))
    }

    @Test
    fun strictParsingRejectsImpossibleDatesAndLeapSeconds() {
        // Non-lenient java.util.Calendar ranges: MONTH 0..11, DAY_OF_MONTH
        // per month/leap year, SECOND 0..59 (a leap-second :60 is rejected,
        // which is exactly the chrono divergence the FFI declines).
        assertEquals(0L, twinTimestamp("2026-13-01T00:00:00Z"))
        assertEquals(0L, twinTimestamp("2026-02-30T00:00:00Z"))
        assertEquals(0L, twinTimestamp("2026-10-31T23:59:60Z"))
    }

    @Test
    fun explicitPriorityNormalizesCaseAndWhitespace() {
        assertEquals("URGENT", twinPriority("URGENT"))
        assertEquals("HIGH", twinPriority("high"))
        assertEquals("NORMAL", twinPriority(" normal "))
    }

    @Test
    fun missingOrUnrecognizedPriorityIsNeverUrgent() {
        assertEquals("NORMAL", twinPriority(null))
        assertEquals("NORMAL", twinPriority(""))
        assertEquals("NORMAL", twinPriority("URGENT2"))
    }
}
