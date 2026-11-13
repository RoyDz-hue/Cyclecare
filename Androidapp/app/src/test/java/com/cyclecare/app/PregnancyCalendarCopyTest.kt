package com.cyclecare.app

import com.cyclecare.app.ui.screens.CALENDAR_FERTILITY_NOTICE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PregnancyCalendarCopyTest {
    @Test
    fun fertilityNoticeExplainsCalendarUncertaintyRatherThanMonitoringOvulation() {
        assertTrue(CALENDAR_FERTILITY_NOTICE.contains("calendar-based estimates"))
        assertTrue(CALENDAR_FERTILITY_NOTICE.contains("cannot confirm ovulation"))
        assertTrue(CALENDAR_FERTILITY_NOTICE.contains("must not be used as contraception"))
        assertFalse(CALENDAR_FERTILITY_NOTICE.contains("monitors your fertile window"))
        assertFalse(CALENDAR_FERTILITY_NOTICE.contains("ovulation peak"))
        assertFalse(CALENDAR_FERTILITY_NOTICE.contains("best conception days"))
    }
}
