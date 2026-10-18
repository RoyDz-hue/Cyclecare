package com.example.data.repository

import android.content.Context
import com.example.data.local.CycleCareDatabase
import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.local.OnboardingProfile
import com.example.data.local.OwnerDataGate
import com.example.data.model.CyclePhase
import com.example.data.model.DayPrediction
import com.example.security.AuthPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Data structure storing 'period_start_date' and 'period_end_date'.
 * Used across the onboarding step logic and cycle algorithms to validate and track period boundaries.
 */
data class PeriodDateRange(
    val period_start_date: LocalDate,
    val period_end_date: LocalDate
) {
    val startDate: LocalDate get() = period_start_date
    val endDate: LocalDate get() = period_end_date

    val startDateString: String get() = period_start_date.toString()
    val endDateString: String get() = period_end_date.toString()

    /**
     * Calculates the total bleeding/period duration in days inclusive.
     * Always at least 1 day.
     */
    val durationDays: Int
        get() = (ChronoUnit.DAYS.between(period_start_date, period_end_date) + 1).toInt().coerceAtLeast(1)

    /**
     * Validates whether period_end_date is on or after period_start_date (end >= start).
     */
    fun isValid(): Boolean = !period_end_date.isBefore(period_start_date)

    companion object {
        fun fromStrings(period_start_date: String, period_end_date: String): PeriodDateRange? {
            return try {
                val start = LocalDate.parse(period_start_date)
                val end = LocalDate.parse(period_end_date)
                PeriodDateRange(start, end)
            } catch (e: Exception) {
                null
            }
        }
    }
}

typealias PeriodDates = PeriodDateRange

/**
 * Result of validating a period start and end date pair for the onboarding step logic.
 */
data class PeriodDateValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val durationDays: Int = 0,
    val range: PeriodDateRange? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CycleRepository(database: CycleCareDatabase) {
    private val activeDatabase = MutableStateFlow(database)
    private var lifecyclePreferences: AuthPreferences? = null
    private var lifecycleContext: Context? = null
    private var lifecycleIdentity: String? = null

    /** Preferred account-bound construction; signed-in users never fall back to legacy storage. */
    constructor(context: Context, authPreferences: AuthPreferences) : this(
        CycleCareDatabase.getDatabase(context.applicationContext, authPreferences.userId)
    ) {
        lifecycleContext = context.applicationContext
        lifecyclePreferences = authPreferences
        lifecycleIdentity = authPreferences.userId
    }
    private val dao get() = activeDatabase.value.cycleDao()
    private val onboardingDao get() = activeDatabase.value.onboardingDao()

    fun activateCurrentOwner() {
        val context = lifecycleContext ?: return
        val preferences = lifecyclePreferences ?: return
        val owner = preferences.userId
        if (lifecycleIdentity == owner) return
        lifecycleIdentity = owner
        storedPeriodDateRange = null
        activeDatabase.value = CycleCareDatabase.getDatabase(context, owner)
    }

    internal fun activateDatabaseForTest(database: CycleCareDatabase, owner: String?) {
        lifecycleIdentity = owner
        storedPeriodDateRange = null
        activeDatabase.value = database
    }

    // In-memory cache for validated period date boundaries
    private var storedPeriodDateRange: PeriodDateRange? = null

    data class ScopedLogSnapshot(val database: CycleCareDatabase, val logs: List<CycleDayLog>)
    val scopedLogs: Flow<ScopedLogSnapshot> = activeDatabase.flatMapLatest { db -> flow {
        emit(ScopedLogSnapshot(db, emptyList()))
        db.cycleDao().getAllLogs().collect { emit(ScopedLogSnapshot(db, it)) }
    } }
    fun isCurrentSnapshot(snapshot: ScopedLogSnapshot): Boolean = snapshot.database === activeDatabase.value

    val allLogs: Flow<List<CycleDayLog>> = activeDatabase.flatMapLatest { db -> flow {
        emit(emptyList())
        emitAll(db.cycleDao().getAllLogs())
    } }
    val settingsFlow: Flow<CycleSettings?> = activeDatabase.flatMapLatest { it.cycleDao().getSettingsFlow() }
    val onboardingProfileFlow: Flow<OnboardingProfile?> = activeDatabase.flatMapLatest { it.onboardingDao().getProfileFlow() }

    fun storePeriodDates(period_start_date: LocalDate, period_end_date: LocalDate): Boolean {
        if (!validatePeriodDates(period_start_date, period_end_date)) {
            return false
        }
        storedPeriodDateRange = PeriodDateRange(period_start_date, period_end_date)
        return true
    }

    fun storePeriodDates(range: PeriodDateRange): Boolean {
        return storePeriodDates(range.period_start_date, range.period_end_date)
    }

    fun getStoredPeriodDates(): PeriodDateRange? = storedPeriodDateRange

    fun validatePeriodDates(
        period_start_date: LocalDate?,
        period_end_date: LocalDate?
    ): Boolean = Companion.validatePeriodDates(period_start_date, period_end_date)

    fun validatePeriodDates(range: PeriodDateRange?): Boolean =
        Companion.validatePeriodDates(range)

    fun validatePeriodDatePair(
        period_start_date: LocalDate?,
        period_end_date: LocalDate?
    ): PeriodDateValidationResult =
        Companion.validatePeriodDatePair(period_start_date, period_end_date)

    suspend fun getOnboardingProfile(): OnboardingProfile? = onboardingDao.getProfile()

    suspend fun saveOnboardingProfile(profile: OnboardingProfile) {
        onboardingDao.insertOrUpdateProfile(profile)
    }

    suspend fun getSettings(): CycleSettings {
        val selectedDatabase = activeDatabase.value
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        var settings = dao.getSettings()
        val profile = onboardingDao.getProfile()
        if (settings == null) {
            settings = CycleSettings(
                isOnboardingCompleted = profile?.isCompleted == true,
                selectedGoalsCsv = profile?.selectedGoalsCsv.orEmpty(),
                lastPeriodStartDate = profile?.lastPeriodDateString.orEmpty(),
                averageCycleLength = profile?.cycleLengthDays ?: 28,
                periodDuration = profile?.periodDurationDays ?: 5,
                lutealPhaseDays = 14,
                remindFertile = profile?.remindFertileWindow ?: false
            )
            dao.saveSettings(settings)
        }
        return settings
    }

    suspend fun completeOnboardingWithProfile(
        profile: OnboardingProfile,
        pin: String = "",
        enableLock: Boolean = false
    ) {
        val selectedDatabase = activeDatabase.value
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        onboardingDao.insertOrUpdateProfile(profile)

        val current = dao.getSettings() ?: CycleSettings()
        val updated = current.copy(
            isOnboardingCompleted = true,
            selectedGoalsCsv = profile.selectedGoalsCsv,
            lastPeriodStartDate = profile.lastPeriodDateString,
            averageCycleLength = profile.cycleLengthDays,
            periodDuration = profile.periodDurationDays,
            remindFertile = profile.remindFertileWindow,
            isAppLockEnabled = enableLock
        )
        dao.saveSettings(updated)

        val periodStartDate = profile.lastPeriodDateString
            .takeIf(String::isNotBlank)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (periodStartDate == null) {
            storedPeriodDateRange = null
        } else {
            val periodDuration = profile.periodDurationDays.coerceIn(2, 10)
            val periodEndDate = periodStartDate.plusDays((periodDuration - 1).toLong())
            storePeriodDates(periodStartDate, periodEndDate)
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
        val wipeOwner = lifecycleIdentity
        val selectedDatabase = activeDatabase.value
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        // Persist pause before deleting, so concurrent sync cannot resurrect the cleared namespace.
        lifecycleContext?.let { OwnerDataGate.markWiped(it, wipeOwner) }
        dao.deleteAllLogs()
        onboardingDao.deleteProfile()
        storedPeriodDateRange = null
        dao.saveSettings(
            CycleSettings(
                isOnboardingCompleted = false,
                lastPeriodStartDate = "",
                averageCycleLength = 28,
                periodDuration = 5
            )
        )
        lifecycleContext?.let { OwnerDataGate.markWiped(it, wipeOwner) }
    }

    fun calculateDayPrediction(date: LocalDate, settings: CycleSettings): DayPrediction? {
        val lastStart = settings.lastPeriodStartDate
            .takeIf(String::isNotBlank)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return null

        val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
        val periodDuration = settings.periodDuration.coerceIn(2, 10)
        val lutealLength = settings.lutealPhaseDays.coerceIn(10, 16)

        val ovulationCycleDay = (cycleLength - lutealLength).coerceAtLeast(periodDuration + 2)
        val fertileStartCycleDay = (ovulationCycleDay - 4).coerceAtLeast(periodDuration + 1)
        val fertileEndCycleDay = (ovulationCycleDay + 1).coerceAtMost(cycleLength)

        val daysDifference = ChronoUnit.DAYS.between(lastStart, date).toInt()
        val cycleDayZeroIndexed = Math.floorMod(daysDifference, cycleLength)
        val cycleDay = cycleDayZeroIndexed + 1

        val isPeriod = cycleDay <= periodDuration
        val isOvulation = cycleDay == ovulationCycleDay
        val isFertile = cycleDay in fertileStartCycleDay..fertileEndCycleDay

        val phase = when {
            isPeriod -> CyclePhase.MENSTRUAL
            cycleDay < fertileStartCycleDay -> CyclePhase.FOLLICULAR
            cycleDay <= fertileEndCycleDay -> CyclePhase.OVULATION
            else -> CyclePhase.LUTEAL
        }

        return DayPrediction(
            date = date,
            dayOfCycle = cycleDay,
            phase = phase,
            isPeriod = isPeriod,
            isFertile = isFertile,
            isOvulation = isOvulation,
            fertilityConfidence = "Calendar Estimate",
            pregnancyChance = "Not calculated from calendar dates"
        )
    }

    companion object {
        fun validatePeriodDates(
            period_start_date: LocalDate?,
            period_end_date: LocalDate?
        ): Boolean {
            if (period_start_date == null || period_end_date == null) return false
            return !period_end_date.isBefore(period_start_date)
        }

        fun validatePeriodDates(range: PeriodDateRange?): Boolean {
            if (range == null) return false
            return validatePeriodDates(range.period_start_date, range.period_end_date)
        }

        fun validatePeriodDatePair(
            period_start_date: LocalDate?,
            period_end_date: LocalDate?
        ): PeriodDateValidationResult {
            if (period_start_date == null) {
                return PeriodDateValidationResult(
                    isValid = false,
                    errorMessage = "Period start date is required",
                    durationDays = 0
                )
            }
            if (period_end_date == null) {
                return PeriodDateValidationResult(
                    isValid = false,
                    errorMessage = "Period end date is required",
                    durationDays = 0
                )
            }
            if (period_end_date.isBefore(period_start_date)) {
                return PeriodDateValidationResult(
                    isValid = false,
                    errorMessage = "Period end date cannot be earlier than start date",
                    durationDays = 0
                )
            }
            val durationDays = (ChronoUnit.DAYS.between(period_start_date, period_end_date) + 1).toInt()
            return PeriodDateValidationResult(
                isValid = true,
                errorMessage = null,
                durationDays = durationDays,
                range = PeriodDateRange(period_start_date, period_end_date)
            )
        }

        fun validatePeriodDateStrings(
            period_start_date: String?,
            period_end_date: String?
        ): Boolean {
            if (period_start_date.isNullOrBlank() || period_end_date.isNullOrBlank()) return false
            return try {
                val start = LocalDate.parse(period_start_date)
                val end = LocalDate.parse(period_end_date)
                validatePeriodDates(start, end)
            } catch (e: Exception) {
                false
            }
        }
    }
}
