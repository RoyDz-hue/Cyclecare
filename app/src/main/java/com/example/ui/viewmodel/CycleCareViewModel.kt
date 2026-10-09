package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.local.OnboardingProfile
import com.example.data.model.DayPrediction
import com.example.data.model.HealthPattern
import com.example.data.model.LearnedObservation
import com.example.data.model.ParsedLogInterpretation
import com.example.data.model.SafetyEvaluation
import com.example.data.model.TodayAttentionItem
import com.example.data.model.UserTonePreference
import com.example.data.repository.CycleRepository
import com.example.domain.ai.CareAiService
import com.example.domain.ai.CareResponse
import com.example.domain.attention.AttentionEngine
import com.example.domain.learning.LearningEngine
import com.example.domain.parser.NaturalLanguageLogParser
import com.example.domain.pattern.BaselineMetrics
import com.example.domain.pattern.PatternEngine
import com.example.domain.safety.SafetyEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class AppNavTab(val title: String) {
    HOME("Home"),
    CYCLE("Cycle"),
    TRACK("Track"),
    INSIGHTS("Insights"),
    CARE("Care")
}

data class CycleUiState(
    val currentDate: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val activeTab: AppNavTab = AppNavTab.HOME,
    val settings: CycleSettings = CycleSettings(),
    val todayPrediction: DayPrediction? = null,
    val selectedDatePrediction: DayPrediction? = null,
    val daysUntilNextPeriod: Int = 18,
    val daysUntilOvulation: Int = 4,
    val isPeriodActiveToday: Boolean = false,
    val safetyEvaluation: SafetyEvaluation = SafetyEngine.evaluate(null),
    val baselineMetrics: BaselineMetrics = BaselineMetrics(28.0, 28, 26, 30, 2, true, 4),
    val patterns: List<HealthPattern> = emptyList(),
    val todayAttentionItem: TodayAttentionItem? = null,
    val learnedObservations: List<LearnedObservation> = emptyList(),
    val userTonePreference: UserTonePreference = UserTonePreference.WARM,
    val doctorQuestions: List<String> = emptyList(),
    val careResponse: CareResponse? = null,
    val isCareLoading: Boolean = false,
    val isAppLocked: Boolean = false,
    val showOnboarding: Boolean = true,
    val isSomethingDifferentSheetOpen: Boolean = false,
    val currentInterpretation: ParsedLogInterpretation? = null
)

class CycleCareViewModel(private val repository: CycleRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CycleUiState())
    val uiState: StateFlow<CycleUiState> = _uiState.asStateFlow()

    val logsMap: StateFlow<Map<String, CycleDayLog>> = repository.allLogs
        .map { logs -> logs.associateBy { it.dateString } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            val settings = repository.getSettings()
            val profile = repository.getOnboardingProfile()
            val isLocked = settings.isAppLockEnabled && settings.appLockPin.isNotBlank()
            val showOnboarding = (profile == null || !settings.isOnboardingCompleted)

            _uiState.value = _uiState.value.copy(
                isAppLocked = isLocked,
                showOnboarding = showOnboarding,
                settings = settings
            )

            updateCalculations(settings, LocalDate.now(), LocalDate.now())
        }

        viewModelScope.launch {
            repository.settingsFlow.collect { newSettings ->
                if (newSettings != null) {
                    updateCalculations(newSettings, _uiState.value.currentDate, _uiState.value.selectedDate)
                }
            }
        }
    }

    private fun updateCalculations(settings: CycleSettings, today: LocalDate, selectedDate: LocalDate) {
        val todayPrediction = repository.calculateDayPrediction(today, settings)
        val selectedPrediction = repository.calculateDayPrediction(selectedDate, settings)

        val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
        val remainingInCycle = (cycleLength - todayPrediction.dayOfCycle).coerceAtLeast(0)
        val daysUntilPeriod = if (todayPrediction.isPeriod) 0 else remainingInCycle

        val ovulationCycleDay = (cycleLength - settings.lutealPhaseDays).coerceAtLeast(settings.periodDuration + 2)
        val daysUntilOvu = if (todayPrediction.dayOfCycle <= ovulationCycleDay) {
            ovulationCycleDay - todayPrediction.dayOfCycle
        } else {
            cycleLength - todayPrediction.dayOfCycle + ovulationCycleDay
        }

        val allLogsList = logsMap.value.values.toList()
        val currentLog = logsMap.value[today.toString()]
        val safety = SafetyEngine.evaluate(currentLog)
        val baseline = PatternEngine.computeBaseline(settings, allLogsList)
        val detectedPatterns = PatternEngine.detectPatterns(settings, allLogsList)
        val attentionItem = AttentionEngine.evaluateTodayPriority(todayPrediction, safety, settings, allLogsList)
        val learnedObs = LearningEngine.compileLearnedObservations(settings, allLogsList)
        val doctorQ = LearningEngine.generateDoctorSummary(settings, allLogsList)

        _uiState.value = _uiState.value.copy(
            currentDate = today,
            selectedDate = selectedDate,
            settings = settings,
            todayPrediction = todayPrediction,
            selectedDatePrediction = selectedPrediction,
            daysUntilNextPeriod = daysUntilPeriod,
            daysUntilOvulation = daysUntilOvu,
            isPeriodActiveToday = todayPrediction.isPeriod,
            safetyEvaluation = safety,
            baselineMetrics = baseline,
            patterns = detectedPatterns,
            todayAttentionItem = attentionItem,
            learnedObservations = learnedObs,
            doctorQuestions = doctorQ
        )
    }

    fun selectTab(tab: AppNavTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun selectDate(date: LocalDate) {
        val currentSettings = _uiState.value.settings
        val selectedPrediction = repository.calculateDayPrediction(date, currentSettings)
        _uiState.value = _uiState.value.copy(
            selectedDate = date,
            selectedDatePrediction = selectedPrediction
        )
    }

    fun openTrackForDate(date: LocalDate) {
        selectDate(date)
        selectTab(AppNavTab.TRACK)
    }

    fun logPeriodStart(date: LocalDate) {
        viewModelScope.launch {
            val updatedSettings = _uiState.value.settings.copy(
                lastPeriodStartDate = date.toString()
            )
            repository.saveSettings(updatedSettings)

            val existing = repository.getLogForDate(date.toString())
            val newLog = (existing ?: CycleDayLog(dateString = date.toString())).copy(
                flowLevel = "MODERATE"
            )
            repository.saveLog(newLog)
            updateCalculations(updatedSettings, _uiState.value.currentDate, _uiState.value.selectedDate)
        }
    }

    /**
     * 10-second micro check-in directly from the Home screen.
     */
    fun quickCheckIn(mood: String, energy: String) {
        viewModelScope.launch {
            val todayStr = _uiState.value.currentDate.toString()
            val existing = repository.getLogForDate(todayStr) ?: CycleDayLog(dateString = todayStr)
            val updated = existing.copy(
                moodLevel = mood,
                energyLevel = energy
            )
            repository.saveLog(updated)
            updateCalculations(_uiState.value.settings, _uiState.value.currentDate, _uiState.value.selectedDate)
        }
    }

    /**
     * Opens the "Something feels different" hybrid logger bottom sheet.
     */
    fun openSomethingDifferentSheet() {
        _uiState.value = _uiState.value.copy(
            isSomethingDifferentSheetOpen = true,
            currentInterpretation = null
        )
    }

    fun closeSomethingDifferentSheet() {
        _uiState.value = _uiState.value.copy(
            isSomethingDifferentSheetOpen = false,
            currentInterpretation = null
        )
    }

    /**
     * Parses freeform natural language text into a clean interpretation preview card.
     */
    fun parseNaturalLanguageInput(rawText: String) {
        val interpretation = NaturalLanguageLogParser.parse(rawText)
        _uiState.value = _uiState.value.copy(currentInterpretation = interpretation)
    }

    /**
     * 1-tap confirmation of interpreted items.
     */
    fun confirmParsedLog(interpretation: ParsedLogInterpretation) {
        viewModelScope.launch {
            val todayStr = _uiState.value.currentDate.toString()
            val existing = repository.getLogForDate(todayStr) ?: CycleDayLog(dateString = todayStr)

            val combinedSymptoms = (existing.symptomsCsv.split(",") + interpretation.symptomsResult)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(",")

            val updated = existing.copy(
                flowLevel = interpretation.flowLevelResult ?: existing.flowLevel,
                energyLevel = interpretation.energyResult ?: existing.energyLevel,
                moodLevel = interpretation.moodResult ?: existing.moodLevel,
                symptomsCsv = combinedSymptoms,
                painIntensity = if (interpretation.painIntensityResult > 0) interpretation.painIntensityResult else existing.painIntensity,
                notes = if (existing.notes.isBlank()) interpretation.rawText else "${existing.notes} | ${interpretation.rawText}"
            )

            repository.saveLog(updated)
            closeSomethingDifferentSheet()
            updateCalculations(_uiState.value.settings, _uiState.value.currentDate, _uiState.value.selectedDate)
        }
    }

    fun updateTonePreference(tone: UserTonePreference) {
        _uiState.value = _uiState.value.copy(userTonePreference = tone)
    }

    fun saveDayLog(log: CycleDayLog) {
        viewModelScope.launch {
            repository.saveLog(log)

            if (log.flowLevel !in listOf("NONE", "SPOTTING")) {
                val logDate = LocalDate.parse(log.dateString)
                val currentStart = try { LocalDate.parse(_uiState.value.settings.lastPeriodStartDate) } catch (e: Exception) { null }
                if (currentStart == null || logDate.isAfter(currentStart.plusDays(15))) {
                    val updatedSettings = _uiState.value.settings.copy(
                        lastPeriodStartDate = log.dateString
                    )
                    repository.saveSettings(updatedSettings)
                }
            }

            val safety = SafetyEngine.evaluate(log)
            _uiState.value = _uiState.value.copy(safetyEvaluation = safety)
        }
    }

    fun deleteDayLog(dateString: String) {
        viewModelScope.launch {
            repository.deleteLog(dateString)
        }
    }

    fun updateSettings(newSettings: CycleSettings) {
        viewModelScope.launch {
            repository.saveSettings(newSettings)
            updateCalculations(newSettings, _uiState.value.currentDate, _uiState.value.selectedDate)
        }
    }

    fun consultCare(question: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCareLoading = true)
            val todayLog = logsMap.value[_uiState.value.currentDate.toString()]
            val response = CareAiService.consult(
                userQuestion = question,
                recentLog = todayLog,
                settings = _uiState.value.settings
            )
            _uiState.value = _uiState.value.copy(
                careResponse = response,
                isCareLoading = false
            )
        }
    }

    fun unlockApp(pin: String): Boolean {
        if (pin == _uiState.value.settings.appLockPin || _uiState.value.settings.appLockPin.isBlank()) {
            _uiState.value = _uiState.value.copy(isAppLocked = false)
            return true
        }
        return false
    }

    fun lockApp() {
        if (_uiState.value.settings.isAppLockEnabled) {
            _uiState.value = _uiState.value.copy(isAppLocked = true)
        }
    }

    fun completeOnboarding(
        goals: List<String>,
        lastPeriodDate: LocalDate,
        cycleLength: Int,
        periodDuration: Int,
        pin: String,
        enableLock: Boolean,
        remindFertile: Boolean = true
    ) {
        viewModelScope.launch {
            val profile = OnboardingProfile(
                id = 1,
                isCompleted = true,
                completedAtTimestamp = System.currentTimeMillis(),
                isAgeVerified = true,
                isTermsAgreed = true,
                accountMode = "PRIVATE",
                selectedGoalsCsv = goals.joinToString(","),
                lastPeriodDateString = lastPeriodDate.toString(),
                approximatePeriodChoice = "EXACT",
                cycleLengthOption = "$cycleLength days",
                cycleLengthDays = cycleLength,
                periodDurationDays = periodDuration,
                remindFertileWindow = remindFertile
            )
            repository.completeOnboardingWithProfile(
                profile = profile,
                pin = pin,
                enableLock = enableLock
            )
            val updated = repository.getSettings()
            _uiState.value = _uiState.value.copy(
                showOnboarding = false,
                isAppLocked = false,
                settings = updated
            )
            updateCalculations(updated, LocalDate.now(), LocalDate.now())
        }
    }

    fun openOnboarding() {
        _uiState.value = _uiState.value.copy(showOnboarding = true)
    }

    fun wipeAllData() {
        viewModelScope.launch {
            repository.deleteAllLogs()
            val resetSettings = repository.getSettings()
            _uiState.value = _uiState.value.copy(
                showOnboarding = true,
                settings = resetSettings
            )
            updateCalculations(resetSettings, LocalDate.now(), LocalDate.now())
        }
    }

    fun getPredictionForDate(date: LocalDate): DayPrediction {
        return repository.calculateDayPrediction(date, _uiState.value.settings)
    }

    class Factory(private val repository: CycleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CycleCareViewModel(repository) as T
        }
    }
}
