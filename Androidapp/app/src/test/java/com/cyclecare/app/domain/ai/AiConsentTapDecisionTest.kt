package com.cyclecare.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tap-binding rules for the consent card; no network, no receipts. */
class AiConsentTapDecisionTest {

    private fun provider(id: String, eligible: Boolean = true, consentEnabled: Boolean = true) =
        AiProcessor(id = id, displayName = "$id Answers", model = "model-1", policyRevision = "p1",
            manifestFingerprint = "a".repeat(64), processors = emptyList(), eligible = eligible,
            unavailableReason = if (eligible) "" else "Unavailable", consentEnabled = consentEnabled,
            consentRevision = 1)

    private fun manifest(revision: String = "rev-1", providers: List<AiProcessor> = listOf(provider("GEMINI"))) =
        AiProcessorManifest(version = 3, accountSnapshot = "owner", manifestRevision = revision,
            providers = providers, hybridReleased = false, hybridMaxAttempts = 0)

    @Test
    fun tapWithoutDisplayedSnapshotFailsClosed() {
        val d = decideAiConsentTap(null, manifest())
        assertTrue(d is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun tapWithoutCurrentManifestFailsClosed() {
        val d = decideAiConsentTap("rev-1", null)
        assertTrue(d is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun tapWithChangedManifestFailsClosed() {
        val d = decideAiConsentTap("rev-1", manifest(revision = "rev-2"))
        assertTrue(d is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun tapEnablesExactlyDisplayedEligibleProviders() {
        val m = manifest(providers = listOf(
            provider("GEMINI"), provider("LAJIWA"),
            provider("COHERE", eligible = false), provider("OPENROUTER", consentEnabled = false)))
        val d = decideAiConsentTap("rev-1", m)
        assertEquals(AiConsentTapDecision.Enable(listOf("GEMINI", "LAJIWA")), d)
    }

    @Test
    fun tapWithNoEligibleProviderFailsClosed() {
        val m = manifest(providers = listOf(provider("GEMINI", eligible = false)))
        val d = decideAiConsentTap("rev-1", m)
        assertTrue(d is AiConsentTapDecision.FailClosed)
    }
}
