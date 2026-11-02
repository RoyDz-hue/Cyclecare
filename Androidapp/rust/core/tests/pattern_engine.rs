//! Boundary/adversarial tests for the PatternEngine port. Expectations are
//! hand-computed from the Kotlin source semantics, not from the Rust code.

use chrono::NaiveDate;
use cyclecare_core::cycle_math::CyclePhase;
use cyclecare_core::pattern_engine::*;

fn d(s: &str) -> NaiveDate {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
}

fn settings() -> PatternSettings {
    PatternSettings {
        average_cycle_length: 28,
        period_duration: 5,
        luteal_phase_days: 14,
        last_period_start_date: String::new(),
    }
}

fn log(date: &str, flow: &str, symptoms: &str) -> PatternLog {
    PatternLog {
        date_string: date.to_string(),
        flow_level: flow.to_string(),
        symptoms_csv: symptoms.to_string(),
    }
}

// ---------- compute_baseline ----------

#[test]
fn no_observed_bleeding_falls_back_to_clamped_configured_length() {
    let mut s = settings();
    s.average_cycle_length = 60; // clamps to 45
    let m = compute_baseline(&s, &[log("2026-01-05", "", "")]);
    assert_eq!(m.average_cycle_length, 45.0);
    assert_eq!(m.median_cycle_length, 45);
    assert_eq!(m.min_cycle_length, 45);
    assert_eq!(m.max_cycle_length, 45);
    assert_eq!(m.variability_days, 0);
    assert!(!m.is_regular);
    assert_eq!(m.observed_cycle_intervals, 0);
    assert_eq!(m.total_logged_days, 1);
}

#[test]
fn consecutive_bleeding_days_are_one_episode_even_beyond_window() {
    // 9 consecutive bleeding days with periodDuration 5 (window 5): must NOT
    // split into a fake second start. Then a second episode 28 days later.
    let mut logs = Vec::new();
    for day in 1..=9 {
        logs.push(log(&format!("2026-01-{day:02}"), "HEAVY", ""));
    }
    for day in 29..=31 {
        logs.push(log(&format!("2026-01-{day:02}"), "LIGHT", ""));
    }
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 1);
    assert_eq!(m.average_cycle_length, 28.0);
    assert_eq!(m.min_cycle_length, 28);
    assert_eq!(m.max_cycle_length, 28);
    // single interval: variance 0, not regular (needs >= 2 intervals)
    assert!(!m.is_regular);
}

#[test]
fn gap_beyond_window_with_no_consecutive_day_starts_new_episode() {
    // Two bleeding clusters separated by 30 days, non-consecutive: 2 episodes.
    let logs = vec![
        log("2026-01-01", "MODERATE", ""),
        log("2026-01-03", "MODERATE", ""), // gap of 2 within window 5: same episode
        log("2026-01-31", "MODERATE", ""), // 30 days after episode start 01-01
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 1);
    assert_eq!(m.average_cycle_length, 30.0);
}

#[test]
fn flow_levels_match_case_insensitively_and_spotting_is_not_a_period() {
    let logs = vec![
        log("2026-01-01", "spotting", ""), // not in PERIOD_FLOW_LEVELS
        log("2026-01-10", "heavy", ""),    // lowercase matches
        log("2026-02-07", "Light", ""),    // mixed case matches: 28-day interval
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 1);
    assert_eq!(m.average_cycle_length, 28.0);
}

#[test]
fn intervals_outside_1_to_120_are_filtered() {
    let logs = vec![
        log("2026-01-01", "LIGHT", ""),
        log("2026-06-30", "LIGHT", ""), // 180 days: filtered out
        log("2026-07-28", "LIGHT", ""), // 28 after 06-30: kept
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 1);
    assert_eq!(m.average_cycle_length, 28.0);
}

#[test]
fn stats_use_population_variance_and_kotlin_rounding() {
    // Intervals 26, 28, 30: mean 28, variance (4+0+4)/3 = 2.666.., sd 1.633 -> 2
    let logs = vec![
        log("2026-01-01", "LIGHT", ""),
        log("2026-01-27", "LIGHT", ""), // +26
        log("2026-02-24", "LIGHT", ""), // +28
        log("2026-03-26", "LIGHT", ""), // +30
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 3);
    assert_eq!(m.average_cycle_length, 28.0);
    assert_eq!(m.median_cycle_length, 28);
    assert_eq!(m.min_cycle_length, 26);
    assert_eq!(m.max_cycle_length, 30);
    assert_eq!(m.variability_days, 2);
    // all in 21..=45 and max-min = 4 <= 7, >= 2 intervals -> regular
    assert!(m.is_regular);
}

#[test]
fn even_count_median_averages_middle_pair() {
    // Intervals 26, 28, 30, 32 -> median (28+30)/2 = 29
    let logs = vec![
        log("2026-01-01", "LIGHT", ""),
        log("2026-01-27", "LIGHT", ""), // +26
        log("2026-02-24", "LIGHT", ""), // +28
        log("2026-03-26", "LIGHT", ""), // +30
        log("2026-04-27", "LIGHT", ""), // +32
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.median_cycle_length, 29);
    // max-min = 6 <= 7 -> still regular
    assert!(m.is_regular);
}

#[test]
fn not_regular_when_spread_exceeds_7_days() {
    let logs = vec![
        log("2026-01-01", "LIGHT", ""),
        log("2026-01-25", "LIGHT", ""), // +24
        log("2026-02-28", "LIGHT", ""), // +34 (leap-aware: Jan25->Feb28 = 34)
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 2);
    assert!(!m.is_regular, "max-min = 10 > 7");
}

#[test]
fn configured_start_is_estimate_only_never_an_interval() {
    // One observed bleeding log + configured start: starts non-empty, so the
    // configured start is NOT appended; still zero intervals.
    let mut s = settings();
    s.last_period_start_date = "2025-12-01".to_string();
    let logs = vec![log("2026-01-10", "HEAVY", "")];
    let m = compute_baseline(&s, &logs);
    assert_eq!(m.observed_cycle_intervals, 0);

    // No observed bleeding at all: configured start is the single start,
    // still zero intervals.
    let m2 = compute_baseline(&s, &[log("2026-01-10", "", "")]);
    assert_eq!(m2.observed_cycle_intervals, 0);
    assert_eq!(m2.average_cycle_length, 28.0); // configured fallback
}

#[test]
fn unparseable_log_dates_are_skipped_but_counted_nowhere() {
    let logs = vec![
        log("not-a-date", "HEAVY", ""),
        log("2026-13-40", "HEAVY", ""),
        log("2026-01-01", "HEAVY", ""),
    ];
    let m = compute_baseline(&settings(), &logs);
    assert_eq!(m.observed_cycle_intervals, 0);
    assert_eq!(m.total_logged_days, 1);
}

// ---------- calculate_phase_for_date ----------

#[test]
fn blank_or_unparseable_anchor_is_follicular() {
    assert_eq!(calculate_phase_for_date(d("2026-01-10"), &settings()), CyclePhase::Follicular);
    let mut s = settings();
    s.last_period_start_date = "garbage".to_string();
    assert_eq!(calculate_phase_for_date(d("2026-01-10"), &s), CyclePhase::Follicular);
}

#[test]
fn phase_boundaries_match_source_clamps() {
    // cycleLength 28, period 5, luteal 14: ovulation day 14, fertile 9..=15.
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    assert_eq!(calculate_phase_for_date(d("2026-01-01"), &s), CyclePhase::Menstrual); // day 1
    assert_eq!(calculate_phase_for_date(d("2026-01-05"), &s), CyclePhase::Menstrual); // day 5
    assert_eq!(calculate_phase_for_date(d("2026-01-06"), &s), CyclePhase::Follicular); // day 6
    assert_eq!(calculate_phase_for_date(d("2026-01-08"), &s), CyclePhase::Follicular); // day 8 < 9
    assert_eq!(calculate_phase_for_date(d("2026-01-09"), &s), CyclePhase::Ovulation); // fertile start
    assert_eq!(calculate_phase_for_date(d("2026-01-15"), &s), CyclePhase::Ovulation); // fertile end
    assert_eq!(calculate_phase_for_date(d("2026-01-16"), &s), CyclePhase::Luteal);
    assert_eq!(calculate_phase_for_date(d("2026-01-28"), &s), CyclePhase::Luteal); // day 28
    // wrap: day 29 -> cycle day 1 -> menstrual
    assert_eq!(calculate_phase_for_date(d("2026-01-29"), &s), CyclePhase::Menstrual);
    // pre-anchor: floorMod semantics (day before anchor is cycle day 28)
    assert_eq!(calculate_phase_for_date(d("2025-12-31"), &s), CyclePhase::Luteal);
}

#[test]
fn extreme_settings_clamp_before_phase_math() {
    let mut s = settings();
    s.average_cycle_length = 5; // clamps to 21
    s.period_duration = 40; // clamps to 10
    s.luteal_phase_days = 99; // clamps to 16
    s.last_period_start_date = "2026-01-01".to_string();
    // cycle 21, period 10, luteal 16: ovulation = 21-16=5 clamped to [11,20] -> 11
    // fertile 6..=12
    assert_eq!(calculate_phase_for_date(d("2026-01-10"), &s), CyclePhase::Menstrual); // day 10
    assert_eq!(calculate_phase_for_date(d("2026-01-11"), &s), CyclePhase::Ovulation); // day 11 in 6..=12
    assert_eq!(calculate_phase_for_date(d("2026-01-13"), &s), CyclePhase::Luteal); // day 13
}

// ---------- detect_patterns ----------

#[test]
fn single_occurrence_forms_no_pattern() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    let logs = vec![log("2026-01-03", "", "cramps")];
    assert!(detect_patterns(&s, &logs, d("2026-02-01")).is_empty());
}

#[test]
fn two_recent_occurrences_form_moderate_pattern_with_verbatim_strings() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    // both in menstrual window (days 1..=5)
    let logs = vec![
        log("2026-01-02", "", "cramps"),
        log("2026-01-30", "", "cramps"), // cycle day 2 of next cycle
    ];
    let pats = detect_patterns(&s, &logs, d("2026-02-01"));
    assert_eq!(pats.len(), 1);
    let p = &pats[0];
    assert_eq!(p.id, "pattern_cramps_menstrual");
    assert_eq!(p.title, "Cramps during Menstruation");
    // total 2 (< 3) but recent 2 (>= 2) -> Moderate per source tiers.
    assert_eq!(p.confidence, "Moderate");
    assert_eq!(p.cycle_phase, "Menstruation");
    assert_eq!(
        p.explanation,
        "Inflammatory prostaglandins and shedding uterine tissue frequently correlate with Cramps during menstrual bleeding."
    );
    assert_eq!(
        p.actionable_care,
        "Rest, warm herbal infusions, and light restorative pacing support ease during this phase."
    );
}

#[test]
fn confidence_tiers_match_source_thresholds() {
    let mut s = settings();
    s.last_period_start_date = "2025-06-01".to_string();
    // 5 occurrences, all cycle day 2 (28-day spacing), 2 within 90 days -> High
    let logs = vec![
        log("2025-06-02", "", "cramps"),
        log("2025-06-30", "", "cramps"),
        log("2025-07-28", "", "cramps"),
        log("2025-12-15", "", "cramps"),
        log("2026-01-12", "", "cramps"),
    ];
    let pats = detect_patterns(&s, &logs, d("2026-02-01"));
    assert_eq!(pats.len(), 1);
    assert_eq!(pats[0].confidence, "High");
    // 3 occurrences, all older than 90 days -> Moderate via total >= 3
    let logs3 = &logs[0..3];
    let pats3 = detect_patterns(&s, logs3, d("2026-02-01"));
    assert_eq!(pats3.len(), 1);
    assert_eq!(pats3[0].confidence, "Moderate");
    // 2 occurrences, both older than 90 days -> Emerging
    let mut s2 = settings();
    s2.last_period_start_date = "2025-06-01".to_string();
    let logs2 = &logs[0..2];
    let pats2 = detect_patterns(&s2, logs2, d("2026-02-01"));
    assert_eq!(pats2.len(), 1);
    assert_eq!(pats2[0].confidence, "Emerging");
}

#[test]
fn recency_weighting_prefers_recent_pattern_in_ranking() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    let today = d("2026-02-01");
    // "cramps": two old occurrences; "headache": two recent occurrences.
    let logs = vec![
        log("2025-01-02", "", "cramps"),
        log("2025-01-30", "", "cramps"),
        log("2026-01-02", "", "headache"),
        log("2026-01-30", "", "headache"),
    ];
    let pats = detect_patterns(&s, &logs, today);
    assert_eq!(pats.len(), 2);
    assert_eq!(pats[0].id, "pattern_headache_menstrual");
    // exp(-0.03*30)+exp(-0.03*0) ~ 0.4066+1 = 1.4066 vs ~ 0 for year-old
    assert!(pats[0].supporting_observations.contains("2 in past 3 months"));
    assert_eq!(
        pats[1].supporting_observations,
        "Observed 2 times historically during Menstruation"
    );
}

#[test]
fn more_than_six_patterns_are_truncated_to_top_six() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    let symptoms = ["cramps", "headache", "nausea", "acne", "fatigue", "bloating", "dizziness", "back_pain"];
    let mut logs = Vec::new();
    for (i, sym) in symptoms.iter().enumerate() {
        // Each symptom gets its own cycle-day-2 dates in successive cycles
        // (distinct dates: the source's distinctBy keeps only the first log
        // per date), spaced exactly 28 days so the phase stays menstrual.
        let first = d("2026-01-02") + chrono::Duration::days(84 * i as i64);
        let second = first + chrono::Duration::days(28);
        logs.push(log(&first.to_string(), "", sym));
        logs.push(log(&second.to_string(), "", sym));
    }
    let pats = detect_patterns(&s, &logs, d("2027-10-01"));
    assert_eq!(pats.len(), 6);
    // Ranking is recency-weighted: the most recent symptom leads.
    assert_eq!(pats[0].id, "pattern_back_pain_menstrual");
}

#[test]
fn anomaly_requires_at_least_three_intervals_and_mad_threshold() {
    let mut s = settings();
    s.last_period_start_date = "2025-01-01".to_string();
    // 4 occurrences (3 intervals) evenly spaced then... intervals [28,28,28]:
    // median 28, MAD 0 -> mad > 0 false -> never anomaly.
    let even = vec![
        log("2025-10-02", "", "cramps"),
        log("2025-10-30", "", "cramps"),
        log("2025-11-27", "", "cramps"),
        log("2025-12-25", "", "cramps"),
    ];
    let pats = detect_patterns(&s, &even, d("2026-01-01"));
    assert_eq!(pats.len(), 1);
    // only 3 occurrences (2 intervals) -> no anomaly evaluation at all
    let three = &even[0..3];
    let pats3 = detect_patterns(&s, three, d("2026-01-01"));
    assert_eq!(pats3.len(), 1);
}

#[test]
fn mad_anomaly_flags_last_interval_outlier() {
    // period_duration 10 widens the menstrual window to cycle days 1..=10 so
    // irregular-but-phase-preserving intervals stay in one (symptom, phase)
    // group. Intervals [26,30,26,30,58]: all occurrences land on cycle days
    // 3..=7; median 28, deviations [2,2,2,2,30] -> MAD 2, |58-28| = 30 > 2.5*2.
    let mut s = settings();
    s.period_duration = 10;
    s.last_period_start_date = "2025-01-01".to_string();
    let logs = vec![
        log("2025-01-05", "", "cramps"),
        log("2025-01-31", "", "cramps"), // +26
        log("2025-03-02", "", "cramps"), // +30
        log("2025-03-28", "", "cramps"), // +26
        log("2025-04-27", "", "cramps"), // +30
        log("2025-06-24", "", "cramps"), // +58: outlier
    ];
    let evaluated = evaluate_patterns(&s, &logs, d("2025-07-01"));
    assert_eq!(evaluated.len(), 1);
    assert!(evaluated[0].is_anomaly);
    assert_eq!(evaluated[0].confidence, "High"); // 6 total, 6 recent
    // Control: drop the outlier -> intervals [26,30,26,30], deviations from
    // median 28 are all 2 -> MAD 2, last |30-28| = 2 <= 5 -> NOT anomaly.
    let evaluated2 = evaluate_patterns(&s, &logs[0..5], d("2025-07-01"));
    assert_eq!(evaluated2.len(), 1);
    assert!(!evaluated2[0].is_anomaly);
}

#[test]
fn duplicate_symptom_in_one_log_counts_once_and_first_log_wins_per_date() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    let logs = vec![
        log("2026-01-02", "", "cramps, cramps , cramps"),
        log("2026-01-02", "", "cramps"), // duplicate date: first log wins
        log("2026-01-30", "", "cramps"),
    ];
    let pats = detect_patterns(&s, &logs, d("2026-02-01"));
    assert_eq!(pats.len(), 1);
    assert!(pats[0].supporting_observations.starts_with("Observed 2 times"));
}

#[test]
fn future_dated_occurrence_clamps_weight_but_counts_as_recent() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    let logs = vec![
        log("2026-01-02", "", "cramps"),
        log("2027-01-02", "", "cramps"), // future relative to today
    ];
    let pats = detect_patterns(&s, &logs, d("2026-02-01"));
    assert_eq!(pats.len(), 1);
    // future date: daysAgo negative -> counted in recent window (verbatim)
    assert!(pats[0].supporting_observations.contains("2 in past 3 months"));
}

#[test]
fn symptom_labels_titlecase_first_char_and_replace_underscores() {
    let mut s = settings();
    s.last_period_start_date = "2026-01-01".to_string();
    let logs = vec![
        log("2026-01-02", "", "back_pain"),
        log("2026-01-30", "", "back_pain"),
    ];
    let pats = detect_patterns(&s, &logs, d("2026-02-01"));
    assert_eq!(pats[0].title, "Back pain during Menstruation");
    assert_eq!(pats[0].id, "pattern_back_pain_menstrual");
}

#[test]
fn empty_and_unparseable_logs_yield_no_patterns() {
    let s = settings();
    assert!(detect_patterns(&s, &[], d("2026-02-01")).is_empty());
    let logs = vec![log("garbage", "", "cramps")];
    assert!(detect_patterns(&s, &logs, d("2026-02-01")).is_empty());
    // blank symptom fragments are ignored
    let logs2 = vec![log("2026-01-02", "", " , ,"), log("2026-01-30", "", "")];
    assert!(detect_patterns(&s, &logs2, d("2026-02-01")).is_empty());
}
