//! JVM-probe corpus for generateClinicianReportText + generateMonthlyTimeline
//! (probe run 2026-11-02, kotlinc 2.1.0/JRE 21, default locale en_US).
//! The "Generated:" line reflects the probed date; the port takes `today`
//! explicitly so the corpus stays deterministic.

use chrono::NaiveDate;
use cyclecare_core::learning::{
    generate_clinician_report_text, generate_monthly_timeline, LearningLog, LearningProfile,
    LearningSettings,
};

fn settings(avg: i32, dur: i32, start: &str) -> LearningSettings {
    LearningSettings {
        is_onboarding_completed: true,
        average_cycle_length: avg,
        period_duration: dur,
        luteal_phase_days: 14,
        last_period_start_date: start.to_string(),
    }
}

fn llog(date: &str, flow: &str, energy: &str, symptoms: &str, pain: i32, notes: &str) -> LearningLog {
    LearningLog {
        date_string: date.to_string(),
        flow_level: flow.to_string(),
        energy_level: energy.to_string(),
        symptoms_csv: symptoms.to_string(),
        pain_intensity: pain,
        notes: notes.to_string(),
    }
}

fn profile(choice: &str) -> LearningProfile {
    LearningProfile {
        is_completed: true,
        typical_symptoms_csv: String::new(),
        approximate_period_choice: choice.to_string(),
    }
}

fn logs_b() -> Vec<LearningLog> {
    vec![
        llog("2026-09-12", "HEAVY", "LOW", "cramps, fatigue", 8, "  rough day  "),
        llog("2026-09-13", "MODERATE", "LOW", "cramps", 3, ""),
        llog("2026-10-01", "LIGHT", "HIGH", "fatigue, cramps", 0, ""),
        llog("2026-10-02", "", "HIGH", "headache", 7, ""),
        llog("2026-10-03", "none", "NORMAL", "", 0, "ok"),
    ]
}

const TODAY: &str = "2026-11-02";
fn today() -> NaiveDate {
    NaiveDate::parse_from_str(TODAY, "%Y-%m-%d").unwrap()
}

#[test]
fn fixture_a_empty_logs_exact_date() {
    let text = generate_clinician_report_text(&settings(28, 5, "2026-10-05"), &[], Some(&profile("EXACT")), today());
    let expected = "CYCLECARE \u{2014} USER-ENTERED SUMMARY\n\
Generated: Nov 2, 2026\n\
This summary reproduces saved information. It is not a diagnosis or a clinical assessment.\n\
\n\
SETUP SETTINGS (not measured averages)\n\
\u{2022} Cycle-length setting: 28 days\n\
\u{2022} Bleeding-duration setting: 5 days\n\
\u{2022} Last period start date entered: 2026-10-05\n\
\n\
SAVED DAILY LOGS\n\
\u{2022} No daily log entries are saved.\n\
\u{2022} No symptom, pain, flow, sleep, mood, or energy history is inferred.";
    assert_eq!(text, expected);
}

#[test]
fn fixture_b_two_months_full() {
    let text = generate_clinician_report_text(&settings(31, 4, "2026-09-12"), &logs_b(), None, today());
    let expected = "CYCLECARE \u{2014} USER-ENTERED SUMMARY\n\
Generated: Nov 2, 2026\n\
This summary reproduces saved information. It is not a diagnosis or a clinical assessment.\n\
\n\
SETUP SETTINGS (not measured averages)\n\
\u{2022} Cycle-length setting: 31 days\n\
\u{2022} Bleeding-duration setting: 4 days\n\
\u{2022} Last period start date entered: 2026-09-12\n\
\n\
SAVED DAILY LOGS (5 dates)\n\
\u{2022} Date range: 2026-09-12 to 2026-10-03\n\
\u{2022} Flow entries: HEAVY (1), LIGHT (1), MODERATE (1)\n\
\u{2022} Symptom tags: Cramps (3), Fatigue (2), Headache (1)\n\
\u{2022} Pain ratings entered: 2026-09-12 8/10, 2026-09-13 3/10, 2026-10-02 7/10\n\
\u{2022} Notes entered:\n  - 2026-09-12: rough day\n  - 2026-10-03: ok\n\
\n\
OPTIONAL DISCUSSION PROMPTS FROM SAVED ENTRIES\n\
1. I recorded Cramps on 3 saved dates. Is there anything specific I should monitor or bring to a follow-up?\n\
2. I recorded Fatigue on 2 saved dates. Is there anything specific I should monitor or bring to a follow-up?\n\
3. I recorded Headache on 1 saved date. Is there anything specific I should monitor or bring to a follow-up?\n\
4. I recorded pain up to 8/10 on 2 saved dates. What follow-up, if any, would you recommend?\n\
5. I recorded heavy flow on 1 saved date. What details would be useful to track or discuss?";
    assert_eq!(text, expected);
}

#[test]
fn fixture_c_blank_start_singular_quirks() {
    let logs = logs_b().into_iter().take(1).collect::<Vec<_>>();
    let text = generate_clinician_report_text(&settings(28, 5, "  "), &logs, Some(&profile("EXACT")), today());
    // Kotlin pluralization quirk: "(1 dates)", "1 saved date" singular correct.
    assert!(text.contains("\u{2022} Last period start date: Not provided"));
    assert!(text.contains("SAVED DAILY LOGS (1 dates)"));
    assert!(text.contains("1. I recorded Cramps on 1 saved date."));
}

#[test]
fn fixture_d_approximate_branch() {
    let text = generate_clinician_report_text(&settings(28, 5, "2026-10-20"), &[], Some(&profile("APPROXIMATE:ONE_TO_TWO_WEEKS")), today());
    assert!(text.contains("\u{2022} Last period start date: 2026-10-20 (estimated from the approximate range selected during setup)"));
}

#[test]
fn timeline_matches_probe_including_first_seen_tiebreak() {
    let tl = generate_monthly_timeline(&logs_b());
    assert_eq!(tl.len(), 2);
    assert_eq!(tl[0].month_title, "October 2026");
    assert_eq!(tl[0].period_bleeding_days, 1);
    assert_eq!(tl[0].dominant_energy, "HIGH");
    // cramps/fatigue/headache all count 1: FIRST-SEEN wins ("Fatigue"),
    // not alphabetical ("Cramps") - locks Kotlin maxByOrNull semantics.
    assert_eq!(tl[0].dominant_symptom, "Fatigue");
    assert_eq!(tl[1].month_title, "September 2026");
    assert_eq!(tl[1].period_bleeding_days, 2);
    assert_eq!(tl[1].dominant_energy, "LOW");
    assert_eq!(tl[1].dominant_symptom, "Cramps");
    // Parity quirks: cycle_length_days always 0, pattern_noticed always null.
    assert!(tl.iter().all(|e| e.cycle_length_days == 0 && e.pattern_noticed.is_none()));
    assert!(generate_monthly_timeline(&[]).is_empty());
}
