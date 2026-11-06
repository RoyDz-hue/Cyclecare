//! LearningEngine port tests; expectations from source strings and orderings.

use chrono::NaiveDate;
use cyclecare_core::cycle_math::{CyclePhase, DayPrediction};
use cyclecare_core::learning::*;
use cyclecare_core::safety::SafetyLevel;

fn settings() -> LearningSettings {
    LearningSettings {
        is_onboarding_completed: true,
        average_cycle_length: 28,
        period_duration: 5,
        luteal_phase_days: 14,
        last_period_start_date: "2026-01-01".to_string(),
    }
}

fn llog(date: &str, flow: &str, symptoms: &str, pain: i32) -> LearningLog {
    LearningLog {
        date_string: date.to_string(),
        flow_level: flow.to_string(),
        energy_level: String::new(),
        symptoms_csv: symptoms.to_string(),
        pain_intensity: pain,
        notes: String::new(),
    }
}

fn ids(obs: &[LearnedObservation]) -> Vec<&str> {
    obs.iter().map(|o| o.id.as_str()).collect()
}

#[test]
fn completed_setup_emits_settings_then_setup_notes() {
    let profile = LearningProfile {
        is_completed: true,
        typical_symptoms_csv: "cramps, headache, cramps".to_string(),
        approximate_period_choice: String::new(),
    };
    let obs = compile_learned_observations(&settings(), &[], Some(&profile));
    assert_eq!(
        ids(&obs),
        vec!["starting_cycle_settings", "setup_notes", "still_learning_no_logs"]
    );
    assert_eq!(
        obs[0].description,
        "Current settings: 28 days per cycle and 5 days for bleeding. These are setup values, not averages measured from your history."
    );
    // dedup within profile csv; display labels joined with ", "
    assert_eq!(
        obs[1].description,
        "You marked Cramps, Headache as typical during setup. These are self-reported setup notes, not daily log entries."
    );
}

#[test]
fn incomplete_setup_and_no_profile_skips_first_two_blocks() {
    let mut s = settings();
    s.is_onboarding_completed = false;
    let obs = compile_learned_observations(&s, &[], None);
    assert_eq!(ids(&obs), vec!["still_learning_no_logs"]);
}

#[test]
fn symptom_top_five_sorted_count_desc_then_key_asc() {
    // counts: cramps 3, headache 2, acne 1, bloating 1, fatigue 1, nausea 1, dizziness 1
    let logs = vec![
        llog("2026-01-02", "", "cramps,headache", 0),
        llog("2026-01-03", "", "cramps,headache", 0),
        llog("2026-01-04", "", "cramps,acne", 0),
        llog("2026-01-05", "", "bloating", 0),
        llog("2026-01-06", "", "fatigue", 0),
        llog("2026-01-07", "", "nausea", 0),
        llog("2026-01-08", "", "dizziness", 0),
    ];
    let mut s = settings();
    s.is_onboarding_completed = false;
    let obs = compile_learned_observations(&s, &logs, None);
    let id_list = ids(&obs);
    // top5: cramps(3), headache(2), then 1-count ties alphabetical: acne, bloating, dizziness
    assert_eq!(
        &id_list[..5],
        [
            "logged_symptom_cramps",
            "logged_symptom_headache",
            "logged_symptom_acne",
            "logged_symptom_bloating",
            "logged_symptom_dizziness"
        ]
    );
    assert!(!id_list.contains(&"logged_symptom_fatigue"));
    assert!(id_list.contains(&"log_coverage"));
    assert!(obs.iter().any(|o| o.headline == "7 saved log dates"));
    // same-symptom duplicates within one log count once
    let dup = vec![llog("2026-01-02", "", "cramps,cramps", 0)];
    let obs2 = compile_learned_observations(&s, &dup, None);
    let cramps = obs2.iter().find(|o| o.id == "logged_symptom_cramps").unwrap();
    assert!(cramps.description.contains("on 1 of 1 saved log dates"));
}

#[test]
fn sparse_logs_flag_under_three_dates() {
    let mut s = settings();
    s.is_onboarding_completed = false;
    let obs = compile_learned_observations(&s, &[llog("2026-01-02", "", "cramps", 0)], None);
    let id_list = ids(&obs);
    assert!(id_list.contains(&"still_learning_sparse_logs"));
    assert!(obs.iter().any(|o| o.headline == "1 saved log date"));
}

#[test]
fn phase_summaries_need_anchor_and_three_logged_days() {
    let mut s = settings();
    s.is_onboarding_completed = false;
    // 3 menstrual days (cycle days 1-5) + 2 luteal days
    let logs = vec![
        llog("2026-01-01", "", "cramps", 0),
        llog("2026-01-02", "", "cramps", 0),
        llog("2026-01-03", "", "headache", 0),
        llog("2026-01-20", "", "cramps", 0),
        llog("2026-01-21", "", "cramps", 0),
    ];
    let obs = compile_learned_observations(&s, &logs, None);
    let phase_obs: Vec<&LearnedObservation> =
        obs.iter().filter(|o| o.id.starts_with("phase_symptom_")).collect();
    // only the menstrual phase has >= 3 logged days; cramps 2, headache 1
    assert_eq!(phase_obs.len(), 2);
    assert_eq!(phase_obs[0].id, "phase_symptom_menstrual_cramps");
    assert_eq!(phase_obs[1].id, "phase_symptom_menstrual_headache");
    assert!(phase_obs[0]
        .description
        .contains("on 2 of 3 logged days in your estimated menstrual phase"));
    // blank anchor: no phase summaries
    s.last_period_start_date = String::new();
    let obs2 = compile_learned_observations(&s, &logs, None);
    assert!(!ids(&obs2).iter().any(|i| i.starts_with("phase_symptom_")));
}

#[test]
fn doctor_summary_top3_then_pain_then_flow_deduped() {
    let logs = vec![
        llog("2026-01-02", "HEAVY", "cramps", 8),
        llog("2026-01-03", "HEAVY", "cramps,headache", 0),
        llog("2026-01-04", "", "headache,nausea", 6),
        llog("2026-01-05", "light", "", 0), // lowercase flow: exact-case match -> not heavy
    ];
    let q = generate_doctor_summary(&settings(), &logs);
    // top-3 symptoms (cramps 2, headache 2, nausea 1) + pain + flow = 5
    assert_eq!(q.len(), 5);
    assert_eq!(
        q[0],
        "I recorded Cramps on 2 saved dates. Is there anything specific I should monitor or bring to a follow-up?"
    );
    assert_eq!(
        q[1],
        "I recorded Headache on 2 saved dates. Is there anything specific I should monitor or bring to a follow-up?"
    );
    assert_eq!(
        q[2],
        "I recorded Nausea on 1 saved date. Is there anything specific I should monitor or bring to a follow-up?"
    );
    assert_eq!(
        q[3],
        "I recorded pain up to 8/10 on 1 saved date. What follow-up, if any, would you recommend?"
    );
    assert_eq!(
        q[4],
        "I recorded heavy flow on 2 saved dates. What details would be useful to track or discuss?"
    );
    // empty logs: no prompts
    assert!(generate_doctor_summary(&settings(), &[]).is_empty());
}

#[test]
fn explain_my_body_null_prediction_is_no_estimate_verbatim() {
    let r = synthesize_explain_my_body(None);
    assert_eq!(r.summary, "No cycle estimate is available yet.");
    assert_eq!(r.safety_level, SafetyLevel::Low);
    assert!(r.what_supports_that.contains("no saved date anchor"));
}

#[test]
fn explain_my_body_with_prediction_is_calendar_estimate_only() {
    let p = DayPrediction {
        date: NaiveDate::parse_from_str("2026-01-14", "%Y-%m-%d").unwrap(),
        day_of_cycle: 14,
        phase: CyclePhase::Ovulation,
        is_period: false,
        is_fertile: true,
        is_ovulation: true,
        fertility_confidence: "Calendar Estimate",
        pregnancy_chance: "Moderate",
    };
    let r = synthesize_explain_my_body(Some(&p));
    assert_eq!(r.summary, "Calendar estimate: cycle day 14, Fertile & Ovulation.");
    assert_eq!(
        r.what_might_be_happening,
        "Ovulation timing varies. A calendar window cannot confirm whether or when ovulation occurred."
    );
    assert!(r.what_you_can_do.contains("should not be used as contraception"));
}
