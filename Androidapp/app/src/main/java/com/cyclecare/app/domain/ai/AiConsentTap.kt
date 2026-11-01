package com.cyclecare.app.domain.ai

/** Outcome of a consent-card Allow tap. Grants are bound to the displayed snapshot. */
sealed class AiConsentTapDecision {
    data class Enable(val providerIds: List<String>) : AiConsentTapDecision()
    data class FailClosed(val reason: String) : AiConsentTapDecision()
}

/**
 * Pure tap decision: enable exactly the providers shown as eligible in the
 * displayed manifest snapshot. Any missing, stale, or changed snapshot fails
 * closed so the card re-renders fresh information instead of granting.
 */
fun decideAiConsentTap(displayedManifestRevision: String?, current: AiProcessorManifest?): AiConsentTapDecision {
    if (displayedManifestRevision.isNullOrBlank()) {
        return AiConsentTapDecision.FailClosed("Review who can answer before allowing. Nothing was enabled.")
    }
    val manifest = current
        ?: return AiConsentTapDecision.FailClosed("The provider list is unavailable. Nothing was enabled.")
    if (manifest.manifestRevision != displayedManifestRevision) {
        return AiConsentTapDecision.FailClosed("The provider list changed. Review it again before allowing.")
    }
    val ids = manifest.providers.filter { it.eligible && it.consentEnabled }.map { it.id }
    if (ids.isEmpty()) {
        return AiConsentTapDecision.FailClosed("No answer provider is available right now. Nothing was enabled.")
    }
    return AiConsentTapDecision.Enable(ids)
}
