//! Port of OnboardingViewModel.kt's OnboardingUiState cycle/date computed
//! properties (main@31b88f5): periodStartChoice, effectiveLastPeriodDate,
//! effectivePeriodEndDate, daysSinceLastPeriod, dayOfCycle,
//! isPeriodActiveToday, currentPhaseName, isPeriodDatePairValid,
//! hasCycleStartEstimate.
//!
//! Corpus: 15 rows (d01-d15) executed 2026-11-02 (today = 2026-11-02)
//! against the verbatim Kotlin getters (ObCycleProbe, kotlinc/JVM).
//! `today` is a parameter (host passes LocalDate.now()).

use chrono::{Datelike, NaiveDate};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObCycleInput {
    pub selected_exact_date: Option<NaiveDate>,
    pub selected_approximate_chip: Option<String>,
    pub selected_period_end_date: Option<NaiveDate>,
    pub period_duration_days: i64,
    pub is_period_ongoing: bool,
    pub cycle_length_days: i64,
}

impl Default for ObCycleInput {
    fn default() -> Self {
        ObCycleInput {
            selected_exact_date: None,
            selected_approximate_chip: None,
            selected_period_end_date: None,
            period_duration_days: 5,
            is_period_ongoing: false,
            cycle_length_days: 28,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObCycleResult {
    pub period_start_choice: String,
    pub effective_last_period_date: NaiveDate,
    pub effective_period_end_date: NaiveDate,
    pub days_since_last_period: i64,
    pub day_of_cycle: i64,
    pub is_period_active_today: bool,
    pub current_phase_name: &'static str,
    pub is_period_date_pair_valid: bool,
    pub has_cycle_start_estimate: bool,
}

/// Kotlin isUnknownDateChoice: null/blank, or either "I don't know" variant,
/// ignoreCase (Unicode simple mapping — the strings are ASCII + U+2019).
fn is_unknown_date_choice(choice: Option<&str>) -> bool {
    let Some(c) = choice else { return true };
    if c.trim().is_empty() {
        return true;
    }
    c.eq_ignore_ascii_case("I don\u{2019}t know") || c.eq_ignore_ascii_case("It varies / I don\u{2019}t know")
}

pub fn has_cycle_start_estimate(input: &ObCycleInput) -> bool {
    input.selected_exact_date.is_some()
        || (input.selected_approximate_chip.is_some()
            && !is_unknown_date_choice(input.selected_approximate_chip.as_deref()))
}

pub fn compute(input: &ObCycleInput, today: NaiveDate) -> ObCycleResult {
    let has_estimate = has_cycle_start_estimate(input);
    let period_start_choice = if input.selected_exact_date.is_some() {
        "EXACT".to_string()
    } else if !has_estimate {
        "UNKNOWN".to_string()
    } else {
        format!("APPROXIMATE:{}", input.selected_approximate_chip.as_deref().unwrap_or(""))
    };

    let effective_last = if let Some(d) = input.selected_exact_date {
        d
    } else {
        let days_ago: i64 = match input.selected_approximate_chip.as_deref() {
            Some("Today") => 0,
            Some("A few days ago") => 4,
            Some("About a week ago") => 7,
            Some("About two weeks ago") => 14,
            Some("It varies / I don\u{2019}t know") | Some("I don\u{2019}t know") => 0,
            _ => 11,
        };
        today - chrono::Duration::days(days_ago)
    };

    let effective_end = if let Some(d) = input.selected_period_end_date {
        d
    } else if input.is_period_ongoing {
        today
    } else {
        effective_last + chrono::Duration::days((input.period_duration_days - 1).max(0))
    };

    let days_since = (today - effective_last).num_days().max(0);
    let day_of_cycle = if has_estimate { (days_since % input.cycle_length_days) + 1 } else { 0 };
    let is_period_active =
        has_estimate && (input.is_period_ongoing || (day_of_cycle <= input.period_duration_days));
    let phase: &'static str = if !has_estimate {
        "No estimate yet"
    } else if is_period_active {
        "Menstrual Phase"
    } else if day_of_cycle < (input.cycle_length_days - 14) - 4 {
        "Follicular Phase"
    } else if ((input.cycle_length_days - 14) - 4..=(input.cycle_length_days - 14) + 1)
        .contains(&day_of_cycle)
    {
        "Estimated fertile window"
    } else {
        "Luteal Phase"
    };
    let pair_valid = effective_end >= effective_last;

    ObCycleResult {
        period_start_choice,
        effective_last_period_date: effective_last,
        effective_period_end_date: effective_end,
        days_since_last_period: days_since,
        day_of_cycle,
        is_period_active_today: is_period_active,
        current_phase_name: phase,
        is_period_date_pair_valid: pair_valid,
        has_cycle_start_estimate: has_estimate,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        NaiveDate::from_ymd_opt(
            s[0..4].parse().unwrap(),
            s[5..7].parse().unwrap(),
            s[8..10].parse().unwrap(),
        )
        .unwrap()
    }

    /// 15 rows executed against the verbatim Kotlin getters on 2026-11-02.
    #[test]
    fn matches_kotlin_corpus() {
        let today = d("2026-11-02");
        let base = ObCycleInput::default();
        // (id, input-delta, expected: choice, last, end, since, day, active, phase, pair, est)
        let rows: Vec<(&str, ObCycleInput, (&str, &str, &str, i64, i64, bool, &str, bool, bool))> = vec![
            ("d01", ObCycleInput { ..base.clone() }, ("UNKNOWN", "2026-10-22", "2026-10-26", 11, 0, false, "No estimate yet", true, false)),
            ("d02", ObCycleInput { selected_approximate_chip: Some("Today".into()), ..base.clone() }, ("APPROXIMATE:Today", "2026-11-02", "2026-11-06", 0, 1, true, "Menstrual Phase", true, true)),
            ("d03", ObCycleInput { selected_approximate_chip: Some("A few days ago".into()), ..base.clone() }, ("APPROXIMATE:A few days ago", "2026-10-29", "2026-11-02", 4, 5, true, "Menstrual Phase", true, true)),
            ("d04", ObCycleInput { selected_approximate_chip: Some("About a week ago".into()), ..base.clone() }, ("APPROXIMATE:About a week ago", "2026-10-26", "2026-10-30", 7, 8, false, "Follicular Phase", true, true)),
            ("d05", ObCycleInput { selected_approximate_chip: Some("About two weeks ago".into()), ..base.clone() }, ("APPROXIMATE:About two weeks ago", "2026-10-19", "2026-10-23", 14, 15, false, "Estimated fertile window", true, true)),
            ("d06", ObCycleInput { selected_approximate_chip: Some("I don\u{2019}t know".into()), ..base.clone() }, ("UNKNOWN", "2026-11-02", "2026-11-06", 0, 0, false, "No estimate yet", true, false)),
            ("d07", ObCycleInput { selected_approximate_chip: Some("It varies / I don\u{2019}t know".into()), ..base.clone() }, ("UNKNOWN", "2026-11-02", "2026-11-06", 0, 0, false, "No estimate yet", true, false)),
            ("d08", ObCycleInput { selected_exact_date: Some(d("2026-10-31")), ..base.clone() }, ("EXACT", "2026-10-31", "2026-11-04", 2, 3, true, "Menstrual Phase", true, true)),
            ("d09", ObCycleInput { selected_exact_date: Some(d("2026-10-23")), ..base.clone() }, ("EXACT", "2026-10-23", "2026-10-27", 10, 11, false, "Estimated fertile window", true, true)),
            ("d10", ObCycleInput { selected_exact_date: Some(d("2026-10-13")), cycle_length_days: 32, ..base.clone() }, ("EXACT", "2026-10-13", "2026-10-17", 20, 21, false, "Luteal Phase", true, true)),
            ("d11", ObCycleInput { selected_approximate_chip: Some("Today".into()), ..base.clone() }, ("APPROXIMATE:Today", "2026-11-02", "2026-11-06", 0, 1, true, "Menstrual Phase", true, true)),
            ("d12", ObCycleInput { selected_approximate_chip: Some("A few days ago".into()), is_period_ongoing: true, ..base.clone() }, ("APPROXIMATE:A few days ago", "2026-10-29", "2026-11-02", 4, 5, true, "Menstrual Phase", true, true)),
            ("d13", ObCycleInput { selected_exact_date: Some(d("2026-10-30")), selected_period_end_date: Some(d("2026-10-28")), ..base.clone() }, ("EXACT", "2026-10-30", "2026-10-28", 3, 4, true, "Menstrual Phase", false, true)),
            ("d14", ObCycleInput { selected_approximate_chip: Some("About two weeks ago".into()), cycle_length_days: 21, ..base.clone() }, ("APPROXIMATE:About two weeks ago", "2026-10-19", "2026-10-23", 14, 15, false, "Luteal Phase", true, true)),
            ("d15", ObCycleInput { selected_approximate_chip: Some("About two weeks ago".into()), cycle_length_days: 40, ..base.clone() }, ("APPROXIMATE:About two weeks ago", "2026-10-19", "2026-10-23", 14, 15, false, "Follicular Phase", true, true)),
        ];
        assert_eq!(rows.len(), 15);
        for (id, input, exp) in rows {
            let r = compute(&input, today);
            assert_eq!(
                (
                    r.period_start_choice.as_str(),
                    r.effective_last_period_date.to_string().as_str(),
                    r.effective_period_end_date.to_string().as_str(),
                    r.days_since_last_period,
                    r.day_of_cycle,
                    r.is_period_active_today,
                    r.current_phase_name,
                    r.is_period_date_pair_valid,
                    r.has_cycle_start_estimate,
                ),
                exp,
                "{id}"
            );
        }
    }
}
