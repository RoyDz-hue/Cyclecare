//! Exact explain output, only the actual phase/day inputs cross JNI.
use crate::cycle_math::CyclePhase;
use crate::learning::ExplainMyBodyResult;
use crate::safety::SafetyLevel;
pub struct Prediction {pub phase:CyclePhase,pub day_of_cycle:i32}
pub fn synthesize_explain_my_body(prediction: Option<&Prediction>) -> ExplainMyBodyResult {
    let Some(prediction) = prediction else {
        return ExplainMyBodyResult {
            summary: "No cycle estimate is available yet.".to_string(),
            what_might_be_happening: "Add a period start date if you want CycleCare to show a calendar estimate. You can still use daily logging and general care information without one.".to_string(),
            what_supports_that: "There is no saved date anchor to calculate a cycle day.".to_string(),
            what_else_could_explain: "Symptoms and body changes can have many causes and cannot be identified from an empty cycle calendar.".to_string(),
            what_you_can_do: "Use the calendar only when its assumptions fit; otherwise leave the estimate unset.".to_string(),
            what_to_watch: "You can record any changes that matter to you without attributing them to a cycle phase.".to_string(),
            when_to_get_checked: "Contact a qualified health professional if you have symptoms that concern you.".to_string(),
            safety_level: SafetyLevel::Low,
        };
    };
    let phase_name = prediction.phase.title();
    let estimate_note = "Based on the period date and cycle length in your settings; this is a calendar estimate, not a hormone measurement or confirmation of ovulation.";
    let phase_education = match prediction.phase {
        CyclePhase::Menstrual => "During menstrual bleeding, the uterus sheds its lining. Experiences vary from person to person and from cycle to cycle.",
        CyclePhase::Follicular => "The follicular phase runs from the start of bleeding toward ovulation. Timing varies, and calendar dates cannot confirm what is happening in an individual body.",
        CyclePhase::Ovulation => "Ovulation timing varies. A calendar window cannot confirm whether or when ovulation occurred.",
        CyclePhase::Luteal => "The luteal phase follows ovulation and ends when the next period begins. Its timing and associated experiences vary.",
    };
    ExplainMyBodyResult {
        summary: format!("Calendar estimate: cycle day {}, {}.", prediction.day_of_cycle, phase_name),
        what_might_be_happening: phase_education.to_string(),
        what_supports_that: estimate_note.to_string(),
        what_else_could_explain: "Any symptoms you experience may have explanations unrelated to cycle timing; these notes do not identify a cause.".to_string(),
        what_you_can_do: "If it is useful, keep notes on dates and observations. Calendar-based fertile-window estimates should not be used as contraception.".to_string(),
        what_to_watch: "If your dates or symptoms change, you can update your record; the estimate will change with the information you enter.".to_string(),
        when_to_get_checked: "Seek advice from a qualified health professional about symptoms that are severe, new, persistent, or worrying to you.".to_string(),
        safety_level: SafetyLevel::Low,
    }
}
