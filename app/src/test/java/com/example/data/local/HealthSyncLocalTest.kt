package com.example.data.local

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class HealthSyncLocalTest {
    @Test fun payloadFingerprintIsDeterministicAndChangesWithHealthMutation() {
        val first = CycleDayLog(dateString = "2026-01-02", flowLevel = "LIGHT", painIntensity = 2)
        val second = first.copy(painIntensity = 3)
        val firstPayload = HealthSyncPayloadCodec.logPayload(first)
        assertEquals(firstPayload, HealthSyncPayloadCodec.logPayload(first))
        assertEquals(64, HealthSyncPayloadCodec.hash(firstPayload).length)
        assertNotEquals(HealthSyncPayloadCodec.hash(firstPayload), HealthSyncPayloadCodec.hash(HealthSyncPayloadCodec.logPayload(second)))
    }
    @Test fun deleteEntryHasNullPayloadAndNullPayloadFingerprintInput() {
        val entry = HealthSyncOutboxEntry("owner", "op", "record", "DELETE", 4, 5, null, HealthSyncPayloadCodec.hash(null), 7)
        assertNull(entry.payloadJson); assertEquals(HealthSyncPayloadCodec.hash(null), entry.payloadHash)
        assertEquals(5L, entry.revision); assertEquals(4L, entry.baseRevision)
    }
    @Test fun canonicalLogPayloadRoundTripsAndRejectsWrongRecordIdentity() {
        val log = CycleDayLog("2026-01-02", bbtCelsius = 36.5f, sleepHours = 8f, waterGlasses = 4, painIntensity = 2)
        assertEquals(log, HealthSyncPayloadCodec.parseLogPayload(HealthSyncPayloadCodec.logPayload(log), "2026-01-02"))
        assertTrue(runCatching { HealthSyncPayloadCodec.parseLogPayload(HealthSyncPayloadCodec.logPayload(log), "2026-01-03") }.isFailure)
    }
    @Test fun settingsAndProfileCodecsRejectOutOfRangeAndUnknownFields() {
        val settings = CycleSettings()
        assertEquals(settings, HealthSyncPayloadCodec.parseSettingsPayload(HealthSyncPayloadCodec.settingsPayload(settings), "settings"))
        val profile = OnboardingProfile()
        assertEquals(profile.copy(completedAtTimestamp = 0L, isCompleted = false, isAgeVerified = false, isTermsAgreed = false), HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(profile), "profile"))
        val bad = org.json.JSONObject(HealthSyncPayloadCodec.settingsPayload(settings)).put("average_cycle_length", 999).toString()
        assertTrue(runCatching { HealthSyncPayloadCodec.parseSettingsPayload(bad, "settings") }.isFailure)
        val extra = org.json.JSONObject(HealthSyncPayloadCodec.settingsPayload(settings)).put("unexpected", true).toString()
        assertTrue(runCatching { HealthSyncPayloadCodec.parseSettingsPayload(extra, "settings") }.isFailure)
    }
    @Test fun cloudRestorePreservesDeviceSecurityAndCannotGrantLegalAcceptance() {
        val local = CycleSettings(isAppLockEnabled = true, isDiscreetNotificationMode = true, lockTimeoutMinutes = 0)
        val remote = CycleSettings(isAppLockEnabled = false, isDiscreetNotificationMode = false, averageCycleLength = 30)
        val payload = HealthSyncPayloadCodec.settingsPayload(remote)
        assertFalse(payload.contains("app_lock_enabled"))
        val merged = HealthSyncPayloadCodec.parseSettingsPayload(payload, "settings", local)
        assertTrue(merged.isAppLockEnabled); assertTrue(merged.isDiscreetNotificationMode)
        assertEquals(0, merged.lockTimeoutMinutes); assertEquals(30, merged.averageCycleLength)
        val profilePayload = HealthSyncPayloadCodec.profilePayload(OnboardingProfile(accountMode = "SYNC_ACCOUNT"))
        assertFalse(profilePayload.contains("terms_agreed"))
        val profile = HealthSyncPayloadCodec.parseProfilePayload(profilePayload, "profile")
        assertFalse(profile.isTermsAgreed); assertFalse(profile.isAgeVerified); assertFalse(profile.isCompleted)
        assertEquals("PRIVATE", profile.accountMode)
    }
    @Test fun largeIntegersCannotWrapIntoAnAllowedHealthRange() {
        val payload = org.json.JSONObject(HealthSyncPayloadCodec.logPayload(CycleDayLog("2026-01-02"))).put("water_glasses", 4_294_967_296L)
        assertTrue(runCatching { HealthSyncPayloadCodec.parseLogPayload(payload.toString(), "2026-01-02") }.isFailure)
    }
}
