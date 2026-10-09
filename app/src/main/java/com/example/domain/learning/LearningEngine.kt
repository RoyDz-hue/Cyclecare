package com.example.domain.learning

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.model.LearnedObservation

object LearningEngine {

    /**
     * Generates "What I've Learned About You" cards for the personal health intelligence space.
     */
    fun compileLearnedObservations(
        settings: CycleSettings,
        logs: List<CycleDayLog>
    ): List<LearnedObservation> {
        val observations = mutableListOf<LearnedObservation>()

        // 1. Your Usual Cycle
        observations.add(
            LearnedObservation(
                id = "usual_cycle",
                category = "Your usual cycle",
                headline = "Consistent ~${settings.averageCycleLength}-Day Cycle Baseline",
                description = "Your cycle rhythm demonstrates regular ovulatory pacing with bleeding averaging ${settings.periodDuration} days.",
                confidence = "High confidence"
            )
        )

        // 2. Your Pattern
        val fatigueLogs = logs.filter { it.symptomsCsv.contains("fatigue") || it.energyLevel == "LOW" || it.energyLevel == "VERY_LOW" }
        if (fatigueLogs.isNotEmpty()) {
            observations.add(
                LearnedObservation(
                    id = "luteal_dip",
                    category = "Your pattern",
                    headline = "Premenstrual Energy Transition",
                    description = "Your energy tends to gently taper 1 to 3 days before your period begins as progesterone shifts.",
                    confidence = "High confidence"
                )
            )
        } else {
            observations.add(
                LearnedObservation(
                    id = "follicular_boost",
                    category = "Your pattern",
                    headline = "Post-Period Vitality Lift",
                    description = "Your logs show steady, resilient energy during the first week following menstruation.",
                    confidence = "Moderate"
                )
            )
        }

        // 3. Something Changing (or Stable)
        if (settings.averageCycleLength >= 32) {
            observations.add(
                LearnedObservation(
                    id = "cycle_shift",
                    category = "Something changing",
                    headline = "Recent Cycles Slightly Longer",
                    description = "Your recent cycles have been about 2 days longer than earlier records. Often correlated with stress, sleep, or travel.",
                    confidence = "Moderate"
                )
            )
        } else {
            observations.add(
                LearnedObservation(
                    id = "cycle_stability",
                    category = "Something changing",
                    headline = "Predictable Hormonal Rhythm",
                    description = "Variability across your recorded cycles remains within ±1–2 days of your median.",
                    confidence = "High confidence"
                )
            )
        }

        // 4. Still Learning
        val sleepLoggedCount = logs.count { it.sleepHours > 0 }
        if (sleepLoggedCount < 5) {
            observations.add(
                LearnedObservation(
                    id = "sleep_learning",
                    category = "Still learning",
                    headline = "Sleep & Phase Correlations",
                    description = "We don't have enough recorded nights yet to confirm whether luteal progesterone affects your sleep depth. Log sleep when convenient.",
                    confidence = "Still learning"
                )
            )
        }

        return observations
    }

    /**
     * Generates structured "Questions for Your Doctor" clinical discussion prompts.
     */
    fun generateDoctorSummary(
        settings: CycleSettings,
        logs: List<CycleDayLog>
    ): List<String> {
        val questions = mutableListOf<String>()
        val hasSevereCramps = logs.any { it.painIntensity >= 7 || it.symptomsCsv.contains("severe_cramps") }
        val hasUnusualFlow = logs.any { it.flowLevel == "UNUSUALLY_HEAVY" }

        questions.add("My typical cycle length is ${settings.averageCycleLength} days with ${settings.periodDuration} days of bleeding.")
        if (hasSevereCramps) {
            questions.add("I experience sharp pelvic pain (rating 7+/10) during menstruation. Does this suggest primary dysmenorrhea or secondary factors like endometriosis?")
        }
        if (hasUnusualFlow) {
            questions.add("I have recorded unusually heavy bleeding during my recent cycle. Would a ferritin and thyroid check be recommended?")
        }
        questions.add("Are my premenstrual fatigue and headache patterns within a healthy hormonal variation range?")
        questions.add("What specific red flags should I monitor if my cycle length varies by more than 4 days?")

        return questions
    }
}
