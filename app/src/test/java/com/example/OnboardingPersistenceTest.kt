package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CycleCareDatabase
import com.example.data.local.OnboardingProfile
import com.example.data.repository.CycleRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingPersistenceTest {

    private lateinit var database: CycleCareDatabase
    private lateinit var repository: CycleRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CycleRepository(database)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testOnboardingProfileInsertionAndRetrieval() = runBlocking {
        val onboardingDao = database.onboardingDao()
        assertNull(onboardingDao.getProfile())

        val profile = OnboardingProfile(
            id = 1,
            isCompleted = true,
            isAgeVerified = true,
            isTermsAgreed = true,
            accountMode = "PRIVATE",
            selectedGoalsCsv = "🌸 Understand my cycle,🩷 Track my periods",
            lastPeriodDateString = "2026-09-20",
            approximatePeriodChoice = "EXACT",
            cycleLengthOption = "28 days",
            cycleLengthDays = 28,
            periodDurationDays = 5,
            remindFertileWindow = true
        )

        onboardingDao.insertOrUpdateProfile(profile)

        val retrieved = onboardingDao.getProfile()
        assertNotNull(retrieved)
        assertEquals(true, retrieved?.isCompleted)
        assertEquals(true, retrieved?.isAgeVerified)
        assertEquals(true, retrieved?.isTermsAgreed)
        assertEquals(28, retrieved?.cycleLengthDays)
        assertEquals("2026-09-20", retrieved?.lastPeriodDateString)
    }

    @Test
    fun testRepositoryCompleteOnboardingGeneratesCycleLogs() = runBlocking {
        val periodStart = LocalDate.now().minusDays(8)
        val profile = OnboardingProfile(
            id = 1,
            isCompleted = true,
            isAgeVerified = true,
            isTermsAgreed = true,
            accountMode = "PRIVATE",
            selectedGoalsCsv = "🌸 Understand my cycle",
            lastPeriodDateString = periodStart.toString(),
            approximatePeriodChoice = "EXACT",
            cycleLengthOption = "28 days",
            cycleLengthDays = 28,
            periodDurationDays = 5,
            remindFertileWindow = true
        )

        repository.completeOnboardingWithProfile(profile)

        // Verify profile was persisted
        val savedProfile = repository.getOnboardingProfile()
        assertNotNull(savedProfile)
        assertEquals(true, savedProfile?.isCompleted)

        // Verify settings were updated
        val settings = repository.getSettings()
        assertTrue(settings.isOnboardingCompleted)
        assertEquals(28, settings.averageCycleLength)
        assertEquals(periodStart.toString(), settings.lastPeriodStartDate)

        // Verify initial period logs were generated matching the entered date
        val firstDayLog = repository.getLogForDate(periodStart.toString())
        assertNotNull(firstDayLog)
        assertEquals("MODERATE", firstDayLog?.flowLevel)
    }

    @Test
    fun testDeleteAllLogsResetsOnboarding() = runBlocking {
        val profile = OnboardingProfile(
            id = 1,
            isCompleted = true,
            isAgeVerified = true,
            isTermsAgreed = true,
            accountMode = "PRIVATE",
            selectedGoalsCsv = "🌸 Understand my cycle",
            lastPeriodDateString = "2026-09-20",
            cycleLengthDays = 28
        )
        repository.completeOnboardingWithProfile(profile)
        assertNotNull(repository.getOnboardingProfile())

        repository.deleteAllLogs()

        assertNull(repository.getOnboardingProfile())
        val settings = repository.getSettings()
        assertFalse(settings.isOnboardingCompleted)
    }
}
