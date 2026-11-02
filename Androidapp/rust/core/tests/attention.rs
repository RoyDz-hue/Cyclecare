//! AttentionEngine port tests: priority order, verbatim strings, sleep math.

use chrono::NaiveDate;
use cyclecare_core::attention::*;
use cyclecare_core::cycle_math::{CyclePhase, DayPrediction};
use cyclecare_core::safety::{evaluate as safety_evaluate, SafetyEvaluation, SafetyLevel, SafetyLog};

fn d(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}

fn prediction(day: i32, is_period: bool, is_fertile: bool) -> DayPrediction {
    DayPrediction {
        date: d("2026-02-01"),
        day_of_cycle: day,
        phase: CyclePhase::Follicular,
        is_period,
        is_fertile,
        is_ovulation: false,
        fertility_confidence: "Calendar Estimate",
        pregnancy_chance: "Low",
    }
}

fn low_safety() -> SafetyEvaluation {
    safety_evaluate(None)
}

fn urgent_safety() -> SafetyEvaluation {
    safety_evaluate(Some(&SafetyLog {
        flow_level: String::new(),
        symptoms_csv: "fainting".to_string(),
        vaginal_health_csv: String::new(),
        urinary_health_csv: String::new(),
        pain_intensity: 0,
    }))
}

fn alog(date: &str, sleep: f32, quality: &str) -> AttentionLog {
    AttentionLog {
        date_string: date.to_string(),
        sleep_hours: sleep,
        sleep_quality: quality.to_string(),
        symptoms_csv: String::new(),
        pain_intensity: 0,
    }
}

#[test]
fn safety_urgent_is_never_preempted() {
    let item = evaluate_today_priority(
        Some(&prediction(9, false, true)),
        &urgent_safety(),
        28,
        &[alog("2026-01-31", 5.0, "POOR")],
        d("2026-02-01"),
    )
    .unwrap();
    assert_eq!(item.id, "safety_priority");
    assert_eq!(item.category, "Safety");
    assert!(item.is_urgent);
    assert_eq!(item.headline, "Urgent medical attention advised");
}

#[test]
fn seek_professional_advice_also_wins_priority_one() {
    let seek = safety_evaluate(Some(&SafetyLog {
        flow_level: "UNUSUALLY_HEAVY".to_string(),
        symptoms_csv: String::new(),
        vaginal_health_csv: String::new(),
        urinary_health_csv: String::new(),
        pain_intensity: 0,
    }));
    assert_eq!(seek.level, SafetyLevel::SeekProfessionalAdvice);
    let item = evaluate_today_priority(None, &seek, 28, &[], d("2026-02-01")).unwrap();
    assert_eq!(item.id, "safety_priority");
}

#[test]
fn null_prediction_and_calm_inputs_yield_nothing() {
    assert!(evaluate_today_priority(None, &low_safety(), 28, &[], d("2026-02-01")).is_none());
}

#[test]
fn period_sleep_taper_only_within_1_to_3_days_and_low_sleep() {
    let today = d("2026-02-01");
    // day 25 of 28 -> 3 days until period; recent sleep 5.5 poor quality
    let logs = vec![alog("2026-01-30", 5.5, "POOR"), alog("2026-01-29", 5.0, "POOR")];
    let item = evaluate_today_priority(Some(&prediction(25, false, false)), &low_safety(), 28, &logs, today).unwrap();
    assert_eq!(item.id, "period_sleep_taper");
    assert_eq!(item.headline, "Calendar estimate: period in about 3 days");
    assert!(item.message.contains("averaging 5.3 hours"));

    // 4 days away: no taper item
    assert!(evaluate_today_priority(Some(&prediction(24, false, false)), &low_safety(), 28, &logs, today).is_none()
        || evaluate_today_priority(Some(&prediction(24, false, false)), &low_safety(), 28, &logs, today).unwrap().id != "period_sleep_taper");

    // good sleep ~7.5 GOOD quality excluded from the filter -> no item
    let good = vec![alog("2026-01-30", 7.5, "GOOD")];
    assert!(evaluate_today_priority(Some(&prediction(25, false, false)), &low_safety(), 28, &good, today).is_none());
}

#[test]
fn sleep_filter_includes_nondefault_quality_even_at_7_5_hours() {
    // sleepHours == 7.5 exactly but quality POOR -> included in average
    let logs = vec![alog("2026-01-30", 7.5, "POOR")];
    let item = evaluate_today_priority(Some(&prediction(26, false, false)), &low_safety(), 28, &logs, d("2026-02-01"));
    // average 7.5 >= 6.8 -> no taper item
    assert!(item.is_none() || item.unwrap().id != "period_sleep_taper");
    let lowq = vec![alog("2026-01-30", 6.5, "POOR")];
    let item2 = evaluate_today_priority(Some(&prediction(26, false, false)), &low_safety(), 28, &lowq, d("2026-02-01")).unwrap();
    assert_eq!(item2.id, "period_sleep_taper");
}

#[test]
fn fertile_window_notice_has_no_overclaim_verbatim() {
    let item = evaluate_today_priority(
        Some(&prediction(12, false, true)),
        &low_safety(),
        28,
        &[],
        d("2026-02-01"),
    )
    .unwrap();
    assert_eq!(item.id, "fertile_window_notice");
    assert_eq!(item.message, "This calendar estimate places the date within a possible fertile window. It cannot confirm ovulation or prevent pregnancy.");
    assert!(!item.is_urgent);
    // period takes precedence over fertile notice
    let during = evaluate_today_priority(
        Some(&prediction(2, true, true)),
        &low_safety(),
        28,
        &[],
        d("2026-02-01"),
    );
    assert!(during.is_none() || during.unwrap().id != "fertile_window_notice");
}

#[test]
fn day_one_period_is_cycle_reset() {
    let item = evaluate_today_priority(
        Some(&prediction(1, true, false)),
        &low_safety(),
        28,
        &[],
        d("2026-02-01"),
    )
    .unwrap();
    assert_eq!(item.id, "phase_menstrual_start");
    assert_eq!(item.headline, "Welcome to Day 1 of your cycle");
}

#[test]
fn wellbeing_checkin_after_4_silent_days_not_during_period() {
    let today = d("2026-02-10");
    let logs = vec![alog("2026-02-05", 0.0, "")]; // 5 days ago
    let item = evaluate_today_priority(Some(&prediction(15, false, false)), &low_safety(), 28, &logs, today).unwrap();
    assert_eq!(item.id, "wellbeing_checkin");
    // 3 days: no check-in
    let recent = vec![alog("2026-02-08", 0.0, "")];
    assert!(evaluate_today_priority(Some(&prediction(15, false, false)), &low_safety(), 28, &recent, today).is_none());
    // during period: no check-in
    let p = prediction(3, true, false);
    assert!(evaluate_today_priority(Some(&p), &low_safety(), 28, &logs, today).is_none());
}

#[test]
fn strong_baseline_shift_beats_lower_priorities() {
    // Build a pain-intensity STRONG deviation: historical avg 2, recent avg 6.
    let today = d("2026-02-01");
    let mut logs: Vec<AttentionLog> = Vec::new();
    for i in 0..15i64 {
        let date = today - chrono::Duration::days(100 + i * 3);
        let mut l = alog(&date.to_string(), 0.0, "");
        l.pain_intensity = 2;
        logs.push(l);
    }
    for i in 0..5i64 {
        let date = today - chrono::Duration::days(5 + i * 10);
        let mut l = alog(&date.to_string(), 0.0, "");
        l.pain_intensity = 6;
        logs.push(l);
    }
    let item = evaluate_today_priority(Some(&prediction(12, false, true)), &low_safety(), 28, &logs, today).unwrap();
    assert_eq!(item.id, "baseline_shift_strong");
    assert_eq!(item.headline, "Noticeable change: Pelvic Comfort Shift");
    assert_eq!(item.category, "Body Rhythm Shift");
}

#[test]
fn notable_shift_is_suppressed_during_period() {
    let today = d("2026-02-01");
    let mut logs: Vec<AttentionLog> = Vec::new();
    for i in 0..15i64 {
        let date = today - chrono::Duration::days(100 + i * 3);
        let mut l = alog(&date.to_string(), 0.0, "");
        l.pain_intensity = 2;
        logs.push(l);
    }
    for i in 0..5i64 {
        let date = today - chrono::Duration::days(5 + i * 10);
        let mut l = alog(&date.to_string(), 0.0, "");
        l.pain_intensity = 4; // diff 2.0 -> NOTABLE
        logs.push(l);
    }
    // on period: suppressed -> falls to lower priorities
    let p = prediction(2, true, false);
    let item = evaluate_today_priority(Some(&p), &low_safety(), 28, &logs, today);
    assert!(item.is_none() || item.unwrap().id != "baseline_shift_notable");
    // off period: shown
    let p2 = prediction(12, false, false);
    let item2 = evaluate_today_priority(Some(&p2), &low_safety(), 28, &logs, today).unwrap();
    assert_eq!(item2.id, "baseline_shift_notable");
    assert_eq!(item2.headline, "A subtle shift in your rhythm");
}
