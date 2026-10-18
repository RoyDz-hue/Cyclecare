package com.example

import com.example.ui.viewmodel.OnboardingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    @Test
    fun testDefaultStateRequiresAgeAndTermsConsent() {
        val viewModel = OnboardingViewModel()
        val state = viewModel.uiState.value

        assertEquals(1, state.step)
        assertFalse(state.isAgeVerified)
        assertFalse(state.isTermsAgreed)
        assertFalse(state.canProceedFromPrivacy)
    }

    @Test
    fun testAgeAndTermsConsentEnablesPrivacyProgression() {
        val viewModel = OnboardingViewModel()

        viewModel.setAgeVerified(true)
        assertFalse("Only age verified should not allow proceeding", viewModel.uiState.value.canProceedFromPrivacy)

        viewModel.setTermsAgreed(true)
        assertTrue("Both age (18+) and terms agreed must allow proceeding", viewModel.uiState.value.canProceedFromPrivacy)

        viewModel.setAgeVerified(false)
        assertFalse("Revoking age confirmation must disable proceeding", viewModel.uiState.value.canProceedFromPrivacy)
    }

    @Test
    fun testPolicyDialogToggles() {
        val viewModel = OnboardingViewModel()

        assertFalse(viewModel.uiState.value.showTermsDialog)
        assertFalse(viewModel.uiState.value.showPrivacyDialog)

        viewModel.setShowTermsDialog(true)
        assertTrue(viewModel.uiState.value.showTermsDialog)

        viewModel.setShowTermsDialog(false)
        assertFalse(viewModel.uiState.value.showTermsDialog)

        viewModel.setShowPrivacyDialog(true)
        assertTrue(viewModel.uiState.value.showPrivacyDialog)

        viewModel.setShowPrivacyDialog(false)
        assertFalse(viewModel.uiState.value.showPrivacyDialog)
    }

    @Test
    fun testApproximateDateChoiceDoesNotRequireAnExactDate() {
        val viewModel = OnboardingViewModel()
        val today = java.time.LocalDate.now()

        val ranges = listOf(
            "A few days ago" to 4L,
            "About a week ago" to 7L,
            "About two weeks ago" to 14L
        )
        ranges.forEach { (choice, representativeDaysAgo) ->
            viewModel.selectApproximateChip(choice)
            val approximate = viewModel.uiState.value
            assertNull(approximate.selectedExactDate)
            assertTrue(approximate.hasCycleStartEstimate)
            assertEquals("APPROXIMATE:$choice", approximate.periodStartChoice)
            assertEquals(today.minusDays(representativeDaysAgo), approximate.effectiveLastPeriodDate)
        }

        viewModel.selectApproximateChip("I don’t know")
        val unknown = viewModel.uiState.value
        assertNull(unknown.selectedExactDate)
        assertTrue(unknown.lastPeriodAnswered)
        assertFalse(unknown.hasCycleStartEstimate)
    }

    @Test
    fun testPeriodEndDateAndDurationCalculation() {
        val viewModel = OnboardingViewModel()
        val today = java.time.LocalDate.now()
        val startDate = today.minusDays(10)
        val endDate = today.minusDays(6)

        viewModel.setExactDate(startDate)
        assertEquals(startDate, viewModel.uiState.value.effectiveLastPeriodDate)

        // Set exact end date (5-day span from startDate to endDate)
        viewModel.setExactEndDate(endDate)
        assertEquals(endDate, viewModel.uiState.value.selectedPeriodEndDate)
        assertEquals(5, viewModel.uiState.value.periodDurationDays)
        assertFalse(viewModel.uiState.value.isPeriodOngoing)

        // Select quick duration pill
        viewModel.selectPeriodDurationOption("4 days", 4, isOngoing = false)
        assertEquals(4, viewModel.uiState.value.periodDurationDays)
        assertEquals(startDate.plusDays(3), viewModel.uiState.value.effectivePeriodEndDate)
        assertFalse(viewModel.uiState.value.isPeriodOngoing)

        // Select ongoing bleeding status
        viewModel.selectPeriodDurationOption("Still ongoing", 3, isOngoing = true)
        assertTrue(viewModel.uiState.value.isPeriodOngoing)
        assertTrue(viewModel.uiState.value.isPeriodActiveToday)
        assertEquals("Menstrual Phase", viewModel.uiState.value.currentPhaseName)
    }

    @Test
    fun testPeriodDateValidationEnsuresEndGreaterOrEqualStart() {
        val viewModel = OnboardingViewModel()
        val start = java.time.LocalDate.of(2026, 9, 20)
        val validEnd = java.time.LocalDate.of(2026, 9, 25)
        val earlierEnd = java.time.LocalDate.of(2026, 9, 15)

        viewModel.setExactDate(start)

        // Valid end date >= start
        viewModel.setExactEndDate(validEnd)
        assertEquals(validEnd, viewModel.uiState.value.selectedPeriodEndDate)
        assertTrue(viewModel.uiState.value.isPeriodDatePairValid)
        assertEquals(start, viewModel.uiState.value.periodDateRange.period_start_date)
        assertEquals(validEnd, viewModel.uiState.value.periodDateRange.period_end_date)

        // Attempting to set an end date earlier than start date clamps to start (end >= start)
        viewModel.setExactEndDate(earlierEnd)
        assertTrue(viewModel.uiState.value.isPeriodDatePairValid)
        assertTrue(!viewModel.uiState.value.effectivePeriodEndDate.isBefore(start))
        assertTrue(viewModel.validatePeriodDates(start, viewModel.uiState.value.effectivePeriodEndDate))
    }
}
