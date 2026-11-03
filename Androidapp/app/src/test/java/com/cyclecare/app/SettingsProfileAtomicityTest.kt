package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.repository.CycleRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Fault injection: a SQLite trigger aborts the profile write, so the SECOND journaled write
 * in updateSettingsBound fails after the settings write succeeded. With one enclosing
 * transaction the settings write must roll back. Plain temporary Room database, no-prefs
 * repository (requireTokenFresh is skipped), not SQLCipher. UNRUN: needs the owner's runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsProfileAtomicityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "atomic-${UUID.randomUUID()}"
    private val db: CycleCareDatabase by lazy {
        context.getDatabasePath(name).parentFile?.mkdirs()
        Room.databaseBuilder(context, CycleCareDatabase::class.java, name).allowMainThreadQueries().build()
    }
    @After fun cleanup() { if (db.isOpen) db.close(); context.deleteDatabase(name) }

    private fun profile() = OnboardingProfile(
        id = 1, isCompleted = true, completedAtTimestamp = 1L, isAgeVerified = true, isTermsAgreed = true,
        accountMode = "PRIVATE", selectedGoalsCsv = "g", lastPeriodDateString = "2026-10-05",
        approximatePeriodChoice = "EXACT", cycleLengthOption = "28", cycleLengthDays = 28, periodDurationDays = 5
    )

    @Test fun profileWriteFailureRollsBackTheSettingsWrite() = runBlocking {
        db.cycleDao().saveSettings(CycleSettings(id = 1, averageCycleLength = 28, periodDuration = 5))
        db.onboardingDao().insertOrUpdateProfile(profile())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_profile BEFORE INSERT ON onboarding_profiles BEGIN SELECT RAISE(ABORT, 'injected'); END")
        val repo = CycleRepository(db).apply { activateDatabaseForTest(db, "A") }
        try {
            repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 31, periodDuration = 6), 28)
            fail("the injected profile failure must propagate")
        } catch (_: Exception) { }
        assertEquals("settings must roll back with the failed profile write", 28, db.cycleDao().getSettings()?.averageCycleLength)
        assertEquals(28, db.onboardingDao().getProfile()?.cycleLengthDays)
    }

    @Test fun bothWritesLandWhenNothingFails() = runBlocking {
        db.cycleDao().saveSettings(CycleSettings(id = 1, averageCycleLength = 28, periodDuration = 5))
        db.onboardingDao().insertOrUpdateProfile(profile())
        val repo = CycleRepository(db).apply { activateDatabaseForTest(db, "A") }
        repo.updateSettingsBound(CycleSettings(id = 1, averageCycleLength = 31, periodDuration = 6), 28)
        assertEquals(31, db.cycleDao().getSettings()?.averageCycleLength)
        assertEquals(31, db.onboardingDao().getProfile()?.cycleLengthDays)
    }
}
