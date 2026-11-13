//! Batch month-grid contract tests. Self-consistency is the lock: every
//! batch entry MUST equal the corpus-locked per-date primitive
//! (calculate_day_prediction) for the same date and settings, and the
//! anchor gate MUST mirror getPredictionForDate (:2153-2156).
use cyclecare_core::cycle_math::{calculate_day_prediction, parse_local_date_iso};
use cyclecare_core::cycle_screen_state::{
    build_month_predictions, MonthPredictionsInput,
};

fn input(last_start: &str, date_choice: Option<&str>, cl_choice: Option<&str>, today: &str, month: &str) -> MonthPredictionsInput {
    MonthPredictionsInput {
        last_period_start_date: last_start.to_string(),
        average_cycle_length: 28,
        period_duration: 5,
        luteal_phase_days: 14,
        date_choice: date_choice.map(Into::into),
        cycle_length_choice: cl_choice.map(Into::into),
        today: parse_local_date_iso(today).unwrap(),
        month_first_day: parse_local_date_iso(month).unwrap(),
    }
}

#[test]
fn november_2026_covers_grid_exactly() {
    let m = build_month_predictions(&input("2026-10-05", Some("exact"), Some("28"), "2026-11-03", "2026-11-01")).unwrap();
    assert!(m.has_cycle_anchor);
    // Exactly the 30 grid cells: the source's neighbor lookups are guarded
    // (column/dayNumber bounds) and never leave the displayed month.
    assert_eq!(m.predictions.len(), 30);
    assert_eq!(m.predictions[0].0.to_string(), "2026-11-01");
    assert_eq!(m.predictions[29].0.to_string(), "2026-11-30");
    for w in m.predictions.windows(2) {
        assert_eq!((w[1].0 - w[0].0).num_days(), 1);
    }
}

#[test]
fn batch_equals_per_date_primitive_for_every_date() {
    let m = build_month_predictions(&input("2026-10-05", Some("exact"), Some("28"), "2026-11-03", "2026-11-01")).unwrap();
    for (date, pred) in &m.predictions {
        let direct = calculate_day_prediction(*date, "2026-10-05", 28, 5, 14);
        match (pred, direct) {
            (Some(a), Some(b)) => {
                assert_eq!(a.date, b.date);
                assert_eq!(a.day_of_cycle, b.day_of_cycle);
                assert_eq!(a.phase, b.phase);
                assert_eq!(a.is_period, b.is_period);
                assert_eq!(a.is_fertile, b.is_fertile);
                assert_eq!(a.is_ovulation, b.is_ovulation);
                assert_eq!(a.fertility_confidence, b.fertility_confidence);
                assert_eq!(a.pregnancy_chance, b.pregnancy_chance);
            }
            (None, None) => {}
            _ => panic!("batch/per-date mismatch on {date}"),
        }
    }
}

#[test]
fn no_anchor_empty_like_source_null() {
    // blank anchor (isNotBlank on the raw string) -> no predictions at all
    let m = build_month_predictions(&input("", Some("exact"), Some("28"), "2026-11-03", "2026-11-01")).unwrap();
    assert!(!m.has_cycle_anchor);
    assert!(m.predictions.is_empty());
    // unknown date choice -> same
    let m2 = build_month_predictions(&input("2026-10-05", Some("I don't know"), Some("28"), "2026-11-03", "2026-11-01")).unwrap();
    assert!(!m2.has_cycle_anchor);
    assert!(m2.predictions.is_empty());
    // null choices classify as unknown (source parity: isUnknownDateChoice
    // treats null/blank as unknown)
    let m3 = build_month_predictions(&input("2026-10-05", None, None, "2026-11-03", "2026-11-01")).unwrap();
    assert!(!m3.has_cycle_anchor);
    assert!(m3.predictions.is_empty());
}

#[test]
fn anchor_gate_independent_of_today() {
    let a = build_month_predictions(&input("2026-10-05", Some("exact"), Some("28"), "2026-11-03", "2026-11-01")).unwrap();
    let b = build_month_predictions(&input("2026-10-05", Some("exact"), Some("28"), "2030-06-15", "2026-11-01")).unwrap();
    assert_eq!(a.has_cycle_anchor, b.has_cycle_anchor);
    assert_eq!(a.predictions.len(), b.predictions.len());
}

#[test]
fn february_leap_and_nonleap_lengths() {
    let leap = build_month_predictions(&input("2024-01-10", Some("exact"), Some("28"), "2024-02-10", "2024-02-01")).unwrap();
    assert_eq!(leap.predictions.len(), 29);
    assert_eq!(leap.predictions[0].0.to_string(), "2024-02-01");
    assert_eq!(leap.predictions[28].0.to_string(), "2024-02-29");
    let plain = build_month_predictions(&input("2026-01-10", Some("exact"), Some("28"), "2026-02-10", "2026-02-01")).unwrap();
    assert_eq!(plain.predictions.len(), 28);
    let dec = build_month_predictions(&input("2026-11-20", Some("exact"), Some("28"), "2026-12-10", "2026-12-01")).unwrap();
    assert_eq!(dec.predictions.len(), 31);
    assert_eq!(dec.predictions[30].0.to_string(), "2026-12-31");
}
