//! SomethingChangedEngine port tests; expectations derived from source rules.

use chrono::NaiveDate;
use cyclecare_core::something_changed::*;

fn d(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}

fn cl(date: &str, symptoms: &str, pain: i32) -> ChangeLog {
    ChangeLog {
        date_string: date.to_string(),
        symptoms_csv: symptoms.to_string(),
        pain_intensity: pain,
    }
}

/// 20 historical logs (120-30 days ago) + 5 recent logs, builder helper.
fn two_windows(hist: &[(i64, &str, i32)], recent: &[(i64, &str, i32)], today: NaiveDate) -> Vec<ChangeLog> {
    let mut v = Vec::new();
    for (i, (offset, sym, pain)) in hist.iter().enumerate() {
        let date = today - chrono::Duration::days(*offset);
        // unique dates guaranteed by caller offsets; add minute guard via index
        let _ = i;
        v.push(cl(&date.to_string(), sym, *pain));
    }
    for (offset, sym, pain) in recent {
        let date = today - chrono::Duration::days(*offset);
        v.push(cl(&date.to_string(), sym, *pain));
    }
    v
}

#[test]
fn fewer_than_14_distinct_dates_yields_nothing() {
    let today = d("2026-02-01");
    let logs = vec![cl("2025-11-01", "cramps", 5), cl("2026-01-15", "cramps", 8)];
    assert!(detect_deviations(&logs, today).is_empty());
    // 13 dates still nothing
    let mut many = Vec::new();
    for i in 0..13i64 {
        let date = today - chrono::Duration::days(10 + i * 5);
        many.push(cl(&date.to_string(), "cramps", 5));
    }
    assert!(detect_deviations(&many, today).is_empty());
}

#[test]
fn empty_recent_or_historical_window_yields_nothing() {
    let today = d("2026-02-01");
    // 20 logs all within 90 days: no historical window
    let mut logs = Vec::new();
    for i in 0..20i64 {
        let date = today - chrono::Duration::days(i * 4);
        logs.push(cl(&date.to_string(), "cramps", 5));
    }
    assert!(detect_deviations(&logs, today).is_empty());
}

#[test]
fn pain_delta_thresholds_map_to_severity_bands() {
    let today = d("2026-02-01");
    // historical: 15 logs pain 2 (avg 2); recent: 5 logs pain 4 (diff 2.0 -> NOTABLE)
    let hist: Vec<(i64, &str, i32)> = (0..15).map(|i| (100 + i * 3, "", 2)).collect();
    let recent: Vec<(i64, &str, i32)> = (0..5).map(|i| (5 + i * 10, "", 4)).collect();
    let logs = two_windows(&hist, &recent, today);
    let devs = detect_deviations(&logs, today);
    assert_eq!(devs.len(), 1);
    assert_eq!(devs[0].factor_key, "pain_intensity");
    assert_eq!(devs[0].title, "Pelvic Comfort Shift");
    assert_eq!(devs[0].severity, DeviationSeverity::Notable);
    assert!((devs[0].baseline_value - 2.0).abs() < 1e-9);
    assert!((devs[0].recent_value - 4.0).abs() < 1e-9);
    assert!((devs[0].delta_percent - 100.0).abs() < 1e-9);
}

#[test]
fn pain_diff_below_1_5_is_silent() {
    let today = d("2026-02-01");
    let hist: Vec<(i64, &str, i32)> = (0..15).map(|i| (100 + i * 3, "", 3)).collect();
    let recent: Vec<(i64, &str, i32)> = (0..5).map(|i| (5 + i * 10, "", 4)).collect();
    let logs = two_windows(&hist, &recent, today);
    assert!(detect_deviations(&logs, today).is_empty());
}

#[test]
fn pain_strong_at_3_5_and_significant_at_2_5() {
    let today = d("2026-02-01");
    let mk = |recent_pain: i32| {
        let hist: Vec<(i64, &str, i32)> = (0..15).map(|i| (100 + i * 3, "", 2)).collect();
        let recent: Vec<(i64, &str, i32)> = (0..5).map(|i| (5 + i * 10, "", recent_pain)).collect();
        detect_deviations(&two_windows(&hist, &recent, today), today)
    };
    assert_eq!(mk(5)[0].severity, DeviationSeverity::Significant); // diff 3.0... wait 5-2=3 -> SIGNIFICANT (>=2.5)
    assert_eq!(mk(6)[0].severity, DeviationSeverity::Strong); // diff 4 >= 3.5
}

#[test]
fn symptom_frequency_shift_ratios_and_labels() {
    let today = d("2026-02-01");
    // historical: 20 logs, cramps on 2 (rate 0.1); recent: 5 logs, cramps on 2 (rate 0.4 = 4x -> STRONG)
    let hist: Vec<(i64, &str, i32)> = (0..20)
        .map(|i| (100 + i * 3, if i < 2 { "cramps" } else { "" }, 0))
        .collect();
    let recent: Vec<(i64, &str, i32)> = vec![
        (5, "back_pain", 0),
        (15, "cramps", 0),
        (25, "cramps", 0),
        (35, "", 0),
        (45, "", 0),
    ];
    let logs = two_windows(&hist, &recent, today);
    let devs = detect_deviations(&logs, today);
    assert_eq!(devs.len(), 1);
    let dev = &devs[0];
    assert_eq!(dev.factor_key, "cramps");
    assert_eq!(dev.title, "Cramps Frequency Shift");
    assert_eq!(dev.severity, DeviationSeverity::Strong);
    assert_eq!(dev.message, "You have recorded Cramps noticeably more often over your last few cycles.");
}

#[test]
fn symptom_below_5_percent_historical_rate_is_ignored() {
    let today = d("2026-02-01");
    // historical 20 logs, cramps once -> rate 0.05, NOT > 0.05 -> no deviation
    let hist: Vec<(i64, &str, i32)> = (0..20)
        .map(|i| (100 + i * 3, if i == 0 { "cramps" } else { "" }, 0))
        .collect();
    let recent: Vec<(i64, &str, i32)> = (0..5).map(|i| (5 + i * 10, "cramps", 0)).collect();
    let logs = two_windows(&hist, &recent, today);
    assert!(detect_deviations(&logs, today).is_empty());
}

#[test]
fn severity_sort_is_strong_first() {
    let today = d("2026-02-01");
    // cramps -> STRONG (4x), headache -> NOTABLE (1.8x)
    let hist: Vec<(i64, &str, i32)> = (0..20)
        .map(|i| {
            (100 + i * 3, if i < 2 { "cramps,headache" } else { "" }, 0)
        })
        .collect();
    let recent: Vec<(i64, &str, i32)> = vec![
        (5, "cramps,headache", 0),
        (15, "cramps,headache", 0),
        (25, "cramps", 0),
        (35, "cramps", 0),
        (45, "", 0),
    ];
    // headache: hist rate 0.1, rec rate 0.4? also 2/5=0.4 -> STRONG too.
    // Adjust: headache recent only once -> 0.2 = 2.0x -> SIGNIFICANT.
    let recent2: Vec<(i64, &str, i32)> = vec![
        (5, "cramps,headache", 0),
        (15, "cramps", 0),
        (25, "cramps", 0),
        (35, "cramps", 0),
        (45, "", 0),
    ];
    let logs = two_windows(&hist, &recent2, today);
    let devs = detect_deviations(&logs, today);
    assert_eq!(devs.len(), 2);
    assert_eq!(devs[0].severity, DeviationSeverity::Strong);
    assert_eq!(devs[0].factor_key, "cramps");
    assert_eq!(devs[1].severity, DeviationSeverity::Significant);
    assert_eq!(devs[1].factor_key, "headache");
}

#[test]
fn future_dated_logs_are_recent_verbatim() {
    // days between date and today negative -> <= 90 -> recent window (verbatim,
    // same asymmetric clamp family as PatternEngine recentCount).
    let today = d("2026-02-01");
    let hist: Vec<(i64, &str, i32)> = (0..15).map(|i| (100 + i * 3, "", 2)).collect();
    let mut logs = two_windows(&hist, &[], today);
    for i in 0..5i64 {
        let date = today + chrono::Duration::days(10 + i * 30); // future
        logs.push(cl(&date.to_string(), "", 6));
    }
    let devs = detect_deviations(&logs, today);
    assert_eq!(devs.len(), 1);
    assert_eq!(devs[0].factor_key, "pain_intensity");
}
