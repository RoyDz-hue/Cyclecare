package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
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
    val totalSteps: Int = 5,
    val selectedGoals: Set<String> = setOf("🌸 Understand my cycle", "🩷 Track my periods"),
    val selectedExactDate: LocalDate? = null,
    val isNotSureSelected: Boolean = false,
    val selectedApproximateChip: String? = null,
    val showDatePickerDialog: Boolean = false,
    val selectedCycleOption: String = "25–28 days",
    val cycleLengthDays: Int = 28,
    val remindFertileWindow: Boolean = true,
    val isAgeVerified: Boolean = false,
    val isTermsAgreed: Boolean = false,
    val showTermsDialog: Boolean = false,
    val showPrivacyDialog: Boolean = false
) {
    val canProceedFromPrivacy: Boolean
        get() = isAgeVerified && isTermsAgreed

    val lastPeriodAnswered: Boolean
        get() = (selectedExactDate != null) || (selectedApproximateChip != null)

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
                "It varies / I don’t know" -> 11
                else -> 11
            }
            return LocalDate.now().minusDays(daysAgo.toLong())
        }

    val daysSinceLastPeriod: Int
        get() = ChronoUnit.DAYS.between(effectiveLastPeriodDate, LocalDate.now()).toInt().coerceAtLeast(0)

    val dayOfCycle: Int
        get() = (daysSinceLastPeriod % cycleLengthDays) + 1
}

/**
 * ViewModel responsible for managing onboarding progression and state across screens.
 */
class OnboardingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun nextStep() {
        _uiState.update { current ->
            if (current.step < current.totalSteps) {
                current.copy(step = current.step + 1)
            } else {
                current
            }
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
            current.copy(
                selectedExactDate = date,
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

    fun setShowTermsDialog(show: Boolean) {
        _uiState.update { it.copy(showTermsDialog = show) }
    }

    fun setShowPrivacyDialog(show: Boolean) {
        _uiState.update { it.copy(showPrivacyDialog = show) }
    }
}
