package com.cyclecare.app

import com.cyclecare.app.data.local.HealthSyncPayloadCodec
import com.cyclecare.app.data.local.OnboardingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Executable contract tests for the profile sync payload: the raw period end
 * date must round-trip verbatim through the v3 health-sync JSON path, and old
 * and new payload versions must stay mutually readable.
 */
class HealthSyncProfilePayloadTest {

    private fun profile() = OnboardingProfile(
        selectedGoalsCsv = "track my periods",
        lastPeriodDateString = "2026-09-01",
        lastPeriodEndDateString = "2026-09-20", // a confirmed 20-day episode
        approximatePeriodChoice = "EXACT",
        cycleLengthOption = "28 days",
        cycleLengthDays = 28,
        periodDurationDays = 5, // typical estimate - untouched by the episode
        typicalSymptomsCsv = "cramps",
        typicalEnergy = "NORMAL",
        typicalSleepQuality = "GOOD",
        hasCompletedDeepBaseline = true
    )

    @Test
    fun rawEndDateRoundTripsVerbatimThroughPayload() {
        val parsed = HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(profile()), "profile")
        assertEquals("2026-09-20", parsed.lastPeriodEndDateString)
        assertEquals("2026-09-01", parsed.lastPeriodDateString)
        assertEquals(5, parsed.periodDurationDays)
    }

    @Test
    fun oldPayloadWithoutEndDateStillParses() {
        // Payload shape written by installs before the raw-end-date field.
        val oldPayload = """{"selected_goals":"g","last_period_date":"2026-09-01","approximate_period_choice":"EXACT","cycle_length_option":"","cycle_length_days":28,"period_duration_days":5,"typical_symptoms":"","typical_energy":"NORMAL","typical_sleep_quality":"GOOD","has_completed_deep_baseline":false}"""
        val parsed = HealthSyncPayloadCodec.parseProfilePayload(oldPayload, "profile")
        assertEquals("", parsed.lastPeriodEndDateString)
        assertEquals("2026-09-01", parsed.lastPeriodDateString)
    }

    @Test
    fun unknownFutureKeysAreIgnoredNotRejected() {
        val futurePayload = HealthSyncPayloadCodec.profilePayload(profile())
            .removeSuffix("}")
            .plus(""","future_unknown_field":"x","another":123}""")
        val parsed = HealthSyncPayloadCodec.parseProfilePayload(futurePayload, "profile")
        assertEquals("2026-09-20", parsed.lastPeriodEndDateString)
    }

    @Test
    fun malformedEndDateIsRejected() {
        val bad = HealthSyncPayloadCodec.profilePayload(profile().copy(lastPeriodEndDateString = "not-a-date"))
        try {
            HealthSyncPayloadCodec.parseProfilePayload(bad, "profile")
            fail("invalid end date must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("invalid_end_date"))
        }
    }

    @Test
    fun wrongTypeEndDateIsRejected() {
        val bad = HealthSyncPayloadCodec.profilePayload(profile())
            .removeSuffix("}")
            .replace("\"last_period_end_date\":\"2026-09-20\"", "\"last_period_end_date\":20")
            .plus("}")
        try {
            HealthSyncPayloadCodec.parseProfilePayload(bad, "profile")
            fail("non-string end date must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("type_last_period_end_date"))
        }
    }

    @Test
    fun typicalEstimateStaysWithinSyncRange() {
        // The estimate contract (2..10) still holds for the estimate field;
        // the raw episode record is a separate key and is never clamped.
        val parsed = HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(profile()), "profile")
        assertTrue(parsed.periodDurationDays in 2..10)
        assertEquals("2026-09-20", parsed.lastPeriodEndDateString)
    }
}
