//! Port of LocalAdaptiveOnboardingBank.questionsFor (main@31b88f5,
//! domain/onboarding/OnboardingQuestions.kt): the offline/guest/failure
//! fallback bank for steps 4-6. The edge function (AI) is a host concern.
//! Executed JVM locks: /downloads/parity/onboarding/ (verbatim source
//! compiled unchanged, corpus q3a-q6a, 2026-11-02 14:03 EAT).

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OnboardingQuestion {
    pub id: &'static str,
    pub prompt: &'static str,
    pub options: &'static [&'static str],
    pub multi_select: bool,
}

const fn q(id: &'static str, prompt: &'static str, options: &'static [&'static str], multi_select: bool) -> OnboardingQuestion {
    OnboardingQuestion { id, prompt, options, multi_select }
}

pub fn questions_for(
    step: u32,
    goals: &[&str],
    prior_answers: &[Vec<&str>],
) -> Vec<OnboardingQuestion> {
    let joined = goals.join(" ").to_lowercase();
    let wants_cycle = joined.contains("cycle") || joined.contains("period");
    let wants_symptoms = joined.contains("symptom") || joined.contains("changes in my body");
    let wants_fertility = joined.contains("fertile") || joined.contains("fertility");
    let wants_tuning = joined.contains("in tune") || joined.contains("exploring");
    let mut out = Vec::new();
    match step {
        4 => {
            if wants_cycle {
                out.push(q(
                    "s4_rhythm_focus",
                    "What feels most unpredictable about your cycle right now?",
                    &["Timing", "Flow", "PMS", "Energy", "Nothing really"],
                    true,
                ));
            }
            if wants_symptoms {
                out.push(q(
                    "s4_symptom_focus",
                    "Which changes do you notice most between phases?",
                    &["Mood", "Skin", "Sleep", "Appetite", "Cramps", "Energy"],
                    true,
                ));
            }
            if wants_fertility {
                out.push(q(
                    "s4_fertility_focus",
                    "What would help you most with fertility awareness?",
                    &["Fertile window timing", "Ovulation signs", "Cycle regularity", "Just learning"],
                    true,
                ));
            }
            if out.is_empty() || wants_tuning {
                out.push(q(
                    "s4_tuning_focus",
                    "What would you like CycleCare to notice for you?",
                    &["Energy shifts", "Mood patterns", "Routine changes", "Surprises me"],
                    true,
                ));
            }
        }
        5 => {
            let earlier = prior_answers
                .iter()
                .flat_map(|v| v.iter().copied())
                .collect::<Vec<_>>()
                .join(" ")
                .to_lowercase();
            out.push(q(
                "s5_gentle_checkins",
                "How would you like check-ins to feel?",
                &["Quiet and minimal", "Warm and encouraging", "Only when something changes"],
                false,
            ));
            if earlier.contains("sleep") || earlier.contains("energy") {
                out.push(q(
                    "s5_rest_focus",
                    "Should CycleCare pay extra attention to rest and energy patterns?",
                    &["Yes, rest matters", "Somewhat", "Not specifically"],
                    false,
                ));
            }
        }
        6 => {
            out.push(q(
                "s6_support_style",
                "When something changes in your pattern, what should CycleCare do first?",
                &["Explain gently what it might mean", "Suggest simple self-care", "Just note it quietly"],
                false,
            ));
            out.push(q(
                "s6_depth",
                "How much detail do you want in insights?",
                &["Short and simple", "Balanced", "Deep explanations"],
                false,
            ));
        }
        _ => {}
    }
    out
}
