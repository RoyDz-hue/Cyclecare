package com.cyclecare.app.domain.onboarding

/**
 * Dynamic onboarding questions for steps 4-6.
 *
 * Questions are generated progressively from what the user has already
 * selected (step 3 goals, then each later step's answers). The primary source
 * is the `onboarding-questions` edge function (AI-driven); the local adaptive
 * bank below is the offline/guest/failure fallback so onboarding never blocks
 * on connectivity, account state, or an AI provider.
 */
data class OnboardingQuestion(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val multiSelect: Boolean = true
)

object LocalAdaptiveOnboardingBank {

    /** Progressive fallback bank: keyed by step, adapted by goals + prior answers. */
    fun questionsFor(
        step: Int,
        goals: Set<String>,
        priorAnswers: Map<String, Set<String>>
    ): List<OnboardingQuestion> {
        val joined = goals.joinToString(" ").lowercase()
        val wantsCycle = joined.contains("cycle") || joined.contains("period")
        val wantsSymptoms = joined.contains("symptom") || joined.contains("changes in my body")
        val wantsFertility = joined.contains("fertile") || joined.contains("fertility")
        val wantsTuning = joined.contains("in tune") || joined.contains("exploring")

        return when (step) {
            4 -> buildList {
                if (wantsCycle) add(
                    OnboardingQuestion(
                        id = "s4_rhythm_focus",
                        prompt = "What feels most unpredictable about your cycle right now?",
                        options = listOf("Timing", "Flow", "PMS", "Energy", "Nothing really"),
                        multiSelect = true
                    )
                )
                if (wantsSymptoms) add(
                    OnboardingQuestion(
                        id = "s4_symptom_focus",
                        prompt = "Which changes do you notice most between phases?",
                        options = listOf("Mood", "Skin", "Sleep", "Appetite", "Cramps", "Energy")
                    )
                )
                if (wantsFertility) add(
                    OnboardingQuestion(
                        id = "s4_fertility_focus",
                        prompt = "What would help you most with fertility awareness?",
                        options = listOf("Fertile window timing", "Ovulation signs", "Cycle regularity", "Just learning")
                    )
                )
                if (isEmpty() || wantsTuning) add(
                    OnboardingQuestion(
                        id = "s4_tuning_focus",
                        prompt = "What would you like CycleCare to notice for you?",
                        options = listOf("Energy shifts", "Mood patterns", "Routine changes", "Surprises me")
                    )
                )
            }
            5 -> buildList {
                val earlier = priorAnswers.values.flatten().joinToString(" ").lowercase()
                add(
                    OnboardingQuestion(
                        id = "s5_gentle_checkins",
                        prompt = "How would you like check-ins to feel?",
                        options = listOf("Quiet and minimal", "Warm and encouraging", "Only when something changes"),
                        multiSelect = false
                    )
                )
                if (earlier.contains("sleep") || earlier.contains("energy")) add(
                    OnboardingQuestion(
                        id = "s5_rest_focus",
                        prompt = "Should CycleCare pay extra attention to rest and energy patterns?",
                        options = listOf("Yes, rest matters", "Somewhat", "Not specifically"),
                        multiSelect = false
                    )
                )
            }
            6 -> buildList {
                add(
                    OnboardingQuestion(
                        id = "s6_support_style",
                        prompt = "When something changes in your pattern, what should CycleCare do first?",
                        options = listOf("Explain gently what it might mean", "Suggest simple self-care", "Just note it quietly"),
                        multiSelect = false
                    )
                )
                add(
                    OnboardingQuestion(
                        id = "s6_depth",
                        prompt = "How much detail do you want in insights?",
                        options = listOf("Short and simple", "Balanced", "Deep explanations"),
                        multiSelect = false
                    )
                )
            }
            else -> emptyList()
        }
    }
}
