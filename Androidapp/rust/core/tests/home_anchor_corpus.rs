//! JVM-probe corpus for home_anchor (kotlinc 2.1.0/JRE 21, /tmp/anchorprobe).
//! Rows c1-c15 classify choices exactly as the JVM printed; n1-n5 lock the
//! strictly-after-today stepping and the 21-day clamp.

use cyclecare_core::home_anchor::{classify_choices, compute_anchor_state, next_period_after};
use chrono::NaiveDate;

fn d(s: &str) -> NaiveDate { NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap() }

#[test]
fn classify_matches_jvm_probe() {
    // (date_choice, cycle_length_choice, unknown_date, unknown_cl, approx)
    let rows: &[(&str, &str, bool, bool, bool)] = &[
        (" unknown ", "28", true, false, false),
        ("UNKNOWN", "Regular", true, false, false),
        ("Don't know", "28", true, false, false),
        ("Don\u{2019}t know", "28", true, false, false),
        ("it varies", "28", true, false, false),
        ("approximate:2026-10-01", "28", false, false, true),
        ("today", "28", false, false, true),
        ("About a week ago", "28", false, false, true),
        ("2026-10-01", "changes often", false, true, false),
        ("2026-10-01", "I don't know", false, true, false),
        ("2026-10-01", "", false, true, false),
        ("", "28", true, false, false),
        ("\u{00A0}unknown", "28", true, false, false), // NBSP trimmed (Kotlin trim = isWhitespace)
        ("\u{00A0}today", "28", false, false, true),
        ("exact:2026-10-01", "28", false, false, false),
    ];
    for (i, (dc, clc, ud, ucl, ap)) in rows.iter().enumerate() {
        assert_eq!(
            classify_choices(Some(dc), Some(clc)),
            (*ud, *ucl, *ap),
            "row c{}",
            i + 1
        );
    }
}

#[test]
fn next_period_matches_jvm_probe() {
    // n1: last start == today steps a full cycle (strictly after).
    assert_eq!(next_period_after(d("2026-11-02"), d("2026-11-02"), 28), d("2026-11-30"));
    // n2: already after today stays.
    assert_eq!(next_period_after(d("2026-11-05"), d("2026-11-02"), 28), d("2026-11-05"));
    // n3: steps forward by 28 until after today.
    assert_eq!(next_period_after(d("2026-09-04"), d("2026-11-02"), 28), d("2026-11-27"));
    // n4: cycle length clamps to 21 before stepping (re-probed with the
    // production coerceIn applied: 2026-09-04 + 21d steps -> 2026-11-06).
    assert_eq!(next_period_after(d("2026-09-04"), d("2026-11-02"), 10), d("2026-11-06"));
    // n5
    assert_eq!(next_period_after(d("2026-10-05"), d("2026-10-31"), 28), d("2026-11-02"));
}

#[test]
fn anchor_state_end_to_end() {
    // No anchor when the date choice is unknown: everything zeroed.
    let s = compute_anchor_state("2026-10-05", Some("unknown"), Some("28"), 28, d("2026-10-31"));
    assert!(!s.has_cycle_anchor && s.days_until_next_period == 0 && s.next_period_date.is_none());
    // Blank settings date kills the anchor even with exact choices.
    let s = compute_anchor_state("", Some("exact:2026-10-05"), Some("28"), 28, d("2026-10-31"));
    assert!(!s.has_cycle_anchor);
    // Exact anchor: days until = 2, approximate = false.
    let s = compute_anchor_state("2026-10-05", Some("exact:2026-10-05"), Some("28"), 28, d("2026-10-31"));
    assert!(s.has_cycle_anchor && !s.cycle_start_is_approximate);
    assert_eq!(s.days_until_next_period, 2);
    assert_eq!(s.next_period_date, Some(d("2026-11-02")));
    // Approximate anchor: "About a week ago".
    let s = compute_anchor_state("2026-10-05", Some("About a week ago"), Some("28"), 28, d("2026-10-31"));
    assert!(s.has_cycle_anchor && s.cycle_start_is_approximate);
}

#[test]
fn phase_display_constants_verbatim() {
    use cyclecare_core::cycle_math::CyclePhase;
    assert_eq!(CyclePhase::Menstrual.kotlin_title(), "Menstruation");
    assert_eq!(CyclePhase::Follicular.kotlin_title(), "Follicular Phase");
    assert_eq!(CyclePhase::Ovulation.kotlin_title(), "Fertile & Ovulation");
    assert_eq!(CyclePhase::Luteal.kotlin_title(), "Luteal Phase");
    assert_eq!(CyclePhase::Menstrual.kotlin_color_hex(), 0xFF8A3B4A);
    assert_eq!(CyclePhase::Follicular.kotlin_color_hex(), 0xFF55745F);
    assert_eq!(CyclePhase::Ovulation.kotlin_color_hex(), 0xFF9B6B2C);
    assert_eq!(CyclePhase::Luteal.kotlin_color_hex(), 0xFF695873);
}
