//! Baseline statistics + recurring-symptom pattern detection.
//! Port of domain/pattern/PatternEngine.kt (main@31b88f5), including
//! BaselineMetrics/EvaluatedPattern and the verbatim user-facing
//! explanation/care strings. No new health rules: thresholds (21-45 clamp,
//! 1..120 interval filter, 90-day recent window, 0.03/day half-weighting,
//! 2.5*MAD anomaly bound, confidence tiers, top-6) are copied, not re-derived.
//!
//! Clock seam: Kotlin detectPatterns reads LocalDate.now() internally; this
//! port takes `today` from the caller, like the cycle_math ports. Nothing else
//! about the computation differs.

use crate::kotlin_text::{kotlin_trim};
use crate::cycle_math::CyclePhase;
use chrono::NaiveDate;
use std::collections::{HashMap, HashSet};

impl CyclePhase {
    /// Verbatim `title` values from data/model/CycleModels.kt.
    pub fn title(self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "Menstruation",
            CyclePhase::Follicular => "Follicular Phase",
            CyclePhase::Ovulation => "Fertile & Ovulation",
            CyclePhase::Luteal => "Luteal Phase",
        }
    }
    /// Kotlin enum.name.lowercase() for pattern ids.
    fn name_lowercase(self) -> &'static str {
        match self {
            CyclePhase::Menstrual => "menstrual",
            CyclePhase::Follicular => "follicular",
            CyclePhase::Ovulation => "ovulation",
            CyclePhase::Luteal => "luteal",
        }
    }
}

/// Minimal log shape the engine reads (fields verbatim from
/// data/local/CycleDayLog.kt).
#[derive(Debug, Clone)]
pub struct PatternLog {
    pub date_string: String,
    pub flow_level: String,
    pub symptoms_csv: String,
}

/// Settings fields the engine reads (verbatim names from CycleSettings).
#[derive(Debug, Clone)]
pub struct PatternSettings {
    pub average_cycle_length: i32,
    pub period_duration: i32,
    pub luteal_phase_days: i32,
    pub last_period_start_date: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BaselineMetrics {
    pub average_cycle_length: f64,
    pub median_cycle_length: i32,
    pub min_cycle_length: i32,
    pub max_cycle_length: i32,
    pub variability_days: i32,
    pub is_regular: bool,
    pub total_logged_days: i32,
    pub observed_cycle_intervals: i32,
}

/// Kotlin Double.roundToInt, probe-locked (RoundProbe, kotlinc 2.1.0/JRE 21):
/// floor(x + 0.5) = Math.round semantics, ties toward +infinity
/// (2.5->3, -2.5->-2, -1.5->-1). NOT kotlin.math.round (which is ties-to-even:
/// round(2.5)=2). Ops' conflation of the two resolved by execution.
fn kotlin_round_to_int(x: f64) -> i32 {
    (x + 0.5).floor() as i32
}

/// Computes cycle-length statistics from distinct observed bleeding episodes.
pub fn compute_baseline(settings: &PatternSettings, logs: &[PatternLog]) -> BaselineMetrics {
    let starts = observed_period_starts(settings, logs);
    let mut cycle_lengths: Vec<i32> = starts
        .windows(2)
        .map(|w| (w[1] - w[0]).num_days() as i32)
        .filter(|d| (1..=120).contains(d))
        .collect();

    let configured_length = settings.average_cycle_length.clamp(21, 45);
    if cycle_lengths.is_empty() {
        return BaselineMetrics {
            average_cycle_length: configured_length as f64,
            median_cycle_length: configured_length,
            min_cycle_length: configured_length,
            max_cycle_length: configured_length,
            variability_days: 0,
            is_regular: false,
            total_logged_days: valid_log_date_count(logs),
            observed_cycle_intervals: 0,
        };
    }

    cycle_lengths.sort();
    let sorted = &cycle_lengths;
    let average = sorted.iter().map(|v| *v as f64).sum::<f64>() / sorted.len() as f64;
    let n = sorted.len();
    let median = if n % 2 == 1 {
        sorted[n / 2] as f64
    } else {
        (sorted[n / 2 - 1] + sorted[n / 2]) as f64 / 2.0
    };
    let variance = sorted
        .iter()
        .map(|v| {
            let d = *v as f64 - average;
            d * d
        })
        .sum::<f64>()
        / n as f64;
    let variability = kotlin_round_to_int(variance.sqrt());

    BaselineMetrics {
        average_cycle_length: average,
        median_cycle_length: kotlin_round_to_int(median),
        min_cycle_length: sorted[0],
        max_cycle_length: sorted[n - 1],
        variability_days: variability,
        is_regular: n >= 2
            && sorted.iter().all(|v| (21..=45).contains(v))
            && sorted[n - 1] - sorted[0] <= 7,
        total_logged_days: valid_log_date_count(logs),
        observed_cycle_intervals: n as i32,
    }
}

fn observed_period_starts(settings: &PatternSettings, logs: &[PatternLog]) -> Vec<NaiveDate> {
    const PERIOD_FLOW_LEVELS: [&str; 4] = ["LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY"];
    let mut bleeding_days: Vec<NaiveDate> = logs
        .iter()
        .filter(|l| PERIOD_FLOW_LEVELS.contains(&l.flow_level.to_uppercase().as_str()))
        .filter_map(|l| NaiveDate::parse_from_str(&l.date_string, "%Y-%m-%d").ok())
        .collect();
    bleeding_days.sort();
    bleeding_days.dedup();

    let mut starts: Vec<NaiveDate> = Vec::new();
    let mut episode_start: Option<NaiveDate> = None;
    let mut last_bleeding_date: Option<NaiveDate> = None;
    let episode_window = settings.period_duration.clamp(1, 10) as i64;
    for date in bleeding_days {
        // A run of consecutive logged bleeding days is positive evidence of
        // ONE episode - a genuinely long period must never be split into a
        // fake new cycle start. Only a gap beyond the typical-duration
        // window (missing or non-bleeding days - uncertain evidence) lets
        // the window semantics start a new episode, as before.
        let previous_day_was_bleeding =
            last_bleeding_date.map(|d| (date - d).num_days() == 1).unwrap_or(false);
        if episode_start.is_none()
            || ((date - episode_start.unwrap()).num_days() > episode_window
                && !previous_day_was_bleeding)
        {
            starts.push(date);
            episode_start = Some(date);
        }
        last_bleeding_date = Some(date);
    }

    let configured_start = if settings.last_period_start_date.is_empty() {
        None
    } else {
        NaiveDate::parse_from_str(&settings.last_period_start_date, "%Y-%m-%d").ok()
    };
    // A configured start is a setup estimate, not a second observed episode.
    // Never turn one saved bleeding log into a measured cycle interval.
    if let Some(cs) = configured_start {
        if starts.is_empty() {
            starts.push(cs);
        }
    }
    starts.sort();
    starts.dedup();
    starts
}

/// Determines the estimated cycle phase for a specific date based on settings.
/// Blank or unparseable anchor yields FOLLICULAR, as in the source.
/// NOTE: period_duration clamps to 2..=10 here (episode detection above uses
/// 1..=10); the difference is verbatim from the source.
pub fn calculate_phase_for_date(date: NaiveDate, settings: &PatternSettings) -> CyclePhase {
    let last_start = if settings.last_period_start_date.is_empty() {
        None
    } else {
        NaiveDate::parse_from_str(&settings.last_period_start_date, "%Y-%m-%d").ok()
    };
    let Some(last_start) = last_start else {
        return CyclePhase::Follicular;
    };

    let cycle_length = settings.average_cycle_length.clamp(21, 45);
    let period_duration = settings.period_duration.clamp(2, 10);
    let luteal_length = settings.luteal_phase_days.clamp(10, 16);
    let ovulation_day = (cycle_length - luteal_length).clamp(period_duration + 1, cycle_length - 1);
    let fertile_start = (ovulation_day - 5).max(period_duration + 1);
    let fertile_end = (ovulation_day + 1).min(cycle_length);

    let days_diff = (date - last_start).num_days() as i32;
    let cycle_day = days_diff.rem_euclid(cycle_length) + 1;

    if cycle_day <= period_duration {
        CyclePhase::Menstrual
    } else if cycle_day < fertile_start {
        CyclePhase::Follicular
    } else if cycle_day <= fertile_end {
        CyclePhase::Ovulation
    } else {
        CyclePhase::Luteal
    }
}

/// Public in the Kotlin file; exposed so the internal stage can be parity-tested.
#[derive(Debug, Clone)]
pub struct EvaluatedPattern {
    pub symptom_key: String,
    pub phase: CyclePhase,
    pub total_count: i32,
    pub recent_count: i32,
    pub weighted_score: f64,
    pub confidence: &'static str,
    pub is_anomaly: bool,
}

/// Verbatim output shape from data/model/CycleModels.kt HealthPattern.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HealthPattern {
    pub id: String,
    pub title: String,
    pub supporting_observations: String,
    pub confidence: String,
    pub cycle_phase: String,
    pub explanation: String,
    pub actionable_care: String,
}

/// Spec sections 17-20: phase correlation, exponential recency weighting
/// (half-life ~23 days), all-time vs 90-day window, MAD anomaly detection,
/// multi-factor confidence. `today` replaces the source's LocalDate.now().
/// The evaluation stage of detectPatterns (grouping, weighting, MAD, confidence),
/// before the top-6 truncation and HealthPattern mapping.
pub fn evaluate_patterns(
    settings: &PatternSettings,
    logs: &[PatternLog],
    today: NaiveDate,
) -> Vec<EvaluatedPattern> {
    // distinctBy date keeps the FIRST occurrence in list order.
    let mut seen_dates: HashSet<NaiveDate> = HashSet::new();
    let dated_logs: Vec<(NaiveDate, &PatternLog)> = logs
        .iter()
        .filter_map(|l| NaiveDate::parse_from_str(&l.date_string, "%Y-%m-%d").ok().map(|d| (d, l)))
        .filter(|(d, _)| seen_dates.insert(*d))
        .collect();
    if dated_logs.is_empty() {
        return Vec::new();
    }

    // Group by (symptom, phase), preserving first-occurrence insertion order
    // (Kotlin groupBy iteration order; matters for stable-sort ties).
    let mut group_order: Vec<(String, CyclePhase)> = Vec::new();
    let mut groups: HashMap<(String, CyclePhase), Vec<(NaiveDate,)>> = HashMap::new();
    for (date, log) in &dated_logs {
        let phase = calculate_phase_for_date(*date, settings);
        let mut seen_symptoms: HashSet<String> = HashSet::new();
        for symptom in log
            .symptoms_csv
            .split(',')
            .map(|s| kotlin_trim(s))
            .filter(|s| !s.is_empty())
        {
            if !seen_symptoms.insert(symptom.to_string()) {
                continue;
            }
            let key = (symptom.to_string(), phase);
            if !groups.contains_key(&key) {
                group_order.push(key.clone());
                groups.insert(key.clone(), Vec::new());
            }
            groups.get_mut(&key).unwrap().push((*date,));
        }
    }
    if group_order.is_empty() {
        return Vec::new();
    }

    let mut evaluated: Vec<EvaluatedPattern> = Vec::new();
    for key in &group_order {
        let list = &groups[key];
        let total_count = list.len() as i32;
        if total_count < 2 {
            continue; // Minimum 2 occurrences to form a pattern
        }

        // Recency weighting: weight = exp(-0.03 * daysAgo), daysAgo clamped >= 0.
        let weighted_score: f64 = list
            .iter()
            .map(|(d,)| {
                let days_ago = (today - *d).num_days().max(0) as f64;
                (-0.03 * days_ago).exp()
            })
            .sum();

        // NOTE: no lower clamp here, verbatim - a future-dated occurrence
        // (negative daysAgo) counts as recent, matching the source.
        let recent_count = list
            .iter()
            .filter(|(d,)| (today - *d).num_days() <= 90)
            .count() as i32;

        // Anomaly detection via MAD over intervals.
        let mut sorted_dates: Vec<NaiveDate> = list.iter().map(|(d,)| *d).collect();
        sorted_dates.sort();
        let intervals: Vec<f64> = sorted_dates
            .windows(2)
            .map(|w| (w[1] - w[0]).num_days() as f64)
            .collect();
        let is_anomaly = if intervals.len() >= 3 {
            let median_interval = compute_median(&intervals);
            let deviations: Vec<f64> = intervals.iter().map(|i| (i - median_interval).abs()).collect();
            let mad = compute_median(&deviations);
            let last_interval = (sorted_dates[sorted_dates.len() - 1]
                - sorted_dates[sorted_dates.len() - 2])
            .num_days() as f64;
            mad > 0.0 && (last_interval - median_interval).abs() > 2.5 * mad
        } else {
            false
        };

        let confidence = if total_count >= 5 && recent_count >= 2 {
            "High"
        } else if total_count >= 3 || recent_count >= 2 {
            "Moderate"
        } else {
            "Emerging"
        };

        evaluated.push(EvaluatedPattern {
            symptom_key: key.0.clone(),
            phase: key.1,
            total_count,
            recent_count,
            weighted_score,
            confidence,
            is_anomaly,
        });
    }

    evaluated
}

/// Full source behavior: evaluate, stable descending sort by weighted score,
/// truncate to top 6, map to user-facing HealthPattern with verbatim strings.
pub fn detect_patterns(
    settings: &PatternSettings,
    logs: &[PatternLog],
    today: NaiveDate,
) -> Vec<HealthPattern> {
    let mut evaluated = evaluate_patterns(settings, logs, today);
    evaluated.sort_by(|a, b| {
        b.weighted_score
            .partial_cmp(&a.weighted_score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    evaluated.truncate(6);

    evaluated
        .into_iter()
        .map(|ep| {
            // label: replace('_',' ') then titlecase the FIRST character only.
            let spaced = ep.symptom_key.replace('_', " ");
            let mut chars = spaced.chars();
            let label = match chars.next() {
                Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
                None => String::new(),
            };
            let phase_title = ep.phase.title();

            let explanation = match ep.phase {
                CyclePhase::Luteal => format!("Progesterone fluctuations before menstruation commonly accompany recurring {label} in your luteal phase."),
                CyclePhase::Menstrual => format!("Inflammatory prostaglandins and shedding uterine tissue frequently correlate with {label} during menstrual bleeding."),
                CyclePhase::Ovulation => format!("The rapid LH surge and estrogen peak around ovulation may trigger {label}."),
                CyclePhase::Follicular => format!("Rising follicular estrogen and metabolic activity frequently align with {label}."),
            };
            let care_advice = match ep.phase {
                CyclePhase::Luteal => "Ensure magnesium-rich nourishment, gentle hydration, and warm compresses for comfort.",
                CyclePhase::Menstrual => "Rest, warm herbal infusions, and light restorative pacing support ease during this phase.",
                CyclePhase::Ovulation => "Stay hydrated and observe whether this recurring signal assists your natural body awareness.",
                CyclePhase::Follicular => "Support rising vitality with balanced nutrition, hydration, and active movement.",
            };
            let observations_text = if ep.recent_count > 0 {
                format!(
                    "Observed {} times ({} in past 3 months) during {}",
                    ep.total_count, ep.recent_count, phase_title
                )
            } else {
                format!("Observed {} times historically during {}", ep.total_count, phase_title)
            };

            HealthPattern {
                id: format!(
                    "pattern_{}_{}",
                    ep.symptom_key.to_lowercase(),
                    ep.phase.name_lowercase()
                ),
                title: format!("{label} during {phase_title}"),
                supporting_observations: observations_text,
                confidence: ep.confidence.to_string(),
                cycle_phase: phase_title.to_string(),
                explanation,
                actionable_care: care_advice.to_string(),
            }
        })
        .collect()
}

fn compute_median(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let n = sorted.len();
    if n % 2 == 1 {
        sorted[n / 2]
    } else {
        (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

fn valid_log_date_count(logs: &[PatternLog]) -> i32 {
    let mut seen: HashSet<NaiveDate> = HashSet::new();
    logs.iter()
        .filter_map(|l| NaiveDate::parse_from_str(&l.date_string, "%Y-%m-%d").ok())
        .for_each(|d| {
            seen.insert(d);
        });
    seen.len() as i32
}
