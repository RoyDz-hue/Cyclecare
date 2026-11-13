package com.cyclecare.app.domain.safety

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.SafetyEvaluation
import com.cyclecare.app.data.model.SafetyLevel

/** Local-only safety triage. This is a conservative prompt for care, not a diagnosis. */
object SafetyEngine {
    fun evaluate(log: CycleDayLog?): SafetyEvaluation {
        // Native core port first (identical semantics incl. verbatim strings);
        // null -> the Kotlin engine below. Clinical wording stays authored here.
        com.cyclecare.app.rust.CycleCareCoreBridge.evaluateSafety(log)?.let { return it }
        if (log == null) return low("No acute symptoms are recorded.")
        val symptoms = values(log.symptomsCsv)
        val vaginal = values(log.vaginalHealthCsv)
        val urinary = values(log.urinaryHealthCsv)
        val fainting = "fainting" in symptoms
        val heavy = log.flowLevel.trim().uppercase() == "UNUSUALLY_HEAVY"
        if (fainting && heavy) return urgent(
            "Fainting and unusually heavy bleeding were recorded together.",
            "Please seek urgent medical care now, especially if symptoms are continuing, worsening, or you feel unsafe."
        )
        if (heavy && log.painIntensity >= 8) return urgent(
            "Unusually heavy bleeding and severe pain were recorded together.",
            "Please seek urgent medical care now. Do not rely on this app to assess the cause."
        )
        if (fainting) return urgent(
            "A fainting episode was recorded.",
            "Please seek prompt medical evaluation and avoid driving if you feel faint or unwell."
        )
        if ("sores_lesions" in vaginal) return seek(
            "New sores or lesions were recorded.",
            "Arrange a clinical assessment, and avoid products that irritate the area."
        )
        if (heavy) return seek(
            "Unusually heavy bleeding was recorded.",
            "Please contact a healthcare professional promptly; seek urgent help if it is worsening or you feel faint."
        )
        if ("severe_cramps" in symptoms || log.painIntensity >= 7) return seek(
            "Severe pain was recorded.",
            "Arrange a clinical review, particularly if pain is new, persistent, or worsening."
        )
        if ("urinary_burning" in urinary && ("urinary_urgency" in urinary || "urinary_frequency" in urinary)) return seek(
            "Several urinary symptoms were recorded together.",
            "Consider prompt clinical advice for assessment. Seek urgent help for fever, flank pain, or worsening illness."
        )
        if (vaginal.any { it in setOf("vaginal_itching", "vaginal_burning", "unusual_odor") }) return review(
            "Vaginal irritation or discharge changes were recorded.",
            "Avoid douching and scented irritants; seek clinical advice if symptoms persist or worsen."
        )
        return if (symptoms.isNotEmpty() || log.painIntensity in 3..6) {
            SafetyEvaluation(SafetyLevel.MONITOR, "Symptoms to monitor", "Symptoms were recorded without an urgent combination.", "Notice whether they persist, change, or worsen.")
        } else low("No acute symptoms are recorded.")
    }

    /**
     * Evaluates NEW text before any network request or account/context gating. It never writes.
     * Existing-log findings are retained, while explicitly negated input is not promoted to a flag.
     */
    fun evaluateInput(rawInput: String, existingLog: CycleDayLog? = null): SafetyEvaluation {
        val text = rawInput.trim().lowercase()
        val base = evaluate(existingLog)
        if (text.isBlank()) return base
        val positive = positiveText(text)
        val savedSymptoms = values(existingLog?.symptomsCsv.orEmpty())
        val fainting = containsAny(positive, "faint", "passed out", "lost consciousness") || "fainting" in savedSymptoms
        val heavy = containsAny(positive, "unusually heavy", "very heavy", "soaking", "soaked", "heavy bleeding", "lots of blood") || existingLog?.flowLevel?.trim()?.uppercase() == "UNUSUALLY_HEAVY"
        val severePain = containsAny(positive, "severe pain", "severe pelvic pain", "severe cramps", "unbearable pain", "excruciating", "pain is killing", "can't stand the pain") || (existingLog?.painIntensity ?: 0) >= 7 || "severe_cramps" in savedSymptoms
        val sores = containsAny(positive, "sores", "lesions", "blisters")
        val urinaryBurning = containsAny(positive, "burning when i pee", "burning urination", "burning to pee")
        val urinaryOther = containsAny(positive, "urgent to pee", "urinary urgency", "peeing often", "frequent urination")
        val candidate = when {
            fainting && heavy -> urgent("Fainting and unusually heavy bleeding were mentioned together.", "Please seek urgent medical care now, especially if symptoms are continuing or worsening.")
            heavy && severePain -> urgent("Unusually heavy bleeding and severe pain were mentioned together.", "Please seek urgent medical care now; this app cannot assess the cause.")
            fainting -> urgent("A fainting episode was mentioned.", "Please seek prompt medical evaluation and avoid driving if you feel faint or unwell.")
            sores -> seek("New sores or lesions were mentioned.", "Arrange a clinical assessment and avoid products that irritate the area.")
            heavy -> seek("Unusually heavy bleeding was mentioned.", "Please contact a healthcare professional promptly; seek urgent help if it worsens or you feel faint.")
            severePain -> seek("Severe pain was mentioned.", "Arrange a clinical review, particularly if pain is new, persistent, or worsening.")
            urinaryBurning && urinaryOther -> seek("Several urinary symptoms were mentioned together.", "Consider prompt clinical advice for assessment; seek urgent help for fever, flank pain, or worsening illness.")
            // A keyword miss is not a negative safety screen. This also covers Latin-script
            // languages and mixed input; script detection cannot establish understanding.
            else -> limitedAssessment(base)
        }
        return if (base.level.ordinal > candidate.level.ordinal) base else candidate
    }

    private fun limitedAssessment(base: SafetyEvaluation): SafetyEvaluation {
        val limitation = "This local check cannot reliably assess every symptom description or language. No matched warning does not mean symptoms are safe."
        return SafetyEvaluation(
            SafetyLevel.REVIEW,
            "Safety assessment limited",
            if (base.level != SafetyLevel.LOW) "${base.message} $limitation" else limitation,
            "If you feel seriously unwell or symptoms are severe or worsening, seek urgent medical care. Otherwise ask a qualified healthcare professional; use an interpreter or someone you trust if language is a barrier."
        )
    }

    private fun positiveText(text: String): String {
        // Negation stops at punctuation/conjunctions, so a second positive symptom survives.
        return text.split(Regex("[,.;!?]|\\b(?:but|and|however)\\b")).joinToString(" ") { clause ->
            if (Regex("\\b(no|not|never|without|haven't|dont|don't|didn't|doesn't|isn't|aren't)\\b").containsMatchIn(clause)) "" else clause
        }
    }
    private fun containsAny(text: String, vararg terms: String) = terms.any {
        Regex("\\b" + Regex.escape(it) + "[a-z]*\\b").containsMatchIn(text)
    }

    private fun values(csv: String) = csv.split(',').map { it.trim().lowercase().replace(' ', '_') }.filter(String::isNotBlank).toSet()
    private fun low(message: String) = SafetyEvaluation(SafetyLevel.LOW, "Routine health status", message, "Continue tracking and seek care for severe or worsening symptoms.")
    private fun review(message: String, advice: String) = SafetyEvaluation(SafetyLevel.REVIEW, "Review recommended", message, advice)
    private fun seek(message: String, advice: String) = SafetyEvaluation(SafetyLevel.SEEK_PROFESSIONAL_ADVICE, "Professional advice recommended", message, advice, true)
    private fun urgent(message: String, advice: String) = SafetyEvaluation(SafetyLevel.URGENT, "Urgent medical attention advised", message, advice, true)
}
