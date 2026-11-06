//! JVM-executed oracles for home_anchor::compute_countdowns and the full
//! updateCalculations anchor chain (fixtures/countdowns/, kotlinc 2.1.0 /
//! JDK 17.0.21+9, decision blocks copied verbatim from
//! CycleCareViewModel.updateCalculations :476-508).
use chrono::NaiveDate;
use cyclecare_core::cycle_math::parse_local_date_iso;
use cyclecare_core::home_anchor::{classify_choices, compute_countdowns, next_period_after};

#[test]
fn countdowns_match_jvm_on_14_cases() {
    let t = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
    let d = |n: i64| Some(t + chrono::Duration::days(n));
    let cases: Vec<(Option<NaiveDate>, Option<i32>, i32, i32, i32, &str)> = vec![
        (d(10), Some(5), 28, 14, 5, "10|14|9"),
        (d(1), Some(28), 28, 14, 5, "1|14|14"),
        (None, None, 28, 14, 5, "0|14|0"),
        (d(3), Some(15), 28, 14, 5, "3|14|27"),
        (d(3), Some(14), 28, 14, 5, "3|14|0"),
        (d(3), Some(16), 28, 14, 5, "3|14|26"),
        (d(20), Some(1), 20, 14, 5, "20|7|6"),
        (d(20), Some(1), 50, 14, 5, "20|31|30"),
        (d(4), Some(2), 28, 8, 3, "4|20|18"),
        (d(4), Some(2), 28, 20, 12, "4|14|12"),
        (d(4), Some(14), 28, 20, 12, "4|14|0"),
        (d(4), Some(15), 28, 20, 12, "4|14|27"),
        (d(100), Some(28), 45, 16, 10, "100|29|1"),
        (d(1), Some(21), 21, 10, 2, "1|11|11"),
    ];
    for (next, day, avg, lut, per, expected) in cases {
        let r = compute_countdowns(t, next, day, avg, lut, per);
        let actual = format!(
            "{}|{}|{}",
            r.days_until_next_period, r.ovulation_cycle_day, r.days_until_ovulation
        );
        assert_eq!(actual, expected, "case {next:?} {day:?} {avg} {lut} {per}");
    }
}

/// Int-overflow family (fixtures/countdowns/adv_jvm_expected.txt rows 1-7):
/// Kotlin Int wrapping + toInt() truncation on malformed inputs.
#[test]
fn countdown_int_overflow_matches_jvm() {
    let t = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
    let d5 = Some(t + chrono::Duration::days(5));
    let cases: Vec<(Option<NaiveDate>, Option<i32>, i32, i32, i32, &str)> = vec![
        (d5, Some(3), 28, i32::MIN, 5, "5|7|4"),
        (d5, Some(3), 28, i32::MAX, 5, "5|7|4"),
        (d5, Some(3), 28, 14, i32::MAX, "5|14|11"),
        (d5, Some(i32::MIN), 28, 14, 5, "5|14|-2147483634"),
        (d5, Some(i32::MAX), 28, 14, 5, "5|14|-2147483605"),
        (NaiveDate::from_ymd_opt(99999999, 1, 1), Some(3), 28, 14, 5, "0|14|11"),
        (NaiveDate::from_ymd_opt(292000000, 1, 1), Some(3), 28, 14, 5, "0|14|11"),
    ];
    for (next, day, avg, lut, per, expected) in cases {
        let r = compute_countdowns(t, next, day, avg, lut, per);
        let actual = format!(
            "{}|{}|{}",
            r.days_until_next_period, r.ovulation_cycle_day, r.days_until_ovulation
        );
        assert_eq!(actual, expected, "overflow case {day:?} {lut} {per}");
    }
}

/// Full updateCalculations anchor chain (:476-498): classify choices ->
/// hasCycleAnchor -> strict parse -> next_period_after while-loop ->
/// countdowns. Composed from the individually probe-locked pieces, replayed
/// against the JVM chain probe (fixtures/countdowns/adv_jvm_expected.txt
/// rows 9-16).
#[test]
fn anchor_chain_matches_jvm() {
    let t = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
    let chain = |last: &str,
                 date_choice: Option<&str>,
                 cl_choice: Option<&str>,
                 avg: i32,
                 lut: i32,
                 per: i32,
                 day: Option<i32>|
     -> String {
        let (unknown_date, unknown_cl, approximate) = classify_choices(date_choice, cl_choice);
        let has_anchor = !last.is_empty() && !unknown_date && !unknown_cl;
        let next = if has_anchor {
            parse_local_date_iso(last).and_then(|last_start| next_period_after(last_start, t, avg))
        } else {
            None
        };
        let cd = compute_countdowns(t, next, if has_anchor { day } else { None }, avg, lut, per);
        format!(
            "{}|{}|{}|{}|{}|{}",
            has_anchor,
            approximate,
            next.map(|d| d.to_string()).unwrap_or_else(|| "null".into()),
            cd.days_until_next_period,
            cd.ovulation_cycle_day,
            cd.days_until_ovulation
        )
    };
    let expected = include_str!("fixtures/countdowns/adv_jvm_expected.txt");
    let mut rows = expected.lines().skip(7); // 7 overflow rows
    assert_eq!(rows.next(), Some("---chain---"));
    let cases: Vec<(&str, Option<&str>, Option<&str>, i32, i32, i32, Option<i32>)> = vec![
        ("2026-10-20", Some("28 days ago"), Some("28"), 28, 14, 5, Some(14)),
        ("2026-10-20", Some("it varies"), Some("28"), 28, 14, 5, None),
        ("2026-10-20", Some("approximate: 3 weeks"), Some("28"), 28, 14, 5, Some(20)),
        ("2026-10-20", Some("today"), Some("I don't know"), 28, 14, 5, None),
        ("not-a-date", Some("28 days ago"), Some("28"), 28, 14, 5, None),
        ("", Some("28 days ago"), Some("28"), 28, 14, 5, None),
        ("2026-11-03", Some("a few days ago"), Some("30"), 21, 14, 5, Some(1)),
        ("2025-01-01", Some("about two weeks ago"), Some("35"), 35, 12, 4, Some(9)),
    ];
    for c in cases {
        let actual = chain(c.0, c.1, c.2, c.3, c.4, c.5, c.6);
        assert_eq!(actual, rows.next().expect("corpus short"), "chain {c:?}");
    }
    assert!(rows.next().is_none());
}
