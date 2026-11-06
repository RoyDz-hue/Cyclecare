//! Corpus for pregnancy.rs. Date semantics JVM-probed 2026-11-02
//! (kotlinc 2.1.0/JRE 21): plusMonths clamps to the target month's last day.
//! Branch expectations derived line-by-line from PregnancyScreen.kt.

use chrono::NaiveDate;
use cyclecare_core::pregnancy::{compute_pregnancy_state, plus_months_clamped, MILESTONES};

fn d(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}

#[test]
fn plus_months_matches_java_probe() {
    assert_eq!(plus_months_clamped(d("2026-11-02"), 6), d("2027-05-02"));
    assert_eq!(plus_months_clamped(d("2026-08-31"), 6), d("2027-02-28"));
    assert_eq!(plus_months_clamped(d("2023-08-31"), 6), d("2024-02-29"));
}

#[test]
fn mid_pregnancy_week_and_trimester() {
    // due = today + 100d -> remaining 100, completed 180, week 25, trimester 2,
    // milestone Corn (week 24 <= 25 < 28).
    let s = compute_pregnancy_state(d("2026-11-02"), d("2027-02-10"));
    assert_eq!(s.days_remaining, 100);
    assert_eq!(s.days_completed, 180);
    assert_eq!(s.current_week, 25);
    assert_eq!(s.current_trimester, 2);
    assert_eq!(s.milestone.fruit, "Corn");
}

#[test]
fn clamps_hold_at_extremes() {
    // Due date far past: remaining 0, completed 280, week 40, trimester 3, Watermelon.
    let s = compute_pregnancy_state(d("2026-11-02"), d("2026-01-01"));
    assert_eq!((s.days_remaining, s.days_completed, s.current_week, s.current_trimester), (0, 280, 40, 3));
    assert_eq!(s.milestone.fruit, "Watermelon");
    // Due date far future: completed clamps to 0, week clamps to 1 (not 0),
    // milestone falls back to first (Poppy Seed).
    let s2 = compute_pregnancy_state(d("2026-11-02"), d("2028-01-01"));
    assert_eq!((s2.days_completed, s2.current_week, s2.current_trimester), (0, 1, 1));
    assert_eq!(s2.milestone.fruit, "Poppy Seed");
    // Week 13 boundary: trimester 1; week 14: trimester 2.
    let w13 = compute_pregnancy_state(d("2026-11-02"), d("2026-11-02") + chrono::Duration::days(280 - 13 * 7));
    assert_eq!((w13.current_week, w13.current_trimester), (13, 1));
    let w14 = compute_pregnancy_state(d("2026-11-02"), d("2026-11-02") + chrono::Duration::days(280 - 14 * 7));
    assert_eq!((w14.current_week, w14.current_trimester), (14, 2));
    assert_eq!(w14.milestone.fruit, "Lime");
}

#[test]
fn milestone_table_is_verbatim() {
    assert_eq!(MILESTONES.len(), 10);
    assert_eq!(MILESTONES[0].emoji, "\u{1F331}");
    assert_eq!(MILESTONES[9].fruit, "Watermelon");
    assert_eq!(MILESTONES[9].length, "51 cm");
}
