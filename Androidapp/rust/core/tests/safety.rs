//! SafetyEngine port tests. Every expected level/message is taken from the
//! Kotlin source; nothing here invents new triage rules.

use cyclecare_core::safety::*;

fn log(flow: &str, symptoms: &str, vaginal: &str, urinary: &str, pain: i32) -> SafetyLog {
    SafetyLog {
        flow_level: flow.to_string(),
        symptoms_csv: symptoms.to_string(),
        vaginal_health_csv: vaginal.to_string(),
        urinary_health_csv: urinary.to_string(),
        pain_intensity: pain,
    }
}

#[test]
fn null_log_is_low() {
    let e = evaluate(None);
    assert_eq!(e.level, SafetyLevel::Low);
    assert_eq!(e.title, "Routine health status");
    assert!(!e.is_red_flag);
}

#[test]
fn fainting_plus_unusually_heavy_is_urgent() {
    let e = evaluate(Some(&log("UNUSUALLY_HEAVY", "fainting", "", "", 0)));
    assert_eq!(e.level, SafetyLevel::Urgent);
    assert_eq!(e.message, "Fainting and unusually heavy bleeding were recorded together.");
    assert!(e.is_red_flag);
}

#[test]
fn heavy_plus_pain_8_is_urgent_pain_7_is_only_seek() {
    let e8 = evaluate(Some(&log("UNUSUALLY_HEAVY", "", "", "", 8)));
    assert_eq!(e8.level, SafetyLevel::Urgent);
    // heavy && pain 7 -> the `heavy` seek branch fires first
    let e7 = evaluate(Some(&log("UNUSUALLY_HEAVY", "", "", "", 7)));
    assert_eq!(e7.level, SafetyLevel::SeekProfessionalAdvice);
    assert_eq!(e7.message, "Unusually heavy bleeding was recorded.");
}

#[test]
fn fainting_alone_is_urgent_regardless_of_flow() {
    let e = evaluate(Some(&log("", "fainting", "", "", 0)));
    assert_eq!(e.level, SafetyLevel::Urgent);
    assert_eq!(e.message, "A fainting episode was recorded.");
}

#[test]
fn sores_seek_before_heavy() {
    // ordering: sores check precedes the heavy-only check in the source
    let e = evaluate(Some(&log("UNUSUALLY_HEAVY", "", "sores_lesions", "", 0)));
    assert_eq!(e.level, SafetyLevel::SeekProfessionalAdvice);
    assert_eq!(e.message, "New sores or lesions were recorded.");
}

#[test]
fn severe_pain_boundary_6_vs_7() {
    // pain 6 with no symptoms -> MONITOR; pain 7 -> SEEK
    let e6 = evaluate(Some(&log("", "", "", "", 6)));
    assert_eq!(e6.level, SafetyLevel::Monitor);
    let e7 = evaluate(Some(&log("", "", "", "", 7)));
    assert_eq!(e7.level, SafetyLevel::SeekProfessionalAdvice);
    assert_eq!(e7.message, "Severe pain was recorded.");
}

#[test]
fn urinary_combination_needs_burning_plus_urgency_or_frequency() {
    let e = evaluate(Some(&log("", "", "", "urinary_burning,urinary_urgency", 0)));
    assert_eq!(e.level, SafetyLevel::SeekProfessionalAdvice);
    let alone = evaluate(Some(&log("", "", "", "urinary_burning", 0)));
    // burning alone falls through to monitor? No: no symptoms, pain 0 -> LOW
    assert_eq!(alone.level, SafetyLevel::Low);
}

#[test]
fn vaginal_irritation_is_review_not_seek() {
    let e = evaluate(Some(&log("", "", "vaginal_itching", "", 0)));
    assert_eq!(e.level, SafetyLevel::Review);
    assert_eq!(e.message, "Vaginal irritation or discharge changes were recorded.");
    assert!(!e.is_red_flag);
}

#[test]
fn any_symptom_or_moderate_pain_is_monitor() {
    let e = evaluate(Some(&log("", "headache", "", "", 0)));
    assert_eq!(e.level, SafetyLevel::Monitor);
    assert_eq!(e.title, "Symptoms to monitor");
    let p = evaluate(Some(&log("", "", "", "", 3)));
    assert_eq!(p.level, SafetyLevel::Monitor);
    let n = evaluate(Some(&log("", "", "", "", 2)));
    assert_eq!(n.level, SafetyLevel::Low);
}

#[test]
fn csv_values_normalize_case_and_spaces() {
    let e = evaluate(Some(&log(" unusually_heavy ", "", "", "", 0)));
    assert_eq!(e.level, SafetyLevel::SeekProfessionalAdvice);
}

// ---------- evaluate_input ----------

#[test]
fn blank_input_returns_base_evaluation() {
    let base = evaluate_input("   ", Some(&log("", "fainting", "", "", 0)));
    assert_eq!(base.level, SafetyLevel::Urgent);
    assert_eq!(base.message, "A fainting episode was recorded.");
    let low = evaluate_input("", None);
    assert_eq!(low.level, SafetyLevel::Low);
}

#[test]
fn negated_symptom_is_not_promoted() {
    let e = evaluate_input("I am not fainting", None);
    // negated -> no candidate -> limitedAssessment(base LOW) -> REVIEW limited
    assert_eq!(e.level, SafetyLevel::Review);
    assert_eq!(e.title, "Safety assessment limited");
    assert_eq!(
        e.message,
        "This local check cannot reliably assess every symptom description or language. No matched warning does not mean symptoms are safe."
    );
}

#[test]
fn negation_stops_at_conjunction_so_second_symptom_survives() {
    let e = evaluate_input("no headache but I fainted today", None);
    assert_eq!(e.level, SafetyLevel::Urgent);
    assert_eq!(e.message, "A fainting episode was mentioned.");
}

#[test]
fn keyword_suffix_matching_catches_inflections() {
    // "fainted" matches term "faint" via the trailing [a-z]* in the source regex
    let e = evaluate_input("i fainted at work", None);
    assert_eq!(e.level, SafetyLevel::Urgent);
}

#[test]
fn mentioned_heavy_and_severe_pain_is_urgent() {
    let e = evaluate_input("heavy bleeding and severe cramps", None);
    assert_eq!(e.level, SafetyLevel::Urgent);
    assert_eq!(e.message, "Unusually heavy bleeding and severe pain were mentioned together.");
}

#[test]
fn existing_urgent_log_is_never_downgraded_by_calm_input() {
    let e = evaluate_input("feeling fine today", Some(&log("", "fainting", "", "", 0)));
    // The saved fainting symptom feeds the candidate's `fainting` flag too, so
    // the candidate is also URGENT; the source's ordinal tie keeps the
    // candidate, i.e. the "mentioned" wording, not the base "recorded" one.
    assert_eq!(e.level, SafetyLevel::Urgent);
    assert_eq!(e.message, "A fainting episode was mentioned.");
}

#[test]
fn limited_assessment_appends_to_non_low_base_message() {
    let e = evaluate_input("feeling okay", Some(&log("", "headache", "", "", 0)));
    // base MONITOR beats candidate REVIEW? ordinal: MONITOR(1) < REVIEW(2) ->
    // candidate REVIEW wins, with base message prepended.
    assert_eq!(e.level, SafetyLevel::Review);
    assert!(e.message.starts_with("Symptoms were recorded without an urgent combination. "));
    assert!(e.message.ends_with("No matched warning does not mean symptoms are safe."));
}

#[test]
fn urinary_mentioned_combination_seeks() {
    let e = evaluate_input("burning urination and peeing often", None);
    assert_eq!(e.level, SafetyLevel::SeekProfessionalAdvice);
    assert_eq!(e.message, "Several urinary symptoms were mentioned together.");
}
