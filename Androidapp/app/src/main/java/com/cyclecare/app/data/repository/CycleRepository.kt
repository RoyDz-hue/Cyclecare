package com.cyclecare.app.data.repository

import android.content.Context
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.OwnerDataGate
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
import com.cyclecare.app.data.local.HealthSyncRevision
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import androidx.room.withTransaction
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
    val range: PeriodDateRange? = null,
    // Gentle confirmation text for unusual-but-real durations; null when the
    // pair is typical. Never promises the entry is saved.
    val warningMessage: String? = null
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
    private val consentGeneration: Long get() = lifecyclePreferences?.healthConsentGeneration ?: 0L

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
        val db = activeDatabase.value
        val selectedOnboardingDao = db.onboardingDao()
        journalMutation(db, "profile", HealthSyncPayloadCodec.profilePayload(profile)) {
            selectedOnboardingDao.insertOrUpdateProfile(profile)
        }
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
            journalMutation(selectedDatabase, "settings", HealthSyncPayloadCodec.settingsPayload(settings)) {
                dao.saveSettings(settings)
            }
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
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        selectedDatabase.withTransaction {
            val sync = selectedDatabase.healthSyncDao()
            val profilePayload = HealthSyncPayloadCodec.profilePayload(profile)
            val profilePrior = sync.revision(owner, "profile")
            onboardingDao.insertOrUpdateProfile(profile)
            sync.putRevision(HealthSyncRevision(owner, "profile", (profilePrior?.revision ?: 0L) + 1L, HealthSyncPayloadCodec.hash(profilePayload), false, System.currentTimeMillis(), profilePrior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), "profile", "UPSERT", profilePrior?.serverRevision ?: 0L, (profilePrior?.revision ?: 0L) + 1L, profilePayload, HealthSyncPayloadCodec.hash(profilePayload), capturedConsentGeneration))
            val settingsPayload = HealthSyncPayloadCodec.settingsPayload(updated)
            val settingsPrior = sync.revision(owner, "settings")
            dao.saveSettings(updated)
            sync.putRevision(HealthSyncRevision(owner, "settings", (settingsPrior?.revision ?: 0L) + 1L, HealthSyncPayloadCodec.hash(settingsPayload), false, System.currentTimeMillis(), settingsPrior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), "settings", "UPSERT", settingsPrior?.serverRevision ?: 0L, (settingsPrior?.revision ?: 0L) + 1L, settingsPayload, HealthSyncPayloadCodec.hash(settingsPayload), capturedConsentGeneration))
        }

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
        val db = activeDatabase.value
        val selectedDao = db.cycleDao()
        journalMutation(db, "settings", HealthSyncPayloadCodec.settingsPayload(settings)) {
            selectedDao.saveSettings(settings)
        }
    }

    private suspend fun journalMutation(
        db: CycleCareDatabase,
        recordId: String,
        payload: String,
        kind: String = "UPSERT",
        write: suspend () -> Unit
    ) {
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        db.withTransaction {
            val sync = db.healthSyncDao()
            val prior = sync.revision(owner, recordId)
            val revision = (prior?.revision ?: 0L) + 1L
            write()
            sync.putRevision(HealthSyncRevision(owner, recordId, revision, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), recordId, kind, prior?.serverRevision ?: 0L, revision, payload, HealthSyncPayloadCodec.hash(payload), capturedConsentGeneration))
        }
    }

    suspend fun getLogForDate(dateString: String): CycleDayLog? {
        val selectedDatabase = activeDatabase.value
        return selectedDatabase.cycleDao().getLogByDate(dateString)
    }

    suspend fun pendingSyncConflicts(): List<com.cyclecare.app.data.local.HealthSyncConflictEntity> =
        activeDatabase.value.healthSyncDao().conflicts(lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest")

    /** Keep the captured local snapshot, rebased on the reviewed server revision. */
    suspend fun retainLocalConflict(recordId: String) {
        val db = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        db.withTransaction {
            val sync = db.healthSyncDao()
            val conflict = sync.conflicts(owner).firstOrNull { it.recordId == recordId } ?: return@withTransaction
            if (conflict.remoteTombstone) throw IllegalStateException("keep_local_unavailable_server_tombstone")
            val current = sync.revision(owner, recordId)
            if (current == null || current.revision != conflict.localRevision || current.payloadHash != HealthSyncPayloadCodec.hash(conflict.localPayloadJson))
                return@withTransaction
            val localSeq = current.revision + 1L
            val payload = conflict.localPayloadJson
            sync.removeReviewedPending(owner, recordId, current.revision)
            sync.putRevision(current.copy(revision = localSeq, serverRevision = conflict.remoteRevision, updatedAt = System.currentTimeMillis()))
            sync.enqueue(HealthSyncOutboxEntry(owner, java.util.UUID.randomUUID().toString(), recordId, conflict.localKind, conflict.remoteRevision, localSeq, payload, HealthSyncPayloadCodec.hash(payload), capturedConsentGeneration))
            sync.resolveConflict(owner, recordId, System.currentTimeMillis(), "RETAIN_LOCAL")
        }
    }

    /** Apply the reviewed typed remote snapshot only if the local snapshot is still current. */
    suspend fun acceptRemoteConflict(recordId: String) {
        val db = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        db.withTransaction {
            val sync = db.healthSyncDao()
            val conflict = sync.conflicts(owner).firstOrNull { it.recordId == recordId } ?: return@withTransaction
            val current = sync.revision(owner, recordId)
            if (current != null && (current.revision != conflict.localRevision || current.payloadHash != HealthSyncPayloadCodec.hash(conflict.localPayloadJson))) return@withTransaction
            if (current == null && conflict.localRevision != 0L) return@withTransaction
            val remoteRevision = conflict.remoteRevision
            if (remoteRevision <= 0L) throw IllegalStateException("remote_record_missing_accept_unavailable")
            val selectedDao = db.cycleDao()
            val selectedOnboardingDao = db.onboardingDao()
            if (conflict.remoteTombstone) {
                if (conflict.remotePayloadJson != null) throw IllegalArgumentException("tombstone_payload")
                when (recordId) { "settings" -> selectedDao.saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(HealthSyncPayloadCodec.settingsPayload(CycleSettings()), recordId, selectedDao.getSettings())); "profile" -> selectedOnboardingDao.insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(OnboardingProfile()), recordId, selectedOnboardingDao.getProfile())); else -> selectedDao.deleteLog(recordId) }
                sync.putRevision(HealthSyncRevision(owner, recordId, (current?.revision ?: 0L) + 1L, null, true, System.currentTimeMillis(), remoteRevision))
            } else {
                val payload = conflict.remotePayloadJson ?: throw IllegalArgumentException("missing_remote_payload")
                when (recordId) {
                    "settings" -> selectedDao.saveSettings(HealthSyncPayloadCodec.parseSettingsPayload(payload, recordId, selectedDao.getSettings()))
                    "profile" -> selectedOnboardingDao.insertOrUpdateProfile(HealthSyncPayloadCodec.parseProfilePayload(payload, recordId, selectedOnboardingDao.getProfile()))
                    else -> selectedDao.insertOrUpdateLog(HealthSyncPayloadCodec.parseLogPayload(payload, recordId))
                }
                sync.putRevision(HealthSyncRevision(owner, recordId, (current?.revision ?: 0L) + 1L, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), remoteRevision))
            }
            sync.removeReviewedPending(owner, recordId, conflict.localRevision)
            sync.resolveConflict(owner, recordId, System.currentTimeMillis(), "ACCEPT_REMOTE")
        }
    }

    fun getLogForDateFlow(dateString: String): Flow<CycleDayLog?> {
        return activeDatabase.value.cycleDao().getLogByDateFlow(dateString)
    }

    suspend fun saveLog(log: CycleDayLog) {
        val selectedDatabase = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        val selectedDao = selectedDatabase.cycleDao()
        selectedDatabase.withTransaction {
            val sync = selectedDatabase.healthSyncDao()
            val recordId = log.dateString
            val prior = sync.revision(owner, recordId)
            val revision = (prior?.revision ?: 0L) + 1L
            val payload = HealthSyncPayloadCodec.logPayload(log)
            val operationId = java.util.UUID.randomUUID().toString()
            selectedDao.insertOrUpdateLog(log)
            sync.putRevision(HealthSyncRevision(owner, recordId, revision, HealthSyncPayloadCodec.hash(payload), false, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, operationId, recordId, "UPSERT", prior?.serverRevision ?: 0L, revision, payload, HealthSyncPayloadCodec.hash(payload), capturedConsentGeneration))
        }
    }

    suspend fun deleteLog(dateString: String) {
        val selectedDatabase = activeDatabase.value
        val owner = lifecycleIdentity?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        val capturedConsentGeneration = consentGeneration
        val selectedDao = selectedDatabase.cycleDao()
        selectedDatabase.withTransaction {
            val sync = selectedDatabase.healthSyncDao()
            val prior = sync.revision(owner, dateString)
            val revision = (prior?.revision ?: 0L) + 1L
            val operationId = java.util.UUID.randomUUID().toString()
            selectedDao.deleteLog(dateString)
            sync.putRevision(HealthSyncRevision(owner, dateString, revision, null, true, System.currentTimeMillis(), prior?.serverRevision ?: 0L))
            sync.enqueue(HealthSyncOutboxEntry(owner, operationId, dateString, "DELETE", prior?.serverRevision ?: 0L, revision, null, HealthSyncPayloadCodec.hash(null), capturedConsentGeneration))
        }
    }

    suspend fun deleteAllLogs() {
        val wipeOwner = lifecycleIdentity
        val selectedDatabase = activeDatabase.value
        val dao = selectedDatabase.cycleDao()
        val onboardingDao = selectedDatabase.onboardingDao()
        val capturedPreferences = lifecyclePreferences
        val capturedContext = lifecycleContext
        // Persist pause before deleting, so concurrent sync cannot resurrect the cleared namespace.
        capturedContext?.let { OwnerDataGate.markWiped(it, wipeOwner) }
        if (capturedPreferences != null && capturedPreferences.userId == wipeOwner) {
            capturedPreferences.isCloudSyncAllowed = false
            listOf("COHERE", "GEMINI", "OPENROUTER").forEach(capturedPreferences::revokeProcessorReceipt)
        }
        val owner = wipeOwner?.takeIf { it.isNotBlank() } ?: "legacy_guest"
        selectedDatabase.withTransaction {
            dao.deleteAllLogs()
            onboardingDao.deleteProfile()
            dao.deleteSettings()
            selectedDatabase.healthSyncDao().eraseOutbox(owner)
            selectedDatabase.healthSyncDao().eraseRevisions(owner)
            selectedDatabase.healthSyncDao().eraseCursor(owner)
            selectedDatabase.healthSyncDao().eraseConflicts(owner)
            dao.saveSettings(CycleSettings(isOnboardingCompleted = false, lastPeriodStartDate = "", averageCycleLength = 28, periodDuration = 5))
        }
        storedPeriodDateRange = null
        capturedContext?.let { OwnerDataGate.markWiped(it, wipeOwner) }
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

        /**
         * Delegates to the tested PeriodDateConsistency rules: only chronology
         * and future dates are hard errors; unusual durations stay valid and
         * carry a confirmation warning.
         */
        fun validatePeriodDatePair(
            period_start_date: LocalDate?,
            period_end_date: LocalDate?,
            isOngoing: Boolean = false,
            today: LocalDate = LocalDate.now()
        ): PeriodDateValidationResult {
            val check = com.cyclecare.app.domain.health.PeriodDateConsistency.validatePair(
                period_start_date, period_end_date, today, isOngoing
            )
            return PeriodDateValidationResult(
                isValid = check.isValid,
                errorMessage = check.errorMessage,
                durationDays = check.durationDays,
                range = if (check.isValid && period_start_date != null && period_end_date != null)
                    PeriodDateRange(period_start_date, period_end_date) else null,
                warningMessage = check.warningMessage
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
