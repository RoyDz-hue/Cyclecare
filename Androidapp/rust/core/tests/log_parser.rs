//! Behavioral tests for the NL log parser, incl. the negation contract.

use cyclecare_core::log_parser::parse;

fn categories(r: &cyclecare_core::log_parser::ParsedLogInterpretation) -> Vec<&'static str> {
    r.items.iter().map(|i| i.category).collect()
}

#[test]
fn negation_cancels_only_the_next_words() {
    let r = parse("no cramps but severe headache");
    assert!(!r.symptoms_result.contains(&"cramps"), "negated cramps must not log");
    assert!(r.symptoms_result.contains(&"headache"), "later headache survives");

    let r2 = parse("no cramps today and no headache");
    assert!(!r2.symptoms_result.contains(&"cramps"));
    assert!(!r2.symptoms_result.contains(&"headache"), "second negation cancels the headache too");
}

#[test]
fn no_energy_is_exhaustion_idiom_not_negation() {
    let r = parse("no energy today");
    assert_eq!(r.energy_result, Some("VERY_LOW"));
}

#[test]
fn heavy_period_with_swahili_terms() {
    let r = parse("hedhi imedamga, damu nyingi sana");
    assert_eq!(r.flow_level_result, Some("HEAVY"));
    assert!(categories(&r).contains(&"Flow"));
}

#[test]
fn light_flow_via_kidogo() {
    let r = parse("period started, kidogo tu");
    assert_eq!(r.flow_level_result, Some("LIGHT"));
}

#[test]
fn severe_cramps_set_pain_and_followup() {
    let r = parse("tumbo inaniuma mbaya sana");
    assert_eq!(r.pain_intensity_result, 8);
    assert!(r.symptoms_result.contains(&"cramps"));
    assert_eq!(r.follow_up_question.as_deref(), Some("How long has the pain felt this strong?"));
}

#[test]
fn moderate_cramps_default() {
    let r = parse("I have cramps today");
    assert_eq!(r.pain_intensity_result, 4);
    assert!(r.follow_up_question.is_none());
}

#[test]
fn migraine_vs_headache() {
    assert!(parse("my head is pounding").symptoms_result.contains(&"migraine"));
    assert!(parse("mild headache").symptoms_result.contains(&"headache"));
}

#[test]
fn tired_vs_exhausted() {
    assert_eq!(parse("feeling tired").energy_result, Some("LOW"));
    assert_eq!(parse("nimechoka, exhausted").energy_result, Some("VERY_LOW"));
    assert_eq!(parse("feeling good and energetic").energy_result, Some("GOOD"));
}

#[test]
fn mood_detection_paths() {
    assert_eq!(parse("nina hasira leo").mood_result, Some("UNCOMFORTABLE"));
    assert_eq!(parse("feeling anxious and overwhelmed").mood_result, Some("LOW"));
    assert_eq!(parse("happy and calm, furaha").mood_result, Some("GOOD"));
}

#[test]
fn unmatched_input_gets_wellness_fallback_and_followup() {
    let r = parse("something feels off today");
    assert_eq!(r.items.len(), 1);
    assert_eq!(r.items[0].category, "Wellness");
    assert!(r.follow_up_question.is_some());
    assert!(r.items[0].detail.contains("something feels off today"));
}

#[test]
fn sleep_and_back_and_bloating() {
    let r = parse("couldn't sleep, mgongo inauma, feeling bloated");
    assert!(categories(&r).contains(&"Sleep"));
    assert!(r.symptoms_result.contains(&"back_pain"));
    assert!(r.symptoms_result.contains(&"bloating"));
}

#[test]
fn multi_symptom_entry_collects_all() {
    let r = parse("period started heavy, cramps killing me, kichwa kinapiga, nimechoka");
    assert_eq!(r.flow_level_result, Some("HEAVY"));
    assert!(r.symptoms_result.contains(&"cramps"));
    assert!(r.symptoms_result.contains(&"headache"), "kinapiga is not in the migraine vocabulary (parity with Kotlin, not an improvement)");
    assert_eq!(r.energy_result, Some("LOW")); // nimechoka, no "very low" markers
    assert!(r.items.len() >= 4);
}
