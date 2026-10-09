package com.example.domain.safety

import com.example.data.local.CycleDayLog
import com.example.data.model.SafetyEvaluation
import com.example.data.model.SafetyLevel

object SafetyEngine {

    /**
     * Deterministic evaluation of red flags and clinical caution indicators.
     * AI is NEVER permitted to downgrade or suppress these deterministic findings.
     */
    fun evaluate(log: CycleDayLog?): SafetyEvaluation {
        if (log == null) {
            return SafetyEvaluation(
                level = SafetyLevel.LOW,
                title = "Routine Health Status",
                message = "No acute symptoms recorded.",
                actionAdvice = "Continue regular wellness tracking."
            )
        }

        val symptoms = log.symptomsCsv.split(",").filter { it.isNotBlank() }
        val vaginal = log.vaginalHealthCsv.split(",").filter { it.isNotBlank() }
        val urinary = log.urinaryHealthCsv.split(",").filter { it.isNotBlank() }

        // 1. URGENT CRITICAL CHECKS
        if (symptoms.contains("fainting") && log.flowLevel == "UNUSUALLY_HEAVY") {
            return SafetyEvaluation(
                level = SafetyLevel.URGENT,
                title = "Immediate Medical Attention Advised",
                message = "Fainting combined with unusually heavy bleeding is a medical emergency that can indicate severe blood loss or ectopic pregnancy.",
                actionAdvice = "Please seek emergency medical care immediately or contact local emergency services.",
                isRedFlag = true
            )
        }

        if (log.flowLevel == "UNUSUALLY_HEAVY" && log.painIntensity >= 8) {
            return SafetyEvaluation(
                level = SafetyLevel.URGENT,
                title = "Severe Bleeding & Acute Pain",
                message = "Bleeding through a pad or tampon in under an hour alongside severe pain (8+/10) warrants immediate emergency clinical evaluation.",
                actionAdvice = "Visit an urgent care center or emergency department immediately.",
                isRedFlag = true
            )
        }

        if (symptoms.contains("fainting")) {
            return SafetyEvaluation(
                level = SafetyLevel.URGENT,
                title = "Fainting Episode Reported",
                message = "Sudden loss of consciousness during a cycle requires urgent professional evaluation to rule out acute anemia or hemodynamic instability.",
                actionAdvice = "Rest with feet elevated, avoid driving, and consult an urgent healthcare provider.",
                isRedFlag = true
            )
        }

        // 2. SEEK PROFESSIONAL ADVICE CHECKS
        if (vaginal.contains("sores_lesions")) {
            return SafetyEvaluation(
                level = SafetyLevel.SEEK_PROFESSIONAL_ADVICE,
                title = "New Sores or Lesions Detected",
                message = "New blisters, sores, or skin breaches in the pelvic area require clinical examination, proper testing, and targeted treatment.",
                actionAdvice = "Schedule an appointment with a gynecologist or sexual health clinic. Avoid topical irritants.",
                isRedFlag = true
            )
        }

        if (log.flowLevel == "UNUSUALLY_HEAVY") {
            return SafetyEvaluation(
                level = SafetyLevel.SEEK_PROFESSIONAL_ADVICE,
                title = "Unusually Heavy Menstrual Flow",
                message = "Soaking through one or more menstrual products in less than 2 hours is considered menorrhagia.",
                actionAdvice = "Monitor hourly pad/tampon counts. If prolonged for more than 2-4 hours, seek urgent medical assessment.",
                isRedFlag = true
            )
        }

        if (symptoms.contains("severe_cramps") || log.painIntensity >= 7) {
            return SafetyEvaluation(
                level = SafetyLevel.SEEK_PROFESSIONAL_ADVICE,
                title = "Severe Pelvic Pain (Level ${log.painIntensity}/10)",
                message = "Intense, debilitating pain that does not respond to standard pain management may indicate endometriosis, ovarian cyst rupture, or pelvic infection.",
                actionAdvice = "Seek a clinical review. Use heat therapy for comfort, but do not ignore persistent severe pain.",
                isRedFlag = true
            )
        }

        if (urinary.contains("urinary_burning") && urinary.contains("urinary_urgency")) {
            return SafetyEvaluation(
                level = SafetyLevel.SEEK_PROFESSIONAL_ADVICE,
                title = "Urinary Symptoms Suggestive of Infection",
                message = "Burning urination combined with urgency and frequency commonly indicates a Urinary Tract Infection (UTI).",
                actionAdvice = "Increase water intake and consult a physician or clinic promptly for a simple urine analysis and antibiotics if needed.",
                isRedFlag = false
            )
        }

        // 3. REVIEW CHECKS
        if (vaginal.contains("vaginal_itching") || vaginal.contains("vaginal_burning") || vaginal.contains("unusual_odor")) {
            return SafetyEvaluation(
                level = SafetyLevel.REVIEW,
                title = "Vaginal Irritation / Discharge Changes",
                message = "Itching, burning, and changes in odor or texture can occur with yeast infections, bacterial vaginosis (BV), or mild contact irritation.",
                actionAdvice = "Avoid douching, scented soaps, or tight synthetic fabrics. If symptoms persist beyond 2-3 days, consult a healthcare provider for a swab.",
                isRedFlag = false
            )
        }

        // 4. MONITOR CHECKS
        if (symptoms.isNotEmpty() || log.painIntensity in 3..6) {
            return SafetyEvaluation(
                level = SafetyLevel.MONITOR,
                title = "Mild to Moderate Cyclical Symptoms",
                message = "Symptoms recorded are common during hormonal transitions across follicular, ovulatory, and luteal phases.",
                actionAdvice = "Stay well hydrated, practice gentle movement, and log how these symptoms evolve over the next 24 hours.",
                isRedFlag = false
            )
        }

        return SafetyEvaluation(
            level = SafetyLevel.LOW,
            title = "Normal Wellness Baseline",
            message = "No acute or concerning symptoms flagged.",
            actionAdvice = "Maintain hydration, balanced nutrition, and restorative rest."
        )
    }
}
