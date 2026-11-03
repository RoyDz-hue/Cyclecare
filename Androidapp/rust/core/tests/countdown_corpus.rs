//! JVM-executed oracle for home_anchor::compute_countdowns (Probe.kt in
//! fixtures/settings_core style; executed on kotlinc 2.1.0 / JDK 17.0.21+9
//! with the decision block copied verbatim from
//! CycleCareViewModel.updateCalculations :491-508).
use cyclecare_core::home_anchor::compute_countdowns;
use chrono::NaiveDate;

#[test]
fn countdowns_match_jvm_on_14_cases() {
    let t = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
    let d = |n: i64| Some(t + chrono::Duration::days(n));
    // (nextPeriod, dayOfCycle, avgCycle, luteal, period, expected)
    let cases: Vec<(Option<NaiveDate>, Option<i64>, i64, i64, i64, &str)> = vec![
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
