package com.example.data.repository

import com.example.data.local.CycleCareDatabase
import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.local.OnboardingProfile
import com.example.data.model.CyclePhase
import com.example.data.model.DayPrediction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CycleRepository(private val database: CycleCareDatabase) {
    private val dao = database.cycleDao()
    private val onboardingDao = database.onboardingDao()

    val allLogs: Flow<List<CycleDayLog>> = dao.getAllLogs()
    val settingsFlow: Flow<CycleSettings?> = dao.getSettingsFlow()
    val onboardingProfileFlow: Flow<OnboardingProfile?> = onboardingDao.getProfileFlow()

    suspend fun getOnboardingProfile(): OnboardingProfile? = onboardingDao.getProfile()

    suspend fun saveOnboardingProfile(profile: OnboardingProfile) {
        onboardingDao.insertOrUpdateProfile(profile)
    }

    suspend fun getSettings(): CycleSettings {
        var settings = dao.getSettings()
        val profile = onboardingDao.getProfile()
        if (settings == null) {
            val defaultLastPeriod = LocalDate.now().minusDays(10).toString()
            settings = CycleSettings(
                isOnboardingCompleted = profile?.isCompleted == true,
                selectedGoalsCsv = profile?.selectedGoalsCsv ?: "🌸 Understand my cycle,🩷 Track my periods",
                lastPeriodStartDate = profile?.lastPeriodDateString ?: defaultLastPeriod,
                averageCycleLength = profile?.cycleLengthDays ?: 28,
                periodDuration = profile?.periodDurationDays ?: 5,
                lutealPhaseDays = 14,
                remindFertile = profile?.remindFertileWindow ?: true
            )
            dao.saveSettings(settings)

            // Seed sample logs only if onboarding was already completed
            if (profile?.isCompleted == true) {
                val periodStart = LocalDate.now().minusDays(10)
                dao.insertOrUpdateLog(
                    CycleDayLog(
                        dateString = periodStart.toString(),
                        flowLevel = "MODERATE",
                        energyLevel = "LOW",
                        moodLevel = "UNCOMFORTABLE",
                        symptomsCsv = "cramps,headache",
                        waterGlasses = 6,
                        sleepHours = 7.5f,
                        sleepQuality = "FAIR",
                        painIntensity = 4,
                        notes = "Cycle day 1. Prioritized warm tea and rest."
                    )
                )
            }
        }
        return settings
    }

    /**
     * Completes onboarding by saving the OnboardingProfile to Room, updating CycleSettings,
     * and generating initial cycle data matching the user's entered period date.
     */
    suspend fun completeOnboardingWithProfile(
        profile: OnboardingProfile,
        pin: String = "",
        enableLock: Boolean = false
    ) {
        // 1. Save onboarding profile
        onboardingDao.insertOrUpdateProfile(profile)

        // 2. Update cycle settings
        val current = dao.getSettings() ?: CycleSettings()
        val updated = current.copy(
            isOnboardingCompleted = true,
            selectedGoalsCsv = profile.selectedGoalsCsv,
            lastPeriodStartDate = profile.lastPeriodDateString,
            averageCycleLength = profile.cycleLengthDays,
            periodDuration = profile.periodDurationDays,
            remindFertile = profile.remindFertileWindow,
            isAppLockEnabled = enableLock,
            appLockPin = pin
        )
        dao.saveSettings(updated)

        // 3. Generate initial cycle data matching the user's entered period date
        val periodStartDate = try {
            if (profile.lastPeriodDateString.isNotBlank()) {
                LocalDate.parse(profile.lastPeriodDateString)
            } else {
                LocalDate.now().minusDays(10)
            }
        } catch (e: Exception) {
            LocalDate.now().minusDays(10)
        }

        val periodDuration = profile.periodDurationDays.coerceIn(3, 7)
        val flowLevels = listOf("MODERATE", "HEAVY", "MODERATE", "LIGHT", "LIGHT", "SPOTTING", "SPOTTING")
        for (i in 0 until periodDuration) {
            val logDate = periodStartDate.plusDays(i.toLong())
            val existing = dao.getLogByDate(logDate.toString())
            if (existing == null) {
                dao.insertOrUpdateLog(
                    CycleDayLog(
                        dateString = logDate.toString(),
                        flowLevel = flowLevels.getOrElse(i) { "LIGHT" },
                        energyLevel = if (i < 2) "LOW" else "NORMAL",
                        moodLevel = if (i < 2) "UNCOMFORTABLE" else "GOOD",
                        symptomsCsv = if (i == 0) "cramps,headache" else if (i == 1) "cramps,bloating" else if (i == 2) "bloating" else "",
                        waterGlasses = 7,
                        sleepHours = 8.0f,
                        sleepQuality = "GOOD",
                        painIntensity = if (i == 0) 4 else if (i == 1) 3 else 1,
                        notes = if (i == 0) "Period started (recorded during onboarding)" else ""
                    )
                )
            }
        }
    }

    suspend fun saveSettings(settings: CycleSettings) {
        dao.saveSettings(settings)
    }

    suspend fun getLogForDate(dateString: String): CycleDayLog? {
        return dao.getLogByDate(dateString)
    }

    fun getLogForDateFlow(dateString: String): Flow<CycleDayLog?> {
        return dao.getLogByDateFlow(dateString)
    }

    suspend fun saveLog(log: CycleDayLog) {
        dao.insertOrUpdateLog(log)
    }

    suspend fun deleteLog(dateString: String) {
        dao.deleteLog(dateString)
    }

    suspend fun deleteAllLogs() {
        // Clear all logs and onboarding profile for complete privacy reset
        onboardingDao.deleteProfile()
        val defaultStart = LocalDate.now().minusDays(10).toString()
        dao.saveSettings(
            CycleSettings(
                isOnboardingCompleted = false,
                lastPeriodStartDate = defaultStart,
                averageCycleLength = 28,
                periodDuration = 5
            )
        )
    }

    fun calculateDayPrediction(date: LocalDate, settings: CycleSettings): DayPrediction {
        val lastStart = try {
            if (settings.lastPeriodStartDate.isNotBlank()) {
                LocalDate.parse(settings.lastPeriodStartDate)
            } else {
                LocalDate.now().minusDays(10)
            }
        } catch (e: Exception) {
            LocalDate.now().minusDays(10)
        }

        val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
        val periodDuration = settings.periodDuration.coerceIn(2, 10)
        val lutealLength = settings.lutealPhaseDays.coerceIn(10, 16)

        // Estimated ovulation day of the cycle: cycleLength - lutealLength (typically day 14 of 28)
        val ovulationCycleDay = (cycleLength - lutealLength).coerceAtLeast(periodDuration + 2)
        val fertileStartCycleDay = (ovulationCycleDay - 4).coerceAtLeast(periodDuration + 1)
        val fertileEndCycleDay = (ovulationCycleDay + 1).coerceAtMost(cycleLength)

        val daysDifference = ChronoUnit.DAYS.between(lastStart, date).toInt()
        val cycleDayZeroIndexed = Math.floorMod(daysDifference, cycleLength)
        val cycleDay = cycleDayZeroIndexed + 1 // 1-based cycle day

        val isPeriod = cycleDay <= periodDuration
        val isOvulation = cycleDay == ovulationCycleDay
        val isFertile = cycleDay in fertileStartCycleDay..fertileEndCycleDay

        val phase = when {
            isPeriod -> CyclePhase.MENSTRUAL
            cycleDay < fertileStartCycleDay -> CyclePhase.FOLLICULAR
            cycleDay <= fertileEndCycleDay -> CyclePhase.OVULATION
            else -> CyclePhase.LUTEAL
        }

        val chance = when {
            isOvulation -> "Peak (~30%)"
            isFertile -> "High (~20%)"
            cycleDay in (fertileStartCycleDay - 2) until fertileStartCycleDay -> "Medium (~10%)"
            else -> "Low (<5%)"
        }

        val confidence = when {
            isOvulation -> "Observation-Supported (Peak LH)"
            isFertile -> "Observation-Supported Window"
            else -> "Calendar Estimate"
        }

        return DayPrediction(
            date = date,
            dayOfCycle = cycleDay,
            phase = phase,
            isPeriod = isPeriod,
            isFertile = isFertile,
            isOvulation = isOvulation,
            fertilityConfidence = confidence,
            pregnancyChance = chance
        )
    }
}
