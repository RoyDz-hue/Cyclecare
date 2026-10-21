package com.cyclecare.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.repository.PeriodDateRange
import com.cyclecare.app.data.repository.PeriodDateValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * UI State for the 5-step progressive CycleCare onboarding experience.
 */
data class OnboardingUiState(
    val step: Int = 1,
    val totalSteps: Int = 6,
    val selectedGoals: Set<String> = emptySet(),
    val selectedExactDate: LocalDate? = null,
    val isNotSureSelected: Boolean = false,
    val selectedApproximateChip: String? = null,
    val showDatePickerDialog: Boolean = false,
    val selectedPeriodEndDate: LocalDate? = null,
    val selectedPeriodDurationOption: String = "",
    val periodDurationDays: Int = 5,
    val isPeriodOngoing: Boolean = false,
    val showEndDatePickerDialog: Boolean = false,
    val selectedCycleOption: String = "",
    val cycleLengthDays: Int = 28,
    val remindFertileWindow: Boolean? = null,
    val isAgeVerified: Boolean = false,
    val isTermsAgreed: Boolean = false,
    val showTermsDialog: Boolean = false,
    val showPrivacyDialog: Boolean = false,
    val selectedTypicalSymptoms: Set<String> = emptySet(),
    val selectedTypicalEnergy: String = "",
    val selectedTypicalSleep: String = "",
    val hasCompletedDeepBaseline: Boolean = false,
    val isCloudSyncAllowed: Boolean = false,
    // Dynamic, progressively generated questions for steps 4-6 (AI-backed with
    // a local adaptive fallback) and the user's answers to them.
    val dynamicQuestions: Map<Int, List<com.cyclecare.app.domain.onboarding.OnboardingQuestion>> = emptyMap(),
    val dynamicAnswers: Map<String, Set<String>> = emptyMap(),
    val isLoadingDynamicQuestions: Boolean = false
) {
    val canProceedFromPrivacy: Boolean
        get() = isAgeVerified && isTermsAgreed

    val lastPeriodAnswered: Boolean
        get() = (selectedExactDate != null) || (selectedApproximateChip != null)

    val needsCycleFollowup: Boolean
        get() = selectedGoals.any { goal ->
            listOf("understand my cycle", "track my periods", "fertility")
                .any { goal.contains(it, ignoreCase = true) }
        }

    val needsPeriodFollowup: Boolean
        get() = needsCycleFollowup

    val needsSymptomFollowup: Boolean
        get() = selectedGoals.any { goal ->
            goal.contains("symptom", ignoreCase = true) ||
                goal.contains("changes in my body", ignoreCase = true)
        }

    val needsFertilityFollowup: Boolean
        get() = selectedGoals.any { it.contains("fertility", ignoreCase = true) }

    val hasCycleStartEstimate: Boolean
        get() = selectedExactDate != null || (selectedApproximateChip != null && !isUnknownDateChoice(selectedApproximateChip))

    val periodStartChoice: String
        get() = when {
            selectedExactDate != null -> "EXACT"
            !hasCycleStartEstimate -> "UNKNOWN"
            else -> "APPROXIMATE:${selectedApproximateChip.orEmpty()}"
        }

    val effectiveLastPeriodDate: LocalDate
        get() {
            if (selectedExactDate != null) {
                return selectedExactDate
            }
            val daysAgo = when (selectedApproximateChip) {
                "Today" -> 0
                "A few days ago" -> 4
                "About a week ago" -> 7
                "About two weeks ago" -> 14
                "It varies / I don’t know", "I don’t know" -> 0
                else -> 11
            }
            return LocalDate.now().minusDays(daysAgo.toLong())
        }

    val effectivePeriodEndDate: LocalDate
        get() {
            if (selectedPeriodEndDate != null) return selectedPeriodEndDate
            if (isPeriodOngoing) return LocalDate.now()
            return effectiveLastPeriodDate.plusDays((periodDurationDays - 1).toLong().coerceAtLeast(0))
        }

    val daysSinceLastPeriod: Int
        get() = ChronoUnit.DAYS.between(effectiveLastPeriodDate, LocalDate.now()).toInt().coerceAtLeast(0)

    val dayOfCycle: Int
        get() = if (hasCycleStartEstimate) (daysSinceLastPeriod % cycleLengthDays) + 1 else 0

    val isPeriodActiveToday: Boolean
        get() = hasCycleStartEstimate && (isPeriodOngoing || (dayOfCycle <= periodDurationDays))

    val currentPhaseName: String
        get() = when {
            !hasCycleStartEstimate -> "No estimate yet"
            isPeriodActiveToday -> "Menstrual Phase"
            dayOfCycle < (cycleLengthDays - 14) - 4 -> "Follicular Phase"
            dayOfCycle in ((cycleLengthDays - 14) - 4)..((cycleLengthDays - 14) + 1) -> "Estimated fertile window"
            else -> "Luteal Phase"
        }

    val periodDateRange: PeriodDateRange
        get() = PeriodDateRange(
            period_start_date = effectiveLastPeriodDate,
            period_end_date = effectivePeriodEndDate
        )

    val isPeriodDatePairValid: Boolean
        get() = CycleRepository.validatePeriodDates(effectiveLastPeriodDate, effectivePeriodEndDate)

    private fun isUnknownDateChoice(choice: String?): Boolean =
        choice.isNullOrBlank() || choice.equals("I don’t know", ignoreCase = true) ||
            choice.equals("It varies / I don’t know", ignoreCase = true)
}

/**
 * ViewModel responsible for managing onboarding progression and state across screens.
 */
class OnboardingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    // Optional AI question service; null in unit tests and pure-offline flows,
    // where the local adaptive bank is the single source.
    var questionService: com.cyclecare.app.data.supabase.OnboardingQuestionService? = null

    fun nextStep() {
        _uiState.update { current ->
            if (current.step < current.totalSteps) {
                current.copy(step = current.step + 1)
            } else {
                current
            }
        }
        loadDynamicQuestionsForCurrentStep()
    }

    /** Answers already collected for the dynamic questions, as the engine input. */
    private fun priorDynamicAnswers(): Map<String, Set<String>> = _uiState.value.dynamicAnswers

    /**
     * Progressive generation: questions for the current step are produced from
     * step-3 goals plus every answer given so far. The AI edge function is
     * tried first; the local adaptive bank guarantees a result instantly.
     */
    fun loadDynamicQuestionsForCurrentStep() {
        val step = _uiState.value.step
        if (step !in 4..6) return
        val goals = _uiState.value.selectedGoals
        val prior = priorDynamicAnswers()
        // Local bank renders immediately; AI refines asynchronously when available.
        val local = com.cyclecare.app.domain.onboarding.LocalAdaptiveOnboardingBank.questionsFor(step, goals, prior)
        _uiState.update { it.copy(dynamicQuestions = it.dynamicQuestions + (step to local)) }
        val service = questionService ?: return
        _uiState.update { it.copy(isLoadingDynamicQuestions = true) }
        viewModelScope.launch {
            val generated = service.generateQuestions(step, goals, prior)
            _uiState.update { current ->
                current.copy(
                    dynamicQuestions = if (!generated.isNullOrEmpty()) current.dynamicQuestions + (step to generated) else current.dynamicQuestions,
                    isLoadingDynamicQuestions = false
                )
            }
        }
    }

    fun toggleDynamicAnswer(questionId: String, option: String, multiSelect: Boolean) {
        _uiState.update { current ->
            val existing = current.dynamicAnswers[questionId] ?: emptySet()
            val next = when {
                !multiSelect -> setOf(option)
                existing.contains(option) -> existing - option
                else -> existing + option
            }
            current.copy(dynamicAnswers = current.dynamicAnswers + (questionId to next))
        }
    }

    fun previousStep() {
        _uiState.update { current ->
            if (current.step > 1) {
                current.copy(step = current.step - 1)
            } else {
                current
            }
        }
    }

    fun toggleGoal(goal: String) {
        _uiState.update { current ->
            val updated = if (current.selectedGoals.contains(goal)) {
                current.selectedGoals - goal
            } else {
                current.selectedGoals + goal
            }
            current.copy(selectedGoals = updated)
        }
    }

    fun setExactDate(date: LocalDate) {
        _uiState.update { current ->
            // If an exact end date was previously chosen and is now before the new start date,
            // clamp to new start date so the invariant end >= start is strictly maintained.
            val validEndDate = current.selectedPeriodEndDate?.let { end ->
                if (CycleRepository.validatePeriodDates(date, end)) end else date
            }
            val newDuration = if (validEndDate != null) {
                (ChronoUnit.DAYS.between(date, validEndDate) + 1).toInt().coerceIn(2, 10)
            } else {
                current.periodDurationDays
            }
            current.copy(
                selectedExactDate = date,
                selectedPeriodEndDate = validEndDate,
                periodDurationDays = newDuration,
                selectedApproximateChip = null,
                showDatePickerDialog = false
            )
        }
    }

    fun setNotSureSelected(notSure: Boolean) {
        _uiState.update { it.copy(isNotSureSelected = notSure) }
    }

    fun selectApproximateChip(chip: String) {
        _uiState.update { current ->
            current.copy(
                selectedApproximateChip = chip,
                selectedExactDate = null
            )
        }
    }

    fun setDatePickerDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showDatePickerDialog = visible) }
    }

    fun setExactEndDate(date: LocalDate) {
        _uiState.update { current ->
            val start = current.effectiveLastPeriodDate
            // Enforce end >= start using repository validation helper
            val validEnd = if (CycleRepository.validatePeriodDates(start, date)) {
                date
            } else {
                start // Clamps to at least start date
            }
            val duration = (ChronoUnit.DAYS.between(start, validEnd) + 1).toInt().coerceIn(2, 10)
            current.copy(
                selectedPeriodEndDate = validEnd,
                periodDurationDays = duration,
                selectedPeriodDurationOption = "$duration days",
                isPeriodOngoing = false,
                showEndDatePickerDialog = false
            )
        }
    }

    fun validatePeriodDates(start: LocalDate?, end: LocalDate?): Boolean {
        return CycleRepository.validatePeriodDates(start, end)
    }

    fun validatePeriodDatePair(start: LocalDate?, end: LocalDate?): PeriodDateValidationResult {
        return CycleRepository.validatePeriodDatePair(start, end)
    }

    fun selectPeriodDurationOption(option: String, days: Int, isOngoing: Boolean = false) {
        _uiState.update { current ->
            val endDate = if (isOngoing) LocalDate.now() else current.effectiveLastPeriodDate.plusDays((days - 1).toLong().coerceAtLeast(0))
            current.copy(
                selectedPeriodDurationOption = option,
                periodDurationDays = days,
                isPeriodOngoing = isOngoing,
                selectedPeriodEndDate = if (!isOngoing) endDate else null
            )
        }
    }

    fun setEndDatePickerDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showEndDatePickerDialog = visible) }
    }

    fun selectCycleOption(option: String, days: Int) {
        _uiState.update { current ->
            current.copy(
                selectedCycleOption = option,
                cycleLengthDays = days
            )
        }
    }

    fun setRemindFertile(remind: Boolean) {
        _uiState.update { it.copy(remindFertileWindow = remind) }
    }

    fun setAgeVerified(verified: Boolean) {
        _uiState.update { it.copy(isAgeVerified = verified) }
    }

    fun setTermsAgreed(agreed: Boolean) {
        _uiState.update { it.copy(isTermsAgreed = agreed) }
    }

    fun setCloudSyncAllowed(allowed: Boolean) {
        _uiState.update { it.copy(isCloudSyncAllowed = allowed) }
    }

    fun setShowTermsDialog(show: Boolean) {
        _uiState.update { it.copy(showTermsDialog = show) }
    }

    fun setShowPrivacyDialog(show: Boolean) {
        _uiState.update { it.copy(showPrivacyDialog = show) }
    }

    fun toggleTypicalSymptom(symptom: String) {
        _uiState.update { current ->
            val updated = if (current.selectedTypicalSymptoms.contains(symptom)) {
                current.selectedTypicalSymptoms - symptom
            } else {
                current.selectedTypicalSymptoms + symptom
            }
            current.copy(selectedTypicalSymptoms = updated, hasCompletedDeepBaseline = true)
        }
    }

    fun selectTypicalEnergy(energy: String) {
        _uiState.update { it.copy(selectedTypicalEnergy = energy, hasCompletedDeepBaseline = true) }
    }

    fun selectTypicalSleep(sleep: String) {
        _uiState.update { it.copy(selectedTypicalSleep = sleep, hasCompletedDeepBaseline = true) }
    }

    fun setCompletedDeepBaseline(completed: Boolean) {
        _uiState.update { it.copy(hasCompletedDeepBaseline = completed) }
    }
}

