package com.cyclecare.app.domain.ai

/** Outcome of a consent-card Allow tap. Grants are bound to the displayed snapshot. */
sealed class AiConsentTapDecision {
    data class Enable(val providerIds: List<String>) : AiConsentTapDecision()
    data class FailClosed(val reason: String) : AiConsentTapDecision()
}

/** Projection of exactly what the consent card displayed and what the grant must bind to. */
private data class DisplayedProvider(
    val id: String,
    val fingerprint: String,
    val eligible: Boolean,
    val consentEnabled: Boolean,
    val consentRevision: Int,
    val policyRevision: String,
    val processorKeys: List<String>
)

private fun binding(m: AiProcessorManifest): Pair<Triple<String, Int, String>, List<DisplayedProvider>> =
    Triple(m.manifestRevision, m.version, m.accountSnapshot) to m.providers.map {
        DisplayedProvider(
            id = it.id,
            fingerprint = it.manifestFingerprint,
            eligible = it.eligible,
            consentEnabled = it.consentEnabled,
            consentRevision = it.consentRevision,
            policyRevision = it.policyRevision,
            processorKeys = it.processors.map { p -> p.id + "|" + p.policyUrl }
        )
    }

/**
 * Pure tap decision: enable exactly the providers shown as eligible in the
 * rendered manifest snapshot. The displayed snapshot must still match live
 * state field-for-field (revision, version, account, and every provider's id,
 * fingerprint, eligibility, consent enablement, consent revision, policy
 * revision and sub-processor list). A revision alone does not prove the set
 * is unchanged, so any drift - including same-revision provider or
 * fingerprint changes - fails closed and the card re-renders fresh
 * information instead of granting.
 */
/** True only when two manifest snapshots bind identically, field-for-field. */
fun aiConsentBindingEqual(a: AiProcessorManifest?, b: AiProcessorManifest?): Boolean =
    a != null && b != null && binding(a) == binding(b)

fun decideAiConsentTap(displayed: AiProcessorManifest?, current: AiProcessorManifest?): AiConsentTapDecision {
    if (displayed == null) {
        return AiConsentTapDecision.FailClosed("Review who can answer before allowing. Nothing was enabled.")
    }
    val live = current
        ?: return AiConsentTapDecision.FailClosed("The provider list is unavailable. Nothing was enabled.")
    if (!aiConsentBindingEqual(displayed, live)) {
        return AiConsentTapDecision.FailClosed("The provider list changed. Review it again before allowing.")
    }
    val ids = displayed.providers.filter { it.eligible && it.consentEnabled }.map { it.id }
    if (ids.isEmpty()) {
        return AiConsentTapDecision.FailClosed("No answer provider is available right now. Nothing was enabled.")
    }
    return AiConsentTapDecision.Enable(ids)
}
