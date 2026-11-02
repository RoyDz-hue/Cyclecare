//! "Something Changed" engine (Spec section 21).
//! Port of domain/pattern/SomethingChangedEngine.kt (main@31b88f5).
//! Compares the recent 90-day window against the historical baseline for pain
//! intensity and per-symptom frequency shifts. Thresholds (14-log minimum,
//! 90-day split, 1.5/2.5/3.5 pain deltas, 1.6x/2.0x/2.5x rate ratios) are
//! copied verbatim, not re-derived. Clock seam: caller supplies `today`.
use crate::kotlin_text::{kotlin_trim};

use chrono::NaiveDate;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum DeviationSeverity {
    Notable,
    Significant,
    Strong,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BaselineDeviation {
    pub factor_key: String,
    pub title: String,
    pub baseline_value: f64,
    pub recent_value: f64,
    pub delta_percent: f64,
    pub severity: DeviationSeverity,
    pub message: String,
}

/// Log fields this engine reads (verbatim names from CycleDayLog).
#[derive(Debug, Clone)]
pub struct ChangeLog {
    pub date_string: String,
    pub symptoms_csv: String,
    pub pain_intensity: i32,
}

pub fn detect_deviations(logs: &[ChangeLog], today: NaiveDate) -> Vec<BaselineDeviation> {
    // distinctBy date keeps the FIRST occurrence; then sorted by date.
    let mut seen = std::collections::HashSet::new();
    let mut dated_logs: Vec<(NaiveDate, &ChangeLog)> = logs
        .iter()
        .filter_map(|l| NaiveDate::parse_from_str(&l.date_string, "%Y-%m-%d").ok().map(|d| (d, l)))
        .filter(|(d, _)| seen.insert(*d))
        .collect();
    dated_logs.sort_by_key(|(d, _)| *d);

    // Require at least 2 distinct cycles/months of data to compute baseline.
    if dated_logs.len() < 14 {
        return Vec::new();
    }

    let recent_logs: Vec<&(NaiveDate, &ChangeLog)> = dated_logs
        .iter()
        .filter(|(d, _)| (today - *d).num_days() <= 90)
        .collect();
    let historical_logs: Vec<&(NaiveDate, &ChangeLog)> = dated_logs
        .iter()
        .filter(|(d, _)| (today - *d).num_days() > 90)
        .collect();
    if recent_logs.is_empty() || historical_logs.is_empty() {
        return Vec::new();
    }

    let mut deviations: Vec<BaselineDeviation> = Vec::new();

    // 1. Pain intensity deviation.
    let historical_pain: Vec<i32> = historical_logs
        .iter()
        .map(|(_, l)| l.pain_intensity)
        .filter(|p| *p > 0)
        .collect();
    let recent_pain: Vec<i32> = recent_logs
        .iter()
        .map(|(_, l)| l.pain_intensity)
        .filter(|p| *p > 0)
        .collect();
    if !historical_pain.is_empty() && !recent_pain.is_empty() {
        let hist_avg = historical_pain.iter().sum::<i32>() as f64 / historical_pain.len() as f64;
        let rec_avg = recent_pain.iter().sum::<i32>() as f64 / recent_pain.len() as f64;
        let diff = rec_avg - hist_avg;
        if diff >= 1.5 {
            let delta_pct = (diff / hist_avg) * 100.0;
            let severity = if diff >= 3.5 {
                DeviationSeverity::Strong
            } else if diff >= 2.5 {
                DeviationSeverity::Significant
            } else {
                DeviationSeverity::Notable
            };
            deviations.push(BaselineDeviation {
                factor_key: "pain_intensity".to_string(),
                title: "Pelvic Comfort Shift".to_string(),
                baseline_value: hist_avg,
                recent_value: rec_avg,
                delta_percent: delta_pct,
                severity,
                message: "Your logged discomfort levels recently averaged higher than your previous baseline.".to_string(),
            });
        }
    }

    // 2. Symptom frequency shifts.
    fn split_symptoms(csv: &str) -> Vec<&str> {
        csv.split(',').map(|s| kotlin_trim(s)).filter(|s| !s.is_empty()).collect()
    }
    let hist_symptoms: Vec<&str> = historical_logs
        .iter()
        .flat_map(|(_, l)| split_symptoms(&l.symptoms_csv))
        .collect();
    let rec_symptoms: Vec<&str> = recent_logs
        .iter()
        .flat_map(|(_, l)| split_symptoms(&l.symptoms_csv))
        .collect();

    // (histSymptoms + recSymptoms).distinct() preserves first-seen order.
    let mut all_keys: Vec<&str> = Vec::new();
    for s in hist_symptoms.iter().chain(rec_symptoms.iter()) {
        if !all_keys.contains(s) {
            all_keys.push(s);
        }
    }

    for sym in all_keys {
        let hist_rate = hist_symptoms.iter().filter(|s| **s == sym).count() as f64
            / historical_logs.len() as f64;
        let rec_rate = rec_symptoms.iter().filter(|s| **s == sym).count() as f64
            / recent_logs.len() as f64;
        if hist_rate > 0.05 && rec_rate > hist_rate * 1.6 {
            let delta_pct = ((rec_rate - hist_rate) / hist_rate) * 100.0;
            let severity = if rec_rate >= hist_rate * 2.5 {
                DeviationSeverity::Strong
            } else if rec_rate >= hist_rate * 2.0 {
                DeviationSeverity::Significant
            } else {
                DeviationSeverity::Notable
            };
            let spaced = sym.replace('_', " ");
            let mut chars = spaced.chars();
            let label = match chars.next() {
                Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
                None => String::new(),
            };
            deviations.push(BaselineDeviation {
                factor_key: sym.to_string(),
                title: format!("{label} Frequency Shift"),
                baseline_value: hist_rate * 100.0,
                recent_value: rec_rate * 100.0,
                delta_percent: delta_pct,
                severity,
                message: format!("You have recorded {label} noticeably more often over your last few cycles."),
            });
        }
    }

    // Stable descending sort by severity (enum ordinal order in source).
    deviations.sort_by(|a, b| b.severity.cmp(&a.severity));
    deviations
}
