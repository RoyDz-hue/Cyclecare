package com.example

import com.example.ui.viewmodel.OnboardingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
