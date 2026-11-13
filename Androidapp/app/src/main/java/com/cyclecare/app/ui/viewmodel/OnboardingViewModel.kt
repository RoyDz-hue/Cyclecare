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
import kotlinx.coroutines.launch
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
    // Unusual-duration confirmation: message shown in the popup, and whether
    // the user has explicitly confirmed the entered end date is correct.
    val periodDateDialogMessage: String? = null,
    val periodEndDateConfirmed: Boolean = false,
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
    val canProceedFromPrivacy:Boolean get()=OnboardingDerived.bool("privacy",this)
    val lastPeriodAnswered:Boolean get()=OnboardingDerived.bool("answered",this)
    val needsCycleFollowup:Boolean get()=OnboardingDerived.any { selectedGoals.map { goal -> listOf("understand my cycle","track my periods","fertility").any{goal.contains(it,ignoreCase=true)} } }
    val needsPeriodFollowup:Boolean get()=needsCycleFollowup
    val needsSymptomFollowup:Boolean get()=OnboardingDerived.any { selectedGoals.map { goal -> goal.contains("symptom",ignoreCase=true)||goal.contains("changes in my body",ignoreCase=true) } }
    val needsFertilityFollowup:Boolean get()=OnboardingDerived.any { selectedGoals.map {it.contains("fertility",ignoreCase=true)} }
    val hasCycleStartEstimate:Boolean get()=OnboardingDerived.bool("estimate",this)
    val periodStartChoice:String get()=OnboardingDerived.choice(this)
    val effectiveLastPeriodDate:LocalDate get(){if(selectedExactDate!=null)return selectedExactDate;val daysAgo=OnboardingDerived.offset(selectedApproximateChip);return LocalDate.now().minusDays(daysAgo.toLong())}
    val effectivePeriodEndDate:LocalDate get(){if(selectedPeriodEndDate!=null)return selectedPeriodEndDate;if(isPeriodOngoing)return LocalDate.now();return effectiveLastPeriodDate.plusDays(OnboardingDerived.endOffset(periodDurationDays).toLong())}
    val daysSinceLastPeriod:Int get()=OnboardingDerived.days { ChronoUnit.DAYS.between(effectiveLastPeriodDate,LocalDate.now()) }
    val dayOfCycle:Int get()=OnboardingDerived.day(this)
    val isPeriodActiveToday:Boolean get()=OnboardingDerived.bool("active",this)
    val currentPhaseName:String get()=OnboardingDerived.phase(this)

    internal val canProceedFromPrivacyKotlin: Boolean
        get() = isAgeVerified && isTermsAgreed

    internal val lastPeriodAnsweredKotlin: Boolean
        get() = (selectedExactDate != null) || (selectedApproximateChip != null)

    internal val needsCycleFollowupKotlin: Boolean
        get() = selectedGoals.any { goal ->
            listOf("understand my cycle", "track my periods", "fertility")
                .any { goal.contains(it, ignoreCase = true) }
        }

    internal val needsPeriodFollowupKotlin: Boolean
        get() = needsCycleFollowupKotlin

    internal val needsSymptomFollowupKotlin: Boolean
        get() = selectedGoals.any { goal ->
            goal.contains("symptom", ignoreCase = true) ||
                goal.contains("changes in my body", ignoreCase = true)
        }

    internal val needsFertilityFollowupKotlin: Boolean
        get() = selectedGoals.any { it.contains("fertility", ignoreCase = true) }

    internal val hasCycleStartEstimateKotlin: Boolean
        get() = selectedExactDate != null || (selectedApproximateChip != null && !isUnknownDateChoice(selectedApproximateChip))

    internal val periodStartChoiceKotlin: String
        get() = when {
            selectedExactDate != null -> "EXACT"
            !hasCycleStartEstimateKotlin -> "UNKNOWN"
            else -> "APPROXIMATE:${selectedApproximateChip.orEmpty()}"
        }

    internal val effectiveLastPeriodDateKotlin: LocalDate
        get() {
            if (selectedExactDate != null) {
                return selectedExactDate
            }
            // COMPILE RESTORE (2026-11-13): `internal` on a local val is
            // illegal Kotlin (replay artifact from the Nov 6-7 lineage,
            // also present on master). Modifier dropped; behavior unchanged.
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

    internal val effectivePeriodEndDateKotlin: LocalDate
        get() {
            if (selectedPeriodEndDate != null) return selectedPeriodEndDate
            if (isPeriodOngoing) return LocalDate.now()
            return effectiveLastPeriodDateKotlin.plusDays((periodDurationDays - 1).toLong().coerceAtLeast(0))
        }

    internal val daysSinceLastPeriodKotlin: Int
        get() = ChronoUnit.DAYS.between(effectiveLastPeriodDateKotlin, LocalDate.now()).toInt().coerceAtLeast(0)

    internal val dayOfCycleKotlin: Int
        get() = if (hasCycleStartEstimateKotlin) (daysSinceLastPeriodKotlin % cycleLengthDays) + 1 else 0

    internal val isPeriodActiveTodayKotlin: Boolean
        get() = hasCycleStartEstimateKotlin && (isPeriodOngoing || (dayOfCycleKotlin <= periodDurationDays))

    internal val currentPhaseNameKotlin: String
        get() = when {
            !hasCycleStartEstimateKotlin -> "No estimate yet"
            isPeriodActiveTodayKotlin -> "Menstrual Phase"
            dayOfCycleKotlin < (cycleLengthDays - 14) - 4 -> "Follicular Phase"
            dayOfCycleKotlin in ((cycleLengthDays - 14) - 4)..((cycleLengthDays - 14) + 1) -> "Estimated fertile window"
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

    private fun reduce(event:OnboardingStateReducer.Event) {
        _uiState.update { OnboardingStateReducer.reduce(it,event) }
    }

    fun nextStep() {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.NEXT))
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
        _uiState.update { OnboardingQuestionReducer.reduce(it,step,local,OnboardingQuestionReducer.Action.LOCAL) }
        val service = questionService ?: return
        _uiState.update { OnboardingQuestionReducer.reduce(it,step,null,OnboardingQuestionReducer.Action.START) }
        viewModelScope.launch {
            val generated = service.generateQuestions(step, goals, prior)
            _uiState.update { current ->
                OnboardingQuestionReducer.reduce(current,step,generated,OnboardingQuestionReducer.Action.GENERATED)
            }
        }
    }

    fun toggleDynamicAnswer(questionId: String, option: String, multiSelect: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.ANSWER,text=questionId,other=option,flag=multiSelect))
    }

    fun previousStep() {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.PREVIOUS))
    }

    fun toggleGoal(goal: String) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.GOAL,text=goal))
    }

    fun setExactDate(date: LocalDate) {
        _uiState.update { OnboardingDateReducer.reduce(it,OnboardingDateReducer.Event(OnboardingDateReducer.Action.EXACT_START,date=date)) }
    }

    fun setNotSureSelected(notSure: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.NOT_SURE,flag=notSure))
    }

    fun selectApproximateChip(chip: String) {
        _uiState.update { OnboardingDateReducer.reduce(it,OnboardingDateReducer.Event(OnboardingDateReducer.Action.APPROXIMATE,text=chip)) }
    }

    fun setDatePickerDialogVisible(visible: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.DATE_PICKER,flag=visible))
    }

    fun setExactEndDate(date: LocalDate) {
        _uiState.update { OnboardingDateReducer.reduce(it,OnboardingDateReducer.Event(OnboardingDateReducer.Action.EXACT_END,date=date)) }
    }

    /** User tapped "Dates are correct": the confirmed raw end date is kept. */
    fun confirmPeriodEndDate() {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.CONFIRM_END))
    }

    /** User tapped "Edit dates": reopen the end date picker to fix the entry. */
    fun editPeriodEndDate() {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.EDIT_END))
    }

    /**
     * The raw period end date the user entered, kept only when the pair is
     * valid and (typical or explicitly confirmed). Null when ongoing, absent,
     * or an unusual entry the user has not confirmed.
     */
    fun confirmedPeriodEndDate():LocalDate? = OnboardingDerived.confirmed(_uiState.value)

    internal fun confirmedPeriodEndDateKotlin(): LocalDate? {
        val s = _uiState.value
        if (s.isPeriodOngoing || !s.periodEndDateConfirmed) return null
        val start = s.selectedExactDate ?: return null
        val end = s.selectedPeriodEndDate ?: return null
        return if (CycleRepository.validatePeriodDates(start, end)) end else null
    }

    fun validatePeriodDates(start: LocalDate?, end: LocalDate?): Boolean {
        return CycleRepository.validatePeriodDates(start, end)
    }

    fun validatePeriodDatePair(start: LocalDate?, end: LocalDate?): PeriodDateValidationResult {
        return CycleRepository.validatePeriodDatePair(start, end)
    }

    fun selectPeriodDurationOption(option: String, days: Int, isOngoing: Boolean = false) {
        _uiState.update { OnboardingDateReducer.reduce(it,OnboardingDateReducer.Event(OnboardingDateReducer.Action.DURATION,text=option,days=days,ongoing=isOngoing)) }
    }

    fun setEndDatePickerDialogVisible(visible: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.END_PICKER,flag=visible))
    }

    fun selectCycleOption(option: String, days: Int) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.CYCLE,text=option,days=days))
    }

    fun setRemindFertile(remind: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.REMIND,flag=remind))
    }

    fun setAgeVerified(verified: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.AGE,flag=verified))
    }

    fun setTermsAgreed(agreed: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.TERMS,flag=agreed))
    }

    fun setCloudSyncAllowed(allowed: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.CLOUD,flag=allowed))
    }

    fun setShowTermsDialog(show: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.TERMS_DIALOG,flag=show))
    }

    fun setShowPrivacyDialog(show: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.PRIVACY_DIALOG,flag=show))
    }

    fun toggleTypicalSymptom(symptom: String) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.SYMPTOM,text=symptom))
    }

    fun selectTypicalEnergy(energy: String) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.ENERGY,text=energy))
    }

    fun selectTypicalSleep(sleep: String) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.SLEEP,text=sleep))
    }

    fun setCompletedDeepBaseline(completed: Boolean) {
        reduce(OnboardingStateReducer.Event(OnboardingStateReducer.Action.DEEP,flag=completed))
    }
}

