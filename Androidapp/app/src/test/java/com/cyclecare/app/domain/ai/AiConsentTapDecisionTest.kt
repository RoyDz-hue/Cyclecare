package com.cyclecare.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tap-binding rules for the consent card; no network, no receipts.
 * The displayed snapshot must match live state field-for-field or the tap
 * fails closed.
 */
class AiConsentTapDecisionTest {

    private fun provider(
        id: String,
        eligible: Boolean = true,
        consentEnabled: Boolean = true,
        fingerprint: String = "a".repeat(64),
        consentRevision: Int = 1,
        policyRevision: String = "p1",
        processors: List<AiSubProcessor> = listOf(AiSubProcessor("sub-1", "Sub Co", "https://example.com/privacy"))
    ) = AiProcessor(id = id, displayName = "$id Answers", model = "model-1", policyRevision = policyRevision,
        manifestFingerprint = fingerprint, processors = processors, eligible = eligible,
        unavailableReason = if (eligible) "" else "Unavailable", consentEnabled = consentEnabled,
        consentRevision = consentRevision)

    private fun manifest(
        revision: String = "rev-1",
        account: String = "owner",
        version: Int = 3,
        providers: List<AiProcessor> = listOf(provider("GEMINI"))
    ) = AiProcessorManifest(version = version, accountSnapshot = account, manifestRevision = revision,
        providers = providers, hybridReleased = false, hybridMaxAttempts = 0)

    @Test
    fun tapWithoutDisplayedSnapshotFailsClosed() {
        assertTrue(decideAiConsentTap(null, manifest()) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun tapWithoutCurrentManifestFailsClosed() {
        assertTrue(decideAiConsentTap(manifest(), null) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun tapWithChangedRevisionFailsClosed() {
        assertTrue(decideAiConsentTap(manifest(), manifest(revision = "rev-2")) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun sameRevisionButChangedFingerprintFailsClosed() {
        val current = manifest(providers = listOf(provider("GEMINI", fingerprint = "b".repeat(64))))
        assertTrue(decideAiConsentTap(manifest(), current) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun sameRevisionButAddedProviderFailsClosed() {
        val current = manifest(providers = listOf(provider("GEMINI"), provider("LAJIWA")))
        assertTrue(decideAiConsentTap(manifest(), current) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun sameRevisionButRemovedProviderFailsClosed() {
        val displayed = manifest(providers = listOf(provider("GEMINI"), provider("LAJIWA")))
        assertTrue(decideAiConsentTap(displayed, manifest()) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun sameRevisionButBumpedConsentRevisionFailsClosed() {
        val current = manifest(providers = listOf(provider("GEMINI", consentRevision = 2)))
        assertTrue(decideAiConsentTap(manifest(), current) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun sameRevisionButChangedPolicyRevisionFailsClosed() {
        val current = manifest(providers = listOf(provider("GEMINI", policyRevision = "p2")))
        assertTrue(decideAiConsentTap(manifest(), current) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun sameRevisionButChangedSubProcessorPolicyUrlFailsClosed() {
        val current = manifest(providers = listOf(provider("GEMINI",
            processors = listOf(AiSubProcessor("sub-1", "Sub Co", "https://example.com/other")))))
        assertTrue(decideAiConsentTap(manifest(), current) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun accountSessionChangeFailsClosed() {
        assertTrue(decideAiConsentTap(manifest(), manifest(account = "other-owner")) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun tapEnablesExactlyDisplayedEligibleProviders() {
        val m = manifest(providers = listOf(
            provider("GEMINI"), provider("LAJIWA"),
            provider("COHERE", eligible = false), provider("OPENROUTER", consentEnabled = false)))
        assertEquals(AiConsentTapDecision.Enable(listOf("GEMINI", "LAJIWA")), decideAiConsentTap(m, m))
    }

    @Test
    fun tapWithNoEligibleProviderFailsClosed() {
        val m = manifest(providers = listOf(provider("GEMINI", eligible = false)))
        assertTrue(decideAiConsentTap(m, m) is AiConsentTapDecision.FailClosed)
    }

    @Test
    fun grantSetComesFromDisplayedSnapshotOnly() {
        // Live state equals the displayed snapshot: the enabled set is derived
        // from the displayed list, never from anything else.
        val m = manifest(providers = listOf(provider("GEMINI"), provider("LAJIWA", consentEnabled = false)))
        assertEquals(AiConsentTapDecision.Enable(listOf("GEMINI")), decideAiConsentTap(m, m))
    }
}
