package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CycleCareDatabase
import com.example.data.local.OnboardingProfile
import com.example.data.repository.CycleRepository
import com.example.data.repository.PeriodDateRange
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
@Config(sdk = [34])
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
    fun testRepositoryCompleteOnboardingDoesNotGenerateCycleLogs() = runBlocking {
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

        // An entered date anchors calendar estimates; it does not imply a saved flow observation.
        assertNull(repository.getLogForDate(periodStart.toString()))
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
        assertNull(repository.getLogForDate("2026-09-20"))
        val settings = repository.getSettings()
        assertFalse(settings.isOnboardingCompleted)
    }

    @Test
    fun testPeriodDateRangeDataStructureAndValidation() {
        val start = LocalDate.of(2026, 9, 20)
        val end = LocalDate.of(2026, 9, 25)

        val range = PeriodDateRange(
            period_start_date = start,
            period_end_date = end
        )

        assertEquals(start, range.period_start_date)
        assertEquals(end, range.period_end_date)
        assertEquals(6, range.durationDays)
        assertTrue(range.isValid())

        // Same-day period (start == end)
        val sameDayRange = PeriodDateRange(
            period_start_date = start,
            period_end_date = start
        )
        assertEquals(1, sameDayRange.durationDays)
        assertTrue(sameDayRange.isValid())

        // Invalid period (end < start)
        val invalidRange = PeriodDateRange(
            period_start_date = end,
            period_end_date = start
        )
        assertFalse(invalidRange.isValid())

        // Test helper validation function
        assertTrue(repository.validatePeriodDates(start, end))
        assertTrue(repository.validatePeriodDates(start, start))
        assertFalse(repository.validatePeriodDates(end, start))
        assertFalse(repository.validatePeriodDates(null, end))
        assertFalse(repository.validatePeriodDates(start, null))

        // Static companion helper
        assertTrue(CycleRepository.validatePeriodDates(start, end))
        assertFalse(CycleRepository.validatePeriodDates(end, start))

        // Detailed validation helper
        val validResult = repository.validatePeriodDatePair(start, end)
        assertTrue(validResult.isValid)
        assertEquals(6, validResult.durationDays)
        assertNull(validResult.errorMessage)

        val invalidResult = repository.validatePeriodDatePair(end, start)
        assertFalse(invalidResult.isValid)
        assertNotNull(invalidResult.errorMessage)
    }

    @Test
    fun testRepositoryStoreAndRetrievePeriodDates() {
        val start = LocalDate.of(2026, 9, 10)
        val end = LocalDate.of(2026, 9, 15)

        // Storing valid pair succeeds
        val stored = repository.storePeriodDates(start, end)
        assertTrue(stored)
        val retrieved = repository.getStoredPeriodDates()
        assertNotNull(retrieved)
        assertEquals(start, retrieved?.period_start_date)
        assertEquals(end, retrieved?.period_end_date)

        // Attempting to store invalid pair (end < start) fails
        val invalidStored = repository.storePeriodDates(end, start)
        assertFalse(invalidStored)
        // Previous valid value is preserved
        assertEquals(start, repository.getStoredPeriodDates()?.period_start_date)
    }
}
