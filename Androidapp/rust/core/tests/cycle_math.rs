//! Cycle math: boundary and adversarial fixtures against the ported Kotlin
//! semantics. All expectations derived from the source formulas, not invented.

use chrono::NaiveDate;
use cyclecare_core::cycle_math::*;

fn d(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}

const STD: (&str, i32, i32, i32) = ("2026-10-01", 28, 5, 14); // start, cycle, duration, luteal

#[test]
fn blank_or_unparseable_anchor_yields_no_prediction() {
    assert!(calculate_day_prediction(d("2026-10-05"), "", 28, 5, 14).is_none());
    assert!(calculate_day_prediction(d("2026-10-05"), "not-a-date", 28, 5, 14).is_none());
    assert!(calculate_day_prediction(d("2026-10-05"), "2026-02-30", 28, 5, 14).is_none());
}

#[test]
fn anchor_day_is_cycle_day_one_menstrual() {
    let p = calculate_day_prediction(d("2026-10-01"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert_eq!(p.day_of_cycle, 1);
    assert_eq!(p.phase, CyclePhase::Menstrual);
    assert!(p.is_period);
    assert!(!p.is_fertile);
}

#[test]
fn standard_28_day_layout() {
    // cycle 28, duration 5, luteal 14 -> ovulation day 14, fertile 10..=15
    let ovu = calculate_day_prediction(d("2026-10-14"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert_eq!(ovu.day_of_cycle, 14);
    assert!(ovu.is_ovulation);
    assert!(ovu.is_fertile);
    assert_eq!(ovu.phase, CyclePhase::Ovulation);
    let f_start = calculate_day_prediction(d("2026-10-10"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert!(f_start.is_fertile);
    let f_end = calculate_day_prediction(d("2026-10-15"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert!(f_end.is_fertile);
    let after = calculate_day_prediction(d("2026-10-16"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert!(!after.is_fertile);
    assert_eq!(after.phase, CyclePhase::Luteal);
    let before = calculate_day_prediction(d("2026-10-09"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert_eq!(before.phase, CyclePhase::Follicular);
    assert!(!before.is_fertile);
}

#[test]
fn wraps_to_next_cycle() {
    let p = calculate_day_prediction(d("2026-10-29"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert_eq!(p.day_of_cycle, 1, "day 29 of a 28-day cycle wraps to day 1");
    assert!(p.is_period);
}

#[test]
fn dates_before_the_anchor_use_floor_mod_not_truncation() {
    // 3 days before the anchor: floorMod(-3, 28) = 25 -> cycle day 26 (Luteal).
    // A truncating modulo would give day -2 and misbehave.
    let p = calculate_day_prediction(d("2026-09-28"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert_eq!(p.day_of_cycle, 26);
    assert_eq!(p.phase, CyclePhase::Luteal);
}

#[test]
fn settings_are_clamped_to_safe_bounds() {
    // cycle length below 21 clamps to 21; above 45 clamps to 45.
    let p = calculate_day_prediction(d("2026-10-22"), STD.0, 10, STD.2, STD.3).unwrap();
    assert_eq!(p.day_of_cycle, 1, "clamped 21-day cycle wraps at day 22");
    let p = calculate_day_prediction(d("2026-11-16"), STD.0, 90, STD.2, STD.3).unwrap();
    assert_eq!(p.day_of_cycle, 2, "clamped 45-day cycle: day 47 -> day 2");
}

#[test]
fn short_cycle_clamps_ovulation_above_period_end() {
    // cycle 21, duration 10 (clamped), luteal 16 (clamped): 21-16=5 < 10+2
    // -> ovulation pinned to day 12, fertile 11..=13.
    let ovu = calculate_day_prediction(d("2026-10-12"), STD.0, 21, 10, 16).unwrap();
    assert_eq!(ovu.day_of_cycle, 12);
    assert!(ovu.is_ovulation);
    let fertile_start = calculate_day_prediction(d("2026-10-11"), STD.0, 21, 10, 16).unwrap();
    assert!(fertile_start.is_fertile);
}

#[test]
fn confidence_labels_are_honest_constants() {
    let p = calculate_day_prediction(d("2026-10-14"), STD.0, STD.1, STD.2, STD.3).unwrap();
    assert_eq!(p.fertility_confidence, "Calendar Estimate");
    assert_eq!(p.pregnancy_chance, "Not calculated from calendar dates");
}

#[test]
fn pair_validation_hard_errors_only_chronology_and_future() {
    let today = d("2026-11-02");
    assert!(!validate_pair(None, Some(today), today, false).is_valid);
    assert!(!validate_pair(Some(today), None, today, false).is_valid);
    let back = validate_pair(Some(d("2026-10-10")), Some(d("2026-10-05")), today, false);
    assert!(!back.is_valid);
    assert_eq!(back.error_message, Some("Period end date cannot be earlier than start date"));
    let future = validate_pair(Some(d("2026-11-03")), Some(d("2026-11-04")), today, false);
    assert!(!future.is_valid);
    assert_eq!(future.error_message, Some("Period dates cannot be in the future"));
}

#[test]
fn duration_boundaries_seven_ok_eight_confirms() {
    let today = d("2026-11-02");
    let seven = validate_pair(Some(d("2026-10-01")), Some(d("2026-10-07")), today, false);
    assert!(seven.is_valid);
    assert_eq!(seven.duration_days, 7);
    assert!(seven.warning_message.is_none(), "7 days is the ACOG-grounded typical ceiling");
    let eight = validate_pair(Some(d("2026-10-01")), Some(d("2026-10-08")), today, false);
    assert!(eight.is_valid, "long periods are never rejected");
    assert_eq!(eight.duration_days, 8);
    assert!(eight.warning_message.is_some());
}

#[test]
fn same_day_period_is_one_day_and_valid() {
    let today = d("2026-11-02");
    let one = validate_pair(Some(today), Some(today), today, false);
    assert!(one.is_valid);
    assert_eq!(one.duration_days, 1);
}

#[test]
fn extreme_durations_stay_confirmable_never_rejected() {
    let today = d("2026-11-02");
    let long = validate_pair(Some(d("2026-01-01")), Some(d("2026-10-01")), today, false);
    assert!(long.is_valid, "prolonged bleeding can be real at any length");
    assert_eq!(long.duration_days, 274);
    assert!(long.warning_message.unwrap().contains("274 days"));
}

#[test]
fn ongoing_message_is_firmer_than_ended() {
    let ongoing = unusual_message(9, true);
    let ended = unusual_message(9, false);
    assert!(ongoing.contains("so far") && ongoing.contains("speaking with a healthcare professional soon"));
    assert!(ended.contains("mentioning to a healthcare professional"));
    assert!(!ended.contains("so far"));
}

#[test]
fn string_helper_matches_kotlin_parse_rules() {
    assert!(validate_period_date_strings(Some("2026-10-01"), Some("2026-10-05")));
    assert!(!validate_period_date_strings(Some("2026-10-05"), Some("2026-10-01")));
    assert!(!validate_period_date_strings(Some(""), Some("2026-10-01")));
    assert!(!validate_period_date_strings(Some("  "), Some("2026-10-01")));
    assert!(!validate_period_date_strings(None, Some("2026-10-01")));
    assert!(!validate_period_date_strings(Some("2026-13-01"), Some("2026-10-01")));
    assert!(!validate_period_date_strings(Some("2026-02-30"), Some("2026-10-01")));
}

#[test]
fn leap_day_anchor_behaves() {
    let p = calculate_day_prediction(d("2024-03-01"), "2024-02-28", 28, 5, 14).unwrap();
    assert_eq!(p.day_of_cycle, 3, "Feb 28 + 2 days over a leap day = cycle day 3");
}
